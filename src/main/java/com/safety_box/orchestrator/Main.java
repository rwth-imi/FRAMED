package com.safety_box.orchestrator;

import com.safety_box.core.SocketEventBus;
import org.json.JSONObject;

public class Main {

  public static void main(String[] args) {
    // start all configured device protocol handlers
    JSONObject config;
    try {
      config = ConfigLoader.loadConfig("config.json");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    SocketEventBus eventBus = new SocketEventBus(5000, "localhost");
    Manager manager = new Manager(config, eventBus);
    for (String key : config.keySet()) {
      manager.instantiate(key);
    }
  }
}

