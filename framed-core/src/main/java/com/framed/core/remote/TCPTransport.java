// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core.remote;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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

import static com.framed.core.utils.RemoteUtils.parseAndDispatch;

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
 * <h2>Connection model and ordering</h2>
 * <p>One outbound connection is kept per target {@code host:port} and reused for every message, and
 * writes to it are serialized. Each accepted connection is read by one thread, and every handler
 * runs on its own single-thread executor, so a handler receives the messages of one sender in the
 * order they were sent. (An earlier version opened a connection per message; the receiver read
 * each on its own thread, so consecutive messages could overtake each other.)</p>
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
  /** Outbound connections, one per {@code host:port}, opened on first use and reused. */
  private final Map<String, PeerConnection> connections = new ConcurrentHashMap<>();
  /** Accepted inbound connections, closed on shutdown. */
  private final Set<Socket> clients = ConcurrentHashMap.newKeySet();


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
          clients.add(client);
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
        if (line.isBlank()) {
          continue;
        }
        try {
          parseAndDispatch(line, handlers, handlerExecutors);
        } catch (RuntimeException malformed) {
          // One corrupt line must not end the connection: it now carries every later message.
          logger.warning("Discarding malformed TCP message: " + malformed);
        }
      }
    } catch (IOException e) {
      if (running) {
        logger.warning("Error while handling client " + client);
      }
    } finally {
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
    } finally {
      clients.remove(client);
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
    if (!running) {
      logger.warning("Dropping message to %s:%d: transport is shut down.".formatted(host, port));
      return;
    }
    JSONObject json = new JSONObject();
    json.put("address", address);
    json.put("payload", message);
    json.put("type", type);
    byte[] line = "%s\n".formatted(json).getBytes(StandardCharsets.UTF_8);
    try {
      connections.computeIfAbsent(host + ":" + port, k -> new PeerConnection(host, port)).write(line);
    } catch (IOException e) {
      logger.warning("Failed to send message to %s:%d: %s".formatted(host, port, e.getMessage()));
    }
  }

  /**
   * A reused outbound connection to one peer. Writes are serialized, so concurrent senders cannot
   * interleave partial lines, and the order of the writes is the order of the messages.
   */
  private final class PeerConnection {
    private final String host;
    private final int port;
    private volatile Socket socket;
    private OutputStream out;

    private PeerConnection(String host, int port) {
      this.host = host;
      this.port = port;
    }

    /** Writes one line, (re)opening the connection as needed; retries once on a stale one. */
    private synchronized void write(byte[] line) throws IOException {
      if (socket == null || socket.isClosed()) {
        open();
      }
      try {
        out.write(line);
        out.flush();
      } catch (IOException stale) {
        if (!running) {
          throw stale;
        }
        // A cached connection can have died since its last use; that shows only on write.
        close();
        open();
        out.write(line);
        out.flush();
      }
    }

    private void open() throws IOException {
      Socket s = new Socket(host, port);
      s.setTcpNoDelay(true);
      socket = s;
      out = s.getOutputStream();
    }

    /** Not synchronized, so shutdown can unblock a write stuck on a full receive window. */
    private void close() {
      Socket s = socket;
      socket = null;
      if (s != null) {
        try {
          s.close();
        } catch (IOException e) {
          logger.warning("Failed to close connection to %s:%d: %s".formatted(host, port, e.getMessage()));
        }
      }
    }
  }

  /**
   * Shuts down the transport and releases resources.
   * <p>Stops accepting new connections, closes the server socket, and terminates the thread pool.</p>
   */
  @Override
  public void shutdown() {
    running = false;
    connections.values().forEach(PeerConnection::close);
    connections.clear();
    clients.forEach(this::closeClient);
    handlerExecutors.values().forEach(ExecutorService::shutdownNow);
    handlerExecutors.clear();
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
   * Registers a handler for messages received on the specified address. Its single-thread
   * executor is created on the first message for it.
   * @param address the logical address/topic to listen on
   * @param handler the handler to process incoming payloads
   */
  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }

}
