package com.framed.communicator.driver.protocol;

import com.framed.core.EventBus;
import com.framed.core.Service;


/**
 * Represents an abstract communication protocol that extends the {@link Service} class.
 *
 * <p>A protocol is responsible for establishing and managing a connection between FRAMED and a particular Device
 * using a specific communication standard or mechanism.</p>
 *
 * <p>This class provides a base for concrete protocol implementations, requiring them
 * to define the {@link #connect()} method for establishing a connection.</p>
 */
public abstract class Protocol extends Service {

  /**
   * Unique identifier for this protocol instance.
   */
  protected String id;

  /**
   * Creates a new Protocol instance.
   *
   * @param id       the unique identifier for the protocol
   * @param eventBus the event bus used for communication and event handling
   */
  public Protocol(String id, EventBus eventBus) {
    super(eventBus);
    this.id = id;
  }

  /**
   * Establishes a connection using the specific protocol implementation and starts the configured data export.
   * Concrete subclasses must provide the logic for connecting.
   */
  public abstract void connect();
}

