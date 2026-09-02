package com.framed.benchmark;

import com.framed.communicator.driver.protocol.mimic.MimicReplayProtocol;
import com.framed.communicator.driver.protocol.mimic.WfdbHeader;
import com.framed.communicator.driver.protocol.mimic.WfdbSignal;
import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.remote.NioTcpTransport;
import com.framed.core.remote.NioUdpTransport;
import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.Transport;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.streamer.dispatcher.CountingDispatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a FRAMED deployment costs when the producer and the sink are two separate instances talking
 * over {@link SocketEventBus}, rather than two services sharing one bus.
 *
 * <p>{@link MimicThroughputBenchmark} measured the in-process path and, for the bus comparison, ran
 * {@code SocketEventBus} with <em>no peers attached</em> — so the remote leg was never exercised.
 * This benchmark closes that gap. Two buses are wired over loopback: a producer instance running the
 * MIMIC replay, and a reader instance running a {@link CountingDispatcher}. Nothing about the
 * services changes; only the bus between them does.</p>
 *
 * <pre>
 *   instance A                                  instance B
 *   MimicReplayProtocol -> SocketEventBus ==[ TCP | UDP ]==> SocketEventBus -> CountingDispatcher
 * </pre>
 *
 * <p>{@link Wiring#LOCAL} runs both services on one {@link LocalEventBus} in the same measurement
 * session, so the cost of crossing the wire is a difference measured here rather than a figure
 * quoted from another study.</p>
 *
 * <h2>What the remote leg changes</h2>
 * <ul>
 *   <li><b>Loss becomes possible.</b> In process, {@code publish} either finds a handler or does
 *       not. Across the wire a datagram can be dropped, a connection can fail, and
 *       {@code RemoteUtils.parseAndDispatchAsync} silently discards a message whose address has no
 *       handler on the receiving side. Delivery ratio, not throughput, is the headline metric.</li>
 *   <li><b>The discovery handshake is no longer synchronous.</b>
 *       {@code Service.subscribeToAnnouncements} makes binding inline on the announcing thread, but
 *       that thread is on the <em>producer's</em> instance; the reader binds on its own transport
 *       worker, so a producer can out-run a remote sink's binding. {@code E3} measures that
 *       directly by not priming.</li>
 *   <li><b>Ordering is not preserved.</b> {@code SocketEventBus.publish} submits one task per peer
 *       to a cached pool, so announcement and samples race each other onto the wire.</li>
 * </ul>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * mvn -pl framed-benchmark test -Dtest=SocketPairThroughputBenchmark \
 *     -Dmimic.record=/path/to/3000125.hea -DargLine="-Xmx8g"
 * }</pre>
 * Opt-in like the other benchmarks: outside Surefire's default includes, and skipped without
 * {@code -Dmimic.record}. Results are written point by point to
 * {@code framed-benchmark/target/benchmark/socket-pair.csv}.
 *
 * <h3>Knobs</h3>
 * <ul>
 *   <li>{@code mimic.sp.experiments} — subset of {@code e1,e2,e3} (default all)</li>
 *   <li>{@code mimic.sp.repeats} — repetitions per point (default 3)</li>
 *   <li>{@code mimic.sp.wallSeconds} — target wall-clock per paced point (default 5)</li>
 *   <li>{@code mimic.sp.dpBudget} — hard cap on datapoints published per run (default 150 000)</li>
 *   <li>{@code mimic.sp.speeds} — comma-separated speed multipliers for E1</li>
 *   <li>{@code mimic.sp.csv} — output file name under {@code target/benchmark}</li>
 * </ul>
 */
class SocketPairThroughputBenchmark {

  private static final String RECORD_PROP = "mimic.record";
  private static final String DEVICE = "PAIR-DEV";
  private static final String CLASS_NAME = "Waveform";
  private static final double MAX_RATE = 100_000.0;

  /**
   * How long the sink must be idle before a run is considered drained. Raising it distinguishes
   * genuine transport loss from in-flight messages discarded when the buses are shut down.
   */
  private static final long QUIET_SECONDS = Long.getLong("mimic.sp.quietSeconds", 2L);

