package com.framed.interop.mqtt;

import java.util.function.BiConsumer;

/**
 * Minimal MQTT transport abstraction used by {@link MqttService}. Keeps the bridge logic
 * independent of any particular client library, so it can be exercised with an in-memory fake in
 * tests and backed by {@link PahoMqttTransport} in production.
 */
public interface MqttTransport {

  /**
   * Establishes the broker connection.
   *
   * @throws Exception if the connection cannot be established
   */
  void connect() throws Exception;

  /**
   * Publishes a message.
   *
   * @param topic   the topic to publish to
   * @param payload the message payload
   * @param qos     the MQTT quality-of-service level (0/1/2)
   * @throws Exception if publishing fails
   */
  void publish(String topic, byte[] payload, int qos) throws Exception;

  /**
   * Subscribes to a topic filter, delivering matching messages to {@code handler} as
   * {@code (topic, payload)}.
   *
   * @param topicFilter the MQTT topic filter (may contain {@code +} / {@code #} wildcards)
   * @param qos         the requested QoS
   * @param handler     receives {@code (topic, payload)} for each matching message
   * @throws Exception if subscribing fails
   */
  void subscribe(String topicFilter, int qos, BiConsumer<String, byte[]> handler) throws Exception;

  /** Closes the connection and releases resources. */
  void close();
}
