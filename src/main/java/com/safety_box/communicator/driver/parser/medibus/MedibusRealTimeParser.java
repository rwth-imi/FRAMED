package com.safety_box.communicator.driver.parser.medibus;

import com.safety_box.communicator.driver.parser.Parser;
import com.safety_box.communicator.driver.utils.DataConstants;
import com.safety_box.communicator.driver.utils.DataUtils;
import com.safety_box.core.EventBus;
import com.safety_box.core.EventBusInterface;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class MedibusRealTimeParser extends Parser<Byte> {
  private final List<Byte> realTimeByteList = new CopyOnWriteArrayList<>();
  private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

  private List<Byte> waveFormTypeList = new CopyOnWriteArrayList<>();
  private final List<Map<String, Object>> waveValResultList = new CopyOnWriteArrayList<>();;

  private final List<JSONObject> realTimeConfigResponsesList = new CopyOnWriteArrayList<>();

  public MedibusRealTimeParser(EventBusInterface eventBus, int waveFormType, JSONArray devices) {
    super(eventBus);
    waveFormTypeList = DataUtils.createWaveFormTypeList(waveFormType);
    for  (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName+".real-time", msg -> {
        handleEventBus(msg, deviceName);
      });
    }
  }

  private synchronized void handleEventBus(Object msg, String deviceName) {
    if (msg instanceof JSONObject) {
      realTimeConfigResponsesList.add((JSONObject) msg);
    } else if (msg instanceof Integer) {
      int value = (int) msg;
      parse((byte) value, deviceName);
    }
  }

  @Override
  public void parse(Byte message, String deviceName) {
    realTimeByteList.add(message);

    int bytesSuccessfullyRead = 0;

    byte[] realTimeByteArray = new byte[realTimeByteList.size()];
    for (int i = 0; i < realTimeByteList.size(); i++) {
      realTimeByteArray[i] = realTimeByteList.get(i);
    }

    // Collect results locally for this parse pass
    List<Map<String, Object>> batch = new ArrayList<>();
    // Capture one record time per realtime record
    long recordWallMs = 0L; // set per record when we detect sync byte
    long recordMonoNs = 0L; // optional relative time

    for (int i = 0; i < realTimeByteArray.length; i++) {
      byte bValue = realTimeByteArray[i];

      if ((bValue & DataConstants.SYNC_BYTE) == DataConstants.SYNC_BYTE) {
        // Start of a new realtime data record -> capture timestamps NOW
        recordWallMs = System.currentTimeMillis();
        recordMonoNs = System.nanoTime();

        byte syncByte = bValue;
        List<byte[]> syncCommands = new ArrayList<>();
        List<byte[]> rtDataValues = new ArrayList<>();
        List<Integer> dataStreamList = new ArrayList<>();
        String respSyncState = null;

        // Parse the record following the sync byte
        for (int j = i + 1; j < realTimeByteArray.length - 1; j++) {
          byte bValueNext = realTimeByteArray[j];
          if ((bValueNext & DataConstants.SYNC_BYTE) == DataConstants.SYNC_BYTE) break;

          byte[] buffer = new byte[2];
          System.arraycopy(realTimeByteArray, j, buffer, 0, 2);

          if ((bValueNext & DataConstants.SYNC_CMD_BYTE) == DataConstants.SYNC_CMD_BYTE) {
            syncCommands.add(buffer);
          } else {
            rtDataValues.add(buffer);
          }
          j++;
          i = j;
        }

        // Streams 1..4 from syncByte bits
        if ((syncByte & 0x01) != 0) dataStreamList.add(0);
        if ((syncByte & 0x02) != 0) dataStreamList.add(1);
        if ((syncByte & 0x04) != 0) dataStreamList.add(2);
        if ((syncByte & 0x08) != 0) dataStreamList.add(3);

        // Sync-commands
        for (byte[] cmd : syncCommands) {
          byte cmdType = cmd[0];
          byte arg = cmd[1];
          switch (cmdType) {
            case DataConstants.SC_TX_DATASTREAM_5_8:
              if ((arg & 0x01) != 0) dataStreamList.add(4);
              if ((arg & 0x02) != 0) dataStreamList.add(5);
              if ((arg & 0x04) != 0) dataStreamList.add(6);
              if ((arg & 0x08) != 0) dataStreamList.add(7);
              break;
            case DataConstants.SC_TX_DATASTREAM_9_12:
              if ((arg & 0x01) != 0) dataStreamList.add(8);
              if ((arg & 0x02) != 0) dataStreamList.add(9);
              if ((arg & 0x04) != 0) dataStreamList.add(10);
              if ((arg & 0x08) != 0) dataStreamList.add(11);
              break;
            case DataConstants.SC_START_CYCLE:
              if (arg == (byte) 0xC0) respSyncState = "InspStart";
              if (arg == (byte) 0xC1) respSyncState = "ExpStart";
              break;
          }
        }

        if (rtDataValues.size() == dataStreamList.size()) {
          for (int k = 0; k < dataStreamList.size(); k++) {
            int streamIndex = dataStreamList.get(k);
            if (streamIndex < waveFormTypeList.size()) {
              byte waveCode = waveFormTypeList.get(streamIndex);
              String waveDataCode = String.format("%02x", waveCode);

              // Be lenient on case to avoid config mismatches
              JSONObject config = realTimeConfigResponsesList.stream()
                .filter(x -> x.getString("dataCode").equals(waveDataCode))
                .findFirst()
                .orElse(null);

              if (config != null) {
                // Prefer doubles for MIN/MAX (they can be negative / decimal per spec)
                double minValue = config.optDouble("minValue", 0.0);
                double maxValue = config.optDouble("maxValue", 0.0);
                int maxBinValue = config.optInt("maxBinValue", 1);

                byte[] rtBytes = rtDataValues.get(k);

                // FIX: 12-bit assembly per spec: first byte = bits 6..11 (high)
                //int binVal = ((rtBytes[0] & 0x3F) << 6) | (rtBytes[1] & 0x3F);  // <-- corrected
                int binVal =  (rtBytes[0] & 0x3F) | ((rtBytes[1] & 0x3F) << 6);  // <-- old

                double rtValue = ( (double)binVal / maxBinValue ) * (maxValue - minValue) + minValue;
                double finalValue = Math.round(rtValue);

                Map<String, Object> result = new HashMap<>();
                result.put("dataStreamIndex", streamIndex);
                // Use the captured record time — not "now" later
                result.put("timestampMs", recordWallMs);
                result.put("relativeTimeNs", recordMonoNs);
                result.put("physioID", DataConstants.MedibusXRealTimeData.get(waveCode));
                result.put("respiratoryCycleState", respSyncState);
                result.put("value", finalValue);
                result.put("config", config);

                batch.add(result);
              }
            }
          }
          bytesSuccessfullyRead = i + 1;
        }
      }
    }

    if (bytesSuccessfullyRead > 0) {
      realTimeByteList.subList(0, bytesSuccessfullyRead).clear();
    }

    if (!batch.isEmpty()) {
      write(deviceName, batch); // <-- pass local batch
    }
  }

  public void write(String deviceName, List<Map<String, Object>> batch) {
    for (Map<String, Object> map : batch) {
      String physioID = (String) map.get("physioID");
      double value = (double) map.get("value");

      long tsMs = (long) map.get("timestampMs");
      // Render ISO-8601 with microseconds from the captured ms
      String tsIso = LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), java.time.ZoneId.systemDefault())
        .format(formatter);

      JSONObject waveValResult = new JSONObject();
      waveValResult.put("timestamp", tsIso);            // <-- use captured time
      waveValResult.put("timestampMs", tsMs);           // optional raw ms
      waveValResult.put("realTime", true);
      waveValResult.put("physioID", physioID);
      waveValResult.put("value", value);
      waveValResult.put("className", "RealTime");

      String address = deviceName + "." + physioID + ".parsed";
      eventBus.publish(deviceName + ".addresses", address);
      eventBus.publish(address, waveValResult);
    }
  }

  @Override
  public void stop() {

  }
}
