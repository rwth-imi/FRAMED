// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.utils.MonotonicClock;
import org.json.JSONObject;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.framed.arn.RuleUtils.extractTimestamp;

/**
 * A reactive multi-input {@link Service} that evaluates a set of firing rules on incoming
 * messages, constructs exactly one consistent snapshot per evaluation cycle, and triggers a
 * user-defined {@link #reactionFunction(Map)} once whenever at least one rule is satisfied.
 *
 * <p>Concrete reactors extend this class, supply their firing rules and input/output channels
 * via the constructor, and implement {@link #reactionFunction(Map)} to compute and publish
 * derived results (typically via {@link RuleUtils#publishResult}).</p>
 *
 * <h2>Core semantics</h2>
 * <ul>
 *   <li>Each input channel maintains:
 *     <ul>
 *       <li>a monotonic absolute sequence counter (never reset), and</li>
 *       <li>the latest JSON message value.</li>
 *     </ul>
 *   </li>
 *
 *   <li>Each rule is a map of <code>channel → token</code> where tokens:
 *     <ul>
 *       <li><b>"*"</b> = ANY: at least 1 new message since this rule last fired</li>
 *       <li><b>"N"</b> = AT_LEAST(N): at least N new messages since last fired</li>
 *       <li><b>"r:v"</b> = REQUIRE_VALUE(v): at least 1 new message AND latest value == v</li>
 *     </ul>
 *   </li>
 *
 *   <li>Each rule maintains its own <b>last-consumed</b> sequence pointer per channel.
 *       This gives each rule independent delta semantics.</li>
 *
 *   <li>On each incoming message:
 *     <ul>
 *       <li>all rules are evaluated against a stable snapshot of latest data + seq counters;</li>
 *       <li>every satisfied rule updates its per-channel pointers;</li>
 *       <li>a single snapshot is created;</li>
 *       <li>{@link #reactionFunction(Map)} is called once, provided the snapshot's logical
 *           timestamp is newer than the last firing (logical-time gate);</li>
 *       <li>the evaluation's timing is handed to the {@link FiringRecorder}.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Snapshot semantics</h2>
 * The snapshot contains:
 * <ul>
 *     <li><b>channel → value</b> extracted from the JSON field "value"</li>
 *     <li><b>channel-timestamp → Instant</b> parsed from JSON "timestamp"</li>
 * </ul>
 *
 * This snapshot is immutable and provided to {@link #reactionFunction(Map)}.
 *
 * <h2>Timing measurement</h2>
 * Every evaluation that satisfies at least one rule is reported to the {@link FiringRecorder}
 * obtained from {@link FiringRecorders#current()} at construction, as a {@link FiringRecord}.
 * The record holds the trigger channel, arrival, decision and reaction instants, and the consumed
 * inputs. Evaluations rejected by the logical-time gate are reported as
 * {@link FiringRecord.Outcome#GATED}. Nothing is published on the event bus for measurement, so
 * the measurement adds no bus load. With the default no-op recorder, no clock is read.
 *
 * <h2>Thread safety</h2>
 * All rule evaluation and delta accounting is done under a single lock. For atomic reactors
 * {@link #reactionFunction(Map)} runs inside that lock; for non-atomic reactors it runs after the
 * lock is released, so several reactions of one reactor may overlap. The recorder is called after
 * the lock is released.
 */
public abstract class Reactor extends Service {
  private final boolean atomic;

  /**
   * Producer group under which this reactor announces its output channels (see
   * {@link Service#announceAddress(String, String)}). Defaults to {@code "CDSS"} so that
   * sinks listing the {@code "CDSS"} device discover all reactor outputs; can be made a
   * constructor parameter to support multiple reactor groups.
   */
  protected final String addressGroup = "CDSS";
  /** Last logical timestamp at which this reactor fired. */
  protected volatile Instant lastLogicalFireTs = Instant.EPOCH;

  /**
   * An immutable evaluation snapshot handed to {@link #reactionFunction(Map)}.
   *
   * @param values   channel → latest value, plus {@code "<channel>-timestamp"} → {@link Instant}
   * @param logicalTs the latest (maximum) channel timestamp in this snapshot
   */
  public record Snapshot(Map<String, Object> values, Instant logicalTs) {
  }

