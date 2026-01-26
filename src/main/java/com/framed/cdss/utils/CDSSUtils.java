package com.framed.cdss.utils;

import org.json.JSONArray;
import org.json.JSONObject;

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

}
