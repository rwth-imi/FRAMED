package com.safety_box.core;

import java.util.function.Consumer;

public interface EventBusInterface {
  void register(String address, Consumer<Object> handler);

  void unregister(String address, Consumer<Object> handler);

  void send(String address, Object message);

  void publish(String address, Object message);
}
