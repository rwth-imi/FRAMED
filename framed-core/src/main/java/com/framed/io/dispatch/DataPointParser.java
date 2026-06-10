package com.framed.io.dispatch;

import com.framed.core.utils.Timer;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class DataPointParser {
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
