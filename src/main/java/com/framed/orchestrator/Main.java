package com.framed.orchestrator;

import com.framed.core.remote.*;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;


public class Main {
  private static final Logger logger = Logger.getLogger(Main.class.getName());

  public static void main(String[] args) throws IOException {
    // start all configured device protocol handlers
    JSONObject servicesConfigs;
    JSONObject communicationConfig;


    try {
      servicesConfigs = ConfigLoader.loadConfig("services.json");
      ConfigLoader.validateServiceConfigs(servicesConfigs);
      communicationConfig = ConfigLoader.loadConfig("communication.json");
      ConfigLoader.validateCommunicationConfigs(communicationConfig);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    Transport transport;
    int port = communicationConfig.getInt("port");

    if (communicationConfig.getString("type").equals("TCP")) {
      transport = new NioTcpTransport(port);
    } else if (communicationConfig.getString("type").equals("UDP")) {
      transport = new NioUdpTransport(port);
    } else {
      throw new RuntimeException("Invalid communication type config");
    }
    SocketEventBus eventBus = new SocketEventBus(transport);
    if (communicationConfig.has("peers")) {
      for (Object peer : communicationConfig.getJSONArray("peers")) {
        JSONObject peerConfig = (JSONObject) peer;
        eventBus.addPeer(new Peer(peerConfig.getString("host"), peerConfig.getInt("port")));
      }
    }


    Manager manager = new Manager(servicesConfigs, eventBus);
    for (String key : servicesConfigs.keySet()) {
      manager.instantiate(key);
    }

    // Add shutdown hook to stop all services cleanly
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        manager.stopAll();
        eventBus.shutdown();
      } catch (Exception e) {
        // log or print error during shutdown — avoid throwing from shutdown hook
        logger.severe("Error stopping manager: " + e.getMessage());
      }
    }));

    // Keep the main thread alive
    try {
      new CountDownLatch(1).await(); // Blocks forever
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

  }
}

