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
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outbound HL7 v2 sink: maps FRAMED datapoints to {@code ORU^R01} messages and sends them over MLLP
 * to a remote endpoint, retrying on transient failures via the {@link Dispatcher} base.
 *
 * <p>Only channels present in the mapping are emitted (so high-rate waveforms are naturally skipped),
 * and emission is further throttled by the {@link EmissionGate}.</p>
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
    if (!gate.allow(dataPoint.channelID(), dataPoint.value(), System.currentTimeMillis())) {
      return;
    }

    String controlId = controlIdFor(dataPoint);
    String oru = OruBuilder.build(ids, patient, concept.get(),
        formatValue(dataPoint.value()), dataPoint.timestamp(), controlId);

    // A transport failure (connect/timeout) throws IOException here -> the base retries with
    // backoff. That retry reuses the same control id (see controlIdFor), so a receiver that lost
    // only the ACK can de-duplicate on MSH-10 rather than recording the observation twice.
    String ack = client.sendAndReceive(oru);

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
   * @param dp the datapoint
   * @return the control id
   */
  static String controlIdFor(DataPoint<?> dp) {
    return "FRAMED-" + Integer.toUnsignedString(
        Objects.hash(dp.deviceID(), dp.channelID(), dp.timestamp(), dp.value()));
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
