// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.io.dispatch;

import com.framed.core.utils.Timer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The microstep of a sample's tag survives the sink path: bus message → {@link DataPointParser}
 * → {@link DataPoint#toJsonString()}.
 */
class DataPointMicrostepTest {

  private static final Instant T = Instant.parse("2026-01-01T12:00:00.000123Z");

  private static JSONObject message() {
    return new JSONObject()
            .put("timestamp", T.atZone(ZoneOffset.UTC).format(Timer.formatter))
            .put("value", 1)
            .put("channelID", "SpO2-Limit")
            .put("deviceID", "CDSS")
            .put("className", "SpO2-Limit-Classifier");
  }

  @Test
  void microstepIsParsedAndSerialized() throws Exception {
    DataPoint<?> dp = DataPointParser.parse(message().put("microstep", 2));

    assertEquals(2, dp.microstep());
    assertEquals(T, dp.timestamp());
    assertEquals(2, new JSONObject(dp.toJsonString()).getLong("microstep"));
  }

  @Test
  void missingMicrostepReadsAsZero() throws Exception {
    DataPoint<?> dp = DataPointParser.parse(message());

    assertEquals(0, dp.microstep());
    assertEquals(0, new JSONObject(dp.toJsonString()).getLong("microstep"));
    assertEquals(0, new DataPoint<>(T, 1, "c", "d", "k").microstep(), "5-argument constructor");
  }

  @Test
  void negativeMicrostepIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new DataPoint<>(T, 1, "c", "d", "k", -1));
  }
}
