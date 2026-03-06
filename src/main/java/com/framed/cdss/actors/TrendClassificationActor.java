
package com.framed.cdss.actors;

import com.framed.cdss.Actor;
import com.framed.cdss.utils.SlopeUtils;
import com.framed.cdss.utils.TrendDirection;
import com.framed.core.EventBus;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.*;

import static com.framed.cdss.utils.CDSSUtils.*;
import static com.framed.cdss.utils.SlopeUtils.computeSlope;

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
 *   slope &lt;= -delta
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
public class TrendClassificationActor extends Actor {

    private final TrendDirection direction;
    /** Sliding window size per input channel; must be >= 2. */
    private final Integer windowSize;

    /**
     * Decrease threshold. Warning if slope <= -delta.
     * Units: "value units per sample" (e.g., SpO₂ percent per sample).
     */
    private final Integer delta;

    /**
     * Number of consecutive windows that must satisfy the warning condition
     * before emitting a warning. Must be >= 1.
     */
    private final Integer persistWindows;

    /** Per-channel sliding window of the most recent values. */
    private final Deque<SlopeUtils.Sample> window;

    /** Per-channel counter of consecutive "trend condition met" evaluations. */
    private Integer consecutiveHits;

    /**
     * Constructs a {@code TrendClassifier} that warns on decreasing trends using regression slope,
     * with configurable persistence.
     *
     * @param eventBus        the event bus used by this actor; must not be {@code null}
     * @param id              identifier of this classifier (often set in configuration); must not be {@code null}
     * @param firingRules     firing rules as accepted by {@link Actor}; must not be {@code null}
     * @param inputChannel   channels observed by this classifier; must not be {@code null}
     * @param outputChannels  channels to publish warning messages to; must not be {@code null}
     * @param windowSize      number of recent samples per channel used for trend detection; must be {@code >= 2}
     * @param delta           non-negative threshold per Channel; warning if slope {@code <= -delta}
     * @param persistWindows  required number of consecutive satisfied windows; must be {@code >= 1}
     *
     * @throws NullPointerException     if any required argument is {@code null}
     * @throws IllegalArgumentException if {@code windowSize < 2}, {@code delta < 0}, or {@code persistWindows < 1}
     */
    public TrendClassificationActor(EventBus eventBus,
                                    String id,
                                    JSONArray firingRules,
                                    String inputChannel,
                                    JSONArray outputChannels,
                                    int windowSize,
                                    int persistWindows,
                                    int delta,
                                    String direction
                           ) {

        super(eventBus, id, parseFiringRulesJson(firingRules), List.of(inputChannel), parseChannelListJson(outputChannels));

        this.windowSize = windowSize;
        this.persistWindows = persistWindows;
        // TODO: allow for doubles here.
        this.delta = delta;

        this.window = new ArrayDeque<>(windowSize);
        consecutiveHits = 0;



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
            Instant ts = (Instant) latestSnapshot.getOrDefault("%s-timestamp".formatted(channel), Instant.now());

            if (raw != null) {
                Deque<SlopeUtils.Sample> curWindow = getWindow(raw, ts);

                // Need a full window to evaluate trend
                if (window.size() >= windowSize) {
                    double slope = computeSlope(curWindow);
                    decideWarning(slope);
                }

            }
        }
    }

    private void decideWarning(double slope) {
        boolean conditionMet = false;
        switch (direction) {
            case DOWN ->  conditionMet = slope <= -delta;
            case UP -> conditionMet = slope >= delta;
            case BOTH -> conditionMet = (slope <= -delta) || (slope >= delta);
        }

        // Persistence logic
        int hits = consecutiveHits;
        if (conditionMet) {
            hits++;
        } else {
            hits = 0;
        }
        consecutiveHits = hits;

        int warnValue = 0;
        if (conditionMet && hits >= persistWindows){
            warnValue = 1;
        }
        publishResult(eventBus, formatter, warnValue, id, outputChannels);


    }

    @NotNull
    private Deque<SlopeUtils.Sample> getWindow(Object raw, Instant ts) {
        double value = switch (raw) {
            case Integer i -> i.doubleValue();
            case Number n -> n.doubleValue();
            default -> throw new ClassCastException(
                    "Non-numeric value: %s".formatted(raw.getClass().getName())
            );
        };

        // Maintain sliding window
        Deque<SlopeUtils.Sample> curWindow = this.window;
        if (curWindow.size() == windowSize) {
            curWindow.removeFirst();
        }
        curWindow.addLast(new SlopeUtils.Sample(ts, value));
        return curWindow;
    }

}
