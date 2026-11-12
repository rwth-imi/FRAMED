// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.core.utils.MonotonicClock;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link FiringRecorder} that writes evaluation timings to CSV files on a background thread.
 *
 * <p>Three files are written into the output directory, each prefixed with the run label:</p>
 * <dl>
 *   <dt>{@code <run>_firings.csv}</dt>
 *   <dd>one row per {@link FiringRecord}: {@code run, reactor, evaluation, outcome, atomic,
 *       trigger_channel, trigger_ts_ns, trigger_m, trigger_arrival_ns, logical_ts_ns, logical_m,
 *       gate_ts_ns, gate_m, decision_ns, react_start_ns, react_end_ns, consumed_n}, where
 *       {@code logical_*} is the output tag and {@code gate_*} the channel's last accepted tag</dd>
 *   <dt>{@code <run>_inputs.csv}</dt>
 *   <dd>one row per {@link FiringRecord.ConsumedInput}: {@code run, reactor, evaluation, channel,
 *       seq, ts_ns, m, arrival_ns}</dd>
 *   <dt>{@code <run>_summary.csv}</dt>
 *   <dd>written on {@link #close()}: per reactor the counts of fired, discarded and no-rule
 *       messages, plus one {@code *dropped*} row with the number of records lost to a full
 *       queue</dd>
 * </dl>
 *
 * <p>Instants are written as nanoseconds since the Unix epoch. An absent instant or tag (a
 * discarded message has no output tag and no reaction times) is written as empty fields. String fields are quoted,
 * because channel names can contain commas.</p>
 *
 * <p><b>Threading:</b> {@link #record(FiringRecord)} only offers to a bounded queue and never
 * blocks. When the queue is full the record is dropped and counted, so a slow disk cannot stall a
 * reactor. Counts in the summary include dropped records. A single daemon thread drains the queue.
 * {@link #close()} drains what is queued, writes the summary and is idempotent.</p>
 */
public final class CsvFiringRecorder implements FiringRecorder {

  private static final Logger LOGGER = Logger.getLogger(CsvFiringRecorder.class.getName());
  private static final Object POISON = new Object();

  private final String run;
  private final Path summaryPath;
  private final BlockingQueue<Object> queue;
  private final BufferedWriter firings;
  private final BufferedWriter inputs;
  private final Thread writer;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final LongAdder dropped = new LongAdder();
  private final Map<String, LongAdder[]> counts = new ConcurrentHashMap<>();

  /** Index of the per-reactor counters in {@link #counts}. */
  private static final int FIRED = 0;
  private static final int DISCARDED = 1;
  private static final int NO_RULE = 2;

  /**
   * Opens the output files and starts the writer thread.
   *
   * @param dir      output directory, created if missing
   * @param run      run label used as file prefix and as the {@code run} column
   * @param capacity writer queue capacity; must be positive
   * @throws IOException if the directory or files cannot be created
   */
  public CsvFiringRecorder(Path dir, String run, int capacity) throws IOException {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
    this.run = run;
    Files.createDirectories(dir);
    String prefix = run.replaceAll("[^A-Za-z0-9._-]", "_");
    this.summaryPath = dir.resolve(prefix + "_summary.csv");
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.firings = Files.newBufferedWriter(dir.resolve(prefix + "_firings.csv"), StandardCharsets.UTF_8);
    this.inputs = Files.newBufferedWriter(dir.resolve(prefix + "_inputs.csv"), StandardCharsets.UTF_8);
    firings.write("run,reactor,evaluation,outcome,atomic,trigger_channel,trigger_ts_ns,trigger_m,"
            + "trigger_arrival_ns,logical_ts_ns,logical_m,gate_ts_ns,gate_m,decision_ns,"
            + "react_start_ns,react_end_ns,consumed_n\n");
    inputs.write("run,reactor,evaluation,channel,seq,ts_ns,m,arrival_ns\n");
    this.writer = new Thread(this::drain, "firing-recorder-writer");
    writer.setDaemon(true);
    writer.start();
  }

  @Override
  public boolean enabled() {
    return !closed.get();
  }

  @Override
  public void record(FiringRecord firing) {
    if (closed.get()) return;
    counters(firing.reactorId())[firing.outcome() == FiringRecord.Outcome.FIRED ? FIRED : DISCARDED]
            .increment();
    if (!queue.offer(firing)) {
      dropped.increment();
    }
  }

  @Override
  public void countNoRule(String reactorId) {
    if (closed.get()) return;
    counters(reactorId)[NO_RULE].increment();
  }

  /**
   * Returns the number of records dropped because the queue was full.
   *
   * @return dropped records so far
   */
  public long dropped() {
    return dropped.sum();
  }

  /**
   * Drains the queue, writes the summary and closes the files. Waits at most ten seconds for the
   * writer to finish; idempotent.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      // The queue may be full; wait for room rather than lose the end-of-stream marker.
      if (!queue.offer(POISON, 10, TimeUnit.SECONDS)) {
        LOGGER.warning("Firing recorder queue did not drain; closing without remaining records");
        writer.interrupt();
      }
      writer.join(TimeUnit.SECONDS.toMillis(10));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      writeSummary();
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "Cannot write firing summary %s".formatted(summaryPath), e);
    }
    if (dropped.sum() > 0) {
      LOGGER.warning("Firing recorder dropped %d records (queue full)".formatted(dropped.sum()));
    }
  }

  private LongAdder[] counters(String reactorId) {
    return counts.computeIfAbsent(reactorId,
            id -> new LongAdder[]{new LongAdder(), new LongAdder(), new LongAdder()});
  }

  private void drain() {
    try (firings; inputs) {
      while (true) {
        Object next = queue.take();
        if (next == POISON) break;
        write((FiringRecord) next);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | UncheckedIOException e) {
      LOGGER.log(Level.SEVERE, "Firing recorder failed; further records are lost", e);
      closed.set(true);
    }
  }

  private void write(FiringRecord r) throws IOException {
    String prefix = "%s,%s,%d,".formatted(quote(run), quote(r.reactorId()), r.evaluation());
    firings.write(prefix
            + r.outcome() + ',' + r.atomic() + ',' + quote(r.triggerChannel()) + ','
            + tag(r.triggerTag()) + ',' + nanos(r.triggerArrival()) + ','
            + tag(r.outputTag()) + ',' + tag(r.gateTag()) + ',' + nanos(r.decision()) + ','
            + nanos(r.reactStart()) + ',' + nanos(r.reactEnd()) + ',' + r.consumed().size() + '\n');
    for (FiringRecord.ConsumedInput in : r.consumed()) {
      inputs.write(prefix + quote(in.channel()) + ',' + in.seq() + ','
              + tag(in.tag()) + ',' + nanos(in.arrival()) + '\n');
    }
  }

  private void writeSummary() throws IOException {
    try (BufferedWriter out = Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8)) {
      out.write("run,reactor,fired,discarded,no_rule\n");
      for (var e : new TreeMap<>(counts).entrySet()) {
        LongAdder[] c = e.getValue();
        out.write("%s,%s,%d,%d,%d%n".formatted(quote(run), quote(e.getKey()),
                c[FIRED].sum(), c[DISCARDED].sum(), c[NO_RULE].sum()));
      }
      out.write("%s,%s,%d,,%n".formatted(quote(run), quote("*dropped*"), dropped.sum()));
    }
  }

  private static String nanos(java.time.Instant instant) {
    return instant == null ? "" : Long.toString(MonotonicClock.toEpochNanos(instant));
  }

  /** Two fields, {@code ts_ns,m}; both empty for a {@code null} tag. */
  private static String tag(Tag tag) {
    return tag == null ? "," : nanos(tag.t()) + ',' + tag.m();
  }

  private static String quote(String s) {
    if (s == null) return "";
    return '"' + s.replace("\"", "\"\"") + '"';
  }
}
