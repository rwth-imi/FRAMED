package com.framed.communicator.io.parsed;

import com.framed.communicator.io.Writer;

import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MedibusParsedWriter extends Writer<JSONObject> {
  private List<String> addresses = new ArrayList<>();

  public MedibusParsedWriter(String path, EventBus eventBus, JSONArray devices) {
    super(path, eventBus);
    for (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName + ".addresses", msg -> {
        if (!addresses.contains(msg.toString())) {
          addresses.add(msg.toString());
          eventBus.register(
            msg.toString(), msg_ -> {
              handleEventBus(msg_, deviceName);
            }
          );
        }
      });
    }
  }


  @Override
  public synchronized void write(JSONObject data, String deviceName) throws IOException {
    Path filePath;
    if (Objects.equals(data.getString("className"), "RealTime")) {
      filePath = path.resolve(deviceName + "_" + timeOnStart + "_parsed_RT.jsonl");
    } else {
      filePath = path.resolve(deviceName + "_" + timeOnStart + "_parsed_SD.jsonl");
    }
    String dataString = data.toString();
    if (dataString != null) {
      Files.write(filePath, dataString.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      Files.write(filePath, "\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
  }


  public synchronized void handleEventBus(Object msg, String deviceName) {
    JSONObject jsonMsg = (JSONObject) msg;
    try {
      write(jsonMsg, deviceName);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
