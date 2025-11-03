package com.framed.streamer.model;

import java.time.Instant;

import org.json.JSONObject;

public record DataPoint<T>(Instant timestamp, T value, String channelID, String deviceID, String className) {
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

