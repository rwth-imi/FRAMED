import com.framed.core.remote.Peer;
import com.framed.core.remote.SocketEventBus;
import com.framed.core.remote.TCPTransport;
import com.framed.core.utils.DispatchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SocketEventBusTcpTest {

  private SocketEventBus busA;
  private SocketEventBus busB;

  @BeforeEach
  public void setup() {
    // Bus A on port 6000
    TCPTransport transportA = new TCPTransport(5000);
    busA = new SocketEventBus(transportA, DispatchMode.SEQUENTIAL);

    // Bus B on port 6001
    TCPTransport transportB = new TCPTransport(5001);
    busB = new SocketEventBus(transportB, DispatchMode.SEQUENTIAL);

    // Add each other as peers
    busA.addPeer(new Peer("localhost", 5001));
    busB.addPeer(new Peer("localhost", 5000));
  }

  @AfterEach
  public void teardown() {
    if (busA != null) busA.shutdown();
    if (busB != null) busB.shutdown();
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

    // Wait for message to be received
    boolean success = latch.await(50, TimeUnit.MILLISECONDS);

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

    // Wait for both handlers to receive the message
    boolean success = latch.await(50, TimeUnit.MILLISECONDS);

    assertTrue(success, "Not all handlers received the message in time");
    assertEquals(message, received1.get());
    assertEquals(message, received2.get());
  }
}
