package com.framed.orchestrator;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * The {@code ConfigLoader} class provides utility methods for loading and validating
 * JSON-based configuration files used by the orchestrator.
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Loads configuration from a file path into a {@link JSONObject}.</li>
 *   <li>Validates service instantiation configurations for required attributes.</li>
 *   <li>Validates communication configurations for required attributes.</li>
 * </ul>
 *
 * <p><b>Validation Rules:</b>
 * <ul>
 *   <li>Service configs must contain {@code class} and {@code id} attributes.</li>
 *   <li>Communication configs must contain {@code port} and {@code type} attributes.</li>
 *   <li>{@code peers} attribute in communication config is optional.</li>
 * </ul>
 */

public class ConfigLoader {

  private static final Logger logger = Logger.getLogger(ConfigLoader.class.getName());

  private ConfigLoader() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Loads a JSON configuration file from the specified path.
   *
   * @param path the file system path to the configuration file
   * @return a {@link JSONObject} representing the configuration
   * @throws IOException if the file cannot be read or parsed
   */
  public static JSONObject loadConfig(String path) throws IOException {
    String content = new String(Files.readAllBytes(Paths.get(path)));
    return new JSONObject(content);
  }

  /**
   * Validates service instantiation configurations.
   * <p>Each service object must contain {@code class} and {@code id} attributes.</p>
   *
   * @param serviceConfig the JSON object containing service definitions
   */
  public static void validateServiceConfigs(JSONObject serviceConfig) {
    boolean valid = true;
    for (String key : serviceConfig.keySet()) {
      JSONArray classes = serviceConfig.getJSONArray(key);
      for (Object clazz : classes) {
        JSONObject classConfig = (JSONObject) clazz;
        if (!classConfig.has("class")) {
          valid = false;
          logger.warning("Service object config" + classConfig + "does not have attribute 'class'");
        }
        if (!classConfig.has("id")) {
          valid = false;
          logger.warning("Service object config" + classConfig + "does not have attribute 'class'");
        }
      }
    }
    if (!valid) {
      throw new IllegalArgumentException("Invalid service config");
    }
  }


  /**
   * Validates communication configurations.
   * <p>The configuration must contain {@code port} and {@code type} attributes.
   * The {@code peers} attribute is optional.</p>
   *
   * @param communicationConfig the JSON object representing communication settings
   */
  public static void validateCommunicationConfigs(JSONObject communicationConfig) {
    boolean valid = true;
    if (!communicationConfig.has("port")) {
      valid = false;
      logger.warning("Communication config" + communicationConfig + "does not have attribute 'port'");
    }
    if (!communicationConfig.has("type")) {
      valid = false;
      logger.warning("Communication config" + communicationConfig + "does not have attribute 'type'");
    }
    if (!communicationConfig.has("peers")) {
      logger.info(communicationConfig + "does not have 'peers'");
    }
    if (!valid) {
      throw new IllegalArgumentException("Invalid communication config");
    }
  }
}

