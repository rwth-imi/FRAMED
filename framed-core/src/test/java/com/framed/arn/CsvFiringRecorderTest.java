// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * File format and non-blocking behaviour of {@link CsvFiringRecorder}.
 */
class CsvFiringRecorderTest {

  private static final Instant T = Instant.parse("2026-01-01T12:00:00Z");
  private static final String CHANNEL = "Measurement.Oxylog.End-tidal CO2 concentration, etCO2.parsed";

  private static FiringRecord fired(long evaluation) {
    return new FiringRecord("R", evaluation, FiringRecord.Outcome.FIRED, true, CHANNEL,
            Tag.of(T), T.plusNanos(10), new Tag(T, 2), Tag.ZERO, T.plusNanos(20), T.plusNanos(30),
            T.plusNanos(40), List.of(new FiringRecord.ConsumedInput(CHANNEL, 1, Tag.of(T), T.plusNanos(10))));
  }

  private static FiringRecord discarded(long evaluation) {
    return new FiringRecord("R", evaluation, FiringRecord.Outcome.DISCARDED, false, "B",
            Tag.of(T), T.plusNanos(10), null, Tag.of(T.plusSeconds(1)), null, null, null, List.of());
  }

  @Test
  void writesFiringsInputsAndSummary(@TempDir Path dir) throws IOException {
    CsvFiringRecorder recorder = new CsvFiringRecorder(dir, "run 1", 100);
    recorder.record(fired(1));
    recorder.record(discarded(2));
    recorder.countNoRule("R");
    recorder.close();

    List<String> firings = Files.readAllLines(dir.resolve("run_1_firings.csv"));
    assertEquals(3, firings.size(), "header + 2 rows");
    assertTrue(firings.get(0).startsWith("run,reactor,evaluation,outcome,"));
    long tNanos = T.getEpochSecond() * 1_000_000_000L;
    assertEquals("\"run 1\",\"R\",1,FIRED,true,\"" + CHANNEL + "\"," + tNanos + ",0," + (tNanos + 10)
            + "," + tNanos + ",2,0,0," + (tNanos + 20) + "," + (tNanos + 30) + "," + (tNanos + 40) + ",1",
            firings.get(1));
    assertEquals("\"run 1\",\"R\",2,DISCARDED,false,\"B\"," + tNanos + ",0," + (tNanos + 10)
            + ",,," + (tNanos + 1_000_000_000L) + ",0,,,,0", firings.get(2),
            "a discarded row has no output tag and no reaction times");

    List<String> inputs = Files.readAllLines(dir.resolve("run_1_inputs.csv"));
    assertEquals(2, inputs.size(), "header + 1 consumed input");
    assertEquals("\"run 1\",\"R\",1,\"" + CHANNEL + "\",1," + tNanos + ",0," + (tNanos + 10),
            inputs.get(1));

    List<String> summary = Files.readAllLines(dir.resolve("run_1_summary.csv"));
    assertEquals(List.of("run,reactor,fired,discarded,no_rule", "\"run 1\",\"R\",1,1,1",
            "\"run 1\",\"*dropped*\",0,,"), summary);
  }

  /** A full queue must drop and count, never block the reactor thread. */
  @Test
  void fullQueueDropsInsteadOfBlocking(@TempDir Path dir) throws IOException {
    int n = 10_000;
    CsvFiringRecorder recorder = new CsvFiringRecorder(dir, "load", 1);
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      for (int i = 1; i <= n; i++) {
        recorder.record(fired(i));
      }
    });
    recorder.close();

    long written = Files.readAllLines(dir.resolve("load_firings.csv")).size() - 1L;
    assertEquals(n, written + recorder.dropped(), "every record is either written or counted");
    assertTrue(Files.readAllLines(dir.resolve("load_summary.csv")).contains("\"load\",\"R\"," + n + ",0,0"),
            "summary counts include dropped records");
  }

  @Test
  void closeIsIdempotentAndStopsRecording(@TempDir Path dir) throws IOException {
    CsvFiringRecorder recorder = new CsvFiringRecorder(dir, "twice", 10);
    recorder.record(fired(1));
    recorder.close();
    recorder.close();
    recorder.record(fired(2));

    assertFalse(recorder.enabled());
    assertEquals(2, Files.readAllLines(dir.resolve("twice_firings.csv")).size());
  }
}
