package com.framed.interop.gate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmissionGateTest {

  @Test
  void passthroughAlwaysAllows() {
    EmissionGate gate = EmissionGate.passthrough();
    assertTrue(gate.allow("c", 1, 0));
    assertTrue(gate.allow("c", 1, 0));
    assertTrue(gate.allow("c", 2, 0));
  }

  @Test
  void minIntervalThrottlesPerChannel() {
    EmissionGate gate = new EmissionGate(false, 1000);
    assertTrue(gate.allow("c", 1, 0));
    assertFalse(gate.allow("c", 2, 500), "within interval");
    assertTrue(gate.allow("c", 3, 1000), "interval elapsed");
    // a different channel has its own budget
    assertTrue(gate.allow("other", 9, 500));
  }

  @Test
  void onChangeSuppressesRepeats() {
    EmissionGate gate = new EmissionGate(true, 0);
    assertTrue(gate.allow("c", 5, 0));
    assertFalse(gate.allow("c", 5, 10), "unchanged value");
    assertTrue(gate.allow("c", 6, 20), "changed value");
  }

  @Test
  void bothFiltersMustPass() {
    EmissionGate gate = new EmissionGate(true, 1000);
    assertTrue(gate.allow("c", 5, 0));
    assertFalse(gate.allow("c", 6, 100), "blocked by interval even though changed");
    assertFalse(gate.allow("c", 5, 2000), "interval ok but value unchanged");
    assertTrue(gate.allow("c", 7, 3000), "interval ok and value changed");
  }

  @Test
  void allowsIsSideEffectFree() {
    EmissionGate gate = new EmissionGate(true, 1000);
    // Repeated checks without a commit must keep allowing: a failed send stays retryable.
    assertTrue(gate.allows("c", 5, 0));
    assertTrue(gate.allows("c", 5, 10), "no commit happened, so the retry must still pass");
    gate.commit("c", 5, 10);
    assertFalse(gate.allows("c", 5, 500), "committed emission now throttles");
    assertFalse(gate.allows("c", 6, 500), "interval applies after commit");
    assertTrue(gate.allows("c", 6, 1500), "interval elapsed and value changed");
  }

  @Test
  void keysAreIndependent() {
    // Callers key per device+channel; same channel on two devices must not share a slot.
    EmissionGate gate = new EmissionGate(true, 1000);
    assertTrue(gate.allow("devA.RR", 12, 0));
    assertTrue(gate.allow("devB.RR", 12, 0), "other device: own interval and own last value");
  }
}
