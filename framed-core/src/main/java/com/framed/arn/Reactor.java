// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.utils.MonotonicClock;
import org.json.JSONObject;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.framed.arn.RuleUtils.extractTag;

/**
 * A reactive multi-input {@link Service} that evaluates a set of firing rules on incoming
 * messages, constructs exactly one consistent snapshot per evaluation cycle, and triggers a
 * user-defined {@link #reactionFunction(Map)} once whenever at least one rule is satisfied.
 *
 * <p>Concrete reactors extend this class, supply their firing rules and input/output channels
 * via the constructor, and implement {@link #reactionFunction(Map)} to compute and publish
 * derived results via {@link RuleUtils#publishResult}, with the firing's output tag
 * ({@link #OUTPUT_TAG_KEY}).</p>
 *
 * <h2>Core semantics</h2>
 * <ul>
 *   <li><b>Tags.</b> Every message carries a {@link Tag} {@code (t, m)}, read by
 *       {@link RuleUtils#extractTag(Object)}; tags are ordered lexicographically. Device and
 *       parser messages carry {@code m = 0}.</li>
 *
 *   <li><b>Logical-time gate (per input channel).</b> A message on channel {@code C} is
 *       <em>accepted</em> only if its tag is strictly greater than the last tag accepted on
 *       {@code C} by this reactor. Otherwise (a duplicate, an older message, or a message without
 *       timestamp) it is <em>discarded</em>: the held value and tag of {@code C}, the sequence
 *       counter {@code seq(C)} and all rule pointers stay unchanged, no rule is evaluated, and the
 *       message is recorded as {@link FiringRecord.Outcome#DISCARDED}.</li>
 *
 *   <li><b>Sequence counters.</b> {@code seq(C)} counts the messages accepted on {@code C}. It is
 *       never reset.</li>
 *
 *   <li><b>Rules.</b> Each rule is a map of <code>channel → token</code>; a rule is satisfied if
 *       every channel in it satisfies its condition (AND). With
 *       {@code delta = seq(C) − lastSeq[rule][C]}:
 *     <ul>
 *       <li><b>"*"</b> = ANY: {@code delta >= 1}</li>
 *       <li><b>"N"</b> = AT_LEAST(N): {@code delta >= N}</li>
 *       <li><b>"r:v"</b> = REQUIRE_VALUE(v): {@code delta >= 1} and the latest value matches
 *           {@code v} ({@link #valueMatchesExpected(Object, String)})</li>
 *     </ul>
 *   </li>
 *
 *   <li><b>Firing.</b> Rules are evaluated after every accepted message. The reactor fires once if
 *       at least one rule is satisfied (OR over rules). After the firing, every satisfied rule
 *       sets {@code lastSeq[rule][C] = seq(C)} for each of its channels; rules that were not
 *       satisfied keep their pending deltas.</li>
 *
 *   <li><b>Output tag.</b> With {@code T} the maximum timestamp over all held inputs (input
 *       microsteps are ignored) and {@code lastOut} the previous output tag, a firing gets the tag
 *       {@code (T, 0)} if {@code T > lastOut.t}, and {@code (T, lastOut.m + 1)} otherwise. Output
 *       tags of a reactor are therefore strictly increasing.</li>
 * </ul>
 *
 * <h2>Snapshot semantics</h2>
 * The snapshot handed to {@link #reactionFunction(Map)} is immutable and contains:
 * <ul>
 *     <li><b>channel → value</b> extracted from the JSON field "value" of the latest accepted
 *         message</li>
 *     <li><b>channel-timestamp → Instant</b>, the timestamp {@code t} of that message's tag</li>
 *     <li><b>{@value #OUTPUT_TAG_KEY} → {@link Tag}</b>, the output tag of this firing</li>
 * </ul>
 *
 * <h2>Timing measurement</h2>
 * Every firing and every discarded message is reported to the {@link FiringRecorder} obtained
 * from {@link FiringRecorders#current()} at construction, as a {@link FiringRecord}. The record
 * holds the trigger channel, arrival, decision and reaction instants, the tags, and the consumed
 * inputs. Nothing is published on the event bus for measurement, so the measurement adds no bus
 * load. With the default no-op recorder, no clock is read.
 *
 * <h2>Thread safety</h2>
 * The gate, the held values, rule evaluation and pointer updates are done under a single lock, so
 * acceptance and evaluation of one message are atomic with respect to the others. For atomic
 * reactors {@link #reactionFunction(Map)} runs inside that lock; for non-atomic reactors it runs
 * after the lock is released, so several reactions of one reactor may overlap and publish out of
 * tag order. The recorder is called after the lock is released.
 */
