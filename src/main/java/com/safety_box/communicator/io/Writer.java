package com.safety_box.communicator.io;

import com.safety_box.core.EventBusInterface;
import com.safety_box.core.Service;
import com.safety_box.core.EventBus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

public abstract class Writer<T> extends Service {
  protected Path path;
  protected long timeOnStart;

  public Writer(String path, EventBusInterface eventBus) {
    super(eventBus);
    this.timeOnStart = Instant.now().toEpochMilli();
    this.path = Path.of(path);
  }

  @Override
  public void stop() {
    System.out.println(
      "Stop not implemented for class Writer."
    );
  }

  public abstract void write(T data, String deviceName) throws IOException;
}
