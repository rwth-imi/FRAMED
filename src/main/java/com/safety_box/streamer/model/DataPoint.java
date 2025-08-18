package com.safety_box.streamer.model;

public record DataPoint<T>(long timestamp, T value, String physioID, String deviceID) {}