  /**
   * Per-rule, per-channel last consumed logical timestamp.
   * lastConsumedTsByRule.get(ruleIndex).get(channel)
   */
  private final List<Map<String, Instant>> lastConsumedTsByRule = new ArrayList<>();

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

  /**
   * Arrival of the latest message per input channel, paired with that message so a reader can
   * tell whether the arrival belongs to the message it sees. Written only when recording.
   */
  private final Map<String, Arrival> arrivalByChannel = new ConcurrentHashMap<>();

  /** Evaluations of this reactor so far; guarded by {@link #evalLock}. */
  private long evaluationCount;

  /** A message and the instant it entered this reactor's handler. */
  private record Arrival(Object msg, Instant at) {
  }

  /** Compiled rules: channel → structured FiringRule. */
  private final List<Map<String, FiringRule>> compiledRules = new ArrayList<>();

  /** Latest JSON message per input channel. */
  private final Map<String, Object> latestByChannel = new ConcurrentHashMap<>();

  /** Absolute message counter per channel (never reset). */
  private final Map<String, Long> channelSeq = new ConcurrentHashMap<>();

  /**
   * Per-rule, per-channel last consumed sequence number.
   * lastConsumedSeqByRule.get(ruleIndex).get(channel)
   */
  private final List<Map<String, Long>> lastConsumedSeqByRule = new ArrayList<>();

  /** Lock protecting evaluation and pointer updates. */
  private final ReentrantLock evalLock = new ReentrantLock();

  /**
   * Constructs a rule-based Actor.
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
   * @param atomic          if {@code true}, {@link #reactionFunction(Map)} and latency
   *                        publishing run inside the evaluation lock (serialized); if
   *                        {@code false}, they run after the lock is released
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
      latestByChannel.put(ch, new JSONObject());
      this.eventBus.register(ch, msg -> onMessage(ch, msg));
    }
  }

  /**
   * Called once per evaluation cycle when any rule is satisfied.
   *
   * @param latestSnapshot immutable snapshot of latest channel values and timestamps
   */
  public abstract void reactionFunction(Map<String, Object> latestSnapshot);

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
   * Handles an incoming message:
   * <ul>
   *     <li>Stores latest JSON object</li>
   *     <li>Increments sequence counter</li>
   *     <li>Evaluates all rules</li>
   * </ul>
   */
  private void onMessage(String channel, Object msg) {
    Instant arrival = null;
    if (recorder.enabled()) {
      arrival = MonotonicClock.now();
      // written before the message, so a reader that sees the new message also finds its arrival
      arrivalByChannel.put(channel, new Arrival(msg, arrival));
    }
    latestByChannel.put(channel, msg);
    channelSeq.merge(channel, 1L, Long::sum);
    evaluateRules(channel, msg, arrival);
  }

