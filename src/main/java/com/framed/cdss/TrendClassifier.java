
package com.framed.cdss;

import com.framed.cdss.utils.TrendDirection;
import com.framed.core.EventBus;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.*;

import static com.framed.cdss.utils.CDSSUtils.*;

/**
 * A specialized {@link Actor} that detects a decreasing trend (downward drift)
 * in numeric channel values using a sliding window and a slope-based metric.
 *
 * <h2>Trend definition (slope-based)</h2>
 * <p>For each channel, this classifier maintains the last {@code windowSize} numeric samples:</p>
 *
 * <pre>
 *   y0, y1, ..., y(n-1)   with n = windowSize
 * </pre>
 *
 * <p>It computes the slope {@code b} of the best-fit line {@code y = a + b*x}
 * using ordinary least squares, where {@code x} is the sample index {@code 0..n-1}:</p>
 *
 * <pre>
 *   b = Σ((xi - x̄)(yi - ȳ)) / Σ((xi - x̄)^2)
 * </pre>
 *
 * <p>The slope is measured in "value units per sample". For SpO₂, that is "% per sample".</p>
 *
 * <h2>Directional warning condition</h2>
 * <p>This classifier is configured to warn on decreasing trends only. A warning is raised when:</p>
 *
 * <pre>
 *   slope <= -delta
 * </pre>
 *
 * <p>where {@code delta} is a non-negative threshold.</p>
 *
 * <h2>Persistence / Debouncing</h2>
 * <p>To reduce false positives due to transient artifacts, the classifier optionally requires the
 * decreasing condition to hold for {@code persistWindows} consecutive evaluated windows before it emits a warning.
 * By default, {@code persistWindows = 2}.</p>
 *
 * <p>Once in the "warning" state for a channel, this implementation emits a warning only on state entry,
 * and resets once the condition is no longer met.</p>
 *
 * <h2>Inputs and outputs</h2>
 * <ul>
 *   <li><strong>Input:</strong> {@link #fireFunction(Map)} is invoked with a snapshot {@code channel -> value}.</li>
 *   <li><strong>Output:</strong> A warning message is published to each configured output channel and also logged.</li>
 * </ul>
 *
 * <p><strong>Assumptions:</strong> snapshot values are numeric ({@link Number}). Non-numeric values throw
 * {@link ClassCastException}.</p>
 *
 */
public class TrendClassifier extends Actor {

    private final TrendDirection direction;
    /** Sliding window size per input channel; must be >= 2. */
    private final Map<String, Integer> windowSizes;

    /**
     * Decrease threshold. Warning if slope <= -delta.
     * Units: "value units per sample" (e.g., SpO₂ percent per sample).
     */
    private final Map<String, Integer> deltaPerChannel;

    /**
     * Number of consecutive windows that must satisfy the warning condition
     * before emitting a warning. Must be >= 1.
     */
    private final Map<String, Integer> persistWindows;

    /** Per-channel sliding window of the most recent values. */
    private final Map<String, Deque<Double>> windows = new HashMap<>();

    /** Per-channel counter of consecutive "trend condition met" evaluations. */
    private final Map<String, Integer> consecutiveHits = new HashMap<>();

    /**
     * Constructs a {@code TrendClassifier} that warns on decreasing trends using regression slope,
     * with configurable persistence.
     *
     * @param eventBus        the event bus used by this actor; must not be {@code null}
     * @param id              identifier of this classifier (often set in configuration); must not be {@code null}
     * @param firingRules     firing rules as accepted by {@link Actor}; must not be {@code null}
     * @param inputChannels   channels observed by this classifier; must not be {@code null}
     * @param outputChannels  channels to publish warning messages to; must not be {@code null}
     * @param windowSizes      number of recent samples per channel used for trend detection; must be {@code >= 2}
     * @param deltas           non-negative threshold per Channel; warning if slope {@code <= -delta}
     * @param persistWindows  required number of consecutive satisfied windows; must be {@code >= 1}
     *
     * @throws NullPointerException     if any required argument is {@code null}
     * @throws IllegalArgumentException if {@code windowSize < 2}, {@code delta < 0}, or {@code persistWindows < 1}
     */
    public TrendClassifier(EventBus eventBus,
                              String id,
                              JSONArray firingRules,
                              JSONArray inputChannels,
                              JSONArray outputChannels,
                              JSONObject windowSizes,
                              JSONObject persistWindows,
                              JSONObject deltas,
                              String direction
                           ) {

        super(eventBus, id, parseFiringRulesJson(firingRules), parseChannelListJson(inputChannels), parseChannelListJson(outputChannels));

        this.windowSizes = parsePerChannelIntMap(windowSizes, 2);
        this.persistWindows = parsePerChannelIntMap(persistWindows, 1);
        // TODO: allow for doubles here.
        this.deltaPerChannel = parsePerChannelIntMap(deltas, 0);


        for (String ch : this.inputChannels) {
            int windowSize = this.windowSizes.getOrDefault(ch, 2);
            windows.put(ch, new ArrayDeque<>(windowSize));
            consecutiveHits.put(ch, 0);
        }


        this.direction = TrendDirection.valueOf(direction);
    }

