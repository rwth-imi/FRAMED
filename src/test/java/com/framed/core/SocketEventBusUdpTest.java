package com.framed.core;

import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.UDPTransport;
import com.framed.core.utils.DispatchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SocketEventBusUdpTest {

  private SocketEventBus busA;
  private SocketEventBus busB;

  private int portA;
  private int portB;
  private String loopbackHost;

  @BeforeEach
  public void setup() throws Exception {
    // Resolve a canonical loopback literal to avoid IPv4/IPv6 surprises on CI
    loopbackHost = InetAddress.getLoopbackAddress().getHostAddress();

    // Find two free UDP ports for this test instance
    portA = findFreeUdpPort();
    portB = findFreeUdpPort();

    // Build transports on dynamic ports
    UDPTransport transportA = new UDPTransport(portA);
    UDPTransport transportB = new UDPTransport(portB);

    // Create buses
    busA = new SocketEventBus(transportA, DispatchMode.SEQUENTIAL);
    busB = new SocketEventBus(transportB, DispatchMode.SEQUENTIAL);

    // Wire peers to each other using loopback
    busA.addPeer(new Peer(loopbackHost, portB));
    busB.addPeer(new Peer(loopbackHost, portA));

    // Small pause to let listener threads bind before sending (CI is slower)
    // If your UDPTransport exposes a "ready" signal, prefer that over sleep.
    Thread.sleep(25);
  }

  @AfterEach
  public void teardown() {
    // Defensive teardown so partial setup doesn't produce NPEs
    try {
      if (busA != null) busA.shutdown();
    } catch (Exception ignored) {}
    try {
      if (busB != null) busB.shutdown();
    } catch (Exception ignored) {}

    // Give the OS a breath to fully release sockets on CI
    try { Thread.sleep(25); } catch (InterruptedException ignored) {}
  }

  @Test
  public void testUdpMessageBetweenBuses() throws InterruptedException {
    String address = "udp.test";
    String message = "Hello over UDP";

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Object> received = new AtomicReference<>();

    busB.register(address, payload -> {
      received.set(payload);
      latch.countDown();
    });

    // Send from A to B
    busA.send(address, message);

    // Wait a bit longer on CI to avoid flakiness
    boolean success = latch.await(2, TimeUnit.SECONDS);

    assertTrue(success, "Message was not received in time");
    assertEquals(message, received.get());
  }

  @Test
  public void testPublishMessageToMultipleHandlers() throws InterruptedException {
    // Keeping the original address, though "udp" would be clearer
    String address = "broadcast.tcp";
    String message = "Broadcast message";

    CountDownLatch latch = new CountDownLatch(2);
    AtomicReference<Object> received1 = new AtomicReference<>();
    AtomicReference<Object> received2 = new AtomicReference<>();

    // Register two handlers on busB for the same address
    busB.register(address, payload -> {
      received1.set(payload);
      latch.countDown();
    });

    busB.register(address, payload -> {
      received2.set(payload);
      latch.countDown();
    });

    // Publish from busA to busB
    busA.publish(address, message);

    boolean success = latch.await(2, TimeUnit.SECONDS);

    assertTrue(success, "Not all handlers received the published message");
    assertEquals(message, received1.get());
    assertEquals(message, received2.get());
  }

  /**
   * Finds a currently free UDP port by letting the OS assign one to a temporary
   * socket and then releasing it. While there's a theoretical race between discovery
   * and bind, it's practically reliable for tests—especially since we need two distinct ports.
   */
  private static int findFreeUdpPort() throws Exception {
    try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}