// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.cdss.reactors;

import com.framed.arn.Reactor;
import com.framed.core.EventBus;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.framed.arn.RuleUtils.publishResult;
import static com.framed.cdss.utils.InterpreterUtils.getInterpreterInputChannelsList;
import static com.framed.cdss.utils.InterpreterUtils.getInterpreterInputFiringRules;

/**
 * Terminal reactor of the alarm CDSS: turns the classifications of the upstream reactors into
 * human-readable warnings.
 *
 * <p>The reactor fires on a new value of any input channel. Each firing evaluates all inputs
 * hierarchically. The class indices follow the limit classification of Definition 7 with the
 * bounds of the study configuration:</p>
 * <ul>
 *   <li>etCO2 (0, 25, 35, 45) mmHg: 0 none measured, 1 severely low, 2 moderately low,
 *       3 normal, 4 high;</li>
 *   <li>SpO2 (87, 97, 98) %: 0 critically low, 1 and 2 in range, 3 above target;</li>
 *   <li>PI (1) %: 0 too low, 1 adequate;</li>
 *   <li>HR (0, 125) bpm: 0 asystole, 1 normal, 2 too high;</li>
 *   <li>S/F (148, 235, 315): 0 severe, 1 moderate, 2 mild ARDS, 3 none.</li>
 * </ul>
 *
 * <p>Two quality gates suppress the checks that depend on an implausible signal:</p>
 * <ul>
 *   <li><b>{@link Gate#ETCO2}</b>: no end-tidal CO2 measured (etCO2 class {@code 0}). The
 *       RR-mismatch check is skipped.</li>
 *   <li><b>{@link Gate#PI}</b>: perfusion index at or below 1 % (PI class {@code 0}). All
 *       pulse-oximetry-based checks are skipped: dislocation, SpO2 limit and trend, heart rate,
 *       hemodynamic arrhythmia and S/F ratio.</li>
 * </ul>
 *
 * <p>The S/F (ARDS) categories apply only when SpO2 &le; 97 % (SpO2 class &le; 1), as stated in
 * Definition A5. Otherwise no S/F warning is issued; this is a precondition of the
 * check, not a quality gate, and does not appear in the status.</p>
 *
 * <p>Warnings are published on their own channels (for example {@code "SpO2-Limit-Warning"}), as
 * before. In addition, <b>every firing</b> publishes exactly one record on
 * {@value #STATUS_CHANNEL}, including firings that raise no warning or whose checks were gated.
 * This makes each firing observable for completeness and latency analysis. The status value is
 * {@code WARNING} or {@code NO_WARNING}, followed by {@code ;SUPPRESSED:<gates>} when gates
 * applied. Examples: {@code NO_WARNING}, {@code WARNING;SUPPRESSED:PI},
 * {@code WARNING;SUPPRESSED:ETCO2+PI}. A gate's own message ("PI too low", "no end tidal CO2
 * measured") counts as a warning.</p>
 *
 * <p><b>Threading:</b> {@link #reactionFunction(Map)} is serialized by an internal lock, so a
 * non-atomic deployment never interleaves the warnings of two firings. The lock is released even
 * if a classification has an unexpected value.</p>
 */
public class InterpretationReactor extends Reactor {

  /** Output channel carrying one status record per firing. */
  public static final String STATUS_CHANNEL = "Interpretation-Status";

  /** Quality gates that suppress dependent checks. */
  public enum Gate {
    /** No end-tidal CO2 measured; suppresses the RR-mismatch check. */
    ETCO2,
    /** Implausible perfusion index; suppresses all pulse-oximetry-based checks. */
    PI
  }

  private final String rrMismatchChannel;
  private final String dislocationChannel;
  private final String spo2LimitChannel;
  private final String spo2TrendChannel;
  private final String etCO2LimitChannel;
  private final String hdArythChannel;
  private final String piLimitChannel;
  private final String hrLimitChannel;
  private final String sfLimitChannel;

  private final ReentrantLock fireLock = new ReentrantLock();

  /** Collects what one firing did; confined to the firing thread. */
  private static final class Outcome {
    private boolean warned;
    private final Set<Gate> gates = EnumSet.noneOf(Gate.class);

    private String status() {
      String base = warned ? "WARNING" : "NO_WARNING";
      if (gates.isEmpty()) return base;
      return base + ";SUPPRESSED:" + gates.stream().map(Enum::name).collect(Collectors.joining("+"));
    }
  }

