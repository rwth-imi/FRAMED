package com.safety_box.communicator.io.raw;

import com.safety_box.communicator.io.Writer;
import com.safety_box.core.EventBus;
import org.json.JSONArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class RawByteWriter extends Writer<byte[]> {

  public RawByteWriter(String path, EventBus eventBus, JSONArray devices) {
    super(path, eventBus);
    for  (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(
        deviceName,
        msg -> {
          handleEventBus(msg, deviceName);
        }
      );

    }
  }

  @Override
  public synchronized void write(byte[] data, String deviceName) throws IOException {
    if (data.length <= 2) return;
    Path filePath = path.resolve(deviceName + "_" + timeOnStart + "_raw.txt");
    Files.write(filePath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    Files.write(filePath, "\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  public void handleEventBus(Object msg, String deviceName) {
    //JSONObject jsonMsg = (JSONObject) msg;
    //byte[] data = (byte[]) jsonMsg.get("data");
    byte[] data  = msg.toString().getBytes();
    try {
      write(data, deviceName);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
