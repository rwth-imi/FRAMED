package com.framed.interop.mqtt;

import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.interop.gate.EmissionGate;
import com.framed.interop.mapping.ObservationMapping;
import com.framed.interop.replay.ReplayFixture;
import com.framed.interop.replay.ReplayFixture.Event;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay-driven simulation of the MQTT bridge: recorded bench telemetry (see
 * {@link ReplayFixture}) is replayed onto a source deployment's bus, published by
 * {@link MqttService} as self-describing payloads, and fed back into a second deployment's
 * inbound bridge — mapped channels only, values and timestamps preserved with full precision.
 * A {@code SEQUENTIAL} bus and the in-memory transport keep every assertion deterministic;
 * two separate buses model the two deployments.
 */
class MqttReplaySimulationTest {

  private static final JSONArray DEVICES =
      new JSONArray().put("Oxylog-3000-Plus-00").put("PC60FW").put("CDSS");

  private static ObservationMapping mapping() {
    try {
      return ObservationMapping.load(ReplayFixture.deploymentMapping());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Event> mapped(List<Event> events, ObservationMapping mapping) {
    return events.stream()
        .filter(e -> mapping.lookup(e.className(), e.deviceID(), e.channelID()).isPresent())
        .toList();
  }

  @Test
  void replayedTelemetryIsBridgedOutAndBackWithFullFidelity() {
    List<Event> events = ReplayFixture.load();
    ObservationMapping mapping = mapping();
    List<Event> mapped = mapped(events, mapping);
    assertFalse(mapped.isEmpty(), "fixture must contain mapped telemetry");
    assertTrue(mapped.size() < events.size(), "fixture must contain unmapped telemetry as a control group");

    // Outbound deployment: replay onto the bus, bridge publishes to the (fake) broker. The device
    // groups deliberately include CDSS: the mapping, not group membership, must decide emission.
    LocalEventBus source = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport out = new FakeMqttTransport();
    new MqttService(source, out, DEVICES, new JSONArray(),
        "framed", 1, mapping, EmissionGate.passthrough(), false, "MQTT-In");

    // Inbound deployment: a second bridge subscribed to the same topic space.
    LocalEventBus receiver = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport in = new FakeMqttTransport();
    new MqttService(receiver, in, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, mapping, EmissionGate.passthrough(), false, "MQTT-In");

    Map<String, List<JSONObject>> received = new HashMap<>();
    for (String address : mapped.stream().map(Event::address).distinct().toList()) {
      List<JSONObject> list = new ArrayList<>();
      received.put(address, list);
      receiver.register(address, msg -> list.add((JSONObject) msg));
    }

    try {
      ReplayFixture.announce(source, events);
      ReplayFixture.publish(source, events); // SEQUENTIAL bus: handled synchronously, in order

      // Outbound: exactly the mapped subset, in recorded order, with concept and full timestamp.
      assertEquals(mapped.size(), out.published.size(),
          "exactly the mapped subset of the replay must be published");
      for (int i = 0; i < mapped.size(); i++) {
        Event ev = mapped.get(i);
        FakeMqttTransport.Published p = out.published.get(i);
        assertEquals("framed/%s/%s".formatted(ev.deviceID(), ev.channelID()), p.topic());
        JSONObject payload = MqttCodec.decode(p.payload());
        assertEquals(((Number) ev.value()).doubleValue(),
            ((Number) payload.get("value")).doubleValue());
        assertEquals(LocalDateTime.ofInstant(ev.timestamp(), ZoneOffset.UTC).format(Timer.formatter),
            payload.getString("timestamp"), "MQTT carries the recorded timestamp verbatim");
        assertEquals(mapping.lookup(ev.className(), ev.deviceID(), ev.channelID()).orElseThrow().code(),
            payload.getString("code"), "payload must be self-describing");
      }

      // Feed the published payloads into the receiving deployment's bridge.
      for (FakeMqttTransport.Published p : out.published) {
        in.deliver(p.topic(), p.payload());
      }
      for (String address : received.keySet()) {
        List<Event> expected = mapped.stream().filter(e -> e.address().equals(address)).toList();
        List<JSONObject> got = received.get(address);
        assertEquals(expected.size(), got.size(),
            "every mapped observation must arrive exactly once on " + address);
        for (int i = 0; i < got.size(); i++) {
          assertEquals(((Number) expected.get(i).value()).doubleValue(),
              ((Number) got.get(i).get("value")).doubleValue());
          assertEquals(
              LocalDateTime.ofInstant(expected.get(i).timestamp(), ZoneOffset.UTC).format(Timer.formatter),
              got.get(i).getString("timestamp"),
              "recorded timestamp must survive the MQTT round trip with full precision");
        }
      }
    } finally {
      source.shutdown();
      receiver.shutdown();
    }
  }

  @Test
  void onChangeGateKeepsReplayAtClinicalCadence() {
    List<Event> events = ReplayFixture.load();
    ObservationMapping mapping = mapping();
    List<Event> mapped = mapped(events, mapping);

    // Expected emissions under onChange: per gate key (deviceID.channelID), only value changes.
    Map<String, Object> last = new HashMap<>();
    int expected = 0;
    for (Event ev : mapped) {
      Object prev = last.put(ev.deviceID() + "." + ev.channelID(), ev.value());
      if (prev == null || !prev.equals(ev.value())) {
        expected++;
      }
    }
    assertTrue(expected < mapped.size(),
        "replay data must contain repeated values for this simulation to be meaningful");

    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport out = new FakeMqttTransport();
    new MqttService(bus, out, DEVICES, new JSONArray(),
        "framed", 1, mapping, new EmissionGate(true, 0), false, "MQTT-In");

    try {
      ReplayFixture.announce(bus, events);
      ReplayFixture.publish(bus, events);
      assertEquals(expected, out.published.size(),
          "onChange gate must emit exactly the per-key value changes of the replay");
    } finally {
      bus.shutdown();
    }
  }
}
