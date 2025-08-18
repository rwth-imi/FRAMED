package com.safety_box.communicator.driver.parser;

import io.vertx.core.Context;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public abstract class ParserVerticle<T> extends VerticleBase {
  protected JsonObject config;
  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    this.vertx = vertx;
    this.config = context.config();
  }
  public abstract void parse(T message, String deviceName);
}
