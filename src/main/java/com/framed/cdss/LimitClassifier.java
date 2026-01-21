
package com.framed.cdss;

import com.framed.core.EventBus;
import kotlin.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A specialized {@link Actor} that evaluates per-channel numeric limit states
 * (below lower bound, within range, above upper bound) based on the latest
 * input snapshot provided by {@link #fireFunction(Map)}.
 *
 * <p>Limits are provided as a map {@code channel -> Pair<lower, upper>}.
 * The {@link #checkLimits(Map)} method returns a map {@code channel -> state}
 * with:
 * <ul>
 *   <li>{@code -1} if value &lt; lower bound</li>
 *   <li>{@code 0}  if lower bound &le; value &le; upper bound</li>
 *   <li>{@code 1}  if value &gt; upper bound</li>
 * </ul>
 *
 * <p>This class stores the latest snapshot delivered by {@link #fireFunction(Map)}
 * and evaluates against it when {@link #checkLimits(Map)} is called.
 *
 * <p>Note: This class assumes input values are numeric (e.g., {@link Integer})
 * or at least {@link Number}-typed. If values can be non-numeric, consider
 * overriding this class or adding validation before calling {@link #checkLimits(Map)}.
 */
public abstract class LimitClassifier extends Actor {
  /**
   * Per-channel numeric limits: channel -> (lowerBound, upperBound).
   */
  Map<String, Pair<Integer, Integer>> limits;

  /**
   * Constructs a {@code LimitClassifier}.
   *
   * @param eventBus      the event bus
   * @param firingRules   firing rules as accepted by {@link Actor} (channel -> token)
   * @param inputChannels the input channels observed by this actor
   * @param outputChannels output channels (not used directly here)
   * @param limits        per-channel limits (lower, upper). The set of limited channels
   *                      must be equal to or a subset of {@code inputChannels}.
   * @throws IllegalArgumentException if limited channels are not a subset of input channels
   */
  protected LimitClassifier(EventBus eventBus,
                            List<Map<String, String>> firingRules,
                            List<String> inputChannels,
                            List<String> outputChannels,
                            Map<String, Pair<Integer, Integer>> limits) {
    super(eventBus, firingRules, inputChannels, outputChannels);
    // validate that input channels are equal to limited channels or at least that limited channels are a subset of input channels.

    if (!Set.copyOf(inputChannels).equals(limits.keySet())) {
      logger.warning("InputChannels are not equal to limited channels.");
      if (!Set.copyOf(inputChannels).containsAll(limits.keySet())) {
        throw new IllegalArgumentException("Limited channels are not a subset of input channels.");
      }
    }
    this.limits = limits;
  }

  /**
   * Evaluates current values against configured {@link #limits} using the most recent snapshot
   * delivered via {@link #fireFunction(Map)}.
   *
   * <p>Return values per channel:
   * <ul>
   *   <li>{@code -1}: value &lt; lower bound</li>
   *   <li>{@code 0}:  lower bound &le; value &le; upper bound</li>
   *   <li>{@code 1}:  value &gt; upper bound</li>
   * </ul>
   *
   * <p>If a channel is missing in the snapshot or the value is not numeric,
   * this method will throw an {@link IllegalStateException} or {@link ClassCastException}.
   *
   * @return a map of channel -> alarm state
   * @throws IllegalStateException if no snapshot has been received yet or a channel value is missing
   * @throws ClassCastException if a channel value is not numeric
   */
  public Map<String, Integer> checkLimits(Map<String, Object> snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      throw new IllegalStateException("No snapshot available yet. fireFunction() has not been invoked.");
    }

    Map<String, Integer> alarmStates = new HashMap<>();
    for (String channel : this.getInputChannels()) {
      if (!limits.containsKey(channel)) {
        // If a channel has no defined limits, you may decide to skip or set 0.
        // Here we skip channels without limits.
        continue;
      }

      Object raw = snapshot.get(channel);
      if (raw == null) {
        throw new IllegalStateException("Missing value for channel '%s' in the latest snapshot.".formatted(channel));
      }

      int value;
      switch (raw) {
        case Integer i -> value = i;
        case Number n -> value = n.intValue();
        default -> throw new ClassCastException("Value for channel '%s' is not numeric: %s".formatted(channel, raw.getClass().getName()));
      }

      Pair<Integer, Integer> bounds = limits.get(channel);
      int lower = bounds.component1();
      int upper = bounds.component2();

      if (value < lower) {
        alarmStates.put(channel, -1);
      } else if (value > upper) {
        alarmStates.put(channel, 1);
      } else {
        alarmStates.put(channel, 0);
      }
    }
    return alarmStates;
  }
}

