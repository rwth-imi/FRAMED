package com.framed.benchmark;

import com.framed.communicator.driver.protocol.mimic.MimicReplayProtocol;
import com.framed.communicator.driver.protocol.mimic.WfdbHeader;
import com.framed.communicator.driver.protocol.mimic.WfdbSignal;
import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.remote.NioTcpTransport;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.streamer.dispatcher.CountingDispatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A throughput case study on the MIMIC-III waveform replay path: how many datapoints per second a
 * FRAMED deployment moves from producer to sink, where that rate stops tracking the offered load,
 * and what runs out first when it does.
 *
 * <p>{@link MimicPacingBenchmark} answers a yes/no question at one operating point — does the
 * deployment keep pace with 125 Hz. This class sweeps the operating points instead, so the answer
 * comes with a curve and a ceiling rather than a single verdict. Every run wires real components:
 * a {@link MimicReplayProtocol} publishing a real WFDB record onto a real {@link EventBus} into one
 * or more {@link CountingDispatcher} sinks, which perform no I/O so the figures isolate the
 * framework rather than a database.</p>
 *
 * <h2>Experiments</h2>
 * <dl>
 *   <dt><b>E1 — saturation curve</b></dt>
 *   <dd>One device, one sink, replay speed swept from real time to flat out. Locates the offered
 *       rate at which delivered throughput stops following it.</dd>
 *   <dt><b>E2 — device scaling</b></dt>
 *   <dd>N concurrent devices, one sink each, flat out. Aggregate ceiling and per-device efficiency:
 *       how a node's capacity divides across beds.</dd>
 *   <dt><b>E3 — sink fan-out</b></dt>
 *   <dd>One device, S sinks bound to the same channels, flat out. The marginal cost of an extra
 *       subscriber on the publish path.</dd>
 *   <dt><b>E4 — dispatch mode</b></dt>
 *   <dd>{@link DispatchMode#PER_HANDLER} (production), {@code SEQUENTIAL} and {@code PARALLEL} at a
 *       bounded offered rate, plus the two safe modes flat out. Shows which modes provide
 *       backpressure and which convert overload into queue growth.</dd>
 *   <dt><b>E5 — real-time bed capacity</b></dt>
 *   <dd>N devices at true 1&times; real time. The largest N that delivers everything within a
 *       latency budget is the deployable answer, and it is the only experiment whose latency
 *       figures are clinically meaningful.</dd>
 *   <dt><b>E6 — bus implementation</b></dt>
 *   <dd>{@link LocalEventBus} against {@link SocketEventBus} (production's bus, no peers attached),
 *       so the in-process figures can be read as production figures.</dd>
 * </dl>
 *
 * <h2>Metrics</h2>
 * <p>Throughput is reported in <em>datapoints per second</em> throughout, never frames per second:
 * MIMIC records are multi-segment and the number of signals present varies between segments, so
 * datapoints per frame is measured (<i>samplesPerFrame</i>) rather than assumed. Offered load is
 * {@code samplesPerFrame · fs · speed · devices}; it is undefined for the flat-out runs, where the
 * producer has no schedule to meet. {@code backlogAtProducerEnd} is the count published but not yet
 * delivered at the instant the last producer finished — the direct measure of queue growth under
 * overload. Latency percentiles are the worst value across the run's sinks, and are bounded below
 * by the 1 ms resolution of the wire timestamp.</p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * mvn -pl framed-benchmark test -Dtest=MimicThroughputBenchmark \
 *     -Dmimic.record=/path/to/mimic3wdb/1.0/30/3000125/3000125.hea \
 *     -DargLine="-Xmx6g"
 * }</pre>
 * Like the pacing benchmark this is opt-in twice over — the {@code *Benchmark} name is outside
 * Surefire's default includes, and it skips unless {@code -Dmimic.record} resolves. The heap
 * setting matters: the flat-out runs deliberately let a backlog form, and that backlog is retained
 * JSON. Results go to {@code framed-benchmark/target/benchmark/mimic-throughput.csv} plus a table
 * on stdout.
 *
 * <h3>Knobs</h3>
 * <ul>
 *   <li>{@code mimic.tp.experiments} — comma-separated subset of {@code e1,e2,e3,e4,e5,e6} (default all)</li>
 *   <li>{@code mimic.tp.repeats} — repetitions per bounded point, median reported (default 3)</li>
 *   <li>{@code mimic.tp.wallSeconds} — target wall-clock duration of a bounded-rate point (default 6)</li>
 *   <li>{@code mimic.tp.frameBudget} — hard cap on frames read per run (default 1 000 000)</li>
 *   <li>{@code mimic.tp.realTimeSeconds} — record seconds per E5 point (default 30)</li>
 *   <li>{@code mimic.tp.maxDevices} — largest concurrent device count in E2 (default 16)</li>
 *   <li>{@code mimic.tp.maxBeds} — largest real-time bed count in E5 (default 256)</li>
 *   <li>{@code mimic.tp.speeds} — comma-separated speed multipliers replacing E1's sweep, which also
 *       drops E1's flat-out point. Repeating one point ({@code -Dmimic.tp.speeds=100000
 *       -Dmimic.tp.repeats=10}) is how to tell a stable ceiling from one that drifts as the machine
 *       warms up.</li>
 *   <li>{@code mimic.tp.csv} — output file name under {@code target/benchmark}
 *       (default {@code mimic-throughput.csv}), so re-measuring one experiment does not truncate an
 *       earlier sweep. {@code benchmark/analyse-throughput.py} merges several CSVs, later files
 *       superseding earlier ones per operating point.</li>
 * </ul>
 */
