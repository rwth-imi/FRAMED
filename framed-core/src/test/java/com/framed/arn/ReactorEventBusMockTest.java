// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import com.framed.utils.InMemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EventBus-oriented tests:
 * - Verifies that a firing publishes nothing beyond inputs and declared outputs.
 */
public class ReactorEventBusMockTest {

    private InMemoryEventBus bus;
    private TestReactor reactor;
    private static final String A = "A";
    private static final String B = "B";

    static class TestReactor extends Reactor {
        private int fireCount = 0;
        TestReactor(InMemoryEventBus bus, String id, List<Map<String,String>> rules,
                    List<String> inputs, List<String> outputs) {
            super(bus, id, rules, inputs, outputs, true);
        }
        @Override public void reactionFunction(Map<String, Object> latestSnapshot) { fireCount++; }
        int getFireCount() { return fireCount; }
    }

    @BeforeEach
    void setup() {
        bus = new InMemoryEventBus();
        // Rules: R0: A:"*", B:"*" (both channels must be updated)
        List<Map<String, String>> rules = List.of(
                Map.of(A, "*", B, "*")
        );
        reactor = new TestReactor(bus, "lat", rules, List.of(A, B), List.of("OUT"));
    }

    /**
     * Regression: measurement no longer publishes latency on the bus, so a firing leaves only
     * the inputs (and any declared outputs) there.
     */
    @Test
    void publishesOnlyInputsAndOutputs() {
        ZonedDateTime t0 = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime t1 = t0.plusSeconds(1);

        // Only when both have new data should the rule fire
        bus.publish(A, dp(1, t0));
        assertEquals(0, reactor.getFireCount());

        bus.publish(B, dp(2, t1));
        assertEquals(1, reactor.getFireCount(), "Rule satisfied, one fire expected");

        java.util.Set<String> allowed = java.util.Set.of(A, B, "OUT",
                com.framed.core.Service.addressRegistry("CDSS"));
        bus.getPublished().forEach(p -> assertTrue(allowed.contains(p.channel()),
                "unexpected publication on " + p.channel()));
    }
}