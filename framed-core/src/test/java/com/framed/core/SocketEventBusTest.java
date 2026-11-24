// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.core;

import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.utils.DispatchMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SocketEventBusTest {

  private SocketEventBus eventBus;
  private MockTransport mockTransport;


  @BeforeEach
  public void setup() {
    mockTransport = new MockTransport();
    eventBus = new SocketEventBus(mockTransport, DispatchMode.SEQUENTIAL);
    eventBus.addPeer(new Peer("localhost", 1234)); // Dummy peer
  }

  @Test
  public void testSendPrefersALocalHandler() {
    AtomicReference<Object> received = new AtomicReference<>();
    eventBus.register("test.address", received::set);

    eventBus.send("test.address", "Hello");

    assertEquals("Hello", received.get());
    assertTrue(mockTransport.getSentMessages().isEmpty(),
            "a send taken by a local handler must not be forwarded");
  }

  @Test
  public void testSendWithoutLocalHandlerGoesToPeer() throws InterruptedException {
    AtomicReference<Object> received = new AtomicReference<>();
    mockTransport.register("test.address", received::set);   // the peer's side, simulated

    eventBus.send("test.address", "Hello");

    // Forwarding runs on the peer's writer thread; wait up to 1s for it to land.
    long deadline = System.currentTimeMillis() + 1_000;
    while (received.get() == null && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertEquals("Hello", received.get());
    assertTrue(mockTransport.getSentMessages().contains("SEND:test.address:Hello"));
  }

  @Test
  public void testPublishMessageToPeer() throws InterruptedException {
    AtomicReference<Object> received1 = new AtomicReference<>();
    AtomicReference<Object> received2 = new AtomicReference<>();

    mockTransport.register("broadcast", received1::set);
    mockTransport.register("broadcast", received2::set);

    eventBus.register("broadcast", received1::set);
    eventBus.register("broadcast", received2::set);

    eventBus.publish("broadcast", "Broadcasting");

    assertEquals("Broadcasting", received1.get());
    assertEquals("Broadcasting", received2.get());

    // Peer publish is submitted to a thread pool; wait up to 1s for it to land.
    long deadline = System.currentTimeMillis() + 1_000;
    while (!mockTransport.getSentMessages().contains("PUBLISH:broadcast:Broadcasting")
           && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(mockTransport.getSentMessages().contains("PUBLISH:broadcast:Broadcasting"));
  }

  @Test
  public void testNoHandlerDoesNotCrash() {
    assertDoesNotThrow(() -> eventBus.send("unknown.address", "No one listens"));
  }
}
