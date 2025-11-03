package com.framed.core;

import java.util.function.Consumer;

public interface EventBus {
  void register(String address, Consumer<Object> handler);

  void send(String address, Object message);

  void publish(String address, Object message);
}