public abstract class Reactor extends Service {

  /**
   * Snapshot key under which {@link #reactionFunction(Map)} finds the {@link Tag} of the current
   * firing. Reactors publish their results with this tag.
   */
  public static final String OUTPUT_TAG_KEY = "@outputTag";

  private final boolean atomic;

  /**
   * Producer group under which this reactor announces its output channels (see
   * {@link Service#announceAddress(String, String)}). Defaults to {@code "CDSS"} so that
   * sinks listing the {@code "CDSS"} device discover all reactor outputs; can be made a
   * constructor parameter to support multiple reactor groups.
   */
  protected final String addressGroup = "CDSS";

  /**
   * Output tag of the latest firing; {@link Tag#ZERO} before the first firing. Written under the
   * evaluation lock before the reaction runs. In a non-atomic reactor a reaction may observe the
   * tag of a later firing here; reactions should use the snapshot's {@link #OUTPUT_TAG_KEY}.
   */
  protected volatile Tag lastOutTag = Tag.ZERO;

  /**
   * An immutable evaluation snapshot.
   *
   * @param values    channel → latest value, {@code "<channel>-timestamp"} → {@link Instant},
   *                  and {@link #OUTPUT_TAG_KEY} → {@link #outputTag}
   * @param outputTag the output tag of the firing
   */
  public record Snapshot(Map<String, Object> values, Tag outputTag) {
  }

  /** Raw firing rule definitions. */
  private final List<Map<String, String>> firingRules;

  /** Actor instance identifier (used in latency tags, classification IDs, etc.). */
  protected final String id;

  /** Input channels this reactor listens to. */
  protected final List<String> inputChannels;

  /** Output channels this reactor may publish to. */
  protected final List<String> outputChannels;

  /** Receives the timing of every evaluation; read once at construction. */
  private final FiringRecorder recorder;

  /** Compiled rules: channel → structured FiringRule. */
  private final List<Map<String, FiringRule>> compiledRules = new ArrayList<>();

  // ---- state below is guarded by evalLock ----

  /** Handled messages of this reactor so far (accepted and discarded). */
  private long evaluationCount;

  /** Latest accepted JSON message per input channel. */
  private final Map<String, Object> latestByChannel = new HashMap<>();

  /** Tag of the latest accepted message per input channel. */
  private final Map<String, Tag> lastAcceptedTag = new HashMap<>();

  /** Arrival instant of the latest accepted message per input channel; only when recording. */
  private final Map<String, Instant> arrivalByChannel = new HashMap<>();

  /** Accepted-message counter per channel (never reset). */
  private final Map<String, Long> channelSeq = new HashMap<>();

  /**
   * Per-rule, per-channel sequence number at the rule's last firing.
   * lastConsumedSeqByRule.get(ruleIndex).get(channel)
   */
  private final List<Map<String, Long>> lastConsumedSeqByRule = new ArrayList<>();

  /** Lock protecting the gate, held values, evaluation and pointer updates. */
  private final ReentrantLock evalLock = new ReentrantLock();

  /**
   * Constructs an atomic rule-based reactor.
   *
   * @param eventBus        the event bus providing input messages and publishing outputs
   * @param id              reactor identifier
   * @param firingRules     list of rules (channel → token)
   * @param inputChannels   list of channels this reactor subscribes to
   * @param outputChannels  list of channels this reactor may publish to
   */
  protected Reactor(EventBus eventBus,
                    String id,
                    List<Map<String, String>> firingRules,
                    List<String> inputChannels,
                    List<String> outputChannels) {
    this(eventBus, id, firingRules, inputChannels, outputChannels, true);
  }

