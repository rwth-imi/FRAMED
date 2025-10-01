package com.safety_box.orchestrator.manager;

import com.safety_box.core.EventBusInterface;
import com.safety_box.core.Service;
import com.safety_box.core.EventBus;

import org.json.JSONArray;
import org.json.JSONObject;


import java.util.HashMap;
import java.util.Map;

public class Manager {
  private final EventBusInterface eventBus;
  Map<String, Service> instances = new HashMap<>();
  JSONObject config;

  public Manager(JSONObject config, EventBusInterface eventBus) {
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
