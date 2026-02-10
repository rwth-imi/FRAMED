package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.core.utils.Timer;
import com.framed.streamer.model.DataPoint;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FfillDispatcher extends Dispatcher {
  private final Map<String, DataPoint<?>> latestDataPoints = new ConcurrentHashMap<>();
  private final Timer timer = new Timer();
  public FfillDispatcher(EventBus eventBus, JSONArray devices, Long frequencyMillis) {
    super(eventBus, devices);
    timer.setPeriodic(frequencyMillis, this::publishAggregatedData);
  }

  private void publishAggregatedData() {
    JSONObject payload = new JSONObject();
    for (Map.Entry<String, DataPoint<?>> entry : latestDataPoints.entrySet()) {
      payload.put(entry.getKey(), entry.getValue().toJsonString());
    }
    eventBus.publish("ffill.data", payload.toString());
  }

  @Override
  public void push(DataPoint<?> dataPoint) throws IOException {
    latestDataPoints.put(dataPoint.channelID(), dataPoint);
  }

  @Override
  public void pushBatch(List<DataPoint<?>> timeSeries) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void stop() {
    timer.shutdown();
  }
}
