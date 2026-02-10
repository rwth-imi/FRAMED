package com.framed.core.local;

import com.framed.core.EventBus;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A local implementation of the {@link EventBus} interface for message-based communication
 * between components within the same JVM.
 *
 * <p>This class provides asynchronous message delivery using a dedicated
 * {@link ExecutorService} per address to ensure ordered message handling.
 * It supports both point-to-point messaging via {@link #send(String, Object)}
 * and broadcasting via {@link #publish(String, Object)}.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Thread-safe handler registration and message dispatching.</li>
 *   <li>Single-threaded executors per address for sequential message processing.</li>
 *   <li>Automatic cleanup of executors when all handlers for an address are removed.</li>
 * </ul>
 */
public class LocalEventBus implements EventBus {

  /**
   * Stores registered handlers for each address.
   * Each address maps to a list of {@link Consumer} instances.
   */
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

  /**
   * Stores a dedicated {@link ExecutorService} for each address to process messages sequentially.
   */
  private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();


  /**
   * Registers a handler for the specified address.
   * Creates a new single-threaded executor for the address if it does not exist.
   *
   * @param address the address to listen on
   * @param handler the handler that processes messages for this address
   */
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
    executors.computeIfAbsent(address, k -> Executors.newSingleThreadExecutor()); // queues all message -> for real-time guarantees, we might need custom ThreadPoolExecutor
  }

  /**
   * Sends a message to a single handler registered for the given address.
   * The first handler in the list is selected for delivery.
   *
   * @param address the target address
   * @param message the message to send
   */
  @Override
  public void send(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    ExecutorService executor = executors.get(address);
    if (list != null && !list.isEmpty() && executor != null) {
      executor.execute(() -> list.get(0).accept(message)); // point-to-point
    }
  }

  /**
   * Publishes a message to all handlers registered for the given address.
   *
   * @param address the target address
   * @param message the message to broadcast
   */
  @Override
  public void publish(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    ExecutorService executor = executors.get(address);
    if (list != null && executor != null) {
      for (Consumer<Object> handler : list) {
        executor.execute(() -> handler.accept(message)); // broadcast
      }
    }
  }

  /**
   * Stops all executors that were added to the {@link #executors} map.
   */
  @Override
  public void shutdown() {
    for (ExecutorService executor : executors.values()) {
      executor.shutdown();
    }
  }
}


