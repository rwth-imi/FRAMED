package com.safety_box.streamer.parser;

import com.safety_box.streamer.model.DataPoint;
import org.json.JSONObject;

import java.time.Instant;

public class Parser {
  public static DataPoint<?> parse(JSONObject jsonObject) throws Exception {
    Object value = jsonObject.get("value");
    Instant timestamp = (Instant) jsonObject.get("timestamp");
    String physioID = jsonObject.getString("physioID");
    String deviceID = jsonObject.getString("deviceID");
    String className = jsonObject.getString("className");
    return new DataPoint<>(timestamp, value, physioID, deviceID,  className);
  }
}
