package com.framed.cdss.utils;

import com.framed.cdss.Actor;

/**
 * Structured representation of a parsed channel condition. Instances are created via factory
 * methods {@link #any()}, {@link #atLeast(long)}, and {@link #requireValue(String)}.
 * <ul>
 *   <li>For {@link ConditionType#AT_LEAST}, {@link #n} holds the minimum required delta (&ge; 1).</li>
 *   <li>For {@link ConditionType#REQUIRE_VALUE}, {@link #value} holds the expected latest value string.</li>
 * </ul>
 *
 * @param type  The condition category.
 * @param n     Minimum required delta for {@link ConditionType#AT_LEAST}; ignored otherwise.  used when type == AT_LEAST
 * @param value Expected latest value for {@link ConditionType#REQUIRE_VALUE}; ignored otherwise.  used when type == REQUIRE_VALUE
 */
public record Condition(ConditionType type, long n, String value) {
  /**
   * @return a condition requiring at least one new message ({@link ConditionType#ANY})
   */
  static Condition any() {
    return new Condition(ConditionType.ANY, 0, null);
  }

  /**
   * @param n minimum number of new messages required (must be &ge; 1)
   * @return a condition of type {@link ConditionType#AT_LEAST} with threshold {@code n}
   */
  static Condition atLeast(long n) {
    return new Condition(ConditionType.AT_LEAST, n, null);
  }

  /**
   * @param v expected latest value (string form)
   * @return a condition of type {@link ConditionType#REQUIRE_VALUE} that requires a new message and latest value equals {@code v}
   */
  static Condition requireValue(String v) {
    return new Condition(ConditionType.REQUIRE_VALUE, 0, v);
  }
}
