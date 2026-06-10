package com.framed.core;

import com.framed.core.remote.Transport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A mock implementation of the {@link Transport} interface for testing purposes.
 * <p>This class simulates message sending and publishing without actual network communication.
 * It stores sent messages in memory and immediately dispatches them to registered handlers.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Simulates {@link #send(String, int, String, Object)} and {@link #publish(String, int, String, Object)} calls.</li>
 *   <li>Maintains a list of sent messages for verification in tests.</li>
 *   <li>Supports handler registration and immediate message dispatch.</li>
 *   <li>No-op lifecycle methods {@link #start()} and {@link #shutdown()}.</li>
 * </ul>
*/
public class MockTransport implements Transport {
  /**
   * Stores registered handlers for each address.
   */
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

  /**
   * Stores all sent or published messages for verification.
   */
  private final List<String> sentMessages = new CopyOnWriteArrayList<>();

  /**
   * Simulates sending a message to a specific host and port.
   * Adds the message to {@link #sentMessages} and dispatches it to registered handlers.
   *
   * @param host    the target host (ignored in mock)
   * @param port    the target port (ignored in mock)
   * @param address the logical address for the message
   * @param message the message to send
   */
  @Override
  public void send(String host, int port, String address, Object message) {
    sentMessages.add("SEND:%s:%s".formatted(address, message));
    dispatch(address, message);
  }


  /**
  * Simulates publishing a message to all subscribers of the specified address.
  * Adds the message to {@link #sentMessages} and dispatches it to registered handlers.
  *
  * @param host    the target host (ignored in mock)
  * @param port    the target port (ignored in mock)
  * @param address the logical address for the message
  * @param message the message to broadcast
  */
  @Override
  public void publish(String host, int port, String address, Object message) {
    sentMessages.add("PUBLISH:%s:%s".formatted(address, message));
    dispatch(address, message);
  }


  /**
   * Dispatches a message to all handlers registered for the given address.
   *
   * @param address the address to dispatch to
   * @param message the message to deliver
   */
  private void dispatch(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null) {
      for (Consumer<Object> handler : list) {
        handler.accept(message);
      }
    }
  }

  /**
   * No-op for mock implementation.
   */
  @Override
  public void start() {
    // No-op for mock
  }


  /**
   * No-op for mock implementation.
   */
  @Override
  public void shutdown() {
    // No-op for mock
  }


  /**
   * Registers a handler for messages received on the specified address.
   *
   * @param address the address to listen on
   * @param handler the handler to process messages
   */
  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }


  /**
   * Returns the list of sent and published messages for verification.
   *
   * @return a list of message logs in the format "SEND:address:message" or "PUBLISH:address:message"
   */
  public List<String> getSentMessages() {
    return sentMessages;
  }
}
