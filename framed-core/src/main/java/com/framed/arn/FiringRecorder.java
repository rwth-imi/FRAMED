// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

/**
 * Receives the timing of reactor evaluations without touching the event bus.
 *
 * <p>The timing is recorded off the bus so that a measurement does not add load to the system it
 * measures. A {@link Reactor} obtains its recorder from {@link FiringRecorders#current()} when it
 * is constructed.</p>
 *
 * <p><b>Threading:</b> implementations are called concurrently from every reactor's handler
 * threads, partly while the reactor holds its evaluation lock. Every method must be thread-safe,
 * must not block, and must not throw.</p>
 */
public interface FiringRecorder {

  /**
   * Tells reactors whether to collect timing at all. When {@code false}, a reactor skips
   * clock readings and record construction, so a disabled recorder costs nearly nothing.
   *
   * @return whether {@link #record(FiringRecord)} and {@link #countNoRule(String)} do anything
   */
  boolean enabled();

  /**
   * Records one handled message: an evaluation that satisfied at least one rule and fired, or
   * a message discarded by the logical-time gate.
   *
   * @param firing the evaluation's timing
   */
  void record(FiringRecord firing);

  /**
   * Counts an accepted message for which no rule was satisfied. Such evaluations get no
   * {@link FiringRecord}; only their number per reactor is kept.
   *
   * @param reactorId id of the evaluating reactor
   */
  void countNoRule(String reactorId);

  /**
   * Flushes and releases resources. Called at most once. Later calls to the other methods
   * may be ignored.
   */
  default void close() {
  }
}
