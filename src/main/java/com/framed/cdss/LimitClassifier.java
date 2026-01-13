package com.framed.cdss;

import com.framed.core.EventBus;
import kotlin.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class LimitClassifier extends Actor{
  Map<String, Pair<Integer, Integer>> limits;
  protected LimitClassifier(EventBus eventBus, List<List<String>> firingRules, List<String> inputChannels, List<String> outputChannels, Map<String, Pair<Integer, Integer>> limits) {
    super(eventBus, firingRules, inputChannels, outputChannels);
    // validate that input channels are equal to limited channels or at least that limited channels are a subset of input channels.

    if (!Set.copyOf(inputChannels).equals(limits.keySet())) {
      logger.warning("InputChannels are not equal to limited channels.");
      if (!Set.copyOf(inputChannels).containsAll(limits.keySet())) {
        throw new IllegalArgumentException("Limited channels are not a subset of input channels.");
      }
    }
    this.limits = limits;
  }

  public Map<String, Integer> checkLimits(){
    Map<String, Integer> alarmStates = new HashMap<>();
    for(String channel: this.getInputChannels()){
      int value = (int) this.inputBuffer.get(channel);
      if(value < limits.get(channel).component1()){
        alarmStates.put(channel, -1);
      } else if (value > limits.get(channel).component2()) {
        alarmStates.put(channel, 1);
      }else {
        alarmStates.put(channel, 0);
      }
    }
    return alarmStates;
  }
}
