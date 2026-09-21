package com.framed.streamer.dispatcher;

/**
 * Fixed-size, allocation-free histogram of non-negative millisecond durations.
 *
 * <p>Buckets are exact below one second (1 ms per bucket) and geometric above it (each bucket 10 %
 * wider than the last, covering up to ~2.4 hours), so a multi-million-sample run costs a constant
 * ~9 kB while keeping millisecond-scale percentiles precise. Values below zero are clamped to zero;
 * values beyond the top bucket saturate it. Reported percentiles are the <em>lower bound</em> of the
 * containing bucket and are therefore conservative; {@link #mean()} and {@link #max()} are exact.
 *
 * <p><b>Threading:</b> not synchronized — callers must provide mutual exclusion (see
 * {@code CountingDispatcher}, which updates it under a private lock).
 *
 * <p><b>Note:</b> deliberately duplicated in {@code framed-communicator}. Leaf modules must not
 * depend on one another and this measurement helper does not warrant a slot in the exported
 * {@code framed-core} SDK, so the two copies are kept identical rather than shared.
 */
final class MillisHistogram {

  /** Number of exact 1 ms buckets, covering 0..999 ms. */
  private static final int LINEAR_BUCKETS = 1_000;
  /** Number of geometric buckets above 1 s. */
  private static final int LOG_BUCKETS = 96;
  /** Growth factor of the geometric buckets. */
  private static final double LOG_BASE = 1.1;
  private static final double LN_BASE = Math.log(LOG_BASE);

  private final long[] buckets = new long[LINEAR_BUCKETS + LOG_BUCKETS];
  private long count;
  private long sum;
  private long max = 0;

  /**
   * Records one observation.
   *
   * @param millis the duration in milliseconds; negative values are treated as zero
   */
  void add(long millis) {
    long v = Math.max(0L, millis);
    count++;
    sum += v;
    if (v > max) max = v;
    buckets[indexOf(v)]++;
  }

  /** @return the number of recorded observations */
  long count() {
    return count;
  }

  /** Discards every observation, returning the histogram to its freshly-constructed state. */
  void reset() {
    java.util.Arrays.fill(buckets, 0L);
    count = 0;
    sum = 0;
    max = 0;
  }

  /** @return the exact arithmetic mean in milliseconds, or {@code 0} if nothing was recorded */
  double mean() {
    return count == 0 ? 0.0 : (double) sum / count;
  }

  /** @return the exact largest observation in milliseconds, or {@code 0} if nothing was recorded */
  long max() {
    return max;
  }

  /**
   * Returns the requested percentile as the lower bound of the bucket containing it.
   *
   * @param p the percentile in {@code [0, 1]}, e.g. {@code 0.95}
   * @return the percentile in milliseconds, or {@code 0} if nothing was recorded
   */
  long percentile(double p) {
    if (count == 0) return 0L;
    long rank = Math.max(1L, (long) Math.ceil(p * count));
    long seen = 0;
    for (int i = 0; i < buckets.length; i++) {
      seen += buckets[i];
      if (seen >= rank) return lowerBoundOf(i);
    }
    return max;
  }

  private static int indexOf(long millis) {
    if (millis < LINEAR_BUCKETS) return (int) millis;
    int offset = (int) (Math.log(millis / (double) LINEAR_BUCKETS) / LN_BASE);
    return LINEAR_BUCKETS + Math.min(LOG_BUCKETS - 1, offset);
  }

  private static long lowerBoundOf(int index) {
    if (index < LINEAR_BUCKETS) return index;
    return (long) (LINEAR_BUCKETS * Math.pow(LOG_BASE, index - LINEAR_BUCKETS));
  }
}