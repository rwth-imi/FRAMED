package com.framed.communicator.io;

import com.framed.core.EventBus;
import com.framed.core.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;


/**
 * An abstract base class for writing data of type {@code T} to a specified file path.
 *
 * <p>This class extends {@link Service} and provides common functionality for
 * writer implementations, such as tracking the start time for distinct output
 * file names and managing the output path.</p>
 *
 * <p>Concrete subclasses must implement the {@link #write(Object, String)} method
 * to define how data should be written to the file system.</p>
 *
 * @param <T> the type of data to be written
 */
public abstract class Writer<T> extends Service {

  /**
   * The file system path where data will be written.
   */
  protected Path path;

  /**
   * The timestamp (in milliseconds since epoch) when this writer instance was created.
   */
  protected long timeOnStart;

  /**
   * Creates a new Writer instance.
   *
   * @param path      the file system path as a string where data should be written
   * @param eventBus  the event bus used for communication and event handling
   */
  protected Writer(String path, EventBus eventBus) {
    super(eventBus);
    this.timeOnStart = Instant.now().toEpochMilli();
    this.path = Path.of(path);
  }

  /**
   * Writes the given data to the configured path.
   * Concrete implementations must define how the data is serialized and stored.
   *
   * @param data        the data to write
   * @param deviceName  the name of the device associated with the data
   * @throws IOException if an I/O error occurs during writing
   */
  public abstract void write(T data, String deviceName) throws IOException;
}

