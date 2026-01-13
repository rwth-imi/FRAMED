package com.framed.streamer.dispatcher;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.framed.core.EventBus;
import com.framed.streamer.model.DataPoint;

import org.json.JSONArray;

import java.util.List;


public class InfluxDispatcher extends Dispatcher {
  private final String org;
  private final String bucket;
  private final WriteApi writeApi;
  private final InfluxDBClient client;

  public InfluxDispatcher(EventBus eventBus, JSONArray devices, String url, String token, String org, String bucket) {
    super(eventBus, devices);
    this.org = org;
    this.bucket = bucket;
    try {
      client = InfluxDBClientFactory.create(url, token.toCharArray());
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
  public void pushBatch(List<DataPoint<?>> batch) {
    for (DataPoint<?> dp : batch) {
      push(dp);
    }
  }

  @Override
  public void stop() {
    super.stop();
    client.close();
  }
}