    /**
   * Main evaluation procedure. Produces exactly one snapshot + one fire
   * per evaluation cycle, regardless of how many rules were satisfied.
   * Corrected to:
   *   • Only treat a channel as "new" if its sequence number actually advanced.
   *   • Update lastConsumedSeqByRule only for channels with real deltas.
   *   • Preserve true AND semantics for multi-input rules.
   */
  private void evaluateRules(String triggerChannel, Object triggerMsg, Instant triggerArrival) {
    Snapshot snapshotToFire = null;
    boolean shouldFire = false;
    final boolean recording = recorder.enabled();
    long evaluation = 0;
    Instant gateTs = null;
    Instant decision = null;
    Instant reactStart = null;
    Instant reactEnd = null;
    Snapshot evaluated = null;
    List<FiringRecord.ConsumedInput> consumed = List.of();

    evalLock.lock();
    try {
      evaluation = ++evaluationCount;
      gateTs = lastLogicalFireTs;

      // --- Build stable snapshot of state at call ---
      Map<String, Long> seqAtCall = new HashMap<>();
      Map<String, Object> latestAtCall = new HashMap<>();

      for (String ch : inputChannels) {
        seqAtCall.put(ch, channelSeq.getOrDefault(ch, 0L));
        latestAtCall.put(ch, latestByChannel.get(ch));
      }

      // --- Evaluate rules ---
      List<Integer> satisfiedRules = new ArrayList<>();

      for (int i = 0; i < compiledRules.size(); i++) {
        Map<String, FiringRule> rule = compiledRules.get(i);

        boolean satisfied = true;

        // AND semantics: every channel in the rule must satisfy its condition
        for (var e : rule.entrySet()) {
          String ch = e.getKey();
          FiringRule cond = e.getValue();

          Object latest = latestAtCall.get(ch);
          Instant ts = extractTimestamp(latest);
          Instant lastTs = lastConsumedTsByRule.get(i).get(ch);

          /* A channel is "new" iff its timestamp is strictly newer */
          /* A channel is "new" iff its timestamp is strictly newer */
          boolean isNew = ts != null && ts.isAfter(lastTs);

// FIX: AT_LEAST(N) must use sequence deltas, not timestamp newness
          if (cond.type() == RuleType.AT_LEAST) {
            long curSeq = seqAtCall.getOrDefault(ch, 0L);
            long lastSeq = lastConsumedSeqByRule.get(i).getOrDefault(ch, 0L);
            long delta = curSeq - lastSeq;

            if (delta < cond.n()) {   // <-- correct AT_LEAST(N)
              satisfied = false;
              break;
            }
          } else {
            if (!testCondition(cond, isNew, latest)) {
              satisfied = false;
              break;
            }
          }
        }

        if (satisfied) {
          satisfiedRules.add(i);
        }
      }

      if (satisfiedRules.isEmpty() && recording) {
        recorder.countNoRule(id);
      }

      // after evaluating satisfiedRules
      if (!satisfiedRules.isEmpty()) {
        Snapshot snap = buildSnapshotFrom(latestAtCall);
        evaluated = snap;
        if (recording) {
          // before the pointers advance: afterwards the consumed deltas are gone
          consumed = pendingInputs(satisfiedRules, seqAtCall, latestAtCall);
          decision = MonotonicClock.now();
        }

        // LIFO logical-time gate
        if (snap.logicalTs.isAfter(lastLogicalFireTs)) {
          lastLogicalFireTs = snap.logicalTs;
          snapshotToFire = snap;
          shouldFire = true;

          // advance per-rule per-channel timestamps + sequence pointers
          for (int ruleIndex : satisfiedRules) {
            Map<String, Instant> lastPtrTs = lastConsumedTsByRule.get(ruleIndex);
            Map<String, Long> lastPtrSeq = lastConsumedSeqByRule.get(ruleIndex);
            Map<String, FiringRule> rule = compiledRules.get(ruleIndex);

            for (String ch : rule.keySet()) {
              // Timestamp pointer (UNCHANGED)
              Instant ts = extractTimestamp(latestAtCall.get(ch));
              if (ts != null && ts.isAfter(lastPtrTs.get(ch))) {
                lastPtrTs.put(ch, ts);
              }

              // Sequence pointer (NEW, needed for AT_LEAST semantics)
              long curSeq = seqAtCall.getOrDefault(ch, 0L);
              long lastSeq = lastPtrSeq.getOrDefault(ch, 0L);
              if (curSeq > lastSeq) {
                lastPtrSeq.put(ch, curSeq);
              }
            }
          }
          if (atomic) {
            if (recording) reactStart = MonotonicClock.now();
            reactionFunction(snapshotToFire.values);
            if (recording) reactEnd = MonotonicClock.now();
          }
        }
      }
    } finally {
      evalLock.unlock();
    }

    // --- Fire outside lock (non-atomic) ---
    if (!atomic && shouldFire && snapshotToFire != null) {
      if (recording) reactStart = MonotonicClock.now();
      reactionFunction(snapshotToFire.values);
      if (recording) reactEnd = MonotonicClock.now();
    }

    if (recording && evaluated != null) {
      recorder.record(new FiringRecord(
              id,
              evaluation,
              shouldFire ? FiringRecord.Outcome.FIRED : FiringRecord.Outcome.GATED,
              atomic,
              triggerChannel,
              extractTimestamp(triggerMsg),
              triggerArrival,
              evaluated.logicalTs,
              gateTs,
              decision,
              reactStart,
              reactEnd,
              consumed));
    }
  }

