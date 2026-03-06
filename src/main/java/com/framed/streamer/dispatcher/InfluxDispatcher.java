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
import java.util.Map;


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

    if (value instanceof String string) {
      point.addField(dataPoint.className(), string);
    } else if (value instanceof Number number) {
      point.addField(dataPoint.className(), number.floatValue());
    } else if (value instanceof Boolean bool) {
      point.addField(dataPoint.className(), bool);
    } else if (value instanceof Map map) {
      point.addField(dataPoint.className(), map.toString());
    }else {
      throw new IllegalArgumentException("Invalid value for data point %s\n".formatted(dataPoint.channelID()));
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
