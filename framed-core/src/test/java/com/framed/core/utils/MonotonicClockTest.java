// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MonotonicClockTest {

  @Test
  void neverGoesBackwards() {
    Instant previous = MonotonicClock.now();
    for (int i = 0; i < 100_000; i++) {
      Instant now = MonotonicClock.now();
      assertFalse(now.isBefore(previous), "clock went backwards at iteration " + i);
      previous = now;
    }
  }

  @Test
  void staysCloseToTheWallClock() {
    Duration drift = Duration.between(Instant.now(), MonotonicClock.now()).abs();
    assertTrue(drift.compareTo(Duration.ofMillis(50)) < 0, "drift " + drift);
  }

  @Test
  void convertsToEpochNanos() {
    Instant t = Instant.parse("2026-01-01T12:00:00.123456789Z");
    assertEquals(t.getEpochSecond() * 1_000_000_000L + 123_456_789L, MonotonicClock.toEpochNanos(t));
    assertEquals(0L, MonotonicClock.toEpochNanos(Instant.EPOCH));
    assertEquals(Long.MIN_VALUE, MonotonicClock.toEpochNanos(null));
  }
}
