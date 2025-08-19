package com.safety_box.orchestrator;

import com.safety_box.orchestrator.io.ConfigLoader;
import com.safety_box.orchestrator.manager.VertxManager;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge;

public class MainVerticle extends VerticleBase {
  private VertxManager vertxManager;
  private  TcpEventBusBridge bridge;
  @Override
  public Future<?> start() throws Exception {
    // start all configured device protocol handlers
    JsonObject config = ConfigLoader.loadConfig("config.json");

    vertxManager = new VertxManager(vertx, config);
    for (String key:config.getMap().keySet()) {
      vertxManager.startAll(key);
    }

    BridgeOptions bridgeOptions = new BridgeOptions()
      .addInboundPermitted(new PermittedOptions().setAddressRegex(".*"))
      .addOutboundPermitted(new PermittedOptions().setAddressRegex(".*"));

    bridge = TcpEventBusBridge.create(vertx, bridgeOptions);
    Future<TcpEventBusBridge> bridgeFuture = bridge.listen(3030);
    bridgeFuture.onSuccess(event -> {
      System.out.println("Bridge started");
    }).onFailure(event -> {
      event.printStackTrace();
    });

    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    System.out.println("Stopping Verticle");
    bridge.close();
    vertxManager.stopAll();
    return super.stop();
  }
}

