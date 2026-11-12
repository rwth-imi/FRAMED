// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.utils.InMemoryEventBus;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.framed.arn.RuleUtils.publishResult;
import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tag semantics of {@link Reactor}: the per-channel logical-time gate, sequence-delta rules with
 * per-rule pointers, and superdense output tags.
 */
class ReactorTagSemanticsTest {

  private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  /** Collects every record a reactor reports. */
  static final class CapturingRecorder implements FiringRecorder {
    final List<FiringRecord> records = new CopyOnWriteArrayList<>();

    @Override public boolean enabled() { return true; }
    @Override public void record(FiringRecord firing) { records.add(firing); }
    @Override public void countNoRule(String reactorId) { }

    List<FiringRecord> of(String reactorId, FiringRecord.Outcome outcome) {
      return records.stream()
              .filter(r -> r.reactorId().equals(reactorId) && r.outcome() == outcome)
              .toList();
    }
  }

  /**
   * Keeps every snapshot it is handed and publishes {@code output.apply(snapshot)} on its single
   * output channel, with the firing's output tag.
   */
  static final class ProbeReactor extends Reactor {
    final List<Map<String, Object>> snapshots = new CopyOnWriteArrayList<>();
    private final Function<Map<String, Object>, Object> output;

    ProbeReactor(InMemoryEventBus bus, String id, List<Map<String, String>> rules,
                 List<String> inputs, String out, Function<Map<String, Object>, Object> output) {
      super(bus, id, rules, inputs, List.of(out), true);
      this.output = output;
    }

