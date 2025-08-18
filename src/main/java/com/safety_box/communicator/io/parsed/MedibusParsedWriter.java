package com.safety_box.communicator.io.parsed;

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

public class MedibusParsedWriter extends Writer<JsonObject> {
  private BufferedWriter bufferedWriterRT;
  private BufferedWriter bufferedWriterSD;

  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
  }

  @Override
  public void write(JsonObject data) throws IOException {
    if (data.getBoolean("realTime")) {
      bufferedWriterRT.write(data.encode());
      bufferedWriterRT.newLine();
    } else {
      bufferedWriterSD.write(data.encode());
      bufferedWriterSD.newLine();
    }

  }

  @Override
  public Future<?> start() throws Exception {
    JsonArray devices = config.getJsonArray("devices");
    for  (Object device : devices) {
      String deviceName = (String) device;
      Path filePathRT = path.resolve(deviceName + "_" + System.currentTimeMillis() + "_parsed_RT.jsonl");
      Path filePathSD = path.resolve(deviceName + "_" + System.currentTimeMillis() + "_parsed_SD.jsonl");
      try {
        bufferedWriterRT = Files.newBufferedWriter(filePathRT, StandardOpenOption.CREATE);
        bufferedWriterSD = Files.newBufferedWriter(filePathSD, StandardOpenOption.CREATE);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      vertx.eventBus().consumer("parsed_" + deviceName, this::handleEventBus);
    }
    return super.start();
  }

  @Override
  public Future<?> stop() throws Exception {
    bufferedWriterRT.close();
    bufferedWriterSD.close();
    return super.stop();
  }

  public void handleEventBus(Message<Object> msg) {
    JsonObject jsonMsg = (JsonObject) msg.body();
    try {
      write(jsonMsg);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
