package com.safety_box.core;

import java.util.function.Consumer;

public interface Transport {
  void send(String host, int port, String address, Object message);
  void publish(String host, int port, String address, Object message);
  void register(String address, Consumer<Object> handler);
  void start();
  void shutdown();
}
