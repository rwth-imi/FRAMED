package com.framed.cdss.utils;

import com.framed.cdss.actors.RespiratoryRateEstimationActor;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;

public class SlopeUtils {
    public record Sample(Instant t, double v) {
    }
    /**
     * Computes the least-squares linear regression slope of the ETCO2 signal over the
     * provided sliding window, using time in seconds relative to the first sample in the window.
     *
     * <p>The slope is returned in units of mmHg/sec (assuming input values are mmHg).</p>
     *
     * <p><strong>Numerical stability:</strong> If the time axis degenerates (e.g., repeated identical timestamps),
     * the denominator may be zero; in that case, the method returns {@code 0.0}.</p>
     *
     * @param win Deque of recent samples (time/value) representing the current slope window.
     * @return The regression slope in mmHg/sec; returns {@code 0.0} if insufficient variance in time.
     */
    public static double computeSlope(Deque<Sample> win) {
        // Linear regression of v over time t (seconds relative to first)
        int n = win.size();
        Sample first = win.peekFirst();
        if (first == null) return 0.0;

        double sumT = 0;
        double sumV = 0;
        double sumTT = 0;
        double sumTV = 0;

        for (Sample s : win) {
            double t = Duration.between(first.t, s.t).toMillis() / 1000.0;
            sumT += t;
            sumV += s.v;
            sumTT += t * t;
            sumTV += t * s.v;
        }

        double denom = (n * sumTT - sumT * sumT);
        if (denom == 0) return 0.0;

        return (n * sumTV - sumT * sumV) / denom; // units: (mmHg) / sec
    }
}
