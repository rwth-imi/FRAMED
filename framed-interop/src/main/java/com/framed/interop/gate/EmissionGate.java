package com.framed.interop.gate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-channel outbound emission throttle. Keeps interoperability traffic at clinical cadence so the
 * raw high-frequency stream never reaches a downstream system.
 *
 * <p>Two independent filters, both applied when enabled; a value is emitted only if it passes both:</p>
 * <ul>
 *   <li><b>minIntervalMs</b> — at most one emission per channel per interval;</li>
 *   <li><b>onChange</b> — suppress repeats of the last emitted value for a channel.</li>
 * </ul>
 *
 * <p>With both filters disabled the gate is a pass-through. The clock is supplied by the caller so
 * behaviour is deterministic in tests.</p>
 */
public final class EmissionGate {

  private final boolean onChange;
  private final long minIntervalMs;

  private final ConcurrentHashMap<String, AtomicLong> lastEmitMs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> lastValue = new ConcurrentHashMap<>();

  /**
   * @param onChange      if true, suppress consecutive equal values per channel
   * @param minIntervalMs minimum milliseconds between emissions per channel (0 disables the filter)
   */
  public EmissionGate(boolean onChange, long minIntervalMs) {
    this.onChange = onChange;
    this.minIntervalMs = Math.max(0, minIntervalMs);
  }

  /** A pass-through gate that never suppresses. */
  public static EmissionGate passthrough() {
    return new EmissionGate(false, 0);
  }

  /**
   * Decides whether {@code value} on {@code channel} should be emitted now, recording state when it is.
   *
   * @param channel the channel id
   * @param value   the latest value
   * @param nowMs   the current time in epoch millis
   * @return {@code true} if the value should be emitted
   */
  public boolean allow(String channel, Object value, long nowMs) {
    if (minIntervalMs > 0) {
      AtomicLong last = lastEmitMs.get(channel);
      if (last != null && nowMs - last.get() < minIntervalMs) {
        return false;
      }
    }
    if (onChange) {
      Object prev = lastValue.get(channel);
      if (prev != null && prev.equals(value)) {
        return false;
      }
    }
    lastEmitMs.computeIfAbsent(channel, k -> new AtomicLong()).set(nowMs);
    if (value != null) {
      lastValue.put(channel, value);
    }
    return true;
  }
}
