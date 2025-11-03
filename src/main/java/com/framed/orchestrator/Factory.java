package com.framed.orchestrator;

import com.framed.core.EventBus;
import com.framed.core.Service;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

public class Factory {

  public static Service instantiate(JSONObject config, EventBus eventBus) throws Exception {
    String className = config.getString("class");
    Class<?> clazz = Class.forName(className);

    for (Constructor<?> constructor : clazz.getConstructors()) {
      Parameter[] parameters = constructor.getParameters();
      Object[] args = new Object[parameters.length];

      boolean match = true;
      for (int i = 0; i < parameters.length; i++) {
        String paramName = parameters[i].getName();
        Class<?> paramType = parameters[i].getType();
        if (config.keySet().contains(paramName)) {
          Object value = config.get(paramName);
          args[i] = value;
        } else if (paramType.equals(EventBus.class)) {
          args[i] = eventBus;
        } else {
          match = false;
          break;
        }
      }

      if (match) {
        return (Service) constructor.newInstance(args);
      }
    }

    throw new RuntimeException("No matching constructor found for class: " + className);
  }

}

