package com.framed.core.remote;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static com.framed.core.utils.RemoteUtils.parseAndDispatchAsync;

/**
 * A {@link Transport} implementation using Java NIO for TCP-based communication.
 * <p>This class provides non-blocking I/O for message exchange between components,
 * services or devices using a simple JSON-based protocol.
 * Messages are framed by newline characters and contain
 * fields for {@code address}, {@code payload}, and {@code type}.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Non-blocking server using {@link Selector} and {@link ServerSocketChannel}.</li>
 *   <li>Handles multiple clients concurrently.</li>
 *   <li>Dispatches messages to registered handlers asynchronously via a worker pool.</li>
 *   <li>Supports point-to-point ({@link #send}) and broadcast ({@link #publish}) messaging.</li>
 * </ul>
 *
 * <h2>Message Format:</h2>
 * <pre>{@code
 * {
 *   "address": "topic.name",
 *   "payload": "data",
 *   "type": "send" | "publish"
 * }
 * }</pre>
 *
 * <h2>Example usage:</h2>
 * <pre>{@code
 * NioTcpTransport transport = new NioTcpTransport(8080);
 * transport.register("sensor.data", msg -> System.out.println("Received: " + msg));
 * transport.start();
 *
 * transport.send("localhost", 8080, "sensor.data", "Hello");
 * }</pre>
 *
 * <h2>Connection model</h2>
 * <p>One TCP connection is kept open per target {@code host:port} and reused for every message sent
 * to that peer. Messages are newline-delimited on that stream, so a single connection carries an
 * unbounded sequence of them.</p>
 *
 * <p>This matters for throughput. An earlier implementation opened a fresh {@code SocketChannel}
 * per message — connect, write, close — making one datapoint one TCP connection; the receiving
 * instance accepts on a single selector thread and could not drain the listen backlog, so above
 * roughly 7,400 messages/s the kernel dropped SYNs and 10–30 % of messages were lost while
 * {@code write} still returned successfully and no failure was reported. Reuse removes the
 * per-message handshake entirely and turns silent loss into backpressure: writes are blocking, so a
 * sender outrunning its peer is throttled by the socket rather than having data discarded.</p>
 *
 * <h2>Threading</h2>
 * <p>Writes to one peer are serialized on that peer's connection — concurrent senders would
 * otherwise interleave partial JSON on the stream and corrupt the newline framing. Writes to
 * <em>different</em> peers proceed in parallel. A write blocks its calling thread while the peer's
 * receive window is full, which is the intended backpressure; callers must therefore not write from
 * an event-bus handler thread ({@code SocketEventBus} already publishes to peers on its own pool).
 * Reading happens on the single selector thread; handler invocation is handed to the worker pool.</p>
 *
 * <p><b>Note:</b> Always call {@link #shutdown()} to release resources when done.</p>
 */
public class NioTcpTransport implements Transport {

  /**
   * How long a connection attempt to a peer may take before the send is abandoned. Bounded so a
   * black-holed peer cannot park a publishing thread indefinitely.
   */
  private static final int CONNECT_TIMEOUT_MS =
          Integer.getInteger("framed.transport.tcp.connectTimeoutMs", 5_000);

  /**
   * Cap on the bytes buffered for one peer while waiting for a newline. A peer that never sends one
   * would otherwise grow this without bound; on overflow the buffer is dropped and the connection
   * closed, since the stream can no longer be resynchronised.
   */
  private static final int MAX_PENDING_BYTES =
          Integer.getInteger("framed.transport.tcp.maxPendingBytes", 8 * 1024 * 1024);

  /** Per-connection read buffer size; the loop reads repeatedly, so this only sizes each read. */
  private static final int READ_CHUNK_BYTES = 64 * 1024;

  /**
   * Whether to disable Nagle's algorithm on the pooled connections.
   *
   * <p>Default {@code true}, favouring latency: the messages this transport carries are a few hundred
   * bytes, and Nagle would hold one back until the previous segment is acknowledged, adding up to
   * tens of milliseconds to a real-time waveform sample. Setting it {@code false} lets the kernel
   * coalesce small writes into fewer, larger segments, which raises peak throughput at that cost.</p>
   */
  private static final boolean TCP_NO_DELAY =
          Boolean.parseBoolean(System.getProperty("framed.transport.tcp.noDelay", "true"));

  Logger logger = Logger.getLogger(getClass().getName());


  private final Selector selector;
  private final ServerSocketChannel serverChannel;
  private final ExecutorService workerPool = Executors.newCachedThreadPool();
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private static final Charset charset = StandardCharsets.UTF_8;
  private volatile boolean running = true;

  /**
   * Bytes received from a peer that do not yet form a complete newline-delimited message. Accumulated
   * as <em>bytes</em>, not as a decoded string: a TCP read can split a multi-byte UTF-8 character
   * across two chunks, and decoding each chunk independently would corrupt it. Medical payloads carry
   * such characters routinely (µV, °C).
   */
  private final Map<SocketChannel, ByteArrayOutputStream> pending = new ConcurrentHashMap<>();

