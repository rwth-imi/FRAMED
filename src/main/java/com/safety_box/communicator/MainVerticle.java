package com.safety_box.communicator;

import com.safety_box.communicator.manager.VertxManager;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

public class MainVerticle extends VerticleBase {
  private VertxManager deviceManager;
  private VertxManager writerManager;
  private VertxManager parserManager;
  @Override
  public Future<?> start() throws Exception {
    // start all configured device protocol handlers

    deviceManager = new VertxManager(vertx, "devices");
    deviceManager.startAll("driver", "deviceID");

    // start all configured writers
    writerManager = new VertxManager(vertx, "writers");
    writerManager.startAll("writer", "writerID");

    // start all configured parsers
    parserManager = new VertxManager(vertx, "parsers");
    parserManager.startAll("parser", "parserID");

    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    deviceManager.stopAll();
    writerManager.stopAll();

    return super.stop();
  }
}

