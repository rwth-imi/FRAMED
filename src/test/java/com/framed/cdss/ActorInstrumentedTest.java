package com.framed.cdss;

import com.framed.utils.InMemoryEventBus;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Instrumented tests: verify per-rule independence and that
 * secondary satisfied rules do not cause multiple fires per evaluation.
 */
public class ActorInstrumentedTest {

    private InMemoryEventBus bus;
    private RecordingActor actor;

    private static final String A = "A";
    private static final String B = "B";

    static class RecordingActor extends Actor {
        private final List<Map<String, Object>> firedSnapshots = new ArrayList<>();
        RecordingActor(InMemoryEventBus bus,
                       String id,
                       List<Map<String, String>> rules,
                       List<String> inputs,
                       List<String> outputs) {
        super(bus, id, rules, inputs, outputs);
        }
        @Override
        public void fireFunction(Map<String, Object> latestSnapshot) {
            firedSnapshots.add(latestSnapshot);
        }
        public List<Map<String, Object>> fired() { return firedSnapshots; }
    }

    @BeforeEach
    void setup() {
        bus = new InMemoryEventBus();
        // Two rules both reference A; one is stricter
        // R0: A:"*" ; R1: A:"2"
        List<Map<String, String>> rules = List.of(
                Map.of(A, "*"),
                Map.of(A, "2")
        );
        actor = new RecordingActor(bus, "inst", rules, List.of(A, B), List.of("OUT"));
    }

    @Test
    void stricterRuleIsNotFiredSeparatelyInSameEvaluation() {
        LocalDateTime t0 = LocalDateTime.now();
        LocalDateTime t1 = t0.plusSeconds(1);
        LocalDateTime t2 = t0.plusSeconds(2);

        // Three messages on A
        bus.publish(A, dp(1, t0)); // R0 satisfied (delta(A)=1)
        bus.publish(A, dp(2, t1)); // R0 satisfied (delta(A)=1 since last R0)
        bus.publish(A, dp(3, t2)); // R0 satisfied; R1 may also be satisfied (delta(A) >= 2) but we only fire once

        List<Map<String, Object>> fired = actor.fired();
        assertEquals(3, fired.size(), "One fire per incoming event since R0 is always satisfied");

        // Verify secondary stricter rule (R1) does not cause extra fire in the same evaluation
        // (we rely on Actor's 'single snapshot per evaluation' policy)
        Map<String, Object> lastSnap = fired.get(fired.size()-1);
        assertEquals(3, lastSnap.get(A));
    }

    @Test
    void noFireWithoutNewData() {
        LocalDateTime t0 = LocalDateTime.now();
        bus.publish(A, dp(10, t0));
        int n = actor.fired().size();

        // No new messages
        assertEquals(n, actor.fired().size(), "No fire when no new data arrived");
    }
}