  /**
   * Constructs a rule-based reactor.
   *
   * @param eventBus        the event bus providing input messages and publishing outputs
   * @param id              reactor identifier
   * @param firingRules     list of rules (channel → token)
   * @param inputChannels   list of channels this reactor subscribes to
   * @param outputChannels  list of channels this reactor may publish to
   * @param atomic          if {@code true}, {@link #reactionFunction(Map)} runs inside the
   *                        evaluation lock (serialized); if {@code false}, it runs after the lock
   *                        is released
   */
  protected Reactor(EventBus eventBus,
                    String id,
                    List<Map<String, String>> firingRules,
                    List<String> inputChannels,
                    List<String> outputChannels,
                    boolean atomic) {

    super(eventBus);
    this.id = id;
    this.atomic = atomic;
    this.recorder = FiringRecorders.current();
    this.firingRules = firingRules;
    this.inputChannels = List.copyOf(inputChannels);
    this.outputChannels = List.copyOf(outputChannels);

    // initial setting of addresses
    for (String out : outputChannels) {
      announceAddress(addressGroup, out);
    }

    compileRules();
    initLastConsumedPointers();

    for (String ch : this.inputChannels) {
      channelSeq.put(ch, 0L);
      lastAcceptedTag.put(ch, Tag.ZERO);
      latestByChannel.put(ch, new JSONObject());
      this.eventBus.register(ch, msg -> onMessage(ch, msg));
    }
  }

  /**
   * Called once per firing, i.e. per accepted message for which at least one rule is satisfied.
   *
   * @param latestSnapshot immutable snapshot of latest channel values and timestamps, plus the
   *                       firing's output tag under {@link #OUTPUT_TAG_KEY}
   */
  public abstract void reactionFunction(Map<String, Object> latestSnapshot);

  /**
   * Returns the output tag of the firing a snapshot belongs to: the value under
   * {@link #OUTPUT_TAG_KEY}. Reactions pass it to {@link RuleUtils#publishResult}.
   *
   * @param snapshot the snapshot handed to {@link #reactionFunction(Map)}
   * @return the firing's output tag
   * @throws IllegalArgumentException if the map is no reactor snapshot
   */
  protected static Tag outputTag(Map<String, Object> snapshot) {
    if (snapshot.get(OUTPUT_TAG_KEY) instanceof Tag tag) return tag;
    throw new IllegalArgumentException("Snapshot carries no output tag");
  }

  /**
   * Returns the input channels this reactor listens to.
   *
   * @return the immutable list of input channel names
   */
  public List<String> getInputChannels() { return inputChannels; }

  /**
   * Returns the output channels this reactor may publish to.
   *
   * @return the immutable list of output channel names
   */
  public List<String> getOutputChannels() { return outputChannels; }

