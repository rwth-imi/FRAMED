package com.framed.cdss.casestudy;

import com.framed.cdss.Actor;
import com.framed.cdss.FiringRule;
import com.framed.core.EventBus;

import java.util.List;
import java.util.Map;

import static com.framed.cdss.utils.CDSSUtils.publishResult;

public class DislocationClassifier extends Actor {

    private final String etCO2LimitChannel;
    private final String spo2TrendChannel;
    private final String sfLimitChannel;

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
     * @param spo2TrendChannel  channel address for the SpO2 trend analysis
     * @param etCO2LimitChannel channel address for the etCO2 limit analysis
     * @param sfLimitChannel    channel address for the sf Limit analysis
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if a rule is empty or references a channel not present in {@code inputChannels}, or contains an invalid token
     */
    protected DislocationClassifier(
            EventBus eventBus,
            String id,
            String spo2TrendChannel,
            String etCO2LimitChannel,
            String sfLimitChannel,
            String outputChannel
            ) {
        super(
            eventBus,
            id,
            List.of(
                Map.of(spo2TrendChannel, "*"),
                Map.of(etCO2LimitChannel,"*"),
                Map.of(sfLimitChannel, "*")
            ),
            List.of(
                    etCO2LimitChannel,
                    spo2TrendChannel,
                    sfLimitChannel
            ),
            List.of(
                    outputChannel
            )
        );
        this.etCO2LimitChannel = etCO2LimitChannel;
        this.spo2TrendChannel = spo2TrendChannel;
        this.sfLimitChannel = sfLimitChannel;
    }

    @Override
    public void fireFunction(Map<String, Object> latestSnapshot) {
        int warnValue = 0;
        int etCO2State = (int) latestSnapshot.get(etCO2LimitChannel);
        int spo2State = (int) latestSnapshot.get(spo2TrendChannel);
        int sfState = (int) latestSnapshot.get(sfLimitChannel);
        if (spo2State == 1 && sfState >= 1 ) {
           if (etCO2State == 0){
               warnValue = 1;
           } else if (etCO2State == 2) {
               warnValue = 2;
           } else if (etCO2State == 3) {
               warnValue = 3;
           }
        }
        publishResult(eventBus, formatter, warnValue, id, outputChannels);
    }


}
