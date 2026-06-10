package com.framed.arn;

import com.framed.core.EventBus;
import com.framed.core.Service;
import org.json.JSONObject;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import static com.framed.arn.RuleUtils.extractTimestamp;
import static com.framed.arn.RuleUtils.publishResult;

/**
 * A reactive multi-input Actor that evaluates a set of firing rules on incoming
 * messages, constructs exactly one consistent snapshot per evaluation cycle,
 * and triggers a user-defined {@link #reactionFunction(Map)} once whenever at least
 * one rule is satisfied.
 *
 * <h1>Core Semantics</h1>
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
 *       <li>{@link #reactionFunction(Map)} is called once;</li>
 *       <li>latency is published according to all three modes (A, B, C).</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h1>Snapshot Semantics</h1>
 * The snapshot contains:
 * <ul>
 *     <li><b>channel → value</b> extracted from the JSON field "value"</li>
 *     <li><b>channel-timestamp → Instant</b> parsed from JSON "timestamp"</li>
 * </ul>
 *
 * This snapshot is immutable and provided to {@link #reactionFunction(Map)}.
 *
 * <h1>Latency Publishing Modes</h1>
 * After a firing, the Actor publishes:
 * <ol>
 *   <li><b>(A) Per-channel latency</b>: For every input channel.</li>
 *   <li><b>(B) One global latency</b>: Based on the earliest channel timestamp.</li>
 *   <li><b>(C2) Rule-participation latency</b>: Only for channels whose delta ≥ 1
 *       for at least one satisfied rule.</li>
 * </ol>
 *
 * The latency messages are deduplicated per timestamp to avoid double publications.
 *
 * <h1>Thread Safety</h1>
 * All rule evaluation and delta accounting is done under a single lock, while
 * {@link #reactionFunction(Map)} is executed outside the lock.
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
  /** Last logical timestamp at which this reactor fired */
  protected volatile Instant lastLogicalFireTs = Instant.EPOCH;

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

  /** Tracks timestamps previously published for latency reporting. */
  private final Map<String, Instant> lastTsPublishedByChannel = new ConcurrentHashMap<>();

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
                    List<String> outputChannels,
                    boolean atomic) {

    super(eventBus);
    this.id = id;
    this.atomic = atomic;
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

  /** @return list of input channels this reactor listens to */
  public List<String> getInputChannels() { return inputChannels; }

  /** @return list of output channels this reactor may publish to */
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
    latestByChannel.put(channel, msg);
    channelSeq.merge(channel, 1L, Long::sum);
    evaluateRules();
  }

    /**
   * Main evaluation procedure. Produces exactly one snapshot + one fire
   * per evaluation cycle, regardless of how many rules were satisfied.
   * Corrected to:
   *   • Only treat a channel as "new" if its sequence number actually advanced.
   *   • Update lastConsumedSeqByRule only for channels with real deltas.
   *   • Preserve true AND semantics for multi-input rules.
   */
  private void evaluateRules() {
    Snapshot snapshotToFire = null;
    boolean shouldFire = false;

    evalLock.lock();
    try {
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

      // after evaluating satisfiedRules
      if (!satisfiedRules.isEmpty()) {
        Snapshot snap = buildSnapshotFrom(latestAtCall);

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
            reactionFunction(snapshotToFire.values);
            publishAllLatencyModes(snapshotToFire.values);
          }
        }
      }
    } finally {
      evalLock.unlock();
    }

    // --- Fire and publish latency outside lock ---
    if (!atomic && shouldFire && snapshotToFire != null) {
      reactionFunction(snapshotToFire.values);
      publishAllLatencyModes(snapshotToFire.values);
    }

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

  /** Default equality check for "r:v". */
  protected boolean valueMatchesExpected(Object actual, String expected) {
    return Objects.equals(Objects.toString(actual, null), expected);
  }

  // --------------------------------------------------------------------------
  // LATENCY PUBLISHING (A + B + C2)
  // --------------------------------------------------------------------------

  /**
   * Publishes latency in all three modes:
   * <ol>
   *   <li>(A) per channel latency</li>
   *   <li>(B) global latency (earliest timestamp)</li>
   *   <li>(C2) rule-participation latency (channels with delta >= 1)</li>
   * </ol>
   */
  private void publishAllLatencyModes(Map<String, Object> snapshot) {
    Instant now = Instant.now();

    publishPerChannelLatency(snapshot, now);
    publishGlobalLatency(snapshot, now);
    publishRuleParticipationLatency(snapshot, now);
  }

  /**
   * (A) Publishes latency per input channel.
   */
  private void publishPerChannelLatency(Map<String, Object> snapshot, Instant benchmarkTs) {
    for (String ch : inputChannels) {
      String timeKey = "%s-timestamp".formatted(ch);
      Object v = snapshot.get(timeKey);
      if (!(v instanceof Instant ts)) {
        // No timestamp present for this channel in the snapshot → nothing to do
        continue;
      }

      // Guard: datapoint claims to be in the future (clock skew or bad source)
      if (ts.isAfter(benchmarkTs)) {
        logger.log(Level.WARNING, "Timestamp from future: {0} > {1}".formatted(ts, benchmarkTs));
        continue;
      }

      Instant lastPublished = lastTsPublishedByChannel.get(ch);

      // Skip if we already published this exact datapoint OR a newer one.
      // (Older or equal timestamps should not be republished.)
      if (lastPublished != null && !ts.isAfter(lastPublished)) {
        continue;  // <-- IMPORTANT: was 'return' before, which aborted the whole method
      }

      double seconds = Duration.between(ts, benchmarkTs).toNanos() / 1_000_000_000d;

      publishResult(
              eventBus,
              seconds,
              "Latency",
              List.of("Latency-%s.%s".formatted(ch, id)),
              benchmarkTs
      );

      lastTsPublishedByChannel.put(ch, ts);
    }
  }

  /**
   * (B) Publishes a global latency value using the earliest timestamp
   * across all channels in the snapshot.
   */
  private void publishGlobalLatency(Map<String, Object> snapshot, Instant benchmarkTs) {
    Instant earliest = null;

    for (String ch : inputChannels) {
      Instant ts = (Instant) snapshot.get("%s-timestamp".formatted(ch));
      if (ts != null) {
        if (earliest == null || ts.isBefore(earliest)) {
          earliest = ts;
        }
      }
    }

    if (earliest.isAfter(benchmarkTs)){
      logger.log(Level.WARNING, "Timestamp from future: {0} > {1}".formatted(earliest, benchmarkTs));
      return;
    }

    if (earliest != null) {
      double seconds = Duration.between(earliest, benchmarkTs).toNanos() / 1_000_000_000d;

      publishResult(
              eventBus,
              seconds,
              "Latency-Global",
              List.of("Latency-Global.%s".formatted(id)),
              benchmarkTs
      );
    }
  }

  /**
   * (C) Publishes latency only for channels that contributed delta >= 1
   * in at least one satisfied rule during this evaluation.
   */
  private void publishRuleParticipationLatency(Map<String, Object> snapshot, Instant benchmarkTs) {
    // Determine participating channels (delta >= 1 for satisfied rules)
    Set<String> participating = new HashSet<>();

    for (int i = 0; i < compiledRules.size(); i++) {
      Map<String, Long> lastPtr = lastConsumedSeqByRule.get(i);
      Map<String, FiringRule> rule = compiledRules.get(i);

      for (var e : rule.entrySet()) {
        String ch = e.getKey();
        long last = lastPtr.get(ch);
        long cur = channelSeq.get(ch);

        if (cur - last >= 1) {
          participating.add(ch);
        }
      }
    }

    // Publish latency for participating channels only
    for (String ch : participating) {
      String timeKey = "%s-timestamp".formatted(ch);
      Instant ts = (Instant) snapshot.get(timeKey);
      if (ts.isAfter(benchmarkTs)){
        logger.log(Level.WARNING, "Timestamp from future: {0} > {1}".formatted(ts, benchmarkTs));
        continue;
      }

      double seconds = Duration.between(ts, benchmarkTs).toNanos() / 1_000_000_000d;

      publishResult(
              eventBus,
              seconds,
              "Latency-RuleParticipation",
              List.of("Latency-Rule-%s.%s".formatted(ch, id)),
              benchmarkTs
      );
    }
  }
}