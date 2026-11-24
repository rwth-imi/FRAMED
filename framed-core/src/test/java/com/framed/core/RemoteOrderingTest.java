// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core;

import com.framed.core.remote.NioTcpTransport;
import com.framed.core.remote.NioUdpTransport;
import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.TCPTransport;
import com.framed.core.remote.Transport;
import com.framed.core.utils.DispatchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Messages from one sender to one remote handler arrive in the order they were sent.
 *
 * <p>A {@link com.framed.arn.Reactor} discards a message whose tag is not newer than the last one
 * it accepted on that channel, so a reordered pair silently costs a firing. Two places could
 * reorder: the sending bus fanning out to peers, and the receiving transport invoking handlers.
 * Both are exercised here with deliberately uneven per-message work, which is what lets a
 * multi-threaded pool overtake itself.</p>
 */
class RemoteOrderingTest {

  private static final String ADDRESS = "seq";
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

  // --------------------------------------------------------------------------------------------
  // Sending side
  // --------------------------------------------------------------------------------------------

  /** A transport whose writes take a short, random time, and which records what it wrote. */
  static final class JitteryTransport implements Transport {
    final List<Object> written = Collections.synchronizedList(new ArrayList<>());
    final CountDownLatch done;

    JitteryTransport(int expected) { done = new CountDownLatch(expected); }

