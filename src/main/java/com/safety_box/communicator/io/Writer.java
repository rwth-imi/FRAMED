package com.safety_box.communicator.io;

import io.vertx.core.Context;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Path;

public abstract class Writer<T> extends VerticleBase {
  protected JsonObject config;
  protected Path path;
  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    this.vertx = vertx;
    this.config = context.config();
    this.path = Path.of(config.getString("path"));
  }


  public abstract void write(T data) throws IOException;
}
