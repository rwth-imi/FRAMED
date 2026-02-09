package com.framed.core;

import com.framed.core.utils.Timer;

import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * An abstract base class for application services that provides access to an {@link EventBus}
 * for inter-component communication / services and a {@link Logger} for logging.
 *
 * <p>Subclasses can use the event bus to send or receive messages and the logger for
 * diagnostic output. This class also provides a default {@link #stop()} method that can
 * be overridden to implement custom shutdown logic.</p>
 */
public abstract class Service {

  /**
   * The event bus used for communication between components.
   */
  protected EventBus eventBus;

  /**
   * Logger instance for this service, initialized with the class name.
   */
  protected final Logger logger;
  protected static final DateTimeFormatter formatter = Timer.formatter;



  /**
   * Creates a new service instance with the specified event bus.
   *
   * @param eventBus the event bus used for communication
   */
  protected Service(EventBus eventBus) {
    this.eventBus = eventBus;
    this.logger = Logger.getLogger(getClass().getName());
  }

  /**
   * Stops the service.
   * <p>By default, this method logs a message indicating that no stop logic is implemented.
   * Subclasses should override this method to provide custom shutdown behavior when necessary.</p>
   */
  public void stop(){
    logger.info("No stop logic implemented for Service: " + this.getClass().getName());
  }
}
