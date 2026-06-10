package com.framed.io.dispatch;

import com.framed.core.utils.Timer;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Utility for converting a JSON bus message into a {@link DataPoint}.
 */
public class DataPointParser {

  private DataPointParser() {}

  /**
   * Converts a JSON bus message into a {@link DataPoint}.
   *
   * <p>The timestamp is parsed using {@link Timer#formatter} and interpreted as UTC.</p>
   *
   * @param jsonObject the JSON object carrying {@code value}, {@code timestamp},
   *                   {@code channelID}, {@code deviceID} and {@code className}
   * @return the datapoint decoded from the given JSON object
   * @throws Exception if a required field is missing or the timestamp cannot be parsed
   */
  public static DataPoint<?> parse(JSONObject jsonObject) throws Exception {
    Object value = jsonObject.get("value");


    LocalDateTime ldt = LocalDateTime.parse(jsonObject.getString("timestamp"), Timer.formatter);
    Instant timestamp = ldt.atZone(ZoneOffset.UTC).toInstant();
    String channelID = jsonObject.getString("channelID");
    String deviceID = jsonObject.getString("deviceID");
    String className = jsonObject.getString("className");
    return new DataPoint<>(timestamp, value, channelID, deviceID, className);
  }
}
