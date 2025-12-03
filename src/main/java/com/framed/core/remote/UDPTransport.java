package com.framed.core.remote;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static com.framed.core.utils.RemoteUtils.parseAndDispatch;
import static com.framed.core.utils.RemoteUtils.parseAndDispatchAsync;


/** A {@link Transport} implementation using UDP datagrams for lightweight, connectionless messaging.
 * <p>This class provides a simple UDP-based per-handler blocking transport mechanism for sending and receiving messages
 * encoded as JSON objects. It supports asynchronous message handling and concurrent consumers.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Connectionless communication using {@link DatagramSocket}.</li>
 *   <li>Blocking message dispatch via an {@link ExecutorService}.</li>
 *   <li>Supports point-to-point ({@link #send}) and broadcast-like ({@link #publish}) semantics.</li>
 *   <li>Graceful shutdown via {@link #shutdown()}.</li>
 * </ul>
 *
 * <h2>Message Format:</h2>
 * <pre>{@code
 * {
 *   "address": "topic.name",
 *   "payload": ...
 * }
 * }</pre>
 *
 * <h2>Example usage:</h2>
 * <pre>{@code
 * UDPTransport transport = new UDPTransport(9090);
 * transport.register("sensor.data", msg -> System.out.println("Received: " + msg));
 * transport.start();
 *
 * transport.send("localhost", 9090, "sensor.data", "Hello over UDP");
 *
 * // Later:
 * transport.shutdown();
 * }</pre>
 *
 * <p><b>Note:</b> UDP is unreliable by design; messages may be lost or arrive out of order.</p>
 */
public class UDPTransport implements Transport {
  Logger logger = Logger.getLogger(getClass().getName());

  private final int port;
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private volatile boolean running = true;
  private DatagramSocket socket;
  private final ExecutorService workerPool = Executors.newCachedThreadPool();
  private final Map<Consumer<Object>, ExecutorService> handlerExecutors = new ConcurrentHashMap<>();

  /**
   * Creates a new UDP transport bound to the specified port.
   *
   * @param port the UDP port to listen on
   */
  public UDPTransport(int port) { this.port = port; }


  /**
   * Starts the UDP listener in a background thread.
   * <p>Receives datagrams, parses them as JSON, and dispatches to registered handlers.</p>
   */
  @Override
  public void start() {
    Thread listener = new Thread(() -> {
      try {
        socket = new DatagramSocket(port);
        byte[] buffer = new byte[4096];
        while (running) {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          socket.receive(packet);

          String data = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
          parseAndDispatch(data, handlers, handlerExecutors);
        }
      } catch (IOException e) {
        // Convert to unchecked only if still running; else it's a normal shutdown
        if (running) {
          throw new java.io.UncheckedIOException("UDP listener failed on port " + port, e);
        }
      }
    }, "UdpTransport-Listener");
    listener.setDaemon(true);
    listener.start();
  }




  /**
   * Sends a point-to-point message via UDP.
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
   */
  private void sendMessage(String host, int port, String address, Object message, String type) {
    try {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
      DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), port);
      socket.send(packet);
    } catch (IOException e) {
      logger.warning("Failed to send message " + e.getMessage());
    }
  }


  /**
   * Registers a handler for messages received on the specified address.
   *
   * @param address the logical address/topic to listen on
   * @param handler the handler to process incoming payloads
   */
  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
    handlerExecutors.put(handler, Executors.newSingleThreadExecutor());
  }

  /**
   * Shuts down the transport and releases resources.
   * <p>Stops receiving datagrams, closes the socket, and terminates the thread pool.</p>
   */
  @Override
  public void shutdown() {
    running = false;
    if (socket != null && !socket.isClosed()) {
      socket.close();
    }
    workerPool.shutdownNow(); // stop handler tasks
  }
}
