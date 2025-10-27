package com.safety_box.communicator.driver.protocol;

import com.safety_box.core.EventBus;
import com.safety_box.core.Service;

public abstract class Protocol extends Service {
  protected String id;


  public Protocol(String id, EventBus eventBus) {
    super(eventBus);
    this.id = id;

  }

  public abstract void connect();
}
