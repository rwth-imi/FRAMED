package com.framed.core;

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
import java.util.function.Consumer;

public class UDPTransport implements Transport {
  private final int port;
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private volatile boolean running = true;
  private DatagramSocket socket;

  public UDPTransport(int port) {
    this.port = port;
  }

  @Override
  public void start() {
    new Thread(() -> {
      try {
        socket = new DatagramSocket(port);
        byte[] buffer = new byte[4096];
        while (running) {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
          socket.receive(packet);
          String data = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
          JSONObject json = new JSONObject(data);
          String address = json.getString("address");
          Object payload = json.get("payload");

          List<Consumer<Object>> list = handlers.get(address);
          if (list != null) {
            for (Consumer<Object> handler : list) {
              handler.accept(payload);
            }
          }
        }
      } catch (IOException e) {
        if (running) throw new RuntimeException(e);
      }
    }, "UdpTransport-Listener").start();
  }

  @Override
  public void send(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message);
  }

  @Override
  public void publish(String host, int port, String address, Object message) {
    sendMessage(host, port, address, message);
  }

  private void sendMessage(String host, int port, String address, Object message) {
    try {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);

      byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
      DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), port);
      socket.send(packet);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void shutdown() {
    running = false;
    if (socket != null && !socket.isClosed()) {
      socket.close();
    }
  }

  @Override
  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }
}
