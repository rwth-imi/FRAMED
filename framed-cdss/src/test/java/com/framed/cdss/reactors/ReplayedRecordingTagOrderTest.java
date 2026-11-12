// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.cdss.reactors;

import com.framed.arn.FiringRecord;
import com.framed.arn.FiringRecorder;
import com.framed.arn.FiringRecorders;
import com.framed.arn.Reactor;
import com.framed.arn.RuleUtils;
import com.framed.arn.Tag;
import com.framed.orchestrator.Factory;
import com.framed.utils.InMemoryEventBus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.framed.core.utils.Timer.formatter;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays a recorded session through the CDSS reactor network of the replay profile and checks
 * that every reactor's output tags strictly increase, both in the order the reactor fired and in
 * the order its messages appear on each output channel.
 *
 * <p>The network is built from the {@code Reactors} section of {@code config/services_replay.json}
 * with {@link Factory}, as {@code Main} does. Samples are published in timestamp order, as the
 * replay protocol does, on a synchronous {@link InMemoryEventBus}; their recorded timestamps are
 * kept (the replay shifts them by a constant, which leaves the tag order unchanged). The test is
 * skipped when the recording or the profile is absent.</p>
 */
class ReplayedRecordingTagOrderTest {

  private static final Path PROFILE = Path.of("..", "config", "services_replay.json");
  private static final Path RECORDING = Path.of("..", "data", "replay", "p01-VC1-clean-0.jsonl");

  /** Collects every record. */
  static final class CapturingRecorder implements FiringRecorder {
    final List<FiringRecord> records = new CopyOnWriteArrayList<>();

    @Override public boolean enabled() { return true; }
    @Override public void record(FiringRecord firing) { records.add(firing); }
    @Override public void countNoRule(String reactorId) { }
  }

  private CapturingRecorder recorder;
  private FiringRecorder previous;

  @BeforeEach
  void setUp() {
    recorder = new CapturingRecorder();
    previous = FiringRecorders.install(recorder);
  }

  @AfterEach
  void tearDown() {
    FiringRecorders.install(previous);
  }

  @Test
  void outputTagsStrictlyIncreaseWithAtomicReactors() throws Exception {
    replayAndCheck(true);
  }

  @Test
  void outputTagsStrictlyIncreaseWithNonAtomicReactors() throws Exception {
    replayAndCheck(false);
  }

  private void replayAndCheck(boolean atomic) throws Exception {
    assumeTrue(Files.isRegularFile(PROFILE) && Files.isRegularFile(RECORDING),
            "replay profile or recording not present");

    InMemoryEventBus bus = new InMemoryEventBus();
    List<String> reactorIds = new ArrayList<>();
    JSONArray reactors = new JSONObject(Files.readString(PROFILE)).getJSONArray("Reactors");
    for (int i = 0; i < reactors.length(); i++) {
      JSONObject cfg = reactors.getJSONObject(i).put("atomic", atomic);
      Reactor r = (Reactor) Factory.instantiate(cfg, bus);
      reactorIds.add(cfg.getString("id"));
      assertNotNull(r);
    }

    for (JSONObject sample : samplesInTimestampOrder()) {
      bus.publish(sample.getString("address"), sample);
    }

    // in firing order, per reactor
    Map<String, List<Tag>> firedTags = new LinkedHashMap<>();
    long laterMicrosteps = 0;
    for (FiringRecord r : recorder.records) {
      if (r.outcome() != FiringRecord.Outcome.FIRED) continue;
      firedTags.computeIfAbsent(r.reactorId(), k -> new ArrayList<>()).add(r.outputTag());
      if (r.outputTag().m() >= 1) laterMicrosteps++;
    }
    assertEquals(new TreeSet<>(reactorIds), new TreeSet<>(firedTags.keySet()),
            "every configured reactor fires on this recording");
    firedTags.forEach((id, tags) -> assertStrictlyIncreasing(tags, "firings of " + id));
    assertTrue(laterMicrosteps > 0, "the network produces firings at microstep >= 1");

    // on the wire, per producing reactor and output channel
    Map<String, List<Tag>> wireTags = new LinkedHashMap<>();
    bus.getPublished().forEach(p -> {
      if (p.message() instanceof JSONObject o && o.has(RuleUtils.MICROSTEP_FIELD)) {
        wireTags.computeIfAbsent(o.getString("className") + " -> " + p.channel(),
                k -> new ArrayList<>()).add(RuleUtils.extractTag(o));
      }
    });
    assertFalse(wireTags.isEmpty());
    wireTags.forEach((key, tags) -> assertStrictlyIncreasing(tags, key));
  }

  private static void assertStrictlyIncreasing(List<Tag> tags, String what) {
    for (int i = 1; i < tags.size(); i++) {
      Tag prev = tags.get(i - 1);
      Tag cur = tags.get(i);
      assertTrue(cur.isAfter(prev), "%s: tag %d %s not after %s".formatted(what, i, cur, prev));
    }
  }

  /**
   * Reads the recording as parsed-address samples, timestamps rendered with
   * {@code Timer.formatter}, stably sorted by timestamp.
   */
  private static List<JSONObject> samplesInTimestampOrder() throws IOException {
    record Timed(Instant t, JSONObject sample) { }
    List<Timed> timed = new ArrayList<>();
    for (String line : Files.readAllLines(RECORDING)) {
      if (line.isBlank()) continue;
      JSONObject in = new JSONObject(line);
      Instant t = Instant.parse(in.getString("timestamp"));
      JSONObject out = new JSONObject()
              .put("timestamp", t.atZone(ZoneOffset.UTC).format(formatter))
              .put("value", in.get("value"))
              .put("className", in.getString("className"))
              .put("deviceID", in.getString("deviceID"))
              .put("channelID", in.getString("channelID"))
              .put("address", "%s.%s.%s.parsed".formatted(in.getString("className"),
                      in.getString("deviceID"), in.getString("channelID")));
      timed.add(new Timed(t, out));
    }
    timed.sort(Comparator.comparing(Timed::t));
    return timed.stream().map(Timed::sample).toList();
  }
}
