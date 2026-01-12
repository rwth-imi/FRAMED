package com.framed.core.remote;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
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


/** A {@link Transport} implementation using Java NIO over UDP for lightweight, connectionless
 * message delivery between services or devices.
 *
 * <p>This class uses a {@link Selector} with a non-blocking {@link DatagramChannel} to receive
 * datagrams and a simple JSON-based envelope for outgoing messages. Each datagram is expected
 * to contain a single JSON object with {@code address}, {@code payload}, and {@code type} fields.</p>
 *
 * <h2>Characteristics of UDP</h2>
 * <ul>
 *   <li><b>Unreliable:</b> Datagrams may be lost, duplicated, or arrive out of order.</li>
 *   <li><b>Message-oriented:</b> Each packet is an independent message; there is no stream.</li>
 *   <li><b>No back-pressure:</b> Sender and receiver are decoupled; overspeed can cause drops.</li>
 * </ul>
 *
 * <h2>Message Format</h2>
 * <pre>{@code
 * {
 *   "address": "topic.name",
 *   "payload": ...,
 *   "type": "send" | "publish"
 * }
 * }</pre>
 *
 * <h2>Example usage</h2>
 * <pre>{@code
 * NioUdpTransport transport = new NioUdpTransport(9000);
 * transport.register("sensor.data", msg -> System.out.println("Received: " + msg));
 * transport.start();
 *
 * // Send a message to a remote UDP listener
 * transport.send("localhost", 9000, "sensor.data", "Hello over UDP");
 *
 * // Later:
 * transport.shutdown();
 * }</pre>
 *
 * <p><b>Note:</b> Always call {@link #shutdown()} to release resources. Consider adding your own
 * reliability or acknowledgement layer if message loss is unacceptable.</p>
 */

public class NioUdpTransport implements Transport {
  Logger logger = Logger.getLogger(getClass().getName());

  private final Selector selector;
  private final DatagramChannel channel;
  private static final Charset charset = StandardCharsets.UTF_8;
  private final ExecutorService workerPool = Executors.newCachedThreadPool();
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private volatile boolean running = true;


  /**
   * Creates a new UDP transport bound to the specified local port.
   *
   * @param port the UDP port to listen on
   * @throws IOException if the selector or channel cannot be initialized or bound
   */
  public NioUdpTransport(int port) throws IOException {
    this.selector = Selector.open();
    this.channel = DatagramChannel.open();
    channel.configureBlocking(false);
    channel.bind(new InetSocketAddress(port));
    channel.register(selector, SelectionKey.OP_READ);
  }

  /**
   * Starts the selector loop on a background thread.
   * <p>When the channel is readable, a datagram is decoded as UTF-8, parsed as JSON,
   * and dispatched to handlers based on its {@code address} field and message type.</p>
   * <p><b>Implementation note:</b> The received buffer is reused per-iteration. Ensure
   * {@link DatagramChannel#receive(java.nio.ByteBuffer)} populates the buffer before decoding.</p>
   */
  @Override
  public void start() {
    workerPool.submit(() -> {
      ByteBuffer buffer = ByteBuffer.allocate(4096);
      while (running) {
        try {
          selector.select();
          for (SelectionKey key : selector.selectedKeys()) {
            if (key.isReadable()) {
              buffer.clear();
              buffer.flip();
              String jsonStr = charset.decode(buffer).toString();
              parseAndDispatchAsync(jsonStr, handlers, workerPool);
            }
          }
          selector.selectedKeys().clear();
        } catch (IOException e) {
          logger.severe(e.getMessage());
          logger.severe("Shutting EventBus down.");
          this.shutdown();
        }
      }
    }, "NioUdpTransport-Selector");
  }

  /**
   * Sends a point-to-point message to the specified host and port via UDP.
   *
   * @param host    target hostname or IP
   * @param port    target UDP port
   * @param address logical address/topic for routing by the receiver
   * @param message payload object to include in the JSON envelope
   */
  @Override
  public void send(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "send");
  }

  /**
   * Publishes a message intended for broadcast semantics on the receiver side.
   *
   * @param host    target hostname or IP
   * @param port    target UDP port
   * @param address logical address/topic for routing by the receiver
   * @param message payload object to include in the JSON envelope
   */
  @Override
  public void publish(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "publish");
  }

  /**
   * Serializes a message into a JSON envelope and sends it as a UDP datagram.
   *
   * @param host    target hostname or IP
   * @param port    target UDP port
   * @param address logical address/topic
   * @param message payload object
   * @param type    message type; typically {@code "send"} or {@code "publish"}
   */
  private void sendMessage(String host, int port, String address, Object message, String type) {
    try {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      ByteBuffer buffer = charset.encode(CharBuffer.wrap(json.toString()));
      channel.send(buffer, new InetSocketAddress(host, port));
    } catch (IOException e) {
      logger.warning("UDP send failed: " + e.getMessage());
    }
  }

  /**
   * Shuts down the transport and releases resources.
   * <p>Closes the selector and datagram channel and stops the event loop.</p>
   */
  @Override
  public void shutdown() {
    running = false;
    try {
      selector.close();
      channel.close();
    } catch (IOException e) {
      logger.severe("Failed to close selector: " + e.getMessage());
    }
  }

  /**
   * Registers a handler for messages received on the specified address.
   *
   * @param address the logical address/topic to listen on
   * @param handler the handler to process incoming payloads
   */
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }
}
