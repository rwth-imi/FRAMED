package com.framed.io.dispatch;

import java.time.Instant;

import org.json.JSONObject;

/**
 * An immutable, timestamped value sample produced by a device channel.
 *
 * @param <T>       the type of the carried value
 * @param timestamp the instant the value was observed
 * @param value     the observed value
 * @param channelID the identifier of the channel that produced the value
 * @param deviceID  the identifier of the device that produced the value
 * @param className the runtime class name describing the value's type
 */
public record DataPoint<T>(Instant timestamp, T value, String channelID, String deviceID, String className) {
  /**
   * Serializes this datapoint to its JSON string representation.
   *
   * @return a JSON string with {@code timestamp}, {@code value}, {@code channelID},
   *         {@code deviceID} and {@code className} fields
   */
  public String toJsonString() {
    JSONObject json = new JSONObject();
    json.put("timestamp", timestamp.toString());
    json.put("value", value);
    json.put("channelID", channelID);
    json.put("deviceID", deviceID);
    json.put("className", className);
    return json.toString();
  }
}

