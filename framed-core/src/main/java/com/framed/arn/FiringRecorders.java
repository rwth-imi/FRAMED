// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the process-wide {@link FiringRecorder} that reactors pick up at construction.
 *
 * <p>By default no timing is recorded. Setting the system property {@value #DIR_PROPERTY} enables
 * a {@link CsvFiringRecorder} that writes into that directory. The recorder is created on the
 * first call to {@link #current()}, and a shutdown hook flushes it when the JVM exits.</p>
 *
 * <ul>
 *   <li>{@value #DIR_PROPERTY}: output directory (enables recording).</li>
 *   <li>{@value #RUN_PROPERTY}: label prefixed to the output files and written into every row;
 *       defaults to {@code run-<epoch millis>}.</li>
 *   <li>{@value #CAPACITY_PROPERTY}: capacity of the writer queue (default
 *       {@value #DEFAULT_CAPACITY}). A full queue drops records and counts them.</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #current()} and {@link #install(FiringRecorder)} are synchronized.
 * A reactor reads the recorder once, in its constructor, so {@link #install(FiringRecorder)} only
 * affects reactors constructed afterwards.</p>
 */
public final class FiringRecorders {

  /** System property naming the output directory; enables recording when set. */
  public static final String DIR_PROPERTY = "framed.arn.metrics.dir";

  /** System property naming the run label. */
  public static final String RUN_PROPERTY = "framed.arn.metrics.run";

  /** System property overriding the writer queue capacity. */
  public static final String CAPACITY_PROPERTY = "framed.arn.metrics.queueCapacity";

  /** Default writer queue capacity. */
  public static final int DEFAULT_CAPACITY = 200_000;

  /** A recorder that records nothing. */
  public static final FiringRecorder NOOP = new FiringRecorder() {
    @Override public boolean enabled() { return false; }
    @Override public void record(FiringRecord firing) { /* disabled */ }
    @Override public void countNoRule(String reactorId) { /* disabled */ }
  };

  private static final Logger LOGGER = Logger.getLogger(FiringRecorders.class.getName());

  private static FiringRecorder current;

  private FiringRecorders() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Returns the process-wide recorder, creating it from the system properties on first use.
   *
   * @return the installed recorder; {@link #NOOP} unless recording was enabled
   */
  public static synchronized FiringRecorder current() {
    if (current == null) {
      current = fromSystemProperties();
    }
    return current;
  }

  /**
   * Replaces the process-wide recorder, for embedding and tests. The caller owns the replaced
   * recorder and is responsible for closing it.
   *
   * @param recorder the recorder for reactors constructed from now on; {@code null} restores
   *                 {@link #NOOP}
   * @return the previously installed recorder, or {@code null} if none had been created yet
   */
  public static synchronized FiringRecorder install(FiringRecorder recorder) {
    FiringRecorder previous = current;
    current = recorder == null ? NOOP : recorder;
    return previous;
  }

  private static FiringRecorder fromSystemProperties() {
    String dir = System.getProperty(DIR_PROPERTY);
    if (dir == null || dir.isBlank()) {
      return NOOP;
    }
    String run = System.getProperty(RUN_PROPERTY, "run-%d".formatted(System.currentTimeMillis()));
    int capacity = Integer.getInteger(CAPACITY_PROPERTY, DEFAULT_CAPACITY);
    try {
      CsvFiringRecorder recorder = new CsvFiringRecorder(Path.of(dir), run, capacity);
      Runtime.getRuntime().addShutdownHook(
              new Thread(recorder::close, "firing-recorder-shutdown"));
      LOGGER.info("Recording reactor firings to %s (run '%s')".formatted(dir, run));
      return recorder;
    } catch (IOException | UncheckedIOException e) {
      LOGGER.log(Level.SEVERE, "Cannot open firing recorder in %s; recording disabled".formatted(dir), e);
      return NOOP;
    }
  }
}
