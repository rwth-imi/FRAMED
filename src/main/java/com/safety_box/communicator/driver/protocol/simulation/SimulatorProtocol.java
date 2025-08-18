package com.safety_box.communicator.driver.protocol.simulation;

import io.vertx.core.Future;

import com.safety_box.communicator.driver.protocol.Protocol;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

public class SimulatorProtocol extends Protocol<String> {

  @Override
  public Future<?> start() throws Exception {
    JsonObject config = this.config();
    this.deviceID = config.getString("deviceID");
    return super.start();
  }

  @Override
  public void connect() {

  }

  @Override
  public void disconnect() {

  }

}
