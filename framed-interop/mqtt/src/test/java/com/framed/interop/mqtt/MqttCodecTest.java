package com.framed.interop.mqtt;

import com.framed.interop.mapping.CodedConcept;
import com.framed.io.dispatch.DataPoint;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttCodecTest {

  private static final DataPoint<Integer> DP =
      new DataPoint<>(Instant.parse("2026-06-23T12:00:00Z"), 38, "etCO2", "Oxylog", "Measurement");

  @Test
  void encodesEavAndConcept() {
    CodedConcept c = new CodedConcept("19889-5", "LOINC", "etCO2", "mm[Hg]", "NM");
    JSONObject o = MqttCodec.decode(MqttCodec.encode(DP, c));

    assertEquals(38, o.getInt("value"));
    assertEquals("etCO2", o.getString("channelID"));
    assertEquals("Oxylog", o.getString("deviceID"));
    assertEquals("Measurement", o.getString("className"));
    assertEquals("19889-5", o.getString("code"));
    assertEquals("LOINC", o.getString("system"));
    assertEquals("mm[Hg]", o.getString("unit"));
    assertTrue(o.getString("timestamp").startsWith("2026-06-23T12:00:00"));
  }

  @Test
  void encodesWithoutConceptOmitsCode() {
    JSONObject o = MqttCodec.decode(MqttCodec.encode(DP, null));
    assertEquals(38, o.getInt("value"));
    assertFalse(o.has("code"));
    assertFalse(o.has("unit"));
  }
}
