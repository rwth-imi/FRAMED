package com.safety_box.core;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SocketEventBus implements EventBus {

  private record Registration(Consumer<Object> handler, ExecutorService executor) {}

  private final Map<String, CopyOnWriteArrayList<Registration>> handlers = new ConcurrentHashMap<>();

  // I/O executors: accept loop + client readers
  private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "SocketEventBus-io-" + THREAD_SEQ.getAndIncrement());
    t.setDaemon(true);
    return t;
  });

  private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

  private final String host;
  private final int port;
  private volatile boolean running = true;

  public SocketEventBus(int port, String host) {
    this.host = host;
    this.port = port;
    startServer(port);
  }

  @Override
  public void register(String address, Consumer<Object> handler) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(handler, "handler");

    // Create a dedicated single-thread executor for this handler
    ExecutorService single = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "SocketEventBus-handler-" + address + "-" + THREAD_SEQ.getAndIncrement());
      t.setDaemon(true);
      return t;
    });

    Registration reg = new Registration(handler, single);
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(reg);
  }

  @Override
  public void unregister(String address, Consumer<Object> handler) {
    CopyOnWriteArrayList<Registration> list = handlers.get(address);
    if (list == null) return;

    for (Registration reg : list) {
      if (reg.handler == handler) {
        list.remove(reg);
        // Shut down the dedicated executor for this handler
        reg.executor.shutdown();
        break;
      }
    }
    if (list.isEmpty()) {
      handlers.remove(address);
    }
  }

  @Override
  public void send(String address, Object message) {
    sendMessageOverSocket(address, message, "send");
  }

  @Override
  public void publish(String address, Object message) {
    sendMessageOverSocket(address, message, "publish");
  }

  // Optional: allow clean shutdown
  public void shutdown() {
    running = false;
    ioExecutor.shutdownNow();
    // Shutdown all handler executors
    for (Map.Entry<String, CopyOnWriteArrayList<Registration>> e : handlers.entrySet()) {
      for (Registration reg : e.getValue()) {
        reg.executor.shutdown();
      }
      e.getValue().clear();
    }
    handlers.clear();
  }

  private void startServer(int port) {
    ioExecutor.submit(() -> {
      try (ServerSocket serverSocket = new ServerSocket(port)) {
        while (running) {
          Socket client = serverSocket.accept();
          ioExecutor.submit(() -> handleClient(client));
        }
      } catch (IOException e) {
        if (running) e.printStackTrace();
      }
    });
  }

  private void handleClient(Socket client) {
    try (BufferedReader reader =
           new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        JSONObject json = new JSONObject(line);
        String address = json.getString("address");
        Object payload = json.get("payload");
        String type = json.getString("type");

        CopyOnWriteArrayList<Registration> registrations = handlers.get(address);
        if (registrations != null && !registrations.isEmpty()) {
          // For "send", submit only to the first handler
          if ("send".equals(type)) {
            Registration first = registrations.get(0);
            submitToHandler(first, payload);
          } else {
            // "publish": submit to all handlers for that address
            for (Registration reg : registrations) {
              submitToHandler(reg, payload);
            }
          }
        }
      }
    } catch (IOException e) {
      if (running) e.printStackTrace();
    } finally {
      try {
        client.close();
      } catch (IOException ignore) {
      }
    }
  }

  private void submitToHandler(Registration reg, Object payload) {
    reg.executor.execute(() -> {
      try {
        reg.handler.accept(payload);
      } catch (Throwable t) {
        // Keep the executor alive even if handler throws
        t.printStackTrace();
      }
    });
  }

  private void sendMessageOverSocket(String address, Object message, String type) {
    try (Socket socket = new Socket(host, port)) {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
      writer.println(json);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
