package com.framed.interop.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * {@link MqttTransport} backed by the Eclipse Paho MQTT 3.1.1 <em>asynchronous</em> client.
 *
 * <p>The async client is used deliberately so {@link #publish} never blocks the calling thread on
 * network I/O: it hands the message to Paho's sender and returns immediately. This matters because
 * publishes originate on the FRAMED bus handler thread, which must stay responsive (the synchronous
 * {@code MqttClient} would block until the broker acknowledges, indefinitely while it is down).
 * {@link #connect} and {@link #subscribe} run only at startup and wait with a bounded timeout.</p>
 *
 * <p>A single Paho callback dispatches each incoming message to every <em>distinct</em> handler
 * whose topic filter matches, so one connection can serve multiple inbound filters. A handler
 * registered under several overlapping filters (e.g. {@code a/#} and {@code a/+}) is invoked at
 * most once per delivery. Note that with overlapping filters the broker itself may still deliver
 * one copy per matching subscription (permitted by MQTT 3.1.1 §3.3.5) — prefer non-overlapping
 * subscribe filters.</p>
 */
public final class PahoMqttTransport implements MqttTransport {

  private static final int CONNECT_TIMEOUT_SEC = 5;
  private static final long ACTION_TIMEOUT_MS = 5_000L;

  private final MqttAsyncClient client;
  private final List<Map.Entry<String, BiConsumer<String, byte[]>>> subscriptions =
      new CopyOnWriteArrayList<>();

  /**
   * @param brokerUrl broker URI (e.g. {@code tcp://127.0.0.1:1883})
   * @param clientId  MQTT client id
   */
  public PahoMqttTransport(String brokerUrl, String clientId) {
    try {
      this.client = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
    } catch (MqttException e) {
      throw new RuntimeException("Failed to create MQTT client for " + brokerUrl, e);
    }
  }

  @Override
  public void connect() throws MqttException {
    MqttConnectOptions options = new MqttConnectOptions();
    options.setCleanSession(true);
    options.setAutomaticReconnect(true);
    options.setConnectionTimeout(CONNECT_TIMEOUT_SEC);
    client.setCallback(new Callback());
    client.connect(options).waitForCompletion(ACTION_TIMEOUT_MS);
  }

  @Override
  public void publish(String topic, byte[] payload, int qos) throws MqttException {
    // Async: returns immediately without waiting for broker acknowledgement, so the bus handler
    // thread is never blocked on network I/O.
    client.publish(topic, payload, qos, false);
  }

  @Override
  public void subscribe(String topicFilter, int qos, BiConsumer<String, byte[]> handler)
      throws MqttException {
    subscriptions.add(new SimpleImmutableEntry<>(topicFilter, handler));
    try {
      client.subscribe(topicFilter, qos).waitForCompletion(ACTION_TIMEOUT_MS);
    } catch (MqttException e) {
      subscriptions.removeIf(entry -> entry.getKey().equals(topicFilter) && entry.getValue() == handler);
      throw e;
    }
  }

  @Override
  public void close() {
    try {
      if (client.isConnected()) {
        client.disconnect().waitForCompletion(ACTION_TIMEOUT_MS);
      }
      client.close();
    } catch (MqttException ignored) {
      // best effort
    }
  }

  /**
   * Dispatches one delivered message to every matching subscription, invoking each distinct
   * handler at most once even when several of its filters match (overlapping filters would
   * otherwise duplicate every observation on the bus).
   *
   * @param topic         the delivery topic
   * @param payload       the message payload
   * @param subscriptions the registered (filter, handler) pairs
   */
  static void dispatchArrived(String topic, byte[] payload,
                              List<Map.Entry<String, BiConsumer<String, byte[]>>> subscriptions) {
    Set<BiConsumer<String, byte[]>> invoked = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Map.Entry<String, BiConsumer<String, byte[]>> sub : subscriptions) {
      if (topicMatches(sub.getKey(), topic) && invoked.add(sub.getValue())) {
        sub.getValue().accept(topic, payload);
      }
    }
  }

  /** MQTT topic-filter match supporting {@code +} (single level) and {@code #} (multi level). */
  static boolean topicMatches(String filter, String topic) {
    String[] f = filter.split("/");
    String[] t = topic.split("/");
    int i = 0;
    for (; i < f.length; i++) {
      if (f[i].equals("#")) {
        return true;
      }
      if (i >= t.length) {
        return false;
      }
      if (f[i].equals("+")) {
        continue;
      }
      if (!f[i].equals(t[i])) {
        return false;
      }
    }
    return i == t.length;
  }

  private final class Callback implements MqttCallback {
    @Override
    public void messageArrived(String topic, MqttMessage message) {
      dispatchArrived(topic, message.getPayload(), subscriptions);
    }

    @Override
    public void connectionLost(Throwable cause) {
      // no-op; automatic reconnect is enabled
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
      // no-op
    }
  }
}
