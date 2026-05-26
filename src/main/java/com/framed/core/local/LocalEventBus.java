package com.framed.core.local;

import com.framed.core.EventBus;
import com.framed.core.utils.DispatchMode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A local implementation of the {@link EventBus} interface for message-based communication
 * between components within the same JVM.
 *
 * <p>This implementation supports configurable local dispatch semantics via {@link DispatchMode}
 * and allows an optional per-handler override of the dispatch mode at registration time.</p>
 *
 * <h2>Dispatch modes</h2>
 * <ul>
 *   <li>{@link DispatchMode#SEQUENTIAL}: handlers run inline on the calling thread (blocking).</li>
 *   <li>{@link DispatchMode#PARALLEL}: handlers run concurrently on a shared thread pool.</li>
 *   <li>{@link DispatchMode#PER_HANDLER}: each handler runs on a dedicated single-thread executor
 *       (FIFO per handler, concurrent across handlers).</li>
 * </ul>
 *
 * <h2>Per-handler override</h2>
 * <p>Handlers can optionally specify their own mode using
 * {@link #register(String, Consumer, DispatchMode)}. If the mode is {@code null},
 * the bus-wide default mode is used.</p>
 *
 * <p><b>Note:</b> This differs from the original "one executor per address" model. Ordering is
 * now defined by the chosen dispatch mode (e.g., PER_HANDLER orders per handler, not per address).</p>
 */
public class LocalEventBus implements EventBus {

  /** Logger for lifecycle and operational messages. */
  private final Logger logger = Logger.getLogger(getClass().getName());

  /**
   * Stores registered handlers for each address.
   * Each address maps to a list of {@link Consumer} instances.
   */
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();

  /**
   * Bus-wide default dispatch mode used when a handler does not set an override.
   */
  private final DispatchMode defaultDispatchMode;

  /**
   * Shared pool used for {@link DispatchMode#PARALLEL} dispatch.
   */
  private final ExecutorService parallelPool = Executors.newCachedThreadPool();

  /**
   * Per-handler executors used for {@link DispatchMode#PER_HANDLER} to guarantee FIFO per handler.
   */
  private final Map<Consumer<Object>, ExecutorService> handlerExecutors = new ConcurrentHashMap<>();

  /**
   * Optional per-handler dispatch mode override.
   * If absent, {@link #defaultDispatchMode} is used.
   */
  private final Map<Consumer<Object>, DispatchMode> handlerModeOverrides = new ConcurrentHashMap<>();

  /**
   * Creates a LocalEventBus using the given default dispatch mode.
   *
   * @param defaultDispatchMode bus-wide default mode used if a handler does not specify an override
   */
  public LocalEventBus(DispatchMode defaultDispatchMode) {
    this.defaultDispatchMode = defaultDispatchMode;
  }

  /**
   * Creates a LocalEventBus with {@link DispatchMode#PER_HANDLER} as default.
   */
  public LocalEventBus() {
    this(DispatchMode.PER_HANDLER);
  }

  // --------------------------------------------------------------------------
  // Registration
  // --------------------------------------------------------------------------

  /**
   * Registers a handler for the specified address using the bus default dispatch mode.
   *
   * @param address the address/topic to listen on
   * @param handler the handler processing messages for this address
   */
  @Override
  public void register(String address, Consumer<Object> handler) {
    register(address, handler, null);
  }

  /**
   * Registers a handler for the specified address with an optional per-handler dispatch mode override.
   *
   * <p>If {@code perHandlerMode} is non-null, it will be used for local dispatch of this handler.</p>
   * <p>If {@code perHandlerMode} is null, the bus-wide default mode is used.</p>
   *
   * @param address        the address/topic to listen on
   * @param handler        the handler processing messages for this address
   * @param perHandlerMode optional override; null means "use bus default"
   */
  public void register(String address, Consumer<Object> handler, DispatchMode perHandlerMode) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);

    if (perHandlerMode == null) {
      handlerModeOverrides.remove(handler);
    } else {
      handlerModeOverrides.put(handler, perHandlerMode);
    }
  }

  // --------------------------------------------------------------------------
  // Messaging
  // --------------------------------------------------------------------------

  /**
   * Sends a message to a single handler registered for the given address.
   * The first handler in the list is selected for delivery (point-to-point semantics).
   *
   * @param address the target address
   * @param message the message to send
   */
  @Override
  public void send(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list == null || list.isEmpty()) return;

    Consumer<Object> handler = list.get(0);
    dispatchToHandler(handler, message);
  }

  /**
   * Publishes a message to all handlers registered for the given address (broadcast semantics).
   *
   * @param address the target address
   * @param message the message to broadcast
   */
  @Override
  public void publish(String address, Object message) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list == null || list.isEmpty()) return;

    for (Consumer<Object> handler : list) {
      dispatchToHandler(handler, message);
    }
  }

  // --------------------------------------------------------------------------
  // Dispatch helper (per-handler override or bus default)
  // --------------------------------------------------------------------------

  /**
   * Dispatches to a single handler using the handler's effective dispatch mode:
   * override if present, otherwise {@link #defaultDispatchMode}.
   */
  private void dispatchToHandler(Consumer<Object> handler, Object message) {
    DispatchMode effective = handlerModeOverrides.getOrDefault(handler, defaultDispatchMode);

    switch (effective) {
      case SEQUENTIAL -> handler.accept(message);

      case PARALLEL -> parallelPool.submit(() -> handler.accept(message));

      case PER_HANDLER -> handlerExecutors
              .computeIfAbsent(handler, h -> Executors.newSingleThreadExecutor())
              .submit(() -> handler.accept(message));
    }
  }

  // --------------------------------------------------------------------------
  // Shutdown
  // --------------------------------------------------------------------------

  /**
   * Shuts down the event bus and releases resources.
   * <p>Stops the shared parallel pool and all per-handler executors.</p>
   */
  @Override
  public void shutdown() {
    parallelPool.shutdownNow();
    handlerExecutors.values().forEach(ExecutorService::shutdownNow);
    handlerExecutors.clear();
    handlerModeOverrides.clear();
    handlers.clear();

    logger.info("LocalEventBus shutdown successfully.");
  }
}