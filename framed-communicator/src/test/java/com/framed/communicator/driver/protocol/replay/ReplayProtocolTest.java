package com.framed.communicator.driver.protocol.replay;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.framed.core.utils.Timer.formatter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ReplayProtocol} over a small hand-written recording and asserts what a reactor
 * network downstream of it would see: the addresses, the announcement handshake, the device and
 * channel filters, and above all the timing the replayed stamps carry.
 */
class ReplayProtocolTest {

  @TempDir
  Path dir;

  /** Captures every publication, separating parsed samples from address announcements. */
  private static final class CapturingBus implements EventBus {
    final List<String[]> announced = new ArrayList<>();     // [registry topic, address]
    final List<String> addresses = new ArrayList<>();       // address per sample
    final List<JSONObject> samples = new ArrayList<>();

    @Override public synchronized void publish(String address, Object message) {
      if (message instanceof JSONObject json) {
        addresses.add(address);
        samples.add(json);
      } else {
        announced.add(new String[]{address, String.valueOf(message)});
      }
    }

    @Override public void register(String address, Consumer<Object> handler) { }
    @Override public void register(String address, Consumer<Object> handler, DispatchMode mode) { }
    @Override public void send(String address, Object message) { }
    @Override public void shutdown() { }

    synchronized long millisOf(int index) {
      return microsOf(index) / 1000L;
    }

    synchronized long microsOf(int index) {
      LocalDateTime moment = LocalDateTime.parse(samples.get(index).getString("timestamp"), formatter);
      Instant instant = moment.toInstant(ZoneOffset.UTC);
      return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }
  }

  /** One recording line, in the shape the JSONL writers produce. */
  private static String line(String timestamp, String className, String device, String channel,
                             Object value) {
    return new JSONObject()
            .put("timestamp", timestamp).put("className", className)
            .put("deviceID", device).put("channelID", channel).put("value", value)
            .toString();
  }

  private Path recording(String... lines) throws IOException {
    Path file = dir.resolve("recording.jsonl");
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return file;
  }

  private ReplayProtocol replay(Path file, JSONArray devices, JSONArray channels, double speed)
          throws InterruptedException {
    return replay(file, devices, channels, speed, new CapturingBus());
  }

  private ReplayProtocol replay(Path file, JSONArray devices, JSONArray channels, double speed,
                                CapturingBus bus) throws InterruptedException {
    ReplayProtocol protocol = new ReplayProtocol("Replay", bus, file.toString(), devices, channels,
            speed, 0.0, 0.0, false);
    assertTrue(protocol.awaitCompletion(Duration.ofSeconds(30)), "replay did not finish");
    return protocol;
  }

