package com.safety_box.communicator.driver.parser;

import com.safety_box.core.EventBusInterface;
import com.safety_box.core.Service;
import com.safety_box.core.EventBus;

public abstract class Parser<T> extends Service {
  public Parser(EventBusInterface eventBus) {
    super(eventBus);
  }

  public abstract void parse(T message, String deviceName);
}
