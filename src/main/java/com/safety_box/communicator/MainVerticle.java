package com.safety_box.communicator;

import com.safety_box.communicator.manager.DeviceManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;

public class MainVerticle extends VerticleBase {
  private DeviceManager deviceManager;

  @Override
  public Future<?> start() throws Exception {
    deviceManager = new DeviceManager(vertx);
    deviceManager.startAll();
    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    deviceManager.stopAll();
    return super.stop();
  }
}

