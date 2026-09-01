package com.framed.communicator.io.parsed;

import com.framed.io.writer.Writer;

import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MedibusParsedWriter extends Writer<JSONObject> {

  /**
   * Addresses already bound, so a re-announced channel is not subscribed twice.
   *
   * <p>Concurrent because announcements are delivered on the announcing thread and different
   * devices announce on different threads &mdash; see
   * {@link com.framed.core.Service#subscribeToAnnouncements(String, java.util.function.Consumer)}.</p>
   */
  private final Set<String> addresses = ConcurrentHashMap.newKeySet();

  public MedibusParsedWriter(String path, EventBus eventBus, JSONArray devices) {
    super(path, eventBus);
    for (Object device : devices) {
      String deviceName = (String) device;
      // Binds synchronously, so the device cannot publish a sample before this writer is listening.
      subscribeToAnnouncements(deviceName, address -> {
        if (address == null || address.isBlank()) return;
        if (!addresses.add(address)) return;
        eventBus.register(address, msg_ -> handleEventBus(msg_, deviceName));
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
