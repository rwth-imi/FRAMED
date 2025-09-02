package com.safety_box.streamer.dispatcher;

import com.safety_box.core.Service;
import com.safety_box.core.EventBus;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;

public abstract class Dispatcher extends Service {

  public Dispatcher(EventBus eventBus) {
    super(eventBus);
  }

  public abstract void push(DataPoint<?> dataPoint);

  public abstract void pushBatch(TimeSeries timeSeries);
}
