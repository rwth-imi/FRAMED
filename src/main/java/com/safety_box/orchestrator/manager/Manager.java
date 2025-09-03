package com.safety_box.orchestrator.manager;

import com.safety_box.core.Service;
import com.safety_box.core.EventBus;

import org.json.JSONArray;
import org.json.JSONObject;


import java.util.HashMap;
import java.util.Map;

public class Manager {
  private final EventBus eventBus;
  Map<String, Service> instances = new HashMap<>();
  JSONObject config;

  public Manager(JSONObject config, EventBus eventBus) {
    this.config = config;
    this.eventBus = eventBus;
  }

  public void instantiate(String classType) {
    JSONArray classes = config.getJSONArray(classType);
    for (Object clazz : classes) {
      JSONObject stoppableConfig = (JSONObject) clazz;
      try {
        Service service = Factory.instantiate(stoppableConfig, eventBus);
        this.instances.put(stoppableConfig.getString("id"), service);
      } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate protocol for device: " +
          stoppableConfig.getString("id"), e);
      }
      System.out.println("Successfully instantiated " + clazz);
    }
  }

  public void stop(String id) {
    Service stoppable = this.instances.get(id);
    stoppable.stop();
  }

  public void stopAll() {
    for (Service stoppable : instances.values()) {
      stoppable.stop();
    }
  }
}
