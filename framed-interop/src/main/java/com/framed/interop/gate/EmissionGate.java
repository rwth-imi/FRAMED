package com.framed.interop.gate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key outbound emission throttle. Keeps interoperability traffic at clinical cadence so the
 * raw high-frequency stream never reaches a downstream system.
 *
 * <p>State is held per <em>key</em>. The key must identify one logical signal: callers that serve
 * multiple devices through a single gate instance must include the device id in the key (e.g.
 * {@code "<deviceID>.<channelID>"}), otherwise identically named channels on different devices
 * share one throttle slot and suppress each other.</p>
 *
 * <p>Two independent filters, both applied when enabled; a value is emitted only if it passes both:</p>
 * <ul>
 *   <li><b>minIntervalMs</b> — at most one emission per key per interval;</li>
 *   <li><b>onChange</b> — suppress repeats of the last emitted value for a key.</li>
 * </ul>
 *
 * <p>Checking and committing are separate steps: {@link #allows} is a side-effect-free check and
 * {@link #commit} records a completed emission. Callers performing fallible I/O must commit only
 * <em>after</em> the emission succeeded — committing first would make the gate suppress the retry
 * of a failed send and silently lose the value. {@link #allow} combines both for callers whose
 * emission cannot fail after the check. The check-then-commit pair is not atomic; concurrent
 * callers may occasionally both pass the check, which for a throttle is an acceptable extra
 * emission (never a loss).</p>
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
   * @param onChange      if true, suppress consecutive equal values per key
   * @param minIntervalMs minimum milliseconds between emissions per key (0 disables the filter)
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
   * Checks whether {@code value} on {@code key} may be emitted now, without recording anything.
   *
   * @param key   the emission key (device-scoped, see class doc)
   * @param value the latest value
   * @param nowMs the current time in epoch millis
   * @return {@code true} if the value passes both filters
   */
  public boolean allows(String key, Object value, long nowMs) {
    if (minIntervalMs > 0) {
      AtomicLong last = lastEmitMs.get(key);
      if (last != null && nowMs - last.get() < minIntervalMs) {
        return false;
      }
    }
    if (onChange) {
      Object prev = lastValue.get(key);
      if (prev != null && prev.equals(value)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Records a completed emission of {@code value} on {@code key}. Call only after the emission
   * actually succeeded, so a failed send stays eligible for retry.
   *
   * @param key   the emission key
   * @param value the emitted value
   * @param nowMs the emission time in epoch millis
   */
  public void commit(String key, Object value, long nowMs) {
    lastEmitMs.computeIfAbsent(key, k -> new AtomicLong()).set(nowMs);
    if (value != null) {
      lastValue.put(key, value);
    }
  }

  /**
   * Convenience for infallible emissions: {@link #allows checks} and, when allowed, immediately
   * {@link #commit commits}.
   *
   * @param key   the emission key
   * @param value the latest value
   * @param nowMs the current time in epoch millis
   * @return {@code true} if the value should be emitted
   */
  public boolean allow(String key, Object value, long nowMs) {
    if (!allows(key, value, nowMs)) {
      return false;
    }
    commit(key, value, nowMs);
    return true;
  }
}
