package com.framed.orchestrator;

import com.framed.core.EventBus;
import com.framed.core.Service;

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
      JSONObject serviceConfig = (JSONObject) clazz;
      try {
        Service service = Factory.instantiate(serviceConfig, eventBus);
        this.instances.put(serviceConfig.getString("id"), service);
      } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate protocol for device: " +
          serviceConfig.getString("id"), e);
      }
      System.out.println("Successfully instantiated " + clazz);
    }
  }

  public void stop(String id) {
    Service service = this.instances.get(id);
    service.stop();
  }

  public void stopAll() {
    for (Service service : instances.values()) {
      service.stop();
    }
  }
}
