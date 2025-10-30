package com.safety_box.core;

import java.util.Objects;

public class Peer {
  private final String host;
  private final int port;

  public Peer(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public String getHost() { return host; }
  public int getPort() { return port; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Peer peer)) return false;
    return port == peer.port && host.equals(peer.host);
  }

  @Override
  public int hashCode() {
    return Objects.hash(host, port);
  }
}

