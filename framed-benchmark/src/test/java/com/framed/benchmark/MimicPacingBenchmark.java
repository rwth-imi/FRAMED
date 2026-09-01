package com.framed.benchmark;

import com.framed.communicator.driver.protocol.mimic.MimicReplayProtocol;
import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.streamer.dispatcher.CountingDispatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Answers, end to end and in one JVM, whether a FRAMED deployment keeps pace with a waveform
 * record's sampling frequency.
 *
 * <p>A {@link MimicReplayProtocol} replays a real MIMIC-III record through a
 * {@link LocalEventBus} configured exactly as production is
 * ({@link DispatchMode#PER_HANDLER}, hardcoded in {@code Main}) into a {@link CountingDispatcher},
 * a sink that performs no I/O so the measurement isolates the framework. Three families of figures
 * come out of every run:</p>
 * <ul>
 *   <li><b>Pacing fidelity</b> (producer side) — did each frame go out on schedule?</li>
 *   <li><b>Keep-up / loss</b> (sink side) — did every published sample arrive, or did the bounded
 *       push queue shed load?</li>
 *   <li><b>End-to-end latency</b> — emit&rarr;sink delay percentiles.</li>
 * </ul>
 *
 * <p>The sweep separates two run classes on purpose. The <em>real-time multiples</em>
 * (1&times;, 5&times;, 10&times;, 50&times;) answer the clinical question and are the only rows where
 * pacing fidelity is meaningful — per-frame {@code Thread.sleep} cannot resolve sub-millisecond
 * intervals, so at high speeds the schedule is unreachable by construction. The <em>max-rate</em> run
 * therefore measures throughput only: with sleeps collapsed the pipeline runs flat out and the
 * sink's achieved datapoints/s is the ceiling, whose ratio to the record's real aggregate rate is the
 * headroom.</p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * mvn -pl framed-benchmark test -Dtest=MimicPacingBenchmark \
 *     -Dmimic.record=/path/to/mimic3wdb/1.0/30/3000003/3000003.hea
 * }</pre>
 * This is a measurement, not a regression test, and it is opt-in twice over: the class name falls
 * outside Surefire's default includes ({@code *Test}, {@code Test*}, {@code *Tests},
 * {@code *TestCase}), so an ordinary {@code mvn test} never runs it, and even when selected
 * explicitly it skips unless {@code -Dmimic.record} points at an existing record. A checkout without
 * the MIMIC dataset therefore builds unchanged. Results are printed and written to
 * {@code framed-benchmark/target/benchmark/mimic-pacing.csv}. The record window and the max-rate window are
 * tunable via {@code -Dmimic.bench.seconds} and {@code -Dmimic.bench.maxRateSeconds} (both in
 * <em>record</em> seconds).
 */
class MimicPacingBenchmark {

  private static final String RECORD_PROP = "mimic.record";
  private static final String DEVICE = "MIMIC-BENCH";
  private static final String CLASS_NAME = "Waveform";

  /**
   * Channel used to prove the sink is bound before the producer starts. The dispatcher registers a
   * sample handler asynchronously, on the handler thread that receives the address announcement; a
   * producer publishing into that window would lose its first samples and the loss metric would
   * measure a startup race rather than the framework. Priming closes the window deterministically.
   */
  private static final String PRIME_CHANNEL = "__prime__";

  /** Speed multipliers whose pacing fidelity is meaningful (sleep-paced). */
  private static final double[] REAL_TIME_SPEEDS = {1.0, 5.0, 10.0, 50.0};

  /** Speed high enough that every sleep collapses, so the run is throughput-bound. */
  private static final double MAX_RATE_SPEED = 100_000.0;

  private static final Path CSV = Path.of("target", "benchmark", "mimic-pacing.csv");

  /** One sweep row: what was configured, what the producer did, what the sink saw. */
  private record Row(double speed, double recordSeconds, boolean maxRate,
                     MimicReplayProtocol.PacingStats pacing, CountingDispatcher.Snapshot sink,
                     long expected, long received, int signals) {

    long missing() {
      return expected - received;
    }
  }

