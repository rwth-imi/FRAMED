package com.framed.cdss.actors;

import com.framed.cdss.Actor;
import com.framed.core.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.framed.cdss.utils.CDSSUtils.publishResult;
import static com.framed.cdss.utils.InterpreterUtils.getInterpreterInputChannelsList;
import static com.framed.cdss.utils.InterpreterUtils.getInterpreterInputFiringRules;

public class InterpretationActor extends Actor {
    private final String rrMismatchChannel;
    private final String dislocationChannel;
    private final String spo2LimitChannel;
    private final String spo2TrendChannel;
    private final String etCO2LimitChannel;
    private final String hdArythChannel;
    private final String piLimitChannel;
    private final String hrLimitChannel;
    private final String sfLimitChannel;

    public InterpretationActor(EventBus eventBus,
                                  String id,
                                  String rrMismatchChannel,
                                  String dislocationChannel,
                                  String spo2LimitChannel,
                                  String spo2TrendChannel,
                                  String etCO2LimitChannel,
                                  String hdArythChannel,
                                  String piLimitChannel,
                                  String hrLimitChannel,
                                  String sfLimitChannel) {

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
                new ArrayList<>());

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

    @Override
    public void fireFunction(Map<String, Object> latestSnapshot) {
        if (interpretEtCO2Limit(latestSnapshot.get(etCO2LimitChannel))){
            interpretRRMismatch(latestSnapshot.get(rrMismatchChannel));
        }
        if (interpretPiQuality(latestSnapshot.get(piLimitChannel))){
            interpretDislocation(latestSnapshot.get(dislocationChannel));
            interpretSpO2Limit(latestSnapshot.get(spo2LimitChannel));
            interpretSpO2Trend(latestSnapshot.get(spo2TrendChannel));
            interpretHRLimit(latestSnapshot.get(hrLimitChannel));
            interpretHDAryth(latestSnapshot.get(hdArythChannel));
            interpretSFLimit(latestSnapshot.get(sfLimitChannel));
        }
    }

    private Boolean interpretEtCO2Limit(Object value) {
        List<String> etCO2LimitWarningChannel = List.of("etCO2-Limit-Warning");
        if (value instanceof Integer intValue){
            switch (intValue) {
                case 0 -> {
                    publishResult(eventBus, formatter, "CHECK PATIENT: no end tidal CO2 measured!", id, etCO2LimitWarningChannel);
                    return false;
                }
                case 1 -> publishResult(eventBus, formatter, "CHECK PATIENT: end tidal CO2 severely low!", id, etCO2LimitWarningChannel);
                case 2 -> publishResult(eventBus, formatter, "CHECK PATIENT: end tidal CO2 moderately low!", id, etCO2LimitWarningChannel);
                case 3 -> publishResult(eventBus, formatter, "CHECK PATIENT: end tidal CO2 high!", id, etCO2LimitWarningChannel);
                default -> {
                    // no warning
                }
            }
        }
        return true;
    }


    private void interpretSFLimit(Object value) {
        List<String> sfLimitWarningChannel = List.of("SF-Limit-Warning");
        if (value instanceof Integer intValue) {
            switch (intValue) {
                case 0 -> publishResult(eventBus, formatter, "CHECK PATIENT: S/F indicates severe ARDS condition!", id, sfLimitWarningChannel);
                case 1 -> publishResult(eventBus, formatter, "CHECK PATIENT: S/F indicates moderate ARDS condition!", id, sfLimitWarningChannel);
                case 2 -> publishResult(eventBus, formatter, "CHECK PATIENT: S/F indicates mild ARDS condition!", id, sfLimitWarningChannel);
                default -> {
                    // no warning
                }
            }
        }
    }

    private void interpretHRLimit(Object value) {
        List<String> hrWarningChannel = List.of("HR-Limit-Warning");
        if (value instanceof Integer intValue) {
            switch (intValue){
                case 1 -> publishResult(eventBus, formatter, "CHECK PATIENT: possible asystole!", id, hrWarningChannel);
                case 2 -> publishResult(eventBus, formatter, "CHECK PATIENT: Hear rate too high!", id, hrWarningChannel);
                default -> {
                    // no warning
                }
            }
        }
    }

    private void interpretHDAryth(Object value) {
        List<String> hdArythWarningChannel = List.of("HD-Aryth-Warning");
        if (value instanceof Integer intValue) {
            switch (intValue){
                case 1 ->publishResult(eventBus, formatter, "CHECK PATIENT: possible asystole!", id, hdArythWarningChannel);
                case 2 ->publishResult(eventBus, formatter, "CHECK PATIENT: possible ventricular fibrillation!", id, hdArythWarningChannel);
                default -> {
                    // no warning
                }
            }

        }
    }

    private void interpretSpO2Trend(Object value) {
        if (value instanceof Integer intValue && intValue == 1) {
            publishResult(eventBus, formatter, "CHECK PATIENT: SpO2 decreasing!", id, List.of("SpO2-Trend-Warning"));
        }
    }

    private void interpretSpO2Limit(Object value) {
        if (value instanceof Integer intValue && intValue == 0) {
            publishResult(eventBus, formatter, "CHECK PATIENT: SpO2 critically low!", id, List.of("SpO2-Limit-Warning"));
        }
    }

    private Boolean interpretPiQuality(Object value) {
        List<String> piQualityChannel = List.of("PI-Quality-Warning");
        boolean quality = true;
        if (value instanceof Integer intValue) {
            if (intValue == 0) {
                publishResult(eventBus, formatter, "CHECK PULSEOXIMETER: PI too low!", id, piQualityChannel);
                quality = false;
            } else if (intValue == 2) {
                publishResult(eventBus, formatter, "CHECK PULSEOXIMETER: PI too high!", id, piQualityChannel);
                quality = false;
            }
        }
        return quality;
    }
    private void interpretDislocation(Object value) {
        if (value instanceof Integer intValue){
            List<String> dislocationWarningChannel = List.of("Dislocation-Warning");
            switch (intValue) {
                case 1 -> publishResult(eventBus, formatter, "CHECK INTUBATION: possibly esophagus intubated!", id, dislocationWarningChannel);
                case 2 -> publishResult(eventBus, formatter, "CHECK INTUBATION: possibly only one lung intubated!", id, dislocationWarningChannel);
                case 3 -> publishResult(eventBus, formatter, "CHECK SpO2: intubation correct but critical SpO2 / FiO2 ratio!", id, dislocationWarningChannel);
                case 0 -> {
                    //no warning
                }
                default -> throw new IllegalStateException("Unexpected value: %s".formatted(value));
            }
        }
    }

    private void interpretRRMismatch(Object value) {
        if (value instanceof Integer intValue && intValue == 1){
            publishResult(eventBus, formatter, "Mismatch between RR Settings and Measurement!", id, List.of("RRMismatchWarning"));
        }
    }
}
