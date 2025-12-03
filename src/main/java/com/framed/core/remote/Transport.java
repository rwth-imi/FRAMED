package com.framed.core.remote;

import java.util.function.Consumer;


/**
 * Defines a transport mechanism for communication between services, JVMs, or devices.
 * Implementations of this interface may use various protocols such as TCP, UDP, or other
 * interoperable messaging systems to deliver messages across network boundaries.
 *
 * <p>The transport supports two messaging patterns:</p>
 * <ul>
 *   <li><b>Send:</b> Point-to-point delivery to a single recipient.</li>
 *   <li><b>Publish:</b> Broadcast delivery to multiple recipients subscribed to an address.</li>
 * </ul>
 *
 * <p>Implementations must also provide lifecycle management through {@link #start()} and
 * {@link #shutdown()} methods.</p>
 */
public interface Transport {

  /**
   * Sends a message to a specific host and port using point-to-point delivery.
   *
   * @param host    the target host
   * @param port    the target port
   * @param address the logical address or topic for the message
   * @param message the message to send
   */
  void send(String host, int port, String address, Object message);

  /**
   * Publishes a message to all subscribers of the specified address.
   *
   * @param host    the target host
   * @param port    the target port
   * @param address the logical address or topic for the message
   * @param message the message to broadcast
   */
  void publish(String host, int port, String address, Object message);

  /**
   * Registers a handler for messages received on the specified address.
   *
   * @param address the address or topic to listen on
   * @param handler a {@link java.util.function.Consumer} that processes incoming messages
   */
  void register(String address, Consumer<Object> handler);
  /**
   * Starts the transport mechanism, initializing resources such as sockets or threads.
   */
  void start();

  /**
   * Shuts down the transport mechanism and releases any allocated resources.
   */
  void shutdown();
}

