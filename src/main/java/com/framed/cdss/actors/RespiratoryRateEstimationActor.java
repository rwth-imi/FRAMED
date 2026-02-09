package com.framed.cdss.actors;

import com.framed.cdss.Actor;
import com.framed.cdss.utils.SlopeUtils;
import com.framed.core.EventBus;
import org.json.JSONArray;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.framed.cdss.utils.CDSSUtils.parseChannelListJson;
import static com.framed.cdss.utils.CDSSUtils.publishResult;
import static com.framed.cdss.utils.SlopeUtils.computeSlope;

/**
 * <p>
 * Actor for estimating respiratory rate (RR) from a continuous EtCO₂ waveform
 * using a robust slope‑based breath detection algorithm. This component consumes
 * streaming etCO2 waveform samples and publishes a continuously updated respiratory
 * rate (in breaths/min) to a designated output channel.
 * </p>
 *
 * <h2>Triggering</h2>
 * <p>
 * This actor is invoked for every new value on the configured
 * <strong>ETCO2_WAVE</strong> input channel. Messages are handled in a
 * non‑consuming manner, i.e., other actors may also subscribe to the same input.
 * </p>
 *
 * <h2>Input</h2>
 * <ul>
 *     <li><strong>ETCO2_WAVE</strong> — A numeric CO₂ concentration sample
 *     (mmHg or %) associated with a timestamp. The snapshot should contain:
 *     <pre>
 *     {
 *         "value": &lt;double&gt;,
 *         "timestamp": "&lt;ISO‑8601 string&gt;"
 *     }
 *     </pre>
 *     The timestamp is read from {@code &lt;channel&gt;-timestamp} if attached by
 *     the event bus; otherwise {@link Instant#now()} is used.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <ul>
 *     <li>Publishes RR (breaths/min) as a JSON object to the configured
 *     <strong>rrOutChannel</strong>. A typical message is:
 *     <pre>
 *     {
 *         "rr": &lt;double breaths/min&gt;,
 *         "timestamp": "&lt;ISO‑8601&gt;"
 *     }
 *     </pre>
 *     </li>
 * </ul>
 *
 * <h2>Algorithm Overview</h2>
 * <p>
 * The algorithm detects breaths by analyzing the time derivative (slope) of the
 * EtCO₂ signal via linear regression over a sliding window. Each breath is
 * detected when:
 * </p>
 * <ol>
 *     <li>The slope exceeds a positive threshold (arming phase, rising limb).</li>
 *     <li>The slope subsequently crosses below a negative threshold (falling
 *         limb), at which point an end‑tidal peak is estimated and recorded.</li>
 * </ol>
 *
 * <p>
 * To improve robustness, the implementation supports:
 * </p>
 * <ul>
 *     <li><strong>Exponential Moving Average (EMA)</strong> smoothing.</li>
 *     <li><strong>Hampel filtering</strong> for outlier suppression.</li>
 *     <li><strong>Sliding‑window least squares</strong> slope estimation.</li>
 *     <li><strong>Hysteresis</strong> for reliable peak detection.</li>
 *     <li><strong>Refractory interval enforcement</strong> to reject physiologically
 *         impossible breath intervals.</li>
 *     <li><strong>Median inter‑breath interval</strong> over a history window
 *         for stable RR estimation.</li>
 * </ul>
 *
 * <h2>Respiratory Rate Computation</h2>
 * <p>
 * Registered peak timestamps are maintained in a bounded history. Intervals
 * between consecutive peaks (in milliseconds) are computed and the median value
 * is used to derive the respiratory rate:
 * </p>
 *
 * <pre>
 * RR = 60000 / median(inter_breath_interval_ms)
 * </pre>
 *
 * <h2>Configuration Parameters</h2>
 * <p>
 * The constructor exposes all tunable parameters:
 * </p>
 * <ul>
 *     <li>{@code windowSize}: Number of samples used for slope regression.</li>
 *     <li>{@code riseSlopeMin}: Slope threshold (mmHg/s) to arm breath detection.</li>
 *     <li>{@code fallSlopeMin}: Negative slope threshold to confirm peak detection.</li>
 *     <li>{@code minBreathIntervalSec}: Minimum physiologic allowable interval
 *         between breaths.</li>
 *     <li>{@code historySeconds}: Duration of stored peak timestamps used for
 *         median RR calculation.</li>
 *     <li>{@code useEMA}, {@code emaAlpha}: Optional exponential smoothing.</li>
 *     <li>{@code useHampel}, {@code hampelWindow}, {@code hampelK}: Optional Hampel
 *         outlier suppression.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This actor is designed to run within the actor framework's single‑threaded
 * dispatch model. Internal data structures (sliding windows, peak buffers) are
 * not thread‑safe for concurrent external access.
 * </p>
 *
 * <h2>Intended Use</h2>
 * <p>
 * Suitable for real‑time respiratory monitoring in decision-support systems
 * where robustness against noise, CO₂ baseline drift, and cardiogenic
 * oscillations is required.
 * </p>
 *
 * <h2>See Also</h2>
 * <ul>
 *     <li>Slope‑based breath detection literature</li>
 *     <li>Hampel filter for robust outlier detection</li>
 *     <li>Actor/event‑driven CDSS architecture</li>
 * </ul>
 */
