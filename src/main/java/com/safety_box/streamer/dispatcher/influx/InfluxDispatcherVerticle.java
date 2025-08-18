package com.safety_box.streamer.dispatcher.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.safety_box.streamer.dispatcher.Dispatcher;
import com.safety_box.streamer.model.DataPoint;
import com.safety_box.streamer.model.TimeSeries;
import io.vertx.core.Context;

import io.vertx.core.VerticleBase;
import io.vertx.core.Vertx;

import com.influxdb.client.write.Point;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

public class InfluxDispatcherVerticle extends Dispatcher {
  private String port;
  private String token;
  private String org;
  private InfluxDBClient client;
  private ZoneOffset zoneOffset;



  @Override
  public void init(Vertx vertx, Context context) {
    super.init(vertx, context);
    port = config().getString("port");
    token = config().getString("token");
    org = config().getString("org");
    client = InfluxDBClientFactory.create("http://localhost:"+port, token.toCharArray());
    LocalDateTime now = LocalDateTime.now();
    ZoneId zone = ZoneId.of("Europe/Berlin");
    zoneOffset = zone.getRules().getOffset(now);
  }


  @Override
  public void push(DataPoint<?> dataPoint) {
    Point point = Point
      .measurement(dataPoint.physioID())
      .time(dataPoint.timestamp(), WritePrecision.NS);
    Object value = dataPoint.value();
    if (value instanceof String) {
      point.addField("value", (String) value);
    } else if (value instanceof Number) {
      point.addField("value", (Number) value);
    } else if (value instanceof Boolean) {
      point.addField("value", (Boolean) value);
    } else {
      System.err.printf("Invalid value for data point %s\n", dataPoint.physioID());
    }
    WriteApiBlocking writeApi = this.client.getWriteApiBlocking();
    writeApi.writePoint(dataPoint.deviceID(), org, point);
  }


  @Override
  public void pushBatch(TimeSeries timeSeries) {
    for (DataPoint<?> dp: timeSeries.dataPoints()) {
      push(dp);
    }
  }
}
