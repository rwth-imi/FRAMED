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
import java.util.Arrays;
import java.util.logging.Logger;

public class RawByteWriter extends Writer<byte[]> {
  private static final Logger logger = Logger.getLogger(RawByteWriter.class.getName());
  private BufferedWriter bufferedWriter;
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
  }
  public Future<?> start() throws Exception {
    JsonArray devices = config.getJsonArray("devices");
    for  (Object device : devices) {
      String deviceName = (String) device;
      Path filePath = path.resolve(deviceName + "_" + System.currentTimeMillis() + "_raw.txt");
      try {
        bufferedWriter = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      vertx.eventBus().consumer(deviceName, this::handleEventBus);
    }
    return super.start();
  }

  public void handleEventBus(Message<Object> msg) {
    JsonObject jsonMsg = (JsonObject) msg.body();
    byte[] data = jsonMsg.getBinary("data");
    try {
      write(data);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  @Override
  public void write(byte[] data) throws IOException {
    for (byte b : data) {
      bufferedWriter.write(b);
    }
    bufferedWriter.newLine();
  }

  @Override
  public Future<?> stop() throws Exception {
    bufferedWriter.close();
    return super.stop();
  }
}