  @Test
  void sweepsReplaySpeedsAndReportsPacingLossAndLatency() throws Exception {
    String configured = System.getProperty(RECORD_PROP);
    assumeTrue(configured != null && !configured.isBlank(),
            "set -D%s=/path/to/record.hea to run the benchmark".formatted(RECORD_PROP));
    Path record = Path.of(configured);
    assumeTrue(Files.isRegularFile(record), "record header not found: " + record);

    double windowSeconds = Double.parseDouble(System.getProperty("mimic.bench.seconds", "20"));
    double maxRateSeconds = Double.parseDouble(System.getProperty("mimic.bench.maxRateSeconds", "600"));

    // Warm-up: let the JIT compile the publish/parse/push path before anything is measured.
    run(record, MAX_RATE_SPEED, 30.0);

    List<Row> rows = new ArrayList<>();
    for (double speed : REAL_TIME_SPEEDS) {
      rows.add(run(record, speed, windowSeconds));
    }
    rows.add(run(record, MAX_RATE_SPEED, maxRateSeconds));

    report(rows);

    // The clinical question: at true real time the deployment must not drift and must not lose.
    Row realTime = rows.get(0);
    assertEquals(0, realTime.sink().dropped(), "the sink dropped datapoints at 1x real time");
    assertEquals(0, realTime.sink().handlerErrors(), "messages failed to parse at 1x real time");
    assertEquals(0, realTime.missing(),
            "samples published but never delivered at 1x real time (expected %d, received %d)"
                    .formatted(realTime.expected(), realTime.received()));
    assertTrue(realTime.pacing().keptPace(),
            "producer fell behind at 1x real time: " + realTime.pacing());
  }

  /** Runs one replay to completion against a freshly wired bus + sink and collects the figures. */
  private Row run(Path record, double speed, double recordSeconds) throws Exception {
    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    CountingDispatcher sink = new CountingDispatcher(bus, new JSONArray(List.of(DEVICE)));
    try {
      primeAddressBinding(bus, sink);

      CountDownLatch done = new CountDownLatch(1);
      MimicReplayProtocol replay = new MimicReplayProtocol(
              "bench", bus, record.toString(), DEVICE, CLASS_NAME, new JSONArray(), speed, recordSeconds) {
        @Override protected long startupDelayMillis() { return 0; }
        @Override protected void onReplayComplete() { done.countDown(); }
      };

      long budgetSeconds = (long) (recordSeconds / speed) + 180L;
      assertTrue(done.await(budgetSeconds, TimeUnit.SECONDS),
              "replay at %.0fx did not finish within %d s".formatted(speed, budgetSeconds));
      assertTrue(sink.awaitQuiescence(Duration.ofMillis(500), Duration.ofMinutes(5)),
              "sink never drained at %.0fx".formatted(speed));

      MimicReplayProtocol.PacingStats pacing = replay.pacingStats();
      CountingDispatcher.Snapshot snapshot = sink.snapshot();

      return new Row(speed, recordSeconds, speed >= MAX_RATE_SPEED,
              pacing, snapshot, pacing.samples(), snapshot.received(),
              snapshot.perChannel().size());
    } finally {
      sink.shutdown(Duration.ofSeconds(5));
      bus.shutdown();
    }
  }

