package com.framed.interop.hl7;

import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.interop.mapping.ObservationMapping;
import com.framed.interop.replay.ReplayFixture;
import com.framed.interop.replay.ReplayFixture.Event;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay-driven end-to-end simulation of the HL7 boundary: recorded bench telemetry (see
 * {@link ReplayFixture}) is replayed onto a source deployment's bus, crosses a real MLLP socket
 * as {@code ORU^R01} messages ({@link Hl7v2Dispatcher} → {@link Hl7v2Protocol}), and must surface
 * on the receiving deployment's bus as EAV events — mapped channels only, values and observation
 * times preserved. Two separate buses model the two deployments (and avoid the documented
 * dispatcher→own-protocol feedback loop).
 */
class Hl7ReplaySimulationTest {

  @Test
  void replayedTelemetryCrossesTheHl7BoundaryEndToEnd() throws Exception {
    List<Event> events = ReplayFixture.load();
    Path mappingPath = ReplayFixture.deploymentMapping();
    ObservationMapping mapping = ObservationMapping.load(mappingPath);

    List<Event> mapped = ReplayFixture.mapped(events, mapping);
    List<Event> unmapped = ReplayFixture.unmapped(events, mapping);
    assertFalse(mapped.isEmpty(), "fixture must contain mapped telemetry");
    assertFalse(unmapped.isEmpty(), "fixture must contain unmapped telemetry as a control group");

    LocalEventBus source = new LocalEventBus(DispatchMode.PER_HANDLER);
    LocalEventBus receiver = new LocalEventBus(DispatchMode.PER_HANDLER);

    // Mapped addresses: collect what arrives; the mapping keys carry the device, so inbound
    // observations land under the same addresses as on the source deployment.
    Map<String, List<JSONObject>> received = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(mapped.size());
    for (String address : mapped.stream().map(Event::address).distinct().toList()) {
      List<JSONObject> list = Collections.synchronizedList(new ArrayList<>());
      received.put(address, list);
      receiver.register(address, msg -> {
        list.add((JSONObject) msg);
        latch.countDown();
      });
    }
    // Unmapped addresses: anything arriving here leaked through the mapping filter.
    Map<String, AtomicInteger> leaked = new ConcurrentHashMap<>();
    for (String address : unmapped.stream().map(Event::address).distinct().toList()) {
      AtomicInteger count = new AtomicInteger();
      leaked.put(address, count);
      receiver.register(address, msg -> count.incrementAndGet());
    }

    Hl7v2Protocol protocol = new Hl7v2Protocol(
        "in", receiver, 0, mappingPath.toString(), "HL7-In", false);
    // The device groups deliberately include CDSS: the mapping, not group membership, must decide
    // what is emitted over HL7.
    Hl7v2Dispatcher dispatcher = new Hl7v2Dispatcher(source,
        new JSONArray().put("Oxylog-3000-Plus-00").put("PC60FW").put("CDSS"),
        "127.0.0.1", protocol.getPort(), mappingPath.toString(),
        new JSONObject(), new JSONObject(), new JSONObject());

    try {
      ReplayFixture.announce(source, events);
      Thread.sleep(300); // PER_HANDLER bus: let the dispatcher bind the announced channels
      ReplayFixture.publish(source, events);

      assertTrue(latch.await(30, TimeUnit.SECONDS),
          "all mapped observations should cross the HL7 boundary");
      Thread.sleep(300); // grace period for any (incorrect) unmapped emissions to surface

      Map<String, List<Event>> expected = mapped.stream()
          .collect(Collectors.groupingBy(Event::address));
      for (Map.Entry<String, List<Event>> e : expected.entrySet()) {
        List<JSONObject> got = received.get(e.getKey());
        assertEquals(e.getValue().size(), got.size(),
            "every mapped observation must arrive exactly once on " + e.getKey());
        for (int i = 0; i < got.size(); i++) {
          Event ev = e.getValue().get(i);
          JSONObject msg = got.get(i);
          assertEquals(((Number) ev.value()).doubleValue(), ((Number) msg.get("value")).doubleValue(),
              "value must survive the HL7 round trip for " + e.getKey());
          // OBX-14 carries seconds precision, so the recorded instant survives truncated to seconds.
          Instant gotTs = LocalDateTime.parse(msg.getString("timestamp"), Timer.formatter)
              .toInstant(ZoneOffset.UTC);
          assertEquals(ev.timestamp().truncatedTo(ChronoUnit.SECONDS), gotTs,
              "observation time must survive the HL7 round trip for " + e.getKey());
        }
      }
      leaked.forEach((address, count) -> assertEquals(0, count.get(),
          "unmapped channel must not cross the HL7 boundary: " + address));
    } finally {
      dispatcher.stop();
      protocol.stop();
      source.shutdown();
      receiver.shutdown();
    }
  }
}