  /**
   * Creates the interpretation reactor. Parameter names match the JSON configuration keys
   * resolved by {@code Factory}; the {@link EventBus} is injected automatically.
   *
   * @param eventBus           the bus to subscribe and publish on
   * @param id                 reactor id; used as {@code className} of all outputs
   * @param rrMismatchChannel  output of the RR-mismatch classifier
   * @param dislocationChannel output of the tube-dislocation classifier
   * @param spo2LimitChannel   output of the SpO2 limit classifier
   * @param spo2TrendChannel   output of the SpO2 trend classifier
   * @param etCO2LimitChannel  output of the etCO2 limit classifier
   * @param hdArythChannel     output of the hemodynamic rhythm classifier
   * @param piLimitChannel     output of the PI limit classifier
   * @param hrLimitChannel     output of the heart rate limit classifier
   * @param sfLimitChannel     output of the S/F limit classifier
   * @param atomic             whether reactions run inside the evaluation lock
   */
  public InterpretationReactor(EventBus eventBus,
                               String id,
                               String rrMismatchChannel,
                               String dislocationChannel,
                               String spo2LimitChannel,
                               String spo2TrendChannel,
                               String etCO2LimitChannel,
                               String hdArythChannel,
                               String piLimitChannel,
                               String hrLimitChannel,
                               String sfLimitChannel,
                               boolean atomic) {

    super(eventBus,
            id,
            getInterpreterInputFiringRules(
                    rrMismatchChannel,
                    dislocationChannel,
                    spo2LimitChannel,
                    spo2TrendChannel,
                    etCO2LimitChannel,
                    hdArythChannel,
                    piLimitChannel,
                    hrLimitChannel,
                    sfLimitChannel
            ),
            getInterpreterInputChannelsList(
                    rrMismatchChannel,
                    dislocationChannel,
                    spo2LimitChannel,
                    spo2TrendChannel,
                    etCO2LimitChannel,
                    hdArythChannel,
                    piLimitChannel,
                    hrLimitChannel,
                    sfLimitChannel),
            List.of(STATUS_CHANNEL),
            atomic);

    this.rrMismatchChannel = rrMismatchChannel;
    this.dislocationChannel = dislocationChannel;
    this.spo2LimitChannel = spo2LimitChannel;
    this.spo2TrendChannel = spo2TrendChannel;
    this.etCO2LimitChannel = etCO2LimitChannel;
    this.hdArythChannel = hdArythChannel;
    this.piLimitChannel = piLimitChannel;
    this.hrLimitChannel = hrLimitChannel;
    this.sfLimitChannel = sfLimitChannel;
  }

  /**
   * Evaluates all inputs, publishes the resulting warnings and one status record on
   * {@value #STATUS_CHANNEL}.
   *
   * @param latestSnapshot latest value per input channel
   * @throws IllegalStateException if the dislocation class has an unknown value; no status is
   *                               published for that firing
   */
  @Override
  public void reactionFunction(Map<String, Object> latestSnapshot) {
    fireLock.lock();
    try {
      Outcome out = new Outcome();
      if (interpretEtCO2Limit(latestSnapshot.get(etCO2LimitChannel), out)) {
        interpretRRMismatch(latestSnapshot.get(rrMismatchChannel), out);
      }
      if (interpretPiQuality(latestSnapshot.get(piLimitChannel), out)) {
        interpretDislocation(latestSnapshot.get(dislocationChannel), out);
        interpretSpO2Limit(latestSnapshot.get(spo2LimitChannel), out);
        interpretSpO2Trend(latestSnapshot.get(spo2TrendChannel), out);
        interpretHRLimit(latestSnapshot.get(hrLimitChannel), out);
        interpretHDAryth(latestSnapshot.get(hdArythChannel), out);
        interpretSFLimit(latestSnapshot.get(sfLimitChannel),
                latestSnapshot.get(spo2LimitChannel), out);
      }
      publishResult(eventBus, out.status(), id, List.of(STATUS_CHANNEL), lastLogicalFireTs);
    } finally {
      fireLock.unlock();
    }
  }

  private void warn(Outcome out, String channel, String message) {
    out.warned = true;
    publishResult(eventBus, message, id, List.of(channel), lastLogicalFireTs);
  }

