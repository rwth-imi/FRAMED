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
      publishResult(eventBus, 1, id, List.of(OUT), outputTag(snapshot));
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
    assertEquals(Tag.of(at(2)), r.triggerTag());
    assertEquals(Tag.of(at(2)), r.outputTag());
    assertEquals(Tag.ZERO, r.gateTag(), "first message on B");
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
   * Formerly gated: a sample older than the last firing, arriving on another channel, is
   * accepted and fires at once, at the held maximum timestamp with the next microstep.
   */
  @Test
  void olderSampleOnAnotherChannelFiresAtTheNextMicrostep() {
    new EchoReactor(bus, "late", List.of(Map.of(A, "*"), Map.of(B, "*")), true);

    bus.publish(A, dp(1, T0.plusSeconds(2)));
    bus.publish(B, dp(2, T0.plusSeconds(1)));
    bus.publish(A, dp(3, T0.plusSeconds(3)));

    assertEquals(List.of(FiringRecord.Outcome.FIRED, FiringRecord.Outcome.FIRED,
            FiringRecord.Outcome.FIRED), outcomes());
    FiringRecord late = recorder.records.get(1);
    assertEquals(B, late.triggerChannel());
    assertEquals(new Tag(at(2), 1), late.outputTag());
    assertEquals(List.of(B), late.consumed().stream().map(FiringRecord.ConsumedInput::channel).toList());
    assertEquals(Tag.of(at(1)), late.consumed().get(0).tag());
    assertEquals(Tag.of(at(3)), recorder.records.get(2).outputTag());
    assertEquals(3, bus.getPublished().stream().filter(p -> p.channel().equals(OUT)).count());
  }

  /** A duplicate tag on a channel is discarded: recorded, but no evaluation and no output. */
  @Test
  void duplicateTagIsRecordedAsDiscarded() {
    new EchoReactor(bus, "dup", List.of(Map.of(A, "*")), true);

    bus.publish(A, dp(1, T0.plusSeconds(1)));
    bus.publish(A, dp(2, T0.plusSeconds(1)));

    assertEquals(List.of(FiringRecord.Outcome.FIRED, FiringRecord.Outcome.DISCARDED), outcomes());
    FiringRecord d = recorder.records.get(1);
    assertEquals(2, d.evaluation());
    assertEquals(Tag.of(at(1)), d.triggerTag());
    assertEquals(Tag.of(at(1)), d.gateTag());
    assertNull(d.outputTag());
    assertNull(d.decision());
    assertNull(d.reactStart());
    assertTrue(d.consumed().isEmpty());
    assertNull(recorder.noRule.get("dup"), "a discarded message is not evaluated");
    assertEquals(1, bus.getPublished().stream().filter(p -> p.channel().equals(OUT)).count());
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

  private List<FiringRecord.Outcome> outcomes() {
    return recorder.records.stream().map(FiringRecord::outcome).toList();
  }

  /**
   * Regression: {@code "r:v"} compares the message's {@code "value"}; it used to compare the whole
   * JSON message and never matched.
   */
  @Test
  void requireValueMatchesTheLatestValue() {
    new EchoReactor(bus, "value", List.of(Map.of(A, "r:1")), true);

    bus.publish(A, dp(0, T0.plusSeconds(1)));
    assertTrue(recorder.records.isEmpty(), "value 0 does not match");
    bus.publish(A, dp(1, T0.plusSeconds(2)));

    assertEquals(List.of(FiringRecord.Outcome.FIRED), outcomes());
    assertEquals(1, recorder.noRule.get("value").get());
  }

  /** Each path carrying the same instant fires, at increasing microsteps. */
  @Test
  void sameInstantOverASecondPathFiresAtTheNextMicrostep() {
    new EchoReactor(bus, "paths", List.of(Map.of(A, "*"), Map.of(B, "*")), true);

    bus.publish(A, dp(1, T0.plusSeconds(1)));
    bus.publish(B, dp(2, T0.plusSeconds(1)));

    assertEquals(List.of(FiringRecord.Outcome.FIRED, FiringRecord.Outcome.FIRED), outcomes());
    assertEquals(List.of(Tag.of(at(1)), new Tag(at(1), 1)),
            recorder.records.stream().map(FiringRecord::outputTag).toList());
    assertEquals(List.of(B), recorder.records.get(1).consumed().stream()
            .map(FiringRecord.ConsumedInput::channel).toList(), "second firing consumes B only");
  }

  /** After a microstep firing, a newer timestamp resets the microstep to 0. */
  @Test
  void newerTimestampResetsTheMicrostep() {
    new EchoReactor(bus, "reset", List.of(Map.of(A, "*"), Map.of(B, "*")), false);

    bus.publish(A, dp(1, T0.plusSeconds(2)));
    bus.publish(B, dp(2, T0.plusSeconds(2)));
    bus.publish(A, dp(3, T0.plusSeconds(4)));
    bus.publish(B, dp(4, T0.plusSeconds(3)));   // late path; held maximum stays t4

    assertEquals(List.of(Tag.of(at(2)), new Tag(at(2), 1), Tag.of(at(4)), new Tag(at(4), 1)),
            recorder.records.stream().map(FiringRecord::outputTag).toList());
  }
}
