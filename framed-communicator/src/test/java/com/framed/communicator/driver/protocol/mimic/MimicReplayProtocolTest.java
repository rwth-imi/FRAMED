package com.framed.communicator.driver.protocol.mimic;

import com.framed.core.EventBus;
import com.framed.core.utils.DispatchMode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end replay test: a tiny two-segment WFDB record is written to disk and driven through
 * {@link MimicReplayProtocol}; the parsed samples captured off the bus are asserted for address
 * shape, ordering and physical-unit conversion.
 */
class MimicReplayProtocolTest {

    @TempDir
    Path dir;

    /** Minimal capturing event bus: records every {@code publish}; delivery is unused here. */
    private static final class CapturingBus implements EventBus {
        final List<String[]> announced = new ArrayList<>();      // [group-topic, address]
        final List<JSONObject> samples = new ArrayList<>();       // parsed payloads
        final List<String> sampleChannels = new ArrayList<>();    // matching addresses

        @Override public synchronized void publish(String channel, Object message) {
            if (message instanceof JSONObject json) {
                samples.add(json);
                sampleChannels.add(channel);
            } else {
                announced.add(new String[]{channel, String.valueOf(message)});
            }
        }
        @Override public void register(String address, Consumer<Object> handler) { }
        @Override public void register(String address, Consumer<Object> handler, DispatchMode mode) { }
        @Override public void send(String address, Object message) { }
        @Override public void shutdown() { }
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void replaysMultiSegmentRecordAsParsedDataPoints() throws Exception {
        // Master: 3 segments (layout + two data), 2 signals @ 100 Hz, 6 samples total.
        write("rec.hea", """
                rec/3 2 100 6
                rec_layout 0
                rec_0001 3
                rec_0002 3
                """);
        write("rec_layout.hea", """
                rec_layout 2 100
                ~ 0 200(0)/mV 8 0 0 0 0 II
                ~ 0 100(0)/mmHg 8 0 0 0 0 ABP
                """);
        write("rec_0001.hea", """
                rec_0001 2 100 3
                rec_0001.dat 80 200(0)/mV 8 128 0 0 0 II
                rec_0001.dat 80 100(0)/mmHg 8 128 0 0 0 ABP
                """);
        write("rec_0002.hea", """
                rec_0002 2 100 3
                rec_0002.dat 80 200(0)/mV 8 128 0 0 0 II
                rec_0002.dat 80 100(0)/mmHg 8 128 0 0 0 ABP
                """);
        // Frame-interleaved [II, ABP] bytes (format 80: value = byte - 128).
        // seg1 II adc {100,0,-100} -> {0.5,0,-0.5} mV ; ABP adc {50,50,50} -> 0.5 mmHg
        Files.write(dir.resolve("rec_0001.dat"),
                new byte[]{(byte) 228, (byte) 178, (byte) 128, (byte) 178, (byte) 28, (byte) 178});
        // seg2 II adc {40,20,0} -> {0.2,0.1,0} mV ; ABP adc {30,30,30} -> 0.3 mmHg
        Files.write(dir.resolve("rec_0002.dat"),
                new byte[]{(byte) 168, (byte) 158, (byte) 148, (byte) 158, (byte) 128, (byte) 158});

        CapturingBus bus = new CapturingBus();
        CountDownLatch done = new CountDownLatch(1);

        // Subclass to remove the startup delay and to signal completion instead of exiting the JVM.
        new MimicReplayProtocol("mimic", bus, dir.resolve("rec.hea").toString(),
                "MIMIC", "Waveform", new JSONArray(), 1000.0, 0.0) {
            @Override protected long startupDelayMillis() { return 0; }
            @Override protected void onReplayComplete() { done.countDown(); }
        };

        assertTrue(done.await(10, TimeUnit.SECONDS), "replay did not finish in time");

        // II channel: six samples in record order, converted to physical mV.
        List<Double> ii = valuesFor(bus, "Waveform.MIMIC.II.parsed");
        assertEquals(List.of(0.5, 0.0, -0.5, 0.2, 0.1, 0.0), ii);

        // ABP channel: six samples, all constant per segment.
        List<Double> abp = valuesFor(bus, "Waveform.MIMIC.ABP.parsed");
        assertEquals(List.of(0.5, 0.5, 0.5, 0.3, 0.3, 0.3), abp);

        // Payload shape matches the parsed-DataPoint contract used by the live drivers.
        JSONObject first = firstFor(bus, "Waveform.MIMIC.II.parsed");
        assertEquals("II", first.getString("channelID"));
        assertEquals("Waveform", first.getString("className"));
        assertTrue(first.has("timestamp"));

        // Both channels were announced under the device group for sink discovery.
        boolean iiAnnounced = bus.announced.stream()
                .anyMatch(a -> a[0].equals("MIMIC.addresses") && a[1].equals("Waveform.MIMIC.II.parsed"));
        assertTrue(iiAnnounced, "II address was not announced");
    }

