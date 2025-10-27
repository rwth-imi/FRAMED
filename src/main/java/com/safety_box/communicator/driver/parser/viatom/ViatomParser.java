package com.safety_box.communicator.driver.parser.viatom;

import com.safety_box.core.EventBus;
import com.safety_box.communicator.driver.parser.Parser;
import org.json.JSONArray;
import org.json.JSONObject;

public class ViatomParser extends Parser {
  public ViatomParser(EventBus eventBus, JSONArray devices) {
    super(eventBus);
    for (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName, msg -> {
        handleEventBus((String) msg, deviceName);
      });
    }
  }

  private void handleEventBus (String msg, String deviceName){
    parse(msg, deviceName);
  }

  @Override
  public void parse(Object message, String deviceName) {
    JSONObject result = new JSONObject((String) message);
    String timestamp =  result.getString("timestamp");
    JSONObject data = result.getJSONObject("data");
    for (String key : data.keySet()) {
      Object value = data.getJSONObject(key).get("value");
      Object field = data.getJSONObject(key).get("field");
      String address = deviceName+"."+key+".parsed";
      JSONObject parsedResult = new JSONObject();
      parsedResult.put("timestamp", timestamp);
      parsedResult.put("realTime", false);
      parsedResult.put("physioID", key);
      parsedResult.put("value", value);
      parsedResult.put("className", field.toString());
      eventBus.publish(deviceName+".addresses", address);
      eventBus.publish(address, parsedResult);
    }
  }

  @Override
  public void stop() {

  }
}
