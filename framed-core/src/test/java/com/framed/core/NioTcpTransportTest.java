package com.framed.core;

import com.framed.core.remote.NioTcpTransport;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link NioTcpTransport}'s connection model and stream framing.
 *
 * <p>The transport used to open a fresh {@code SocketChannel} for every message — connect, write,
 * close — so one datapoint cost one TCP connection. The receiving instance accepts on a single
 * selector thread and could not drain the listen backlog at waveform rates: measured against the
 * MIMIC replay it saturated near 7,400 messages/s and then <em>lost</em> 10–30 % of messages while
 * {@code write} kept returning successfully. Connections are now pooled per peer and reused.</p>
 */
class NioTcpTransportTest {

  private final List<NioTcpTransport> started = new ArrayList<>();

  @AfterEach
  void tearDown() {
    started.forEach(NioTcpTransport::shutdown);
  }

  private NioTcpTransport listening(int port) throws IOException {
    NioTcpTransport transport = new NioTcpTransport(port);
    started.add(transport);
    transport.start();
    return transport;
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  /** The sender's pooled connections, by {@code host:port}. */
  @SuppressWarnings("unchecked")
  private static Map<String, ?> linksOf(NioTcpTransport transport) throws Exception {
    Field f = NioTcpTransport.class.getDeclaredField("links");
    f.setAccessible(true);
    return (Map<String, ?>) f.get(transport);
  }

  @Test
  void deliversAPublishedMessageToARegisteredHandler() throws Exception {
    int port = freePort();
    NioTcpTransport receiver = listening(port);

    AtomicReference<Object> seen = new AtomicReference<>();
    CountDownLatch got = new CountDownLatch(1);
    receiver.register("Waveform.DEV.II.parsed", msg -> {
      seen.set(msg);
      got.countDown();
    });

    NioTcpTransport sender = listening(freePort());
    sender.publish("127.0.0.1", port, "Waveform.DEV.II.parsed",
            new JSONObject().put("value", 42.0).toString());

    assertTrue(got.await(10, TimeUnit.SECONDS), "message never arrived");
    assertEquals(42.0, new JSONObject(seen.get().toString()).getDouble("value"));
  }

  @Test
  void reusesOneConnectionPerPeerAcrossManyMessages() throws Exception {
    int port = freePort();
    NioTcpTransport receiver = listening(port);

    int expected = 500;
    CountDownLatch all = new CountDownLatch(expected);
    receiver.register("bulk", msg -> all.countDown());

    NioTcpTransport sender = listening(freePort());
    for (int i = 0; i < expected; i++) {
      sender.publish("127.0.0.1", port, "bulk", new JSONObject().put("i", i).toString());
    }

    assertTrue(all.await(30, TimeUnit.SECONDS),
            "delivered only %d of %d messages".formatted(expected - all.getCount(), expected));
    assertEquals(1, linksOf(sender).size(),
            "expected one pooled connection for the peer, not one per message");
  }

  @Test
  void keepsSeparateConnectionsForSeparatePeers() throws Exception {
    int portA = freePort();
    int portB = freePort();
    NioTcpTransport a = listening(portA);
    NioTcpTransport b = listening(portB);

    CountDownLatch both = new CountDownLatch(2);
    a.register("fan", msg -> both.countDown());
    b.register("fan", msg -> both.countDown());

    NioTcpTransport sender = listening(freePort());
    sender.publish("127.0.0.1", portA, "fan", "x");
    sender.publish("127.0.0.1", portB, "fan", "y");

    assertTrue(both.await(10, TimeUnit.SECONDS), "one of the two peers did not receive");
    assertEquals(2, linksOf(sender).size(), "each peer needs its own connection");
  }

  @Test
  void readsEveryMessageWhenManyArriveOnOneConnection() throws Exception {
    int port = freePort();
    NioTcpTransport receiver = listening(port);

    int expected = 300;
    CountDownLatch all = new CountDownLatch(expected);
    receiver.register("stream", msg -> all.countDown());

    StringBuilder wire = new StringBuilder();
    for (int i = 0; i < expected; i++) {
      wire.append(new JSONObject()
              .put("address", "stream").put("payload", "p" + i).put("type", "publish")).append('\n');
    }

    try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
      client.getOutputStream().write(wire.toString().getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();
      assertTrue(all.await(20, TimeUnit.SECONDS),
              "dispatched only %d of %d messages from one connection"
                      .formatted(expected - all.getCount(), expected));
    }
  }

  /**
   * Regression test for the framing bug that connection reuse exposes: bytes were decoded per TCP
   * read, so a multi-byte UTF-8 character split across two reads became replacement characters.
   * Medical payloads carry these routinely (µV, °C).
   */
  @Test
  void decodesAMultiByteCharacterSplitAcrossTwoReads() throws Exception {
    int port = freePort();
    NioTcpTransport receiver = listening(port);

    AtomicReference<Object> seen = new AtomicReference<>();
    CountDownLatch got = new CountDownLatch(1);
    receiver.register("units", msg -> {
      seen.set(msg);
      got.countDown();
    });

    String payload = "12 µV at 37 °C";
    byte[] framed = (new JSONObject()
            .put("address", "units").put("payload", payload).put("type", "publish") + "\n")
            .getBytes(StandardCharsets.UTF_8);

    // Split inside the two-byte encoding of 'µ' (0xC2 0xB5), so neither half is valid UTF-8 alone.
    int mu = indexOf(framed, (byte) 0xC2);
    assertTrue(mu > 0, "fixture should contain a multi-byte character");
    int split = mu + 1;

    try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
      OutputStream out = client.getOutputStream();
      out.write(framed, 0, split);
      out.flush();
      Thread.sleep(250);                       // force the remainder into a separate read
      out.write(framed, split, framed.length - split);
      out.flush();

      assertTrue(got.await(10, TimeUnit.SECONDS), "message never arrived");
      assertEquals(payload, seen.get().toString(),
              "a character split across two TCP reads was corrupted");
    }
  }

  @Test
  void reconnectsAfterThePeerGoesAwayAndComesBack() throws Exception {
    int port = freePort();
    NioTcpTransport first = listening(port);
    CountDownLatch before = new CountDownLatch(1);
    first.register("revive", msg -> before.countDown());

    NioTcpTransport sender = listening(freePort());
    sender.publish("127.0.0.1", port, "revive", "one");
    assertTrue(before.await(10, TimeUnit.SECONDS), "first message never arrived");

    first.shutdown();
    started.remove(first);
    Thread.sleep(300);

    NioTcpTransport second = listening(port);
    CountDownLatch after = new CountDownLatch(1);
    second.register("revive", msg -> after.countDown());

    // The pooled connection is now dead; the send must notice and re-establish it. The first
    // attempt may be absorbed by the dying socket, so allow the retry a second message.
    for (int attempt = 0; attempt < 5 && after.getCount() > 0; attempt++) {
      sender.publish("127.0.0.1", port, "revive", "two");
      if (after.await(2, TimeUnit.SECONDS)) {
        break;
      }
    }
    assertTrue(after.getCount() == 0, "sender never re-established the connection to the peer");
  }

  private static int indexOf(byte[] haystack, byte needle) {
    for (int i = 0; i < haystack.length; i++) {
      if (haystack[i] == needle) {
        return i;
      }
    }
    return -1;
  }
}
