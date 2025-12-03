package com.framed.core;

import java.util.function.Consumer;

/**
 * Defines a simple event bus mechanism for message-based communication between components and/or services.
 * <p>Implementations of this interface allow registering handlers and sending or publishing messages
 * to specific addresses.</p>
 *
 * <p>The event bus supports two messaging patterns:</p>
 * <ul>
 *   <li><b>Send:</b> Delivers a message to a single handler registered for the given address.</li>
 *   <li><b>Publish:</b> Broadcasts a message to all handlers registered for the given address.</li>
 * </ul>
 */
public interface EventBus {

  /**
   * Registers a handler for messages sent to the specified address.
   *
   * @param address  the address to listen on
   * @param handler  a {@link Consumer} that processes incoming messages
   */
  void register(String address, Consumer<Object> handler);

  /**
   * Sends a message to a single handler registered for the given address.
   *
   * @param address  the target address
   * @param message  the message to send
   */
  void send(String address, Object message);

  /**
   * Publishes a message to all handlers registered for the given address.
   *
   * @param address  the target address
   * @param message  the message to broadcast
   */
  void publish(String address, Object message);

  /**
   * Shutdown method of the EventBus. Depending on the implementation, this should stop all workers.
   */
  void shutdown();
}
