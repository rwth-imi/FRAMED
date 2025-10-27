package com.safety_box.streamer.dispatcher;

import com.safety_box.core.EventBus;
import com.safety_box.core.Service;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import com.safety_box.streamer.Parser;
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
      eventBus.register(deviceID+".addresses", msg -> {
        if (!addresses.contains(msg.toString())){
          addresses.add(msg.toString());
          eventBus.register((String) msg, msg_ ->{
            try {
              JSONObject body = (JSONObject) msg_;
              body.put("deviceID", deviceID);
              DataPoint<?> dp = Parser.parse((JSONObject) body);
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

  public abstract void pushBatch(TimeSeries timeSeries);
}
