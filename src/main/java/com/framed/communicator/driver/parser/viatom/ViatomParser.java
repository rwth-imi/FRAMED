package com.framed.communicator.driver.parser.viatom;

import com.framed.core.EventBus;
import com.framed.communicator.driver.parser.Parser;
import org.json.JSONArray;
import org.json.JSONObject;

public class ViatomParser extends Parser<Object> {
  public ViatomParser(EventBus eventBus, JSONArray devices) {
    super(eventBus);
    for (Object device : devices) {
      String deviceName = (String) device;
      eventBus.register(deviceName, msg -> handleEventBus((String) msg, deviceName));
    }
  }

  private void handleEventBus(String msg, String deviceName) {
    parse(msg, deviceName);
  }

  @Override
  public void parse(Object message, String deviceName) {
    JSONObject result = new JSONObject((String) message);
    String timestamp = result.getString("timestamp");
    JSONObject data = result.getJSONObject("data");
    for (String key : data.keySet()) {
      Object value = data.getJSONObject(key).get("value");
      Object field = data.getJSONObject(key).get("field");
      String className = field.toString();
      String address = "%s.%s.%s.parsed".formatted(className, deviceName, key);
      JSONObject parsedResult = new JSONObject();
      parsedResult.put("timestamp", timestamp);
      parsedResult.put("channelID", key);
      parsedResult.put("value", value);
      parsedResult.put("className", className);
      eventBus.publish("%s.addresses".formatted(deviceName), address);
      eventBus.publish(address, parsedResult);
    }
  }
}
