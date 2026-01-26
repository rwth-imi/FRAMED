
package com.framed.cdss;

import com.framed.cdss.casestudy.RuleType;
import  com.framed.core.EventBus;
import com.framed.core.Service;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An abstract reactive {@code Actor} that listens to a set of input channels on an {@link EventBus},
 * buffers their latest messages, and evaluates configurable firing rules to decide when to invoke
 * a user-defined {@link #fireFunction(Map)}.
 * <p>
 * <strong>Key capabilities</strong>
 * <ul>
 *   <li>Maintains a per-channel <em>latest value</em> and a per-channel <em>monotonic sequence number</em>
 *       (the number of received messages for that channel).</li>
 *   <li>Supports multiple <em>firing rules</em>. Each rule is a {@code Map<channel, token>} where the token
 *       describes the condition for that channel within the rule. A rule fires when <em>all</em> its
 *       channel conditions are satisfied.</li>
 *   <li>When a rule fires, the actor calls {@link #fireFunction(Map)} with an <em>immutable snapshot</em> of the
 *       latest values of <strong>all</strong> configured input channels.</li>
 *   <li>Evaluation is performed under a lock to ensure atomic update-and-evaluate semantics, avoiding races
 *       that could otherwise cause missed or duplicate firings for a given rule.</li>
 * </ul>
 *
 * <h2>Firing Rule Configuration</h2>
 * The constructor accepts {@code List<Map<String,String>> firingRules}. Each map defines one rule; keys are
 * input channel names and values are <em>tokens</em> with the following semantics:
 * <ul>
 *   <li><b>"*"</b> — The channel must have received <em>at least one</em> new message since the last time
 *       <em>this rule</em> fired (i.e., {@code delta >= 1}).</li>
 *   <li><b>"N"</b> — The channel must have received <em>at least N</em> new messages since the last time
 *       <em>this rule</em> fired (N is a positive integer).</li>
 *   <li><b>"r:v"</b> — The channel must have received at least one new message since the last time
 *       <em>this rule</em> fired, and the <em>latest</em> value on the channel must match {@code v}. Matching is
 *       implemented by {@link #valueMatchesExpected(Object, String)} which, by default, compares string
 *       representations via {@code Objects.toString(actual, null).equals(expected)}.</li>
 * </ul>
 * A rule is considered satisfied only if <strong>all</strong> its channel conditions are satisfied simultaneously.
 * Multiple rules may be satisfied by the same incoming message and can fire within a single evaluation pass.
 *
 * <h2>Thread Safety</h2>
 * <ul>
 *   <li>Incoming messages update channel state ({@code latestByChannel} and {@code channelSeq}) and then trigger
 *       rule evaluation.</li>
 *   <li>Rule evaluation runs under a {@link ReentrantLock}, ensuring atomicity between observing the updated state,
 *       testing conditions, taking the snapshot, and invoking {@link #fireFunction(Map)}.</li>
 *   <li>Channel state maps are {@link ConcurrentHashMap}s suitable for concurrent access.</li>
 * </ul>
 *
 * <h2>Snapshot Semantics</h2>
 * When a rule fires, the actor passes an <em>unmodifiable snapshot</em> containing the latest value for
 * <strong>every</strong> input channel (not only those participating in the satisfied rule). This provides
 * the callee with a full view of the actor's current inputs.
 *
 * <h2>Extensibility</h2>
 * <ul>
 *   <li>Override {@link #valueMatchesExpected(Object, String)} to implement custom equality (e.g., typed comparison,
 *       JSON field matching, domain-specific predicates) for {@code "r:v"} conditions.</li>
 *   <li>Implement {@link #fireFunction(Map)} to define the behavior executed when any rule fires.</li>
 *   <li>Implement {@link #stop()} to provide shutdown/cleanup behavior in concrete actors.</li>
 * </ul>
 *
 * <h2>Assumptions</h2>
 * <ul>
 *   <li>The provided {@code EventBus} supports registering a handler per channel via
 *       {@code eventBus.register(channel, handler)} where the handler receives the message object.</li>
 *   <li>Messages are typed as {@code Object}; this class stores and forwards them opaquely.</li>
 *   <li>Input channel names in rules must exist in {@code inputChannels}; otherwise construction fails.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * List<String> inputs = List.of("A", "B", "C");
 *
 * Map<String, String> rule0 = Map.of(
 *     "A", "*",   // at least 1 new on A
 *     "B", "2"    // at least 2 new on B
 * );
 *
 * Map<String, String> rule1 = Map.of(
 *     "C", "r:OK" // at least 1 new on C and latest equals "OK"
 * );
 *
 * List<Map<String, String>> rules = List.of(rule0, rule1);
 *
 * Actor actor = new Actor(eventBus, rules, inputs, List.of("OUT")) {
 *   @Override public void stop() { /* cleanup *\/ }
 *   @Override public void fireFunction(Map<String, Object> latest) {
 *     // react to satisfied rules (latest contains A,B,C latest values)
 *   }
 * };
 * }</pre>
 */
public abstract class Actor extends Service {
  /**
   * User-provided list of firing rules. Each rule is a mapping from input channel name to a token:
   * <ul>
   *   <li>{@code "*"} — at least one new item since this rule last fired</li>
   *   <li>{@code "N"} — at least N new items (N &ge; 1) since this rule last fired</li>
   *   <li>{@code "r:v"} — at least one new item and latest value equals {@code v}</li>
   * </ul>
   * Rules are evaluated independently and can fire concurrently (in sequence) within one evaluation pass.
   */
  // Each rule is a map: channel -> token ("*", "N", "r:v")
  private final List<Map<String, String>> firingRules; // raw user-facing config

  /**
   * Identifier of the LimitClassifier instance
   */
  protected final String id;

  /**
   * Names of input channels that this actor subscribes to and maintains state for.
   * All channels referenced in {@link #firingRules} must appear here.
   */
  protected final List<String> inputChannels;

  /**
   * Names of output channels this actor may use (not used internally in this base class).
   */
  protected final List<String> outputChannels;

  // ==== Compiled rule state ====
  /**
   * Parsed (compiled) view of {@link #firingRules}: for each rule, maps channel to a structured {@link FiringRule}.
   */
  // Parsed rules: channel -> Condition
  private final List<Map<String, FiringRule>> compiledRules = new ArrayList<>();


  /**
   * Latest message object observed for each input channel. Updated on every incoming message.
   */
  // Latest value per channel
  private final Map<String, Object> latestByChannel = new ConcurrentHashMap<>();

  /**
   * Monotonic per-channel sequence (count of received messages). Incremented on every incoming message.
   */
  // Monotonic sequence (count of received messages) per channel
  private final Map<String, Long> channelSeq = new ConcurrentHashMap<>();

  /**
   * Lock to ensure atomic update-and-evaluate. Protects rule evaluation, snapshot creation,
   * and invocation of {@link #fireFunction(Map)} to prevent races that could cause missed or duplicated firings.
   */
  // Concurrency control for evaluation (update+evaluate as an atomic unit)
  private final ReentrantLock evalLock = new ReentrantLock();

  /**
   * Constructs an {@code Actor} that subscribes to the given {@code inputChannels}, evaluates the provided
   * {@code firingRules}, and optionally exposes {@code outputChannels}.
   *
   * <p>For each input channel, this actor:
   * <ol>
   *   <li>Initializes its sequence counter and latest value holder;</li>
   *   <li>Registers a message handler on the {@link EventBus} that updates state and evaluates rules.</li>
   * </ol>
   *
   * <p>During construction, rules are compiled to internal {@link FiringRule}s and validated for channel existence.
   *
   * @param eventBus      the event bus used to subscribe to input channels and receive messages; must not be {@code null}
   * @param id            the identifier of the specified Actor. Commonly set in the config.
   * @param firingRules   list of rule maps ({@code channel -> token}), where token is one of {@code "*"}, a positive integer string, or {@code "r:v"}; must not be {@code null}
   * @param inputChannels list of input channels to subscribe to; must contain all channels referenced in {@code firingRules}; must not be {@code null}
   * @param outputChannels list of output channels (not used internally by this class but exposed via {@link #getOutputChannels()}); must not be {@code null}
   * @throws NullPointerException if any argument is {@code null}
   * @throws IllegalArgumentException if a rule is empty or references a channel not present in {@code inputChannels}, or contains an invalid token
   */
  protected Actor(EventBus eventBus,
                  String id,
                  List<Map<String, String>> firingRules,
                  List<String> inputChannels,
                  List<String> outputChannels) {
    super(eventBus);
    this.id = id;
    this.firingRules = firingRules;
    this.inputChannels = List.copyOf(inputChannels);
    this.outputChannels = List.copyOf(outputChannels);

    // Pre-compile rules
    compileRules();

    // Register listeners for each input channel
    for (String inChannel : this.inputChannels) {
      // Init counters
      channelSeq.put(inChannel, 0L);
      latestByChannel.put(inChannel, new JSONObject());

      this.eventBus.register(inChannel, msg -> onMessage(inChannel, msg));
    }
  }

  /**
   * @param latestSnapshot Unmodifiable snapshot of the latest value of ALL input channels (channel -> latest object).
   */
  public abstract void fireFunction(Map<String, Object> latestSnapshot);

  /**
   * @return the list of input channels this actor subscribes to; never {@code null}
   */
  public List<String> getInputChannels() {
    return inputChannels;
  }

  /**
   * @return the list of output channels associated with this actor (not used internally by this class); never {@code null}
   */
  public List<String> getOutputChannels() {
    return outputChannels;
  }


  /**
   * Internal message handler invoked for each incoming message on a subscribed channel.
   * <ol>
   *   <li>Updates the channel's latest value and increments its sequence counter;</li>
   *   <li>Triggers evaluation of all firing rules.</li>
   * </ol>
   *
   * @param channel the input channel name on which the message was received
   * @param msg the received message (typed as {@code Object})
   */
  private void onMessage(String channel, Object msg) {
    // Update per-channel state
    latestByChannel.put(channel, msg);
    channelSeq.merge(channel, 1L, Long::sum);
    evaluateRules();
  }

  /**
   * Evaluates all compiled rules under a mutual exclusion lock. For each rule:
   * <ol>
   *   <li>Computes the number of new messages ({@code delta}) since the rule last fired on each referenced channel;</li>
   *   <li>Checks whether the channel condition is satisfied given {@code delta} and the channel's latest value;</li>
   *   <li>If all channel conditions are satisfied, constructs an unmodifiable snapshot of all input channels'
   *       latest values, and invokes
   *       {@link #fireFunction(Map)}.</li>
   * </ol>
   * Multiple rules can fire within a single evaluation pass if they are satisfied simultaneously.
   */


  private void evaluateRules() {
    evalLock.lock();
    try {
      for (int i = 0; i < compiledRules.size(); i++) {
        Map<String, FiringRule> rule = compiledRules.get(i);

        boolean satisfied = true;
        for (Map.Entry<String, FiringRule> e : rule.entrySet()) {
          String channel = e.getKey();
          FiringRule cond = e.getValue();

          long curSeq = channelSeq.getOrDefault(channel, 0L);
          Object latest = latestByChannel.get(channel);

          // For global consumption, delta = curSeq (since last reset)
          if (!testCondition(cond, curSeq, latest)) {
            satisfied = false;
            break;
          }
        }

        if (satisfied) {
          // create snaptshot of latest values
          Map<String, Object> unmodifiableSnapshot = buildSnapshot();
          // reset channels after consumption
          for (String ch : inputChannels) {
            channelSeq.put(ch, 0L);
          }

          fireFunction(unmodifiableSnapshot);
          break;
        }
      }
    } finally {
      evalLock.unlock();
    }
  }

  @NotNull
  private Map<String, Object> buildSnapshot() {
    // Build snapshot of all channels
    Map<String, Object> snapshot = new LinkedHashMap<>();
    for (String ch : inputChannels) {
      JSONObject dataPoint = (JSONObject) latestByChannel.get(ch);
      snapshot.put(ch, dataPoint.has("value") ? dataPoint.get("value") : 0);
    }
    return Collections.unmodifiableMap(snapshot);
  }


  // ==== Rule compilation & condition logic ====

  /**
   * Compiles the user-provided {@link #firingRules} into internal {@link FiringRule}s.
   * Validates that each rule is non-empty and references only known input channels.
   *
   * @throws IllegalArgumentException if a rule is empty or references an unknown input channel
   * @throws IllegalArgumentException if a token is syntactically invalid (see {@link #parseCondition(String)})
   */
  private void compileRules() {
    for (int i = 0; i < firingRules.size(); i++) {
      Map<String, String> ruleConfig = firingRules.get(i);
      if (ruleConfig == null || ruleConfig.isEmpty()) {
        throw new IllegalArgumentException("Rule %d is empty or null.".formatted(i));
      }

      Map<String, FiringRule> compiled = new HashMap<>();

      for (Map.Entry<String, String> e : ruleConfig.entrySet()) {
        String channel = e.getKey();
        String token = e.getValue();

        if (!inputChannels.contains(channel)) {
          throw new IllegalArgumentException(
            "Rule %d references unknown input channel '%s'".formatted(i, channel));
        }

        FiringRule condition = parseCondition(token);
        compiled.put(channel, condition);
      }

      compiledRules.add(Collections.unmodifiableMap(compiled));
    }
  }

  /**
   * Parses a condition token into a structured {@link FiringRule}.
   * <ul>
   *   <li>{@code "*"} -> {@link RuleType#ANY}</li>
   *   <li>{@code "N"} (digits) -> {@link RuleType#AT_LEAST} with {@code n = N}, where {@code N >= 1}</li>
   *   <li>{@code "r:v"} -> {@link RuleType#REQUIRE_VALUE} with expected {@code v}</li>
   * </ul>
   *
   * @param token the condition token string; must not be {@code null}
   * @return a parsed {@link FiringRule}
   * @throws IllegalArgumentException if the token is {@code null}, {@code N <= 0} for numeric tokens, or an unsupported form
   */
  private FiringRule parseCondition(String token) {
    if (token == null) {
      throw new IllegalArgumentException("Condition token must not be null.");
    }
    String t = token.trim();

    if ("*".equals(t)) {
      return FiringRule.any();
    }

    // Pure number => at least N updates
    if (t.matches("\\d+")) {
      long n = Long.parseLong(t);
      if (n <= 0) {
        throw new IllegalArgumentException("Numeric condition must be >= 1, was: %s".formatted(t));
      }
      return FiringRule.atLeast(n);
    }

    // r:v => require latest value equals v AND at least one new item
    if (t.startsWith("r:")) {
      String v = t.substring(2);
      return FiringRule.requireValue(v);
    }

    throw new IllegalArgumentException("Unsupported condition token: '%s'. Use '*', a positive integer 'N', or 'r:v'.".formatted(token));
  }

  /**
   * Tests whether a channel satisfies a given {@link FiringRule}, based on the number of new messages
   * since the rule last fired ({@code delta}) and the channel's current latest value.
   *
   * @param cond   the compiled condition
   * @param delta  the number of new messages since the rule's last update on this channel (>= 0)
   * @param latest the latest observed value for the channel (may be {@code null})
   * @return {@code true} if the condition is satisfied, {@code false} otherwise
   */
  private boolean testCondition(FiringRule cond, long delta, Object latest) {
    return switch (cond.type()) {
      case ANY -> delta >= 1;
      case AT_LEAST -> delta >= cond.n();
      case REQUIRE_VALUE -> delta >= 1 && valueMatchesExpected(latest, cond.value());
    };
  }

  /**
   * Hook for customizing equality checks used by {@code "r:v"} conditions.
   * <p>
   * The default implementation compares string representations:
   * <pre>{@code
   * Objects.toString(actual, null).equals(expected)
   * }</pre>
   * Override this to perform typed or domain-specific comparisons.
   *
   * @param actual   the latest channel value (may be {@code null})
   * @param expected the expected value string provided in the rule token (never {@code null})
   * @return {@code true} if the actual value should be considered equal to the expected one
   */
  protected boolean valueMatchesExpected(Object actual, String expected) {
    return Objects.equals(Objects.toString(actual, null), expected);
  }

}
