package com.framed.utils;

import com.framed.core.utils.Timer;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Helpers for building input JSON datapoints. */
public final class JsonFixtures {

    private JsonFixtures() {}

    /**
     * Build a JSON datapoint: {"value": value, "timestamp": tsString}
     * The timestamp is truncated to seconds to avoid fractional seconds.
     */
    public static JSONObject dp(Object value, LocalDateTime ts) {
        JSONObject o = new JSONObject();
        o.put("value", value);
        o.put("timestamp", ts.truncatedTo(ChronoUnit.SECONDS).format(Timer.formatter));
        return o;
    }
}