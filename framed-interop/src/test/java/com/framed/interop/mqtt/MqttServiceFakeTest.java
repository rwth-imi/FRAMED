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
  void waveformKindChannelIsNotPublished() {
    ObservationMapping mapping = ObservationMapping.fromJson(new JSONObject("""
        { "RealTime.Oxylog-3000-Plus-00.CO2_mmHg": {"unit":"mm[Hg]","kind":"waveform"} }"""));
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray().put("Oxylog-3000-Plus-00"), new JSONArray(),
        "framed", 1, mapping, EmissionGate.passthrough(), false, "MQTT-In");

    bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"),
        "RealTime.Oxylog-3000-Plus-00.CO2_mmHg.parsed");
    bus.publish("RealTime.Oxylog-3000-Plus-00.CO2_mmHg.parsed",
        datapoint(38, "CO2_mmHg", "RealTime"));

    assertTrue(fake.published.isEmpty(),
        "waveform-kind channels are the SDC bridge's job, not MQTT's");
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

  /**
   * Regression: the onChange state is committed only after a successful publish — a throwing
   * transport must not suppress an identical follow-up value that never reached the broker.
   */
  @Test
  void failedPublishDoesNotSuppressIdenticalFollowUpUnderOnChange() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray().put("Oxylog-3000-Plus-00"), new JSONArray(),
        "framed", 1, MAPPING, new EmissionGate(true, 0), false, "MQTT-In");

    bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), "Measurement.Oxylog-3000-Plus-00.etCO2.parsed");
    fake.failPublishes = true;
    bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", datapoint(38, "etCO2", "Measurement"));
    assertTrue(fake.published.isEmpty(), "the failed publish must not be recorded");

    fake.failPublishes = false;
    bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", datapoint(38, "etCO2", "Measurement"));
    assertEquals(1, fake.published.size(),
        "an identical value must still be emitted after a failed publish");
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

  /**
   * Regression: a broker that is down at startup must not permanently cost the MQTT leg — the
   * constructor survives, and a later (retried) connect brings inbound subscriptions up.
   */
  @Test
  void brokerDownAtStartupIsRetriedNotFatal() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    fake.failConnects = 1;
    MqttService service = new MqttService(bus, fake, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    assertTrue(!fake.connected, "first connect failed, constructor must survive it");

    assertTrue(service.connectAndSubscribe(), "retry connects once the broker is up");
    AtomicReference<Object> got = new AtomicReference<>();
    bus.register("Measurement.EXT.etCO2.parsed", msg -> got.set(((JSONObject) msg).get("value")));
    fake.deliver("framed/EXT/etCO2", inboundPayload("2026-06-23T12:30:00.000000"));
    assertEquals(42, ((Number) got.get()).intValue(),
        "subscriptions must be live after the retried connect");
  }

  @Test
  void inboundEpochTimestampsAreNormalized() {
    LocalEventBus bus = new LocalEventBus(DispatchMode.SEQUENTIAL);
    FakeMqttTransport fake = new FakeMqttTransport();
    new MqttService(bus, fake, new JSONArray(), new JSONArray().put("framed/#"),
        "framed", 1, MAPPING, EmissionGate.passthrough(), false, "MQTT-In");

    List<String> stamps = new ArrayList<>();
    bus.register("Measurement.EXT.etCO2.parsed",
        msg -> stamps.add(((JSONObject) msg).getString("timestamp")));

    // Numeric JSON epoch millis (the common MQTT convention) and epoch seconds as a string.
    JSONObject numericMillis = new JSONObject().put("value", 42).put("channelID", "etCO2")
        .put("deviceID", "EXT").put("className", "Measurement")
        .put("timestamp", 1750680000000L);
    fake.deliver("framed/EXT/etCO2", numericMillis.toString().getBytes(StandardCharsets.UTF_8));
    fake.deliver("framed/EXT/etCO2", inboundPayload("1750680000"));

    String expected = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(1_750_680_000L),
        ZoneOffset.UTC).format(Timer.formatter);
    assertEquals(List.of(expected, expected), stamps,
        "13-digit millis and 10-digit seconds must both normalize to the bus format");
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
