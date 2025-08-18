package com.safety_box.streamer.dispatcher;

import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import com.safety_box.streamer.parser.Parser;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public abstract class Dispatcher extends VerticleBase {
  private JsonObject config;

  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    this.vertx = vertx;
    this.config = context.config();
  }

  @Override
  public Future<?> start() throws Exception {
    vertx.eventBus().consumer("remote.data", msg -> {
      try {
        DataPoint<?> dp = Parser.parse((JsonObject) msg);
        push(dp);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    return super.start();
  }

  public abstract void push(DataPoint<?> dataPoint);

  public abstract void pushBatch(TimeSeries timeSeries);
}