class MimicThroughputBenchmark {

  private static final String RECORD_PROP = "mimic.record";
  private static final String CLASS_NAME = "Waveform";

  /** Speed high enough that every pacing sleep collapses, so the producer runs flat out. */
  private static final double MAX_RATE = 100_000.0;

  private static final Path CSV = Path.of("target", "benchmark",
          System.getProperty("mimic.tp.csv", "mimic-throughput.csv"));

  private static final String CSV_HEADER = """
          experiment,label,repeat,ok,failure,devices,sinksPerDevice,dispatchMode,bus,speed,\
          recordSeconds,channels,samplesPerFrame,publishedDatapoints,expectedDeliveries,\
          receivedDatapoints,undelivered,deliveryRatio,backlogAtProducerEnd,dropped,handlerErrors,\
          offeredDpPerSec,producerDpPerSec,deliveredDpPerSec,sinkSustainedDpPerSec,\
          latP50Ms,latP95Ms,latP99Ms,latMaxMs,lagP95Ms,lagMaxMs,framesBehind,frames,keptPace,\
          producerWallMs,harnessWallMs,peakHeapMb,peakThreads,gcCount,gcMillis
          """;

  /** Which {@link EventBus} implementation a point is measured on. */
  private enum BusKind { LOCAL, SOCKET }

  /** One configured operating point. */
  private record Point(String experiment, String label, int devices, int sinksPerDevice,
                       DispatchMode mode, BusKind bus, double speed, double recordSeconds) {

    boolean flatOut() {
      return speed >= MAX_RATE;
    }
  }

  /** What one execution of a {@link Point} produced. */
  private record Outcome(Point point, int repeat, boolean ok, String failure,
                         long publishedDp, long expectedDeliveries, long receivedDp,
                         long backlogAtProducerEnd, long dropped, long handlerErrors,
                         double samplesPerFrame, double offeredDpPerSec,
                         double producerDpPerSec, double deliveredDpPerSec, double sinkSustainedDpPerSec,
                         long latP50, long latP95, long latP99, long latMax,
                         long lagP95, long lagMax, long framesBehind, long frames,
                         boolean keptPace, long producerWallMs, long harnessWallMs,
                         long peakHeapMb, int peakThreads, long gcCount, long gcMillis,
                         int channels) {

    long undelivered() {
      return expectedDeliveries - receivedDp;
    }

    /** Fraction of what was published that actually reached every sink bound to it. */
    double deliveryRatio() {
      return expectedDeliveries == 0 ? 0.0 : (double) receivedDp / expectedDeliveries;
    }
  }