  /**
   * The load-bearing property: relative timing survives the replay. Two events that shared one
   * device frame timestamp must still share one stamp afterwards — that identity is what a
   * reactor's logical-time gate keys off — and the intervals between distinct stamps must be the
   * recorded intervals, not the replay thread's scheduling jitter.
   */
  @Test
  void preservesRecordedIntervalsAndSharedFrameTimestamps() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 5.0),
            line("2026-04-01T17:49:33.320Z", "RealTime", "Vent", "Paw", 6.0),
            line("2026-04-01T17:49:33.320Z", "RealTime", "Vent", "Flow", 7.0),
            line("2026-04-01T17:49:33.640Z", "RealTime", "Vent", "Paw", 8.0));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 1.0, bus);

    assertEquals(4, bus.samples.size());
    assertEquals(320L, bus.millisOf(1) - bus.millisOf(0));
    assertEquals(bus.millisOf(1), bus.millisOf(2), "same recorded frame must keep one stamp");
    assertEquals(640L, bus.millisOf(3) - bus.millisOf(0));

    // Contemporaneous with the run, not with the 2026-04-01 recording date.
    long ageMillis = System.currentTimeMillis() - bus.millisOf(0);
    assertTrue(ageMillis >= 0 && ageMillis < 30_000,
            "stamps must be shifted to replay time, was %d ms old".formatted(ageMillis));
  }

  /**
   * Sub-millisecond structure survives too. This recording family timestamps to the microsecond,
   * and rounding the schedule to milliseconds would move every such sample against its neighbours
   * — small, but enough to change a windowed feature and therefore a verdict.
   */
  @Test
  void preservesMicrosecondOffsets() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.100250Z", "RealTime", "Vent", "Paw", 2.0),
            line("2026-04-01T17:49:33.200500Z", "RealTime", "Vent", "Paw", 3.0));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 1.0, bus);

    assertEquals(100_250L, bus.microsOf(1) - bus.microsOf(0));
    assertEquals(200_500L, bus.microsOf(2) - bus.microsOf(0));
  }

  /** Speed divides the schedule: at 4x the same recording replays in a quarter of the time. */
  @Test
  void speedCompressesTheSchedule() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:34.000Z", "RealTime", "Vent", "Paw", 2.0));

    CapturingBus bus = new CapturingBus();
    long start = System.currentTimeMillis();
    ReplayProtocol protocol = replay(file, new JSONArray(), new JSONArray(), 4.0, bus);
    long elapsed = System.currentTimeMillis() - start;

    assertEquals(250L, bus.millisOf(1) - bus.millisOf(0));
    assertTrue(elapsed < 1_000, "4x replay of 1 s took %d ms".formatted(elapsed));
    assertEquals(2, protocol.stats().events());
    assertEquals(8.0, protocol.stats().targetHz(), 1e-9, "2 events compressed into 0.25 s");
  }

  /** Free-running mode drops the schedule but must still stamp in publication order. */
  @Test
  void speedZeroReplaysAsFastAsPossible() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:59:33.000Z", "RealTime", "Vent", "Paw", 2.0));

    CapturingBus bus = new CapturingBus();
    long start = System.currentTimeMillis();
    ReplayProtocol protocol = replay(file, new JSONArray(), new JSONArray(), 0.0, bus);

    assertTrue(System.currentTimeMillis() - start < 1_000, "free-running replay must not pace");
    assertTrue(bus.millisOf(1) >= bus.millisOf(0), "stamps must not go backwards");
    assertEquals(0.0, protocol.stats().targetHz(), 1e-9, "there is no schedule to hold");
  }

  /** Each address is announced exactly once, before the first sample on it. */
  @Test
  void announcesEachAddressOnceUpFront() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.100Z", "RealTime", "Vent", "Paw", 2.0),
            line("2026-04-01T17:49:33.200Z", "RealTime", "Vent", "Flow", 3.0));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 1.0, bus);

    List<String> announcements = bus.announced.stream().map(entry -> entry[1]).toList();
    assertEquals(List.of("RealTime.Vent.Paw.parsed", "RealTime.Vent.Flow.parsed").size(),
            announcements.size(), "one announcement per address, not per event");
    assertTrue(announcements.contains("RealTime.Vent.Paw.parsed"));
    assertTrue(announcements.contains("RealTime.Vent.Flow.parsed"));
    assertEquals("Vent.addresses", bus.announced.get(0)[0]);
    assertEquals("RealTime.Vent.Paw.parsed", bus.addresses.get(0));
  }

  /** Device and channel filters both narrow the replay; an empty filter admits everything. */
  @Test
  void filtersByDeviceAndChannel() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.100Z", "RealTime", "Vent", "Flow", 2.0),
            line("2026-04-01T17:49:33.200Z", "Percentage_int", "Oxi", "SpO2", 98));

    CapturingBus all = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 0.0, all);
    assertEquals(3, all.samples.size());

    CapturingBus vent = new CapturingBus();
    replay(file, new JSONArray(List.of("Vent")), new JSONArray(), 0.0, vent);
    assertEquals(2, vent.samples.size());
    assertFalse(vent.addresses.contains("Percentage_int.Oxi.SpO2.parsed"));

    CapturingBus paw = new CapturingBus();
    replay(file, new JSONArray(List.of("Vent")), new JSONArray(List.of("Paw")), 0.0, paw);
    assertEquals(1, paw.samples.size());
    assertEquals("RealTime.Vent.Paw.parsed", paw.addresses.get(0));
  }

  /** Events out of order in the file are replayed in timestamp order. */
  @Test
  void sortsEventsByRecordedTimestamp() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.200Z", "RealTime", "Vent", "Paw", 3.0),
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.100Z", "RealTime", "Vent", "Paw", 2.0));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 0.0, bus);

    assertEquals(List.of(1.0, 2.0, 3.0),
            bus.samples.stream().map(sample -> sample.getDouble("value")).toList());
  }

  /** The published envelope is the four-field shape a parser emits. */
  @Test
  void publishesTheParsedEnvelope() throws Exception {
    Path file = recording(line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 12.5));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 0.0, bus);

    JSONObject sample = bus.samples.get(0);
    assertEquals(12.5, sample.getDouble("value"));
    assertEquals("Paw", sample.getString("channelID"));
    assertEquals("RealTime", sample.getString("className"));
    // Parseable by DataPointParser, which requires exactly Timer.formatter.
    assertEquals(26, sample.getString("timestamp").length());
  }
}
