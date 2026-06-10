package com.framed.communicator.driver.protocol.medibus.utils;

import com.framed.core.EventBus;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ParsingUtils {
  private ParsingUtils() {
    throw new IllegalStateException("Utility class");
  }
  public static byte[] hexToBytes(String hex) {
    String[] parts = hex.split(" ");
    byte[] bytes = new byte[parts.length];
    for (int i = 0; i < parts.length; i++) {
      bytes[i] = (byte) Integer.parseInt(parts[i], 16);
    }
    return bytes;
  }

  public static String stringToHex(String bytesAsString) {
    byte[] bytes = bytesAsString.getBytes(StandardCharsets.US_ASCII);

    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X ", b));
    }
    return sb.toString().trim();
  }

  public static void readRealtimeConfigResponse(byte[] packetData, EventBus eventBus, String id) {
    // Store configuration values
    ByteBuffer bb = ByteBuffer.wrap(packetData);

    bb.position(2); // skip the packet header

    while (bb.remaining() >= 23) {
      byte[] dataCode = new byte[2];
      bb.get(dataCode);
      String dataCodeString = new String(dataCode, StandardCharsets.US_ASCII).trim().replaceAll("\\s+", "");
      byte[] interval = new byte[8];
      bb.get(interval);
      String intervalString = new String(interval, StandardCharsets.US_ASCII).trim().replaceAll("\\s+", "");
      byte[] minValue = new byte[5];
      bb.get(minValue);
      String minValueString = new String(minValue, StandardCharsets.US_ASCII).trim().replaceAll("\\s+", "");
      byte[] maxValue = new byte[5];
      bb.get(maxValue);
      String maxValueString = new String(maxValue, StandardCharsets.US_ASCII).trim().replaceAll("\\s+", "");
      byte[] maxBinValue = new byte[3];
      bb.get(maxBinValue);
      String maxBinValueString = new String(maxBinValue, StandardCharsets.US_ASCII).trim().replaceAll("\\s+", "");

      JSONObject rtConfig = new JSONObject();
      rtConfig.put("dataCode", dataCodeString);
      rtConfig.put("Interval", Integer.parseInt(intervalString));
      rtConfig.put("minValue", Integer.parseInt(minValueString));
      rtConfig.put("maxValue", Integer.parseInt(maxValueString));
      rtConfig.put("maxBinValue", Integer.parseInt(maxBinValueString, 16));

      eventBus.publish(id + ".real-time", rtConfig);
    }
  }
}
