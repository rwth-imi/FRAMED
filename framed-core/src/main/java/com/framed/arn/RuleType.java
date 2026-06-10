package com.framed.arn;

/**
 * Enumerates the types of channel conditions supported:
 * <ul>
 *   <li>{@link #ANY}: at least one new message</li>
 *   <li>{@link #AT_LEAST}: at least {@code n} new messages</li>
 *   <li>{@link #REQUIRE_VALUE}: at least one new message and latest value equals {@code value}</li>
 * </ul>
 */
public enum RuleType {
  /** Satisfied when at least one new message has arrived on the channel. */
  ANY,
  /** Satisfied when at least {@code n} new messages have arrived on the channel. */
  AT_LEAST,
  /** Satisfied when a new message has arrived and the latest value equals the configured value. */
  REQUIRE_VALUE
}


