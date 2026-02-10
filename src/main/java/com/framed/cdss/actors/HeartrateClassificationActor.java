package com.framed.cdss.actors;

import com.framed.cdss.Actor;
import com.framed.cdss.FiringRule;
import com.framed.core.EventBus;

import java.util.List;
import java.util.Map;

import static com.framed.cdss.utils.CDSSUtils.publishResult;

public class HeartrateClassificationActor extends Actor {
    private final String etCO2TrendChannel;
    private final String hrLimitChannel;

    /**
     * Constructs an {@code Actor} that subscribes to the given {@code inputChannels}, evaluates the provided
     * {@code firingRules}, and optionally exposes {@code outputChannels}.
     *
     * <p>For each input channel, this actor:
     * <ol>
     *   <li>Initializes its sequence counter and latest value holder;</li>
     *   <li>Registers a message handler on the {@link EventBus} that updates state and evaluates rules.</li>
     * </ol>
     *
     * <p>During construction, rules are compiled to internal {@link FiringRule}s and validated for channel existence.
     *
     * @param eventBus       the event bus used to subscribe to input channels and receive messages; must not be {@code null}
     * @param id             the identifier of the specified Actor. Commonly set in the config.
     * @param etCO2TrendChannel the channel on which the etCO2 trend warning is published
     * @param hrLimitChannel    the channel on which the Heartrate limit warning is published
     * @param outputChannel     the channel on which to publish the arrhythmia warning
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if a rule is empty or references a channel not present in {@code inputChannels}, or contains an invalid token
     */
    public HeartrateClassificationActor(
            EventBus eventBus,
            String id,
            String etCO2TrendChannel,
            String hrLimitChannel,
            String outputChannel
            ) {
        super(
                eventBus,
                id,
                List.of(
                        Map.of(etCO2TrendChannel, "*"),
                        Map.of(hrLimitChannel, "*")
                ),
                List.of(
                        etCO2TrendChannel,
                        hrLimitChannel
                ),
                List.of(
                        outputChannel
                )
        );
        this.hrLimitChannel = hrLimitChannel;
        this.etCO2TrendChannel = etCO2TrendChannel;
    }


    @Override
    public void fireFunction(Map<String, Object> latestSnapshot) {
        int etCO2Status = (int) latestSnapshot.get(etCO2TrendChannel);
        int hrStatus  = (int) latestSnapshot.get(hrLimitChannel);
        int warnValue = 0;
        if (etCO2Status == 1) {
            if (hrStatus == 0) {
                warnValue = 1;
            } else if (hrStatus == 2) {
                warnValue = 2;
            }
        }
        publishResult(eventBus, formatter, warnValue, id, outputChannels);
    }
}
