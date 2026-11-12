// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.core.EventBus;
import com.framed.core.Service;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.framed.core.utils.Timer.formatter;

/**
 * Runtime helpers shared by {@link Reactor} and its concrete implementations:
 * extracting a logical tag or timestamp from an incoming message and publishing a result
 * envelope to a reactor's output channels.
 */
public final class RuleUtils {

  // TODO(framework): the announce group below is hard-coded to "CDSS" to preserve the
  // existing config convention (sinks list the "CDSS" pseudo-device). Parameterize it
  // (e.g. pass the reactor's addressGroup) so the core carries no domain vocabulary.
  private static final String REACTOR_ADDRESS_GROUP = "CDSS";

  private RuleUtils() {
    throw new IllegalStateException("Utility class");
  }

  /** Message field carrying a tag's microstep; absent means 0. */
  public static final String MICROSTEP_FIELD = "microstep";

  /**
   * Publishes a result envelope ({@code timestamp}, {@code microstep}, {@code className},
   * {@code value}, {@code channelID}) to each output channel and announces those channels so
   * sinks can discover them.
   *
   * <p>A reactor publishes at most one message per output channel and firing, with the firing's
   * output tag (see {@link Reactor}); a second message on the same channel with the same tag
   * would be discarded by every downstream reactor.</p>
   *
   * @param eventBus       the bus to publish on
   * @param value          the result value
   * @param id             the producing reactor's classification id
   * @param outputChannels channels to publish the result to
   * @param tag            the output tag of the firing that produced the result
   */
  public static void publishResult(
          EventBus eventBus,
          Object value,
          String id,
          List<String> outputChannels,
          Tag tag
  ) {
    JSONObject result = new JSONObject();

    result.put(
            "timestamp",
            tag.t().atZone(ZoneOffset.UTC).format(formatter)
    );
    result.put(MICROSTEP_FIELD, tag.m());

    result.put("className", id);
    result.put("value", value);

    for (String out : outputChannels) {
      result.put("channelID", out);
      eventBus.publish(Service.addressRegistry(REACTOR_ADDRESS_GROUP), out);
      eventBus.publish(out, result);
    }
  }

  /**
   * Parses the tag {@code (timestamp, microstep)} of a message. A missing or non-numeric
   * {@code "microstep"} field is read as 0, so device and parser messages carry {@code (t, 0)}.
   *
   * @param msg the message (expected to be a {@link JSONObject})
   * @return the tag, or {@code null} if the message is no {@link JSONObject} or has no
   *         {@code "timestamp"}
   * @throws java.time.format.DateTimeParseException if the timestamp is malformed
   * @throws IllegalArgumentException if the microstep is negative
   */
  public static Tag extractTag(Object msg) {
    Instant t = extractTimestamp(msg);
    if (t == null) return null;
    long m = ((JSONObject) msg).optLong(MICROSTEP_FIELD, 0L);
    return new Tag(t, m);
  }

  /**
   * Parses the logical timestamp from an incoming message, if present.
   *
   * @param latest the latest message for a channel (expected to be a {@link JSONObject})
   * @return the parsed {@link Instant}, or {@code null} if absent/unparseable
   */
  public static Instant extractTimestamp(Object latest) {
    if (!(latest instanceof JSONObject dp)) return null;
    if (!dp.has("timestamp")) return null;
    return LocalDateTime.parse(dp.getString("timestamp"), formatter)
            .atZone(ZoneOffset.UTC)
            .toInstant();
  }
}