    @Override
    public void reactionFunction(Map<String, Object> snapshot) {
      snapshots.add(snapshot);
      publishResult(eventBus, output.apply(snapshot), id, outputChannels, outputTag(snapshot));
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

  private static JSONObject msg(Object value, int seconds) {
    return dp(value, T0.plusSeconds(seconds));
  }

  private static JSONObject msg(Object value, int seconds, long microstep) {
    return msg(value, seconds).put(RuleUtils.MICROSTEP_FIELD, microstep);
  }

  private ProbeReactor probe(String id, List<Map<String, String>> rules, List<String> inputs) {
    return new ProbeReactor(bus, id, rules, inputs, id + ".out", s -> 1);
  }

  private List<Tag> outputTags(String reactorId) {
    return recorder.of(reactorId, FiringRecord.Outcome.FIRED).stream()
            .map(FiringRecord::outputTag).toList();
  }

  // ---- 1. duplicates ----

  /** A duplicate tag is discarded for {@code "*"} rules and fires nothing. */
  @Test
  void duplicateTagIsDiscardedForAnyRule() {
    ProbeReactor r = probe("any", List.of(Map.of("A", "*")), List.of("A"));

    bus.publish("A", msg(1, 1));
    bus.publish("A", msg(2, 1));

    assertEquals(1, r.snapshots.size());
    assertEquals(1, recorder.of("any", FiringRecord.Outcome.DISCARDED).size());
    assertEquals(1, r.snapshots.get(0).get("A"), "the duplicate does not replace the held value");
  }

  /** A duplicate tag does not increment {@code seq}: an {@code "N"} rule ignores it. */
  @Test
  void duplicateTagDoesNotIncrementSeqForNRule() {
    ProbeReactor r = probe("n", List.of(Map.of("A", "3")), List.of("A"));

    bus.publish("A", msg(1, 1));
    bus.publish("A", msg(1, 1));           // duplicate: discarded
    bus.publish("A", msg(2, 2));
    assertTrue(r.snapshots.isEmpty(), "only 2 accepted messages, rule needs 3");

    bus.publish("A", msg(3, 3));
    assertEquals(1, r.snapshots.size());
    assertEquals(1, recorder.of("n", FiringRecord.Outcome.DISCARDED).size());
  }

  /** The same timestamp with a higher microstep is a new tag and is accepted. */
  @Test
  void higherMicrostepAtTheSameTimestampIsAccepted() {
    ProbeReactor r = probe("micro", List.of(Map.of("A", "*")), List.of("A"));

    bus.publish("A", msg(1, 1, 0));
    bus.publish("A", msg(2, 1, 1));
    bus.publish("A", msg(3, 1, 1));        // duplicate of (t1, 1)

    assertEquals(2, r.snapshots.size());
    assertEquals(1, recorder.of("micro", FiringRecord.Outcome.DISCARDED).size());
    assertEquals(List.of(Tag.of(at(1)), new Tag(at(1), 1)), outputTags("micro"),
            "input microsteps are ignored for T; the second firing gets the next microstep");
  }

  // ---- 2. older tags ----

  /** An older tag is discarded: no held value, seq or rule state changes. */
  @Test
  void olderTagIsDiscarded() {
    ProbeReactor r = probe("old", List.of(Map.of("A", "2")), List.of("A"));

    bus.publish("A", msg(1, 2));
    bus.publish("A", msg(2, 1));           // older: discarded
    assertTrue(r.snapshots.isEmpty(), "the older message does not count towards N");

    bus.publish("A", msg(3, 3));
    assertEquals(1, r.snapshots.size());
    assertEquals(at(3), r.snapshots.get(0).get("A-timestamp"));

    FiringRecord d = recorder.of("old", FiringRecord.Outcome.DISCARDED).get(0);
    assertEquals(Tag.of(at(1)), d.triggerTag());
    assertEquals(Tag.of(at(2)), d.gateTag());
  }

  // ---- 3. independent OR rules ----

  /**
   * R1 = {(C1, 1)} at 1 Hz and R2 = {(C2, 5)} at 0.5 Hz: firing R1 every second does not consume
   * R2's pending C2 updates, so R2 still fires on every fifth C2 message, i.e. every 10 s.
   */
  @Test
  void firingOneOrRuleDoesNotConsumeTheOthersPendingUpdates() {
    probe("or", List.of(Map.of("C1", "1"), Map.of("C2", "5")), List.of("C1", "C2"));

    for (int s = 1; s <= 40; s++) {
      bus.publish("C1", msg(s, s));
      if (s % 2 == 0) bus.publish("C2", msg(s, s));
    }

    List<FiringRecord> fired = recorder.of("or", FiringRecord.Outcome.FIRED);
    List<Instant> r2 = fired.stream().filter(f -> f.triggerChannel().equals("C2"))
            .map(f -> f.triggerTag().t()).toList();
    assertEquals(List.of(at(10), at(20), at(30), at(40)), r2, "R2 fires every 10 s");
    assertEquals(40, fired.stream().filter(f -> f.triggerChannel().equals("C1")).count(),
            "R1 fires on every C1 message");
    fired.stream().filter(f -> f.triggerChannel().equals("C2")).forEach(f -> {
      assertEquals(new Tag(f.triggerTag().t(), 1), f.outputTag(), "same instant as R1's firing");
      assertEquals(List.of("C2"), f.consumed().stream().map(FiringRecord.ConsumedInput::channel).toList());
      assertEquals(f.consumed().get(0).seq() % 5, 0);
    });
  }

  // ---- 4. AND rules ----

  /** An AND rule fires only when every channel of the rule has {@code delta >= n}. */
  @Test
  void andRuleFiresOnlyWhenEveryChannelHasItsDelta() {
    ProbeReactor r = probe("and", List.of(Map.of("A", "2", "B", "*")), List.of("A", "B"));

    bus.publish("A", msg(1, 1));
    bus.publish("B", msg(1, 1));
    assertTrue(r.snapshots.isEmpty(), "A has delta 1 < 2");

    bus.publish("A", msg(2, 2));
    assertEquals(1, r.snapshots.size(), "A delta 2, B delta 1");

    bus.publish("B", msg(3, 3));
    bus.publish("A", msg(4, 4));
    assertEquals(1, r.snapshots.size(), "pointers advanced: A delta 1 < 2");

    bus.publish("B", msg(5, 5));
    assertEquals(1, r.snapshots.size(), "B delta 2, but A delta still 1");

    bus.publish("A", msg(6, 6));
    assertEquals(2, r.snapshots.size());
    assertEquals(List.of(Tag.of(at(2)), Tag.of(at(6))), outputTags("and"));
  }

  // ---- 5. diamond ----

  /**
   * Diamond {@code S -> {A, B} -> C -> D}: one source instant yields C outputs {@code (t, 0)} and
   * {@code (t, 1)}. D, a {@code "*"} consumer of C, accepts both, and its second firing sees the
   * values of both paths for the instant.
   */
  @Test
  void diamondYieldsTwoMicrostepsThatADownstreamConsumerAccepts() {
    new ProbeReactor(bus, "A", List.of(Map.of("S", "*")), List.of("S"), "A",
            s -> "a" + s.get("S"));
    new ProbeReactor(bus, "B", List.of(Map.of("S", "*")), List.of("S"), "B",
            s -> "b" + s.get("S"));
    ProbeReactor c = new ProbeReactor(bus, "C", List.of(Map.of("A", "*"), Map.of("B", "*")),
            List.of("A", "B"), "C", s -> s.get("A") + "|" + s.get("B"));
    ProbeReactor d = new ProbeReactor(bus, "D", List.of(Map.of("C", "*")), List.of("C"), "D",
            s -> s.get("C"));

    bus.publish("S", msg(1, 1));
    bus.publish("S", msg(2, 2));

    assertEquals(List.of(Tag.of(at(1)), new Tag(at(1), 1), Tag.of(at(2)), new Tag(at(2), 1)),
            outputTags("C"));
    List<JSONObject> cOut = new ArrayList<>();
    bus.getPublished().stream().filter(p -> p.channel().equals("C"))
            .forEach(p -> cOut.add((JSONObject) p.message()));
    assertEquals(List.of(0L, 1L, 0L, 1L), cOut.stream()
            .map(o -> o.getLong(RuleUtils.MICROSTEP_FIELD)).toList(), "microstep is on the wire");

    assertEquals(4, d.snapshots.size(), "D accepts (t,0) and (t,1) of each instant");
    assertTrue(recorder.of("D", FiringRecord.Outcome.DISCARDED).isEmpty());
    assertEquals(List.of("a1|null", "a1|b1", "a2|b1", "a2|b2"),
            d.snapshots.stream().map(s -> s.get("C")).toList(),
            "the second firing per instant sees both paths' new values");
    assertEquals(outputTags("C"), outputTags("D"));
    assertEquals(4, c.snapshots.size());
  }
}
