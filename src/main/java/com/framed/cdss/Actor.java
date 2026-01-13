
package com.framed.cdss;

import com.framed.core.EventBus;
import com.framed.core.Service;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class Actor extends Service {
  // assumption: one eventbus address per observed attribute.
  // May be suboptimal: some data may be synced with the same rate.
  // However, this way, we would trigger one call of the msg handler for each attribute.
  // Make sure to handle this via FiringRules
  // improvement idea: one address per device, multiple channels possible per message.
  final List<List<String>> firingRules;
  final List<String> inputChannels;
  final List<String> outputChannels;

  // this is a consumed at most once logic. Other settings would be possible.
  final Map<String, Object> inputBuffer = new ConcurrentHashMap<>();
  private final Map<String, Boolean> updatedFlags = new ConcurrentHashMap<>();

  protected Actor(EventBus eventBus, List<List<String>> firingRules, List<String> inputChannels, List<String> outputChannels) {
    super(eventBus);
    this.firingRules = firingRules;
    this.inputChannels = inputChannels;
    this.outputChannels = outputChannels;

    for (String inChannel : this.inputChannels) {
      this.eventBus.register(inChannel, msg -> {
        JSONObject msgJson = (JSONObject) msg;
        Object value = msgJson.get("value");
        //save the most current value of the input channel
        inputBuffer.put(inChannel, value);
        //set the updated flag to true for the firing rule
        updatedFlags.put(inChannel, true);
        checkFiringRules();
      });
    }
  }

  private void checkFiringRules() {
    for (List<String> rule : firingRules) {
      // if all channels listed in a particular firing rule were updated, call the fireFunction with the latest observations
      boolean allUpdated = rule.stream().allMatch(ch -> updatedFlags.getOrDefault(ch, false));
      if (allUpdated) {
        fireFunction(inputBuffer);
        rule.forEach(ch -> updatedFlags.put(ch, false));
      }
    }
  }

  public abstract void fireFunction(Map<String, Object> latestValues);

  public List<String> getInputChannels() {
    return inputChannels;
  }

  public List<String> getOutputChannels() {
    return outputChannels;
  }
}
