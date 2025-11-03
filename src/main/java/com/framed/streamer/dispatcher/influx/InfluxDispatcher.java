package com.framed.streamer.dispatcher.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.framed.core.EventBus;
import com.framed.streamer.dispatcher.Dispatcher;
import com.framed.streamer.model.DataPoint;
import com.framed.streamer.model.TimeSeries;

import org.json.JSONArray;


public class InfluxDispatcher extends Dispatcher {
  private final String org;
  private final String bucket;
  WriteApi writeApi;

  public InfluxDispatcher(EventBus eventBus, JSONArray devices, String url, String token, String org, String bucket) {
    super(eventBus, devices);
    this.org = org;
    this.bucket = bucket;
    try {
      InfluxDBClient client = InfluxDBClientFactory.create(url, token.toCharArray());
      writeApi = client.makeWriteApi();
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
      System.err.printf("Invalid value for data point %s\n", dataPoint.channelID());
    }
    System.out.println(dataPoint.timestamp() + dataPoint.className());
    point.addTag("channelID", dataPoint.channelID());
    writeApi.writePoint(bucket, org, point);
  }

  @Override
  public void pushBatch(TimeSeries timeSeries) {
    for (DataPoint<?> dp : timeSeries.dataPoints()) {
      push(dp);
    }
  }

  @Override
  public void stop() {

  }
}
