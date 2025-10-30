package com.safety_box.core;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
import java.util.function.Consumer;

public class NioUdpTransport implements Transport {
  private final int port;
  private final Selector selector;
  private final DatagramChannel channel;
  private final Charset charset = StandardCharsets.UTF_8;
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private volatile boolean running = true;

  public NioUdpTransport(int port) throws IOException {
    this.port = port;
    this.selector = Selector.open();
    this.channel = DatagramChannel.open();
    channel.configureBlocking(false);
    channel.bind(new InetSocketAddress(port));
    channel.register(selector, SelectionKey.OP_READ);
  }

  @Override
  public void start() {
    Thread loop = new Thread(() -> {
      ByteBuffer buffer = ByteBuffer.allocate(4096);
      while (running) {
        try {
          selector.select();
          for (SelectionKey key : selector.selectedKeys()) {
            if (key.isReadable()) {
              buffer.clear();
              SocketAddress sender = channel.receive(buffer);
              buffer.flip();
              String jsonStr = charset.decode(buffer).toString();

              JSONObject json = new JSONObject(jsonStr);
              String address = json.getString("address");
              Object payload = json.get("payload");

              String type = json.optString("type", "publish");

              List<Consumer<Object>> list = handlers.get(address);
              if (list != null) {
                if ("send".equals(type) && !list.isEmpty()) {
                  list.get(0).accept(payload);
                } else {
                  for (Consumer<Object> handler : list) {
                    handler.accept(payload);
                  }
                }
              }
            }
          }
          selector.selectedKeys().clear();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }, "NioUdpTransport-Selector");
    loop.setDaemon(true);
    loop.start();
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
    try {
      JSONObject json = new JSONObject();
      json.put("address", address);
      json.put("payload", message);
      json.put("type", type);

      ByteBuffer buffer = charset.encode(CharBuffer.wrap(json.toString()));
      channel.send(buffer, new InetSocketAddress(host, port));
    } catch (IOException e) {
      System.err.println("UDP send failed: " + e.getMessage());
    }
  }
  @Override
  public void shutdown() {
    running = false;
    try {
      selector.close();
      channel.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }
}
