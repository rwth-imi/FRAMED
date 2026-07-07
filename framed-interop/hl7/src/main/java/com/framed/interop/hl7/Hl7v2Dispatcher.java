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
   * How long the base {@link Dispatcher}'s retry loop may keep re-attempting one datapoint before
   * this dispatcher gives up and dead-letters it. Bounds worker wedging on a dead endpoint: with
   * the interval slot burnt per attempt (see {@link #push}), an outage costs at most one
   * ~{@code retryBudgetMs} retry cycle per gate interval while all other samples are rejected
   * cheaply. Package-visible for tests.
   */
  long retryBudgetMs = 30_000;

  // Retry tracking for the single push worker thread (the base retries a failed push() of the
  // SAME datapoint in a loop): lets a retry bypass the gate it already passed, and enforces the
  // retry budget. Accessed only on the worker thread.
  private String inFlightControlId;
  private long inFlightSinceMs;

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
    if (concept.isEmpty() || concept.get().isWaveform()) {
      // Unmapped channels and waveform-kind mappings are not emitted over HL7: per-sample ORU
      // messages cannot carry device-rate streams — that is the SDC bridge's job.
      return;
    }
    String gateKey = EmissionGate.keyFor(dataPoint.deviceID(), dataPoint.channelID());
    String controlId = controlIdFor(dataPoint);
    long nowMs = System.currentTimeMillis();

    // The base Dispatcher retries a failed push() of the same datapoint in a loop, so this method
    // sees each attempt. A retry (recognized by its stable control id) bypasses the gate it
    // already passed — re-checking would suppress it, silently losing the datapoint — but only
    // within the retry budget, after which the datapoint is dead-lettered so a dead endpoint
    // cannot wedge the single push worker indefinitely.
    boolean isRetry = controlId.equals(inFlightControlId);
    if (isRetry) {
      if (nowMs - inFlightSinceMs > retryBudgetMs) {
        inFlightControlId = null;
        onDrop(dataPoint, new IOException(
            "HL7 endpoint unreachable for %d ms; giving up on control id %s"
                .formatted(nowMs - inFlightSinceMs, controlId)));
        return;
      }
    } else {
      if (!gate.allows(gateKey, dataPoint.value(), nowMs)) {
        return;
      }
      // Burn the interval slot at attempt time: during an endpoint outage, attempts are then
      // throttled to one per interval instead of one per sample. The onChange state is committed
      // separately below, only once the receiver accepted the value.
      gate.commitAttempt(gateKey, nowMs);
      inFlightControlId = controlId;
      inFlightSinceMs = nowMs;
    }

    String oru = OruBuilder.build(ids, patient, concept.get(),
        formatValue(dataPoint.value()), dataPoint.timestamp(), controlId);

    // A transport failure (connect/timeout) throws IOException here -> the base retries with
    // backoff. That retry reuses the same control id (see controlIdFor), so a receiver that lost
    // only the ACK can de-duplicate on MSH-10 rather than recording the observation twice.
    String ack = client.sendAndReceive(oru);
    inFlightControlId = null;

    String ackCode = Hl7Message.parse(ack).field("MSA", 1);
    if (!"AA".equals(ackCode) && !"CA".equals(ackCode)) {
      // Application-level reject/error: the peer received the message and rejected it, so resending
      // the identical content cannot succeed. Drop to the dead-letter hook instead of retrying
      // forever (which would wedge the single push worker on a poison message). The onChange state
      // is NOT committed: the receiver never stored this value, so an identical follow-up value
      // must stay eligible for emission.
      onDrop(dataPoint, new IOException(
          "HL7 endpoint NAK (MSA-1=" + ackCode + ") for control id " + controlId));
      return;
    }
    gate.commitValue(gateKey, dataPoint.value());
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