  @Test
  void sweepsOfferedLoadDeviceCountFanOutAndDispatchMode() throws Exception {
    String configured = System.getProperty(RECORD_PROP);
    assumeTrue(configured != null && !configured.isBlank(),
            "set -D%s=/path/to/record.hea to run the benchmark".formatted(RECORD_PROP));
    Path record = Path.of(configured);
    assumeTrue(Files.isRegularFile(record), "record header not found: " + record);

    Set<String> wanted = Set.of(System.getProperty("mimic.tp.experiments", "e1,e2,e3,e4,e5,e6")
            .toLowerCase().split("\\s*,\\s*"));
    int repeats = Integer.getInteger("mimic.tp.repeats", 3);
    double wallSeconds = Double.parseDouble(System.getProperty("mimic.tp.wallSeconds", "6"));
    long frameBudget = Long.getLong("mimic.tp.frameBudget", 1_000_000L);
    double realTimeSeconds = Double.parseDouble(System.getProperty("mimic.tp.realTimeSeconds", "30"));
    int maxDevices = Integer.getInteger("mimic.tp.maxDevices", 16);
    int maxBeds = Integer.getInteger("mimic.tp.maxBeds", 256);

    WfdbHeader master = WfdbHeader.parse(record);
    double fs = master.samplingFrequency();
    List<String> channels = channelIdsOf(record, master);

    System.out.printf("record %s: %.0f Hz, %d layout signals %s, %d segments%n",
            master.recordName(), fs, channels.size(), channels, master.segments().size());

    // Sizing. A bounded-rate point reads whatever fits in the target wall-clock window, capped by
    // the frame budget so a fast point cannot read the whole 190-hour record.
    double budgetSeconds = frameBudget / fs;
    java.util.function.DoubleUnaryOperator recordSecondsFor =
            speed -> Math.min(speed * wallSeconds, budgetSeconds);

    List<Point> points = new ArrayList<>();

    if (wanted.contains("e1")) {
      // Overridable so a single operating point can be repeated over time — the way to tell a
      // stable ceiling from one that drifts as the machine heats up.
      double[] sweep = parseSpeeds(System.getProperty("mimic.tp.speeds",
              "1,2,5,10,20,50,100,200,500,1000,1500,2000,3000,4000,5000"));
      for (double speed : sweep) {
        points.add(new Point("E1", "speed=%.0fx".formatted(speed), 1, 1,
                DispatchMode.PER_HANDLER, BusKind.LOCAL, speed, recordSecondsFor.applyAsDouble(speed)));
      }
      if (System.getProperty("mimic.tp.speeds") == null) {
        points.add(new Point("E1", "speed=flat-out", 1, 1,
                DispatchMode.PER_HANDLER, BusKind.LOCAL, MAX_RATE, budgetSeconds));
      }
    }

    if (wanted.contains("e2")) {
      for (int devices = 1; devices <= maxDevices; devices *= 2) {
        // Hold total work roughly constant so a 64-device point is not 64x the wall clock.
        double seconds = Math.max(budgetSeconds / devices, 60.0);
        points.add(new Point("E2", "devices=%d".formatted(devices), devices, 1,
                DispatchMode.PER_HANDLER, BusKind.LOCAL, MAX_RATE, seconds));
      }
    }

    if (wanted.contains("e3")) {
      for (int sinks : new int[]{1, 2, 4, 8}) {
        points.add(new Point("E3", "sinks=%d".formatted(sinks), 1, sinks,
                DispatchMode.PER_HANDLER, BusKind.LOCAL, MAX_RATE, budgetSeconds / sinks));
      }
    }

    if (wanted.contains("e4")) {
      // PARALLEL is measured at a bounded rate only: it submits one task per message per handler to
      // an unbounded cached pool, and flat out that is a thread-count experiment, not a throughput
      // one. The two modes that are safe to saturate are also run flat out.
      for (DispatchMode mode : DispatchMode.values()) {
        points.add(new Point("E4", "mode=%s@200x".formatted(mode), 1, 4,
                mode, BusKind.LOCAL, 200.0, recordSecondsFor.applyAsDouble(200.0)));
      }
      for (DispatchMode mode : new DispatchMode[]{DispatchMode.PER_HANDLER, DispatchMode.SEQUENTIAL}) {
        points.add(new Point("E4", "mode=%s@flat-out".formatted(mode), 1, 4,
                mode, BusKind.LOCAL, MAX_RATE, budgetSeconds / 4));
      }
    }

    if (wanted.contains("e5")) {
      for (int beds = 1; beds <= maxBeds; beds *= 4) {
        points.add(new Point("E5", "beds=%d".formatted(beds), beds, 1,
                DispatchMode.PER_HANDLER, BusKind.LOCAL, 1.0, realTimeSeconds));
      }
    }

    if (wanted.contains("e6")) {
      for (BusKind bus : BusKind.values()) {
        for (double speed : new double[]{1, 200, MAX_RATE}) {
          double seconds = speed >= MAX_RATE ? budgetSeconds : recordSecondsFor.applyAsDouble(speed);
          points.add(new Point("E6", "%s@%s".formatted(bus, speed >= MAX_RATE ? "flat-out"
                  : "%.0fx".formatted(speed)), 1, 1, DispatchMode.PER_HANDLER, bus, speed, seconds));
        }
      }
    }

    assumeTrue(!points.isEmpty(), "no experiments selected");

    startCsv();

    // Warm-up: compile the publish/parse/push path before anything is recorded.
    System.out.println("warm-up...");
    runOnce(record, new Point("warmup", "warmup", 1, 1, DispatchMode.PER_HANDLER,
            BusKind.LOCAL, MAX_RATE, 120.0), 0, fs, channels);

    List<Outcome> outcomes = new ArrayList<>();
    for (Point p : points) {
      // The real-time points are wall-clock bound; repeating each one costs minutes for no extra
      // resolution, so they run once.
      int reps = "E5".equals(p.experiment()) ? 1 : repeats;
      for (int r = 1; r <= reps; r++) {
        System.out.printf("  %s %-24s repeat %d/%d ... ", p.experiment(), p.label(), r, reps);
        System.out.flush();
        Outcome o = runOnce(record, p, r, fs, channels);
        outcomes.add(o);
        appendCsv(o);
        System.out.println(o.ok()
                ? "%.0f dp/s delivered, %.4f delivered/published, p95 %d ms"
                        .formatted(o.deliveredDpPerSec(), o.deliveryRatio(), o.latP95())
                : "FAILED: " + o.failure());
        System.gc();
        Thread.sleep(500);
      }
    }

    report(outcomes, fs);
  }

