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
 * extracting a logical timestamp from an incoming message and publishing a result
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

  /**
   * Publishes a result envelope ({@code timestamp}, {@code className}, {@code value},
   * {@code channelID}) to each output channel and announces those channels so sinks can
   * discover them.
   *
   * @param eventBus       the bus to publish on
   * @param value          the result value
   * @param id             the producing reactor's classification id
   * @param outputChannels channels to publish the result to
   * @param timestamp      the logical timestamp of the result
   */
  public static void publishResult(
          EventBus eventBus,
          Object value,
          String id,
          List<String> outputChannels,
          Instant timestamp
  ) {
    JSONObject result = new JSONObject();

    result.put(
            "timestamp",
            timestamp.atZone(ZoneOffset.UTC).format(formatter)
    );

    result.put("className", id);
    result.put("value", value);

    for (String out : outputChannels) {
      result.put("channelID", out);
      eventBus.publish(Service.addressRegistry(REACTOR_ADDRESS_GROUP), out);
      eventBus.publish(out, result);
    }
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
