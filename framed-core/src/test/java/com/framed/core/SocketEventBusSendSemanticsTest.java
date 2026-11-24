// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core;

import com.framed.core.local.LocalEventBus;
import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.Transport;
import com.framed.core.utils.DispatchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code send} is point-to-point: one message reaches exactly one handler in the whole deployment.
 * The handler is chosen deterministically — the first local handler registered for the address
 * or, when the address has no local handler, the first peer added to the bus.
 */
class SocketEventBusSendSemanticsTest {

  /** Records what the bus hands to the transport, with its destination; delivers nothing. */
  static final class RecordingTransport implements Transport {
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override public void send(String host, int port, String address, Object message) {
      calls.add("SEND %s:%d %s %s".formatted(host, port, address, message));
    }
    @Override public void publish(String host, int port, String address, Object message) {
      calls.add("PUBLISH %s:%d %s %s".formatted(host, port, address, message));
    }
    @Override public void register(String address, Consumer<Object> handler) { /* inbound unused */ }
    @Override public void start() { /* nothing to start */ }
    @Override public void shutdown() { /* nothing to stop */ }

    /** Waits until at least {@code n} calls were recorded, or the timeout passes. */
    void awaitCalls(int n, long millis) throws InterruptedException {
      long deadline = System.currentTimeMillis() + millis;
      while (calls.size() < n && System.currentTimeMillis() < deadline) {
        Thread.sleep(5);
      }
    }
  }

  private final RecordingTransport transport = new RecordingTransport();
  private final SocketEventBus bus = new SocketEventBus(transport, DispatchMode.SEQUENTIAL);

  @AfterEach
  void tearDown() {
    bus.shutdown();
  }

  @Test
  void aSendReachesOnlyTheFirstLocalHandler() {
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    bus.register("addr", msg -> first.incrementAndGet());
    bus.register("addr", msg -> second.incrementAndGet());

    bus.send("addr", "m");

    assertEquals(1, first.get());
    assertEquals(0, second.get(), "send is point-to-point; a second handler must not receive it");
  }

  @Test
  void aSendServedLocallyIsNotForwardedToPeers() throws InterruptedException {
    bus.addPeer(new Peer("10.0.0.1", 4000));
    AtomicInteger local = new AtomicInteger();
    bus.register("addr", msg -> local.incrementAndGet());

    bus.send("addr", "m");
    transport.awaitCalls(1, 200);   // give a (wrong) asynchronous forward time to show up

    assertEquals(1, local.get());
    assertTrue(transport.calls.isEmpty(), "forwarded although a local handler took it: " + transport.calls);
  }

  @Test
  void aSendWithoutALocalHandlerGoesToTheFirstPeerOnly() throws InterruptedException {
    bus.addPeer(new Peer("10.0.0.1", 4000));
    bus.addPeer(new Peer("10.0.0.2", 4000));
    bus.addPeer(new Peer("10.0.0.3", 4000));

    bus.send("addr", "m");
    transport.awaitCalls(2, 300);   // a second call would be the defect; wait for it to appear

    assertEquals(List.of("SEND 10.0.0.1:4000 addr m"), transport.calls);
  }

  @Test
  void aSendWithNoHandlerAndNoPeerIsDroppedQuietly() {
    assertDoesNotThrow(() -> bus.send("nobody", "m"));
  }

  @Test
  void aPublishStillReachesEveryLocalHandlerAndEveryPeer() throws InterruptedException {
    bus.addPeer(new Peer("10.0.0.1", 4000));
    bus.addPeer(new Peer("10.0.0.2", 4000));
    AtomicInteger handlers = new AtomicInteger();
    bus.register("addr", msg -> handlers.incrementAndGet());
    bus.register("addr", msg -> handlers.incrementAndGet());

    bus.publish("addr", "m");
    transport.awaitCalls(2, 2_000);

    assertEquals(2, handlers.get());
    assertEquals(2, transport.calls.size());
    assertTrue(transport.calls.containsAll(List.of("PUBLISH 10.0.0.1:4000 addr m", "PUBLISH 10.0.0.2:4000 addr m")));
  }

  @Test
  void theLocalBusAlsoSendsToTheFirstHandlerOnly() {
    LocalEventBus local = new LocalEventBus(DispatchMode.SEQUENTIAL);
    try {
      AtomicInteger first = new AtomicInteger();
      AtomicInteger second = new AtomicInteger();
      local.register("addr", msg -> first.incrementAndGet());
      local.register("addr", msg -> second.incrementAndGet());
      local.send("addr", "m");
      assertEquals(1, first.get());
      assertEquals(0, second.get());
    } finally {
      local.shutdown();
    }
  }
}