  private static final Path CSV = Path.of("target", "benchmark",
          System.getProperty("mimic.sp.csv", "socket-pair.csv"));

  /** How the producing instance is connected to the reading instance. */
  private enum Wiring {
    /** Both services on one in-process bus: the baseline the remote legs are compared against. */
    LOCAL,
    /** Two instances, {@link NioTcpTransport} over loopback. */
    TCP,
    /** Two instances, {@link NioUdpTransport} over loopback. */
    UDP
  }

  private record Point(String experiment, String label, Wiring wiring, double speed,
                       double recordSeconds, boolean prime) {
    boolean flatOut() {
      return speed >= MAX_RATE;
    }
  }

  private record Outcome(Point point, int repeat, boolean ok, String failure,
                         long published, long received, long missing, double deliveryRatio,
                         double producerDpPerSec, double sinkDpPerSec, double offeredDpPerSec,
                         long sendFailures, long latP50, long latP95, long latP99, long latMax,
                         double samplesPerFrame, long harnessWallMs, long peakHeapMb,
                         int peakThreads) {}

  @Test
  void comparesInProcessAgainstTwoInstancesOverTcpAndUdp() throws Exception {
    String configured = System.getProperty(RECORD_PROP);
    assumeTrue(configured != null && !configured.isBlank(),
            "set -D%s=/path/to/record.hea to run the benchmark".formatted(RECORD_PROP));
    Path record = Path.of(configured);
    assumeTrue(Files.isRegularFile(record), "record header not found: " + record);

    Set<String> wanted = Set.of(System.getProperty("mimic.sp.experiments", "e1,e2,e3")
            .toLowerCase().split("\\s*,\\s*"));
    int repeats = Integer.getInteger("mimic.sp.repeats", 3);
    double wallSeconds = Double.parseDouble(System.getProperty("mimic.sp.wallSeconds", "5"));
    long dpBudget = Long.getLong("mimic.sp.dpBudget", 150_000L);

    WfdbHeader master = WfdbHeader.parse(record);
    double fs = master.samplingFrequency();
    List<String> channels = channelIdsOf(record, master);
    System.out.printf("record %s: %.0f Hz, channels %s%n", master.recordName(), fs, channels);

    List<Point> points = new ArrayList<>();

    if (wanted.contains("e1")) {
      for (double speed : parseSpeeds(System.getProperty("mimic.sp.speeds", "1,5,20,50,100,200,500"))) {
        for (Wiring w : Wiring.values()) {
          points.add(new Point("E1", "%s@%.0fx".formatted(w, speed), w, speed,
                  sizeFor(speed, wallSeconds, dpBudget, fs), true));
        }
      }
    }
    if (wanted.contains("e2")) {
      for (Wiring w : Wiring.values()) {
        points.add(new Point("E2", "%s@flat-out".formatted(w), w, MAX_RATE,
                sizeFor(MAX_RATE, wallSeconds, dpBudget, fs), true));
      }
    }
    if (wanted.contains("e3")) {
      // No priming: measures what a real deployment loses while the remote sink is still binding.
      for (Wiring w : Wiring.values()) {
        points.add(new Point("E3", "%s@1x-unprimed".formatted(w), w, 1.0,
                sizeFor(1.0, wallSeconds, dpBudget, fs), false));
      }
    }

    assumeTrue(!points.isEmpty(), "no experiments selected");

    startCsv();
    System.out.println("warm-up...");
    runOnce(record, new Point("warmup", "warmup", Wiring.LOCAL, MAX_RATE, 60, true), 0, fs, channels);

    List<Outcome> outcomes = new ArrayList<>();
    for (Point p : points) {
      for (int r = 1; r <= repeats; r++) {
        System.out.printf("  %s %-22s repeat %d/%d ... ", p.experiment(), p.label(), r, repeats);
        System.out.flush();
        Outcome o = runOnce(record, p, r, fs, channels);
        outcomes.add(o);
        appendCsv(o);
        System.out.println(o.ok()
                ? "%,.0f dp/s delivered, %.4f delivered/published, %d send failures"
                        .formatted(o.sinkDpPerSec(), o.deliveryRatio(), o.sendFailures())
                : "FAILED: " + o.failure());
        System.gc();
        Thread.sleep(400);
      }
    }
    report(outcomes);
  }

