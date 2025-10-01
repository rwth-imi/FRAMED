package com.safety_box.streamer.dispatcher;

import com.safety_box.core.EventBus;
import com.safety_box.core.EventBusInterface;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import com.safety_box.streamer.parser.Parser;
import org.json.JSONArray;
import org.json.JSONObject;


import java.util.ArrayList;
import java.util.List;

public abstract class LocalDispatcher extends Dispatcher {
  private List<String> addresses = new ArrayList<>();

  public LocalDispatcher(EventBusInterface eventBus, JSONArray devices) {
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

  public abstract void push(DataPoint<?> dataPoint);

  public abstract void pushBatch(TimeSeries timeSeries);
}
