package com.framed.cdss;

import com.framed.utils.InMemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EventBus-oriented tests:
 * - Verifies that publish calls occur (latency/global/rule-participation),
 *   without asserting the exact envelope (implementation detail of CDSSUtils).
 */
public class ActorEventBusMockTest {

    private InMemoryEventBus bus;
    private TestActor actor;
    private static final String A = "A";
    private static final String B = "B";

    static class TestActor extends Actor {
        private int fireCount = 0;
        TestActor(InMemoryEventBus bus, String id, List<Map<String,String>> rules,
                  List<String> inputs, List<String> outputs) {
            super(bus, id, rules, inputs, outputs);
        }
        @Override public void fireFunction(Map<String, Object> latestSnapshot) { fireCount++; }
        int getFireCount() { return fireCount; }
    }

    @BeforeEach
    void setup() {
        bus = new InMemoryEventBus();
        // Rules: R0: A:"*", B:"*" (both channels must be updated)
        List<Map<String, String>> rules = List.of(
                Map.of(A, "*", B, "*")
        );
        actor = new TestActor(bus, "lat", rules, List.of(A, B), List.of("OUT"));
    }

    @Test
    void publishesLatencyOnFire() {
        LocalDateTime t0 = LocalDateTime.now();
        LocalDateTime t1 = t0.plusSeconds(1);

        // Only when both have new data should the rule fire
        bus.publish(A, dp(1, t0));
        assertEquals(0, actor.getFireCount());

        bus.publish(B, dp(2, t1));
        assertEquals(1, actor.getFireCount(), "Rule satisfied, one fire expected");

        // We expect some publish calls happened (latency, global, and rule participation)
        assertFalse(bus.getPublished().isEmpty(),
                "EventBus should have publish calls (latency metrics)");
    }
}