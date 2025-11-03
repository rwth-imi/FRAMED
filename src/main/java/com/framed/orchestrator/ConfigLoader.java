package com.framed.orchestrator;


import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

public class ConfigLoader {

  private static final Logger logger = Logger.getLogger(ConfigLoader.class.getName());

  public static JSONObject loadConfig(String path) throws Exception {
    String content = new String(Files.readAllBytes(Paths.get(path)));
    return new JSONObject(content);
  }

  public static void validateServiceConfigs(JSONObject instantiation_config) throws Exception {
    boolean valid = true;
    for (String key : instantiation_config.keySet()) {
      JSONArray classes = instantiation_config.getJSONArray(key);
      for (Object clazz : classes) {
        JSONObject serviceConfig = (JSONObject) clazz;
        if (!serviceConfig.has("class")) {
          valid = false;
          logger.warning("Service object config" + serviceConfig + "does not have attribute 'class'");
        }
        if (!serviceConfig.has("id")) {
          valid = false;
          logger.warning("Service object config" + serviceConfig + "does not have attribute 'class'");
        }
      }
    }
    if (!valid) {
      throw new Exception("Invalid service config");
    }
  }

  public static void validateCommunicationConfigs(JSONObject communicationConfig) throws Exception {
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
      throw new Exception("Invalid communication config");
    }
  }
}

