package com.framed.communicator.driver.protocol.mimic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the accuracy contract the pacing and latency summaries rely on: exact mean and max, exact
 * percentiles below one second, and conservative (bucket lower bound) percentiles above it.
 */
class MillisHistogramTest {

  @Test
  void reportsZeroesWhenEmpty() {
    MillisHistogram h = new MillisHistogram();
    assertEquals(0, h.count());
    assertEquals(0.0, h.mean());
    assertEquals(0, h.max());
    assertEquals(0, h.percentile(0.95));
  }

  @Test
  void isExactBelowOneSecond() {
    MillisHistogram h = new MillisHistogram();
    for (int i = 1; i <= 100; i++) h.add(i);

    assertEquals(100, h.count());
    assertEquals(50.5, h.mean(), 1e-9);
    assertEquals(100, h.max());
    assertEquals(50, h.percentile(0.50));
    assertEquals(95, h.percentile(0.95));
    assertEquals(100, h.percentile(1.0));
  }

  @Test
  void clampsNegativeValuesToZero() {
    MillisHistogram h = new MillisHistogram();
    h.add(-5);
    h.add(-1);

    assertEquals(2, h.count());
    assertEquals(0.0, h.mean());
    assertEquals(0, h.max());
    assertEquals(0, h.percentile(0.99));
  }

  @Test
  void keepsMeanAndMaxExactAboveOneSecondWhilePercentilesStayConservative() {
    MillisHistogram h = new MillisHistogram();
    h.add(5_000);

    assertEquals(5_000.0, h.mean(), 1e-9, "mean is accumulated exactly, not from buckets");
    assertEquals(5_000, h.max(), "max is tracked exactly, not from buckets");

    long p50 = h.percentile(0.50);
    assertTrue(p50 <= 5_000, "percentile must be the bucket lower bound, was " + p50);
    assertTrue(p50 >= 5_000 / 1.1, "bucket resolution above 1 s must stay within 10 %, was " + p50);
  }

  @Test
  void resetReturnsItToTheEmptyState() {
    MillisHistogram h = new MillisHistogram();
    for (int i = 1; i <= 100; i++) h.add(i);

    h.reset();

    assertEquals(0, h.count());
    assertEquals(0.0, h.mean());
    assertEquals(0, h.max());
    assertEquals(0, h.percentile(0.95));

    h.add(7);
    assertEquals(1, h.count());
    assertEquals(7, h.max());
    assertEquals(7, h.percentile(0.50), "old buckets must not skew the new distribution");
  }

  @Test
  void saturatesRatherThanOverflowingOnAbsurdValues() {
    MillisHistogram h = new MillisHistogram();
    h.add(Long.MAX_VALUE / 4);

    assertEquals(1, h.count());
    assertEquals(Long.MAX_VALUE / 4, h.max());
    assertTrue(h.percentile(0.99) > 0, "saturated bucket must still yield a positive lower bound");
  }
}
