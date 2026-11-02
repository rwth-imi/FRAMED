// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import java.time.Instant;
import java.util.List;

/**
 * Timing of one rule evaluation of a {@link Reactor} that satisfied at least one firing rule.
 *
 * <p>All instants come from {@link com.framed.core.utils.MonotonicClock}, except the logical
 * timestamps ({@code triggerTs}, {@code logicalTs}, {@code gateTs} and
 * {@link ConsumedInput#ts()}). Those are carried by the messages, and a replay stamps them from
 * the same clock. The derived durations are left to the analysis:</p>
 * <ul>
 *   <li>trigger latency = {@code reactEnd − triggerTs}</li>
 *   <li>transport and queueing = {@code triggerArrival − triggerTs}</li>
 *   <li>scheduling and lock wait = {@code decision − triggerArrival}</li>
 *   <li>execution = {@code reactEnd − reactStart}</li>
 *   <li>rule wait of a consumed input = {@code decision − arrival}</li>
 *   <li>end-to-end from the newest source sample = {@code reactEnd − logicalTs}</li>
 * </ul>
 *
 * @param reactorId      id of the evaluating reactor
 * @param evaluation     per-reactor evaluation counter (1-based, counts every evaluation)
 * @param outcome        {@link Outcome#FIRED} or {@link Outcome#GATED}
 * @param atomic         whether the reactor runs its reaction inside the evaluation lock
 * @param triggerChannel the input channel whose message started this evaluation
 * @param triggerTs      logical timestamp of that message, {@code null} if it had none
 * @param triggerArrival instant the message entered the reactor's handler
 * @param logicalTs      logical timestamp of the snapshot (the maximum over all input channels)
 * @param gateTs         logical time the reactor had already fired at when this evaluation
 *                       started; a firing requires {@code logicalTs > gateTs}
 * @param decision       instant the firing decision was taken, inside the evaluation lock
 * @param reactStart     instant {@link Reactor#reactionFunction} was entered; {@code null} if gated
 * @param reactEnd       instant {@link Reactor#reactionFunction} returned; {@code null} if gated
 * @param consumed       inputs with new data in a satisfied rule: consumed when fired, left
 *                       pending when gated
 */
public record FiringRecord(
        String reactorId,
        long evaluation,
        Outcome outcome,
        boolean atomic,
        String triggerChannel,
        Instant triggerTs,
        Instant triggerArrival,
        Instant logicalTs,
        Instant gateTs,
        Instant decision,
        Instant reactStart,
        Instant reactEnd,
        List<ConsumedInput> consumed) {

  /** Result of an evaluation in which at least one firing rule was satisfied. */
  public enum Outcome {
    /** The reaction ran. */
    FIRED,
    /**
     * A rule was satisfied but the logical-time gate rejected the snapshot, because its logical
     * timestamp was not newer than the last firing. The pending inputs are consumed by a later
     * firing.
     */
    GATED
  }

  /**
   * One input of a satisfied rule that carried new data.
   *
   * @param channel input channel
   * @param seq     the channel's absolute sequence number at evaluation time
   * @param ts      logical timestamp of the channel's latest message
   * @param arrival instant that message entered the reactor's handler
   */
  public record ConsumedInput(String channel, long seq, Instant ts, Instant arrival) {
  }

  /**
   * Compact canonical constructor; copies {@code consumed} defensively.
   */
  public FiringRecord {
    consumed = List.copyOf(consumed);
  }
}
