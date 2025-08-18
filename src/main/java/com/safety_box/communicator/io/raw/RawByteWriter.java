package com.safety_box.communicator.io.raw;

import com.safety_box.communicator.io.Writer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

public class RawByteWriter extends Writer<byte[]> {
  private long timeOnStart;
  public void init(Vertx vertx, Context context) {
    this.timeOnStart =  System.currentTimeMillis();
    super.init(vertx, context);
  }
  public Future<?> start() throws Exception {
    JsonArray devices = config.getJsonArray("devices");
    for  (Object device : devices) {
      String deviceName = (String) device;
      vertx.eventBus().consumer(deviceName, msg -> {
        handleEventBus(msg, deviceName);
      });
    }
    return super.start();
  }

  @Override
  public void write(byte[] data, String deviceName) throws IOException {
    if (data.length <= 2) return;
    Path filePath = path.resolve(deviceName + "_" + timeOnStart + "_raw.txt");
    Files.write(filePath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    Files.write(filePath, "\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  @Override
  public Future<?> stop() throws Exception {
    return super.stop();
  }

  public void handleEventBus(Message<Object> msg, String deviceName) {
    JsonObject jsonMsg = (JsonObject) msg.body();
    byte[] data = jsonMsg.getBinary("data");
    try {
      write(data, deviceName);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
