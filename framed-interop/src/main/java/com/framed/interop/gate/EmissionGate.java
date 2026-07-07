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
 * <p>Checking and committing are separate steps: {@link #allows} is a side-effect-free check;
 * the two filters' state is committed independently. Callers performing fallible I/O should
 * {@link #commitAttempt} the <em>interval</em> state when they start an emission attempt (so a
 * failing endpoint is probed at most once per interval instead of once per sample) and
 * {@link #commitValue} the <em>onChange</em> state only after the receiver accepted the value (so
 * a failed or rejected send does not suppress an identical follow-up value). {@link #commit} does
 * both at once; {@link #allow} combines check and full commit for callers whose emission cannot
 * fail after the check. The check-then-commit pair is not atomic; concurrent callers may
 * occasionally both pass the check, which for a throttle is an acceptable extra emission (never a
 * loss).</p>
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
   * The canonical device-scoped emission key (see class doc). All callers gating bus datapoints
   * must build their key through this method so identically named channels on different devices
   * never share a throttle slot.
   *
   * @param deviceID  the device id
   * @param channelID the channel id
   * @return the emission key {@code "<deviceID>.<channelID>"}
   */
  public static String keyFor(String deviceID, String channelID) {
    return deviceID + "." + channelID;
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
   * Records an emission <em>attempt</em> on {@code key}: only the interval filter's state.
   * Call when starting a fallible emission, before its outcome is known — during an endpoint
   * outage this throttles attempts to one per interval instead of one per sample, without
   * affecting the onChange filter.
   *
   * @param key   the emission key
   * @param nowMs the attempt time in epoch millis
   */
  public void commitAttempt(String key, long nowMs) {
    lastEmitMs.computeIfAbsent(key, k -> new AtomicLong()).set(nowMs);
  }

  /**
   * Records a <em>delivered</em> value on {@code key}: only the onChange filter's state.
   * Call only after the receiver accepted the value, so a failed or rejected send does not
   * suppress an identical follow-up value.
   *
   * @param key   the emission key
   * @param value the delivered value ({@code null} is ignored)
   */
  public void commitValue(String key, Object value) {
    if (value != null) {
      lastValue.put(key, value);
    }
  }

  /**
   * Records a completed emission of {@code value} on {@code key}: both filters' state at once
   * ({@link #commitAttempt} + {@link #commitValue}).
   *
   * @param key   the emission key
   * @param value the emitted value
   * @param nowMs the emission time in epoch millis
   */
  public void commit(String key, Object value, long nowMs) {
    commitAttempt(key, nowMs);
    commitValue(key, value);
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
