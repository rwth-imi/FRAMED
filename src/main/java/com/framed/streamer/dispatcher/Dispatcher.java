package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.streamer.model.DataPoint;
import com.framed.streamer.Parser;
import org.json.JSONArray;
import org.json.JSONObject;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public abstract class Dispatcher extends Service {
  private final List<String> addresses = new ArrayList<>();


  public Dispatcher(EventBus eventBus, JSONArray devices) {
    super(eventBus);
    for (Object deviceObj : devices) {
      String deviceID = deviceObj.toString();
      eventBus.register(deviceID + ".addresses", msg -> {
        if (!addresses.contains(msg.toString())) {
          addresses.add(msg.toString());
          eventBus.register((String) msg, msg_ -> {
            try {
              JSONObject body = (JSONObject) msg_;
              body.put("deviceID", deviceID);
              DataPoint<?> dp = Parser.parse(body);
              push(dp);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
        }
      });
    }
  }

  public abstract void push(DataPoint<?> dataPoint) throws IOException;

  public abstract void pushBatch(List<DataPoint<?>> batch);
}
