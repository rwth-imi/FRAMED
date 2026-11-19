// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.cdss.reactors;

import com.framed.utils.InMemoryEventBus;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tube-dislocation classification, Eq. 10 of the manuscript.
 */
class DislocationClassificationReactorTest {

  private static final String TREND = "SpO2-Trend";
  private static final String ETCO2 = "etCO2-Limit";
  private static final String SF = "S/F-Limit";
  private static final String OUT = "Dislocation";
  private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  /** Feeds one complete input triple and returns all outputs published. */
  private static List<Object> outputs(int spo2Trend, int sfClass, int etco2Class) {
    InMemoryEventBus bus = new InMemoryEventBus();
    new DislocationClassificationReactor(bus, "disl", TREND, ETCO2, SF, OUT, true);
    // S/F alone satisfies no rule; S/F + trend fires but etCO2 is still missing (no output);
    // etCO2 then fires with all three present.
    bus.publish(SF, dp(sfClass, T0.plusSeconds(1)));
    bus.publish(TREND, dp(spo2Trend, T0.plusSeconds(2)));
    bus.publish(ETCO2, dp(etco2Class, T0.plusSeconds(3)));
    return bus.getPublished().stream()
            .filter(p -> p.channel().equals(OUT))
            .map(p -> ((JSONObject) p.message()).get("value"))
            .toList();
  }

  private static int classify(int spo2Trend, int sfClass, int etco2Class) {
    List<Object> out = outputs(spo2Trend, sfClass, etco2Class);
    assertEquals(1, out.size(), "exactly one output once all inputs are present");
    return (int) out.get(0);
  }

  @Test
  void equation10WithModerateOrSevereSf() {
    for (int sf : new int[]{0, 1}) {
      assertEquals(1, classify(1, sf, 0), "esophagus, sf=" + sf);
      assertEquals(2, classify(1, sf, 2), "one lung, sf=" + sf);
      assertEquals(3, classify(1, sf, 3), "tube ok, critical S/F, sf=" + sf);
      assertEquals(0, classify(1, sf, 1), "etCO2 class 1 is not covered by Eq. 10, sf=" + sf);
      assertEquals(0, classify(1, sf, 4), "etCO2 class 4 is not covered by Eq. 10, sf=" + sf);
    }
  }

  /** Regression: the condition was sfState >= 1, i.e. mild or no ARDS. */
  @Test
  void mildOrNoArdsNeverIndicatesDislocation() {
    for (int etco2 = 0; etco2 <= 4; etco2++) {
      assertEquals(0, classify(1, 2, etco2), "mild, etCO2=" + etco2);
      assertEquals(0, classify(1, 3, etco2), "none, etCO2=" + etco2);
    }
  }

  @Test
  void noDownwardSpo2TrendNeverIndicatesDislocation() {
    assertEquals(0, classify(0, 0, 0));
  }
}
