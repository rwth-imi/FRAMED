package com.safety_box.streamer.dispatcher;

import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import com.safety_box.streamer.parser.Parser;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

import java.nio.charset.StandardCharsets;

public abstract class Dispatcher extends VerticleBase {
  private JsonObject config;

  @Override
  public void init(Vertx vertx, Context context) {
    NetClient client = vertx.createNetClient();
    Future<NetSocket> clientFuture = client.connect(3030, "localhost");
    config = context.config();
    JsonArray devices =  config.getJsonArray("devices");
    for (Object deviceObj : devices) {
      String deviceID = deviceObj.toString();
      clientFuture.onSuccess(socket -> {
        // Register to receive messages from a specific address
        JsonObject register = new JsonObject()
          .put("type", "register")
          .put("address", deviceID+".parsed");

        sendFrame(socket, register);

        socket.handler(buffer -> {
          JsonObject msg = bufferToJson(buffer);
          System.out.println("Received: " + msg.encodePrettily());
          JsonObject body = msg.getJsonObject("body");
          body.put("deviceID", deviceID);
          try {
            DataPoint<?> dp = Parser.parse(body);
            push(dp);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }).onFailure(res -> {
        System.out.println("Failed to connect to remote host");
        res.printStackTrace();
      });
    }
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
  public void sendFrame(NetSocket socket, JsonObject frame) {
    Buffer buffer = Buffer.buffer();
    String json = frame.encode();
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
    int length = jsonBytes.length;

    buffer.appendInt(length); // 4-byte length prefix
    buffer.appendBytes(jsonBytes);

    socket.write(buffer);
  }

  public JsonObject bufferToJson(Buffer buffer) {
    if (buffer.length() < 4) {
      throw new IllegalArgumentException("Buffer too short to contain length prefix");
    }
    String jsonString = "";
    try {
      int length = buffer.getInt(0);
      byte[] jsonBytes = buffer.getBytes(4, 4 + length);
      jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      System.out.println(buffer);
      throw new RuntimeException(e);
    }

    return new JsonObject(jsonString);

  }

  public abstract void push(DataPoint<?> dataPoint);

  public abstract void pushBatch(TimeSeries timeSeries);
}