    private void write(Object message) {
      LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(0, 200_000));
      written.add(message);
      done.countDown();
    }
    @Override public void send(String host, int port, String address, Object message) { write(message); }
    @Override public void publish(String host, int port, String address, Object message) { write(message); }
    @Override public void register(String address, Consumer<Object> handler) { /* unused */ }
    @Override public void start() { /* unused */ }
    @Override public void shutdown() { /* unused */ }
  }

  @Test
  void publicationsLeaveForAPeerInPublicationOrder() throws InterruptedException {
    int n = 2_000;
    JitteryTransport transport = new JitteryTransport(n);
    SocketEventBus producer = bus(transport);
    producer.addPeer(new Peer("127.0.0.1", 1));

    for (int i = 0; i < n; i++) producer.publish(ADDRESS, i);

    assertTrue(transport.done.await(60, TimeUnit.SECONDS), "not every publication reached the transport");
    assertStrictlyIncreasing(transport.written, n);
  }

  @Test
  void sendsAndPublicationsToOnePeerShareOneOrder() throws InterruptedException {
    int n = 1_000;
    JitteryTransport transport = new JitteryTransport(n);
    SocketEventBus producer = bus(transport);
    producer.addPeer(new Peer("127.0.0.1", 1));

    for (int i = 0; i < n; i++) {
      if (i % 2 == 0) producer.send(ADDRESS, i); else producer.publish(ADDRESS, i);
    }

    assertTrue(transport.done.await(60, TimeUnit.SECONDS), "not every message reached the transport");
    assertStrictlyIncreasing(transport.written, n);
  }

  // --------------------------------------------------------------------------------------------
  // Over real sockets
  // --------------------------------------------------------------------------------------------

  @Test
  void aRemoteHandlerSeesMessagesInSendOrderOverNioTcp() throws Exception {
    Transport reader = boundNio(true);
    deliversInOrder(reader, portOf(reader), boundNio(true), 2_000, true);
  }

  @Test
  void aRemoteHandlerSeesMessagesInSendOrderOverBlockingTcp() throws Exception {
    int readerPort = freeTcpPort();
    TCPTransport reader = new TCPTransport(readerPort);
    TCPTransport writer = new TCPTransport(freeTcpPort());
    deliversInOrder(reader, readerPort, writer, 2_000, true);
  }

  /**
   * UDP may lose datagrams, and the network may reorder them; on loopback it does neither in
   * practice. What the transport must not do is reorder what it received, so the check here is
   * that the received messages are in order, whatever their number.
   */
  @Test
  void aRemoteHandlerSeesReceivedDatagramsInArrivalOrderOverNioUdp() throws Exception {
    Transport reader = boundNio(false);
    deliversInOrder(reader, portOf(reader), boundNio(false), 300, false);
  }

  private void deliversInOrder(Transport readerTransport, int readerPort, Transport writerTransport,
                               int n, boolean lossless) throws Exception {
    SocketEventBus reader = bus(readerTransport);
    List<Object> seen = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch all = new CountDownLatch(n);
    reader.register(ADDRESS, msg -> {
      int v = ((Number) msg).intValue();
      if (v % 5 == 0) LockSupport.parkNanos(300_000);   // uneven work lets a pool overtake itself
      seen.add(v);
      all.countDown();
    });
    Thread.sleep(50);   // the blocking transports bind on their own thread

    SocketEventBus writer = bus(writerTransport);
    writer.addPeer(new Peer("127.0.0.1", readerPort));
    for (int i = 0; i < n; i++) {
      writer.publish(ADDRESS, i);
      if (!lossless && i % 10 == 0) LockSupport.parkNanos(200_000);   // keep UDP below the socket buffer
    }

    boolean complete = all.await(lossless ? 60 : 10, TimeUnit.SECONDS);
    if (lossless) {
      assertTrue(complete, "only %d of %d messages arrived".formatted(seen.size(), n));
      assertStrictlyIncreasing(seen, n);
    } else {
      assertTrue(!seen.isEmpty(), "no datagram arrived");
      assertStrictlyIncreasing(seen, seen.size());
    }
  }

  // --------------------------------------------------------------------------------------------
  // Point-to-point on the receiving side
  // --------------------------------------------------------------------------------------------

  @Test
  void aRemoteSendReachesOnlyTheFirstHandlerOverBlockingTcp() throws Exception {
    int readerPort = freeTcpPort();
    remoteSendIsPointToPoint(new TCPTransport(readerPort), readerPort, new TCPTransport(freeTcpPort()));
  }

  @Test
  void aRemoteSendReachesOnlyTheFirstHandlerOverNioTcp() throws Exception {
    Transport reader = boundNio(true);
    remoteSendIsPointToPoint(reader, portOf(reader), boundNio(true));
  }

  private void remoteSendIsPointToPoint(Transport readerTransport, int readerPort, Transport writerTransport)
          throws Exception {
    SocketEventBus reader = bus(readerTransport);
    CountDownLatch first = new CountDownLatch(1);
    AtomicInteger second = new AtomicInteger();
    reader.register(ADDRESS, msg -> first.countDown());
    reader.register(ADDRESS, msg -> second.incrementAndGet());
    Thread.sleep(50);

    SocketEventBus writer = bus(writerTransport);
    writer.addPeer(new Peer("127.0.0.1", readerPort));
    writer.send(ADDRESS, 1);

    assertTrue(first.await(10, TimeUnit.SECONDS), "the first handler never received the send");
    Thread.sleep(300);   // room for a wrong second delivery to land
    assertEquals(0, second.get(), "a remote send reached a second handler");
  }

  // --------------------------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------------------------

  private static void assertStrictlyIncreasing(List<Object> values, int expectedSize) {
    List<Object> copy;
    synchronized (values) { copy = new ArrayList<>(values); }
    assertEquals(expectedSize, copy.size(), "unexpected number of messages");
    for (int i = 1; i < copy.size(); i++) {
      int previous = ((Number) copy.get(i - 1)).intValue();
      int current = ((Number) copy.get(i)).intValue();
      if (current <= previous) {
        fail("out of order at position %d: %d after %d".formatted(i, current, previous));
      }
    }
  }

  private static int freeTcpPort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      probe.setReuseAddress(true);
      return probe.getLocalPort();
    }
  }

  /** Binds an NIO transport on a free port, retrying the bind rather than trusting a probe. */
  private static Transport boundNio(boolean tcp) throws IOException {
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

  /** The NIO transports do not expose their bound port, so read it off the channel. */
  private static int portOf(Transport transport) {
    try {
      for (Field f : transport.getClass().getDeclaredFields()) {
        f.setAccessible(true);
        Object v = f.get(transport);
        if (v instanceof ServerSocketChannel c) return ((InetSocketAddress) c.getLocalAddress()).getPort();
        if (v instanceof DatagramChannel c) return ((InetSocketAddress) c.getLocalAddress()).getPort();
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot read the transport's bound port", e);
    }
    throw new IllegalStateException("transport exposes no channel");
  }
}
