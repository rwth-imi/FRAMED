package com.framed.interop.mqtt;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** In-memory {@link MqttTransport} for deterministic bridge tests (no broker, no sockets). */
final class FakeMqttTransport implements MqttTransport {

  record Published(String topic, byte[] payload, int qos) {}

  final List<Published> published = new CopyOnWriteArrayList<>();
  private final List<Map.Entry<String, BiConsumer<String, byte[]>>> subs = new CopyOnWriteArrayList<>();
  boolean connected;

  @Override
  public void connect() {
    connected = true;
  }

  @Override
  public void publish(String topic, byte[] payload, int qos) {
    published.add(new Published(topic, payload, qos));
  }

  @Override
  public void subscribe(String topicFilter, int qos, BiConsumer<String, byte[]> handler) {
    subs.add(new SimpleImmutableEntry<>(topicFilter, handler));
  }

  @Override
  public void close() {
    connected = false;
  }

  /** Test helper: simulate an inbound broker message, routing to matching subscribers. */
  void deliver(String topic, byte[] payload) {
    for (Map.Entry<String, BiConsumer<String, byte[]>> sub : subs) {
      if (PahoMqttTransport.topicMatches(sub.getKey(), topic)) {
        sub.getValue().accept(topic, payload);
      }
    }
  }
}
