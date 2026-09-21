package com.framed.core;

import com.framed.core.remote.NioTcpTransport;
import com.framed.core.remote.NioUdpTransport;
import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.Transport;
import com.framed.core.utils.DispatchMode;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two-instance path: a {@link SocketEventBus} publishing to a peer bus over each
 * {@link Transport}. Both directions of this had no test, which is how the UDP receive loop shipped
 * unable to receive anything (see {@link NioUdpTransportTest}).
 */
class SocketEventBusPeerTest {

  private final List<SocketEventBus> buses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    buses.forEach(SocketEventBus::shutdown);
  }

  private SocketEventBus bus(Transport transport) {
    SocketEventBus b = new SocketEventBus(transport, DispatchMode.PER_HANDLER);
    buses.add(b);
    return b;
  }

  /**
   * Binds a transport on a free port, retrying the bind rather than trusting a probe: a port that
   * probes free can be taken before the transport binds it, and probing with the wrong protocol
   * (a UDP probe for a TCP bind) says nothing at all.
   */
  private Transport boundTransport(boolean tcp) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < 25; attempt++) {
      int port;
      if (tcp) {
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
      } else {
        try (DatagramSocket probe = new DatagramSocket(0)) { port = probe.getLocalPort(); }
      }
      try {
        return tcp ? new NioTcpTransport(port) : new NioUdpTransport(port);
      } catch (BindException raced) {
        last = raced;
      }
    }
    throw last;
  }

  private void deliversToPeer(boolean tcp) throws Exception {
    Transport readerTransport = boundTransport(tcp);
    int readerPort = portOf(readerTransport);
    SocketEventBus reader = bus(readerTransport);

    CountDownLatch got = new CountDownLatch(1);
    AtomicReference<Object> payload = new AtomicReference<>();
    reader.register("Waveform.DEV.II.parsed", msg -> {
      payload.set(msg);
      got.countDown();
    });

    SocketEventBus producer = bus(boundTransport(tcp));
    producer.addPeer(new Peer("127.0.0.1", readerPort));

    producer.publish("Waveform.DEV.II.parsed", new JSONObject().put("value", 7.5));

    assertTrue(got.await(10, TimeUnit.SECONDS),
            "the peer bus received nothing over %s".formatted(tcp ? "TCP" : "UDP"));
    assertEquals(7.5, new JSONObject(payload.get().toString()).getDouble("value"));
  }

  /** The transports do not expose their bound port, so read it off the channel for the test. */
  private static int portOf(Transport transport) {
    try {
      for (Field f : transport.getClass().getDeclaredFields()) {
        f.setAccessible(true);
        Object v = f.get(transport);
        if (v instanceof ServerSocketChannel c) {
          return ((java.net.InetSocketAddress) c.getLocalAddress()).getPort();
        }
        if (v instanceof DatagramChannel c) {
          return ((java.net.InetSocketAddress) c.getLocalAddress()).getPort();
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot read the transport's bound port", e);
    }
    throw new IllegalStateException("transport exposes no channel");
  }

  @Test
  void deliversToAPeerOverTcp() throws Exception {
    deliversToPeer(true);
  }

  @Test
  void deliversToAPeerOverUdp() throws Exception {
    deliversToPeer(false);
  }

  @Test
  void aPublishWithNoPeersStaysLocal() throws Exception {
    SocketEventBus solo = bus(boundTransport(true));
    CountDownLatch local = new CountDownLatch(1);
    solo.register("topic", msg -> local.countDown());
    solo.publish("topic", new JSONObject().put("v", 1));
    assertTrue(local.await(5, TimeUnit.SECONDS), "a peerless bus must still dispatch locally");
  }
}
