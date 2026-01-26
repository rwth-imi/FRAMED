package com.framed.cdss.casestudy;

/**
 * Enumerates the types of channel conditions supported:
 * <ul>
 *   <li>{@link #ANY}: at least one new message</li>
 *   <li>{@link #AT_LEAST}: at least {@code n} new messages</li>
 *   <li>{@link #REQUIRE_VALUE}: at least one new message and latest value equals {@code value}</li>
 * </ul>
 */
public enum RuleType { ANY, AT_LEAST, REQUIRE_VALUE }


