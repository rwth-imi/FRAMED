package com.framed.cdss;

import com.framed.core.EventBus;
import com.framed.core.Service;

public abstract class MealyTransductor extends Service {
  public MealyTransductor(EventBus eventBus) {
    super(eventBus);
  }

  @Override
  public void stop() {

  }
}
