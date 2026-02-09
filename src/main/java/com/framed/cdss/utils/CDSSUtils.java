package com.framed.cdss.utils;

import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CDSSUtils {

    private CDSSUtils() {
        throw new IllegalStateException("Utility class");
    }
    public static List<Map<String, String>> parseFiringRulesJson(JSONArray firingRulesJson){
        List<Map<String, String>> firingRules = new ArrayList<>();
        for (Object firingRuleObject: firingRulesJson){
            JSONObject firingRuleJson = (JSONObject) firingRuleObject;
            Map<String, Object> map = firingRuleJson.toMap();
            Map<String, String> firingRule = new HashMap<>();

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                firingRule.put(entry.getKey(), String.valueOf(entry.getValue()));
            }

            firingRules.add(firingRule);
        }
        return firingRules;
    }

    public static Map<String, List<Float>> parseLimitsJson(JSONObject limitsJson) {
        Map<String, List<Float>> result = new HashMap<>();

        for (String channel : limitsJson.keySet()) {
            JSONArray arr = limitsJson.getJSONArray(channel);
            List<Float> bounds = new java.util.ArrayList<>();

            for (Object o : arr) {
                Number n = (Number) o;
                bounds.add(n.floatValue());
            }

            bounds.sort(Float::compare);
            result.put(channel, List.copyOf(bounds));
        }

        return result;
    }


    public static List<String> parseChannelListJson(JSONArray channelListJson) {
        List<String> result = new ArrayList<>();
        for (Object o : channelListJson) {
            result.add(String.valueOf(o));  // safe conversion
        }
        return result;
    }


    public static Map<String, Integer> parsePerChannelIntMap(
            JSONObject json,
            int minValue) {

        Map<String, Integer> result = new HashMap<>();

        for (String channel : json.keySet()) {

            Object raw = json.get(channel);
            if (!(raw instanceof Number n)) {
                throw new IllegalArgumentException(
                        "Value for channel '%s' must be numeric, but was: %s".formatted(channel, raw.getClass().getSimpleName()));
            }

            int value = n.intValue();
            if (value < minValue) {
                throw new IllegalArgumentException(
                        "Value for channel '%s' must be >= %d, but was: %d".formatted(channel, minValue, value));
            }

            result.put(channel, value);
        }

        return result;
    }

    public static void publishResult(EventBus eventBus, DateTimeFormatter formatter, Object warnValue, String id, List<String> outputChannels) {
        JSONObject result = new JSONObject();
        result.put("timestamp", LocalDateTime.now().format(formatter));
        result.put("className", id);
        result.put("value", warnValue);
        for (String out : outputChannels) {
            result.put("channelID", out);
            eventBus.publish("CDSS.addresses", out);
            eventBus.publish(out, result);
        }
    }

}
