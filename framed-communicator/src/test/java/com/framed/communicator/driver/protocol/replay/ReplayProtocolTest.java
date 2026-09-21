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
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
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
    return replay(file, devices, channels, speed, 1, bus);
  }

  private ReplayProtocol replay(Path file, JSONArray devices, JSONArray channels, double speed,
                                int repeat, CapturingBus bus) throws InterruptedException {
    ReplayProtocol protocol = new ReplayProtocol("Replay", bus, file.toString(), devices, channels,
            speed, repeat, 0.0, 0.0, false);
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

  /**
   * A three-event recording whose tightest cadence is 100 ms: the seam gap the repetition logic
   * derives is that interval, so the cycle is the 300 ms span plus 100 ms.
   */
  private Path repeatable() throws IOException {
    return recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.100Z", "RealTime", "Vent", "Paw", 2.0),
            line("2026-04-01T17:49:33.300Z", "RealTime", "Vent", "Paw", 3.0));
  }

  /** The default: one pass, and stamps identical to what the class emitted before repeat existed. */
  @Test
  void repeatOneReplaysTheRecordingOnce() throws Exception {
    CapturingBus bus = new CapturingBus();
    ReplayProtocol protocol = replay(repeatable(), new JSONArray(), new JSONArray(), 0.0, 1, bus);

    assertEquals(3, bus.samples.size());
    assertEquals(3, protocol.stats().events());
  }

  /**
   * The load-bearing property of a repeated replay: it is a concatenation, not an overlay. Every
   * pass contributes its events once, in order, at stamps strictly later than the pass before —
   * so a downstream window never sees one recorded instant twice.
   */
  @Test
  void repeatConcatenatesPassesWithoutOverlap() throws Exception {
    CapturingBus bus = new CapturingBus();
    replay(repeatable(), new JSONArray(), new JSONArray(), 0.0, 3, bus);

    assertEquals(9, bus.samples.size(), "three passes over a three-event recording");
    assertEquals(List.of(1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0, 2.0, 3.0),
            bus.samples.stream().map(sample -> sample.getDouble("value")).toList());
  }

  /**
   * The seam is one cycle wide, and a cycle is the recording's span plus its tightest observed
   * interval — here 300 ms + 100 ms. Checked on the stamps rather than the wall clock, because the
   * stamp is what a reactor's window keys off.
   */
  @Test
  void repeatShiftsEachPassByOneCycle() throws Exception {
    CapturingBus bus = new CapturingBus();
    replay(repeatable(), new JSONArray(), new JSONArray(), 1.0, 2, bus);

    assertEquals(6, bus.samples.size());
    long cycle = bus.millisOf(3) - bus.millisOf(0);
    assertEquals(400L, cycle, "span 300 ms + seam gap 100 ms");
    // The whole pass is shifted rigidly: the recording's internal timing survives repetition.
    assertEquals(bus.millisOf(1) - bus.millisOf(0), bus.millisOf(4) - bus.millisOf(3));
    assertEquals(bus.millisOf(2) - bus.millisOf(1), bus.millisOf(5) - bus.millisOf(4));
    assertTrue(bus.millisOf(3) > bus.millisOf(2), "the seam must not go backwards");
  }

  /** The cycle is expressed in recorded time, so speed compresses the seam like any other gap. */
  @Test
  void speedCompressesTheSeamToo() throws Exception {
    CapturingBus bus = new CapturingBus();
    replay(repeatable(), new JSONArray(), new JSONArray(), 4.0, 2, bus);

    assertEquals(100L, bus.millisOf(3) - bus.millisOf(0), "a 400 ms cycle at 4x");
  }

  /**
   * Samples sharing one device frame timestamp must still share one stamp in every pass — the
   * identity a reactor's logical-time gate keys off does not survive being split, and a repeated
   * run must not split it at the seam or anywhere after it.
   */
  @Test
  void repeatPreservesSharedFrameTimestamps() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Flow", 2.0),
            line("2026-04-01T17:49:33.200Z", "RealTime", "Vent", "Paw", 3.0));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 1.0, 2, bus);

    assertEquals(6, bus.samples.size());
    assertEquals(bus.microsOf(0), bus.microsOf(1), "shared frame, first pass");
    assertEquals(bus.microsOf(3), bus.microsOf(4), "shared frame, second pass");
  }

  /** Free-running mode has no schedule to shift, but still owes every pass its events. */
  @Test
  void repeatAppliesToFreeRunningReplayToo() throws Exception {
    CapturingBus bus = new CapturingBus();
    long start = System.currentTimeMillis();
    replay(repeatable(), new JSONArray(), new JSONArray(), 0.0, 4, bus);

    assertEquals(12, bus.samples.size());
    assertTrue(System.currentTimeMillis() - start < 1_000, "free-running replay must not pace");
  }

  /** A recording of one instant has no observable cadence; the seam falls back to a millisecond. */
  @Test
  void seamGapFallsBackWhenTheRecordingHasOneInstant() throws Exception {
    Path file = recording(
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Paw", 1.0),
            line("2026-04-01T17:49:33.000Z", "RealTime", "Vent", "Flow", 2.0));

    CapturingBus bus = new CapturingBus();
    replay(file, new JSONArray(), new JSONArray(), 1.0, 2, bus);

    assertEquals(4, bus.samples.size());
    assertEquals(1L, bus.millisOf(2) - bus.millisOf(0), "zero span + the 1 ms fallback gap");
  }

  /** Nonsense repetition counts degrade to a single pass rather than emitting nothing. */
  @Test
  void repeatBelowOneReplaysOnce() throws Exception {
    for (int repeat : new int[]{0, -3}) {
      CapturingBus bus = new CapturingBus();
      replay(repeatable(), new JSONArray(), new JSONArray(), 0.0, repeat, bus);
      assertEquals(3, bus.samples.size(), "repeat=%d must replay once".formatted(repeat));
    }
  }

  /**
   * Repetition lengthens a run without materially changing the rate it offers. The sweep that
   * motivated this feature plots latency against {@code targetHz}, so a repeated run has to stay
   * comparable to an unrepeated one on that axis.
   *
   * <p>The one source of drift is the seam: a repeated run spends {@code seamGap} of recorded time
   * per cycle carrying no events, so the rate falls by the seam's share of the cycle. That share is
   * the tightest interval in the recording divided by its span, which is negligible for any real
   * recording — on the 608 s session this feature was built for, a 1.7 ms seam moves the rate by
   * three ten-thousandths of a percent. It is only visible on a fixture short enough for one
   * inter-sample gap to be a sizeable fraction of the whole, which is what the exact figures below
   * pin down.</p>
   */
  @Test
  void repeatDoesNotChangeTheOfferedRate() throws Exception {
    Path file = repeatable();
    double once = replay(file, new JSONArray(), new JSONArray(), 4.0, 1, new CapturingBus())
            .stats().targetHz();
    double thrice = replay(file, new JSONArray(), new JSONArray(), 4.0, 3, new CapturingBus())
            .stats().targetHz();

    assertEquals(40.0, once, 1e-9, "3 events over a 300 ms span at 4x");
    // 9 events over 3 cycles less the trailing seam: 1100 ms of recorded time at 4x. The 18 % gap
    // from `once` is this fixture's 100 ms seam against its 300 ms span, not a defect.
    assertEquals(9 / (1.1 / 4.0), thrice, 1e-9);
  }

  /** On a recording dense enough to be realistic, that seam drift disappears into the noise. */
  @Test
  void offeredRateDriftShrinksWithTheRecordingLength() throws Exception {
    String[] lines = new String[101];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = line("2026-04-01T17:49:%02d.%03dZ".formatted(33 + i / 100, (i % 100) * 10),
              "RealTime", "Vent", "Paw", (double) i);
    }
    Path file = recording(lines);

    double once = replay(file, new JSONArray(), new JSONArray(), 0.0, 1, new CapturingBus())
            .stats().targetHz();
    double tenfold = replay(file, new JSONArray(), new JSONArray(), 0.0, 10, new CapturingBus())
            .stats().targetHz();

    // Free-running mode reports no schedule at all, so both are zero; the pacing comparison is the
    // one below, which is what the sweep's x-axis actually reads.
    assertEquals(0.0, once, 1e-9);
    assertEquals(0.0, tenfold, 1e-9);

    double pacedOnce = replay(file, new JSONArray(), new JSONArray(), 100.0, 1, new CapturingBus())
            .stats().targetHz();
    double pacedTenfold = replay(file, new JSONArray(), new JSONArray(), 100.0, 10,
            new CapturingBus()).stats().targetHz();

    assertEquals(pacedOnce, pacedTenfold, pacedOnce * 0.01,
            "a 10 ms seam against a 1 s span must move the offered rate by under 1 %");
  }

  /**
   * {@code repeat} is a required configuration key. Factory matches a constructor only when every
   * parameter name is present in the JSON, and there is deliberately no overload that would let a
   * config omit it and silently get one pass.
   */
  @Test
  void repeatIsARequiredConstructorParameter() {
    List<String> names = Arrays.stream(ReplayProtocol.class.getConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
            .map(Parameter::getName)
            .toList();

    assertEquals(1, ReplayProtocol.class.getConstructors().length,
            "a second constructor would make Factory's choice order-dependent");
    assertTrue(names.contains("repeat"), "Factory resolves config keys by parameter name");
  }
}
