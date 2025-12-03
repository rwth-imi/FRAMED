package com.framed.core.remote;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
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

/**
 * A {@link Transport} implementation using traditional blocking I/O over TCP sockets.
 * <p>This class provides a simple server that accepts incoming TCP connections and processes
 * messages line-by-line using a JSON-based protocol. Each message is expected to be a single
 * JSON object containing {@code address}, {@code payload}, and {@code type} fields.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Blocking I/O using {@link ServerSocket} and {@link Socket}.</li>
 *   <li>Handles multiple clients concurrently using an {@link ExecutorService}.</li>
 *   <li>Supports point-to-point ({@link #send}) and broadcast ({@link #publish}) messaging.</li>
 *   <li>Graceful shutdown via {@link #shutdown()}.</li>
 * </ul>
 *
 * <h2>Message Format:</h2>
 * <pre>{@code
 * {
 *   "address": "topic.name",
 *   "payload": ...,
 *   "type": "send" | "publish"
 * }
 * }</pre>
 *
 * <h2>Example usage:</h2>
 * <pre>{@code
 * TCPTransport transport = new TCPTransport(8080);
 * transport.register("sensor.data", msg -> System.out.println("Received: " + msg));
 * transport.start();
 *
 * transport.send("localhost", 8080, "sensor.data", "Hello over TCP");
 *
 * // Later:
 * transport.shutdown();
 * }</pre>
 *
 * <p><b>Note:</b> Always call {@link #shutdown()} to release resources and stop the server.</p>
 */
public class TCPTransport implements Transport {
  Logger logger = Logger.getLogger(getClass().getName());

  private final int port;
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private volatile boolean running = true;
  private final ExecutorService workerPool = Executors.newCachedThreadPool();
  private final Map<Consumer<Object>, ExecutorService> handlerExecutors = new ConcurrentHashMap<>();
  private ServerSocket serverSocket;


  /**
   * Creates a new TCP transport bound to the specified port.
   *
   * @param port the TCP port to listen on
   */
  public TCPTransport(int port) {
    this.port = port;
  }

  /**
   * Starts the TCP server in a background thread.
   * <p>Accepts incoming connections and delegates each client to {@link #handleClient(Socket)}.</p>
   */
  @Override
  public void start() {
    workerPool.submit(() -> {
      try {
        serverSocket = new ServerSocket(port);
        while (running) {
          Socket client = serverSocket.accept();
          workerPool.submit(() -> handleClient(client));
        }
      } catch (IOException e) {
        logger.severe(e.getMessage());
        if (running) {
          logger.severe("Shutting EventBus down.");
          this.shutdown();
        }
      }
    });
  }

  /**
   * Handles communication with a single client.
   * <p>Reads messages line-by-line, parses them as JSON, and dispatches to registered handlers.</p>
   *
   * @param client the client socket
   */
  private void handleClient(Socket client) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        parseAndDispatch(line, handlers, handlerExecutors);
      }
    } catch (IOException e) {
      logger.warning("Error while handling client " + client);
      closeClient(client);
    }
  }

  /**
   * Closes a client connection.
   *
   * @param client the client socket channel
   */
  private void closeClient(Socket client) {
    try {
      client.close();
    } catch (IOException e) {
      logger.warning("Failed to close client: " + e.getMessage());
    }
  }

  /**
   * Sends a point-to-point message to the specified host and port via TCP.
   *
   * @param host    target hostname or IP
   * @param port    target TCP port
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
   * @param port    target TCP port
   * @param address logical address/topic for routing by the receiver
   * @param message payload object to include in the JSON envelope
   */
  @Override
  public void publish(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "publish");
  }


  /**
   * Serializes a message into a JSON envelope and sends it over a TCP socket.
   *
   * @param host    target hostname or IP
   * @param port    target TCP port
   * @param address logical address/topic
   * @param message payload object
   * @param type    message type; typically {@code "send"} or {@code "publish"}
   */
  private void sendMessage(String host, int port, String address, Object message, String type) {
    try (Socket socket = new Socket(host, port)) {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
      writer.println(json);
    } catch (IOException e) {
      logger.warning("Failed to send message " + e.getMessage());
    }
  }

  /**
   * Shuts down the transport and releases resources.
   * <p>Stops accepting new connections, closes the server socket, and terminates the thread pool.</p>
   */
  @Override
  public void shutdown() {
    running = false;
    handlerExecutors.values().forEach(ExecutorService::shutdownNow);
    workerPool.shutdownNow();
    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
      }
    } catch (IOException e) {
      logger.severe("Failed to close server socket: " + e.getMessage());
    }

  }

  /**
   * Registers a handler for messages received on the specified address.
   * Creates a single threaded executor for that handler.
   * @param address the logical address/topic to listen on
   * @param handler the handler to process incoming payloads
   */
  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
    handlerExecutors.put(handler, Executors.newSingleThreadExecutor());
  }

}
