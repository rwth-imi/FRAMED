package com.framed.core.remote;

import org.json.JSONObject;

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
 * <p><b>Note:</b> Always call {@link #shutdown()} to release resources when done.</p>
 */
public class NioTcpTransport implements Transport {
  Logger logger = Logger.getLogger(getClass().getName());


  private final Selector selector;
  private final ServerSocketChannel serverChannel;
  private final ExecutorService workerPool = Executors.newCachedThreadPool();
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private static final Charset charset = StandardCharsets.UTF_8;
  private volatile boolean running = true;
  private final Map<SocketChannel, StringBuilder> buffers = new ConcurrentHashMap<>();


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
    SocketChannel client = server.accept();
    client.configureBlocking(false);
    client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(4096));
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

    try {
      int bytesRead = client.read(buffer);
      if (bytesRead == -1) {
        closeClient(client);
        return;
      }

      buffer.flip();
      String chunk = charset.decode(buffer).toString();
      buffer.clear();

      buffers.computeIfAbsent(client, k -> new StringBuilder()).append(chunk);
      StringBuilder sb = buffers.get(client);

      int newline;
      while ((newline = sb.indexOf("\n")) != -1) {
        String jsonStr = sb.substring(0, newline).trim();
        sb.delete(0, newline + 1);
        parseAndDispatchAsync(jsonStr, handlers, workerPool);
      }

    } catch (IOException e) {
      logger.warning("Error reading from client: " + e.getMessage());
      closeClient(client);
    }
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
      buffers.remove(client);
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
    try (SocketChannel channel = SocketChannel.open()) {
      channel.configureBlocking(true); // short-lived
      channel.connect(new InetSocketAddress(host, port));

      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      ByteBuffer buffer = charset.encode(CharBuffer.wrap(json.toString()));
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    } catch (IOException e) {
      logger.warning("TCP send failed: " + e.getMessage());
    }
  }

  /**
   * Shuts down the transport, closing the selector, server channel, and worker pool.
   */
  @Override
  public void shutdown() {
    running = false;
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