public class RespiratoryRateEstimationActor extends Actor {

    // ---- Channels ----
    private final String etco2Channel;

    // ---- Detection parameters (tunable) ----
    private final int windowSize;                 // N samples for slope window (e.g., 15–50 depending on sampling rate)
    private final double riseSlopeMin;            // mmHg/sec threshold to "arm" detection
    private final double fallSlopeMin;            // mmHg/sec threshold (positive value) for negative slope to confirm peak
    private final double minBreathIntervalSec;    // refractory period (e.g., 1.0 sec)
    private final int historySeconds;             // window to keep peaks (e.g., 40 sec)

    // ---- Optional filtering ----
    private final boolean useEMA;
    private final double emaAlpha;                // 0<alpha<=1, e.g., 0.2
    private final boolean useHampel;
    private final int hampelWindow;               // in samples, odd preferred (e.g., 7)
    private final double hampelK;                 // threshold in MAD multiples (e.g., 3.0)


    private final Deque<SlopeUtils.Sample> window = new ArrayDeque<>();
    private final Deque<Instant> detectedPeaks = new ArrayDeque<>();
    private boolean armed = false;                // set when slope exceeds riseSlopeMin
    private Instant lastPeakTs = null;

    // For EMA
    private Double emaValue = null;
    // For Hampel
    private final Deque<Double> hampelBuf = new ArrayDeque<>();

    /**
     * Creates a respiratory rate estimator actor that detects breaths from an EtCO₂ waveform
     * using a robust slope-based algorithm with optional smoothing and outlier suppression.
     *
     * @param eventBus             Event bus for subscribing and publishing messages.
     * @param id                   Unique actor identifier.
     * @param etco2Channel         Input channel name carrying ETCO2_WAVE samples.
     * @param outputChannels      List of channels this actor is permitted to publish to.
     * @param windowSize           Sliding window size (in samples) for slope regression; must be {@code >= 3}.
     * @param riseSlopeMin         Positive slope threshold (mmHg/s) to "arm" detection on the expiratory upstroke.
     * @param fallSlopeMin         Magnitude of negative slope threshold (mmHg/s) required to confirm a breath (falling limb).
     * @param minBreathIntervalSec Minimum allowed inter-breath interval (seconds) used as a refractory period.
     * @param historySeconds       Duration (seconds) to retain detected peak timestamps for RR calculation.
     * @param useEMA               Whether to apply exponential moving average smoothing to the incoming signal.
     * @param emaAlpha             EMA smoothing factor {@code (0 < alpha <= 1)}; higher values weight recent samples more.
     * @param useHampel            Whether to apply a Hampel filter for outlier suppression.
     * @param hampelWindow         Window size (in samples) for the Hampel filter; odd value preferred.
     * @param hampelK              Outlier threshold in MAD multiples (e.g., 3.0) for the Hampel filter.
     *
     * @throws IllegalArgumentException if {@code windowSize < 3}.
     */
    public RespiratoryRateEstimationActor(
            EventBus eventBus,
            String id,
            String etco2Channel,
            JSONArray outputChannels,
            // Tunables with sensible defaults:
            int windowSize,
            double riseSlopeMin,
            double fallSlopeMin,
            double minBreathIntervalSec,
            int historySeconds,
            boolean useEMA,
            double emaAlpha,
            boolean useHampel,
            int hampelWindow,
            double hampelK
    ) {
        super(eventBus, id, List.of(Map.of(etco2Channel, "*")), List.of(etco2Channel), parseChannelListJson(outputChannels));
        this.etco2Channel = etco2Channel;
        if (windowSize < 3) throw new IllegalArgumentException("windowSize must be >= 3");
        this.windowSize = windowSize;
        this.riseSlopeMin = riseSlopeMin;
        this.fallSlopeMin = fallSlopeMin;
        this.minBreathIntervalSec = minBreathIntervalSec;
        this.historySeconds = historySeconds;

        this.useEMA = useEMA;
        this.emaAlpha = emaAlpha;
        this.useHampel = useHampel;
        this.hampelWindow = hampelWindow;
        this.hampelK = hampelK;
    }

