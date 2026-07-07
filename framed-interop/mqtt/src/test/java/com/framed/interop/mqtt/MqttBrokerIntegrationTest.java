package com.framed.interop.mqtt;

import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MQTT over a real embedded broker (Moquette) with the real Paho transport, validating
 * {@link PahoMqttTransport} in both directions.
 */
class MqttBrokerIntegrationTest {

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
  private static final String MAPPING = """
      { "Measurement.Oxylog-3000-Plus-00.etCO2": {"code":"19889-5","system":"LOINC","display":"etCO2","unit":"mm[Hg]","valueType":"NM"} }""";

  @TempDir
  Path tmp;

  private Server broker;
  private String url;

  @BeforeEach
  void startBroker() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    Properties props = new Properties();
    props.setProperty(IConfig.HOST_PROPERTY_NAME, "127.0.0.1");
    props.setProperty(IConfig.PORT_PROPERTY_NAME, Integer.toString(port));
    props.setProperty(IConfig.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");
    // Keep the broker fully in-memory: otherwise Moquette writes an H2 store to ./data.
    props.setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
    props.setProperty(IConfig.DATA_PATH_PROPERTY_NAME, tmp.resolve("moquette").toString());
    broker = new Server();
    broker.startServer(new MemoryConfig(props));
    url = "tcp://127.0.0.1:" + port;
  }

  @AfterEach
  void stopBroker() {
    if (broker != null) {
      broker.stopServer();
    }
  }

  private String mappingFile() throws Exception {
    Path p = tmp.resolve("mapping.json");
    Files.writeString(p, MAPPING);
    return p.toString();
  }

  @Test
  void outboundDatapointReachesBrokerSubscriber() throws Exception {
    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<String> topicSeen = new AtomicReference<>();
    AtomicReference<JSONObject> payloadSeen = new AtomicReference<>();
    PahoMqttTransport subscriber = new PahoMqttTransport(url, "ext-sub");
    subscriber.connect();
    subscriber.subscribe("framed/#", 1, (topic, payload) -> {
      topicSeen.set(topic);
      payloadSeen.set(MqttCodec.decode(payload));
      latch.countDown();
    });

    MqttService service = new MqttService(bus, "framed-out", url, "svc-out",
        new JSONArray().put("Oxylog-3000-Plus-00"),
        new JSONArray(), "framed", 1, mappingFile(), new JSONObject(), false, "MQTT-In");
    try {
      bus.publish(Service.addressRegistry("Oxylog-3000-Plus-00"), "Measurement.Oxylog-3000-Plus-00.etCO2.parsed");
      Thread.sleep(300);
      bus.publish("Measurement.Oxylog-3000-Plus-00.etCO2.parsed", new JSONObject()
          .put("timestamp", LocalDateTime.now().format(TS))
          .put("channelID", "etCO2").put("value", 38).put("className", "Measurement"));

      assertTrue(latch.await(5, TimeUnit.SECONDS), "subscriber should receive the published datapoint");
      assertEquals("framed/Oxylog-3000-Plus-00/etCO2", topicSeen.get());
      assertEquals(38, payloadSeen.get().getInt("value"));
      assertEquals("19889-5", payloadSeen.get().getString("code"));
    } finally {
      service.stop();
      subscriber.close();
      bus.shutdown();
    }
  }

  @Test
  void inboundBrokerMessageReachesBus() throws Exception {
    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Object> valueSeen = new AtomicReference<>();
    bus.register("Measurement.EXT.etCO2.parsed", msg -> {
      valueSeen.set(((JSONObject) msg).get("value"));
      latch.countDown();
    });

    MqttService service = new MqttService(bus, "framed-in", url, "svc-in", new JSONArray(),
        new JSONArray().put("framed/#"), "framed", 1, mappingFile(), new JSONObject(), false, "MQTT-In");
    PahoMqttTransport publisher = new PahoMqttTransport(url, "ext-pub");
    publisher.connect();
    try {
      Thread.sleep(300); // let the service's subscription register on the broker
      JSONObject payload = new JSONObject().put("value", 42).put("channelID", "etCO2")
          .put("deviceID", "EXT").put("className", "Measurement").put("code", "19889-5");
      publisher.publish("framed/EXT/etCO2", payload.toString().getBytes(StandardCharsets.UTF_8), 1);

      assertTrue(latch.await(5, TimeUnit.SECONDS), "inbound MQTT message should reach the bus");
      assertEquals(42, ((Number) valueSeen.get()).intValue());
    } finally {
      publisher.close();
      service.stop();
      bus.shutdown();
    }
  }
}
