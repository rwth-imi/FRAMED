package com.safety_box.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class EventBus implements EventBusInterface{
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
    executors.computeIfAbsent(address, k -> Executors.newSingleThreadExecutor()); // queues all message -> for real-time guarantees, we might need custom ThreadPoolExecutor
  }

  public void unregister(String address, Consumer<Object> handler) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null) {
      list.remove(handler);
      if (list.isEmpty()) {
        ExecutorService executor = executors.remove(address);
        if (executor != null) {
          executor.shutdown();
        }
      }
    }
  }

  public void send(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    ExecutorService executor = executors.get(address);
    if (list != null && !list.isEmpty() && executor != null) {
      executor.execute(() -> list.get(0).accept(message)); // point-to-point
    }
  }

  public void publish(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    ExecutorService executor = executors.get(address);
    if (list != null && executor != null) {
      for (Consumer<Object> handler : list) {
        executor.execute(() -> handler.accept(message)); // broadcast
      }
    }
  }
}


