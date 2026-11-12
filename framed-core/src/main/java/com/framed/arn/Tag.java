// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * A superdense logical tag {@code (t, m)}: a timestamp {@code t} and a microstep {@code m}.
 *
 * <p>Tags are ordered lexicographically: {@code (t, m) < (t', m')} iff {@code t < t'}, or
 * {@code t == t'} and {@code m < m'}. Devices and parsers emit {@code m = 0}; a {@link Reactor}
 * emits {@code m >= 1} only when it fires more than once for the same timestamp, for example
 * when one source instant reaches it over several paths.</p>
 *
 * <p>On the bus a tag is carried by the message fields {@code "timestamp"} (formatted with
 * {@link com.framed.core.utils.Timer#formatter}) and {@code "microstep"} (absent means 0); see
 * {@link RuleUtils#extractTag(Object)} and {@link RuleUtils#publishResult}.</p>
 *
 * @param t timestamp; never {@code null}
 * @param m microstep; never negative
 */
public record Tag(Instant t, long m) implements Comparable<Tag> {

  /** The smallest tag, {@code (EPOCH, 0)}: the initial "last accepted" and "last output" tag. */
  public static final Tag ZERO = new Tag(Instant.EPOCH, 0);

  private static final Comparator<Tag> ORDER =
          Comparator.comparing(Tag::t).thenComparingLong(Tag::m);

  /**
   * Validates the components.
   *
   * @throws NullPointerException     if {@code t} is {@code null}
   * @throws IllegalArgumentException if {@code m} is negative
   */
  public Tag {
    Objects.requireNonNull(t, "t");
    if (m < 0) throw new IllegalArgumentException("microstep must be >= 0, was " + m);
  }

  /**
   * Returns the tag {@code (t, 0)}.
   *
   * @param t timestamp
   * @return the tag at microstep 0
   */
  public static Tag of(Instant t) {
    return new Tag(t, 0);
  }

  /**
   * Compares lexicographically by timestamp, then microstep.
   *
   * @param o the other tag
   * @return a negative, zero or positive value as this tag is smaller, equal or greater
   */
  @Override
  public int compareTo(Tag o) {
    return ORDER.compare(this, o);
  }

  /**
   * Whether this tag is strictly greater than {@code o}.
   *
   * @param o the other tag
   * @return {@code this > o}
   */
  public boolean isAfter(Tag o) {
    return compareTo(o) > 0;
  }
}
