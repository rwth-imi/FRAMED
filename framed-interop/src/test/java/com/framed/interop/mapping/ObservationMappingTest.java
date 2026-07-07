package com.framed.interop.mapping;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationMappingTest {

  private static ObservationMapping sample() {
    return ObservationMapping.fromJson(new JSONObject("""
        {
          "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"},
          "Percentage_int.SpO2": {"code":"59408-5","system":"LOINC","display":"SpO2","unit":"%"}
        }"""));
  }

  @Test
  void resolvesDeviceSpecificKey() {
    CodedConcept c = sample().lookup("Measurement", "Oxylog-3000-Plus-00", "etCO2").orElseThrow();
    assertEquals("19889-5", c.code());
    assertEquals("mm[Hg]", c.unit());
    assertTrue(c.isNumeric());
  }

  @Test
  void fallsBackToClassAndChannelKey() {
    // device component absent in the key -> resolves regardless of device id
    CodedConcept c = sample().lookup("Percentage_int", "PC60FW", "SpO2").orElseThrow();
    assertEquals("59408-5", c.code());
    assertEquals("NM", c.valueType(), "defaulted");
  }

  @Test
  void unmappedChannelIsEmpty() {
    assertTrue(sample().lookup("RealTime", "Dev", "CO2_mmHg").isEmpty());
  }

  @Test
  void parsesSdcAttributesWithDefaults() {
    ObservationMapping m = ObservationMapping.fromJson(new JSONObject("""
        {
          "Measurement.Dev.etCO2": {"code":"19889-5","kind":"metric","mdc":"424242"},
          "Settings.Dev.RR":      {"code":"76270-8","kind":"setting"},
          "RealTime.Dev.CO2":     {"unit":"mm[Hg]","kind":"waveform"}
        }"""));

    CodedConcept metric = m.lookup("Measurement", "Dev", "etCO2").orElseThrow();
    assertEquals(CodedConcept.Kind.METRIC, metric.kind());
    assertEquals("424242", metric.mdc());

    assertEquals(CodedConcept.Kind.SETTING, m.lookup("Settings", "Dev", "RR").orElseThrow().kind());

    CodedConcept waveform = m.lookup("RealTime", "Dev", "CO2").orElseThrow();
    assertTrue(waveform.isWaveform());
    assertEquals("", waveform.code(), "SDC-only entries may omit the code");
    assertTrue(m.channelForCode("").isEmpty(), "codeless entries must not enter the reverse index");

    // entries without SDC attributes keep working with defaults
    CodedConcept legacy = sample().lookup("Measurement", "Oxylog-3000-Plus-00", "etCO2").orElseThrow();
    assertEquals(CodedConcept.Kind.METRIC, legacy.kind());
    assertEquals("", legacy.mdc());
  }

  @Test
  void reverseMapsCodeToChannelCoordinates() {
    ObservationMapping.Channel ch = sample().channelForCode("19889-5").orElseThrow();
    assertEquals("Measurement", ch.className());
    assertEquals("Oxylog-3000-Plus-00", ch.deviceID());
    assertEquals("etCO2", ch.channelID());

    // 2-part key -> no device component
    ObservationMapping.Channel spo2 = sample().channelForCode("59408-5").orElseThrow();
    assertEquals(null, spo2.deviceID());
    assertEquals("SpO2", spo2.channelID());
  }
}
