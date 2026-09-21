package com.framed.streamer.dispatcher;

import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.io.dispatch.DataPoint;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the counting sink over a real {@link LocalEventBus}: announced channels are bound,
 * samples are counted per channel, latency is derived from the sample timestamp, and the failure
 * hooks tally instead of printing.
 *
 * <p>The bus is driven in {@link DispatchMode#SEQUENTIAL} so that address registration is complete
 * before the first sample is published; the push path stays asynchronous either way, so the
 * assertions wait via {@link CountingDispatcher#awaitQuiescence}.</p>
 */
class CountingDispatcherTest {

  private static final String DEVICE = "DEV";
  private static final String HR = "Numeric.DEV.HR.parsed";
  private static final String SPO2 = "Numeric.DEV.SpO2.parsed";

  private LocalEventBus bus;
  private CountingDispatcher sink;

  @BeforeEach
  void setUp() {
    bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    sink = new CountingDispatcher(bus, new JSONArray(List.of(DEVICE)), new JSONArray());
  }

  @AfterEach
  void tearDown() {
    sink.shutdown(Duration.ofSeconds(1));
    bus.shutdown();
  }

  /** Publishes a sample on {@code address} stamped {@code agedMillis} in the past. */
  private void publish(String address, String channelID, double value, long agedMillis) {
    JSONObject parsed = new JSONObject();
    parsed.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).minusNanos(agedMillis * 1_000_000L)
            .format(Timer.formatter));
    parsed.put("channelID", channelID);
    parsed.put("value", value);
    parsed.put("className", "Numeric");
    bus.publish(address, parsed);
  }

  private void announce(String address) {
    bus.publish(DEVICE + ".addresses", address);
  }

  /**
   * The channel filter scopes the figures without narrowing what the sink binds: a producer group
   * carrying several pipeline stages (a reactor network announces every stage under one group) can
   * then be measured on its final output alone.
   */
  @Test
  void channelFilterScopesTheFiguresToTheSelectedChannels() throws Exception {
    sink.shutdown(Duration.ofSeconds(1));
    sink = new CountingDispatcher(bus, new JSONArray(List.of(DEVICE)), new JSONArray(List.of("HR")));

    announce(HR);
    announce(SPO2);

    for (int i = 0; i < 4; i++) publish(HR, "HR", 60 + i, 0);
    for (int i = 0; i < 7; i++) publish(SPO2, "SpO2", 98, 0);

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");

    CountingDispatcher.Snapshot s = sink.snapshot();
    assertEquals(4, s.received(), "only the selected channel is counted");
    assertEquals(List.of("HR"), List.copyOf(s.perChannel().keySet()));
  }

  /**
   * A filtered-out arrival must still count as activity, or a benchmark reads its final snapshot
   * too early.
   *
   * <p>The filter scopes the figures, not the traffic: an unlisted channel's datapoints cross the
   * bus and occupy the same push queue. If only counted arrivals stamped the activity clock,
   * {@link CountingDispatcher#awaitQuiescence} would report "drained" one quiet period into a burst
   * of intermediate traffic — and the snapshot taken after it would undercount. That is the
   * documented measurement pattern for a filtered sink (the kink CDSS sink is one), so the last
   * arrival before quiescence being an uncounted one is the case that matters.</p>
   */
  @Test
  void aFilteredOutArrivalStillDefersQuiescence() throws Exception {
    sink.shutdown(Duration.ofSeconds(1));
    sink = new CountingDispatcher(bus, new JSONArray(List.of(DEVICE)), new JSONArray(List.of("HR")));
    announce(HR);
    announce(SPO2);

    // Establish a quiet baseline first, so the sink is demonstrably idle by more than the quiet
    // period below: without it the construction-time seed would satisfy the assertion by itself.
    assertTrue(sink.awaitQuiescence(Duration.ofMillis(400), Duration.ofSeconds(5)),
            "the freshly built sink never went quiet");

    publish(SPO2, "SpO2", 98, 0);      // counted by nothing, queued by everything

    // The push path is asynchronous, so poll for the arrival rather than assuming it has landed.
    boolean deferred = false;
    long deadline = System.currentTimeMillis() + 5_000;
    while (!deferred && System.currentTimeMillis() < deadline) {
      deferred = !sink.awaitQuiescence(Duration.ofMillis(400), Duration.ofMillis(20));
    }
    assertTrue(deferred, "an uncounted arrival left the sink reporting itself as drained");
    assertEquals(0, sink.snapshot().received(), "the filter must still scope the figures");
  }

  /** The same for a dropped datapoint the filter excludes: busy either way. */
  @Test
  void aFilteredOutDropStillDefersQuiescence() throws Exception {
    sink.shutdown(Duration.ofSeconds(1));
    sink = new CountingDispatcher(bus, new JSONArray(List.of(DEVICE)), new JSONArray(List.of("HR")));

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(400), Duration.ofSeconds(5)),
            "the freshly built sink never went quiet");

    sink.onDrop(new DataPoint<>(Instant.now(), 98.0, "SpO2", DEVICE, "Numeric"),
            new IllegalStateException("queue full"));

    assertFalse(sink.awaitQuiescence(Duration.ofMillis(400), Duration.ofMillis(100)),
            "an uncounted drop left the sink reporting itself as drained");
    assertEquals(0, sink.snapshot().dropped(), "the filter must still scope the figures");
  }

  /** An empty filter is the unfiltered sink: everything announced is counted. */
  @Test
  void emptyChannelFilterCountsEveryChannel() throws Exception {
    announce(HR);
    announce(SPO2);

    publish(HR, "HR", 60, 0);
    publish(SPO2, "SpO2", 98, 0);

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");
    assertEquals(2, sink.snapshot().received());
  }

  @Test
  void countsSamplesPerAnnouncedChannel() throws Exception {
    announce(HR);
    announce(SPO2);

    for (int i = 0; i < 5; i++) publish(HR, "HR", 60 + i, 0);
    for (int i = 0; i < 3; i++) publish(SPO2, "SpO2", 98, 0);

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");

    CountingDispatcher.Snapshot s = sink.snapshot();
    assertEquals(8, s.received());
    assertEquals(0, s.dropped());
    assertEquals(0, s.handlerErrors());
    assertEquals(5L, s.perChannel().get("HR"));
    assertEquals(3L, s.perChannel().get("SpO2"));
  }

  @Test
  void ignoresChannelsThatWereNeverAnnounced() throws Exception {
    announce(HR);
    publish(HR, "HR", 60, 0);
    publish(SPO2, "SpO2", 98, 0);   // never announced -> no handler bound

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");
    assertEquals(1, sink.snapshot().received());
  }

  @Test
  void derivesLatencyFromTheSampleTimestamp() throws Exception {
    announce(HR);
    for (int i = 0; i < 4; i++) publish(HR, "HR", 60, 50);   // stamped 50 ms in the past

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");

    CountingDispatcher.Snapshot s = sink.snapshot();
    assertEquals(4, s.received());
    assertTrue(s.p50LatencyMillis() >= 50,
            "expected p50 latency >= 50 ms, was " + s.p50LatencyMillis());
    assertTrue(s.meanLatencyMillis() >= 50.0,
            "expected mean latency >= 50 ms, was " + s.meanLatencyMillis());
    assertTrue(s.maxLatencyMillis() < 5_000, "latency implausibly large: " + s.maxLatencyMillis());
  }

  @Test
  void countsDropsInsteadOfPrintingThem() {
    DataPoint<Double> dp = new DataPoint<>(Instant.now(), 60.0, "HR", DEVICE, "Numeric");

    sink.onDrop(dp, new IllegalStateException("queue full"));
    sink.onDrop(dp, new IllegalStateException("queue full"));

    CountingDispatcher.Snapshot s = sink.snapshot();
    assertEquals(2, s.dropped());
    assertEquals(0, s.received(), "a drop must not be counted as a receipt");
  }

  @Test
  void countsUnparseableMessagesAsHandlerErrors() throws Exception {
    announce(HR);

    JSONObject broken = new JSONObject();      // no timestamp / channelID / value
    broken.put("className", "Numeric");
    bus.publish(HR, broken);

    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");

    CountingDispatcher.Snapshot s = sink.snapshot();
    assertEquals(1, s.handlerErrors());
    assertEquals(0, s.received());
  }

  @Test
  void resetClearsEveryFigureSoPrimingTrafficCannotLeakIntoAMeasurement() throws Exception {
    announce(HR);
    publish(HR, "HR", 60, 200);
    sink.onDrop(new DataPoint<>(Instant.now(), 60.0, "HR", DEVICE, "Numeric"),
            new IllegalStateException("queue full"));
    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");
    assertEquals(1, sink.snapshot().received());

    sink.reset();

    CountingDispatcher.Snapshot cleared = sink.snapshot();
    assertEquals(0, cleared.received());
    assertEquals(0, cleared.dropped());
    assertEquals(0, cleared.handlerErrors());
    assertTrue(cleared.perChannel().isEmpty());
    assertEquals(0, cleared.maxLatencyMillis(), "the pre-reset 200 ms sample must not survive");
    assertEquals(0.0, cleared.meanLatencyMillis());
    assertEquals(0, cleared.wallElapsedMillis(), "the timing window must restart, not extend");

    // The sink keeps working, and the restarted window covers only post-reset traffic.
    publish(HR, "HR", 61, 0);
    assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(5)), "sink never drained");
    CountingDispatcher.Snapshot after = sink.snapshot();
    assertEquals(1, after.received());
    assertEquals(1L, after.perChannel().get("HR"));
    assertTrue(after.maxLatencyMillis() < 200, "latency must reflect only the new sample");
  }

  /**
   * The summary must reach stdout, not the logger: in a full-app run {@code stop()} is called from
   * the launcher's shutdown hook, where {@code LogManager} may already have reset its handlers and
   * silently dropped the record.
   */
  @Test
  void stopPrintsTheSummaryToStdout() {
    sink.pushBatch(List.<DataPoint<?>>of(new DataPoint<>(Instant.now(), 60.0, "HR", DEVICE, "Numeric")));

    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      sink.stop();
    } finally {
      System.setOut(original);
    }

    String printed = captured.toString(StandardCharsets.UTF_8);
    assertTrue(printed.contains("CountingDispatcher summary"), "no summary printed: " + printed);
    assertTrue(printed.contains("received=1"), "summary lacks the counts: " + printed);
  }

  @Test
  void pushBatchCountsEveryDatapoint() {
    Instant now = Instant.now();
    sink.pushBatch(List.<DataPoint<?>>of(
            new DataPoint<>(now, 60.0, "HR", DEVICE, "Numeric"),
            new DataPoint<>(now, 61.0, "HR", DEVICE, "Numeric"),
            new DataPoint<>(now, 98.0, "SpO2", DEVICE, "Numeric")));

    CountingDispatcher.Snapshot s = sink.snapshot();
    assertEquals(3, s.received());
    assertEquals(2L, s.perChannel().get("HR"));
    assertEquals(1L, s.perChannel().get("SpO2"));
  }
}