import com.safety_box.core.Peer;
import com.safety_box.core.SocketEventBus;
import com.safety_box.core.UDPTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SocketEventBusUdpTest {

  private SocketEventBus busA;
  private SocketEventBus busB;

  @BeforeEach
  public void setup() {
    // Bus A on port 7000
    UDPTransport transportA = new UDPTransport(7000);
    busA = new SocketEventBus(transportA);

    // Bus B on port 7001
    UDPTransport transportB = new UDPTransport(7001);
    busB = new SocketEventBus(transportB);

    // Add each other as peers
    busA.addPeer(new Peer("localhost", 7001));
    busB.addPeer(new Peer("localhost", 7000));
  }

  @AfterEach
  public void teardown() {
    busA.shutdown();
    busB.shutdown();
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

    // Wait for message to be received
    boolean success = latch.await(50, TimeUnit.MILLISECONDS);

    assertTrue(success, "Message was not received in time");
    assertEquals(message, received.get());
  }

  @Test
  public void testPublishMessageToMultipleHandlers() throws InterruptedException {
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

    // Wait for both handlers to receive the message
    boolean success = latch.await(50, TimeUnit.MILLISECONDS);

    assertTrue(success, "Not all handlers received the published message");
    assertEquals(message, received1.get());
    assertEquals(message, received2.get());
  }
}
