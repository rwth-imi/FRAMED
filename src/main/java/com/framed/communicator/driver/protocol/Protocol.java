package com.framed.communicator.driver.protocol;

import com.framed.core.EventBus;
import com.framed.core.Service;

public abstract class Protocol extends Service {
  protected String id;


  public Protocol(String id, EventBus eventBus) {
    super(eventBus);
    this.id = id;

  }

  public abstract void connect();
}
