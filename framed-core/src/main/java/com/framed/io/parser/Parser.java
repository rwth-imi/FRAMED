package com.framed.io.parser;

import com.framed.core.EventBus;
import com.framed.core.Service;

public abstract class Parser<T> extends Service {
  protected Parser(EventBus eventBus) {
    super(eventBus);
  }

  public abstract void parse(T message, String deviceName);
}