  /**
   * Publishes an address announcement plus sample traffic on a throwaway channel until the sink
   * counts one, proving the asynchronous address binding has completed, then clears the sink so none
   * of that setup traffic lands in the measurement.
   */
  private void primeAddressBinding(LocalEventBus bus, CountingDispatcher sink) throws InterruptedException {
    String address = "%s.%s.%s.parsed".formatted(CLASS_NAME, DEVICE, PRIME_CHANNEL);
    bus.publish(Service.addressRegistry(DEVICE), address);

    long deadline = System.currentTimeMillis() + 10_000L;
    while (System.currentTimeMillis() < deadline) {
      JSONObject sample = new JSONObject();
      sample.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(Timer.formatter));
      sample.put("channelID", PRIME_CHANNEL);
      sample.put("value", 0.0);
      sample.put("className", CLASS_NAME);
      bus.publish(address, sample);

      Thread.sleep(10);
      if (sink.snapshot().received() > 0) {
        // Let every in-flight priming sample land before clearing, so none of them can be counted
        // after the reset and make the delivery accounting come out negative.
        assertTrue(sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(10)),
                "sink did not settle after priming");
        sink.reset();
        return;
      }
    }
    throw new AssertionError("sink never bound the primed address within 10 s");
  }

  /** Writes the CSV and prints a human-readable table with the headroom over real time. */
  private void report(List<Row> rows) throws Exception {
    Files.createDirectories(CSV.getParent());

    StringBuilder csv = new StringBuilder("""
            speed,recordSeconds,maxRate,frames,signals,targetFrameHz,achievedFrameHz,framesBehind,\
            lagMeanMs,lagP50Ms,lagP95Ms,lagMaxMs,keptPace,expectedDatapoints,receivedDatapoints,\
            missingDatapoints,dropped,handlerErrors,sinkDatapointsPerSec,latMeanMs,latP50Ms,latP95Ms,\
            latP99Ms,latMaxMs
            """);
    for (Row r : rows) {
      MimicReplayProtocol.PacingStats p = r.pacing();
      CountingDispatcher.Snapshot s = r.sink();
      csv.append("%.0f,%.0f,%b,%d,%d,%.3f,%.3f,%d,%.3f,%d,%d,%d,%b,%d,%d,%d,%d,%d,%.1f,%.3f,%d,%d,%d,%d%n"
              .formatted(r.speed(), r.recordSeconds(), r.maxRate(), p.frames(), r.signals(),
                      p.targetHz(), p.achievedHz(), p.framesBehind(), p.meanLagMillis(),
                      p.p50LagMillis(), p.p95LagMillis(), p.maxLagMillis(), p.keptPace(),
                      r.expected(), r.received(), r.missing(), s.dropped(), s.handlerErrors(),
                      s.achievedHz(), s.meanLatencyMillis(), s.p50LatencyMillis(),
                      s.p95LatencyMillis(), s.p99LatencyMillis(), s.maxLatencyMillis()));
    }
    Files.writeString(CSV, csv.toString());

    System.out.printf("%n=== MIMIC pacing benchmark (%s) ===%n", CSV.toAbsolutePath());
    System.out.printf("%8s %8s %10s %10s %8s %9s %9s %12s %8s %8s %8s%n",
            "speed", "frames", "targetHz", "achieveHz", "behind", "lagP95", "missing",
            "sink dp/s", "latP50", "latP95", "latP99");
    for (Row r : rows) {
      MimicReplayProtocol.PacingStats p = r.pacing();
      CountingDispatcher.Snapshot s = r.sink();
      System.out.printf("%8.0f %8d %10.1f %10.1f %8d %9d %9d %12.0f %8d %8d %8d%n",
              r.speed(), p.frames(), p.targetHz(), p.achievedHz(), p.framesBehind(),
              p.p95LagMillis(), r.missing(), s.achievedHz(), s.p50LatencyMillis(),
              s.p95LatencyMillis(), s.p99LatencyMillis());
    }

    Row realTime = rows.get(0);
    Row ceiling = rows.get(rows.size() - 1);
    double realAggregateHz = realTime.pacing().targetHz() * realTime.signals();
    if (realAggregateHz > 0) {
      System.out.printf("real aggregate rate %.0f dp/s; throughput ceiling %.0f dp/s; headroom %.1fx%n",
              realAggregateHz, ceiling.sink().achievedHz(), ceiling.sink().achievedHz() / realAggregateHz);
    }
    System.out.printf("per-channel deliveries at 1x: %s%n", realTime.sink().perChannel());
    System.out.println("note: pacing fidelity is only interpretable on the sleep-paced rows; "
            + "the max-rate row measures throughput, where being 'behind' is expected by design.");
  }
}