// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.utils.InMemoryEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.framed.arn.RuleUtils.publishResult;
import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Off-bus timing of {@link Reactor} evaluations via the {@link FiringRecorder}.
 */
class ReactorFiringRecorderTest {

  private static final String A = "A";
  private static final String B = "B";
  private static final String OUT = "OUT";
  private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  /** Collects everything a reactor reports. */
  static final class CapturingRecorder implements FiringRecorder {
    final List<FiringRecord> records = new CopyOnWriteArrayList<>();
    final Map<String, AtomicInteger> noRule = new ConcurrentHashMap<>();
    volatile boolean enabled = true;

    @Override public boolean enabled() { return enabled; }
    @Override public void record(FiringRecord firing) { records.add(firing); }
    @Override public void countNoRule(String reactorId) {
      noRule.computeIfAbsent(reactorId, k -> new AtomicInteger()).incrementAndGet();
    }
  }

  /** Publishes one output per firing. */
  static final class EchoReactor extends Reactor {
    EchoReactor(InMemoryEventBus bus, String id, List<Map<String, String>> rules, boolean atomic) {
      super(bus, id, rules, List.of(A, B), List.of(OUT), atomic);
    }

    @Override
    public void reactionFunction(Map<String, Object> snapshot) {
      publishResult(eventBus, 1, id, List.of(OUT), lastLogicalFireTs);
    }
  }

  private InMemoryEventBus bus;
  private CapturingRecorder recorder;
  private FiringRecorder previous;

  @BeforeEach
  void setUp() {
    bus = new InMemoryEventBus();
    recorder = new CapturingRecorder();
    previous = FiringRecorders.install(recorder);
  }

  @AfterEach
  void tearDown() {
    FiringRecorders.install(previous);
  }

  private static Instant at(int seconds) {
    return T0.plusSeconds(seconds).toInstant();
  }

  @Test
  void andRuleRecordsTriggerAndBothConsumedInputs() {
    new EchoReactor(bus, "and", List.of(Map.of(A, "*", B, "*")), true);

    bus.publish(A, dp(1, T0.plusSeconds(1)));
    assertTrue(recorder.records.isEmpty(), "A alone satisfies no rule");
    assertEquals(1, recorder.noRule.get("and").get());

    bus.publish(B, dp(2, T0.plusSeconds(2)));
    assertEquals(1, recorder.records.size());
    FiringRecord r = recorder.records.get(0);
    assertEquals(FiringRecord.Outcome.FIRED, r.outcome());
    assertEquals(B, r.triggerChannel());
    assertEquals(at(2), r.triggerTs());
    assertEquals(at(2), r.logicalTs());
    assertEquals(Instant.EPOCH, r.gateTs());
    assertEquals(2, r.evaluation());
    assertEquals(List.of(A, B), r.consumed().stream().map(FiringRecord.ConsumedInput::channel).sorted().toList());
    r.consumed().forEach(in -> assertNotNull(in.arrival(), "arrival of " + in.channel()));
  }

  @Test
  void timingIsOrderedForAtomic() {
    assertOrderedTiming(true);
  }

  @Test
  void timingIsOrderedForNonAtomic() {
    assertOrderedTiming(false);
  }

  private void assertOrderedTiming(boolean atomic) {
    new EchoReactor(bus, "order", List.of(Map.of(A, "*")), atomic);

    bus.publish(A, dp(1, T0.plusSeconds(1)));

    FiringRecord r = recorder.records.get(0);
    assertEquals(atomic, r.atomic());
    assertFalse(r.decision().isBefore(r.triggerArrival()), "decision before arrival");
    assertFalse(r.reactStart().isBefore(r.decision()), "reaction before decision");
    assertFalse(r.reactEnd().isBefore(r.reactStart()), "reaction ended before it started");
  }

  /**
   * Regression for the deferral behind the v03 latency outliers: a sample older than the last
   * firing is gated and consumed only by the next firing.
   */
  @Test
  void olderSampleIsGatedAndConsumedByTheNextFiring() {
    new EchoReactor(bus, "gate", List.of(Map.of(A, "*"), Map.of(B, "*")), true);

    bus.publish(A, dp(1, T0.plusSeconds(2)));
    bus.publish(B, dp(2, T0.plusSeconds(1)));
    bus.publish(A, dp(3, T0.plusSeconds(3)));

    assertEquals(3, recorder.records.size());
    FiringRecord gated = recorder.records.get(1);
    assertEquals(FiringRecord.Outcome.GATED, gated.outcome());
    assertEquals(B, gated.triggerChannel());
    assertEquals(at(2), gated.gateTs());
    assertNull(gated.reactStart());
    assertNull(gated.reactEnd());
    assertEquals(List.of(B), gated.consumed().stream().map(FiringRecord.ConsumedInput::channel).toList());

    FiringRecord next = recorder.records.get(2);
    assertEquals(FiringRecord.Outcome.FIRED, next.outcome());
    assertEquals(A, next.triggerChannel());
    assertTrue(next.consumed().stream().anyMatch(in -> in.channel().equals(B) && in.ts().equals(at(1))),
            "the gated B sample is consumed by the next firing");
    assertEquals(2, bus.getPublished().stream().filter(p -> p.channel().equals(OUT)).count(),
            "only the two FIRED evaluations produce output");
  }

  /** Regression: measurement must not publish anything on the bus. */
  @Test
  void firingsPublishNoLatencyMessages() {
    new EchoReactor(bus, "quiet", List.of(Map.of(A, "*"), Map.of(B, "*")), false);

    for (int i = 1; i <= 5; i++) {
      bus.publish(i % 2 == 0 ? A : B, dp(i, T0.plusSeconds(i)));
    }

    assertEquals(5, recorder.records.size());
    assertTrue(bus.getPublished().stream().noneMatch(p -> p.channel().startsWith("Latency")),
            "no Latency* channel on the bus");
  }

  @Test
  void disabledRecorderReceivesNothing() {
    recorder.enabled = false;
    new EchoReactor(bus, "off", List.of(Map.of(A, "*", B, "*")), true);

    bus.publish(A, dp(1, T0.plusSeconds(1)));
    bus.publish(B, dp(2, T0.plusSeconds(2)));

    assertTrue(recorder.records.isEmpty());
    assertTrue(recorder.noRule.isEmpty());
    assertEquals(1, bus.getPublished().stream().filter(p -> p.channel().equals(OUT)).count(),
            "the reactor still fires");
  }
}
