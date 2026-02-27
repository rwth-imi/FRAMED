package com.framed.cdss;

import com.framed.utils.InMemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal tests: one concrete Actor capturing snapshots;
 * verifies one-fire-per-evaluation and snapshot correctness.
 */
public class ActorMinimalTest {

    private InMemoryEventBus bus;
    private TestActor actor;

    private static final String CH_A = "A";
    private static final String CH_B = "B";

    /** Minimal concrete actor that just records fired snapshots. */
    static class TestActor extends Actor {
        private final List<Map<String, Object>> fired = new ArrayList<>();

        TestActor(InMemoryEventBus bus,
                  String id,
                  List<Map<String, String>> rules,
                  List<String> inputs,
                  List<String> outputs) {
            super(bus, id, rules, inputs, outputs);
        }

        @Override
        public void fireFunction(Map<String, Object> latestSnapshot) {
            fired.add(latestSnapshot);
        }

        public List<Map<String, Object>> getFired() {
            return fired;
        }
    }

    @BeforeEach
    void setup() {
        bus = new InMemoryEventBus();

        // Rules: R0: A:"*" ; R1: B:"*"
        List<Map<String, String>> rules = List.of(
                Map.of(CH_A, "*"),
                Map.of(CH_B, "*")
        );

        actor = new TestActor(
                bus,
                "test-actor",
                rules,
                List.of(CH_A, CH_B),
                List.of("OUT")
        );
    }

    @Test
    void firesOncePerMessage_whenEachRuleSingleChannelStar() {
        // Send datapoints with increasing timestamps
        LocalDateTime t0 = LocalDateTime.now();
        LocalDateTime t1 = t0.plusSeconds(1);
        LocalDateTime t2 = t0.plusSeconds(2);

        bus.publish(CH_A, dp(10, t0));
        bus.publish(CH_B, dp(20, t1));
        bus.publish(CH_A, dp(11, t2));

        List<Map<String, Object>> fired = actor.getFired();
        assertEquals(3, fired.size(), "Expected one fire per incoming message");

        // Check snapshot 1 (after first publish on A)
        Map<String, Object> s0 = fired.get(0);
        assertEquals(10, s0.get(CH_A));
        assertEquals(0, s0.get(CH_B)); // initial value for B is 0 per Actor
        assertNotNull(s0.get("%s-timestamp".formatted(CH_A)));
        assertNotNull(s0.get("%s-timestamp".formatted(CH_B)));

        // Check snapshot 2 (after publish on B)
        Map<String, Object> s1 = fired.get(1);
        assertEquals(10, s1.get(CH_A));
        assertEquals(20, s1.get(CH_B));

        // Check snapshot 3 (after second publish on A)
        Map<String, Object> s2 = fired.get(2);
        assertEquals(11, s2.get(CH_A));
        assertEquals(20, s2.get(CH_B));
    }

    @Test
    void snapshotIsImmutable() {
        bus.publish(CH_A, dp(1, LocalDateTime.now()));
        Map<String, Object> snap = actor.getFired().get(0);

        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", 1));
    }
}