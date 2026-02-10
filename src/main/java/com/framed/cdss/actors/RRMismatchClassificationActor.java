package com.framed.cdss.actors;

import com.framed.cdss.Actor;
import com.framed.core.EventBus;
import org.json.JSONArray;

import java.util.List;
import java.util.Map;

import static com.framed.cdss.utils.CDSSUtils.parseChannelListJson;
import static com.framed.cdss.utils.CDSSUtils.publishResult;

public class RRMismatchClassificationActor extends Actor {

    private final int varLimit;
    private final String rrEstimationChannel;
    private final String rrSettingsChannel;

    public RRMismatchClassificationActor(EventBus eventBus, String id, String rrEstimationChannel, String rrSettingsChannel, JSONArray outputChannels, int varLimit){
        super(
                eventBus,
                id,
                List.of(
                        Map.of(
                                rrEstimationChannel, "*"
                        ),
                        Map.of(
                                rrSettingsChannel, "*"
                        )
                ),
                List.of(rrEstimationChannel, rrSettingsChannel),
                parseChannelListJson(outputChannels)
        );
        this.varLimit = varLimit;
        this.rrEstimationChannel = rrEstimationChannel;
        this.rrSettingsChannel = rrSettingsChannel;

    }
    @Override
    public void fireFunction(Map<String, Object> latestSnapshot) {
        double rrEstimation = (double) latestSnapshot.getOrDefault(rrEstimationChannel, 0);
        double rrSetting = (double) latestSnapshot.getOrDefault(rrSettingsChannel, 0 );
        int warnValue = 0;
        if (Math.abs(rrEstimation - rrSetting) > varLimit){
            warnValue = 1;
        }
        publishResult(eventBus, formatter, warnValue, id, outputChannels);
    }
}