  private static double[] parseSpeeds(String csv) {
    String[] parts = csv.split("\\s*,\\s*");
    double[] speeds = new double[parts.length];
    for (int i = 0; i < parts.length; i++) speeds[i] = Double.parseDouble(parts[i]);
    return speeds;
  }

  // ---------------------------------------------------------------------------------------------
  // One measurement
  // ---------------------------------------------------------------------------------------------

  /** Wires a fresh bus, sinks and producers for one point, runs it to completion and measures it. */
  private Outcome runOnce(Path record, Point p, int repeat, double fs, List<String> channels)
          throws Exception {
    List<CountingDispatcher> sinks = new ArrayList<>();
    Map<String, List<CountingDispatcher>> byDevice = new LinkedHashMap<>();
    List<MimicReplayProtocol> producers = new ArrayList<>();
    ResourceSampler sampler = new ResourceSampler();
    EventBus bus = null;

    try {
      bus = newBus(p.bus(), p.mode());
      for (int d = 0; d < p.devices(); d++) {
        String device = "BENCH-%02d".formatted(d);
        List<CountingDispatcher> forDevice = new ArrayList<>();
        for (int s = 0; s < p.sinksPerDevice(); s++) {
          CountingDispatcher sink = new CountingDispatcher(bus, new JSONArray(List.of(device)));
          sinks.add(sink);
          forDevice.add(sink);
        }
        byDevice.put(device, forDevice);
      }

      primeAddressBindings(bus, byDevice, channels);

      CountDownLatch done = new CountDownLatch(p.devices());
      long t0 = System.currentTimeMillis();
      for (String device : byDevice.keySet()) {
        producers.add(new MimicReplayProtocol("bench-" + device, bus, record.toString(), device,
                CLASS_NAME, new JSONArray(), p.speed(), p.recordSeconds()) {
          @Override protected long startupDelayMillis() { return 0; }
          @Override protected void onReplayComplete() { done.countDown(); }
        });
      }

      long budget = p.flatOut() ? 900L : (long) (p.recordSeconds() / p.speed()) + 300L;
      if (!done.await(budget, TimeUnit.SECONDS)) {
        return failed(p, repeat, "producers did not finish within %d s".formatted(budget), sampler);
      }

      // Backlog is only meaningful at this instant: published, but not yet handed to a sink.
      long receivedAtProducerEnd = sinks.stream().mapToLong(s -> s.snapshot().received()).sum();

      for (CountingDispatcher sink : sinks) {
        if (!sink.awaitQuiescence(Duration.ofMillis(500), Duration.ofMinutes(10))) {
          return failed(p, repeat, "a sink never drained", sampler);
        }
      }
      long t1 = System.currentTimeMillis();

      long publishedDp = 0;
      long frames = 0;
      long producerWallMs = 0;
      long lagP95 = 0;
      long lagMax = 0;
      long framesBehind = 0;
      boolean keptPace = true;
      for (MimicReplayProtocol producer : producers) {
        MimicReplayProtocol.PacingStats stats = producer.pacingStats();
        publishedDp += stats.samples();
        frames += stats.frames();
        producerWallMs = Math.max(producerWallMs, stats.wallElapsedMillis());
        lagP95 = Math.max(lagP95, stats.p95LagMillis());
        lagMax = Math.max(lagMax, stats.maxLagMillis());
        framesBehind += stats.framesBehind();
        keptPace &= stats.keptPace();
      }

      long receivedDp = 0;
      long dropped = 0;
      long handlerErrors = 0;
      long p50 = 0, p95 = 0, p99 = 0, latMax = 0;
      double sustained = 0;
      Set<String> seenChannels = new LinkedHashSet<>();
      for (CountingDispatcher sink : sinks) {
        CountingDispatcher.Snapshot s = sink.snapshot();
        receivedDp += s.received();
        dropped += s.dropped();
        handlerErrors += s.handlerErrors();
        sustained += s.achievedHz();
        seenChannels.addAll(s.perChannel().keySet());
        // Worst sink wins: a mean over sinks would hide the one that fell behind.
        p50 = Math.max(p50, s.p50LatencyMillis());
        p95 = Math.max(p95, s.p95LatencyMillis());
        p99 = Math.max(p99, s.p99LatencyMillis());
        latMax = Math.max(latMax, s.maxLatencyMillis());
      }

      long expected = publishedDp * p.sinksPerDevice();
      double samplesPerFrame = frames == 0 ? 0 : (double) publishedDp / frames;
      double offered = p.flatOut() ? Double.NaN : samplesPerFrame * fs * p.speed() * p.devices();
      long harnessWallMs = Math.max(1, t1 - t0);

      return new Outcome(p, repeat, true, "",
              publishedDp, expected, receivedDp,
              expected - receivedAtProducerEnd, dropped, handlerErrors,
              samplesPerFrame, offered,
              producerWallMs == 0 ? 0 : publishedDp * 1000.0 / producerWallMs,
              receivedDp * 1000.0 / harnessWallMs, sustained,
              p50, p95, p99, latMax, lagP95, lagMax, framesBehind, frames, keptPace,
              producerWallMs, harnessWallMs,
              sampler.peakHeapMb(), sampler.peakThreads(),
              sampler.gcCount(), sampler.gcMillis(), seenChannels.size());

    } catch (Throwable t) {
      return failed(p, repeat, t.getClass().getSimpleName() + ": " + t.getMessage(), sampler);
    } finally {
      sampler.close();
      for (CountingDispatcher sink : sinks) {
        try { sink.shutdown(Duration.ofSeconds(5)); } catch (RuntimeException ignored) { /* teardown */ }
      }
      if (bus != null) bus.shutdown();
    }
  }

