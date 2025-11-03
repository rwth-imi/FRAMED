package com.framed.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class MockTransport implements Transport {
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private final List<String> sentMessages = new CopyOnWriteArrayList<>();

  @Override
  public void send(String host, int port, String address, Object message) {
    sentMessages.add("SEND:" + address + ":" + message);
    dispatch(address, message);
  }

  @Override
  public void publish(String host, int port, String address, Object message) {
    sentMessages.add("PUBLISH:" + address + ":" + message);
    dispatch(address, message);
  }


  private void dispatch(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null) {
      for (Consumer<Object> handler : list) {
        handler.accept(message);
      }
    }
  }

  @Override
  public void start() {
    // No-op for mock
  }

  @Override
  public void shutdown() {
    // No-op for mock
  }

  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }

  public List<String> getSentMessages() {
    return sentMessages;
  }
}
