package com.framed.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class SocketEventBus implements EventBus {
  private final Transport transport;
  private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
  private final Map<String, List<Consumer<Object>>> localHandlers = new ConcurrentHashMap<>();

  public SocketEventBus(Transport transport) {
    this.transport = transport;
    this.transport.start();
  }

  public void addPeer(Peer peer) {
    peers.add(peer);
  }

  public void removePeer(Peer peer) {
    peers.remove(peer);
  }

  @Override
  public void register(String address, Consumer<Object> handler) {
    localHandlers.computeIfAbsent(address, k -> new CopyOnWriteArrayList<>()).add(handler);
    transport.register(address, handler);
  }

  @Override
  public void send(String address, Object message) {
    dispatchLocally(address, message);
    for (Peer peer : peers) {
      transport.send(peer.host(), peer.port(), address, message);
    }
  }

  @Override
  public void publish(String address, Object message) {
    dispatchLocally(address, message);
    for (Peer peer : peers) {
      transport.publish(peer.host(), peer.port(), address, message);
    }
  }

  private void dispatchLocally(String address, Object message) {
    List<Consumer<Object>> handlers = localHandlers.get(address);
    if (handlers != null) {
      for (Consumer<Object> handler : handlers) {
        handler.accept(message);
      }
    }
  }

  public void shutdown() {
    transport.shutdown();
    localHandlers.clear();
    peers.clear();
  }
}
