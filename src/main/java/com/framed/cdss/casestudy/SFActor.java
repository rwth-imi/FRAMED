package com.framed.cdss.casestudy;

import com.framed.cdss.Actor;
import com.framed.core.EventBus;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class SFActor extends Actor {
  final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
  private static final String SPO_2_ADDRESS = "PC60FW.SpO2.parsed";
  private static final String FIO_2_ADDRESS = "Oxylog-3000-Plus-00.Inspiratory oxygen fraction, FiO2.parsed";


  public SFActor(EventBus eventBus) {
    super(
      eventBus,
      List.of(
        Map.of(SPO_2_ADDRESS, "*"),
        Map.of(FIO_2_ADDRESS, "*")
      ),
      List.of(
        SPO_2_ADDRESS,
        FIO_2_ADDRESS
      ),
      List.of(
        "S/F-Value"
      )
    );

  }

  @Override
  public void fireFunction(Map<String, Object> latestValues) {
    if (
      latestValues.get(SPO_2_ADDRESS) instanceof Number spo2
        && latestValues.get(FIO_2_ADDRESS) instanceof Number fio2
    ){
      float sf = spo2.floatValue() / fio2.floatValue();
      JSONObject result = new JSONObject();
      result.put("timestamp", LocalDateTime.now().format(formatter));
      result.put("value", sf);
      for (String channelID: outputChannels) {
        result.put("channelID", channelID);
        result.put("className", "S/F");
        eventBus.publish("CDSS.addresses", channelID);
        eventBus.publish(channelID, result);
      }
    }
  }
}
