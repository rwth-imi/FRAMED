package com.framed.cdss;

/**
 * Structured representation of a parsed channel condition. Instances are created via factory
 * methods {@link #any()}, {@link #atLeast(long)}, and {@link #requireValue(String)}.
 * <ul>
 *   <li>For {@link RuleType#AT_LEAST}, {@link #n} holds the minimum required delta (&ge; 1).</li>
 *   <li>For {@link RuleType#REQUIRE_VALUE}, {@link #value} holds the expected latest value string.</li>
 * </ul>
 *
 * @param type  The condition category.
 * @param n     Minimum required delta for {@link RuleType#AT_LEAST}; ignored otherwise.  used when type == AT_LEAST
 * @param value Expected latest value for {@link RuleType#REQUIRE_VALUE}; ignored otherwise.  used when type == REQUIRE_VALUE
 */
record FiringRule(RuleType type, long n, String value) {
  /**
   * @return a condition requiring at least one new message ({@link RuleType#ANY})
   */
  static FiringRule any() {
    return new FiringRule(RuleType.ANY, 0, null);
  }

  /**
   * @param n minimum number of new messages required (must be &ge; 1)
   * @return a condition of type {@link RuleType#AT_LEAST} with threshold {@code n}
   */
  static FiringRule atLeast(long n) {
    return new FiringRule(RuleType.AT_LEAST, n, null);
  }

  /**
   * @param v expected latest value (string form)
   * @return a condition of type {@link RuleType#REQUIRE_VALUE} that requires a new message and latest value equals {@code v}
   */
  static FiringRule requireValue(String v) {
    return new FiringRule(RuleType.REQUIRE_VALUE, 0, v);
  }
}
