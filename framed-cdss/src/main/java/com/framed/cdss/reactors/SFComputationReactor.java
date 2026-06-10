package com.framed.cdss.reactors;

import com.framed.arn.Reactor;
import com.framed.core.EventBus;

import java.util.List;
import java.util.Map;

import static com.framed.arn.RuleUtils.publishResult;

public class SFComputationReactor extends Reactor {
  private final String spo2Channel;
  private final String fio2Channel;


  public SFComputationReactor(
          EventBus eventBus,
          String id,
          String spo2Channel,
          String fio2Channel,
          String outputChannel,
          boolean atomic) {
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
      ),
      atomic
    );
    this.spo2Channel = spo2Channel;
    this.fio2Channel = fio2Channel;
  }

  @Override
  public void reactionFunction(Map<String, Object> latestValues) {
    Object rawSpo2 = latestValues.get(spo2Channel);
    Object rawFio2 = latestValues.get(fio2Channel);
    if (rawSpo2 == null || rawFio2 == null) {
      return;
    }
    if (
      rawSpo2 instanceof Number spo2
        &&  rawFio2 instanceof Number fio2
    ){
      float sf = 100 * spo2.floatValue() / fio2.floatValue();
      publishResult(eventBus, sf, id, outputChannels, lastLogicalFireTs);
    }
  }
}