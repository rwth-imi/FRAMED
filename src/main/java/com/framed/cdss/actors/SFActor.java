package com.framed.cdss.casestudy;

import com.framed.cdss.Actor;
import com.framed.core.EventBus;

import java.util.List;
import java.util.Map;

import static com.framed.cdss.utils.CDSSUtils.publishResult;

public class SFActor extends Actor {
  private final String spo2Channel;
  private final String fio2Channel;


  public SFActor(EventBus eventBus, String id, String spo2Channel, String fio2Channel, String outputChannel) {
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
        outputChannel
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
      publishResult(eventBus, formatter, sf, id, outputChannels);
    }
  }
}
