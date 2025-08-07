
package com.safety_box.communicator.manager;

import com.safety_box.communicator.driver.protocol.Protocol;
import com.safety_box.communicator.io.ConfigLoader;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class DeviceManager {
  JsonArray devices;
  Map<String, Protocol<?>> protocols = new HashMap<>();
  String configPath = "config.json";

  public DeviceManager() {
    JsonObject config;
    try {
      config = ConfigLoader.loadConfig(this.configPath);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load config", e);
    }
    this.devices = config.getJsonArray("devices");
  }

  public void instantiateProtocols() {
    for (Object device : devices) {
      JsonObject deviceConfig = (JsonObject) device;
      try {
        Protocol<?> protocol = (Protocol<?>) DriverFactory.instantiate(deviceConfig);
        this.protocols.put(deviceConfig.getString("deviceID"), protocol);
      } catch (Exception e) {
        throw new RuntimeException("Failed to instantiate protocol for device: " +
          deviceConfig.getString("deviceID"), e);
      }
    }
  }


  public void stop(String device) {

  }

  public void start(String device) {}

  public void stopAll() {

  }

  public void startAll() {

  }

}
