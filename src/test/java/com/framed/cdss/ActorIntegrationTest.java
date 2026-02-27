package com.framed.cdss;

import com.framed.utils.InMemoryEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
public class ActorIntegrationTest {

    private InMemoryEventBus bus;
    private CaptureActor actor;

    private static final String A = "A";
    private static final String B = "B";
    private static final String C = "C";

    static class CaptureActor extends Actor {
        private final List<Map<String, Object>> snaps = new ArrayList<>();
        CaptureActor(InMemoryEventBus bus, String id, List<Map<String,String>> rules,
                     List<String> inputs, List<String> outputs) {
            super(bus, id, rules, inputs, outputs);
        }
        @Override public void fireFunction(Map<String, Object> latestSnapshot) { snaps.add(latestSnapshot); }
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

        actor = new CaptureActor(bus, "int",
                rules, List.of(A, B, C), List.of("OUT"));
    }

    @Test
    void allDatapointsUsedInCorrectOrder_randomized() throws Exception {

        // Make the test long enough to actually verify temporal behavior
        final int N = 1000;  // or 1000 if you want more stress

        // Use same channels as before
        List<String> channels = List.of(A, B, C);

        record Step(String ch, int val, LocalDateTime ts) {}

        List<Step> steps = new ArrayList<>(N);
        Random rnd = new Random();

        // --- Generate and publish approx. 100 per second ---
        for (int i = 0; i < N; i++) {

            // Pick a random channel
            String ch = channels.get(rnd.nextInt(channels.size()));

            // Random value (for test clarity we keep it bounded)
            int val = rnd.nextInt(1000);

            // Real timestamp
            LocalDateTime ts = LocalDateTime.now();

            Step s = new Step(ch, val, ts);
            steps.add(s);

            // Publish the datapoint
            bus.publish(ch, dp(val, ts));

            // Sleep for roughly 100 per second (10ms)
            Thread.sleep(10);
        }

        // --- Now verify snapshots ---
        List<Map<String, Object>> snaps = actor.snapshots();

        // Each publish fires exactly once (because rules = "*"), so sizes must match
        assertEquals(steps.size(), snaps.size(),
                "Actor should fire once per incoming datapoint");

        // For value‑tracking across channels
        Map<String, Integer> lastSeenValue = new HashMap<>();
        for (String ch : channels) lastSeenValue.put(ch, 0);

        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            Map<String, Object> snap = snaps.get(i);

            // Updated channel must reflect the new value
            assertEquals(s.val(), snap.get(s.ch()),
                    "Snapshot should reflect the new value for channel %s".formatted(s.ch()));

            // Non-updated channels: must reflect whatever lastSeenValue recorded
            for (String ch : channels) {
                int expected = lastSeenValue.get(ch);
                if (ch.equals(s.ch())) {
                    expected = s.val(); // update happens here
                }
                assertEquals(expected, snap.get(ch),
                        "Snapshot should reflect correct value for channel %s at step %d".formatted(ch, i));
            }

            // Update lastSeen for next iteration
            lastSeenValue.put(s.ch(), s.val());
        }
    }

    @Test
    void combinedRuleRequiresMultipleChannels_thenFireOnce() {
        // Create a new actor with rule requiring A and B together
        List<Map<String, String>> rules = List.of(
                Map.of(A, "*", B, "*")
        );
        actor = new CaptureActor(bus, "int2", rules, List.of(A, B, C), List.of("OUT"));

        LocalDateTime t = LocalDateTime.now();
        bus.publish(A, dp(100, t.plusSeconds(1)));
        assertTrue(actor.snapshots().isEmpty(), "Not enough to satisfy A&B");

        bus.publish(B, dp(200, t.plusSeconds(2)));
        assertEquals(1, actor.snapshots().size(),
                "Now both A and B updated => one fire");

        // Next message on C should independently cause a fire due to R2 not present here -> it should NOT fire
        // (because only rule requires A & B; C alone should NOT fire).
        bus.publish(C, dp(300, t.plusSeconds(3)));
        assertEquals(1, actor.snapshots().size(), "C alone should not satisfy A&B rule");
    }
}