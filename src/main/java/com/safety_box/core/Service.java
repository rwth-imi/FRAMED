package com.safety_box.core;

public abstract class Service {
  protected EventBusInterface eventBus;
  public Service(EventBusInterface eventBus) {
    this.eventBus = eventBus;
  }
  public abstract void stop();
}
