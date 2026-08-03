package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.io.dispatch.DataPoint;
import com.framed.io.dispatch.Dispatcher;
import org.json.JSONArray;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A sink that performs no I/O and only counts what reaches it — the measurement instrument for
 * throughput and latency benchmarks.
 *
 * <p>Every other {@code Dispatcher} in this module writes somewhere (a file, InfluxDB), so a run
 * against them measures the sink as much as the framework. This one deliberately does nothing but
 * increment counters, isolating the cost of the acquisition path itself: event-bus dispatch,
 * {@code DataPoint} parsing and the async push queue. It answers three questions per run:</p>
 * <ul>
 *   <li><b>Keep-up</b> — how many datapoints arrived, per channel, and how many the bounded push
 *       queue had to {@linkplain #onDrop drop}.</li>
 *   <li><b>Throughput</b> — datapoints per second between the first and last push.</li>
 *   <li><b>End-to-end latency</b> — {@code now − dataPoint.timestamp()} at push time. Valid as an
 *       emit&rarr;sink latency whenever producer and sink share a clock (same host); across hosts it
 *       also carries the clock offset and should not be read as latency.</li>
 * </ul>
 *
 * <p>A summary line is printed on {@link #stop()}, so a full-app deployment emits it via the
 * launcher's shutdown hook; in-process harnesses can read the same figures from {@link #snapshot()}.</p>
 *
 * <h2>Configuration keys</h2>
 * <ul>
 *   <li>{@code devices} &mdash; JSON array of device identifiers whose announced channels to count.</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #push} runs on the dispatcher's single worker thread and
 * {@link #onDrop} may run on either that thread or an event-bus handler thread; all counter updates
 * are taken under a private lock, so {@link #snapshot()} is safe to call from any thread at any time
 * and always observes a mutually consistent set of figures.</p>
 */
public class CountingDispatcher extends Dispatcher {

  /** Guards every field below; uncontended in practice (a single writer plus rare readers). */
  private final Object lock = new Object();

  private final MillisHistogram latency = new MillisHistogram();
  private final Map<String, Long> perChannel = new LinkedHashMap<>();
  private long received;
  private long firstPushMillis = -1;
  private long lastPushMillis = -1;
  /** Last time anything at all happened (push, drop or parse failure); seeded at construction so a
   *  sink that never received counts as quiescent rather than as never-drained. */
  private long lastActivityMillis = System.currentTimeMillis();

  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong handlerErrors = new AtomicLong();

  /**
   * Creates a counting sink bound to the announced channels of the given devices. Parameter names
   * match the JSON configuration keys resolved by {@code Factory}; the {@link EventBus} is injected
   * automatically.
   *
   * @param eventBus the event bus to discover channels and receive samples on
   * @param devices  the device identifiers whose announced channels this sink binds to
   */
  public CountingDispatcher(EventBus eventBus, JSONArray devices) {
    super(eventBus, devices);
  }

  @Override
  public void push(DataPoint<?> dataPoint) {
    long now = System.currentTimeMillis();
    long lag = now - dataPoint.timestamp().toEpochMilli();
    String channel = dataPoint.channelID();

    synchronized (lock) {
      received++;
      latency.add(lag);
      perChannel.merge(channel, 1L, Long::sum);
      if (firstPushMillis < 0) firstPushMillis = now;
      lastPushMillis = now;
      lastActivityMillis = now;
    }
  }

  @Override
  public void pushBatch(List<DataPoint<?>> batch) {
    for (DataPoint<?> dp : batch) {
      push(dp);
    }
  }

  /**
   * Counts the drop instead of printing it. A benchmark can saturate the queue by design, and the
   * inherited stderr trace would then cost more than the work being measured.
   */
  @Override
  protected void onDrop(DataPoint<?> dp, Throwable cause) {
    dropped.incrementAndGet();
    synchronized (lock) {
      lastActivityMillis = System.currentTimeMillis();
    }
  }

  /**
   * Counts the failure instead of printing a stack trace, for the same reason as {@link #onDrop}.
   */
  @Override
  protected void onHandlerError(String deviceID, String address, Object rawMsg, Exception e) {
    handlerErrors.incrementAndGet();
    synchronized (lock) {
      lastActivityMillis = System.currentTimeMillis();
    }
  }

  /**
   * Returns a consistent snapshot of everything counted so far. Safe to call at any time from any
   * thread; figures are cumulative, not reset by reading.
   *
   * @return the current counts, throughput and latency distribution
   */
  public Snapshot snapshot() {
    synchronized (lock) {
      long elapsed = firstPushMillis < 0 ? 0 : lastPushMillis - firstPushMillis;
      double achievedHz = elapsed > 0 ? received * 1000.0 / elapsed : 0.0;
      return new Snapshot(received, dropped.get(), handlerErrors.get(),
              new TreeMap<>(perChannel), elapsed, achievedHz,
              latency.mean(), latency.percentile(0.50), latency.percentile(0.95),
              latency.percentile(0.99), latency.max());
    }
  }

  /**
   * Discards everything counted so far, as if the sink had just been constructed.
   *
   * <p>For harnesses that warm up or prime the pipeline before the run they actually want to
   * measure: without it, the setup traffic would show up in the counts, in the latency distribution
   * and — because it moves the start of the timing window earlier — in the throughput figure.
   * Call it while the sink is quiescent; concurrent pushes race with the clear and may survive it.</p>
   */
  public void reset() {
    synchronized (lock) {
      latency.reset();
      perChannel.clear();
      received = 0;
      firstPushMillis = -1;
      lastPushMillis = -1;
      lastActivityMillis = System.currentTimeMillis();
    }
    dropped.set(0);
    handlerErrors.set(0);
  }

  /**
   * Blocks until the sink has been idle for {@code quietPeriod}, i.e. until the push queue has
   * drained. Benchmarks need this before reading a final {@link #snapshot()}: the producer finishing
   * its last publish says nothing about the async queue behind this sink, and calling
   * {@link #shutdown(Duration)} instead would drop whatever is still queued.
   *
   * <p>Idleness is inferred from arrivals, so this reports "drained" for a producer that has merely
   * stalled longer than {@code quietPeriod}. Callers that need a true end-of-stream should observe
   * producer completion first and use this only to await the queue behind it.</p>
   *
   * @param quietPeriod how long nothing must arrive for the sink to count as drained
   * @param timeout     give up after this long
   * @return {@code true} if the sink went quiet, {@code false} if {@code timeout} elapsed first
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  public boolean awaitQuiescence(Duration quietPeriod, Duration timeout) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    long quietMillis = quietPeriod.toMillis();

    while (System.currentTimeMillis() < deadline) {
      long idleFor;
      synchronized (lock) {
        idleFor = System.currentTimeMillis() - lastActivityMillis;
      }
      if (idleFor >= quietMillis) return true;
      Thread.sleep(Math.min(20L, Math.max(1L, quietMillis / 4)));
    }
    return false;
  }

  /**
   * Prints the run summary, then stops the push worker. Invoked by the launcher's shutdown hook in a
   * full-app deployment, which is what makes the figures visible without any extra tooling.
   *
   * <p>The summary deliberately goes to {@link System#out} rather than through the inherited
   * {@code logger}: {@code java.util.logging}'s {@code LogManager} installs its own shutdown hook,
   * and once it has reset the handlers every subsequent log record is silently discarded. Since this
   * summary <em>is</em> the measurement, it cannot depend on winning that race.</p>
   */
  @Override
  public void stop() {
    System.out.println("CountingDispatcher summary: " + snapshot());
    System.out.flush();
    super.stop();
  }

  /**
   * Immutable view of one measurement run.
   *
   * @param received            datapoints handed to {@link #push}
   * @param dropped             datapoints the bounded push queue could not accept
   * @param handlerErrors       messages that failed to parse before reaching the queue
   * @param perChannel          received count per channel identifier, ordered by identifier
   * @param wallElapsedMillis   wall-clock span between the first and last push
   * @param achievedHz          {@code received} divided by {@code wallElapsedMillis}, in datapoints/s
   * @param meanLatencyMillis   mean emit&rarr;sink latency
   * @param p50LatencyMillis    median emit&rarr;sink latency
   * @param p95LatencyMillis    95th-percentile emit&rarr;sink latency
   * @param p99LatencyMillis    99th-percentile emit&rarr;sink latency
   * @param maxLatencyMillis    largest observed emit&rarr;sink latency
   */
  public record Snapshot(long received, long dropped, long handlerErrors,
                         Map<String, Long> perChannel, long wallElapsedMillis, double achievedHz,
                         double meanLatencyMillis, long p50LatencyMillis, long p95LatencyMillis,
                         long p99LatencyMillis, long maxLatencyMillis) {

    @Override
    public String toString() {
      return ("received=%d dropped=%d handlerErrors=%d channels=%d achieved=%.1f dp/s "
              + "latency(mean/p50/p95/p99/max)=%.1f/%d/%d/%d/%d ms elapsed=%d ms perChannel=%s")
              .formatted(received, dropped, handlerErrors, perChannel.size(), achievedHz,
                      meanLatencyMillis, p50LatencyMillis, p95LatencyMillis, p99LatencyMillis,
                      maxLatencyMillis, wallElapsedMillis, perChannel);
    }
  }
}