  /** Returns {@code false} if the etCO2 gate applies. */
  private boolean interpretEtCO2Limit(Object value, Outcome out) {
    String channel = "etCO2-Limit-Warning";
    if (value instanceof Integer intValue) {
      switch (intValue) {
        case 0 -> {
          warn(out, channel, "CHECK PATIENT: no end tidal CO2 measured!");
          out.gates.add(Gate.ETCO2);
          return false;
        }
        case 1 -> warn(out, channel, "CHECK PATIENT: end tidal CO2 severely low!");
        case 2 -> warn(out, channel, "CHECK PATIENT: end tidal CO2 moderately low!");
        case 4 -> warn(out, channel, "CHECK PATIENT: end tidal CO2 high!");
        default -> {
          // no warning
        }
      }
    }
    return true;
  }

  /** S/F categories apply only for SpO2 &le; 97 % (SpO2 class &le; 1, Definition A5). */
  private void interpretSFLimit(Object value, Object spo2Class, Outcome out) {
    String channel = "SF-Limit-Warning";
    boolean spo2AtMost97 = spo2Class instanceof Integer spo2 && spo2 <= 1;
    if (spo2AtMost97 && value instanceof Integer intValue) {
      switch (intValue) {
        case 0 -> warn(out, channel, "CHECK PATIENT: S/F indicates severe ARDS condition!");
        case 1 -> warn(out, channel, "CHECK PATIENT: S/F indicates moderate ARDS condition!");
        case 2 -> warn(out, channel, "CHECK PATIENT: S/F indicates mild ARDS condition!");
        default -> {
          // no warning
        }
      }
    }
  }

  private void interpretHRLimit(Object value, Outcome out) {
    String channel = "HR-Limit-Warning";
    if (value instanceof Integer intValue) {
      switch (intValue) {
        case 0 -> warn(out, channel, "CHECK PATIENT: possible asystole!");
        case 2 -> warn(out, channel, "CHECK PATIENT: Hear rate too high!");
        default -> {
          // no warning
        }
      }
    }
  }

  private void interpretHDAryth(Object value, Outcome out) {
    String channel = "HD-Aryth-Warning";
    if (value instanceof Integer intValue) {
      switch (intValue) {
        case 1 -> warn(out, channel, "CHECK PATIENT: possible asystole!");
        case 2 -> warn(out, channel, "CHECK PATIENT: possible ventricular fibrillation!");
        default -> {
          // no warning
        }
      }
    }
  }

  private void interpretSpO2Trend(Object value, Outcome out) {
    if (value instanceof Integer intValue && intValue == 1) {
      warn(out, "SpO2-Trend-Warning", "CHECK PATIENT: SpO2 decreasing!");
    }
  }

  private void interpretSpO2Limit(Object value, Outcome out) {
    if (value instanceof Integer intValue && intValue == 0) {
      warn(out, "SpO2-Limit-Warning", "CHECK PATIENT: SpO2 critically low!");
    }
  }

  /** Returns {@code false} if the PI gate applies. */
  private boolean interpretPiQuality(Object value, Outcome out) {
    String channel = "PI-Quality-Warning";
    if (value instanceof Integer intValue) {
      switch (intValue) {
        case 0 -> {
          warn(out, channel, "CHECK PULSEOXIMETER: PI too low!");
          out.gates.add(Gate.PI);
          return false;
        }
        default -> {
          // no warning
        }
      }
    }
    return true;
  }

  private void interpretDislocation(Object value, Outcome out) {
    if (value instanceof Integer intValue) {
      String channel = "Dislocation-Warning";
      switch (intValue) {
        case 1 -> warn(out, channel, "CHECK INTUBATION: possibly esophagus intubated!");
        case 2 -> warn(out, channel, "CHECK INTUBATION: possibly only one lung intubated!");
        case 3 -> warn(out, channel, "CHECK SpO2: intubation correct but critical SpO2 / FiO2 ratio!");
        case 0 -> {
          // no warning
        }
        default -> throw new IllegalStateException("Unexpected value: %s".formatted(value));
      }
    }
  }

  private void interpretRRMismatch(Object value, Outcome out) {
    if (value instanceof Integer intValue && intValue == 1) {
      warn(out, "RRMismatchWarning", "Mismatch between RR Settings and Measurement!");
    }
  }
}