  /** Outbound connections, one per {@code host:port}, created on first send and reused after. */
  private final Map<String, PeerLink> links = new ConcurrentHashMap<>();

  /**
   * Inbound connections this transport has accepted, tracked so {@link #shutdown()} can close them.
   *
   * <p>Closing the {@link Selector} does not close the channels registered with it. Left open, an
   * accepted connection outlives the transport that serves it: a peer keeps writing into a socket
   * whose reader is gone, every write succeeds into the receive buffer, and the data is discarded
   * without an exception on either side.</p>
   */
  private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();


  /**
  * Creates a new NIO TCP transport bound to the specified port.
  *
  * @param port the TCP port to listen on
  * @throws IOException if the server socket or selector cannot be initialized
  */
  public NioTcpTransport(int port) throws IOException {
    this.selector = Selector.open();
    this.serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    serverChannel.bind(new InetSocketAddress("0.0.0.0", port));
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
  }

  /**
   * Starts the transport event loop in a background thread.
   * <p>Accepts new connections and reads incoming messages asynchronously.</p>
   */
  @Override
  public void start() {
    workerPool.submit(() -> {
      try {
        while (running) {
          selector.select();
          for (SelectionKey key : selector.selectedKeys()) {
            if (key.isAcceptable()) {
              handleAccept(key);
            } else if (key.isReadable()) {
              handleRead(key);
            }
          }
          selector.selectedKeys().clear();
        }
      } catch (IOException e) {
        if (running) {
          logger.severe(e.getMessage());
          logger.severe("Shutting EventBus down.");
          this.shutdown();
        }
      }
    }, "NioTcpTransport-Selector");
  }

