package com.framed.orchestrator;

import com.framed.core.remote.*;
import com.framed.core.utils.DispatchMode;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;


/**
 * Config-driven launcher for a FRAMED deployment.
 *
 * <p>On startup it reads {@code config/services.json} and {@code config/communication.json},
 * builds the {@link Transport} and {@link SocketEventBus} described by the communication
 * config, connects any configured peers, then instantiates the configured services
 * (dispatchers, devices, writers, parsers and reactors) via the {@code Manager}. After
 * instantiation it runs all registered deployment validators, installs a shutdown hook to
 * stop the event bus and managed services cleanly, and blocks the main thread until the
 * process is terminated.</p>
 */
public class Main {
  private static final Logger logger = Logger.getLogger(Main.class.getName());

  /** This class is not meant to be instantiated; it only exposes {@link #main(String[])}. */
  private Main() {}

  /**
   * Loads the configuration, builds the runtime, instantiates services and blocks until shutdown.
   *
   * @param args command-line arguments (currently unused)
   * @throws IOException if the configuration files cannot be read
   */
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
    SocketEventBus eventBus = new SocketEventBus(transport, DispatchMode.PER_HANDLER);

    // add configured peers (remote SocketEventBus instances)
    if (communicationConfig.has("peers")) {
      for (Object peer : communicationConfig.getJSONArray("peers")) {
        JSONObject peerConfig = (JSONObject) peer;
        eventBus.addPeer(new Peer(peerConfig.getString("host"), peerConfig.getInt("port")));
      }
    }

    // instantiate all configured service, including DFCN reactors
    Manager manager = new Manager(servicesConfigs, eventBus);
    if (servicesConfigs.has("Dispatchers")) {
      manager.instantiate("Dispatchers");
    }
    if (servicesConfigs.has("Devices")) {
      manager.instantiate("Devices");
    }
    if (servicesConfigs.has("Writers")) {
      manager.instantiate("Writers");
    }
    if (servicesConfigs.has("Parsers")) {
      manager.instantiate("Parsers");
    }
    if (servicesConfigs.has("Reactors")) {
      manager.instantiate("Reactors");
    }

    //for (String key : servicesConfigs.keySet()) {
    //  manager.instantiate(key);
    //}

    // run all registered deployment validators (e.g. the CDSS acyclic-reactor-network check):
    manager.validate();


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

