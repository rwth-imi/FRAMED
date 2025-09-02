package com.safety_box.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class EventBus {
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private final Executor executor;

  public EventBus(Executor executor) {
    this.executor = executor;
  }

  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }

  public void unregister(String address, Consumer<Object> handler) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null) {
      list.remove(handler);
    }
  }

  public void send(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null && !list.isEmpty()) {
      // Send to first handler only (point-to-point)
      executor.execute(() -> list.get(0).accept(message));
    }
  }

  public void publish(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null) {
      for (Consumer<Object> handler : list) {
        executor.execute(() -> handler.accept(message));
      }
    }
  }
}

