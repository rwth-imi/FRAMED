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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
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
 *
 * <p><b>Broker availability:</b> a broker that is down at startup is not fatal — the bridge logs a
 * warning and a daemon thread retries connect+subscribe until it succeeds or the service stops
 * (the deployment must not lose its MQTT leg permanently to a startup race). Once the initial
 * connect succeeded, later connection drops are healed by the transport's automatic reconnect.
 * Outbound publishes while disconnected fail and are dropped with a warning, throttled by the
 * gate's interval filter.</p>
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
   * One inbound handler instance shared across all subscribe calls (including re-subscribes after
   * a reconnect), so the transport's identity-based de-duplication of overlapping filters holds.
   */
  private final BiConsumer<String, byte[]> inboundHandler = this::onInbound;

  /** Delay between startup-connect retries. Package-visible for tests. */
  long reconnectDelayMs = 5_000;

  private volatile boolean running = true;
  private volatile boolean connected;

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
    // Outbound bus wiring is independent of broker state: discover device channels and forward.
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

    if (!connectAndSubscribe()) {
      // Broker down at startup: keep the deployment alive and retry in the background, otherwise
      // a startup race against the broker would silently cost the MQTT leg until a restart.
      Thread retry = new Thread(this::reconnectLoop, "MqttService-connect-retry");
      retry.setDaemon(true);
      retry.start();
    }
  }

  /**
   * Connects the transport and subscribes all configured inbound filters. Safe to call again
   * after a failure; a no-op once connected. Synchronized so the retry thread and any direct
   * caller cannot connect concurrently.
   *
   * @return {@code true} if the transport is connected (subscribe failures are logged, not fatal)
   */
  synchronized boolean connectAndSubscribe() {
    if (connected) {
      return true;
    }
    try {
      transport.connect();
    } catch (Exception e) {
      logger.log(Level.WARNING,
          "MQTT broker not reachable (will retry in %d ms)".formatted(reconnectDelayMs), e);
      return false;
    }
    // Inbound: subscribe and republish onto the bus (shared handler, see inboundHandler).
    for (Object t : subscribeTopics) {
      String filter = t.toString();
      try {
        transport.subscribe(filter, qos, inboundHandler);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to subscribe to MQTT topic " + filter, e);
      }
    }
    connected = true;
    return true;
  }

  private void reconnectLoop() {
    while (running && !connected) {
      try {
        Thread.sleep(reconnectDelayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (connectAndSubscribe()) {
        return;
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
      if (concept.isPresent() && concept.get().isWaveform()) {
        return; // one MQTT message per waveform sample would flood the broker; SDC streams these
      }
      // Interval state is committed at attempt time (a down broker is probed at most once per
      // interval, not once per sample); onChange state only after the publish was handed off, so
      // a throwing transport doesn't suppress an identical follow-up value that never left.
      String gateKey = EmissionGate.keyFor(device, dp.channelID());
      long nowMs = System.currentTimeMillis();
      if (!gate.allows(gateKey, dp.value(), nowMs)) {
        return;
      }
      gate.commitAttempt(gateKey, nowMs);
      String topic = "%s/%s/%s".formatted(topicPrefix, device, dp.channelID());
      transport.publish(topic, MqttCodec.encode(dp, concept.orElse(null)), qos);
      gate.commitValue(gateKey, dp.value());
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
      String timestamp = normalizeTimestamp(o.optString("timestamp", null), topic);

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

  /**
   * Normalizes an external timestamp to the bus convention (UTC, {@code Timer.formatter}), which
   * every subscriber parses strictly — an unvalidated pass-through would make all sinks and
   * reactors reject every inbound sample. Accepts the bus format itself, ISO-8601 with an offset
   * or {@code Z} (converted to UTC), offset-less ISO-8601 (interpreted as UTC), and the common
   * epoch conventions (13 digits = millis, 10 digits = seconds; numeric JSON values arrive here
   * stringified). An absent or unparseable timestamp falls back to the UTC arrival time (with a
   * warning when unparseable).
   *
   * @param raw   the external timestamp text, or {@code null} if absent
   * @param topic the source topic, for the warning log
   * @return a bus-format UTC timestamp string
   */
  private String normalizeTimestamp(String raw, String topic) {
    if (raw != null && !raw.isBlank()) {
      try {
        LocalDateTime.parse(raw, formatter);
        return raw; // already in bus format
      } catch (DateTimeParseException ignored) {
        // try the ISO-8601 forms below
      }
      try {
        return OffsetDateTime.parse(raw)
            .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().format(formatter);
      } catch (DateTimeParseException ignored) {
        // not offset-carrying ISO-8601
      }
      try {
        return LocalDateTime.parse(raw).format(formatter); // offset-less ISO-8601, taken as UTC
      } catch (DateTimeParseException ignored) {
        // unparseable
      }
      // Digit-only timestamps: the common epoch conventions. Exactly 13 digits is epoch millis
      // and exactly 10 is epoch seconds (both hold until the year 2286); other digit counts are
      // ambiguous (e.g. a 14-digit HL7 DTM would misread as a year-2612 epoch) and deliberately
      // fall through to the arrival-time fallback instead of guessing.
      if ((raw.length() == 13 || raw.length() == 10) && raw.chars().allMatch(Character::isDigit)) {
        try {
          long v = Long.parseLong(raw);
          Instant epoch = raw.length() == 13 ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
          return LocalDateTime.ofInstant(epoch, ZoneOffset.UTC).format(formatter);
        } catch (NumberFormatException | DateTimeException ignored) {
          // unparseable after all
        }
      }
      logger.log(Level.WARNING,
          "Unparseable timestamp \"%s\" on MQTT topic %s; using arrival time".formatted(raw, topic));
    }
    // Bus timestamps are UTC by convention: never stamp with local wall-clock time.
    return LocalDateTime.now(ZoneOffset.UTC).format(formatter);
  }

  @Override
  public void stop() {
    running = false;
    transport.close();
  }
}
