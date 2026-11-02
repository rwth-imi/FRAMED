// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.cdss.reactors;

import com.framed.utils.InMemoryEventBus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Limit classification of Definition 7: index of the first bound the value does not exceed,
 * compared as a real number.
 */
class LimitClassificationReactorTest {

  private static final String IN = "in";
  private static final String OUT = "out";
  private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  /** Feeds the values one per second and returns the published classes in order. */
  private static List<Object> classify(double[] bounds, double... values) {
    InMemoryEventBus bus = new InMemoryEventBus();
    new LimitClassificationReactor(bus, "lcr",
            new JSONArray().put(new JSONObject().put(IN, "*")),
            IN, new JSONArray().put(OUT), new JSONArray(bounds), true);
    for (int i = 0; i < values.length; i++) {
      bus.publish(IN, dp(values[i], T0.plusSeconds(i + 1L)));
    }
    return bus.getPublished().stream()
            .filter(p -> p.channel().equals(OUT))
            .map(p -> ((JSONObject) p.message()).get("value"))
            .toList();
  }

  /** Regression: values were truncated to int, so PI 1.4 fell into the "too low" class. */
  @Test
  void piIsClassifiedOnItsRealValue() {
    assertEquals(List.of(0, 0, 1, 1), classify(new double[]{1}, 0.5, 1.0, 1.4, 7.5));
  }

  @Test
  void sfRatioUsesTheManuscriptBounds() {
    assertEquals(List.of(0, 0, 1, 2, 2, 3),
            classify(new double[]{148, 235, 315}, 98.0, 147.9, 150.8, 235.1, 315.0, 320.0));
  }

  @Test
  void boundsAreInclusiveUpperLimits() {
    assertEquals(List.of(0, 1, 1, 2, 3, 4),
            classify(new double[]{0, 25, 35, 45}, 0, 0.1, 25, 35, 45, 45.1));
  }
}
