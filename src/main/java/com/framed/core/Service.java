package com.framed.core;

public abstract class Service {
  protected EventBus eventBus;

  public Service(EventBus eventBus) {
    this.eventBus = eventBus;
  }

  public abstract void stop();
}
