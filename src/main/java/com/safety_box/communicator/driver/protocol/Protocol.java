package com.safety_box.communicator.driver.protocol;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.VerticleBase;

import java.io.IOException;

public abstract class Protocol<T> extends VerticleBase {
  protected String deviceID;
  public abstract void connect();
  public abstract void disconnect();
  public abstract T readData();
  public abstract void writeData(T data) throws IOException;
}
