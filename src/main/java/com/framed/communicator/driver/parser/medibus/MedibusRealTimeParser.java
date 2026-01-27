package com.framed.communicator.driver.parser.medibus;

import com.framed.communicator.driver.parser.Parser;
import com.framed.communicator.driver.protocol.medibus.utils.DataUtils;
import com.framed.communicator.driver.protocol.medibus.utils.DataConstants;
import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class MedibusRealTimeParser extends Parser<Byte> {
  private final List<Byte> realTimeByteList = new CopyOnWriteArrayList<>();

  private final List<Byte> waveFormTypeList;

  private final List<JSONObject> realTimeConfigResponsesList = new CopyOnWriteArrayList<>();

  public MedibusRealTimeParser(EventBus eventBus, int waveFormType, JSONArray devices) {
    super(eventBus);
    waveFormTypeList = DataUtils.createWaveFormTypeList(waveFormType);
    for (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register("%s.real-time".formatted(deviceName), msg -> handleEventBus(msg, deviceName));
    }
  }

  private synchronized void handleEventBus(Object msg, String deviceName) {
    if (msg instanceof JSONObject message) {
      realTimeConfigResponsesList.add(message);
    } else if (msg instanceof Byte) {
      parse((byte) msg, deviceName);
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
    long recordWallMs; // set per record when we detect sync byte
    long recordMonoNs; // optional relative time


    for (int i = 0; i < realTimeByteArray.length; i++) {
      byte bValue = realTimeByteArray[i];

      // ignore slow bytes in realtime parser
      if ((bValue & DataConstants.RT_BYTE) == 0) continue;

      // strict Sync detection (mask + pattern)
      if ((bValue & DataConstants.SYNC_MASK) == DataConstants.SYNC_BYTE) {
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

          // next record starts? (mask + pattern)
          if ((bValueNext & DataConstants.SYNC_MASK) == DataConstants.SYNC_BYTE) break;

          byte[] buffer = new byte[2];
          System.arraycopy(realTimeByteArray, j, buffer, 0, 2);

          boolean isCmdPair = isIsCmdPair(buffer);

          boolean isValPair =
            ((buffer[0] & DataConstants.RT_BYTE_MASK) == DataConstants.RT_BYTE) &&
              ((buffer[1] & DataConstants.RT_BYTE_MASK) == DataConstants.RT_BYTE);

          if (isCmdPair) {
            syncCommands.add(buffer);
          } else if (isValPair) {
            rtDataValues.add(buffer);
          } else {
            // malformed pair -> stop this record; drop only this sync-frame later
            malformed = true;
            break;
          }

          // skip seen entries
          j++;
          i = j;
        }

        // if malformed, drop only the sync byte we consumed and continue scanning
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
        respSyncState = interpretCommandTypes(syncCommands, dataStreamList, respSyncState);

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

                int binVal = ((rtBytes[1] & 0x3F) << 6) | (rtBytes[0] & 0x3F);

                double rtValue = ((double) binVal / maxBinValue) * (maxValue - minValue) + minValue;
                double finalValue = Math.round(rtValue);

                Map<String, Object> result = new HashMap<>();
                result.put("dataStreamIndex", streamIndex);
                // Use the captured record time — not "now" later
                result.put("timestampMs", recordWallMs);
                result.put("relativeTimeNs", recordMonoNs);
                result.put("channelID", DataConstants.MedibusXRealTimeData.get(waveCode));
                result.put("respiratoryCycleState", respSyncState);
                result.put("value", finalValue);
                result.put("config", config);

                batch.add(result);
              }
            }
          }
          //  move bytesSuccessfullyRead forward to last consumed index (i was updated inside)
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

  private static boolean isIsCmdPair(byte[] buffer) {
    // classify pair strictly
    return ((buffer[0] & DataConstants.SYNC_MASK) == DataConstants.SYNC_CMD_BYTE) &&
        ((buffer[1] & DataConstants.SYNC_MASK) == DataConstants.SYNC_CMD_BYTE);
  }

  private static String interpretCommandTypes(List<byte[]> syncCommands, List<Integer> dataStreamList, String respSyncState) {
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
    return respSyncState;
  }

  public void write(String deviceName, List<Map<String, Object>> batch) {
    for (Map<String, Object> map : batch) {
      String channelID = (String) map.get("channelID");
      double value = (double) map.get("value");

      long tsMs = (long) map.get("timestampMs");
      // Render ISO-8601 with microseconds from the captured ms
      String tsIso = LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), java.time.ZoneId.systemDefault())
        .format(formatter);

      JSONObject waveValResult = new JSONObject();
      waveValResult.put("timestamp", tsIso);
      waveValResult.put("timestampMs", tsMs);           // optional raw ms
      waveValResult.put("channelID", channelID);
      waveValResult.put("value", value);
      waveValResult.put("className", "RealTime");

      String address = deviceName + "." + channelID + ".parsed";
      eventBus.publish(deviceName + ".addresses", address);
      eventBus.publish(address, waveValResult);
    }
  }
}
