package com.framed.interop.hl7;

import com.framed.core.EventBus;
import com.framed.interop.hl7.mllp.MllpServer;
import com.framed.interop.mapping.ObservationMapping;
import com.framed.io.protocol.Protocol;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Inbound HL7 v2 source: runs an MLLP server, parses received {@code ORU}/{@code ADT} messages and
 * publishes their observations onto the bus in the standard {@code "<className>.<deviceID>.<channelID>.parsed"}
 * convention, so existing reactors and dispatchers consume external data transparently.
 */
public final class Hl7v2Protocol extends Protocol {

  private final int port;
  private final ObservationMapping mapping;
  private final String inboundDeviceId;
  private final boolean ingestAdt;

  private volatile MllpServer server;

  /**
   * @param id              the service id
   * @param eventBus        the event bus
   * @param port            MLLP listen port ({@code 0} for an ephemeral port; see {@link #getPort()})
   * @param mappingPath     path to the interop mapping JSON
   * @param inboundDeviceId device id used for inbound channels whose mapping key has no device
   * @param ingestADT       whether to publish ADT patient demographics
   */
  public Hl7v2Protocol(String id, EventBus eventBus, int port, String mappingPath,
                       String inboundDeviceId, boolean ingestADT) {
    super(id, eventBus);
    this.port = port;
    this.inboundDeviceId = inboundDeviceId;
    this.ingestAdt = ingestADT;
    try {
      this.mapping = ObservationMapping.load(Path.of(mappingPath));
    } catch (IOException e) {
      throw new RuntimeException("Failed to load HL7 mapping at " + mappingPath, e);
    }
    connect();
  }

  @Override
  public void connect() {
    ObservationSink sink = this::publishObservation;
    InboundRouter router = new InboundRouter(mapping, sink, inboundDeviceId, ingestAdt);
    try {
      this.server = new MllpServer(port, router::handle);
      logger.info("HL7 MLLP server '%s' listening on port %d".formatted(id, server.getPort()));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to start MLLP server on port " + port, e);
    }
  }

  private void publishObservation(String className, String deviceID, String channelID,
                                  Object value, java.time.Instant ts) {
    String address = "%s.%s.%s.parsed".formatted(className, deviceID, channelID);
    JSONObject result = new JSONObject();
    result.put("timestamp", ZonedDateTime.ofInstant(ts, ZoneOffset.UTC).format(formatter));
    result.put("channelID", channelID);
    result.put("value", value);
    result.put("className", className);
    announceAddress(deviceID, address);
    eventBus.publish(address, result);
  }

  /** @return the actual bound port (useful when constructed with port 0). */
  public int getPort() {
    MllpServer s = this.server;
    return s == null ? -1 : s.getPort();
  }

  @Override
  public void stop() {
    MllpServer s = this.server;
    if (s != null) {
      s.close();
    }
  }
}
