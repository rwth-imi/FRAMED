package com.framed.cdss;

import com.framed.core.EventBus;
import com.framed.core.Service;

import java.util.List;

public abstract class Actor extends Service {
  private final List<List<String>> firingRules;
  private final List<String> inputChannels;
  private final List<String> outputChannels;

  protected Actor(EventBus eventBus, List<List<String>> firingRules, List<String> inputChannels, List<String> outputChannels) {
    super(eventBus);
    this.firingRules = firingRules;
    this.inputChannels = inputChannels;
    this.outputChannels = outputChannels;
  }

  @Override
  public void stop() {

  }

  public abstract void process();

  public List<String> getInputChannels() {
    return inputChannels;
  }

  public List<String> getOutputChannels() {
    return outputChannels;
  }
}