    /**
     * Processes a single incoming ETCO2_WAVE sample, updates the smoothing/filters,
     * maintains the sliding window, estimates the local slope (mmHg/s), and drives
     * the hysteresis-based breath detection state machine. When a breath is confirmed,
     * registers its timestamp and, if enough history is present, computes and publishes RR.
     *
     * <p><strong>Expected input:</strong> The snapshot should contain the ETCO2 value
     * under {@code etco2Channel} (Number or String convertible to double). A timestamp
     * is read from {@code <channel>-timestamp} if available; otherwise {@link Instant#now()}
     * is used as a fallback.</p>
     *
     * <p><strong>Side effects:</strong> Updates internal buffers (EMA value, Hampel buffer,
     * sliding window, peak history), and may publish RR to {@code outputChannels}.</p>
     *
     * @param snapshot A map-like payload representing the latest event bus snapshot for the input channel.
     *                 Must contain a numeric ETCO2 value at key {@code etco2Channel}. Timestamp is optional.
     */
    @Override
    public void fireFunction(Map<String, Object> snapshot) {
        Object raw = snapshot.get(etco2Channel);
        if (!(raw instanceof Number) && !(raw instanceof String)) return;

        // Parse value
        double etco2;
        try {
            etco2 = Double.parseDouble(raw.toString());
        } catch (Exception e) {
            return;
        }

        // Timestamp from snapshot if present; fallback to now
        Instant ts = (Instant) snapshot.getOrDefault("%s-timestamp".formatted(etco2Channel), Instant.now());
        // If your base Actor provides access to original message JSON including timestamp,
        // adapt here to read it; otherwise Instant.now() is acceptable if upstream time is monotonic.

        double v = etco2;

        // Optional EMA smoothing
        if (useEMA) {
            emaValue = (emaValue == null) ? v : (emaAlpha * v + (1 - emaAlpha) * emaValue);
            v = emaValue;
        }

        // Optional Hampel (outlier suppression)
        if (useHampel) {
            v = hampelFilter(v);
        }

        // Append to window
        window.addLast(new SlopeUtils.Sample(ts, v));
        if (window.size() > windowSize) {
            window.pollFirst();
        }

        // Need at least 3 samples to compute slope securely
        if (window.size() < Math.max(3, windowSize / 2)) return;

        // Compute slope (mmHg/sec) by least-squares over the window
        double slope = computeSlope(window);

        // Hysteresis-based peak detection on slope:
        // 1) Arm when slope >= +riseSlopeMin
        // 2) Confirm peak when slope <= -fallSlopeMin (i.e., negative strong downslope)
        if (!armed && slope >= riseSlopeMin) {
            armed = true;
        }

        if (armed && slope <= -Math.abs(fallSlopeMin)) {
            // Candidate peak around the transition — use the maximum value in the last half-window as end-tidal proxy
            Instant peakTs = estimatePeakTime(window);
            if (peakTs != null) {
                maybeRegisterPeak(peakTs);
            }
            armed = false;
        }

        // After updating detections, compute and publish RR if possible
        computeAndPublishRR();
    }




    /**
     * Estimates the end-tidal (peak) timestamp using the recent portion of the window.
     * The method searches for the maximum value within the last half of the current window
     * and returns the timestamp of that maximum as a proxy for end-tidal time.
     *
     * <p>This is called at the moment the slope crosses the negative threshold after being
     * armed on a positive slope, i.e., near the breath peak-to-downslope transition.</p>
     *
     * @param win Deque of recent samples forming the current analysis window.
     * @return The {@link Instant} of the peak within the last half-window, or {@code null} if not found.
     */
    private Instant estimatePeakTime(Deque<SlopeUtils.Sample> win) {
        // Use the last half of the window to find the local maximum as the likely end-tidal point
        int n = win.size();
        int startIdx = Math.max(0, n - Math.max(3, n / 2));

        int idx = 0;
        double maxV = -Double.MAX_VALUE;
        Instant maxT = null;
        for (SlopeUtils.Sample s : win) {
            if (idx >= startIdx && s.v() > maxV) {
                maxV = s.v();
                maxT = s.t();
            }
            idx++;
        }
        return maxT;
    }