  /**
   * Handles a new client connection.
   *
   * @param key the selection key representing the accept event
   * @throws IOException if the client cannot be accepted
   */
  private void handleAccept(SelectionKey key) throws IOException {
    ServerSocketChannel server = (ServerSocketChannel) key.channel();
    // Accept every queued connection: readiness is reported once, and in non-blocking mode accept()
    // returns null when the backlog is empty rather than blocking.
    SocketChannel client;
    while ((client = server.accept()) != null) {
      client.configureBlocking(false);
      client.socket().setTcpNoDelay(TCP_NO_DELAY);
      client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(READ_CHUNK_BYTES));
      clients.add(client);
    }
  }


  /**
   * Handles reading data from a client socket.
   * <p>Accumulates data until a newline is found, then parses and dispatches JSON messages.</p>
   *
   * @param key the selection key representing the read event
   */
  private void handleRead(SelectionKey key) {
    SocketChannel client = (SocketChannel) key.channel();
    ByteBuffer buffer = (ByteBuffer) key.attachment();
    ByteArrayOutputStream acc = pending.computeIfAbsent(client, k -> new ByteArrayOutputStream());

    try {
      int bytesRead;
      // Read until the socket is drained: with a reused connection many messages can arrive between
      // two selector wakeups, and one read of the buffer size would leave the rest queued.
      while ((bytesRead = client.read(buffer)) > 0) {
        buffer.flip();
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        buffer.clear();
        acc.write(chunk, 0, chunk.length);

        if (acc.size() > MAX_PENDING_BYTES) {
          logger.warning("Peer sent %d bytes with no message delimiter; dropping connection."
                  .formatted(acc.size()));
          closeClient(client);
          return;
        }
        drainMessages(acc);
      }
      if (bytesRead == -1) {
        closeClient(client);
      }
    } catch (IOException e) {
      logger.warning("Error reading from client: " + e.getMessage());
      closeClient(client);
    }
  }

  /**
   * Dispatches every complete newline-delimited message buffered so far and retains the trailing
   * partial one.
   *
   * <p>Splitting is done on the newline <em>byte</em> before decoding, which is safe for UTF-8: no
   * byte of a multi-byte sequence can equal {@code 0x0A}. Each message is then decoded whole, so a
   * character straddling two TCP reads survives.</p>
   *
   * @param acc the peer's accumulated bytes; consumed messages are removed from it
   */
  private void drainMessages(ByteArrayOutputStream acc) {
    byte[] data = acc.toByteArray();
    int start = 0;
    for (int i = 0; i < data.length; i++) {
      if (data[i] != '\n') {
        continue;
      }
      String jsonStr = new String(data, start, i - start, charset).trim();
      start = i + 1;
      if (jsonStr.isEmpty()) {
        continue;
      }
      try {
        parseAndDispatchAsync(jsonStr, handlers, workerPool);
      } catch (RuntimeException malformed) {
        // One corrupt message must not cost the rest of the stream.
        logger.warning("Discarding malformed TCP message: " + malformed);
      }
    }
    if (start == 0) {
      return;               // no complete message yet; leave the buffer untouched
    }
    acc.reset();
    acc.write(data, start, data.length - start);
  }

  /**
   * Closes a client connection and removes its' buffer.
   *
   * @param client the client socket channel
   */
  private void closeClient(SocketChannel client) {
    try {
      client.close();
    } catch (IOException e) {
      logger.warning("Failed to close client: " + e.getMessage());
    } finally {
      pending.remove(client);
      clients.remove(client);
    }
  }
  /**
   * Sends a message to the first subscriber of the specified address.
   *
   * @param host    the target host
   * @param port    the target port
   * @param address the logical address for the message
   * @param message the message payload
   */
  @Override
  public void send(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "send");
  }


  /**
   * Publishes a message to all subscribers of the specified address.
   *
   * @param host    the target host
   * @param port    the target port
   * @param address the logical address for the message
   * @param message the message payload
   */
  @Override
  public void publish(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "publish");
  }


  /**
   * Sends a JSON-formatted message over TCP.
   * Message type is used by the EventBus
   *
   * @param host    the target host
   * @param port    the target port
   * @param address the message address
   * @param message the message payload
   * @param type    the message type ("send" or "publish")
   */
  private void sendMessage(String host, int port, String address, Object message, String type) {
    JSONObject json = new JSONObject();
    json.put("address", address);
    json.put("payload", message);
    json.put("type", type);

    if (!running) {
      logger.warning("Dropping message to %s:%d: transport is shut down.".formatted(host, port));
      return;
    }

    ByteBuffer buffer = charset.encode("%s\n".formatted(json));
    PeerLink link = links.computeIfAbsent(host + ":" + port, k -> new PeerLink(host, port));
    try {
      link.write(buffer);
    } catch (IOException e) {
      logger.warning("TCP send to %s:%d failed: %s".formatted(host, port, e.getMessage()));
    }
  }

  /**
   * A reused outbound connection to one peer.
   *
   * <p>All writes to the peer are serialized on this object: the stream is newline-framed, so two
   * threads writing concurrently could interleave halves of two messages and leave the receiver
   * unable to parse either. A write blocks while the peer's receive window is full — that
   * backpressure is deliberate, and is what makes the transport lossless rather than silently
   * dropping under overload.</p>
   */
  private final class PeerLink {
    private final String host;
    private final int port;

    /**
     * The live connection, or {@code null} before the first send and after a failure. Volatile
     * rather than lock-guarded so {@link #closeQuietly()} can be called from another thread while a
     * blocking write is in flight.
     */
    private volatile SocketChannel channel;

    private PeerLink(String host, int port) {
      this.host = host;
      this.port = port;
    }

    /**
     * Writes one framed message, opening or re-opening the connection as needed.
     *
     * @param payload the encoded message, positioned at its start
     * @throws IOException if the message could not be delivered even on a fresh connection
     */
    private synchronized void write(ByteBuffer payload) throws IOException {
      if (channel == null || !channel.isConnected()) {
        open();
      }
      try {
        writeFully(payload);
      } catch (IOException stale) {
        if (!running) {
          // shutdown() closed this connection under us; reconnecting would outlive the transport
          throw stale;
        }
        // A cached connection can have died since its last use — the peer restarted, or an idle
        // reaper closed it — and that surfaces only on write. Retry once on a fresh connection;
        // a second failure is a real one and is reported to the caller.
        closeQuietly();
        open();
        payload.rewind();
        writeFully(payload);
      }
    }

    private void writeFully(ByteBuffer payload) throws IOException {
      while (payload.hasRemaining()) {
        channel.write(payload);
      }
    }

    private void open() throws IOException {
      SocketChannel opened = SocketChannel.open();
      try {
        opened.socket().connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        opened.socket().setTcpNoDelay(TCP_NO_DELAY);
      } catch (IOException e) {
        try {
          opened.close();
        } catch (IOException ignored) {
          // the connect failure is the one worth reporting
        }
        throw e;
      }
      channel = opened;
    }

    /**
     * Closes the connection if one is open.
     *
     * <p>Deliberately <em>not</em> synchronized: writes are blocking, so a peer that has stopped
     * reading can hold the write lock indefinitely, and taking it here would make
     * {@link NioTcpTransport#shutdown()} hang behind that write. Closing the channel instead makes
     * the blocked write fail with {@code AsynchronousCloseException}, which releases it.</p>
     */
    private void closeQuietly() {
      SocketChannel current = channel;
      channel = null;
      if (current == null) {
        return;
      }
      try {
        current.close();
      } catch (IOException e) {
        logger.warning("Failed to close connection to %s:%d: %s".formatted(host, port, e.getMessage()));
      }
    }
  }

  /**
   * Shuts down the transport, closing the selector, server channel, and worker pool.
   */
  @Override
  public void shutdown() {
    running = false;
    links.values().forEach(PeerLink::closeQuietly);
    links.clear();
    // Close accepted connections before the selector: a peer must see the connection drop and
    // re-establish it, rather than write indefinitely into a socket nobody reads.
    clients.forEach(this::closeClient);
    clients.clear();
    pending.clear();
    try {
      selector.close();
      serverChannel.close();
    } catch (IOException e) {
      logger.severe("Failed to close selector: " + e.getMessage());
    }
    workerPool.shutdownNow();
  }

  /**
   * Registers a handler for messages received on the specified address.
   *
   * @param address the address to listen on
   * @param handler the handler to process messages
   */
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }
}
