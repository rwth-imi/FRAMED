package com.framed.core;

import java.util.logging.Logger;

public abstract class Service {
  protected EventBus eventBus;
  protected final Logger logger;

  protected Service(EventBus eventBus) {
    this.eventBus = eventBus;
    this.logger = Logger.getLogger(getClass().getName());
  }

  public void stop(){
    logger.info("No stop logic implemented for Service: " + this.getClass().getName());
  }
}