  private Outcome failed(Point p, int repeat, String why, ResourceSampler sampler) {
    return new Outcome(p, repeat, false, why, 0, 0, 0, 0, 0, 0, 0, Double.NaN,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0,
            sampler.peakHeapMb(), sampler.peakThreads(), sampler.gcCount(), sampler.gcMillis(), 0);
  }

  private EventBus newBus(BusKind kind, DispatchMode mode) throws IOException {
    if (kind == BusKind.LOCAL) return new LocalEventBus(mode);
    // Production's bus, with no peers attached: the remote fan-out is a separate question, and
    // measuring it here would confound the local dispatch cost with a socket. Port 0 lets the OS
    // pick: probing for a free port and then binding it races with everything else on the machine,
    // and no peer has to find this transport anyway.
    return new SocketEventBus(new NioTcpTransport(0), mode);
  }

  // ---------------------------------------------------------------------------------------------
  // Setup helpers
  // ---------------------------------------------------------------------------------------------

  /** The channel IDs a replay of this record will publish, derived the way the protocol derives them. */
  private List<String> channelIdsOf(Path record, WfdbHeader master) throws IOException {
    Set<String> names = new LinkedHashSet<>();
    Path baseDir = record.toAbsolutePath().getParent();
    List<WfdbSignal> signals = master.signals();
    if (master.isMultiSegment()) {
      for (WfdbHeader.Segment seg : master.segments()) {
        if (seg.name().endsWith("_layout")) {
          signals = WfdbHeader.parse(baseDir.resolve(seg.name() + ".hea")).signals();
          break;
        }
      }
    }
    for (int s = 0; s < signals.size(); s++) {
      String d = signals.get(s).description();
      names.add(d == null || d.isBlank() ? "sig" + s : d.trim().replaceAll("\\s+", "_"));
    }
    return List.copyOf(names);
  }

