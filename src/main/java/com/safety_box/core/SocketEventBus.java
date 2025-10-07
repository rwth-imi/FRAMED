package com.safety_box.core;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class SocketEventBus implements EventBusInterface {
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final String url;
  private int port;

  public SocketEventBus(int port, String url) {
    this.url = url;
    this.port = port;
    startServer(port);
  }

  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }

  @Override
  public void unregister(String address, Consumer<Object> handler) {
    List<Consumer<Object>> list = handlers.get(address);
    if (list != null) list.remove(handler);
  }

  @Override
  public void send(String address, Object message) {
    sendMessageOverSocket(address, message, "send");
  }

  @Override
  public void publish(String address, Object message) {
    sendMessageOverSocket(address, message, "publish");
  }

  private void startServer(int port) {
    executor.submit(() -> {
      try (ServerSocket serverSocket = new ServerSocket(port)) {
        while (true) {
          Socket client = serverSocket.accept();
          executor.submit(() -> handleClient(client));
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
  }

  private void handleClient(Socket client) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        JSONObject json = new JSONObject(line);
        String address = json.getString("address");
        Object payload = json.get("payload");
        String type = json.getString("type");

        List<Consumer<Object>> consumers = handlers.get(address);
        if (consumers != null) {
          for (Consumer<Object> handler : consumers) {
            try {
              handler.accept(payload);
            } catch (Exception e) {
              System.out.println(e.getMessage());
            }
            if ("send".equals(type)) break; // point-to-point
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void sendMessageOverSocket(String address, Object message, String type) {
    try (Socket socket = new Socket("localhost", port)) {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
      writer.println(json.toString());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}

