package com.safety_box.streamer.dispatcher.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.safety_box.streamer.dispatcher.LocalDispatcher;
import com.safety_box.streamer.dispatcher.RemoteDispatcher;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class InfluxLocalDispatcherVerticle extends LocalDispatcher {
  private String port;
  private String token;
  private String org;
  private InfluxDBClient client;
  private ZoneOffset zoneOffset;
  private String bucket;
  WriteApiBlocking writeApi;


  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    port = config().getString("port");
    token = config().getString("token");
    org = config().getString("org");
    client = InfluxDBClientFactory.create("http://localhost:"+port, token.toCharArray());
    bucket = config().getString("bucket");
    LocalDateTime now = LocalDateTime.now();
    ZoneId zone = ZoneId.of("Europe/Berlin");
    zoneOffset = zone.getRules().getOffset(now);
  }

  @Override
  public Future<?> start() throws Exception {
    writeApi = this.client.getWriteApiBlocking();
    return super.start();
  }

  @Override
  public void push(DataPoint<?> dataPoint) {
    Point point = Point
      .measurement(dataPoint.deviceID());
    Object value = dataPoint.value();
    if (value instanceof String) {
      point.addField(dataPoint.className(), (String) value);
    } else if (value instanceof Number) {
      point.addField(dataPoint.className(), (Number) value);
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
}
