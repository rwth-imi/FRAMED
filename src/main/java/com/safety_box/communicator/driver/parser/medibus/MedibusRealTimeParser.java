package com.safety_box.communicator.driver.parser.medibus;

import com.safety_box.communicator.driver.parser.Parser;
import com.safety_box.communicator.driver.protocol.medibus.utils.DataUtils;
import com.safety_box.communicator.driver.protocol.medibus.utils.DataConstants;
import com.safety_box.core.EventBus;
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

  public MedibusRealTimeParser(EventBus eventBus, int waveFormType, JSONArray devices) {
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

    // NEW: local masks/patterns (minimal addition; use your DataConstants if present)
    final int SYNC_MASK        = DataConstants.SYNC_MASK;        // e.g., 0xF0
    final int SYNC_PATTERN     = DataConstants.SYNC_BYTE;        // e.g., 0xD0 (1101xxxx)
    final int SYNC_CMD_MASK    = DataConstants.SYNC_MASK;    // e.g., 0xF0
    final int SYNC_CMD_PATTERN = DataConstants.SYNC_CMD_BYTE;    // e.g., 0xC0 (1100xxxx)
    final int DATA_MASK        = 0xC0;                           // 1100_0000
    final int DATA_PATTERN     = 0x80;                           // 1000_0000 (value byte)

    for (int i = 0; i < realTimeByteArray.length; i++) {
      byte bValue = realTimeByteArray[i];

      // NEW: ignore slow bytes in realtime parser
      if ((bValue & 0x80) == 0) continue;

      // NEW: strict Sync detection (mask + pattern)
      if ( (bValue & SYNC_MASK) == SYNC_PATTERN ) {
        // Start of a new realtime data record -> capture timestamps NOW
        recordWallMs = System.currentTimeMillis();
        recordMonoNs = System.nanoTime();

        byte syncByte = bValue;
        List<byte[]> syncCommands = new ArrayList<>();
        List<byte[]> rtDataValues = new ArrayList<>();
        List<Integer> dataStreamList = new ArrayList<>();
        String respSyncState = null;

        boolean malformed = false; // NEW

        // Parse the record following the sync byte
        for (int j = i + 1; j < realTimeByteArray.length - 1; j++) {
          byte bValueNext = realTimeByteArray[j];

          // NEW: next record starts? (mask + pattern)
          if ( (bValueNext & SYNC_MASK) == SYNC_PATTERN ) break;

          byte[] buffer = new byte[2];
          System.arraycopy(realTimeByteArray, j, buffer, 0, 2);

          // NEW: classify pair strictly
          boolean isCmdPair =
            ((buffer[0] & SYNC_CMD_MASK) == SYNC_CMD_PATTERN) &&
              ((buffer[1] & SYNC_CMD_MASK) == SYNC_CMD_PATTERN);

          boolean isValPair =
            ((buffer[0] & DATA_MASK) == DATA_PATTERN) &&
              ((buffer[1] & DATA_MASK) == DATA_PATTERN);

          if (isCmdPair) {
            syncCommands.add(buffer);
          } else if (isValPair) {
            rtDataValues.add(buffer);
          } else {
            // NEW: malformed pair -> stop this record; drop only this sync-frame later
            malformed = true;
            break;
          }

          j++;
          i = j; // keep your original outer index sync
        }

        // NEW: if malformed, drop only the sync byte we consumed and continue scanning
        if (malformed) {
          bytesSuccessfullyRead = Math.max(bytesSuccessfullyRead, i + 1);
          continue;
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
            // (Optional) If you have a constant for "corrupt record" (CF C0), you can skip emitting this record.
          }
        }

        if (rtDataValues.size() == dataStreamList.size()) {
          for (int k = 0; k < dataStreamList.size(); k++) {
            int streamIndex = dataStreamList.get(k);
            if (streamIndex < waveFormTypeList.size()) {
              byte waveCode = waveFormTypeList.get(streamIndex);

              String waveDataCode = String.format("%02x", Byte.toUnsignedInt(waveCode));
              JSONObject config = realTimeConfigResponsesList.stream()
                .filter(x -> waveDataCode.equalsIgnoreCase(x.optString("dataCode", "")))
                .findFirst()
                .orElse(null);

              if (config != null) {
                // Prefer doubles for MIN/MAX (they can be negative / decimal per spec)
                double minValue = config.optDouble("minValue", 0.0);
                double maxValue = config.optDouble("maxValue", 0.0);
                int maxBinValue = config.optInt("maxBinValue", 1);

                byte[] rtBytes = rtDataValues.get(k);

                // NOTE: your corrected 12-bit assembly (first=low6, second=high6)
                int binVal = ((rtBytes[1] & 0x3F) << 6) | (rtBytes[0] & 0x3F);

                double rtValue = ((double) binVal / maxBinValue) * (maxValue - minValue) + minValue;
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
          // NEW: move bytesSuccessfullyRead forward to last consumed index (i was updated inside)
          bytesSuccessfullyRead = Math.max(bytesSuccessfullyRead, i + 1);
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