  /**
   * Collects the inputs that carry new data for at least one satisfied rule, using the same
   * newness test as rule evaluation (sequence delta for {@code AT_LEAST}, a strictly newer
   * timestamp otherwise). Must be called under {@link #evalLock}, before the consumed pointers
   * advance.
   */
  private List<FiringRecord.ConsumedInput> pendingInputs(List<Integer> satisfiedRules,
                                                         Map<String, Long> seqAtCall,
                                                         Map<String, Object> latestAtCall) {
    Map<String, FiringRecord.ConsumedInput> pending = new LinkedHashMap<>();
    for (int ruleIndex : satisfiedRules) {
      for (var e : compiledRules.get(ruleIndex).entrySet()) {
        String ch = e.getKey();
        if (pending.containsKey(ch)) continue;
        Object latest = latestAtCall.get(ch);
        Instant ts = extractTimestamp(latest);
        long seq = seqAtCall.getOrDefault(ch, 0L);
        boolean isNew = e.getValue().type() == RuleType.AT_LEAST
                ? seq > lastConsumedSeqByRule.get(ruleIndex).getOrDefault(ch, 0L)
                : ts != null && ts.isAfter(lastConsumedTsByRule.get(ruleIndex).get(ch));
        if (isNew) {
          Arrival arrival = arrivalByChannel.get(ch);
          Instant at = arrival != null && arrival.msg() == latest ? arrival.at() : null;
          pending.put(ch, new FiringRecord.ConsumedInput(ch, seq, ts, at));
        }
      }
    }
    return new ArrayList<>(pending.values());
  }

  /**
   * Builds an immutable latest-value snapshot from the provided map.
   * Extracts:
   * <ul>
   *   <li>value   under channel name</li>
   *   <li>Instant under "channel-timestamp"</li>
   * </ul>
   */
  private Snapshot buildSnapshotFrom(Map<String, Object> latestAtCall) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    Instant maxTs = Instant.EPOCH;

    for (String ch : inputChannels) {
      JSONObject dp = (JSONObject) latestAtCall.get(ch);

      Object value = dp.has("value") ? dp.get("value") : null;
      snapshot.put(ch, value);

      if (dp.has("timestamp")) {
        Instant ts = LocalDateTime
                .parse(dp.getString("timestamp"), formatter)
                .atZone(ZoneOffset.UTC)
                .toInstant();

        snapshot.put("%s-timestamp".formatted(ch), ts);
        if (ts.isAfter(maxTs)) {
          maxTs = ts;
        }
      }
    }

    return new Snapshot(Collections.unmodifiableMap(snapshot), maxTs);
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
      Map<String, Instant> ptrTs = new HashMap<>();
      Map<String, Long> ptrSeq = new HashMap<>();
      for (String ch : inputChannels) {
        ptrTs.put(ch, Instant.EPOCH);
        ptrSeq.put(ch, 0L);
      }
      lastConsumedTsByRule.add(ptrTs);
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

  /** Tests whether a given rule condition is satisfied according to delta + latest value. */
  private boolean testCondition(FiringRule cond, boolean isNew, Object latest) {
    return switch (cond.type()) {
      case ANY -> isNew;
      case AT_LEAST -> isNew; // AT_LEAST(N) collapses to timestamp ordering in LIFO mode
      case REQUIRE_VALUE -> isNew && valueMatchesExpected(latest, cond.value());
    };
  }

  /**
   * Default equality check for the {@code "r:v"} (REQUIRE_VALUE) token: compares the latest
   * value's string form against the expected value. Override to customize matching.
   *
   * @param actual   the latest value received on the channel
   * @param expected the expected value string from the rule token
   * @return {@code true} if the value matches
   */
  protected boolean valueMatchesExpected(Object actual, String expected) {
    return Objects.equals(Objects.toString(actual, null), expected);
  }
}
