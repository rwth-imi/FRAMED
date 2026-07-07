package com.framed.interop.hl7.hl7v2;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds HL7 v2 general acknowledgement ({@code ACK}) messages in response to a received message.
 */
public final class AckBuilder {

  private static final DateTimeFormatter HL7_TS =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private static final String SEGMENT_SEP = "\r";

  /** Application Accept. */
  public static final String AA = "AA";
  /** Application Error. */
  public static final String AE = "AE";
  /** Application Reject. */
  public static final String AR = "AR";

  private AckBuilder() {}

  /**
   * Builds an ACK echoing the control id of the incoming message, with sender/receiver swapped.
   *
   * @param incoming  the message being acknowledged
   * @param code      the acknowledgement code ({@link #AA}, {@link #AE}, {@link #AR})
   * @param text      optional MSA-3 text (e.g. an error description); may be empty
   * @param timestamp the ACK timestamp
   * @return the ACK message text
   */
  public static String build(Hl7Message incoming, String code, String text, Instant timestamp) {
    SendingIds in = new SendingIds(
        incoming.field("MSH", 3), incoming.field("MSH", 4),
        incoming.field("MSH", 5), incoming.field("MSH", 6));
    SendingIds out = in.swapped();
    String ts = HL7_TS.format(timestamp);
    String controlId = incoming.controlId();

    return "MSH|^~\\&|" + out.sendingApp() + '|' + out.sendingFacility()
        + '|' + out.receivingApp() + '|' + out.receivingFacility()
        + '|' + ts + "||ACK|" + controlId + "|P|2.5" + SEGMENT_SEP
        + "MSA|" + code + '|' + controlId
        + (text == null || text.isEmpty() ? "" : "|" + Hl7Escape.field(text))
        + SEGMENT_SEP;
  }
}
