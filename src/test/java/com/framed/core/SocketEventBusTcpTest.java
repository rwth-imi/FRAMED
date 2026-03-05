package com.framed.core;

import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.TCPTransport;
import com.framed.core.utils.DispatchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SocketEventBusTcpTest {

  private SocketEventBus busA;
  private SocketEventBus busB;

  private int portA;
  private int portB;
  private String loopbackHost;

  @BeforeEach
  public void setup() throws Exception {
    // Use canonical loopback literal to avoid IPv6/IPv4 mismatch on CI hosts
    loopbackHost = InetAddress.getLoopbackAddress().getHostAddress();

    // Get two free TCP ports for this test instance
    portA = findFreeTcpPort();
    portB = findFreeTcpPort();

    // Create transports on dynamic ports
    TCPTransport transportA = new TCPTransport(portA);
    TCPTransport transportB = new TCPTransport(portB);

    // Create buses
    busA = new SocketEventBus(transportA, DispatchMode.SEQUENTIAL);
    busB = new SocketEventBus(transportB, DispatchMode.SEQUENTIAL);

    // Wire peers to each other
    busA.addPeer(new Peer(loopbackHost, portB));
    busB.addPeer(new Peer(loopbackHost, portA));

    // Give the listener threads a brief moment to start (prefer an explicit "ready" if available)
    Thread.sleep(25);
  }

  @AfterEach
  public void teardown() {
    try {
      if (busA != null) busA.shutdown();
    } catch (Exception ignored) {}
    try {
      if (busB != null) busB.shutdown();
    } catch (Exception ignored) {}

    // Yield to allow the OS to fully release listening sockets on CI runners
    try { Thread.sleep(25); } catch (InterruptedException ignored) {}
  }

  @Test
  public void testSendMessageBetweenBuses() throws InterruptedException {
    String address = "test.tcp";
    String message = "Hello over TCP";

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Object> received = new AtomicReference<>();

    busB.register(address, payload -> {
      received.set(payload);
      latch.countDown();
    });

    // Send from A to B
    busA.send(address, message);

    // CI can be slower; wait a bit longer to avoid flakiness
    boolean success = latch.await(2, TimeUnit.SECONDS);

    assertTrue(success, "Message was not received in time");
    assertEquals(message, received.get());
  }

  @Test
  public void testPublishMessageToMultipleHandlers() throws InterruptedException {
    String address = "broadcast.tcp";
    String message = "Broadcast over TCP";

    CountDownLatch latch = new CountDownLatch(2);
    AtomicReference<Object> received1 = new AtomicReference<>();
    AtomicReference<Object> received2 = new AtomicReference<>();

    // Register two handlers on busB
    busB.register(address, payload -> {
      received1.set(payload);
      latch.countDown();
    });

    busB.register(address, payload -> {
      received2.set(payload);
      latch.countDown();
    });

    // Publish from A to B
    busA.publish(address, message);

    boolean success = latch.await(2, TimeUnit.SECONDS);

    assertTrue(success, "Not all handlers received the message in time");
    assertEquals(message, received1.get());
    assertEquals(message, received2.get());
  }

  /** Finds a currently free TCP port by binding a ServerSocket to port 0 on loopback. */
  private static int findFreeTcpPort() throws Exception {
    try (ServerSocket seocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      seocket.setReuseAddress(true);
      return seocket.getLocalPort();
    }
  }
}