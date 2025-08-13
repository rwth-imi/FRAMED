package com.safety_box.communicator.manager;

import com.safety_box.communicator.io.ConfigLoader;
import io.vertx.core.*;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.deployment.Deployment;
import io.vertx.core.internal.deployment.DeploymentContext;
import io.vertx.core.internal.deployment.DeploymentManager;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;

public class DeviceManager {
  private final JsonArray devices;
  private final Vertx vertx;
  private final Map<String, String> deploymentIDs = new HashMap<>();

  public DeviceManager(Vertx vertx) {
    this.vertx = vertx;
    JsonObject config;
    try {
      config = ConfigLoader.loadConfig("config.json");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    this.devices = config.getJsonArray("devices");
  }

  public void startAll() throws Exception {
    for (Object deviceObj : devices) {
      JsonObject deviceConfig = (JsonObject) deviceObj;
      String verticleClassName = deviceConfig.getString("driver");
      DeploymentOptions options = new DeploymentOptions().setConfig(deviceConfig);

      Future<String> deploymentFuture = vertx.deployVerticle(verticleClassName, options);

      deploymentFuture.onSuccess(deploymentID -> {
        deploymentIDs.put(deviceConfig.getString("deviceID"), deploymentID);
        System.out.println("Successfully deployed: " + verticleClassName);
      }).onFailure(err -> {
        System.err.println("Failed to deploy " + verticleClassName + ": " + err.getMessage());
      });
    }
  }


  public void stopAll() {
    for (String deviceID : deploymentIDs.keySet()) {
      vertx.undeploy(deploymentIDs.get(deviceID));
    }
  }

}

