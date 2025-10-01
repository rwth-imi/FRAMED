package com.safety_box.communicator.driver.protocol;

import com.safety_box.core.EventBusInterface;
import com.safety_box.core.Service;
import com.safety_box.core.EventBus;

public abstract class Protocol extends Service {
  protected String id;


  public Protocol(String id, EventBusInterface eventBus) {
    super(eventBus);
    this.id = id;

  }

  public abstract void connect();
}
