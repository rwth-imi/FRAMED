package com.framed.streamer;

import com.framed.core.utils.Timer;
import com.framed.streamer.model.DataPoint;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Parser {
  public static DataPoint<?> parse(JSONObject jsonObject) throws Exception {
    Object value = jsonObject.get("value");


    LocalDateTime ldt = LocalDateTime.parse(jsonObject.getString("timestamp"), Timer.formatter);
    ZoneId zoneId = ZoneId.systemDefault();
    Instant timestamp = ldt.atZone(zoneId).toInstant();
    String channelID = jsonObject.getString("channelID");
    String deviceID = jsonObject.getString("deviceID");
    String className = jsonObject.getString("className");
    return new DataPoint<>(timestamp, value, channelID, deviceID, className);
  }
}
