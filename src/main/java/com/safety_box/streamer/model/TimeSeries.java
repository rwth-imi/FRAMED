package com.safety_box.streamer.model;

import java.util.List;

public record TimeSeries(List<DataPoint<?>> dataPoints) {}
