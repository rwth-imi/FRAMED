package com.framed.interop.mqtt;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.interop.gate.EmissionGate;
import com.framed.interop.mapping.CodedConcept;
import com.framed.interop.mapping.ObservationMapping;
import com.framed.io.dispatch.DataPoint;
import com.framed.io.dispatch.DataPointParser;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bidirectional MQTT bridge. A single broker connection both publishes FRAMED observations and
 * subscribes to inbound topics, keeping the framework's internal flow on the EAV {@link DataPoint}
 * model — only the wire payload is MQTT/JSON.
 *
 * <p>Outbound: discovers each configured device's channels (the standard
 * {@code announceAddress}/{@code addressRegistry} handshake), applies the {@link EmissionGate}, and
 * publishes to {@code "<topicPrefix>/<deviceID>/<channelID>"}. Only mapped channels are emitted
 * unless {@code includeUnmapped} is set.</p>
 *
 * <p>Inbound: for each subscribed topic, decodes the payload and republishes it as a
 * {@code "<className>.<deviceID>.<channelID>.parsed"} bus event.</p>
 *
 * <p>This is a single bidirectional {@link Service} (rather than a Dispatcher + Protocol pair)
 * because one MQTT client connection inherently serves both directions; the Paho client is already
 * asynchronous, so no extra worker is needed to keep the bus handler non-blocking.</p>
 */
public final class MqttService extends Service {

  private final MqttTransport transport;
  private final JSONArray devices;
  private final JSONArray subscribeTopics;
  private final String topicPrefix;
  private final int qos;
  private final ObservationMapping mapping;
  private final EmissionGate gate;
  private final boolean includeUnmapped;
  private final String inboundDeviceId;

  private final Set<String> boundChannels = ConcurrentHashMap.newKeySet();

  /**
   * Config-loadable constructor.
   *
   * @param eventBus        the event bus
   * @param id              the service id
   * @param brokerUrl       MQTT broker URI (e.g. {@code tcp://127.0.0.1:1883})
   * @param clientId        MQTT client id
   * @param devices         device groups whose announced channels are forwarded outbound
   * @param subscribeTopics inbound topic filters (may be empty)
   * @param topicPrefix     prefix for published topics (e.g. {@code framed})
   * @param qos             MQTT QoS for publish/subscribe
   * @param mappingPath     path to the interop mapping JSON
   * @param gate            emission gate config ({@code onChange}, {@code minIntervalMs})
   * @param includeUnmapped if true, publish unmapped channels too (no coded concept in payload)
   * @param inboundDeviceId device id for inbound messages whose payload/topic lacks one
   */
  public MqttService(EventBus eventBus, String id, String brokerUrl, String clientId,
                     JSONArray devices, JSONArray subscribeTopics, String topicPrefix, int qos,
                     String mappingPath, JSONObject gate, boolean includeUnmapped,
                     String inboundDeviceId) {
    super(eventBus);
    this.transport = new PahoMqttTransport(brokerUrl, clientId);
    this.devices = devices;
    this.subscribeTopics = subscribeTopics;
    this.topicPrefix = topicPrefix;
    this.qos = qos;
    try {
      this.mapping = ObservationMapping.load(Path.of(mappingPath));
    } catch (Exception e) {
      throw new RuntimeException("Failed to load interop mapping at " + mappingPath, e);
    }
    JSONObject g = gate == null ? new JSONObject() : gate;
    this.gate = new EmissionGate(g.optBoolean("onChange", false), g.optLong("minIntervalMs", 0));
    this.includeUnmapped = includeUnmapped;
    this.inboundDeviceId = inboundDeviceId;
    init();
  }

  /** Test constructor that accepts a transport and pre-built mapping/gate directly. */
  MqttService(EventBus eventBus, MqttTransport transport, JSONArray devices,
              JSONArray subscribeTopics, String topicPrefix, int qos, ObservationMapping mapping,
              EmissionGate gate, boolean includeUnmapped, String inboundDeviceId) {
    super(eventBus);
    this.transport = transport;
    this.devices = devices;
    this.subscribeTopics = subscribeTopics;
    this.topicPrefix = topicPrefix;
    this.qos = qos;
    this.mapping = mapping;
    this.gate = gate;
    this.includeUnmapped = includeUnmapped;
    this.inboundDeviceId = inboundDeviceId;
    init();
  }

  private void init() {
    try {
      transport.connect();
    } catch (Exception e) {
      throw new RuntimeException("Failed to connect MQTT transport", e);
    }

    // Outbound: discover device channels and forward them.
    for (Object deviceObj : devices) {
      String device = deviceObj.toString();
      eventBus.register(addressRegistry(device), msg -> {
        String address = String.valueOf(msg);
        if (address.isBlank()) {
          return;
        }
        if (boundChannels.add(device + "|" + address)) {
          eventBus.register(address, m -> onOutbound(device, m));
        }
      });
    }

    // Inbound: subscribe and republish onto the bus.
    for (Object t : subscribeTopics) {
      String filter = t.toString();
      try {
        transport.subscribe(filter, qos, this::onInbound);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to subscribe to MQTT topic " + filter, e);
      }
    }
  }

  private void onOutbound(String device, Object message) {
    if (!(message instanceof JSONObject body)) {
      return;
    }
    try {
      JSONObject enriched = new JSONObject(body.toString());
      enriched.put("deviceID", device);
      DataPoint<?> dp = DataPointParser.parse(enriched);

      Optional<CodedConcept> concept = mapping.lookup(dp.className(), dp.deviceID(), dp.channelID());
      if (concept.isEmpty() && !includeUnmapped) {
        return;
      }
      if (!gate.allow(dp.channelID(), dp.value(), System.currentTimeMillis())) {
        return;
      }
      String topic = "%s/%s/%s".formatted(topicPrefix, device, dp.channelID());
      transport.publish(topic, MqttCodec.encode(dp, concept.orElse(null)), qos);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to publish MQTT message for device " + device, e);
    }
  }

  private void onInbound(String topic, byte[] payload) {
    try {
      JSONObject o = MqttCodec.decode(payload);
      String[] seg = topic.split("/");
      String topicDevice = seg.length >= 2 ? seg[seg.length - 2] : inboundDeviceId;
      String topicChannel = seg.length >= 1 ? seg[seg.length - 1] : "";

      String channelID = o.optString("channelID", topicChannel);
      String deviceID = o.optString("deviceID", topicDevice);
      String className = o.optString("className", "");
      if (className.isEmpty()) {
        className = mapping.channelForCode(o.optString("code", ""))
            .map(ObservationMapping.Channel::className).orElse("Measurement");
      }
      Object value = o.opt("value");
      if (value == null) {
        // JSONObject.put(key, null) would silently drop the field; a valueless sample is useless.
        logger.log(Level.WARNING, "Inbound MQTT message on {0} has no value; dropping", topic);
        return;
      }
      String timestamp = o.optString("timestamp", LocalDateTime.now().format(formatter));

      String address = "%s.%s.%s.parsed".formatted(className, deviceID, channelID);
      JSONObject result = new JSONObject()
          .put("timestamp", timestamp)
          .put("channelID", channelID)
          .put("value", value)
          .put("className", className);
      announceAddress(deviceID, address);
      eventBus.publish(address, result);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to handle inbound MQTT message on " + topic, e);
    }
  }

  @Override
  public void stop() {
    transport.close();
  }
}
