package com.safety_box.streamer;

import com.safety_box.streamer.dispatcher.influx.InfluxDispatcherVerticle;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.parser.Parser;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.bridge.BridgeOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.eventbus.bridge.tcp.TcpEventBusBridge;

import java.nio.charset.StandardCharsets;

import static io.vertx.ext.eventbus.bridge.tcp.impl.protocol.FrameHelper.sendFrame;

public class MainVerticle extends VerticleBase {

  @Override
  public Future<?> start() throws Exception {
    vertx.deployVerticle(InfluxDispatcherVerticle.class.getName());
    NetClient client = vertx.createNetClient();
    Future<NetSocket> clientFuture = client.connect(8080, "localhost");
    clientFuture.onSuccess(socket -> {

      String deviceID = "Oxylog-3000-Plus-00";
      // Register to receive messages from a specific address
      JsonObject register = new JsonObject()
        .put("type", "register")
        .put("address", "parsed_Oxylog-3000-Plus-00");

      sendFrame(socket, register);

      socket.handler(buffer -> {
        JsonObject msg = bufferToJson(buffer);
        System.out.println("Received: " + msg.encodePrettily());
        JsonObject body = msg.getJsonObject("body");
        body.put("deviceID", deviceID);
        vertx.eventBus().publish("remote.data", body);

      });
    }).onFailure(res -> {
      System.out.println("Failed to connect to remote host");
      res.printStackTrace();
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

    int length = buffer.getInt(0);
    byte[] jsonBytes = buffer.getBytes(4, 4 + length);
    String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);

    return new JsonObject(jsonString);
  }


}
