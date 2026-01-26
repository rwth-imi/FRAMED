
package com.framed.cdss;

import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.framed.cdss.CDSSUtils.*;


/**
 * A specialized {@link Actor} that classifies numeric input values per channel
 * based on a configured, ascending-sorted list of upper bounds.
 *
 * <p><strong>Classification logic:</strong>
 * For each channel, the classifier receives a list of upper bounds:
 *
 * <pre>
 *  channelA → [b0, b1, b2, ..., bN]   // sorted ascending
 * </pre>
 *
 * Given an incoming numeric value {@code v}, the classifier returns the
 * <em>first index</em> {@code i} such that:
 *
 * <pre>
 *  v <= b[i]
 * </pre>
 *
 * If no such upper bound exists (i.e., {@code v} is greater than all bounds),
 * the classifier returns:
 *
 * <pre>
 *  bounds.size()
 * </pre>
 *
 * <p><strong>Example:</strong>
 *
 * Suppose the configuration is:
 *
 * <pre>
 *  "SpO2": [90, 93, 96, 100]
 * </pre>
 *
 * Then:
 *
 * <ul>
 *   <li>{@code v = 89  → index = 0} (89 ≤ 90)</li>
 *   <li>{@code v = 92  → index = 1} (92 ≤ 93)</li>
 *   <li>{@code v = 95  → index = 2} (95 ≤ 96)</li>
 *   <li>{@code v = 100 → index = 3} (100 ≤ 100)</li>
 *   <li>{@code v = 150 → index = 4} (no bound reached → size = 4)</li>
 * </ul>
 *
 * <p>The result is therefore a discrete "classification bin" for each channel.
 *
 * <p><strong>Input:</strong>
 * The actor listens to a set of input channels and expects incoming snapshots
 * in {@link #fireFunction(Map)} where each channel maps to a numeric value
 * (any {@link Number} subtype is accepted).
 *
 * <p><strong>Output:</strong>
 * For each input event, the actor publishes a result message containing:
 *
 * <ul>
 *   <li>{@code timestamp}: ISO timestamp</li>
 *   <li>{@code value}:     the computed classification index</li>
 *   <li>{@code className}: this classifier's ID</li>
 *   <li>{@code channelID}: the output channel used</li>
 * </ul>
 *
 * <p><strong>Limits configuration:</strong>
 * Provided as a JSON object of the form:
 *
 * <pre>
 * {
 *   "channel1": [10, 20, 30],
 *   "channel2": [5, 7, 9, 12],
 *   ...
 * }
 * </pre>
 *
 * All lists are required to be numeric and are automatically sorted ascending.
 *
 * <p><strong>Constraints:</strong>
 * <ul>
 *   <li>Limited channels must be a subset of the input channels.</li>
 *   <li>Each value in the snapshot must be numeric.</li>
 *   <li>Missing channel values will result in an exception.</li>
 * </ul>
 *
 * <p>This class generalizes a "limits classifier" into multiple dynamically
 * sized classification bins instead of fixed lower/upper bounds.
 */

public class LimitClassifier extends Actor {

  /**
   * Per-channel list of sorted ascending numeric upper bounds.
   * Example:
   *  channel → [90, 93, 96, 100]
   */
  Map<String, List<Float>> limits;


  /**
   * Constructs a {@code LimitClassifier}.
   *
   * @param eventBus        the event bus used for input and output messaging
   * @param id              the identifier for this classifier
   * @param firingRules     firing rules forwarded to {@link Actor}
   * @param inputChannels   JSON array of input channel names
   * @param outputChannels  JSON array of output channel names
   * @param limits      a JSON object describing upper-bound lists per channel
   *
   * <p><strong>Format of limitsJson:</strong>
   * <pre>
   * {
   *   "channelA": [limit0, limit1, limit2, ...],
   *   "channelB": [...],
   *   ...
   * }
   * </pre>
   *
   * <p>The lists must contain numeric values. They will be sorted ascending.
   *
   * @throws IllegalArgumentException
   *         if limit channels are not a subset of input channels
   */

  public LimitClassifier(EventBus eventBus,
                         String id,
                         JSONArray firingRules,
                         JSONArray inputChannels,
                         JSONArray outputChannels,
                         JSONObject limits) {

    super(eventBus, id,
            parseFiringRulesJson(firingRules),
            parseChannelListJson(inputChannels),
            parseChannelListJson(outputChannels));

    if (!Set.copyOf(this.inputChannels).containsAll(limits.keySet())) {
      throw new IllegalArgumentException("Limited channels must be a subset of input channels.");
    }

    this.limits = parseLimitsJson(limits);
  }



  /**
   * Classifies each channel's input value using its configured list of
   * ascending upper bounds.
   *
   * <p>For each channel, given:
   * <pre>
   * bounds = [b0, b1, b2, ..., bN]  // sorted ascending
   * value = v
   * </pre>
   *
   * the returned index is the first {@code i} such that:
   *
   * <pre>
   * v <= b[i]
   * </pre>
   *
   * If {@code v} exceeds all bounds, the result is:
   *
   * <pre>
   * bounds.size()
   * </pre>
   *
   * @param snapshot a map channel → numeric value (must contain all required channels)
   * @return a map channel → classification index
   *
   * @throws IllegalStateException
   *         if snapshot is null or missing a required channel
   *
   * @throws ClassCastException
   *         if a snapshot value is not numeric
   */
  public Map<String, Integer> checkLimits(Map<String, Object> snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      throw new IllegalStateException("No snapshot available yet.");
    }

    Map<String, Integer> alarmStates = new HashMap<>();

    for (String channel : this.getInputChannels()) {
      if (!limits.containsKey(channel)) {
        continue;
      }

      Object raw = snapshot.get(channel);
      if (raw == null) {
        throw new IllegalStateException("Missing value for channel: %s".formatted(channel));
      }

      int value = switch (raw) {
        case Integer i -> i;
        case Number n -> n.intValue();
        default -> throw new ClassCastException("Non-numeric value on channel: %s".formatted(raw));
      };

      List<Float> bounds = limits.get(channel);

      // find first index where upperBound >= value
      int index = 0;
      for (; index < bounds.size(); index++) {
        if (value <= bounds.get(index)) {
          break;
        }
      }

      alarmStates.put(channel, index);
    }

    return alarmStates;
  }


  /**
   * Receives a snapshot from the runtime, classifies all channel values via
   * {@link #checkLimits(Map)}, and publishes one result message per output channel.
   *
   * <p>The output message contains:
   * <ul>
   *   <li>{@code timestamp}: ISO-8601 timestamp</li>
   *   <li>{@code value}: classification index computed by {@link #checkLimits(Map)}</li>
   *   <li>{@code className}: this classifier's ID</li>
   *   <li>{@code channelID}: the output channel the message is published to</li>
   * </ul>
   *
   * @param latestSnapshot a map channel → numeric value
   */

  @Override
  public void fireFunction(Map<String, Object> latestSnapshot) {
    Map<String, Integer> states = checkLimits(latestSnapshot);

    for (String ch : inputChannels) {
      JSONObject result = new JSONObject();
      result.put("timestamp", LocalDateTime.now().format(formatter));
      result.put("value", states.get(ch));
      result.put("className", id);

      for (String out : outputChannels) {
        result.put("channelID", out);
        eventBus.publish("CDSS.addresses", out);
        eventBus.publish(out, result);
      }
    }
  }
}

