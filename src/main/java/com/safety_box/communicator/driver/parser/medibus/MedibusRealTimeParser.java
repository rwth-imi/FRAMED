package com.safety_box.communicator.driver.parser.medibus;

import com.safety_box.communicator.driver.parser.Parser;
import com.safety_box.communicator.driver.utils.DataConstants;
import com.safety_box.communicator.driver.utils.DataUtils;
import com.safety_box.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class MedibusRealTimeParser extends Parser<Byte> {
  private final ArrayList<Byte> realTimeByteList = new ArrayList<>();

  private ArrayList<Byte> waveFormTypeList = new ArrayList<>();
  private final List<Map<String, Object>> waveValResultList = new CopyOnWriteArrayList<>();;

  private final ArrayList<JSONObject> realTimeConfigResponsesList = new ArrayList<>();

  public MedibusRealTimeParser(EventBus eventBus, int waveFormType, JSONArray devices) {
    super(eventBus);
    waveFormTypeList = DataUtils.createWaveFormTypeList(waveFormType);
    for  (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName+"_rt", msg -> {
        if (msg instanceof JSONObject) {
          realTimeConfigResponsesList.add((JSONObject) msg);
        } else if (msg instanceof Byte) {
          parse((Byte) msg, deviceName);
        }
      });
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

    for (int i = 0; i < realTimeByteArray.length; i++) {
      byte bValue = realTimeByteArray[i];

      if ((bValue & DataConstants.SYNC_BYTE) == DataConstants.SYNC_BYTE) {
        byte syncByte = bValue;
        List<byte[]> syncCommands = new ArrayList<>();
        List<byte[]> rtDataValues = new ArrayList<>();
        List<Integer> dataStreamList = new ArrayList<>();
        String respSyncState = null;

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

        // Build dataStreamList from syncByte
        if ((syncByte & 0x01) != 0) dataStreamList.add(0);
        if ((syncByte & 0x02) != 0) dataStreamList.add(1);
        if ((syncByte & 0x04) != 0) dataStreamList.add(2);
        if ((syncByte & 0x08) != 0) dataStreamList.add(3);

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
          long unixTimestamp = System.currentTimeMillis();
          for (int k = 0; k < dataStreamList.size(); k++) {
            int streamIndex = dataStreamList.get(k);
            if (streamIndex < waveFormTypeList.size()) {
              byte waveCode = waveFormTypeList.get(streamIndex);
              String waveDataCode = String.format("%02x", waveCode);
              JSONObject config = realTimeConfigResponsesList.stream()
                .filter(x -> x.getString("dataCode").equals(waveDataCode))
                .findFirst()
                .orElse(null);

              if (config != null) {
                int minValue = config.getInt("minValue");
                int maxValue = config.getInt("maxValue");
                int maxBinValue = config.getInt("maxBinValue");

                byte[] rtBytes = rtDataValues.get(k);
                int binVal = (rtBytes[0] & 0x3F) | ((rtBytes[1] & 0x3F) << 6);
                double rtValue = (((double) binVal / maxBinValue) * (maxValue - minValue)) + minValue;
                double finalValue = Math.round(rtValue * 10000.0) / 10000.0;

                // Store or process the result
                Map<String, Object> result = new HashMap<>();
                result.put("dataStreamIndex", streamIndex);
                result.put("timestamp", System.currentTimeMillis());
                result.put("physioID", DataConstants.MedibusXRealTimeData.get(waveCode));
                result.put("respiratoryCycleState", respSyncState);
                result.put("value", finalValue);
                result.put("relativeTimeCounter", unixTimestamp);
                result.put("config", config);

                waveValResultList.add(result); // Assuming this is now a List<Map<String, Object>>
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

    if (!waveValResultList.isEmpty()) {
      write(deviceName);
    }
  }


  public void write(String deviceName) {
    for (Map<String, Object> map : waveValResultList) {
      String physioID = (String) map.get("physioID");
      double value = (double) map.get("value");
      //System.out.printf("RT_Message - %s: %s%n", physioID, value);
      JSONObject waveValResult = new JSONObject();
      waveValResult.put("timestamp", Instant.now());
      waveValResult.put("realTime", true);
      waveValResult.put("physioID", physioID);
      waveValResult.put("value", value);
      waveValResult.put("className", "RealTime");
      String address = deviceName+"."+physioID+".parsed";
      eventBus.publish(deviceName+".addresses", address);
      eventBus.publish(address, waveValResult);
    }
  }

  @Override
  public void stop() {

  }
}
