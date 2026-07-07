package com.framed.interop.hl7;

import com.framed.core.EventBus;
import com.framed.interop.gate.EmissionGate;
import com.framed.interop.hl7.hl7v2.Hl7Message;
import com.framed.interop.hl7.hl7v2.OruBuilder;
import com.framed.interop.hl7.hl7v2.PatientContext;
import com.framed.interop.hl7.hl7v2.SendingIds;
import com.framed.interop.hl7.mllp.MllpClient;
import com.framed.interop.mapping.CodedConcept;
import com.framed.interop.mapping.ObservationMapping;
import com.framed.io.dispatch.DataPoint;
import com.framed.io.dispatch.Dispatcher;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Outbound HL7 v2 sink: maps FRAMED datapoints to {@code ORU^R01} messages and sends them over MLLP
 * to a remote endpoint, retrying on transient failures via the {@link Dispatcher} base.
 *
 * <p>Only channels present in the mapping are emitted (so high-rate waveforms are naturally skipped),
 * and emission is further throttled by the {@link EmissionGate}, keyed per device and channel.</p>
 *
 * <p><b>Deployment caution:</b> never point {@code host}/{@code port} at FRAMED's own
 * {@link Hl7v2Protocol} MLLP server. The inbound side republishes received observations under the
 * mapped device groups — the very groups this dispatcher subscribes to — so a self-referential
 * endpoint creates a feedback loop that re-emits every observation indefinitely.</p>
 */
public final class Hl7v2Dispatcher extends Dispatcher {

  private final ObservationMapping mapping;
  private final EmissionGate gate;
  private final PatientContext patient;
  private final SendingIds ids;
  private final MllpClient client;

  /**
   * @param eventBus   the event bus
   * @param devices    device groups whose announced channels are forwarded
   * @param host       MLLP server host to send to
   * @param port       MLLP server port
   * @param mappingPath path to the interop mapping JSON
   * @param patient    PID/PV1 context (config JSON)
   * @param sendingIds MSH-3..6 identifiers (config JSON)
   * @param gate       emission gate config ({@code minIntervalMs}, {@code onChange})
   */
  public Hl7v2Dispatcher(EventBus eventBus, JSONArray devices, String host, int port,
                         String mappingPath, JSONObject patient, JSONObject sendingIds,
                         JSONObject gate) {
    super(eventBus, devices);
    try {
      this.mapping = ObservationMapping.load(Path.of(mappingPath));
    } catch (IOException e) {
      throw new RuntimeException("Failed to load HL7 mapping at " + mappingPath, e);
    }
    this.patient = PatientContext.fromJson(patient);
    this.ids = SendingIds.fromJson(sendingIds);
    JSONObject g = gate == null ? new JSONObject() : gate;
    this.gate = new EmissionGate(g.optBoolean("onChange", false), g.optLong("minIntervalMs", 0));
    this.client = new MllpClient(host, port, 5000);
  }

  @Override
  public void push(DataPoint<?> dataPoint) throws IOException {
    Optional<CodedConcept> concept =
        mapping.lookup(dataPoint.className(), dataPoint.deviceID(), dataPoint.channelID());
    if (concept.isEmpty()) {
      return; // unmapped channel (e.g. a raw waveform) — not emitted over HL7
    }
    // Gate keyed per device+channel so identically named channels on different devices don't
    // share a throttle slot. Checked here, committed only after the send succeeded: committing
    // before a failed send would make the gate suppress the base Dispatcher's retry of this very
    // datapoint and silently lose it.
    String gateKey = dataPoint.deviceID() + "." + dataPoint.channelID();
    long nowMs = System.currentTimeMillis();
    if (!gate.allows(gateKey, dataPoint.value(), nowMs)) {
      return;
    }

    String controlId = controlIdFor(dataPoint);
    String oru = OruBuilder.build(ids, patient, concept.get(),
        formatValue(dataPoint.value()), dataPoint.timestamp(), controlId);

    // A transport failure (connect/timeout) throws IOException here -> the base retries with
    // backoff. That retry reuses the same control id (see controlIdFor), so a receiver that lost
    // only the ACK can de-duplicate on MSH-10 rather than recording the observation twice.
    String ack = client.sendAndReceive(oru);
    // The peer received the message (even a NAK below counts as delivered): the emission happened.
    gate.commit(gateKey, dataPoint.value(), nowMs);

    String ackCode = Hl7Message.parse(ack).field("MSA", 1);
    if (!"AA".equals(ackCode) && !"CA".equals(ackCode)) {
      // Application-level reject/error: the peer received the message and rejected it, so resending
      // the identical content cannot succeed. Drop to the dead-letter hook instead of retrying
      // forever (which would wedge the single push worker on a poison message).
      onDrop(dataPoint, new IOException(
          "HL7 endpoint NAK (MSA-1=" + ackCode + ") for control id " + controlId));
    }
  }

  /**
   * Stable message control id (MSH-10) for a datapoint. It is derived from the datapoint rather
   * than a counter so that a retry of the <em>same</em> datapoint re-sends the same control id,
   * letting a conformant receiver de-duplicate when only the acknowledgement was lost.
   *
   * <p>The id is the first 80 bits of a SHA-256 over the datapoint's identity, hex-encoded to the
   * 20-character HL7 v2.5 length limit of MSH-10. 80 bits keeps birthday collisions between
   * <em>distinct</em> observations negligible over years of continuous vitals (a 32-bit hash, by
   * contrast, collides within weeks and makes receivers silently discard real observations).</p>
   *
   * @param dp the datapoint
   * @return the control id (20 hex characters)
   */
  static String controlIdFor(DataPoint<?> dp) {
    String identity = dp.deviceID() + '|' + dp.channelID() + '|' + dp.timestamp() + '|' + dp.value();
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().withUpperCase().formatHex(hash, 0, 10);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // mandated by the JCA spec
    }
  }

  @Override
  public void pushBatch(List<DataPoint<?>> batch) {
    for (DataPoint<?> dp : batch) {
      try {
        push(dp);
      } catch (IOException e) {
        onDrop(dp, e);
      }
    }
  }

  /** Renders whole-number doubles without a trailing {@code .0} for tidy OBX-5 values. */
  private static String formatValue(Object value) {
    if (value instanceof Double d && !d.isInfinite() && !d.isNaN() && d == Math.rint(d)) {
      return Long.toString(d.longValue());
    }
    return String.valueOf(value);
  }

  @Override
  public void stop() {
    client.close();
    super.stop();
  }
}
