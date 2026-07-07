package com.framed.interop.hl7;

import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.interop.hl7.hl7v2.AckBuilder;
import com.framed.interop.hl7.hl7v2.Hl7Message;
import com.framed.interop.hl7.mllp.MllpServer;
import com.framed.io.dispatch.DataPoint;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hl7v2DispatcherTest {

  @TempDir
  Path tmp;

  private static final DataPoint<Integer> DP =
      new DataPoint<>(Instant.parse("2026-06-23T12:00:00Z"), 38, "etCO2", "Oxylog-3000-Plus-00", "Measurement");

  @Test
  void controlIdIsStableAcrossRetriesOfTheSameDatapoint() {
    assertEquals(Hl7v2Dispatcher.controlIdFor(DP), Hl7v2Dispatcher.controlIdFor(DP),
        "same datapoint must yield the same control id so a retry de-duplicates");
    DataPoint<Integer> changed =
        new DataPoint<>(DP.timestamp(), 40, "etCO2", "Oxylog-3000-Plus-00", "Measurement");
    assertNotEquals(Hl7v2Dispatcher.controlIdFor(DP), Hl7v2Dispatcher.controlIdFor(changed));
  }

  @Test
  void controlIdFitsMsh10AndIsNot32Bit() {
    String id = Hl7v2Dispatcher.controlIdFor(DP);
    assertEquals(20, id.length(), "MSH-10 is limited to 20 characters in HL7 v2.5");
    assertTrue(id.matches("[0-9A-F]{20}"),
        "hex-encoded 80-bit hash; a 32-bit hash space makes receivers de-duplicate away real observations");
  }

  @Test
  void permanentNakIsNotRetried() throws Exception {
    Path mapping = tmp.resolve("m.json");
    Files.writeString(mapping, """
        { "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"} }""");

    AtomicInteger received = new AtomicInteger();
    MllpServer server = new MllpServer(0, raw -> {
      received.incrementAndGet();
      return AckBuilder.build(Hl7Message.parse(raw), AckBuilder.AE, "rejected", Instant.now());
    });

    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    Hl7v2Dispatcher dispatcher = new Hl7v2Dispatcher(bus, new JSONArray().put("Oxylog-3000-Plus-00"),
        "127.0.0.1", server.getPort(), mapping.toString(), new JSONObject(), new JSONObject(), new JSONObject());

    try {
      String channel = "Measurement.Oxylog-3000-Plus-00.etCO2.parsed";
      bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), channel);
      Thread.sleep(300);
      bus.publish(channel, new JSONObject()
          .put("timestamp", LocalDateTime.now().format(Timer.formatter))
          .put("channelID", "etCO2").put("value", 38).put("className", "Measurement"));

      // Wait for the single send.
      long deadline = System.currentTimeMillis() + 5000;
      while (received.get() == 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(1, received.get(), "message should be sent once");

      // A retry loop (the old behaviour) would resend within the ~100ms initial backoff.
      Thread.sleep(800);
      assertEquals(1, received.get(), "a permanent NAK must not be retried");
    } finally {
      dispatcher.stop();
      server.close();
      bus.shutdown();
    }
  }

  /**
   * Regression for the gate/retry interaction: the emission gate must not record state before the
   * MLLP send, otherwise the base Dispatcher's IOException retry is gated out and the datapoint is
   * silently lost for the rest of the interval.
   */
  @Test
  void transientSendFailureIsRetriedDespiteMinIntervalGate() throws Exception {
    Path mapping = tmp.resolve("m.json");
    Files.writeString(mapping, """
        { "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"} }""");

    // Reserve an ephemeral port, then leave it closed so the first send fails with IOException.
    int port;
    try (ServerSocket reserve = new ServerSocket(0)) {
      port = reserve.getLocalPort();
    }

    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    Hl7v2Dispatcher dispatcher = new Hl7v2Dispatcher(bus, new JSONArray().put("Oxylog-3000-Plus-00"),
        "127.0.0.1", port, mapping.toString(), new JSONObject(), new JSONObject(),
        new JSONObject().put("minIntervalMs", 60_000));

    AtomicInteger received = new AtomicInteger();
    MllpServer server = null;
    try {
      String channel = "Measurement.Oxylog-3000-Plus-00.etCO2.parsed";
      bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), channel);
      Thread.sleep(300);
      bus.publish(channel, new JSONObject()
          .put("timestamp", LocalDateTime.now().format(Timer.formatter))
          .put("channelID", "etCO2").put("value", 38).put("className", "Measurement"));

      // Let the first push fail (connection refused), then bring the endpoint up.
      Thread.sleep(300);
      server = new MllpServer(port, raw -> {
        received.incrementAndGet();
        return AckBuilder.build(Hl7Message.parse(raw), AckBuilder.AA, "", Instant.now());
      });

      long deadline = System.currentTimeMillis() + 10_000;
      while (received.get() == 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(1, received.get(),
          "the retry of a failed send must pass the gate and deliver the datapoint");
    } finally {
      dispatcher.stop();
      if (server != null) {
        server.close();
      }
      bus.shutdown();
    }
  }

  /** The gate is keyed per device+channel: same-named channels on two devices must both emit. */
  @Test
  void gateDoesNotCoupleSameChannelAcrossDevices() throws Exception {
    Path mapping = tmp.resolve("m.json");
    Files.writeString(mapping, """
        {
          "Measurement.DevA.RR": {"code":"9279-1","system":"LOINC","display":"RR","unit":"/min","valueType":"NM"},
          "Measurement.DevB.RR": {"code":"9279-1","system":"LOINC","display":"RR","unit":"/min","valueType":"NM"}
        }""");

    AtomicInteger received = new AtomicInteger();
    MllpServer server = new MllpServer(0, raw -> {
      received.incrementAndGet();
      return AckBuilder.build(Hl7Message.parse(raw), AckBuilder.AA, "", Instant.now());
    });

    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    Hl7v2Dispatcher dispatcher = new Hl7v2Dispatcher(bus,
        new JSONArray().put("DevA").put("DevB"),
        "127.0.0.1", server.getPort(), mapping.toString(), new JSONObject(), new JSONObject(),
        new JSONObject().put("minIntervalMs", 60_000).put("onChange", true));

    try {
      bus.publish(Service.addressRegistry("DevA"), "Measurement.DevA.RR.parsed");
      bus.publish(Service.addressRegistry("DevB"), "Measurement.DevB.RR.parsed");
      Thread.sleep(300);
      JSONObject sample = new JSONObject()
          .put("timestamp", LocalDateTime.now().format(Timer.formatter))
          .put("channelID", "RR").put("value", 12).put("className", "Measurement");
      bus.publish("Measurement.DevA.RR.parsed", new JSONObject(sample.toString()));
      bus.publish("Measurement.DevB.RR.parsed", new JSONObject(sample.toString()));

      long deadline = System.currentTimeMillis() + 5000;
      while (received.get() < 2 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(2, received.get(),
          "a channel-only gate key would let device A's RR suppress device B's RR");
    } finally {
      dispatcher.stop();
      server.close();
      bus.shutdown();
    }
  }
}