  /**
   * Binds every sink to every channel it will later receive, before any producer starts.
   *
   * <p>The dispatcher's channel binding is asynchronous — it happens on the handler thread that
   * receives the address announcement — and the replay protocol announces its channels and then
   * immediately starts publishing. Flat out, that window is wide enough to lose a visible number of
   * samples, and the delivery ratio would then be measuring a startup race instead of the
   * framework. Priming closes it deterministically: announce and publish on every real address
   * until each sink has counted a sample on each, then clear the sinks so none of the setup traffic
   * lands in the measurement.</p>
   */
  private void primeAddressBindings(EventBus bus, Map<String, List<CountingDispatcher>> byDevice,
                                    List<String> channels) throws InterruptedException {
    for (Map.Entry<String, List<CountingDispatcher>> e : byDevice.entrySet()) {
      for (String channel : channels) {
        bus.publish(Service.addressRegistry(e.getKey()), addressOf(e.getKey(), channel));
      }
    }

    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      for (Map.Entry<String, List<CountingDispatcher>> e : byDevice.entrySet()) {
        for (String channel : channels) {
          JSONObject sample = new JSONObject();
          sample.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(Timer.formatter));
          sample.put("channelID", channel);
          sample.put("value", 0.0);
          sample.put("className", CLASS_NAME);
          bus.publish(addressOf(e.getKey(), channel), sample);
        }
      }
      Thread.sleep(20);

