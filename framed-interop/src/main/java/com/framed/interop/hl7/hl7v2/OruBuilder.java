package com.framed.interop.hl7.hl7v2;

import com.framed.interop.mapping.CodedConcept;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Builds {@code ORU^R01} (unsolicited observation result) HL7 v2.5 messages from FRAMED
 * observations. Segments are joined with {@code \r} as required by MLLP.
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
   * @param concept the coded concept (OBX-3 / OBX-6)
   * @param value   the observation value, already formatted (OBX-5)
   */
  public record Observation(CodedConcept concept, String value) {}

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
    return build(ids, patient, List.of(new Observation(concept, value)), timestamp, controlId);
  }

  /**
   * Builds an ORU^R01 carrying one or more observations (one OBX each).
   *
   * @param ids          MSH sender/receiver ids
   * @param patient      patient context for PID/PV1
   * @param observations the observations to encode
   * @param timestamp    the observation time (used for MSH-7 and each OBX)
   * @param controlId    the MSH-10 message control id
   * @return the HL7 message text
   */
  public static String build(SendingIds ids, PatientContext patient, List<Observation> observations,
                             Instant timestamp, String controlId) {
    String ts = HL7_TS.format(timestamp);
    StringBuilder sb = new StringBuilder();

    sb.append("MSH|^~\\&|").append(ids.sendingApp()).append('|').append(ids.sendingFacility())
        .append('|').append(ids.receivingApp()).append('|').append(ids.receivingFacility())
        .append('|').append(ts).append("||ORU^R01|").append(controlId).append("|P|2.5")
        .append(SEGMENT_SEP);

    sb.append("PID|1||").append(patient.mrn()).append("^^^MRN||")
        .append(patient.lastName()).append('^').append(patient.firstName())
        .append("||").append(patient.dob()).append('|').append(patient.sex())
        .append(SEGMENT_SEP);

    sb.append("PV1|1|I|").append(patient.location()).append(SEGMENT_SEP);

    sb.append("OBR|1||||").append(SEGMENT_SEP);

    int setId = 1;
    for (Observation obs : observations) {
      CodedConcept c = obs.concept();
      sb.append("OBX|").append(setId++).append('|').append(c.valueType()).append('|')
          .append(c.code()).append('^').append(c.display()).append('^').append(c.system())
          .append("||").append(obs.value()).append('|').append(c.unit())
          .append("|||||F").append(SEGMENT_SEP);
    }

    return sb.toString();
  }
}
