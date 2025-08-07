package com.safety_box.communicator;

import com.safety_box.communicator.manager.DeviceManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;

public class MainVerticle extends AbstractVerticle {

  private DeviceManager deviceManager;

  @Override
  public void start(Promise<Void> startPromise) {
    try {
      this.deviceManager = new DeviceManager();
      deviceManager.instantiateProtocols();
      startPromise.complete();
    } catch (Exception e) {
      startPromise.fail(e);
    }
  }


}
