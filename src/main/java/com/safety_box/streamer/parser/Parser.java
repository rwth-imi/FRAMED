package com.safety_box.streamer.parser;

import com.safety_box.streamer.model.DataPoint;
import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;

public class Parser {
  public static DataPoint<?> parse(JsonObject jsonObject) throws Exception {
    Object value = jsonObject.getValue("value");
    long timestamp = jsonObject.getLong("timestamp");
    String physioID = jsonObject.getString("physioID");
    String deviceID = jsonObject.getString("deviceID");
    return new DataPoint<>(timestamp, value, physioID, deviceID);
  }
}
