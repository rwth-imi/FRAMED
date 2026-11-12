// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.arn;

import java.time.Instant;
import java.util.List;

/**
 * Timing of one message handled by a {@link Reactor}: either an evaluation that satisfied at
 * least one firing rule ({@link Outcome#FIRED}), or a message rejected by the per-channel
 * logical-time gate ({@link Outcome#DISCARDED}). Accepted messages that satisfy no rule get no
 * record; they are only counted ({@link FiringRecorder#countNoRule(String)}).
 *
 * <p>All instants come from {@link com.framed.core.utils.MonotonicClock}, except the timestamps
 * of the logical tags ({@code triggerTag}, {@code outputTag}, {@code gateTag} and
 * {@link ConsumedInput#tag()}). Those are carried by the messages, and a replay stamps them from
 * the same clock. The derived durations are left to the analysis:</p>
 * <ul>
 *   <li>trigger latency = {@code reactEnd − triggerTag.t}</li>
 *   <li>transport and queueing = {@code triggerArrival − triggerTag.t}</li>
 *   <li>scheduling and lock wait = {@code decision − triggerArrival}</li>
 *   <li>execution = {@code reactEnd − reactStart}</li>
 *   <li>rule wait of a consumed input = {@code decision − arrival}</li>
 *   <li>end-to-end from the newest source sample = {@code reactEnd − outputTag.t}</li>
 * </ul>
 *
 * @param reactorId      id of the evaluating reactor
 * @param evaluation     per-reactor counter of handled messages (1-based; counts accepted and
 *                       discarded messages)
 * @param outcome        {@link Outcome#FIRED} or {@link Outcome#DISCARDED}
 * @param atomic         whether the reactor runs its reaction inside the evaluation lock
 * @param triggerChannel the input channel whose message started this evaluation
 * @param triggerTag     tag of that message, {@code null} if it had no timestamp
 * @param triggerArrival instant the message entered the reactor's handler
 * @param outputTag      tag of the firing's outputs; {@code null} if discarded
 * @param gateTag        last tag the reactor had accepted on the trigger channel before this
 *                       message; acceptance requires {@code triggerTag > gateTag}
 * @param decision       instant the firing decision was taken, inside the evaluation lock;
 *                       {@code null} if discarded
 * @param reactStart     instant {@link Reactor#reactionFunction} was entered; {@code null} if
 *                       discarded
 * @param reactEnd       instant {@link Reactor#reactionFunction} returned; {@code null} if
 *                       discarded
 * @param consumed       inputs with new data in a satisfied rule, consumed by this firing;
 *                       empty if discarded
 */
public record FiringRecord(
        String reactorId,
        long evaluation,
        Outcome outcome,
        boolean atomic,
        String triggerChannel,
        Tag triggerTag,
        Instant triggerArrival,
        Tag outputTag,
        Tag gateTag,
        Instant decision,
        Instant reactStart,
        Instant reactEnd,
        List<ConsumedInput> consumed) {

  /** Result of handling one input message. */
  public enum Outcome {
    /** The message was accepted, at least one rule was satisfied, and the reaction ran. */
    FIRED,
    /**
     * The message's tag was not strictly greater than the last tag accepted on its channel (a
     * duplicate or an older message). It changed no state: no held value, sequence counter or
     * rule was updated.
     */
    DISCARDED
  }

  /**
   * One input of a satisfied rule that carried new data.
   *
   * @param channel input channel
   * @param seq     the channel's accepted-message count at evaluation time
   * @param tag     tag of the channel's latest accepted message
   * @param arrival instant that message entered the reactor's handler
   */
  public record ConsumedInput(String channel, long seq, Tag tag, Instant arrival) {
  }

  /**
   * Compact canonical constructor; copies {@code consumed} defensively.
   */
  public FiringRecord {
    consumed = List.copyOf(consumed);
  }
}
