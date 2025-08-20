package com.safety_box.streamer.dispatcher;

import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;

public abstract class Dispatcher extends VerticleBase {
  private JsonObject config;

  public abstract void push(DataPoint<?> dataPoint);

  public abstract void pushBatch(TimeSeries timeSeries);
}
