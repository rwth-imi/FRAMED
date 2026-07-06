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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
