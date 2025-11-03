package com.safety_box.core;

import org.json.JSONException;
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

public class NioTcpTransport implements Transport {
  private final int port;
  private final Selector selector;
  private final ServerSocketChannel serverChannel;
  private final ExecutorService workerPool = Executors.newCachedThreadPool();
  private final Map<String, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
  private final Charset charset = StandardCharsets.UTF_8;
  private volatile boolean running = true;

  public NioTcpTransport(int port) throws IOException {
    this.port = port;
    this.selector = Selector.open();
    this.serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    serverChannel.bind(new InetSocketAddress("0.0.0.0", port));
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
  }

  @Override
  public void start() {
    Thread loop = new Thread(() -> {
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
        e.printStackTrace();
      }
    }, "NioTcpTransport-Selector");
    loop.setDaemon(true);
    loop.start();
  }

  private void handleAccept(SelectionKey key) throws IOException {
    ServerSocketChannel server = (ServerSocketChannel) key.channel();
    SocketChannel client = server.accept();
    client.configureBlocking(false);
    client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(4096));
  }

  private final Map<SocketChannel, StringBuilder> buffers = new ConcurrentHashMap<>();

  private void handleRead(SelectionKey key) {
    SocketChannel client = (SocketChannel) key.channel();
    ByteBuffer buffer = (ByteBuffer) key.attachment();
    try {
      int bytesRead = client.read(buffer);
      if (bytesRead == -1) {
        client.close();
        buffers.remove(client);
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

        try {
          JSONObject json = new JSONObject(jsonStr);
          String address = json.getString("address");
          Object payload = json.get("payload");

          List<Consumer<Object>> list = handlers.get(address);
          if (list != null) {
            for (Consumer<Object> handler : list) {
              workerPool.submit(() -> handler.accept(payload));
            }
          }
        } catch (JSONException e) {
          System.err.println("Invalid JSON: " + jsonStr);
        }
      }

    } catch (IOException e) {
      try {
        client.close();
      } catch (IOException ignored) {}
      buffers.remove(client);
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
      System.err.println("TCP send failed: " + e.getMessage());
    }
  }

  @Override
  public void shutdown() {
    running = false;
    try {
      selector.close();
      serverChannel.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    workerPool.shutdownNow();
  }

  public void register(String address, Consumer<Object> handler) {
    handlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
  }
}
