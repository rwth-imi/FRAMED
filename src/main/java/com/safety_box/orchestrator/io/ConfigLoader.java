package com.safety_box.orchestrator.io;


import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;

public class ConfigLoader {

  public static JSONObject loadConfig(String path) throws Exception {
    String content = new String(Files.readAllBytes(Paths.get(path)));
    return new JSONObject(content);
  }
}

