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
  /** When true, {@link #publish} throws instead of recording — simulates a down broker. */
  boolean failPublishes;
  /** Number of {@link #connect} calls that throw before connecting succeeds. */
  int failConnects;

  @Override
  public void connect() {
    if (failConnects > 0) {
      failConnects--;
      throw new IllegalStateException("simulated broker down at startup");
    }
    connected = true;
  }

  @Override
  public void publish(String topic, byte[] payload, int qos) {
    if (failPublishes) {
      throw new IllegalStateException("simulated broker outage");
    }
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

  /** Test helper: simulate an inbound broker message, using the real transport dispatch logic. */
  void deliver(String topic, byte[] payload) {
    PahoMqttTransport.dispatchArrived(topic, payload, subs);
  }
}
