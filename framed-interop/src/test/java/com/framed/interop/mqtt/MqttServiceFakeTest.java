package com.framed.interop.mqtt;

import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.framed.interop.gate.EmissionGate;
import com.framed.interop.mapping.ObservationMapping;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridge-logic simulation using an in-memory transport (no broker). A {@code SEQUENTIAL} bus makes
 * outbound assertions deterministic.
 */
class MqttServiceFakeTest {

  private static final ObservationMapping MAPPING = ObservationMapping.fromJson(new JSONObject("""
      { "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"} }"""));

  private static JSONObject datapoint(int value, String channelID, String className) {
    return new JSONObject()
        .put("timestamp", LocalDateTime.now().format(Timer.formatter))
        .put("channelID", channelID)
        .put("value", value)
        .put("className", className);
  }

  @Test
  void outboundPublishesMappedChannelToTopic() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray().put("Oxylog-3000-Plus-00"), new JSONArray(),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), "Measurement.Oxylog-3000-Plus-00.etCO2.parsed");
    bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", datapoint(38, "etCO2", "Measurement"));

    assertEquals(1, fake.published.size());
    FakeMqttTransport.Published p = fake.published.get(0);
    assertEquals("framed/Oxylog-3000-Plus-00/etCO2", p.topic());
    JSONObject payload = MqttCodec.decode(p.payload());
    assertEquals(38, payload.getInt("value"));
    assertEquals("19889-5", payload.getString("code"));
  }

  @Test
  void unmappedChannelIsNotPublished() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray().put("Oxylog-3000-Plus-00"), new JSONArray(),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), "Measurement.Oxylog-3000-Plus-00.Unknown.parsed");
    bus.publish("Measurement.Oxylog-3000-Plus-00.Unknown.parsed", datapoint(1, "Unknown", "Measurement"));

    assertTrue(fake.published.isEmpty());
  }

  @Test
  void onChangeGateSuppressesRepeats() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray().put("Oxylog-3000-Plus-00"), new JSONArray(),
        "framed", 1, MAPPING, new EmissionGate(true, 0), false, "MQTT-In");

    bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), "Measurement.Oxylog-3000-Plus-00.etCO2.parsed");
    bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", datapoint(38, "etCO2", "Measurement"));
    bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", datapoint(38, "etCO2", "Measurement"));
    bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", datapoint(40, "etCO2", "Measurement"));

    assertEquals(2, fake.published.size(), "duplicate value suppressed, changed value emitted");
  }

  @Test
  void inboundRepublishesOntoBus() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    AtomicReference<Object> got = new AtomicReference<>();
    bus.register("Measurement.EXT.etCO2.parsed", msg -> got.set(((JSONObject) msg).get("value")));

    JSONObject payload = new JSONObject().put("value", 42).put("channelID", "etCO2")
        .put("deviceID", "EXT").put("className", "Measurement");
    fake.deliver("framed/EXT/etCO2", payload.toString().getBytes(StandardCharsets.UTF_8));

    assertEquals(42, ((Number) got.get()).intValue());
  }

  private static byte[] inboundPayload(String timestamp) {
    JSONObject payload = new JSONObject().put("value", 42).put("channelID", "etCO2")
        .put("deviceID", "EXT").put("className", "Measurement");
    if (timestamp != null) {
      payload.put("timestamp", timestamp);
    }
    return payload.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void inboundTimestampsAreNormalizedToBusFormatUtc() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    List<String> stamps = new ArrayList<>();
    bus.register("Measurement.EXT.etCO2.parsed",
        msg -> stamps.add(((JSONObject) msg).getString("timestamp")));

    fake.deliver("framed/EXT/etCO2", inboundPayload("2026-06-23T12:30:00.123456"));
    fake.deliver("framed/EXT/etCO2", inboundPayload("2026-06-23T14:30:00+02:00"));
    fake.deliver("framed/EXT/etCO2", inboundPayload("2026-06-23T12:30:00"));

    assertEquals(List.of(
            "2026-06-23T12:30:00.123456",  // bus format: passed through unchanged
            "2026-06-23T12:30:00.000000",  // ISO-8601 with offset: converted to UTC
            "2026-06-23T12:30:00.000000"), // offset-less ISO-8601: interpreted as UTC
        stamps, "every subscriber parses timestamps strictly, so all forms must normalize");
  }

  @Test
  void inboundAbsentOrUnparseableTimestampFallsBackToUtcArrival() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    List<String> stamps = new ArrayList<>();
    bus.register("Measurement.EXT.etCO2.parsed",
        msg -> stamps.add(((JSONObject) msg).getString("timestamp")));

    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
    fake.deliver("framed/EXT/etCO2", inboundPayload("last tuesday, around noon"));
    fake.deliver("framed/EXT/etCO2", inboundPayload(null));
    LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1);

    assertEquals(2, stamps.size());
    for (String stamp : stamps) {
      LocalDateTime got = LocalDateTime.parse(stamp, Timer.formatter); // parseable bus format
      assertTrue(!got.isBefore(before) && !got.isAfter(after),
          "fallback must be the UTC arrival time, not local wall-clock: " + stamp);
    }
  }

  @Test
  void inboundWithoutValueIsDroppedNotPublished() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    AtomicReference<Object> got = new AtomicReference<>();
    bus.register("Measurement.EXT.etCO2.parsed", msg -> got.set(msg));

    JSONObject noValue = new JSONObject().put("channelID", "etCO2")
        .put("deviceID", "EXT").put("className", "Measurement");
    fake.deliver("framed/EXT/etCO2", noValue.toString().getBytes(StandardCharsets.UTF_8));

    assertNull(got.get(), "a payload without a value must not produce a bus event");
  }
}
