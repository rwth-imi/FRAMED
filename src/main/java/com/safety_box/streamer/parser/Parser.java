package com.safety_box.streamer.parser;

import com.safety_box.streamer.model.DataPoint;
import io.vertx.core.json.JsonObject;

import java.time.Instant;

public class Parser {
  public static DataPoint<?> parse(JsonObject jsonObject) throws Exception {
    Object value = jsonObject.getValue("value");
    Instant timestamp = jsonObject.getInstant("timestamp");
    String physioID = jsonObject.getString("physioID");
    String deviceID = jsonObject.getString("deviceID");
    String className = jsonObject.getString("className");
    return new DataPoint<>(timestamp, value, physioID, deviceID,  className);
  }
}
