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
