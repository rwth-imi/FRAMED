package com.framed.streamer;

import com.framed.streamer.model.DataPoint;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Parser {
  public static DataPoint<?> parse(JSONObject jsonObject) throws Exception {
    Object value = jsonObject.get("value");

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"); // TODO this needs to be global, right?
    LocalDateTime ldt = LocalDateTime.parse(jsonObject.getString("timestamp"), formatter);
    ZoneId zoneId = ZoneId.systemDefault();
    Instant timestamp = ldt.atZone(zoneId).toInstant();
    String physioID = jsonObject.getString("physioID");
    String deviceID = jsonObject.getString("deviceID");
    String className = jsonObject.getString("className");
    return new DataPoint<>(timestamp, value, physioID, deviceID,  className);
  }
}
