package com.framed.core;

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

public class TCPTransport implements Transport {
  private final int port;
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private volatile boolean running = true;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private ServerSocket serverSocket;

  public TCPTransport(int port) {
    this.port = port;
  }

  @Override
  public void start() {
    executor.submit(() -> {
      try {
        serverSocket = new ServerSocket(port);
        while (running) {
          Socket client = serverSocket.accept();
          executor.submit(() -> handleClient(client));
        }
      } catch (IOException e) {
        if (running) throw new RuntimeException(e);
      }
    });
  }

  private void handleClient(Socket client) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        JSONObject json = new JSONObject(line);
        String address = json.getString("address");
        Object payload = json.get("payload");
        String type = json.getString("type");

        List<Consumer<Object>> list = handlers.get(address);
        if (list != null) {
          for (Consumer<Object> handler : list) {
            handler.accept(payload);
            if ("send".equals(type)) break;
          }
        }
      }
    } catch (IOException e) {
      if (running) throw new RuntimeException(e);
    }
  }

  @Override
  public void send(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "send");
  }

  @Override
  public void publish(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message, "publish");
  }

  private void sendMessage(String host, int port, String address, Object message, String type) {
    try (Socket socket = new Socket(host, port)) {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
      writer.println(json.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void shutdown() {
    running = false;
    executor.shutdownNow();
    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }
}
