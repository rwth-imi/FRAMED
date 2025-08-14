package com.safety_box.communicator.io.raw;

import com.safety_box.communicator.io.Writer;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class RawByteWriter extends Writer<byte[]> {

  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    JsonArray ports = config.getJsonArray("ports");
    for  (Object port : ports) {
      String portName = (String) port;
      vertx.eventBus().consumer(portName, msg -> {
        JsonObject jsonMsg = (JsonObject) msg;
        byte[] data = jsonMsg.getBinary("data");
        try {
          write(data);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }


  }

  @Override
  public void write(byte[] data) throws IOException {
    Files.write(path, data, StandardOpenOption.APPEND);

  }
}
