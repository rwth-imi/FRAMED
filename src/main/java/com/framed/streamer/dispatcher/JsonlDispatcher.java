package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.streamer.model.DataPoint;
import org.json.JSONArray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

public class JsonlDispatcher extends Dispatcher {

  private final Path path;

  public JsonlDispatcher(EventBus eventBus, JSONArray devices, String path, String fileName) {
    super(eventBus, devices);
    long timeOnStart = Instant.now().toEpochMilli();
    String file = timeOnStart + "_" + fileName;
    this.path = Path.of(path).resolve(file);
  }

  @Override
  public void push(DataPoint<?> dataPoint) throws IOException {
    String dataString = dataPoint.toJsonString();
    Files.write(path, (dataString + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  @Override
  public void pushBatch(List<DataPoint<?>> batch) {

  }

  @Override
  public void stop() {

  }
}
