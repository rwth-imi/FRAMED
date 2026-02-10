package com.framed.communicator.driver.parser.medibus;

import com.framed.communicator.driver.parser.Parser;
import com.framed.communicator.driver.protocol.medibus.utils.DataConstants;
import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.logging.Level;

public class MedibusSlowParser extends Parser<byte[]> {

  public MedibusSlowParser(EventBus eventBus, JSONArray devices) {
    super(eventBus);
    for (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName, msg -> handleEventBus(msg, deviceName));
    }


  }

  private synchronized void handleEventBus(Object msg, String deviceName) {
    switch (msg) {
      case byte[] values -> parse(values, deviceName);
      case JSONArray message -> {
        byte[] values = new byte[message.length()];
        for (int i = 0; i < message.length(); i++) {
          values[i] = (byte) message.getInt(i);
        }
        parse(values, deviceName);
      }
      case null, default -> logger.warning("Message dataypte unmatched.");
    }


  }

  @Override
  public void parse(byte[] message, String deviceName) {
    LocalDateTime timestamp = LocalDateTime.now();
    String data = new String(message, StandardCharsets.US_ASCII);

    String echo = data.substring(0, 2);
    switch (echo) {
      case "\u0001$" -> // Data cp1
        parseNumMessage(message, 6, "MeasurementCP1", deviceName, timestamp);
      case "\u0001+" -> // Data cp2
        parseNumMessage(message, 6, "MeasurementCP2", deviceName, timestamp);
      case "\u0001)" -> // Data device settings
        parseNumMessage(message, 7, "DeviceSettings", deviceName, timestamp);
      case "\u0001*" -> // Data text messages
        parseTextMessage(message, deviceName, timestamp);
      case "\u0001'" -> // Alarm cp1
        parseAlarmMessage(message, "AlarmCP1", deviceName, timestamp);
      case "\u0001." -> // Alarm cp2
        parseAlarmMessage(message, "AlarmCP2", deviceName, timestamp);
      default ->
        logger.log(Level.WARNING, "Unknown message of type: {}", echo);
    }
  }


  private void parseTextMessage(byte[] message, String deviceName, LocalDateTime timestamp) {
    int offset = 4;
    int dataArrayLength = message.length - 2;
    byte[] dataArray = new byte[dataArrayLength];
    System.arraycopy(message, 2, dataArray, 0, dataArrayLength);
    String response = new String(dataArray, StandardCharsets.US_ASCII);
    int responseLength = response.length();

    String dataCode;
    String dataValue;
    String channelID;

    int lastItemLength;
    for (int i = 0; i < responseLength; i += offset + lastItemLength) {
      dataCode = response.substring(i, i + 1);
      String lastItemLengthString = response.substring(i + 2, i + 3);
      byte textItemLengthByte = lastItemLengthString.getBytes(StandardCharsets.US_ASCII)[0];
      lastItemLength = textItemLengthByte - 0x30;

      if (lastItemLength != 0) {
        dataValue = response.substring(i + 3, i + 3 + lastItemLength);
        dataValue = dataValue.trim();
        byte dataCodeByte = dataCode.getBytes(StandardCharsets.US_ASCII)[0];
        channelID = DataConstants.MedibusXTextMessages.get(dataCodeByte);

        write(deviceName, channelID, "TextMessage", dataValue, timestamp);
      }
    }
  }

  private void parseNumMessage(byte[] message, int offset, String reqType, String deviceName, LocalDateTime timestamp) {
    int dataLength = 4;
    int dataArrayLength = message.length - 2;
    byte[] dataArray = new byte[dataArrayLength];
    System.arraycopy(message, 2, dataArray, 0, dataArrayLength);
    String response = new String(dataArray, StandardCharsets.US_ASCII);

    String dataCode;
    double dataValue;
    String channelID;

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
      String className;

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
        default -> throw new IllegalStateException("Unexpected value: %s".formatted(reqType));
      }

      write(deviceName, channelID, className, dataValue, timestamp);
    }
  }

  private void parseAlarmMessage(byte[] message, String reqType, String deviceName, LocalDateTime timestamp) {
    int offset = 15;
    int dataArrayLength = message.length - 2;
    byte[] dataArray = new byte[dataArrayLength];
    System.arraycopy(message, 2, dataArray, 0, dataArrayLength);
    String response = new String(dataArray, StandardCharsets.US_ASCII);
    int responseLength = response.length();
    String dataCode;
    String dataValue;
    String channelID;

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
          default -> throw new IllegalStateException("Unexpected value: %s".formatted(reqType));
        };
        write(deviceName, channelID, "Alarm", dataValue, timestamp);
      }
    }
  }

  private void write(String deviceName, String channelID, String className, Object dataValue, LocalDateTime timestamp) {
    JSONObject result = new JSONObject().put("channelID", channelID);
    result.put("value", dataValue);
    result.put("timestamp", timestamp.format(formatter));
    result.put("className", className);
    String address = "%s.%s.%s.parsed".formatted(className, deviceName, channelID);
    eventBus.publish(MessageFormat.format("{0}.addresses", deviceName), address);
    eventBus.publish(address, result);
  }
}