    /**
     * Registers a detected breath peak timestamp, enforcing a refractory period and
     * pruning old detections outside the configured history window.
     *
     * <p>If the candidate timestamp is too close to the previous peak (i.e., less than
     * {@code minBreathIntervalSec}), it is discarded as physiologically implausible.</p>
     *
     * <p>On acceptance, the peak timestamp is appended to the internal detection buffer
     * and {@code lastPeakTs} is updated.</p>
     *
     * @param ts Candidate peak time to register.
     */
    private void maybeRegisterPeak(Instant ts) {
        // Enforce refractory period
        if (lastPeakTs != null) {
            double sec = Duration.between(lastPeakTs, ts).toMillis() / 1000.0;
            if (sec < minBreathIntervalSec) {
                return; // too soon
            }
        }
        lastPeakTs = ts;

        // Drop old detections outside history window
        Instant cutoff = ts.minusSeconds(historySeconds);
        while (!detectedPeaks.isEmpty() && detectedPeaks.peekFirst().isBefore(cutoff)) {
            detectedPeaks.pollFirst();
        }

        detectedPeaks.addLast(ts);
    }

    /**
     * Computes the respiratory rate (RR) from the internal list of detected peak timestamps
     * and publishes it to the configured output channel.
     *
     * <p>The method requires at least two valid peaks. Intervals shorter than the refractory
     * period are ignored as a safety check (though they should have been rejected at detection time).
     * The remaining inter-breath intervals (in milliseconds) are sorted and their median is used to
     * derive RR as:
     * </p>
     *
     * <pre>
     * RR [breaths/min] = 60000 / median(inter_breath_interval_ms)
     * </pre>
     *
     * <p>If no valid intervals remain after filtering, nothing is published.</p>
     */
    private void computeAndPublishRR() {
        if (detectedPeaks.size() < 2) return;

        List<Long> intervalsMs = new ArrayList<>();
        Iterator<Instant> it = detectedPeaks.iterator();
        Instant prev = it.next();
        while (it.hasNext()) {
            Instant cur = it.next();
            long d = Duration.between(prev, cur).toMillis();
            prev = cur;
            // Respect refractory in detection; here we only filter blatantly tiny intervals
            if (d >= (long)(minBreathIntervalSec * 1000.0)) {
                intervalsMs.add(d);
            }
        }
        if (intervalsMs.isEmpty()) publishResult(eventBus, formatter, 0, id, outputChannels);;

        intervalsMs.sort(Long::compareTo);
        double medianMs = intervalsMs.get(intervalsMs.size() / 2);
        double rr = 60000.0 / medianMs;

        publishResult(eventBus, formatter, rr, id, outputChannels);
    }

    /**
     * Applies a streaming Hampel filter to the current sample for robust outlier suppression.
     *
     * <p>The filter maintains a small buffer of recent values. It computes the median (med)
     * and median absolute deviation (MAD) over the buffer; if the absolute standardized
     * deviation {@code |x - med| / (1.4826 * MAD)} exceeds {@code hampelK}, the sample is
     * replaced with the buffer median. The factor 1.4826 is the consistency constant for
     * normally distributed data.</p>
     *
     * <p>If the buffer is not yet sufficiently populated, the input sample is returned unchanged.
     * If MAD equals zero (no variability), the sample is also returned unchanged.</p>
     *
     * @param x Current (optionally EMA-smoothed) input sample.
     * @return The filtered sample, or the original value if no outlier suppression is applied.
     */
    private double hampelFilter(double x) {
        // Maintain a small buffer; if center is outlier vs median by K*MAD, replace with median.
        // For streaming we apply to the *current* sample by checking recent window statistics.
        hampelBuf.addLast(x);
        if (hampelBuf.size() > Math.max(3, hampelWindow)) {
            hampelBuf.pollFirst();
        }
        if (hampelBuf.size() < Math.max(3, hampelWindow / 2)) {
            return x; // not enough data
        }

        List<Double> vals = new ArrayList<>(hampelBuf);
        Collections.sort(vals);
        double med = vals.get(vals.size() / 2);

        // MAD
        List<Double> absDev = new ArrayList<>(vals.size());
        for (double v : vals) absDev.add(Math.abs(v - med));
        Collections.sort(absDev);
        double mad = absDev.get(absDev.size() / 2);
        if (mad == 0) return x;

        double z = Math.abs(x - med) / (1.4826 * mad); // 1.4826 ≈ consistency factor for normal dist
        if (z > hampelK) {
            return med; // suppress outlier to median
        }
        return x;
    }
}