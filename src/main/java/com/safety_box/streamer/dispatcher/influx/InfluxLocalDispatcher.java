package com.safety_box.streamer.dispatcher.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.safety_box.core.EventBusInterface;
import com.safety_box.streamer.dispatcher.LocalDispatcher;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;

import org.json.JSONArray;

import java.util.concurrent.TimeUnit;


public class InfluxLocalDispatcher extends LocalDispatcher {
  private final String org;
  private final String bucket;
  WriteApiBlocking writeApi;

  public InfluxLocalDispatcher(EventBusInterface eventBus, JSONArray devices, String url, String token, String org, String bucket) {
    super(eventBus, devices);
    this.org = org;
    this.bucket = bucket;
    try {
      InfluxDBClient client = InfluxDBClientFactory.create(url, token.toCharArray());
      writeApi = client.getWriteApiBlocking();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void push(DataPoint<?> dataPoint) {
    Point point = Point
      .measurement(dataPoint.deviceID())
      .time(dataPoint.timestamp(), WritePrecision.NS);

    Object value = dataPoint.value();

    if (value instanceof String) {
      point.addField(dataPoint.className(), (String) value);
    } else if (value instanceof Number) {
      point.addField(dataPoint.className(), ((Number) value).floatValue());
    } else if (value instanceof Boolean) {
      point.addField(dataPoint.className(), (Boolean) value);
    } else {
      System.err.printf("Invalid value for data point %s\n", dataPoint.physioID());
    }
    System.out.println(dataPoint.timestamp() + dataPoint.className());
    point.addTag("physioID", dataPoint.physioID());
    writeApi.writePoint(bucket, org, point);
  }

  @Override
  public void pushBatch(TimeSeries timeSeries) {
    for (DataPoint<?> dp: timeSeries.dataPoints()) {
      push(dp);
    }
  }

  @Override
  public void stop() {

  }
}
