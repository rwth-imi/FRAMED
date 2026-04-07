package com.framed.cdss.reactors;

import com.framed.cdss.Reactor;
import com.framed.core.EventBus;
import org.json.JSONArray;

import java.util.List;
import java.util.Map;

import static com.framed.cdss.utils.CDSSUtils.parseChannelListJson;
import static com.framed.cdss.utils.CDSSUtils.publishResult;

public class RRMismatchClassificationReactor extends Reactor {

    private final int varLimit;
    private final String rrEstimationChannel;
    private final String rrSettingsChannel;

    public RRMismatchClassificationReactor(EventBus eventBus, String id, String rrEstimationChannel, String rrSettingsChannel, JSONArray outputChannels, int varLimit){
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
        double rrEstimation = ((Number) latestSnapshot.get(rrEstimationChannel)).doubleValue();
        double rrSetting = ((Number) latestSnapshot.get(rrSettingsChannel)).doubleValue();
        int warnValue = 0;
        if (Math.abs(rrEstimation - rrSetting) > varLimit){
            warnValue = 1;
        }
        publishResult(eventBus, warnValue, id, outputChannels, lastLogicalFireTs);
    }
}
