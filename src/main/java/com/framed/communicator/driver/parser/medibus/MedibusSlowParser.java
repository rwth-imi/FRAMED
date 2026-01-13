package com.framed.communicator.driver.parser.medibus;

import com.framed.communicator.driver.parser.Parser;
import com.framed.communicator.driver.protocol.medibus.utils.DataConstants;
import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MedibusSlowParser extends Parser<byte[]> {
  final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

  public MedibusSlowParser(EventBus eventBus, JSONArray devices) {
    super(eventBus);
    for (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName, msg -> {
        handleEventBus(msg, deviceName);
      });
    }


  }

  private synchronized void handleEventBus(Object msg, String deviceName) {
    if (msg instanceof byte[] values){
      parse(values, deviceName);
    } else if (msg instanceof JSONArray message) {
      byte[] values = new byte[message.length()];
      for (int i = 0; i < message.length(); i++) {
        values[i] = (byte) message.getInt(i);
      }
      parse(values, deviceName);
    } else {
      logger.warning("Message dataypte unmatched.");
    }


  }

  @Override
  public void parse(byte[] message, String deviceName) {
    String data = new String(message, StandardCharsets.US_ASCII);

    String echo = data.substring(0, 2);
    switch (echo) {
      case "\u0001$" -> // Data cp1
        parseNumMessage(message, 6, "MeasurementCP1", deviceName);
      case "\u0001+" -> // Data cp2
        parseNumMessage(message, 6, "MeasurementCP2", deviceName);
      case "\u0001)" -> // Data device settings
        parseNumMessage(message, 7, "DeviceSettings", deviceName);
      case "\u0001*" -> // Data text messages
        parseTextMessage(message, deviceName);
      case "\u0001'" -> // Alarm cp1
        parseAlarmMessage(message, "AlarmCP1", deviceName);
      case "\u0001." -> // Alarm cp2
        parseAlarmMessage(message, "AlarmCP2", deviceName);
    }
  }


  private void parseTextMessage(byte[] message, String deviceName) {
    int offset = 4;
    int dataArrayLength = message.length - 2;
    byte[] dataArray = new byte[dataArrayLength];
    System.arraycopy(message, 2, dataArray, 0, dataArrayLength);
    String response = new String(dataArray, StandardCharsets.US_ASCII);
    int responseLength = response.length();

    String dataCode;
    String dataValue;
    String channelID;

    int lastItemLength = 0;
    for (int i = 0; i < responseLength; i += offset + lastItemLength) {
      dataCode = response.substring(i, i + 1);
      String lastItemLengthString = response.substring(i + 2, i + 3);
      byte textItemLengthByte = lastItemLengthString.getBytes(StandardCharsets.US_ASCII)[0];
      lastItemLength = textItemLengthByte - 0x30;

      if (!(lastItemLength == 0)) {
        dataValue = response.substring(i + 3, i + 3 + lastItemLength);
        dataValue = dataValue.trim();
        byte dataCodeByte = dataCode.getBytes(StandardCharsets.US_ASCII)[0];
        channelID = DataConstants.MedibusXTextMessages.get(dataCodeByte);
        // System.out.printf("TextMessage: %s%n", dataValue);
        JSONObject result = new JSONObject().put(channelID, "TextMessage");
        result.put("value", dataValue);
        write(deviceName, result, "TextMessage");
      }
    }
  }

  private void parseNumMessage(byte[] message, int offset, String reqType, String deviceName) {
    // init local parser variables
    String dataCode;
    double dataValue;
    String channelID;

    int dataLength = 4;
    int dataArrayLength = message.length - 2;
    byte[] dataArray = new byte[dataArrayLength];
    System.arraycopy(message, 2, dataArray, 0, dataArrayLength);
    String response = new String(dataArray, StandardCharsets.US_ASCII);

    int responseLength = response.length();

    for (int i = 0; i < responseLength; i += offset) {
      if (i + 2 + dataLength > responseLength) {
        break;
      }
      dataCode = response.substring(i, i + 2).trim();

      String dataValueStr = response.substring(i + 2, i + 2 + dataLength).trim();
      int dataValueLen = dataValueStr.length();

      if (dataValueLen != 0) {
        dataValue = Double.parseDouble(dataValueStr);
      } else {
        dataValue = -1;
      }

      byte dataCodeByte = (byte) (Integer.parseInt(dataCode, 16) % 256);
      String className = "";

      switch (reqType) {
        case "MeasurementCP1" -> {
          className = "Measurement";
          channelID = DataConstants.MedibusXMeasurementCP1.get(dataCodeByte);
        }
        case "MeasurementCP2" -> {
          className = "Measurement";
          channelID = DataConstants.MedibusXMeasurementCP2.get(dataCodeByte);
        }
        case "DeviceSettings" -> {
          className = "Settings";
          channelID = DataConstants.MedibusXDeviceSettings.get(dataCodeByte);
        }
        default -> throw new IllegalStateException("Unexpected value: " + reqType);
      }
      JSONObject result = new JSONObject().put("channelID", channelID);
      result.put("value", dataValue);
      write(deviceName, result, className);
    }
  }

  private void parseAlarmMessage(byte[] message, String reqType, String deviceName) {
    int offset = 15;
    int dataArrayLength = message.length - 2;
    byte[] dataArray = new byte[dataArrayLength];
    System.arraycopy(message, 2, dataArray, 0, dataArrayLength);
    String response = new String(dataArray, StandardCharsets.US_ASCII);
    int responseLength = response.length();
    String dataCode = "";
    String dataValue = "";
    String channelID = "";

    if (responseLength > 0) {
      for (int i = 0; i < responseLength; i += offset) {
        dataCode = response.substring(i + 1, i + 3);

        int j = Math.min(responseLength, i + 15);
        if (j <= i + 3) {
          break;
        }
        dataValue = response.substring(i + 3, j);
        dataValue = dataValue.trim();
        byte dataCodeByte = dataCode.getBytes(StandardCharsets.US_ASCII)[0];
        channelID = switch (reqType) {
          case "AlarmCP1" -> DataConstants.MedibusXAlarmsCP1.get(dataCodeByte);
          case "AlarmCP2" -> DataConstants.MedibusXAlarmsCP2.get(dataCodeByte);
          default -> throw new IllegalStateException("Unexpected value: " + reqType);
        };
        JSONObject result = new JSONObject().put("channelID", channelID);
        result.put("value", dataValue);
        write(deviceName, result, "Alarm");
      }
    }
  }

  private void write(String deviceName, JSONObject result, String className) {
    result.put("timestamp", LocalDateTime.now().format(formatter));
    result.put("className", className);
    String channelID = result.getString("channelID");
    String address = deviceName + "." + channelID + ".parsed";
    eventBus.publish(deviceName + ".addresses", address);
    eventBus.publish(address, result);
  }
}
