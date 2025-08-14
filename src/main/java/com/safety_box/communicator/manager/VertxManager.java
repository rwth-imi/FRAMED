package com.safety_box.communicator.manager;

import com.safety_box.communicator.io.ConfigLoader;
import io.vertx.core.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;

public class VertxManager {
  private final JsonArray verticles;
  private final Vertx vertx;
  private final Map<String, String> deploymentIDs = new HashMap<>();

  public VertxManager(Vertx vertx, String vertxType) {
    this.vertx = vertx;
    JsonObject config;
    try {
      config = ConfigLoader.loadConfig("config.json");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    this.verticles = config.getJsonArray(vertxType);
  }

  public void startAll(String classKey, String idKey) throws Exception {
    for (Object deviceObj : verticles) {
      JsonObject vertxConfig = (JsonObject) deviceObj;
      String verticleClassName = vertxConfig.getString(classKey);
      DeploymentOptions options = new DeploymentOptions().setConfig(vertxConfig);

      Future<String> deploymentFuture = vertx.deployVerticle(verticleClassName, options);

      deploymentFuture.onSuccess(deploymentID -> {
        deploymentIDs.put(vertxConfig.getString(idKey), deploymentID);
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

