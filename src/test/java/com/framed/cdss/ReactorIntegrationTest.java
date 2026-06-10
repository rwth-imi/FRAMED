package com.framed.cdss;

import com.framed.utils.InMemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

import static com.framed.utils.JsonFixtures.dp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level scenarios:
 * - Multiple channels, interleaved messages
 * - Verify each datapoint triggers a single evaluation when rules allow
 * - Verify snapshot values reflect correct arrival order
 * - Verify no datapoints are skipped in practice
 */
public class ReactorIntegrationTest {

    private InMemoryEventBus bus;
    private CaptureActor reactor;

    private static final String A = "A";
    private static final String B = "B";
    private static final String C = "C";

    static class CaptureActor extends Reactor {
        private final List<Map<String, Object>> snaps = new ArrayList<>();
        CaptureActor(InMemoryEventBus bus, String id, List<Map<String,String>> rules,
                     List<String> inputs, List<String> outputs) {
            super(bus, id, rules, inputs, outputs, true);
        }
        @Override public void reactionFunction(Map<String, Object> latestSnapshot) { snaps.add(latestSnapshot); }
        List<Map<String,Object>> snapshots() { return snaps; }
    }

    @BeforeEach
    void setup() {
        bus = new InMemoryEventBus();

        // Three independent single-channel rules:
        // R0: A:"*" , R1: B:"*" , R2: C:"*"
        // => any incoming event on any channel triggers one fire.
        List<Map<String, String>> rules = List.of(
                Map.of(A, "*"),
                Map.of(B, "*"),
                Map.of(C, "*")
        );

        reactor = new CaptureActor(bus, "int",
                rules, List.of(A, B, C), List.of("OUT"));
    }

    @Test
    void allDatapointsUsedInCorrectOrder_randomized() {

        // Make the test long enough to actually verify temporal behavior
        final int N = 1000;  // or 1000 if you want more stress

        // Use same channels as before
        List<String> channels = List.of(A, B, C);

        record Step(String ch, int val, ZonedDateTime ts) {}

        List<Step> steps = new ArrayList<>(N);
        Random rnd = new Random();

        // Use a fixed past base so timestamps are second-precise, monotonically
        // increasing, and never hit the LIFO logical-time gate.
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        for (int i = 0; i < N; i++) {
            String ch = channels.get(rnd.nextInt(channels.size()));
            int val = rnd.nextInt(1000);
            ZonedDateTime ts = base.plusSeconds(i);

            steps.add(new Step(ch, val, ts));
            bus.publish(ch, dp(val, ts));
        }

        // --- Now verify snapshots ---
        List<Map<String, Object>> snaps = reactor.snapshots();

        // Each publish fires exactly once (because rules = "*"), so sizes must match
        assertEquals(steps.size(), snaps.size(),
                "Actor should fire once per incoming datapoint");

        // For value-tracking across channels; null = channel has not yet received data
        Map<String, Object> lastSeenValue = new HashMap<>();
        for (String ch : channels) lastSeenValue.put(ch, null);

        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            Map<String, Object> snap = snaps.get(i);

            // Updated channel must reflect the new value
            assertEquals(s.val(), snap.get(s.ch()),
                    "Snapshot should reflect the new value for channel %s".formatted(s.ch()));

            // Non-updated channels must reflect whatever was last seen (null if never received data)
            for (String ch : channels) {
                Object expected = ch.equals(s.ch()) ? s.val() : lastSeenValue.get(ch);
                assertEquals(expected, snap.get(ch),
                        "Snapshot should reflect correct value for channel %s at step %d".formatted(ch, i));
            }

            lastSeenValue.put(s.ch(), s.val());
        }
    }

    @Test
    void combinedRuleRequiresMultipleChannels_thenFireOnce() {
        // Create a new actor with rule requiring A and B together
        List<Map<String, String>> rules = List.of(
                Map.of(A, "*", B, "*")
        );
        reactor = new CaptureActor(bus, "int2", rules, List.of(A, B, C), List.of("OUT"));

        ZonedDateTime t = ZonedDateTime.now(ZoneOffset.UTC);
        bus.publish(A, dp(100, t.plusSeconds(1)));
        assertTrue(reactor.snapshots().isEmpty(), "Not enough to satisfy A&B");

        bus.publish(B, dp(200, t.plusSeconds(2)));
        assertEquals(1, reactor.snapshots().size(),
                "Now both A and B updated => one fire");

        // Next message on C should independently cause a fire due to R2 not present here -> it should NOT fire
        // (because only rule requires A & B; C alone should NOT fire).
        bus.publish(C, dp(300, t.plusSeconds(3)));
        assertEquals(1, reactor.snapshots().size(), "C alone should not satisfy A&B rule");
    }
}