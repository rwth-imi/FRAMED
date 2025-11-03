package com.framed.streamer.model;

import java.util.List;

public record TimeSeries(List<DataPoint<?>> dataPoints) {}
