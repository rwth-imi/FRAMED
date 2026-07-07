package com.framed.interop.hl7.hl7v2;

import com.framed.interop.mapping.CodedConcept;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds {@code ORU^R01} (unsolicited observation result) HL7 v2.5 messages from FRAMED
 * observations. Segments are joined with {@code \r} as required by MLLP.
 *
 * <p>All data taken from observations, mapping and configuration is delimiter-escaped via
 * {@link Hl7Escape} before embedding; the configured patient location is treated as
 * component-structured (its {@code ^} separators are preserved). Each OBX carries its own
 * observation's time in OBX-14, so receivers (including a FRAMED inbound side) keep the original
 * per-observation timestamps instead of re-stamping on arrival or inheriting the message time.</p>
 */
public final class OruBuilder {

  /** HL7 timestamp format ({@code yyyyMMddHHmmss}), interpreted in UTC. */
  private static final DateTimeFormatter HL7_TS =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private static final String SEGMENT_SEP = "\r";

  private OruBuilder() {}

  /**
   * A single observation to encode as an OBX segment.
   *
   * @param concept   the coded concept (OBX-3 / OBX-6)
   * @param value     the observation value, already formatted (OBX-5)
   * @param timestamp the observation time (OBX-14)
   */
  public record Observation(CodedConcept concept, String value, Instant timestamp) {}

  /**
   * Builds an ORU^R01 carrying a single observation.
   *
   * @param ids       MSH sender/receiver ids
   * @param patient   patient context for PID/PV1
   * @param concept   the coded concept
   * @param value     the observation value (already formatted)
   * @param timestamp the observation time
   * @param controlId the MSH-10 message control id
   * @return the HL7 message text
   */
  public static String build(SendingIds ids, PatientContext patient, CodedConcept concept,
                             String value, Instant timestamp, String controlId) {
    return build(ids, patient, List.of(new Observation(concept, value, timestamp)), timestamp,
        controlId);
  }

  /**
   * Builds an ORU^R01 carrying one or more observations (one OBX each, stamped with its own
   * observation time in OBX-14).
   *
   * @param ids          MSH sender/receiver ids
   * @param patient      patient context for PID/PV1
   * @param observations the observations to encode
   * @param timestamp    the message time (MSH-7)
   * @param controlId    the MSH-10 message control id
   * @return the HL7 message text
   */
  public static String build(SendingIds ids, PatientContext patient, List<Observation> observations,
                             Instant timestamp, String controlId) {
    String ts = HL7_TS.format(timestamp);
    StringBuilder sb = new StringBuilder();

    sb.append("MSH|^~\\&|").append(Hl7Escape.field(ids.sendingApp())).append('|')
        .append(Hl7Escape.field(ids.sendingFacility()))
        .append('|').append(Hl7Escape.field(ids.receivingApp())).append('|')
        .append(Hl7Escape.field(ids.receivingFacility()))
        .append('|').append(ts).append("||ORU^R01|").append(Hl7Escape.field(controlId))
        .append("|P|2.5").append(SEGMENT_SEP);

    sb.append("PID|1||").append(Hl7Escape.field(patient.mrn())).append("^^^MRN||")
        .append(Hl7Escape.field(patient.lastName())).append('^')
        .append(Hl7Escape.field(patient.firstName()))
        .append("||").append(Hl7Escape.field(patient.dob())).append('|')
        .append(Hl7Escape.field(patient.sex()))
        .append(SEGMENT_SEP);

    sb.append("PV1|1|I|").append(Hl7Escape.components(patient.location())).append(SEGMENT_SEP);

    sb.append("OBR|1||||").append(SEGMENT_SEP);

    int setId = 1;
    for (Observation obs : observations) {
      CodedConcept c = obs.concept();
      sb.append("OBX|").append(setId++).append('|').append(Hl7Escape.field(c.valueType())).append('|')
          .append(Hl7Escape.field(c.code())).append('^').append(Hl7Escape.field(c.display()))
          .append('^').append(Hl7Escape.field(c.system()))
          .append("||").append(Hl7Escape.field(obs.value())).append('|')
          .append(Hl7Escape.field(c.unit()))
          .append("|||||F|||").append(HL7_TS.format(obs.timestamp())).append(SEGMENT_SEP);
    }

    return sb.toString();
  }
}
