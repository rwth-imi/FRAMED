package com.safety_box.communicator.manager;

import com.safety_box.communicator.driver.protocol.Protocol;
import io.vertx.core.json.JsonObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

public class DriverFactory {

  public static Protocol<?> instantiate(JsonObject config) throws Exception {
    String className = config.getString("driver");
    Class<?> clazz = Class.forName(className);

    for (Constructor<?> constructor : clazz.getConstructors()) {
      Parameter[] parameters = constructor.getParameters();
      Object[] args = new Object[parameters.length];

      boolean match = true;
      for (int i = 0; i < parameters.length; i++) {
        String paramName = parameters[i].getName(); // may need workaround
        Class<?> paramType = parameters[i].getType();

        Object value = config.getValue(paramName);
        if (value == null) {
          match = false;
          break;
        }

        args[i] = paramType.cast(value); // may need conversion
      }

      if (match) {
        return (Protocol<?>) constructor.newInstance(args);
      }
    }

    throw new RuntimeException("No matching constructor found for class: " + className);
  }

  public static List<Object> initializeAll(List<JsonObject> configs) throws Exception {
    List<Object> instances = new ArrayList<>();
    for (JsonObject config : configs) {
      instances.add(instantiate(config));
    }
    return instances;
  }
}
