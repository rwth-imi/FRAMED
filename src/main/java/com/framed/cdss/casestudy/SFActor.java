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
  private final String spo2Channel;
  private final String fio2Channel;


  public SFActor(EventBus eventBus, String id, String spo2Channel, String fio2Channel) {
    super(
      eventBus,
      id,
      List.of(
        Map.of(spo2Channel, "*"),
        Map.of(fio2Channel, "*")
      ),
      List.of(
        spo2Channel,
        fio2Channel
      ),
      List.of(
        "S/F-Value"
      )
    );
    this.spo2Channel = spo2Channel;
    this.fio2Channel = fio2Channel;
  }

  @Override
  public void fireFunction(Map<String, Object> latestValues) {
    if (
      latestValues.get(spo2Channel) instanceof Number spo2
        && latestValues.get(fio2Channel) instanceof Number fio2
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
