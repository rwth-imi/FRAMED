package com.safety_box.communicator.io;


import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ConfigLoader {

  public static JsonObject loadConfig(String path) throws Exception {
    String content = new String(Files.readAllBytes(Paths.get(path)));
    return new JsonObject(content);
  }
}

