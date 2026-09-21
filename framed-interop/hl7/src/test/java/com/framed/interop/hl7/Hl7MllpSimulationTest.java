package com.framed.interop.hl7;

import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.interop.hl7.hl7v2.Hl7Message;
import com.framed.interop.hl7.mllp.MllpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end simulation: actual HL7 v2 messages flow over a real MLLP socket into the inbound
 * {@link Hl7v2Protocol} and surface on the bus as EAV events; and a full outbound→inbound loopback
 * through {@link Hl7v2Dispatcher}.
 */
class Hl7MllpSimulationTest {

  @TempDir
  Path tmp;

  private Path writeMapping(String name, String json) throws IOException {
    Path p = tmp.resolve(name);
    Files.writeString(p, json);
    return p;
  }

  // 3-part keys: inbound observations land under the mapped device id.
  private static final String MAPPING_DEVICE = """
      {
        "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"},
        "Percentage_int.PC60FW.SpO2": {"code":"59408-5","system":"LOINC","display":"SpO2","unit":"%","valueType":"NM"}
      }""";

  private static final String INCOMING_ORU = String.join("\r",
      "MSH|^~\\&|MONITOR|ICU|FRAMED|HOSP|20260623120000||ORU^R01|MSG0001|P|2.5",
      "PID|1||12345^^^MRN||Doe^Jane||19800101|F",
      "PV1|1|I|ICU^01^A",
      "OBR|1||||",
      "OBX|1|NM|19889-5^End tidal CO2^LOINC||38|mm[Hg]|||||F",
      "OBX|2|NM|59408-5^SpO2^LOINC||95|%|||||F") + "\r";

  private static final String INCOMING_ADT = String.join("\r",
      "MSH|^~\\&|ADT|HOSP|FRAMED|ICU|20260623120000||ADT^A08|MSG0002|P|2.5",
      "EVN|A08|20260623120000",
      "PID|1||67890^^^MRN||Smith^John||19700202|M",
      "PV1|1|I|ICU^02^B") + "\r";

  @Test
  void actualOruOverMllpSurfacesAsEavEvents() throws Exception {
    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(2);

    bus.register("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", msg -> {
      values.put("etCO2", ((JSONObject) msg).get("value"));
      latch.countDown();
    });
    bus.register("Percentage_int.PC60FW.SpO2.parsed", msg -> {
      values.put("SpO2", ((JSONObject) msg).get("value"));
      latch.countDown();
    });

    Hl7v2Protocol protocol = new Hl7v2Protocol(
        "in", bus, 0, writeMapping("m.json", MAPPING_DEVICE).toString(), "HL7-In", false);
    try (MllpClient client = new MllpClient("127.0.0.1", protocol.getPort(), 3000)) {
      String ack = client.sendAndReceive(INCOMING_ORU);

      Hl7Message ackMsg = Hl7Message.parse(ack);
      assertEquals("AA", ackMsg.field("MSA", 1));
      assertEquals("MSG0001", ackMsg.field("MSA", 2));

      assertTrue(latch.await(5, TimeUnit.SECONDS), "both observations should reach the bus");
      assertEquals(38, ((Number) values.get("etCO2")).intValue());
      assertEquals(95, ((Number) values.get("SpO2")).intValue());
    } finally {
      protocol.stop();
      bus.shutdown();
    }
  }

  @Test
  void actualAdtOverMllpPublishesPatientContext() throws Exception {
    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    AtomicReference<Object> mrn = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    bus.register("Patient.HL7-In.MRN.parsed", msg -> {
      mrn.set(((JSONObject) msg).get("value"));
      latch.countDown();
    });

    Hl7v2Protocol protocol = new Hl7v2Protocol(
        "in", bus, 0, writeMapping("m.json", MAPPING_DEVICE).toString(), "HL7-In", true);
    try (MllpClient client = new MllpClient("127.0.0.1", protocol.getPort(), 3000)) {
      String ack = client.sendAndReceive(INCOMING_ADT);
      assertEquals("AA", Hl7Message.parse(ack).field("MSA", 1));
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      assertEquals("67890", mrn.get());
    } finally {
      protocol.stop();
      bus.shutdown();
    }
  }

  @Test
  void dispatcherToProtocolLoopback() throws Exception {
    // 2-part key so the inbound observation lands under the fallback device ("REMOTE"),
    // giving a distinct address from the seed channel we feed the dispatcher.
    String mappingLoop = """
        { "Measurement.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"} }""";
    Path mapping = writeMapping("loop.json", mappingLoop);

    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    AtomicReference<Object> looped = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    bus.register("Measurement.REMOTE.etCO2.parsed", msg -> {
      looped.set(((JSONObject) msg).get("value"));
      latch.countDown();
    });

    Hl7v2Protocol protocol = new Hl7v2Protocol("in", bus, 0, mapping.toString(), "REMOTE", false);
    Hl7v2Dispatcher dispatcher = new Hl7v2Dispatcher(
        bus, new JSONArray().put("Oxylog-3000-Plus-00"), "127.0.0.1", protocol.getPort(),
        mapping.toString(), new JSONObject(), new JSONObject(), new JSONObject());

    try {
      String channel = "Measurement.Oxylog-3000-Plus-00.etCO2.parsed";
      // 1) announce the channel so the dispatcher binds to it
      bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), channel);
      // 2) let the dispatcher register its handler
      Thread.sleep(300);
      // 3) emit a datapoint exactly as a device driver would
      JSONObject dp = new JSONObject()
          .put("timestamp", LocalDateTime.now().format(Timer.formatter))
          .put("channelID", "etCO2")
          .put("value", 42)
          .put("className", "Measurement");
      bus.publish(channel, dp);

      assertTrue(latch.await(5, TimeUnit.SECONDS), "datapoint should loop back through HL7/MLLP");
      assertEquals(42, ((Number) looped.get()).intValue());
    } finally {
      dispatcher.stop();
      protocol.stop();
      bus.shutdown();
    }
  }
}
