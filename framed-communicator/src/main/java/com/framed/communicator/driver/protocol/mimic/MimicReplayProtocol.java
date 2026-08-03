package com.framed.communicator.driver.protocol.mimic;

import com.framed.core.EventBus;
import com.framed.io.protocol.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Replays a <a href="https://physionet.org/content/mimic3wdb/1.0/">MIMIC-III Waveform Database</a>
 * record into the FRAMED {@link EventBus} in (scaled) real time, exercising the normal,
 * non-interop data path: each decoded sample is published as a parsed {@code DataPoint} message on
 * an address of the shape {@code "<className>.<deviceID>.<channelID>.parsed"}, exactly as the live
 * device drivers do. Downstream writers, dispatchers and CDSS reactors therefore see the record as
 * if it came from a real monitor.
 *
 * <p>WFDB records are read directly (no external tooling): the master multi-segment header defines
 * segment order and gaps, each segment header supplies the per-signal format, gain, baseline, units
 * and name, and {@link WfdbSegmentReader} decodes the {@code .dat} payload. Gap ({@code "~"}) and
 * zero-length layout segments are skipped without stalling the replay clock, so the emitted stream
 * is continuous. Signal names from the header become channel IDs (e.g. {@code II}, {@code PLETH},
 * {@code ABP}); values are converted to physical units, or passed through raw for uncalibrated
 * signals.</p>
 *
 * <h2>Configuration keys</h2>
 * <ul>
 *   <li>{@code recordPath} &mdash; path to the record's master {@code .hea} header file.</li>
 *   <li>{@code deviceID} &mdash; device identifier stamped on every sample (sinks bind to this).</li>
 *   <li>{@code className} &mdash; semantic category used as the address prefix (e.g. {@code Waveform}).</li>
 *   <li>{@code channels} &mdash; JSON array of signal names to replay; empty replays all signals.</li>
 *   <li>{@code speed} &mdash; replay-speed multiplier ({@code 1.0} = real time, {@code 60} = 60&times;).</li>
 *   <li>{@code maxSeconds} &mdash; stop after this much <em>record</em> time ({@code 0} = whole record).</li>
 * </ul>
 *
 * <p>Replay runs on a dedicated daemon-style thread started from {@link #connect()}; the samples are
 * paced against wall-clock time so trend/window reactors observe realistic timing. When the record
 * is exhausted {@link #onReplayComplete()} is invoked (by default it drains briefly and terminates
 * the JVM, matching the CSV/JSONL replay driver).</p>
 *
 * <h2>Pacing statistics</h2>
 * <p>Replay always accumulates cheap per-frame pacing statistics &mdash; how far each frame's actual
 * emit time drifted from its scheduled time &mdash; and logs a one-line summary just before
 * {@link #onReplayComplete()}. The same figures are available programmatically via
 * {@link #pacingStats()}; together they answer whether the deployment sustains the record's sampling
 * frequency. See {@link PacingStats} for the threading contract.</p>
 */
public class MimicReplayProtocol extends Protocol {

    private final Path recordPath;
    private final String deviceID;
    private final String className;
    private final Set<String> channelFilter = new HashSet<>();
    private final double speed;
    private final double maxSeconds;

    /**
     * Pacing accumulator. Written exclusively by the replay thread; readable from other threads only
     * once replay completion has been observed (see {@link #pacingStats()}).
     */
    private final PacingRecorder pacing = new PacingRecorder();

    /**
     * Creates a MIMIC replay protocol. Parameter names match the JSON configuration keys resolved by
     * {@code Factory}; the {@link EventBus} is injected automatically.
     *
     * @param id         unique service identifier
     * @param eventBus   the event bus samples are published on
     * @param recordPath path to the record's master {@code .hea} header file
     * @param deviceID   device identifier stamped on every emitted sample
     * @param className  semantic category used as the address prefix
     * @param channels   signal names to replay; empty (or {@code [ ]}) replays every signal
     * @param speed      replay-speed multiplier ({@code 1.0} = real time)
     * @param maxSeconds stop after this much record time in seconds; {@code 0} replays the whole record
     */
    public MimicReplayProtocol(String id, EventBus eventBus, String recordPath, String deviceID,
                               String className, JSONArray channels, double speed, double maxSeconds) {
        super(id, eventBus);
        this.recordPath = Path.of(recordPath);
        this.deviceID = deviceID;
        this.className = className;
        for (Object c : channels) {
            channelFilter.add(String.valueOf(c));
        }
        this.speed = speed <= 0 ? 1.0 : speed;
        this.maxSeconds = maxSeconds;
        connect();
    }

    @Override
    public void connect() {
        Thread t = new Thread(this::runReplay, "MIMIC-Replay-" + id);
        t.setDaemon(true);
        t.start();
    }

    private void runReplay() {
        try {
            Thread.sleep(startupDelayMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            WfdbHeader master = WfdbHeader.parse(recordPath);
            Path baseDir = recordPath.toAbsolutePath().getParent();
            double fs = master.samplingFrequency();
            logger.info("Replaying MIMIC record %s (%.0f Hz) as device %s"
                    .formatted(master.recordName(), fs, deviceID));

            announceKnownChannels(master, baseDir);

            double maxFrames = maxSeconds > 0 ? maxSeconds * fs : Double.MAX_VALUE;
            long globalFrame = 0;
            long startMillis = System.currentTimeMillis();
            pacing.begin(fs * speed);

            for (WfdbHeader.Segment segment : segmentsOf(master)) {
                if (segment.gap()) {
                    logger.fine("Skipping %d-sample gap".formatted(segment.numSamples()));
                    continue;
                }
                if (segment.numSamples() == 0 || segment.name().endsWith("_layout")) {
                    continue; // layout / empty segment carries no samples
                }
                globalFrame = replaySegment(master, baseDir, segment, fs, startMillis,
                        globalFrame, maxFrames);
                if (globalFrame >= maxFrames) break;
            }

            logger.info("MIMIC replay finished after %d frames.".formatted(globalFrame));
            logger.info("MIMIC replay pacing: %s".formatted(pacing.snapshot()));
            onReplayComplete();

        } catch (Exception ex) {
            logger.log(Level.SEVERE, "MIMIC replay failed", ex);
        }
    }

    /**
     * Replays a single data segment, returning the updated global frame counter.
     */
    private long replaySegment(WfdbHeader master, Path baseDir, WfdbHeader.Segment segment,
                               double fs, long startMillis, long globalFrame, double maxFrames)
            throws Exception {
        List<WfdbSignal> signals;
        Path datPath;
        long frameHint;

        if (master.isMultiSegment()) {
            WfdbHeader segHeader = WfdbHeader.parse(baseDir.resolve(segment.name() + ".hea"));
            signals = segHeader.signals();
            datPath = baseDir.resolve(signals.get(0).fileName());
            frameHint = segment.numSamples();
        } else {
            signals = master.signals();
            datPath = baseDir.resolve(signals.get(0).fileName());
            frameHint = master.numSamplesPerSignal();
        }

        WfdbSegmentReader reader = WfdbSegmentReader.open(datPath, signals, frameHint);

        // Which columns are we emitting, and under which channel IDs?
        boolean[] emit = new boolean[signals.size()];
        int emitted = 0;
        for (int s = 0; s < signals.size(); s++) {
            String name = channelId(signals.get(s), s);
            emit[s] = includes(name);
            if (emit[s]) {
                emitted++;
                announceAddress(deviceID, addressOf(name));
            }
        }

        for (long f = 0; f < reader.numFrames(); f++) {
            if (globalFrame >= maxFrames) return globalFrame;

            long targetMillis = startMillis + (long) (globalFrame / (fs * speed) * 1000.0);
            long now = System.currentTimeMillis();
            long delay = targetMillis - now;
            if (delay > 0) {
                Thread.sleep(delay);
                now = System.currentTimeMillis();
            }
            pacing.record(now - targetMillis, now, emitted);

            String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(formatter);
            for (int s = 0; s < signals.size(); s++) {
                if (!emit[s]) continue;
                WfdbSignal sig = signals.get(s);
                double value = round(sig.toPhysical(reader.adc(s, f)));
                publishSample(channelId(sig, s), value, timestamp);
            }
            globalFrame++;
        }
        return globalFrame;
    }

    /**
     * Publishes one sample. The channel's address is <em>not</em> re-announced here: it has already
     * been announced up front ({@link #announceKnownChannels}) and again per segment before the frame
     * loop, so a per-sample announcement would only double the bus traffic without adding discovery.
     */
    private void publishSample(String channelID, double value, String timestamp) {
        String address = addressOf(channelID);
        JSONObject parsed = new JSONObject();
        parsed.put("timestamp", timestamp);
        parsed.put("channelID", channelID);
        parsed.put("value", value);
        parsed.put("className", className);
        eventBus.publish(address, parsed);
    }

    /** Announces every channel we can discover up front (from the layout header when present). */
    private void announceKnownChannels(WfdbHeader master, Path baseDir) {
        Set<String> names = new LinkedHashSet<>();
        try {
            if (master.isMultiSegment()) {
                for (WfdbHeader.Segment seg : master.segments()) {
                    if (seg.name().endsWith("_layout")) {
                        WfdbHeader layout = WfdbHeader.parse(baseDir.resolve(seg.name() + ".hea"));
                        collectNames(layout.signals(), names);
                        break;
                    }
                }
            } else {
                collectNames(master.signals(), names);
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not pre-announce channels from layout header", e);
        }
        for (String name : names) {
            if (includes(name)) announceAddress(deviceID, addressOf(name));
        }
    }

    private void collectNames(List<WfdbSignal> signals, Set<String> out) {
        for (int s = 0; s < signals.size(); s++) {
            out.add(channelId(signals.get(s), s));
        }
    }

    private List<WfdbHeader.Segment> segmentsOf(WfdbHeader master) {
        if (master.isMultiSegment()) return master.segments();
        // Single-segment record: synthesise one segment referencing the master itself.
        return List.of(new WfdbHeader.Segment(master.recordName(), master.numSamplesPerSignal(), false));
    }

    private String channelId(WfdbSignal signal, int index) {
        String d = signal.description();
        if (d == null || d.isBlank()) return "sig" + index;
        return d.trim().replaceAll("\\s+", "_");
    }

    private String addressOf(String channelID) {
        return "%s.%s.%s.parsed".formatted(className, deviceID, channelID);
    }

    private boolean includes(String channelID) {
        return channelFilter.isEmpty() || channelFilter.contains(channelID);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Milliseconds to wait after construction before replay begins, giving sinks and reactors time
     * to be instantiated and to subscribe. Overridable for tests.
     *
     * @return the startup delay in milliseconds
     */
    protected long startupDelayMillis() {
        return Duration.ofSeconds(5).toMillis();
    }

    /**
     * Invoked once the whole record (or {@code maxSeconds} of it) has been replayed. The default
     * drains briefly to let downstream processing finish and then terminates the JVM, matching the
     * CSV/JSONL replay driver's behaviour. Tests override this to avoid exiting.
     */
    protected void onReplayComplete() {
        try {
            Thread.sleep(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }

    /**
     * Returns a snapshot of the replay's pacing fidelity &mdash; how well the producer held the
     * configured sample rate.
     *
     * <p><b>Threading:</b> the underlying counters are written only by the replay thread. A caller
     * on another thread sees a consistent, final snapshot once it has observed replay completion
     * through a happens-before edge (e.g. a latch counted down from an overridden
     * {@link #onReplayComplete()}). Reading it <em>during</em> replay yields a torn but
     * order-of-magnitude-correct view.</p>
     *
     * @return the pacing statistics accumulated so far
     */
    public PacingStats pacingStats() {
        return pacing.snapshot();
    }

    /**
     * Immutable summary of how closely replay followed its schedule.
     *
     * <p>For frame <i>i</i> the scheduled emit time is {@code start + i / (fs · speed)} and the
     * <em>lag</em> is {@code actualEmit − scheduled}. Lag is non-negative by construction (the
     * replay loop only ever sleeps to catch up to the schedule, never runs ahead of it), so a
     * persistently growing lag means the deployment cannot sustain the target rate.</p>
     *
     * <p>Percentiles come from a bucketed histogram: exact at 1 ms resolution below one second,
     * ~10&nbsp;% relative resolution above it. They are therefore lower bounds on the true value.</p>
     *
     * @param frames            number of frames emitted
     * @param samples           number of individual samples published ({@code Σ signals per frame};
     *                          not simply {@code frames · signals}, since a multi-segment record may
     *                          carry a different signal set per segment)
     * @param targetHz          frame rate the replay was scheduled at ({@code fs · speed})
     * @param achievedHz        frames divided by the wall-clock span between first and last emit
     * @param framesBehind      frames whose lag exceeded one frame period, i.e. that missed their slot
     * @param meanLagMillis     arithmetic mean lag
     * @param p50LagMillis      median lag
     * @param p95LagMillis      95th-percentile lag
     * @param maxLagMillis      largest observed lag
     * @param wallElapsedMillis wall-clock span between the first and last emit
     */
    public record PacingStats(long frames, long samples, double targetHz, double achievedHz,
                              long framesBehind, double meanLagMillis, long p50LagMillis,
                              long p95LagMillis, long maxLagMillis, long wallElapsedMillis) {

        /**
         * Returns {@code true} if the producer held its schedule: at least 98&nbsp;% of the target
         * rate was achieved and no frame missed its slot by more than one frame period.
         *
         * @return whether the replay kept pace with the configured sample rate
         */
        public boolean keptPace() {
            return frames > 0 && achievedHz >= targetHz * 0.98 && framesBehind == 0;
        }

        @Override
        public String toString() {
            return ("frames=%d samples=%d target=%.2f Hz achieved=%.2f Hz behind=%d "
                    + "lag(mean/p50/p95/max)=%.1f/%d/%d/%d ms elapsed=%d ms keptPace=%b")
                    .formatted(frames, samples, targetHz, achievedHz, framesBehind,
                            meanLagMillis, p50LagMillis, p95LagMillis, maxLagMillis,
                            wallElapsedMillis, keptPace());
        }
    }

    /**
     * Single-writer accumulator behind {@link #pacingStats()}. Kept allocation-free per frame: lag
     * lands in a {@link MillisHistogram} bucket rather than a growing list, so a multi-million-frame
     * replay costs a fixed few kilobytes.
     */
    private static final class PacingRecorder {
        private final MillisHistogram lag = new MillisHistogram();
        private double targetHz;
        private long framePeriodMillis = 1;
        private long frames;
        private long samples;
        private long framesBehind;
        private long firstEmitMillis = -1;
        private long lastEmitMillis = -1;

        /** Arms the recorder for a replay running at {@code targetHz} frames per second. */
        void begin(double targetHz) {
            this.targetHz = targetHz;
            this.framePeriodMillis = Math.max(1L, Math.round(1000.0 / Math.max(targetHz, 1e-9)));
        }

        /**
         * Records one frame emitted at {@code emitMillis}, {@code lagMillis} behind its scheduled
         * time, carrying {@code samplesInFrame} published samples.
         */
        void record(long lagMillis, long emitMillis, int samplesInFrame) {
            frames++;
            samples += samplesInFrame;
            lag.add(lagMillis);
            if (lagMillis > framePeriodMillis) framesBehind++;
            if (firstEmitMillis < 0) firstEmitMillis = emitMillis;
            lastEmitMillis = emitMillis;
        }

        PacingStats snapshot() {
            long elapsed = firstEmitMillis < 0 ? 0 : lastEmitMillis - firstEmitMillis;
            double achievedHz = elapsed > 0 ? frames * 1000.0 / elapsed : 0.0;
            return new PacingStats(frames, samples, targetHz, achievedHz, framesBehind,
                    lag.mean(), lag.percentile(0.50), lag.percentile(0.95), lag.max(), elapsed);
        }
    }
}