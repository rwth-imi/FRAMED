package com.framed.orchestrator;

import com.framed.core.EventBus;
import com.framed.core.Service;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;

/**
 * The {@code Factory} class provides dynamic instantiation of {@link Service} implementations
 * based on JSON configuration. It uses Java reflection to match constructor parameters with
 * configuration keys and injects an {@link EventBus} where required.
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Dynamically loads classes by name using {@link Class#forName(String)}.</li>
 *   <li>Matches constructor parameters with JSON configuration keys.</li>
 *   <li>Automatically injects an {@link EventBus} into constructors.</li>
 * </ul>
 *
 * <p><b>Matching Logic:</b>
 * <ul>
 *   <li>If a constructor parameter name matches a key in the JSON config, its value is used.</li>
 *   <li>If a parameter type is {@link EventBus}, the provided event bus is injected.</li>
 *   <li>If no matching constructor is found, a {@link ClassNotFoundException} is thrown.</li>
 * </ul>
 */
public class Factory {
  private Factory() {
    throw new IllegalStateException("Utility class");
  }
  /**
   * Instantiates a {@link Service} implementation based on the provided configuration and event bus.
   * <p>The method attempts to find a constructor whose parameters match the keys in the JSON config
   * or require an {@link EventBus}. If a match is found, the constructor is invoked with the resolved arguments.</p>
   *
   * @param config    the JSON configuration containing the class name and constructor arguments
   * @param eventBus  the event bus to inject into constructors that require it
   * @return a new {@link Service} instance
   * @throws ClassNotFoundException    if no matching constructor is found or the class cannot be loaded
   * @throws InvocationTargetException if the constructor invocation fails
   * @throws InstantiationException    if the class cannot be instantiated
   * @throws IllegalAccessException    if the constructor is not accessible
   */
  public static Service instantiate(JSONObject config, EventBus eventBus) throws ClassNotFoundException, InvocationTargetException, InstantiationException, IllegalAccessException {
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

    throw new ClassNotFoundException("No matching constructor found for class: " + className);
  }

}



