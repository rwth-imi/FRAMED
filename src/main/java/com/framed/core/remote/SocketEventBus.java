package com.framed.core.remote;

import com.framed.core.EventBus;
import com.framed.core.utils.DispatchMode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;


/**
 * A distributed {@link EventBus} implementation that uses a {@link Transport} for remote communication.
 *
 * <p>This class allows local and remote event dispatching across multiple peers connected via TCP or UDP.
 * It supports point-to-point and publish-subscribe semantics, combining local handler execution with
 * remote message forwarding.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Integrates with any {@link Transport} implementation (e.g., TCP or UDP).</li>
 *   <li>Maintains a dynamic set of remote peers for message propagation.</li>
 *   <li>Supports local handler registration and synchronous or asynchronous local dispatch.</li>
 *   <li>Graceful shutdown via {@link #shutdown()}.</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * Transport tcpTransport = new TCPTransport(8080);
 * SocketEventBus eventBus = new SocketEventBus(tcpTransport, DispatchMode.PER_HANDLER);
 *
 * eventBus.register("sensor.data", msg -> System.out.println("Local handler received: " + msg));
 *
 * eventBus.addPeer(new Peer("remote-host", 8081));
 *
 * eventBus.publish("sensor.data", "Temperature: 22°C");
 *
 * // Later:
 * eventBus.shutdown();
 * }</pre>
 *
 * <b>Threading Model:</b>
 * <ul>
 *   <li>{@link DispatchMode#SEQUENTIAL} – All handlers run sequentially on the calling thread.</li>
 *   <li>{@link DispatchMode#PARALLEL} – Handlers run concurrently using a shared thread pool.</li>
 *   <li>{@link DispatchMode#PER_HANDLER} – Each handler has its own single-thread executor for ordered execution.</li>
 * </ul>
 * Remote dispatch is delegated to the underlying {@link Transport} implementation.
 * <b>Note:</b> Ensure {@link #shutdown()} is called to release resources and stop the transport.
 */

 public class SocketEventBus implements EventBus {
  private final Transport transport;
  private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
  private final Map<String, List<Consumer<Object>>> localHandlers = new ConcurrentHashMap<>();
  private final Logger logger;
  private final DispatchMode dispatchMode;
  private final ExecutorService parallelPool = Executors.newCachedThreadPool();
  private final Map<Consumer<Object>, ExecutorService> handlerExecutors = new ConcurrentHashMap<>();


  /**
   * Creates a new {@code SocketEventBus} using the specified transport and dispatch mode.
   *
   * @param transport    the transport implementation (e.g., TCPTransport or UDPTransport)
   * @param dispatchMode determines how local handlers are executed:
   *                     <ul>
   *                       <li>{@link DispatchMode#SEQUENTIAL} – handlers run sequentially on the caller thread</li>
   *                       <li>{@link DispatchMode#PARALLEL} – handlers run concurrently using a shared thread pool</li>
   *                       <li>{@link DispatchMode#PER_HANDLER} – each handler has its own single-thread executor for ordered execution</li>
   *                     </ul>
   */
  public SocketEventBus(Transport transport, DispatchMode dispatchMode) {
    this.transport = transport;
    this.dispatchMode = dispatchMode;
    this.transport.start();
    this.logger = Logger.getLogger(getClass().getName());
  }


  /**
   * Adds a remote peer to the event bus.
   * <p>Messages sent or published will also be forwarded to this peer.</p>
   *
   * @param peer the remote peer to add
   */
  public void addPeer(Peer peer) {
    peers.add(peer);
  }

  /**
   * Removes a remote peer from the event bus.
   *
   * @param peer the remote peer to remove
   */
  public void removePeer(Peer peer) {
    peers.remove(peer);
  }


  /**
   * Registers a local handler for the specified address.
   * <p>The handler will also be registered with the underlying transport for remote messages.</p>
   *
   * @param address the logical address/topic to listen on
   * @param handler the handler to process incoming payloads
   */
  @Override
  public void register(String address, Consumer<Object> handler) {
    localHandlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
    transport.register(address, handler);
  }

  /**
   * Sends a point-to-point message to all registered peers and dispatches locally.
   * <p>Uses {@code send} semantics: only the first handler on the remote side will process the message.</p>
   *
   * @param address the logical address/topic
   * @param message the payload to send
   */
  @Override
  public void send(String address, Object message) {
    dispatchLocally(address, message);
    for (Peer peer : peers) {
      transport.send(peer.host(), peer.port(), address, message);
    }
  }

  /**
   * Publishes a message to all registered peers and dispatches locally.
   * <p>Uses {@code publish} semantics: all handlers on the remote side will process the message.</p>
   *
   * @param address the logical address/topic
   * @param message the payload to publish
   */
  @Override
  public void publish(String address, Object message) {
    dispatchLocally(address, message);
    for (Peer peer : peers) {
      transport.publish(peer.host(), peer.port(), address, message);
    }
  }


  /**
   * Dispatches a message to all local handlers registered for the given address.
   * <p>Execution is synchronous and blocking on the calling thread.</p>
   *
   * @param address the logical address/topic
   * @param message the payload to deliver
   */
  private void dispatchLocally(String address, Object message) {
    List<Consumer<Object>> handlers = localHandlers.get(address);
    if (handlers != null) {
      for (Consumer<Object> handler : handlers) {
        switch (dispatchMode) {
          case SEQUENTIAL:
            handler.accept(message); // if in SEQUENTIAL mode, call each handler in the executing thread
            break;
          case PARALLEL:
            parallelPool.submit(() -> handler.accept(message)); // if in PARALLEL mode, create a new thread
            break;
          case PER_HANDLER:
            handlerExecutors
              .computeIfAbsent(handler, h -> Executors.newSingleThreadExecutor())
              .submit(() -> handler.accept(message)); // if in PER_HANDLER mode, create new thread
                                                      // if handler was never called, else use per handler threads.
            break;
        }
      }
    }
  }

  /**
   * Shuts down the event bus and releases resources.
   * <p>Stops the underlying transport, alle executors, and clears local handlers and peer list.</p>
   */
  public void shutdown() {
    transport.shutdown();
    localHandlers.clear();
    peers.clear();
    parallelPool.shutdownNow();
    handlerExecutors.values().forEach(ExecutorService::shutdownNow);
    logger.info("SocketEventBus shutdown successfully.");
  }

}