  /**
   * Handles an incoming message: applies the per-channel gate, and for an accepted message stores
   * it, increments the channel's sequence counter, evaluates all rules and fires at most once.
   */
  private void onMessage(String channel, Object msg) {
    final boolean recording = recorder.enabled();
    final Instant arrival = recording ? MonotonicClock.now() : null;
    final Tag tag = extractTag(msg);

    long evaluation = 0;
    Tag gateTag = null;
    boolean discarded = false;
    Snapshot snapshotToFire = null;
    Instant decision = null;
    Instant reactStart = null;
    Instant reactEnd = null;
    List<FiringRecord.ConsumedInput> consumed = List.of();

    evalLock.lock();
    try {
      evaluation = ++evaluationCount;
      gateTag = lastAcceptedTag.get(channel);

      // --- per-channel logical-time gate ---
      if (tag == null || !tag.isAfter(gateTag)) {
        discarded = true;
        return;
      }
      lastAcceptedTag.put(channel, tag);
      latestByChannel.put(channel, msg);
      channelSeq.merge(channel, 1L, Long::sum);
      if (recording) arrivalByChannel.put(channel, arrival);

      // --- evaluate rules on sequence deltas ---
      List<Integer> satisfiedRules = new ArrayList<>();
      for (int i = 0; i < compiledRules.size(); i++) {
        if (ruleSatisfied(i)) satisfiedRules.add(i);
      }

      if (satisfiedRules.isEmpty()) {
        if (recording) recorder.countNoRule(id);
        return;
      }

      // --- output tag ---
      Tag outTag = nextOutputTag();
      snapshotToFire = buildSnapshot(outTag);

      if (recording) {
        // before the pointers advance: afterwards the consumed deltas are gone
        consumed = pendingInputs(satisfiedRules);
        decision = MonotonicClock.now();
      }

      lastOutTag = outTag;
      for (int ruleIndex : satisfiedRules) {
        Map<String, Long> ptr = lastConsumedSeqByRule.get(ruleIndex);
        for (String ch : compiledRules.get(ruleIndex).keySet()) {
          ptr.put(ch, channelSeq.get(ch));
        }
      }

      if (atomic) {
        if (recording) reactStart = MonotonicClock.now();
        reactionFunction(snapshotToFire.values());
        if (recording) reactEnd = MonotonicClock.now();
      }
    } finally {
      evalLock.unlock();
      if (discarded && recording) {
        recorder.record(new FiringRecord(id, evaluation, FiringRecord.Outcome.DISCARDED, atomic,
                channel, tag, arrival, null, gateTag, null, null, null, List.of()));
      }
    }

    // --- fire outside lock (non-atomic) ---
    if (!atomic) {
      if (recording) reactStart = MonotonicClock.now();
      reactionFunction(snapshotToFire.values());
      if (recording) reactEnd = MonotonicClock.now();
    }

    if (recording) {
      recorder.record(new FiringRecord(id, evaluation, FiringRecord.Outcome.FIRED, atomic,
              channel, tag, arrival, snapshotToFire.outputTag(), gateTag, decision, reactStart,
              reactEnd, consumed));
    }
  }

  /** Whether rule {@code i} is satisfied by the current sequence deltas. Under {@link #evalLock}. */
  private boolean ruleSatisfied(int i) {
    Map<String, Long> lastSeq = lastConsumedSeqByRule.get(i);
    for (var e : compiledRules.get(i).entrySet()) {
      String ch = e.getKey();
      FiringRule cond = e.getValue();
      long delta = channelSeq.get(ch) - lastSeq.get(ch);
      boolean ok = switch (cond.type()) {
        case ANY -> delta >= 1;
        case AT_LEAST -> delta >= cond.n();
        case REQUIRE_VALUE -> delta >= 1
                && valueMatchesExpected(valueOf(latestByChannel.get(ch)), cond.value());
      };
      if (!ok) return false;
    }
    return true;
  }

  /**
   * Computes the output tag of a firing from the held inputs and {@link #lastOutTag}. Under
   * {@link #evalLock}.
   *
   * @throws IllegalStateException if the tag would not be strictly greater than the previous one
   *                               (unreachable while the per-channel gate holds)
   */
  private Tag nextOutputTag() {
    Instant maxT = Instant.EPOCH;
    for (String ch : inputChannels) {
      Instant t = lastAcceptedTag.get(ch).t();
      if (t.isAfter(maxT)) maxT = t;
    }
    Tag last = lastOutTag;
    Tag out = maxT.isAfter(last.t()) ? Tag.of(maxT) : new Tag(maxT, last.m() + 1);
    if (!out.isAfter(last)) {
      throw new IllegalStateException("Reactor %s: output tag %s not after previous %s"
              .formatted(id, out, last));
    }
    return out;
  }

