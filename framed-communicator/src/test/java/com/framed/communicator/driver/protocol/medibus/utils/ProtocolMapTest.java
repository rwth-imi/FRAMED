package com.framed.communicator.driver.protocol.medibus.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the structured Medibus parameter catalog: every parameter must carry an address-safe id,
 * and within the numeric parameter maps an id must never denote two different parameters (different
 * labels). Same-meaning duplicates (one parameter reachable via several codes) are allowed.
 */
class ProtocolMapTest {

  private static final Map<String, Map<Byte, MedibusParam>> PARAM_MAPS = new TreeMap<>(Map.of(
      "MeasurementCP1", ProtocolMap.MedibusXMeasurementCP1,
      "MeasurementCP2", ProtocolMap.MedibusXMeasurementCP2,
      "RealTime", ProtocolMap.MedibusXRealTimeData,
      "DeviceSettings", ProtocolMap.MedibusXDeviceSettings));

  private static final Map<String, Map<Byte, MedibusParam>> ALL_MAPS = new TreeMap<>(Map.of(
      "MeasurementCP1", ProtocolMap.MedibusXMeasurementCP1,
      "MeasurementCP2", ProtocolMap.MedibusXMeasurementCP2,
      "RealTime", ProtocolMap.MedibusXRealTimeData,
      "DeviceSettings", ProtocolMap.MedibusXDeviceSettings,
      "AlarmsCP1", ProtocolMap.MedibusXAlarmsCP1,
      "AlarmsCP2", ProtocolMap.MedibusXAlarmsCP2,
      "TextMessages", ProtocolMap.MedibusXTextMessages));

  @Test
  void everyIdIsPresentAndAddressSafe() {
    for (var mapEntry : ALL_MAPS.entrySet()) {
      for (var e : mapEntry.getValue().entrySet()) {
        MedibusParam p = e.getValue();
        String where = "%s[0x%02X]".formatted(mapEntry.getKey(), e.getKey());
        assertNotNull(p, where + " is null");
        assertFalse(p.id() == null || p.id().isBlank(), where + " has a blank id");
        // '.' and whitespace would break the dotted bus address "<className>.<deviceID>.<id>.parsed"
        assertFalse(p.id().contains("."), where + " id contains '.': " + p.id());
        assertFalse(p.id().chars().anyMatch(Character::isWhitespace),
            where + " id contains whitespace: '" + p.id() + "'");
      }
    }
  }

  @Test
  void parameterIdsAreUnambiguous() {
    StringBuilder problems = new StringBuilder();
    for (var mapEntry : PARAM_MAPS.entrySet()) {
      Map<String, String> idToLabel = new HashMap<>();
      for (MedibusParam p : mapEntry.getValue().values()) {
        String prev = idToLabel.putIfAbsent(p.id(), p.label());
        if (prev != null && !prev.equals(p.label())) {
          problems.append("%n  %s: id '%s' used for both \"%s\" and \"%s\""
              .formatted(mapEntry.getKey(), p.id(), prev, p.label()));
        }
      }
    }
    assertEquals("", problems.toString(),
        "Ambiguous ids (add OVERRIDE_* entries to disambiguate):" + problems);
  }

  @Test
  void migratedChannelIdsMatchConfig() {
    assertEquals("etCO2", ProtocolMap.MedibusXMeasurementCP1.get((byte) 0xDB).id());
    assertEquals("FiO2", ProtocolMap.MedibusXMeasurementCP1.get((byte) 0xF0).id());
    assertEquals("CO2_mmHg", ProtocolMap.MedibusXRealTimeData.get((byte) 0x06).id());
    assertEquals("RR", ProtocolMap.MedibusXDeviceSettings.get((byte) 0x09).id());
  }
}
