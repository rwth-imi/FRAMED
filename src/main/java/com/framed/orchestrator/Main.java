package com.framed.orchestrator;

import com.framed.cdss.casestudy.SFActor;
import com.framed.core.remote.*;
import com.framed.core.utils.DispatchMode;
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


    // load and validate communication and service configs
    try {
      servicesConfigs = ConfigLoader.loadConfig("config/services.json");
      ConfigLoader.validateServiceConfigs(servicesConfigs);
      communicationConfig = ConfigLoader.loadConfig("config/communication.json");
      ConfigLoader.validateCommunicationConfigs(communicationConfig);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }

    //initialize EventBus by config, using TCP or UDP remote transportation protocols
    Transport transport;
    int port = communicationConfig.getInt("port");

    if (communicationConfig.getString("type").equals("TCP")) {
      transport = new NioTcpTransport(port);
    } else if (communicationConfig.getString("type").equals("UDP")) {
      transport = new NioUdpTransport(port);
    } else {
      logger.warning("Invalid communication type config, using blocking TCP instead...");
      transport = new TCPTransport(port);
    }
    SocketEventBus eventBus = new SocketEventBus(transport, DispatchMode.PARALLEL);

    // add configured peers (remote SocketEventBus instances)
    if (communicationConfig.has("peers")) {
      for (Object peer : communicationConfig.getJSONArray("peers")) {
        JSONObject peerConfig = (JSONObject) peer;
        eventBus.addPeer(new Peer(peerConfig.getString("host"), peerConfig.getInt("port")));
      }
    }

    // instantiate all configured service, including DFCN actors
    Manager manager = new Manager(servicesConfigs, eventBus);
    for (String key : servicesConfigs.keySet()) {
      manager.instantiate(key);
    }

    // validate the DFCN properties by successfully instantiating the DAG:
    manager.validateDFCN();


      // Add shutdown hook to stop all services cleanly
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        logger.info("Shutting down EventBus...");
        eventBus.shutdown();
        logger.info("Shutting down managed services...");
        manager.stopAll();
      } catch (Exception e) {
        // log or print error during shutdown — avoid throwing from shutdown hook
        logger.severe("Error stopping manager: %s".formatted(e.getMessage()));
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

