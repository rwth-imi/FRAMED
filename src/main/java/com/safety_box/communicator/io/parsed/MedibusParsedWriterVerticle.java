package com.safety_box.communicator.io.parsed;

import com.safety_box.communicator.io.WriterVerticle;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class MedibusParsedWriterVerticle extends WriterVerticle<JsonObject> {
  private long timeOnStart;
  @Override
  public void init(Vertx vertx, Context context) {
    this.timeOnStart =  System.currentTimeMillis();
    super.init(vertx, context);
  }

  @Override
  public void write(JsonObject data, String deviceName) throws IOException {
    if (data.getBoolean("realTime")) {
      Path filePath = path.resolve(deviceName + "_" + timeOnStart + "_parsed_RT.jsonl");
      Files.write(filePath, data.encode().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      Files.write(filePath, "\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } else {
      Path filePath = path.resolve(deviceName + "_" + timeOnStart + "_parsed_SD.jsonl");
      Files.write(filePath, data.encode().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      Files.write(filePath, "\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

  }

  @Override
  public Future<?> start() throws Exception {
    JsonArray devices = config.getJsonArray("devices");
    for  (Object device : devices) {
      String deviceName = (String) device;
      vertx.eventBus().consumer(deviceName+".addresses", msg -> {
        vertx.eventBus().consumer(
          (String) msg.body(), msg_ ->{
            handleEventBus(msg_, deviceName);
          }
        );
      });
    }
    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    return super.stop();
  }

  public void handleEventBus(Message<Object> msg, String deviceName) {
    JsonObject jsonMsg = (JsonObject) msg.body();
    try {
      write(jsonMsg, deviceName);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
