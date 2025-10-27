package com.safety_box.communicator.driver.parser;

import com.safety_box.core.EventBus;
import com.safety_box.core.Service;

public abstract class Parser<T> extends Service {
  public Parser(EventBus eventBus) {
    super(eventBus);
  }

  public abstract void parse(T message, String deviceName);
}