    /**
     * Updates per-channel windows with the newest snapshot values, computes the regression slope when enough
     * samples exist, and emits warnings for channels exhibiting a persistent decreasing trend.
     *
     * <p>Behavior per channel:</p>
     * <ol>
     *   <li>If the channel is missing in the snapshot, it is skipped.</li>
     *   <li>The numeric value is appended to the channel window (oldest removed if full).</li>
     *   <li>Once the window is full, compute regression slope over the window.</li>
     *   <li>If slope {@code <= -delta}, increment the persistence counter; otherwise reset it.</li>
     *   <li>If the counter reaches {@code persistWindows}, enter warning state and emit a warning once.</li>
     *   <li>If slope no longer meets the condition, exit warning state.</li>
     * </ol>
     *
     * @param latestSnapshot snapshot map {@code channel -> value}; values must be {@link Number}
     * @throws ClassCastException if any encountered snapshot value is not numeric
     */
    @Override
    public void fireFunction(Map<String, Object> latestSnapshot) {
        if (latestSnapshot == null || latestSnapshot.isEmpty()) {
            return; // or throw; depending on your system's expectations
        }

        for (String channel : inputChannels) {
            Object raw = latestSnapshot.get(channel);
            if (raw != null) {
                Deque<Double> window = getWindow(channel, raw);

                // Need a full window to evaluate trend
                if (window.size() >= windowSizes.get(channel)) {
                    double slope = computeRegressionSlope(window);
                    decideWarning(channel, slope, window);
                }

            }
        }
    }

    private void decideWarning(String channel, double slope, Deque<Double> window) {
        // Decreasing trend condition (DOWN only)
        boolean conditionMet = slope <= -deltaPerChannel.get(channel);

        // Persistence logic
        int hits = consecutiveHits.get(channel);
        if (conditionMet) {
            hits++;
        } else {
            hits = 0;
        }
        consecutiveHits.put(channel, hits);

        boolean shouldWarnNow = conditionMet && hits >= persistWindows.get(channel);

        if (shouldWarnNow ) {
            emitWarning(channel, slope, window);
        }
    }

    @NotNull
    private Deque<Double> getWindow(String channel, Object raw) {
        double value = switch (raw) {
            case Integer i -> i.doubleValue();
            case Number n -> n.doubleValue();
            default -> throw new ClassCastException(
                    "Non-numeric value for channel '%s': %s".formatted(channel, raw.getClass().getName())
            );
        };

        // Maintain sliding window
        Deque<Double> window = windows.get(channel);
        if (window.size() == windowSizes.get(channel)) {
            window.removeFirst();
        }
        window.addLast(value);
        return window;
    }

    /**
     * Computes the ordinary least squares regression slope over a window using sample indices {@code 0..n-1}.
     *
     * <p>The returned slope is in "value units per sample" and is robust to noise compared to
     * endpoint-based differences.</p>
     *
     * @param window deque containing exactly {@code n} samples (n >= 2)
     * @return regression slope (units per sample)
     */
    private double computeRegressionSlope(Deque<Double> window) {
        int n = window.size();
        double meanX = (n - 1) / 2.0; // mean of 0...n-1

        double[] y = new double[n];
        double meanY = 0.0;

        int i = 0;
        for (double v : window) {
            y[i++] = v;
            meanY += v;
        }
        meanY /= n;

        double num = 0.0; // Σ (xi - meanX)(yi - meanY)
        double den = 0.0; // Σ (xi - meanX)^2
        for (i = 0; i < n; i++) {
            double dx = i - meanX;
            double dy = y[i] - meanY;
            num += dx * dy;
            den += dx * dx;
        }

        return den == 0.0 ? 0.0 : (num / den);
    }

    /**
     * Emits a decreasing-trend warning for the given channel.
     *
     * <p>This method logs a warning and publishes a structured warning message to each configured output channel.</p>
     *
     * @param channel the channel for which the warning is emitted
     * @param slope   regression slope (negative indicates decreasing trend)
     * @param window  the current evaluation window (used to include context)
     */
    private void emitWarning(String channel, double slope, Deque<Double> window) {
        // Publish warning event
        JSONObject result = new JSONObject();
        result.put("timestamp", LocalDateTime.now().format(formatter));
        result.put("className", id);
        result.put("inputChannel", channel);

        // Keep "value" consistent with other classifiers: store the computed metric
        result.put("value", slope);

        // Add metadata for consumers
        result.put("trendMetric", "REGRESSION_SLOPE");
        result.put("direction", this.direction);
        result.put("windowSize", windowSizes.get(channel));
        result.put("delta", deltaPerChannel.get(channel));
        result.put("persistWindows", persistWindows);

        result.put("windowFirst", window.peekFirst());
        result.put("windowLast", window.peekLast());

        for (String out : outputChannels) {
            result.put("channelID", out);
            eventBus.publish("CDSS.addresses", out);
            eventBus.publish(out, result);
        }
    }
}