      boolean allBound = byDevice.values().stream().flatMap(List::stream)
              .allMatch(sink -> sink.snapshot().perChannel().keySet().containsAll(channels));
      if (allBound) {
        for (CountingDispatcher sink : byDevice.values().stream().flatMap(List::stream).toList()) {
          if (!sink.awaitQuiescence(Duration.ofMillis(100), Duration.ofSeconds(30))) {
            throw new IllegalStateException("sink did not settle after priming");
          }
          sink.reset();
        }
        return;
      }
    }
    throw new IllegalStateException("sinks never bound every primed channel within 60 s");
  }

  private String addressOf(String device, String channel) {
    return "%s.%s.%s.parsed".formatted(CLASS_NAME, device, channel);
  }

  // ---------------------------------------------------------------------------------------------
  // Reporting
  // ---------------------------------------------------------------------------------------------

  /** Truncates the CSV and writes its header, ready for {@link #appendCsv}. */
  private void startCsv() throws IOException {
    Files.createDirectories(CSV.getParent());
    Files.writeString(CSV, CSV_HEADER);
  }

  /**
   * Appends one completed point and flushes it.
   *
   * <p>A sweep runs for tens of minutes; writing the whole file at the end means a failure in the
   * last experiment throws away everything measured before it. Once bitten.</p>
   */
  private void appendCsv(Outcome o) throws IOException {
    Point p = o.point();
    String line = "%s,%s,%d,%b,%s,%d,%d,%s,%s,%.0f,%.0f,%d,%.4f,%d,%d,%d,%d,%.6f,%d,%d,%d,%s,%.1f,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%b,%d,%d,%d,%d,%d,%d%n"
            .formatted(p.experiment(), p.label(), o.repeat(), o.ok(), o.failure().replace(',', ';'),
                    p.devices(), p.sinksPerDevice(), p.mode(), p.bus(), p.speed(), p.recordSeconds(),
                    o.channels(), o.samplesPerFrame(), o.publishedDp(), o.expectedDeliveries(),
                    o.receivedDp(), o.undelivered(), o.deliveryRatio(), o.backlogAtProducerEnd(),
                    o.dropped(), o.handlerErrors(),
                    Double.isNaN(o.offeredDpPerSec()) ? "" : "%.1f".formatted(o.offeredDpPerSec()),
                    o.producerDpPerSec(), o.deliveredDpPerSec(), o.sinkSustainedDpPerSec(),
                    o.latP50(), o.latP95(), o.latP99(), o.latMax(), o.lagP95(), o.lagMax(),
                    o.framesBehind(), o.frames(), o.keptPace(),
                    o.producerWallMs(), o.harnessWallMs(), o.peakHeapMb(), o.peakThreads(),
                    o.gcCount(), o.gcMillis());
    Files.writeString(CSV, line, java.nio.file.StandardOpenOption.APPEND);
  }

  /** Prints one block per experiment, each row the median repeat by delivered throughput. */
  private void report(List<Outcome> outcomes, double fs) {
    System.out.printf("%n=== MIMIC throughput case study (%s) ===%n", CSV.toAbsolutePath());

    Map<String, List<Outcome>> byExperiment = new LinkedHashMap<>();
    for (Outcome o : outcomes) {
      byExperiment.computeIfAbsent(o.point().experiment(), k -> new ArrayList<>()).add(o);
    }

    for (Map.Entry<String, List<Outcome>> exp : byExperiment.entrySet()) {
      System.out.printf("%n-- %s --%n", exp.getKey());
      System.out.printf("%-22s %12s %12s %12s %10s %9s %8s %8s %8s %7s %7s%n",
              "point", "offered", "produced", "consumed", "delivered", "backlog", "dropped",
              "latP95", "latP99", "heapMB", "threads");
      System.out.printf("%-22s %12s %12s %12s %10s %9s %8s %8s %8s %7s %7s%n",
              "", "dp/s", "dp/s", "dp/s", "/published", "dp", "dp", "ms", "ms", "peak", "peak");

      Map<String, List<Outcome>> byLabel = new LinkedHashMap<>();
      for (Outcome o : exp.getValue()) {
        byLabel.computeIfAbsent(o.point().label(), k -> new ArrayList<>()).add(o);
      }
      for (Map.Entry<String, List<Outcome>> point : byLabel.entrySet()) {
        Outcome m = median(point.getValue());
        if (!m.ok()) {
          System.out.printf("%-22s  FAILED: %s%n", point.getKey(), m.failure());
          continue;
        }
        System.out.printf("%-22s %12s %12.0f %12.0f %10.4f %9d %8d %8d %8d %7d %7d%n",
                point.getKey(),
                Double.isNaN(m.offeredDpPerSec()) ? "flat-out" : "%.0f".formatted(m.offeredDpPerSec()),
                m.producerDpPerSec(), m.sinkSustainedDpPerSec(), m.deliveryRatio(),
                m.backlogAtProducerEnd(), m.dropped(), m.latP95(), m.latP99(),
                m.peakHeapMb(), m.peakThreads());
      }
    }

    System.out.printf("%nfs=%.0f Hz. Throughput is datapoints/s. 'offered' = samplesPerFrame*fs*speed*devices, "
            + "undefined flat out. 'produced' is the producer's own publish rate; 'consumed' is the "
            + "sinks' sustained rate over their active window (the CSV also carries the "
            + "harness-window figure, which setup and drain dilute). Latency percentiles are the "
            + "worst sink's, floored by the 1 ms wire-timestamp resolution.%n", fs);
  }

  /** Median repeat of a point, ranked by delivered throughput; failures sort first so they surface. */
  private Outcome median(List<Outcome> repeats) {
    List<Outcome> sorted = new ArrayList<>(repeats);
    sorted.sort(Comparator.comparingDouble(o -> o.ok() ? o.deliveredDpPerSec() : -1));
    return sorted.get(sorted.size() / 2);
  }

  // ---------------------------------------------------------------------------------------------
  // Resource sampling
  // ---------------------------------------------------------------------------------------------

  /**
   * Samples heap and thread count for the duration of a run.
   *
   * <p>Peak heap is <em>used</em> heap, so it includes garbage not yet collected and is an upper
   * bound on the live backlog rather than a measurement of it; {@code backlogAtProducerEnd} is the
   * direct figure. Peak thread count is exact and is the interesting one under
   * {@link DispatchMode#PER_HANDLER}, which allocates one executor per distinct handler.</p>
   */
  private static final class ResourceSampler implements AutoCloseable {

    private final Thread thread;
    private final long gcCountAtStart;
    private final long gcMillisAtStart;
    private volatile boolean running = true;
    private volatile long peakHeapBytes;
    private volatile int peakThreads;
    private volatile long gcCountAtEnd;
    private volatile long gcMillisAtEnd;

    ResourceSampler() {
      gcCountAtStart = totalGcCount();
      gcMillisAtStart = totalGcMillis();
      gcCountAtEnd = gcCountAtStart;
      gcMillisAtEnd = gcMillisAtStart;
      MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
      ThreadMXBean threads = ManagementFactory.getThreadMXBean();
      thread = new Thread(() -> {
        while (running) {
          peakHeapBytes = Math.max(peakHeapBytes, memory.getHeapMemoryUsage().getUsed());
          peakThreads = Math.max(peakThreads, threads.getThreadCount());
          gcCountAtEnd = totalGcCount();
          gcMillisAtEnd = totalGcMillis();
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            return;
          }
        }
      }, "bench-sampler");
      thread.setDaemon(true);
      thread.start();
    }

    long peakHeapMb() {
      return peakHeapBytes / (1024 * 1024);
    }

    int peakThreads() {
      return peakThreads;
    }

    /** Collections that ran during this point. */
    long gcCount() {
      return Math.max(0, gcCountAtEnd - gcCountAtStart);
    }

    /**
     * Approximate wall-clock milliseconds spent collecting during this point.
     *
     * <p>{@code GarbageCollectorMXBean.getCollectionTime()} is documented as approximate and sums
     * over collector threads, so treat it as an indicator of collection pressure rather than as a
     * stop-the-world figure.</p>
     */
    long gcMillis() {
      return Math.max(0, gcMillisAtEnd - gcMillisAtStart);
    }

    private static long totalGcCount() {
      long total = 0;
      for (java.lang.management.GarbageCollectorMXBean gc
              : ManagementFactory.getGarbageCollectorMXBeans()) {
        long count = gc.getCollectionCount();
        if (count > 0) total += count;
      }
      return total;
    }

    private static long totalGcMillis() {
      long total = 0;
      for (java.lang.management.GarbageCollectorMXBean gc
              : ManagementFactory.getGarbageCollectorMXBeans()) {
        long millis = gc.getCollectionTime();
        if (millis > 0) total += millis;
      }
      return total;
    }

    @Override
    public void close() {
      running = false;
      thread.interrupt();
    }
  }
}
