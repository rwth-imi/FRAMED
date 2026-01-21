package com.framed.cdss;

/**
 * Enumerates the types of channel conditions supported by this actor:
 * <ul>
 *   <li>{@link #ANY}: at least one new message</li>
 *   <li>{@link #AT_LEAST}: at least {@code n} new messages</li>
 *   <li>{@link #REQUIRE_VALUE}: at least one new message and latest value equals {@code value}</li>
 * </ul>
 */
enum RuleType { ANY, AT_LEAST, REQUIRE_VALUE }


