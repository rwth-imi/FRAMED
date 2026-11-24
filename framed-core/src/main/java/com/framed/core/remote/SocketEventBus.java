// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core.remote;

import com.framed.core.EventBus;
import com.framed.core.utils.DispatchMode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A distributed {@link EventBus} implementation backed by a {@link Transport} for remote communication.
 *
 * <p>This event bus supports local dispatch and remote forwarding (send/publish) across a dynamic set
 * of peers. Local listeners can be executed using a default {@link DispatchMode} configured at bus
 * construction time.</p>
 *
 * <h2>Supported Local Dispatch Modes</h2>
 * <ul>
 *   <li>{@link DispatchMode#SEQUENTIAL} – Handlers run sequentially on the calling thread.</li>
 *   <li>{@link DispatchMode#PARALLEL} – Handlers run concurrently using a shared thread pool.</li>
 *   <li>{@link DispatchMode#PER_HANDLER} – Each handler has its own single-thread executor to preserve
 *       per-handler ordering while still allowing concurrency across handlers.</li>
 * </ul>
 *
 * <h2>Per-Handler Dispatch Override</h2>
 * <p>In addition to the bus-wide default dispatch mode, this implementation allows specifying an
 * optional per-handler dispatch mode at registration time:</p>
 * <ul>
 *   <li>If a per-handler mode is provided (non-null), it is used for <b>local dispatch</b> of that handler.</li>
 *   <li>If it is {@code null}, the bus-wide default {@link #dispatchMode} is used.</li>
 * </ul>
 *
 * <p><b>Important:</b> The per-handler dispatch override affects only local dispatch performed by
 * {@link #dispatchLocally(String, Object)}. Remote dispatch is delegated to the underlying
 * {@link Transport}, which may invoke handlers on its own threads.</p>
 *
 * <h2>Send and publish</h2>
 * <ul>
 *   <li>{@link #publish(String, Object)} reaches every local handler of the address and is
 *       forwarded to every peer, where every handler of the address receives it.</li>
 *   <li>{@link #send(String, Object)} is point-to-point: it reaches exactly one handler in the
 *       deployment. If the address has a local handler, the first one registered receives it and
 *       nothing is forwarded. Otherwise it is forwarded to the first peer added to this bus, whose
 *       first handler for the address receives it. The bus does not know which peers have handlers,
 *       so a send to a peer without one is lost; deployments that send across instances should
 *       list the receiving instance first.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * <p>Messages forwarded to one peer leave in the order this bus was called with them: each peer has
 * a single-thread executor that performs every write to it, sends and publications alike. With
 * the receiving transport dispatching per handler in arrival order, a remote handler sees one
 * sender thread's messages in the order they were sent (UDP may still lose or reorder datagrams on
 * the network). This matters to a {@code Reactor}: its logical-time gate discards a message whose
 * tag is not newer than the last one accepted on its channel, so a reordered pair costs a
 * firing. Messages from different sender threads, or from different instances, have no defined
 * order relative to each other.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Call {@link #shutdown()} to stop the transport and terminate all executors created/used by this bus.</p>
 */
public class SocketEventBus implements EventBus {

    /** Underlying transport used for remote communication. */
    private final Transport transport;

    /**
     * Connected remote peers that receive forwarded messages, in the order they were added; the
     * first one is the target of a {@link #send} without a local handler.
     */
    private final Set<Peer> peers = new CopyOnWriteArraySet<>();

    /**
     * One single-thread executor per peer, performing every write to that peer in submission
     * order. Writes block while the peer's receive window is full; doing them here keeps that
     * backpressure off the publishing thread without letting writes overtake each other.
     */
    private final Map<Peer, ExecutorService> peerExecutors = new ConcurrentHashMap<>();

    /**
     * Local handler registry: address -> list of handlers.
     * <p>{@link CopyOnWriteArrayList} avoids concurrent modification issues on iteration during dispatch.</p>
     */
    private final Map<String, List<Consumer<Object>>> localHandlers = new ConcurrentHashMap<>();

    /** Logger for lifecycle and operational messages. */
    private final Logger logger;

    /**
     * Bus-wide default dispatch mode used if no per-handler override is configured.
     */
    private final DispatchMode dispatchMode;

    /**
     * Shared pool used for:
     * <ul>
     *   <li>{@link DispatchMode#PARALLEL} local dispatch</li>
     * </ul>
     */
    private final ExecutorService parallelPool = Executors.newCachedThreadPool();

    /**
     * Per-handler executors used when a handler's effective mode is {@link DispatchMode#PER_HANDLER}.
     * <p>Each handler gets a single-thread executor to guarantee FIFO ordering for that handler.</p>
     */
    private final Map<Consumer<Object>, ExecutorService> handlerExecutors = new ConcurrentHashMap<>();

    /**
     * Per-handler dispatch mode override.
     * <p>If a handler is present in this map, it will be dispatched locally according to that mode.
     * If absent, the bus default {@link #dispatchMode} is used.</p>
     */
    private final Map<Consumer<Object>, DispatchMode> handlerModeOverrides = new ConcurrentHashMap<>();

    /**
     * Creates a new {@code SocketEventBus} using the specified transport and bus-wide default dispatch mode.
     *
     * @param transport    the transport implementation (e.g., TCPTransport or UDPTransport)
     * @param dispatchMode default local dispatch mode used when a handler does not specify an override
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

    /** The single-thread executor that performs every write to {@code peer}. */
    private ExecutorService peerExecutor(Peer peer) {
        return peerExecutors.computeIfAbsent(peer, p -> Executors.newSingleThreadExecutor(
                r -> new Thread(r, "SocketEventBus-peer-%s:%d".formatted(p.host(), p.port()))));
    }

    /**
     * Removes a remote peer from the event bus.
     *
     * @param peer the remote peer to remove
     */
    public void removePeer(Peer peer) {
        peers.remove(peer);
        ExecutorService executor = peerExecutors.remove(peer);
        if (executor != null) {
            executor.shutdown();   // messages already queued for the peer still go out
        }
    }

    // --------------------------------------------------------------------------
    // Registration
    // --------------------------------------------------------------------------

    /**
     * Registers a local handler for the specified address using the bus-wide default dispatch mode.
     *
     * <p>This method is backwards compatible and behaves exactly like the original implementation.
     * The handler will also be registered with the underlying {@link Transport} for remote messages.</p>
     *
     * @param address the logical address/topic to listen on
     * @param handler the handler to process incoming payloads
     */
    @Override
    public void register(String address, Consumer<Object> handler) {
        register(address, handler, null);
    }

    /**
     * Registers a local handler for the specified address with an optional per-handler dispatch mode override.
     *
     * <p>If {@code perHandlerMode} is non-null, it will be used to dispatch this handler locally.
     * If {@code perHandlerMode} is null, the bus-wide default {@link #dispatchMode} will be used.</p>
     *
     * <p><b>Scope:</b> The override applies only to local dispatch performed by this bus.
     * Remote callback threading is determined by the underlying {@link Transport} implementation.</p>
     *
     * @param address        the logical address/topic to listen on
     * @param handler        the handler to process incoming payloads
     * @param perHandlerMode optional per-handler local dispatch mode; null means "use bus default"
     */
    public void register(String address, Consumer<Object> handler, DispatchMode perHandlerMode) {
        localHandlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);

        // Store override (or remove override if null to fall back to bus default)
        if (perHandlerMode == null) {
            handlerModeOverrides.remove(handler);
        } else {
            handlerModeOverrides.put(handler, perHandlerMode);
        }

        // Forward registration to transport so the handler can receive remote messages.
        transport.register(address, handler);
    }

    // --------------------------------------------------------------------------
    // Messaging
    // --------------------------------------------------------------------------

    /**
     * Sends a point-to-point message: exactly one handler in the deployment receives it.
     *
     * <p>If the address has a local handler, the first one registered receives the message and it
     * is not forwarded. Otherwise it is forwarded to the first peer added to this bus, whose first
     * handler for the address receives it. See the class documentation for the consequences.</p>
     *
     * @param address the logical address/topic
     * @param message the payload to send
     */
    @Override
    public void send(String address, Object message) {
        List<Consumer<Object>> handlers = localHandlers.get(address);
        if (handlers != null && !handlers.isEmpty()) {
            dispatchToHandler(handlers.get(0), message);
            return;
        }
        Iterator<Peer> inOrderAdded = peers.iterator();
        if (inOrderAdded.hasNext()) {
            Peer first = inOrderAdded.next();
            forward(first, () -> transport.send(first.host(), first.port(), address, message), "send", address);
        }
    }

    /**
     * Publishes a message to all local handlers and forwards it to every peer.
     *
     * <p>Uses {@code publish} semantics: all handlers on the remote side receive the message.
     * Forwarding is asynchronous but ordered per peer (see the class documentation).</p>
     *
     * @param address the logical address/topic
     * @param message the payload to publish
     */
    @Override
    public void publish(String address, Object message) {
        dispatchLocally(address, message);
        for (Peer peer : peers) {
            forward(peer, () -> transport.publish(peer.host(), peer.port(), address, message), "publish", address);
        }
    }

    /** Queues one write on the peer's executor; a failing write is logged, not propagated. */
    private void forward(Peer peer, Runnable write, String type, String address) {
        try {
            peerExecutor(peer).execute(() -> {
                try {
                    write.run();
                } catch (RuntimeException e) {
                    logger.warning("Transport %s to %s:%d [%s] failed: %s"
                            .formatted(type, peer.host(), peer.port(), address, e));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            // the peer was removed or the bus shut down concurrently; the message is dropped
        }
    }

    // --------------------------------------------------------------------------
    // Local dispatch (effective mode = per-handler override or bus default)
    // --------------------------------------------------------------------------

    /**
     * Dispatches a message to all local handlers registered for the given address.
     *
     * <p>The effective dispatch mode for each handler is determined as follows:</p>
     * <ol>
     *   <li>If the handler has a per-handler override configured, use it.</li>
     *   <li>Otherwise, use the bus-wide default {@link #dispatchMode}.</li>
     * </ol>
     *
     * <p>In {@link DispatchMode#SEQUENTIAL} mode, handlers execute on the calling thread.
     * In {@link DispatchMode#PARALLEL} mode, handlers execute on {@link #parallelPool}.
     * In {@link DispatchMode#PER_HANDLER} mode, each handler executes on its own single-thread executor.</p>
     *
     * @param address the logical address/topic
     * @param message the payload to deliver
     */
    private void dispatchLocally(String address, Object message) {
        List<Consumer<Object>> handlers = localHandlers.get(address);
        if (handlers == null) return;

        for (Consumer<Object> handler : handlers) {
            dispatchToHandler(handler, message);
        }
    }

    /** Runs one handler according to its effective dispatch mode (override or bus default). */
    private void dispatchToHandler(Consumer<Object> handler, Object message) {
        DispatchMode effective = handlerModeOverrides.getOrDefault(handler, this.dispatchMode);

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
     *
     * <p>This method:</p>
     * <ul>
     *   <li>Stops the underlying {@link Transport}</li>
     *   <li>Clears local handler and peer state</li>
     *   <li>Terminates the shared thread pool and the per-peer writers (queued writes are dropped)</li>
     *   <li>Terminates all per-handler executors</li>
     * </ul>
     *
     * <p><b>Note:</b> This forcefully interrupts running tasks via {@code shutdownNow()}.</p>
     */
    public void shutdown() {
        transport.shutdown();
        localHandlers.clear();
        peers.clear();

        parallelPool.shutdownNow();
        peerExecutors.values().forEach(ExecutorService::shutdownNow);
        peerExecutors.clear();
        handlerExecutors.values().forEach(ExecutorService::shutdownNow);
        handlerExecutors.clear();
        handlerModeOverrides.clear();

        logger.info("SocketEventBus shutdown successfully.");
    }
}