// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core.utils;

import java.time.Instant;

/**
 * A wall-clock-aligned clock that never steps: one anchor pair ({@link Instant#now()},
 * {@link System#nanoTime()}) is taken when the class is loaded, and every later reading is the
 * anchor plus the elapsed monotonic time.
 *
 * <p>Use it wherever two instants taken at different places in one JVM are subtracted to give
 * a duration, for example a replay's emit stamp and a reactor's firing time. Both readings then
 * advance at the rate of the monotonic clock, so an NTP step or a manual clock change during a run
 * cannot produce negative or inflated latencies. The returned instants stay comparable with
 * wall-clock timestamps from the same host to within the clock drift since class loading. Over a
 * run of minutes that drift is in the order of microseconds to milliseconds, so instants of this
 * clock should not be mixed with {@link Instant#now()} in one subtraction.</p>
 *
 * <p>Resolution is nanoseconds; callers that serialize through {@link Timer#formatter} must
 * truncate to microseconds themselves.</p>
 *
 * <p><b>Threading:</b> stateless after class initialisation; safe to call from any thread.</p>
 */
public final class MonotonicClock {

  /** Wall-clock instant of the anchor. */
  private static final Instant ANCHOR_WALL;

  /** {@link System#nanoTime()} reading of the anchor. */
  private static final long ANCHOR_NANOS;

  static {
    ANCHOR_NANOS = System.nanoTime();
    ANCHOR_WALL = Instant.now();
  }

  private MonotonicClock() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Returns the current instant of this clock.
   *
   * @return the anchor wall-clock instant plus the monotonic time elapsed since the anchor
   */
  public static Instant now() {
    return ANCHOR_WALL.plusNanos(System.nanoTime() - ANCHOR_NANOS);
  }

  /**
   * Converts an instant to nanoseconds since the Unix epoch.
   *
   * @param instant the instant to convert; may be {@code null}
   * @return epoch nanoseconds, or {@link Long#MIN_VALUE} for {@code null}
   */
  public static long toEpochNanos(Instant instant) {
    if (instant == null) return Long.MIN_VALUE;
    return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
            instant.getNano());
  }
}
