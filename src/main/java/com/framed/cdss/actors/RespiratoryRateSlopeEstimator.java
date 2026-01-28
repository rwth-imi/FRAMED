package com.framed.cdss.casestudy;

import com.framed.cdss.Actor;
import com.framed.core.EventBus;
import org.json.JSONObject;

import java.time.Instant;
import java.util.*;

import static com.framed.cdss.utils.CDSSUtils.publishResult;

/**
 * Respiratory Rate (RR) estimation from EtCO2 waveform.
 * Trigger:
 *  - Fires whenever a new EtCO2 sample arrives.
 * Input:
 *  - "ETCO2_WAVE": JSON { "value": <double>, "timestamp": <string> }
 * Output:
 *  - Publishes RR (breaths/min) to the configured RR output channel.
 * Algorithm:
 *  - Detects breaths using rising-edge thresholding of EtCO2 signal.
 *  - Tracks time between detected expiratory peaks.
 *  - Computes RR = 60 / median(inter-breath interval).
 */
public class RREstimator extends Actor {

    private static final double MIN_ETCO2_PEAK = 5.0;   // mmHg
    private static final double MIN_BREATH_INTERVAL_SEC = 1.0; // avoid >60 BPM false spikes

    // Buffer of recent peaks
    private final Deque<Instant> detectedPeaks = new ArrayDeque<>();

    private final String etCO2Channel;

    // State machine for waveform
    private boolean inExpiration = false;
    private double lastValue = 0;

    public RREstimator(
            EventBus eventBus,
            String id,
            String etCO2Channel,
            List<String> outputChannels
    ) {
        super(eventBus, id, List.of(Map.of(etCO2Channel, "*")), List.of(etCO2Channel), outputChannels);
        this.etCO2Channel = etCO2Channel;
    }

    @Override
    public void fireFunction(Map<String, Object> snapshot) {

        Object v = snapshot.get(etCO2Channel);
        if (v == null) return;

        double etco2;
        try {
            etco2 = Double.parseDouble(v.toString());
        } catch (Exception e) {
            return; // non-numeric → skip safely
        }

        // Timestamp for detection (taken from snapshotTimes)
        Instant ts = Instant.now();

        detectBreath(etco2, ts);
    }

    private void detectBreath(double etco2, Instant ts) {

        // rising edge detection
        if (!inExpiration && etco2 > MIN_ETCO2_PEAK && etco2 > lastValue) {
            inExpiration = true;
        }

        // peak detection (falling edge after rising)
        if (inExpiration && etco2 < lastValue) {

            // Register peak
            registerPeak(ts);

            inExpiration = false;
        }

        lastValue = etco2;
    }

    private void registerPeak(Instant ts) {

        // Remove old peaks
        while (!detectedPeaks.isEmpty()) {
            Instant first = detectedPeaks.peekFirst();
            if (first.plusSeconds(40).isBefore(ts)) {
                detectedPeaks.pollFirst();
            } else {
                break;
            }
        }

        // Add new peak
        detectedPeaks.addLast(ts);

        if (detectedPeaks.size() < 2) return;

        computeRR();
    }

    private void computeRR() {

        if (detectedPeaks.size() < 2) return;

        List<Long> intervals = new ArrayList<>();

        Iterator<Instant> it = detectedPeaks.iterator();
        Instant prev = it.next();

        while (it.hasNext()) {
            Instant cur = it.next();
            long deltaMs = cur.toEpochMilli() - prev.toEpochMilli();
            prev = cur;

            double sec = deltaMs / 1000.0;

            if (sec > MIN_BREATH_INTERVAL_SEC) {
                intervals.add(deltaMs);
            }
        }

        if (intervals.isEmpty()) return;

        // median interval
        intervals.sort(Long::compare);
        double medianMs = intervals.get(intervals.size() / 2);

        double rr = 60000.0 / medianMs;

        publishRR(rr);
    }

    private void publishRR(double rr) {
        JSONObject result = new JSONObject();
        result.put("rr", rr);
        result.put("timestamp", Instant.now().toString());

        publishResult(eventBus, formatter, rr, id, outputChannels);
    }
}