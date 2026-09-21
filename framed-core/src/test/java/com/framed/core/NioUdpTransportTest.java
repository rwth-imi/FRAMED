package com.framed.core;

import com.framed.core.remote.NioUdpTransport;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link NioUdpTransport}'s receive loop.
 *
 * <p>The loop used to clear its buffer and immediately flip it without ever calling
 * {@code channel.receive(..)}, so it decoded an empty buffer, dispatched nothing, and — because the
 * datagram was never consumed — left the selection key perpetually ready and spun. A deployment
 * configured with {@code "type": "UDP"} therefore received no remote traffic at all, silently.</p>
 */
class NioUdpTransportTest {

  private final List<NioUdpTransport> started = new ArrayList<>();

  @AfterEach
  void tearDown() {
    started.forEach(NioUdpTransport::shutdown);
  }

  private NioUdpTransport listening(int port) throws IOException {
    NioUdpTransport transport = new NioUdpTransport(port);
    started.add(transport);
    transport.start();
    return transport;
  }

  private static int freePort() throws IOException {
    try (DatagramSocket probe = new DatagramSocket(0)) {
      return probe.getLocalPort();
    }
  }

  @Test
  void deliversAPublishedMessageToARegisteredHandler() throws Exception {
    int port = freePort();
    NioUdpTransport receiver = listening(port);

    CountDownLatch got = new CountDownLatch(1);
    List<Object> payloads = new ArrayList<>();
    receiver.register("Waveform.DEV.II.parsed", msg -> {
      synchronized (payloads) {
        payloads.add(msg);
      }
      got.countDown();
    });

    NioUdpTransport sender = listening(freePort());
    JSONObject sample = new JSONObject().put("channelID", "II").put("value", 42.0);
    sender.publish("127.0.0.1", port, "Waveform.DEV.II.parsed", sample);

    assertTrue(got.await(10, TimeUnit.SECONDS),
            "nothing arrived: the UDP transport is not reading its datagrams");
    synchronized (payloads) {
      assertEquals(1, payloads.size());
      assertEquals(42.0, new JSONObject(payloads.get(0).toString()).getDouble("value"));
    }
  }

  @Test
  void drainsEveryDatagramWhenSeveralArriveTogether() throws Exception {
    int port = freePort();
    NioUdpTransport receiver = listening(port);

    int expected = 200;
    CountDownLatch all = new CountDownLatch(expected);
    receiver.register("bulk", msg -> all.countDown());

    NioUdpTransport sender = listening(freePort());
    for (int i = 0; i < expected; i++) {
      sender.publish("127.0.0.1", port, "bulk", new JSONObject().put("i", i));
    }

    // Loopback UDP can still drop, so this asserts the loop keeps draining rather than demanding
    // every datagram: the old implementation delivered exactly zero.
    assertTrue(all.await(15, TimeUnit.SECONDS) || all.getCount() < expected * 0.5,
            "delivered only %d of %d datagrams".formatted(expected - all.getCount(), expected));
  }

  @Test
  void ignoresAMalformedDatagramWithoutStoppingTheReceiveLoop() throws Exception {
    int port = freePort();
    NioUdpTransport receiver = listening(port);

    CountDownLatch good = new CountDownLatch(1);
    receiver.register("after-garbage", msg -> good.countDown());

    try (DatagramSocket raw = new DatagramSocket()) {
      byte[] junk = "not json at all\n".getBytes();
      raw.send(new java.net.DatagramPacket(junk, junk.length,
              java.net.InetAddress.getLoopbackAddress(), port));
    }

    NioUdpTransport sender = listening(freePort());
    sender.publish("127.0.0.1", port, "after-garbage", new JSONObject().put("ok", true));

    assertTrue(good.await(10, TimeUnit.SECONDS),
            "a malformed datagram killed the receive loop");
  }
}
