package com.safety_box.orchestrator;

import com.safety_box.core.EventBus;
import com.safety_box.orchestrator.io.ConfigLoader;
import com.safety_box.orchestrator.manager.Manager;
import org.json.JSONObject;

import java.util.concurrent.Executors;

public class Main {

  public static void main(String[] args) {
    // start all configured device protocol handlers
    JSONObject config;
    try {
      config = ConfigLoader.loadConfig("config.json");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    EventBus eventBus = new EventBus(Executors.newCachedThreadPool());
    Manager manager = new Manager(config, eventBus);
    for (String key : config.keySet()) {
      manager.instantiate(key);
    }
  }
}

