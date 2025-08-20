package com.safety_box.streamer.dispatcher;

import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import com.safety_box.streamer.parser.Parser;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

import java.nio.charset.StandardCharsets;

public abstract class LocalDispatcher extends Dispatcher {
  private JsonObject config;

  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    this.vertx = vertx;
    this.config = context.config();


  }

  @Override
  public Future<?> start() throws Exception {
    NetClient client = vertx.createNetClient();
    Future<NetSocket> clientFuture = client.connect(1111, "localhost");
    JsonArray devices =  config.getJsonArray("devices");
    for (Object deviceObj : devices) {
      String deviceID = deviceObj.toString();
      vertx.eventBus().consumer(deviceID+".parsed", msg -> {
        try {
          JsonObject body = (JsonObject) msg.body();
          body.put("deviceID", deviceID);
          DataPoint<?> dp = Parser.parse((JsonObject) msg.body());
          push(dp);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }
    return super.start();
  }

  public abstract void push(DataPoint<?> dataPoint);

  public abstract void pushBatch(TimeSeries timeSeries);
}