    @Test
    void channelFilterRestrictsEmittedSignals() throws Exception {
        write("rec.hea", """
                rec 2 100 2
                rec.dat 80 200(0)/mV 8 128 0 0 0 II
                rec.dat 80 100(0)/mmHg 8 128 0 0 0 ABP
                """);
        Files.write(dir.resolve("rec.dat"),
                new byte[]{(byte) 228, (byte) 178, (byte) 128, (byte) 178});

        CapturingBus bus = new CapturingBus();
        CountDownLatch done = new CountDownLatch(1);

        new MimicReplayProtocol("mimic", bus, dir.resolve("rec.hea").toString(),
                "MIMIC", "Waveform", new JSONArray(List.of("II")), 1000.0, 0.0) {
            @Override protected long startupDelayMillis() { return 0; }
            @Override protected void onReplayComplete() { done.countDown(); }
        };

        assertTrue(done.await(10, TimeUnit.SECONDS), "replay did not finish in time");
        assertFalse(valuesFor(bus, "Waveform.MIMIC.II.parsed").isEmpty());
        assertTrue(valuesFor(bus, "Waveform.MIMIC.ABP.parsed").isEmpty(), "ABP should be filtered out");
    }

    @Test
    void recordsPacingStatisticsForEveryFrameAndSample() throws Exception {
        write("rec.hea", """
                rec 2 100 3
                rec.dat 80 200(0)/mV 8 128 0 0 0 II
                rec.dat 80 100(0)/mmHg 8 128 0 0 0 ABP
                """);
        Files.write(dir.resolve("rec.dat"), new byte[]{
                (byte) 228, (byte) 178, (byte) 128, (byte) 178, (byte) 28, (byte) 178});

        CapturingBus bus = new CapturingBus();
        CountDownLatch done = new CountDownLatch(1);

        MimicReplayProtocol replay = new MimicReplayProtocol("mimic", bus,
                dir.resolve("rec.hea").toString(), "MIMIC", "Waveform", new JSONArray(), 10.0, 0.0) {
            @Override protected long startupDelayMillis() { return 0; }
            @Override protected void onReplayComplete() { done.countDown(); }
        };

        assertTrue(done.await(10, TimeUnit.SECONDS), "replay did not finish in time");

        MimicReplayProtocol.PacingStats stats = replay.pacingStats();
        assertEquals(3, stats.frames());
        assertEquals(6, stats.samples(), "two signals per frame must be counted individually");
        assertEquals(1000.0, stats.targetHz(), 1e-9, "100 Hz record at 10x");
        assertTrue(stats.maxLagMillis() >= 0, "lag is non-negative by construction");
        assertTrue(stats.meanLagMillis() <= stats.maxLagMillis());
        assertEquals(stats.samples(), bus.samples.size(), "every counted sample must reach the bus");
    }

    @Test
    void announcesChannelsOnceUpFrontAndPerSegmentButNotPerSample() throws Exception {
        write("rec.hea", """
                rec 2 100 3
                rec.dat 80 200(0)/mV 8 128 0 0 0 II
                rec.dat 80 100(0)/mmHg 8 128 0 0 0 ABP
                """);
        Files.write(dir.resolve("rec.dat"), new byte[]{
                (byte) 228, (byte) 178, (byte) 128, (byte) 178, (byte) 28, (byte) 178});

        CapturingBus bus = new CapturingBus();
        CountDownLatch done = new CountDownLatch(1);

        new MimicReplayProtocol("mimic", bus, dir.resolve("rec.hea").toString(),
                "MIMIC", "Waveform", new JSONArray(), 1000.0, 0.0) {
            @Override protected long startupDelayMillis() { return 0; }
            @Override protected void onReplayComplete() { done.countDown(); }
        };

        assertTrue(done.await(10, TimeUnit.SECONDS), "replay did not finish in time");

        // 2 channels announced up front from the header + 2 again before the segment's frame loop.
        // Announcing per sample as well would only add bus traffic, not discovery.
        assertEquals(6, bus.samples.size());
        assertEquals(4, bus.announced.size(),
                "address announcements must not scale with the number of samples");
    }

    private static List<Double> valuesFor(CapturingBus bus, String address) {
        List<Double> out = new ArrayList<>();
        synchronized (bus) {
            for (int i = 0; i < bus.samples.size(); i++) {
                if (bus.sampleChannels.get(i).equals(address)) {
                    out.add(bus.samples.get(i).getDouble("value"));
                }
            }
        }
        return out;
    }

    private static JSONObject firstFor(CapturingBus bus, String address) {
        synchronized (bus) {
            for (int i = 0; i < bus.samples.size(); i++) {
                if (bus.sampleChannels.get(i).equals(address)) return bus.samples.get(i);
            }
        }
        throw new AssertionError("no sample for " + address);
    }
}