  /** Record seconds that keep a paced point near the wall-clock target without blowing the budget. */
  private double sizeFor(double speed, double wallSeconds, long dpBudget, double fs) {
    double byWall = speed >= MAX_RATE ? Double.MAX_VALUE : speed * wallSeconds;
    double byBudget = dpBudget / 3.0 / fs;   // ~3 datapoints per frame on this record
    return Math.min(byWall, byBudget);
  }

  // ---------------------------------------------------------------------------------------------

  private Outcome runOnce(Path record, Point p, int repeat, double fs, List<String> channels)
          throws Exception {
    EventBus producerBus = null;
    EventBus readerBus = null;
    CountingDispatcher sink = null;
    SendFailureCounter failures = new SendFailureCounter();
    ResourceSampler sampler = new ResourceSampler();

    try {
      if (p.wiring() == Wiring.LOCAL) {
        producerBus = new LocalEventBus(DispatchMode.PER_HANDLER);
        readerBus = producerBus;
      } else {
        Bound reader = boundTransport(p.wiring());
        readerBus = new SocketEventBus(reader.transport(), DispatchMode.PER_HANDLER);

        SocketEventBus producer = new SocketEventBus(
                boundTransport(p.wiring()).transport(), DispatchMode.PER_HANDLER);
        producer.addPeer(new Peer("127.0.0.1", reader.port()));
        producerBus = producer;
      }

      sink = new CountingDispatcher(readerBus, new JSONArray(List.of(DEVICE)));
      if (p.prime()) {
        primeAcrossWire(producerBus, sink, channels, p.wiring());
      }

      CountDownLatch done = new CountDownLatch(1);
      long t0 = System.currentTimeMillis();
      MimicReplayProtocol replay = new MimicReplayProtocol("pair", producerBus, record.toString(),
              DEVICE, CLASS_NAME, new JSONArray(), p.speed(), p.recordSeconds()) {
        @Override protected long startupDelayMillis() { return 0; }
        @Override protected void onReplayComplete() { done.countDown(); }
      };

      long budget = p.flatOut() ? 900L : (long) (p.recordSeconds() / p.speed()) + 300L;
      if (!done.await(budget, TimeUnit.SECONDS)) {
        return failed(p, repeat, "producer did not finish within %d s".formatted(budget),
                sampler, failures);
      }
      // The wire adds a tail the in-process path does not have: in-flight datagrams and connections
      // still land after the producer stops. Wait for the sink to go quiet rather than sampling it
      // the moment the producer finishes.
      sink.awaitQuiescence(Duration.ofSeconds(QUIET_SECONDS), Duration.ofMinutes(10));
      long t1 = System.currentTimeMillis();

      MimicReplayProtocol.PacingStats pacing = replay.pacingStats();
      CountingDispatcher.Snapshot snap = sink.snapshot();
      long published = pacing.samples();
      long received = snap.received();
      double samplesPerFrame = pacing.frames() == 0 ? 0 : (double) published / pacing.frames();

      return new Outcome(p, repeat, true, "",
              published, received, published - received,
              published == 0 ? 0 : (double) received / published,
              pacing.wallElapsedMillis() == 0 ? 0 : published * 1000.0 / pacing.wallElapsedMillis(),
              snap.achievedHz(),
              p.flatOut() ? Double.NaN : samplesPerFrame * fs * p.speed(),
              failures.count(), snap.p50LatencyMillis(), snap.p95LatencyMillis(),
              snap.p99LatencyMillis(), snap.maxLatencyMillis(),
              samplesPerFrame, Math.max(1, t1 - t0),
              sampler.peakHeapMb(), sampler.peakThreads());

    } catch (Throwable t) {
      return failed(p, repeat, t.getClass().getSimpleName() + ": " + t.getMessage(), sampler, failures);
    } finally {
      sampler.close();
      failures.close();
      if (sink != null) {
        try { sink.shutdown(Duration.ofSeconds(5)); } catch (RuntimeException ignored) { /* teardown */ }
      }
      if (readerBus != null) readerBus.shutdown();
      if (producerBus != null && producerBus != readerBus) producerBus.shutdown();
      // Since NioTcpTransport pools its connections, a TCP run leaves one socket in TIME_WAIT
      // rather than one per datapoint, so the long port-space recovery it used to need is gone.
      Thread.sleep(300);
    }
  }

