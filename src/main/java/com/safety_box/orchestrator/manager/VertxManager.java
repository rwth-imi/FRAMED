package com.safety_box.orchestrator.manager;

import io.vertx.core.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;

public class VertxManager {
  private final Vertx vertx;
  private final Map<String, String> deploymentIDs = new HashMap<>();
  private final JsonObject config;
  public VertxManager(Vertx vertx, JsonObject config) {
    this.vertx = vertx;
    this.config = config;
  }

  public void startAll(String vertxType) {
    JsonArray verticles = config.getJsonArray(vertxType);
    for (Object verticleObj : verticles) {
      JsonObject vertxConfig = (JsonObject) verticleObj;
      String verticleClassName = vertxConfig.getString("class");
      DeploymentOptions options = new DeploymentOptions().setConfig(vertxConfig);

      Future<String> deploymentFuture = vertx.deployVerticle(verticleClassName, options);

      deploymentFuture.onSuccess(deploymentID -> {
        deploymentIDs.put(vertxConfig.getString("id"), deploymentID);
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

