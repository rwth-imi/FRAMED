package com.safety_box.orchestrator;

import com.safety_box.core.Peer;
import com.safety_box.core.SocketEventBus;
import com.safety_box.core.TCPTransport;
import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

  public static void main(String[] args) {
    // start all configured device protocol handlers
    JSONObject config;
    try {
      config = ConfigLoader.loadConfig("config.json");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }


    TCPTransport tcp = new TCPTransport(4999);

    SocketEventBus eventBus = new SocketEventBus(tcp);

    Manager manager = new Manager(config, eventBus);
    for (String key : config.keySet()) {
      manager.instantiate(key);
    }
  }
}

