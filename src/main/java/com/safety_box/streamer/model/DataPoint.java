package com.safety_box.streamer.model;

import java.time.Instant;

public record DataPoint<T>(Instant timestamp, T value, String physioID, String deviceID, String className) {}
