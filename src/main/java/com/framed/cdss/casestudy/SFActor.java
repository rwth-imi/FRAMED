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


  public SFActor(EventBus eventBus) {
    super(
      eventBus,
      List.of(
        List.of("PC60FW.SpO2.parsed"),
        List.of("Oxylog-3000-Plus-00.Inspiratory oxygen fraction, FiO2.parsed")
      ),
      List.of(
        "PC60FW.SpO2.parsed",
        "Oxylog-3000-Plus-00.Inspiratory oxygen fraction, FiO2.parsed"
      ),
      List.of(
        "S/F"
      )
    );
  }

  @Override
  public void fireFunction(Map<String, Object> latestValues) {
    if (
      latestValues.get("PC60FW.SpO2.parsed") instanceof Number spo2
        && latestValues.get("Oxylog-3000-Plus-00.Inspiratory oxygen fraction, FiO2.parsed") instanceof Number fio2
    ){
      float sf = spo2.floatValue() / fio2.floatValue();
      JSONObject result = new JSONObject();
      result.put("timestamp", LocalDateTime.now().format(formatter));
      result.put("value", sf);
      result.put("channelID", "S/F");
      result.put("className", "SFActor");
      eventBus.publish("S/F", result);
    }
  }
}
