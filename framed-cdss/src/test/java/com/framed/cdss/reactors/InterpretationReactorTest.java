// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.cdss.reactors;

import com.framed.utils.InMemoryEventBus;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Interpretation reactor (Definition A13): class-to-warning mapping, quality gates and the
 * per-firing status record.
 */
class InterpretationReactorTest {

  private static final String RR = "RRMismatchClassification";
  private static final String DISL = "Dislocation";
  private static final String SPO2_LIMIT = "SpO2-Limit";
  private static final String SPO2_TREND = "SpO2-Trend";
  private static final String ETCO2 = "etCO2-Limit";
  private static final String HD = "HD-aryth";
  private static final String PI = "PI-Limit";
  private static final String HR = "HR-Limit";
  private static final String SF = "S/F-Limit";
  private static final String STATUS = InterpretationReactor.STATUS_CHANNEL;
  private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  private InMemoryEventBus bus;
  private int second;

  /** All classes in their normal range, with the study bounds. */
  private static Map<String, Integer> normal() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put(RR, 0);
    m.put(DISL, 0);
    m.put(SPO2_LIMIT, 1);
    m.put(SPO2_TREND, 0);
    m.put(ETCO2, 3);
    m.put(HD, 0);
    m.put(PI, 1);
    m.put(HR, 1);
    m.put(SF, 3);
    return m;
  }

  @BeforeEach
  void setUp() {
    bus = new InMemoryEventBus();
    second = 0;
    new InterpretationReactor(bus, "Interpreter", RR, DISL, SPO2_LIMIT, SPO2_TREND, ETCO2, HD, PI, HR,
            SF, true);
  }

  private void publish(String channel, int value) {
    bus.publish(channel, dp(value, T0.plusSeconds(++second)));
  }

  /**
   * Publishes all classes, then clears the capture and publishes the last one again, so the
   * captured messages belong to exactly one firing over the complete state.
   */
  private Map<String, List<Object>> fire(Map<String, Integer> classes) {
    classes.forEach(this::publish);
    bus.clearPublished();
    publish(RR, classes.get(RR));
    Map<String, List<Object>> byChannel = new LinkedHashMap<>();
    bus.getPublished().stream()
            .filter(p -> p.message() instanceof JSONObject)
            .filter(p -> "Interpreter".equals(((JSONObject) p.message()).optString("className")))
            .forEach(p -> byChannel.computeIfAbsent(p.channel(), k -> new java.util.ArrayList<>())
                    .add(((JSONObject) p.message()).get("value")));
    return byChannel;
  }

  @Test
  void normalStateYieldsOnlyANoWarningStatus() {
    Map<String, List<Object>> out = fire(normal());
    assertEquals(Map.of(STATUS, List.of("NO_WARNING")), out);
  }

  @Test
  void everyFiringPublishesExactlyOneStatus() {
    normal().forEach(this::publish);
    long statuses = bus.getPublished().stream().filter(p -> p.channel().equals(STATUS)).count();
    assertEquals(normal().size(), statuses, "one status per input update");
  }

  /** Regression: class 1 (0 < HR <= 125) was reported as asystole. */
  @Test
  void heartRateClassesFollowTheBounds() {
    Map<String, Integer> s = normal();
    s.put(HR, 0);
    assertEquals(List.of("CHECK PATIENT: possible asystole!"), fire(s).get("HR-Limit-Warning"));
    s.put(HR, 2);
    assertEquals(List.of("CHECK PATIENT: Hear rate too high!"), fire(s).get("HR-Limit-Warning"));
    s.put(HR, 1);
    assertNull(fire(s).get("HR-Limit-Warning"));
  }

  /** Regression: class 3 (35-45 mmHg, normal) was reported as high, class 4 not at all. */
  @Test
  void etco2ClassesFollowTheBounds() {
    Map<String, Integer> s = normal();
    s.put(ETCO2, 4);
    assertEquals(List.of("CHECK PATIENT: end tidal CO2 high!"), fire(s).get("etCO2-Limit-Warning"));
    s.put(ETCO2, 3);
    assertNull(fire(s).get("etCO2-Limit-Warning"));
    s.put(ETCO2, 2);
    assertEquals(List.of("CHECK PATIENT: end tidal CO2 moderately low!"), fire(s).get("etCO2-Limit-Warning"));
  }

  @Test
  void lowPiSuppressesPulseOximetryChecks() {
    Map<String, Integer> s = normal();
    s.put(PI, 0);
    s.put(SPO2_LIMIT, 0);
    s.put(HR, 0);
    s.put(SF, 0);
    Map<String, List<Object>> out = fire(s);
    assertEquals(List.of("WARNING;SUPPRESSED:PI"), out.get(STATUS));
    assertEquals(List.of("CHECK PULSEOXIMETER: PI too low!"), out.get("PI-Quality-Warning"));
    assertNull(out.get("SpO2-Limit-Warning"));
    assertNull(out.get("HR-Limit-Warning"));
    assertNull(out.get("SF-Limit-Warning"));
  }

  @Test
  void missingEtco2SuppressesRrMismatch() {
    Map<String, Integer> s = normal();
    s.put(ETCO2, 0);
    s.put(RR, 1);
    Map<String, List<Object>> out = fire(s);
    assertEquals(List.of("WARNING;SUPPRESSED:ETCO2"), out.get(STATUS));
    assertNull(out.get("RRMismatchWarning"));
  }

  @Test
  void bothGatesAreReported() {
    Map<String, Integer> s = normal();
    s.put(ETCO2, 0);
    s.put(PI, 0);
    assertEquals(List.of("WARNING;SUPPRESSED:ETCO2+PI"), fire(s).get(STATUS));
  }

  /** Definition A5: the ARDS categories apply only for SpO2 <= 97 %. */
  @Test
  void sfWarningRequiresSpo2AtMost97() {
    Map<String, Integer> s = normal();
    s.put(SF, 1);
    s.put(SPO2_LIMIT, 1);
    assertEquals(List.of("CHECK PATIENT: S/F indicates moderate ARDS condition!"),
            fire(s).get("SF-Limit-Warning"));
    s.put(SPO2_LIMIT, 2);
    assertNull(fire(s).get("SF-Limit-Warning"));
    s.put(SPO2_LIMIT, 3);
    assertNull(fire(s).get("SF-Limit-Warning"));
  }

  /** Regression: the lock was not released when a classification had an unexpected value. */
  @Test
  void lockIsReleasedAfterAnUnexpectedValue() {
    normal().forEach(this::publish);
    assertThrows(IllegalStateException.class, () -> publish(DISL, 7));

    // a firing from another thread must not block on a leaked lock
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> publish(DISL, 0));
  }
}
