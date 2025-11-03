import com.framed.core.Peer;
import com.framed.core.SocketEventBus;
import com.framed.core.MockTransport;
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
    eventBus = new SocketEventBus(mockTransport);
    eventBus.addPeer(new Peer("localhost", 1234)); // Dummy peer
  }

  @Test
  public void testSendMessageToPeer() {
    AtomicReference<Object> received = new AtomicReference<>();

    mockTransport.register("test.address", received::set);
    eventBus.register("test.address", received::set);

    eventBus.send("test.address", "Hello");

    assertEquals("Hello", received.get());
    assertTrue(mockTransport.getSentMessages().contains("SEND:test.address:Hello"));
  }

  @Test
  public void testPublishMessageToPeer() {
    AtomicReference<Object> received1 = new AtomicReference<>();
    AtomicReference<Object> received2 = new AtomicReference<>();

    mockTransport.register("broadcast", received1::set);
    mockTransport.register("broadcast", received2::set);

    eventBus.register("broadcast", received1::set);
    eventBus.register("broadcast", received2::set);

    eventBus.publish("broadcast", "Broadcasting");

    assertEquals("Broadcasting", received1.get());
    assertEquals("Broadcasting", received2.get());
    assertTrue(mockTransport.getSentMessages().contains("PUBLISH:broadcast:Broadcasting"));
  }

  @Test
  public void testNoHandlerDoesNotCrash() {
    assertDoesNotThrow(() -> eventBus.send("unknown.address", "No one listens"));
  }
}