  private Outcome failed(Point p, int repeat, String why, ResourceSampler s, SendFailureCounter f) {
    return new Outcome(p, repeat, false, why, 0, 0, 0, 0, 0, 0, Double.NaN, f.count(),
            0, 0, 0, 0, 0, 0, s.peakHeapMb(), s.peakThreads());
  }

  /** A started transport together with the port it actually bound. */
  private record Bound(Transport transport, int port) {}

  /**
   * Binds a transport on a free port, retrying the bind itself. Probing a port and then binding it
   * is racy, and probing with the wrong protocol — a TCP probe for a UDP bind — proves nothing.
   */
  private Bound boundTransport(Wiring wiring) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < 25; attempt++) {
      int port;
      if (wiring == Wiring.TCP) {
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
      } else {
        try (DatagramSocket probe = new DatagramSocket(0)) { port = probe.getLocalPort(); }
      }
      try {
        return new Bound(wiring == Wiring.TCP ? new NioTcpTransport(port)
                : new NioUdpTransport(port), port);
      } catch (BindException raced) {
        last = raced;
      }
    }
    throw last;
  }

  /**
   * Drives the discovery handshake to completion before measuring, so a primed point measures
   * steady-state transport loss rather than the binding race. Across the wire this needs retrying:
   * the announcement itself can be dropped.
   */
  private void primeAcrossWire(EventBus producerBus, CountingDispatcher sink,
                               List<String> channels, Wiring wiring) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 60_000L;
    while (System.currentTimeMillis() < deadline) {
      for (String channel : channels) {
        producerBus.publish(Service.addressRegistry(DEVICE), addressOf(channel));
      }
      Thread.sleep(wiring == Wiring.LOCAL ? 5 : 60);
      for (String channel : channels) {
        JSONObject sample = new JSONObject();
        sample.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(Timer.formatter));
        sample.put("channelID", channel);
        sample.put("value", 0.0);
        sample.put("className", CLASS_NAME);
        producerBus.publish(addressOf(channel), sample);
      }
      Thread.sleep(wiring == Wiring.LOCAL ? 10 : 120);

      if (sink.snapshot().perChannel().keySet().containsAll(channels)) {
        sink.awaitQuiescence(Duration.ofMillis(300), Duration.ofSeconds(30));
        sink.reset();
        return;
      }
    }
    throw new IllegalStateException("reader never bound every channel across the wire within 60 s");
  }

  private String addressOf(String channel) {
    return "%s.%s.%s.parsed".formatted(CLASS_NAME, DEVICE, channel);
  }

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

  private static double[] parseSpeeds(String csv) {
    String[] parts = csv.split("\\s*,\\s*");
    double[] out = new double[parts.length];
    for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i]);
    return out;
  }

  // ---------------------------------------------------------------------------------------------

  private static final String CSV_HEADER = """
          experiment,label,repeat,ok,failure,wiring,speed,recordSeconds,primed,samplesPerFrame,\
          published,received,missing,deliveryRatio,offeredDpPerSec,producerDpPerSec,sinkDpPerSec,\
          sendFailures,latP50Ms,latP95Ms,latP99Ms,latMaxMs,harnessWallMs,peakHeapMb,peakThreads
          """;

  private void startCsv() throws IOException {
    Files.createDirectories(CSV.getParent());
    Files.writeString(CSV, CSV_HEADER);
  }

  private void appendCsv(Outcome o) throws IOException {
    Point p = o.point();
    // Locale.ROOT, not the default: a decimal comma would split every float across two columns.
    String line = String.format(Locale.ROOT,
            "%s,%s,%d,%b,%s,%s,%.0f,%.1f,%b,%.4f,%d,%d,%d,%.6f,%s,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d%n",
            p.experiment(), p.label(), o.repeat(), o.ok(), o.failure().replace(',', ';'),
                    p.wiring(), p.speed(), p.recordSeconds(), p.prime(), o.samplesPerFrame(),
                    o.published(), o.received(), o.missing(), o.deliveryRatio(),
                    Double.isNaN(o.offeredDpPerSec()) ? ""
                            : String.format(Locale.ROOT, "%.1f", o.offeredDpPerSec()),
                    o.producerDpPerSec(), o.sinkDpPerSec(), o.sendFailures(),
                    o.latP50(), o.latP95(), o.latP99(), o.latMax(),
                    o.harnessWallMs(), o.peakHeapMb(), o.peakThreads());
    Files.writeString(CSV, line, java.nio.file.StandardOpenOption.APPEND);
  }

  private void report(List<Outcome> outcomes) {
    System.out.printf("%n=== socket-pair benchmark (%s) ===%n", CSV.toAbsolutePath());
    System.out.printf("%-24s %10s %12s %12s %10s %9s %8s %8s%n",
            "point", "offered", "producer", "delivered", "delivered", "send", "latP95", "latP99");
    System.out.printf("%-24s %10s %12s %12s %10s %9s %8s %8s%n",
            "", "dp/s", "dp/s", "dp/s", "/published", "failures", "ms", "ms");
    for (Outcome o : outcomes) {
      if (!o.ok()) {
        System.out.printf("%-24s  FAILED: %s%n", o.point().label(), o.failure());
        continue;
      }
      System.out.printf("%-24s %10s %12.0f %12.0f %10.4f %9d %8d %8d%n",
              o.point().label(),
              Double.isNaN(o.offeredDpPerSec()) ? "flat-out" : "%.0f".formatted(o.offeredDpPerSec()),
              o.producerDpPerSec(), o.sinkDpPerSec(), o.deliveryRatio(), o.sendFailures(),
              o.latP95(), o.latP99());
    }
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Counts transport send failures, which the transports report by logging a warning and dropping
   * the message. Without this the loss would be invisible in the figures.
   */
  private static final class SendFailureCounter implements AutoCloseable {

    private final AtomicLong count = new AtomicLong();
    private final List<Logger> watched = new ArrayList<>();
    private final Handler handler = new Handler() {
      @Override public void publish(LogRecord r) {
        String m = r.getMessage();
        if (m != null && (m.contains("send failed") || m.contains("Error reading"))) {
          count.incrementAndGet();
        }
      }
      @Override public void flush() { }
      @Override public void close() { }
    };

    SendFailureCounter() {
      for (String name : List.of(NioTcpTransport.class.getName(), NioUdpTransport.class.getName())) {
        Logger logger = Logger.getLogger(name);
        logger.addHandler(handler);
        watched.add(logger);
      }
    }

    long count() {
      return count.get();
    }

    @Override
    public void close() {
      watched.forEach(l -> l.removeHandler(handler));
    }
  }

  /** Peak heap and live thread count over a run; see MimicThroughputBenchmark for the caveats. */
  private static final class ResourceSampler implements AutoCloseable {

    private final Thread thread;
    private volatile boolean running = true;
    private volatile long peakHeapBytes;
    private volatile int peakThreads;

    ResourceSampler() {
      thread = new Thread(() -> {
        while (running) {
          peakHeapBytes = Math.max(peakHeapBytes,
                  ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
          peakThreads = Math.max(peakThreads, ManagementFactory.getThreadMXBean().getThreadCount());
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            return;
          }
        }
      }, "pair-sampler");
      thread.setDaemon(true);
      thread.start();
    }

    long peakHeapMb() { return peakHeapBytes / (1024 * 1024); }

    int peakThreads() { return peakThreads; }

    @Override public void close() { running = false; thread.interrupt(); }
  }
}
