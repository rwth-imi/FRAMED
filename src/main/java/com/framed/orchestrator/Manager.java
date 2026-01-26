package com.framed.orchestrator;

import com.framed.cdss.Actor;
import com.framed.cdss.DFCN;
import com.framed.core.EventBus;
import com.framed.core.Service;

import org.json.JSONArray;
import org.json.JSONObject;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


/** The {@code Manager} class is responsible for orchestrating the lifecycle of {@link Service} instances
 * based on a JSON configuration. It uses an {@link EventBus} for inter-service communication and provides
 * methods to instantiate, stop, and manage services dynamically.
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Instantiates services from JSON configuration using {@link Factory}.</li>
 *   <li>Maintains a registry of active service instances keyed by their IDs.</li>
 *   <li>Supports stopping individual services or all services at once.</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * JSONObject config = new JSONObject("""
 * {
 *   "devices": [
 *     { "id": "sensor1", "type": "TemperatureSensor" },
 *     { "id": "sensor2", "type": "HumiditySensor" }
 *   ]
 * }
 * """);
 *
 * EventBus eventBus = new SocketEventBus(new TCPTransport(8080));
 * Manager manager = new Manager(config, eventBus);
 *
 * // Instantiate all services under "devices"
 * manager.instantiate("devices");
 *
 * // Stop a specific service
 * manager.stop("sensor1");
 *
 * // Stop all services
 * manager.stopAll();
 * }</pre>
 *
 * <p><b>Threading Model:</b> All operations are synchronous and blocking. Service instantiation and
 * stopping occur on the calling thread.</p>
 *
 * <p><b>Note:</b> Ensure that the provided configuration contains valid service definitions and that
 * {@link Factory} supports the specified types (use the validators in {@link ConfigLoader}.</p>
 */
public class Manager {
  private final EventBus eventBus;
  private final Logger logger;
  Map<String, Service> instances = new HashMap<>();
  JSONObject config;

  /**
   * Creates a new {@code Manager} with the given configuration and event bus.
   *
   * @param config    the JSON configuration containing service definitions
   * @param eventBus  the event bus for inter-service communication
   */
  public Manager(JSONObject config, EventBus eventBus) {
    this.config = config;
    this.eventBus = eventBus;
    this.logger = Logger.getLogger(getClass().getName());
  }

  /**
   * Instantiates all services of the specified type from the configuration.
   * <p>The configuration must contain an array under the given {@code classType} key,
   * where each element is a JSON object representing a service definition.</p>
   *
   * @param classType the key in the configuration representing the service type group
   */
  public void instantiate(String classType) {
    JSONArray classes = config.getJSONArray(classType);
    for (Object clazz : classes) {
      JSONObject serviceConfig = (JSONObject) clazz;
      try {
        Service service = Factory.instantiate(serviceConfig, eventBus);
        this.instances.put(serviceConfig.getString("id"), service);
      } catch (Exception e) {
        logger.severe("Failed to instantiate Service: %s%s".formatted(serviceConfig.getString("id"), e));
      }
      logger.info("Successfully instantiated %s: %s".formatted(classType, serviceConfig.getString("id")));
    }
  }

  public void validateDFCN() {
    List<Actor> actorList = new ArrayList<>();
    for (Service service: this.instances.values()) {
      if (service instanceof Actor actor) {
        actorList.add(actor);
      }
    }
    new DFCN(actorList);
  }

  /**
   * Stops the service with the specified ID.
   *
   * @param id the ID of the service to stop
   */
  public void stop(String id) {
    Service service = this.instances.get(id);
    service.stop();
  }

  /**
   * Stops all active services managed by this instance.
   */
  public void stopAll() {
    for (Service service : instances.values()) {
      service.stop();
    }
  }
}