  /**
   * Collects the inputs that carry new data ({@code delta >= 1}) for at least one satisfied rule.
   * Must be called under {@link #evalLock}, before the consumed pointers advance.
   */
  private List<FiringRecord.ConsumedInput> pendingInputs(List<Integer> satisfiedRules) {
    Map<String, FiringRecord.ConsumedInput> pending = new LinkedHashMap<>();
    for (int ruleIndex : satisfiedRules) {
      Map<String, Long> lastSeq = lastConsumedSeqByRule.get(ruleIndex);
      for (String ch : compiledRules.get(ruleIndex).keySet()) {
        if (pending.containsKey(ch)) continue;
        long seq = channelSeq.get(ch);
        if (seq > lastSeq.get(ch)) {
          pending.put(ch, new FiringRecord.ConsumedInput(ch, seq, lastAcceptedTag.get(ch),
                  arrivalByChannel.get(ch)));
        }
      }
    }
    return new ArrayList<>(pending.values());
  }

  /**
   * Builds the immutable snapshot of the held inputs: value under the channel name, timestamp
   * under {@code "<channel>-timestamp"} (only for channels that have accepted a message), and the
   * output tag under {@link #OUTPUT_TAG_KEY}. Under {@link #evalLock}.
   */
  private Snapshot buildSnapshot(Tag outTag) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (String ch : inputChannels) {
      snapshot.put(ch, valueOf(latestByChannel.get(ch)));
      if (channelSeq.get(ch) > 0) {
        snapshot.put("%s-timestamp".formatted(ch), lastAcceptedTag.get(ch).t());
      }
    }
    snapshot.put(OUTPUT_TAG_KEY, outTag);
    return new Snapshot(Collections.unmodifiableMap(snapshot), outTag);
  }

  /** The {@code "value"} field of a held message, or {@code null}. */
  private static Object valueOf(Object msg) {
    return msg instanceof JSONObject dp && dp.has("value") ? dp.get("value") : null;
  }

  /** Compiles rule tokens ("*", "N", "r:v") into structured FiringRule objects. */
  private void compileRules() {
    for (int i = 0; i < firingRules.size(); i++) {
      Map<String, String> ruleCfg = firingRules.get(i);

      if (ruleCfg == null || ruleCfg.isEmpty())
        throw new IllegalArgumentException("Rule %d is empty or null.".formatted(i));

      Map<String, FiringRule> compiled = new HashMap<>();

      for (var e : ruleCfg.entrySet()) {
        String ch = e.getKey();
        String tok = e.getValue();

        if (!inputChannels.contains(ch))
          throw new IllegalArgumentException("Rule %d references unknown channel '%s'".formatted(i, ch));

        compiled.put(ch, parseCondition(tok));
      }

      compiledRules.add(Collections.unmodifiableMap(compiled));
    }
  }

  /** Initializes per-rule per-channel last-consumed pointers to zero. */
  private void initLastConsumedPointers() {
    for (int i = 0; i < firingRules.size(); i++) {
      Map<String, Long> ptrSeq = new HashMap<>();
      for (String ch : inputChannels) {
        ptrSeq.put(ch, 0L);
      }
      lastConsumedSeqByRule.add(ptrSeq);
    }
  }

  /** Parses a token ("*", "N", "r:v") into a FiringRule. */
  private FiringRule parseCondition(String token) {
    if (token == null) throw new IllegalArgumentException("Condition token must not be null.");
    String t = token.trim();

    if ("*".equals(t)) return FiringRule.any();

    if (t.matches("\\d+")) {
      long n = Long.parseLong(t);
      if (n <= 0)
        throw new IllegalArgumentException("Numeric condition must be >= 1, was: %s".formatted(t));
      return FiringRule.atLeast(n);
    }

    if (t.startsWith("r:"))
      return FiringRule.requireValue(t.substring(2));

    throw new IllegalArgumentException("Unsupported token: %s".formatted(token));
  }

  /**
   * Equality check for the {@code "r:v"} (REQUIRE_VALUE) token: compares the string form of the
   * latest accepted value (the message's {@code "value"} field) against the expected value.
   * Override to customize matching. Called under the evaluation lock.
   *
   * @param actual   the latest accepted value on the channel, or {@code null}
   * @param expected the expected value string from the rule token
   * @return {@code true} if the value matches
   */
  protected boolean valueMatchesExpected(Object actual, String expected) {
    return Objects.equals(Objects.toString(actual, null), expected);
  }
}
