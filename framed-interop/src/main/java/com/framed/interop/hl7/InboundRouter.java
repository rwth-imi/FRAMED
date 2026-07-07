package com.framed.interop.hl7;

import com.framed.interop.hl7.hl7v2.AckBuilder;
import com.framed.interop.hl7.hl7v2.Hl7Message;
import com.framed.interop.mapping.ObservationMapping;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses inbound HL7 messages and routes their content onto the FRAMED bus as EAV observations,
 * returning the HL7 acknowledgement to send back.
 *
 * <ul>
 *   <li>{@code ORU^R01} — each {@code OBX} is reverse-mapped by code to a FRAMED channel and handed
 *       to the {@link ObservationSink}.</li>
 *   <li>{@code ADT^A01}/{@code ADT^A08} — when ADT ingestion is enabled, patient demographics are
 *       published as EAV observations under the {@code Patient} class.</li>
 * </ul>
 *
 * <p>Well-formed messages are answered with {@code AA}; a parse failure yields {@code AE}.</p>
 */
public final class InboundRouter {

  private static final Logger LOGGER = Logger.getLogger(InboundRouter.class.getName());

  private static final DateTimeFormatter HL7_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  /** HL7 DTM: 4–14 digits (year down to seconds), optional fraction, optional zone offset. */
  private static final Pattern HL7_DTM =
      Pattern.compile("(\\d{4,14})(?:\\.\\d+)?([+-]\\d{4})?");

  private final ObservationMapping mapping;
  private final ObservationSink sink;
  private final String inboundDeviceId;
  private final boolean ingestAdt;

  /**
   * @param mapping         the bidirectional code mapping
   * @param sink            where decoded observations are delivered
   * @param inboundDeviceId fallback device id when a mapping key has no device component
   * @param ingestAdt       whether to publish ADT demographics
   */
  public InboundRouter(ObservationMapping mapping, ObservationSink sink,
                       String inboundDeviceId, boolean ingestAdt) {
    this.mapping = mapping;
    this.sink = sink;
    this.inboundDeviceId = inboundDeviceId;
    this.ingestAdt = ingestAdt;
  }

  /**
   * Handles one raw HL7 message.
   *
   * @param raw the received HL7 message text
   * @return the acknowledgement message text to send back
   */
  public String handle(String raw) {
    Hl7Message msg;
    try {
      msg = Hl7Message.parse(raw);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "Failed to parse inbound HL7", e);
      return AckBuilder.build(Hl7Message.parse("MSH|^~\\&|||||||UNK|0|P|2.5"),
          AckBuilder.AE, "unparseable message", Instant.now());
    }

    try {
      if (msg.segment("MSH") == null) {
        return AckBuilder.build(msg, AckBuilder.AE, "missing MSH segment", Instant.now());
      }
      String type = msg.messageType();
      switch (type) {
        case "ORU^R01" -> handleOru(msg);
        case "ADT^A01", "ADT^A08", "ADT^A04" -> handleAdt(msg);
        default -> LOGGER.log(Level.FINE, "Ignoring unsupported message type {0}", type);
      }
      return AckBuilder.build(msg, AckBuilder.AA, "", Instant.now());
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "Error routing inbound HL7", e);
      return AckBuilder.build(msg, AckBuilder.AE, e.getMessage(), Instant.now());
    }
  }

  private void handleOru(Hl7Message msg) {
    for (String[] obx : msg.segments("OBX")) {
      String code = Hl7Message.firstComponent(Hl7Message.field(obx, "OBX", 3));
      String rawValue = Hl7Message.field(obx, "OBX", 5);
      if (code.isEmpty()) {
        continue;
      }
      var channel = mapping.channelForCode(code);
      if (channel.isEmpty()) {
        LOGGER.log(Level.FINE, "No mapping for inbound code {0}", code);
        continue;
      }
      ObservationMapping.Channel ch = channel.get();
      String deviceID = ch.deviceID() != null ? ch.deviceID() : inboundDeviceId;
      Instant ts = parseTimestamp(Hl7Message.field(obx, "OBX", 14));
      sink.accept(ch.className(), deviceID, ch.channelID(), coerce(rawValue), ts);
    }
  }

  private void handleAdt(Hl7Message msg) {
    if (!ingestAdt) {
      return;
    }
    String mrn = Hl7Message.firstComponent(msg.field("PID", 3));
    if (!mrn.isEmpty()) {
      sink.accept("Patient", inboundDeviceId, "MRN", mrn, Instant.now());
    }
    String name = msg.field("PID", 5);
    if (!name.isEmpty()) {
      sink.accept("Patient", inboundDeviceId, "Name", name.replace('^', ' ').trim(), Instant.now());
    }
  }

  /** Numeric values become {@link Double}; everything else stays a {@link String}. */
  private static Object coerce(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return value;
    }
  }

  /**
   * Parses an HL7 DTM (OBX-14) honouring any explicit zone offset and every legal precision from
   * year down to seconds (shorter precisions are padded: missing month/day default to {@code 01},
   * missing time-of-day to {@code 00}). Without an explicit offset the value is interpreted as
   * UTC, matching the outbound convention of {@code OruBuilder}. An absent value falls back to the
   * arrival time; a malformed value does too, with a warning — never silently.
   *
   * @param hl7Ts the raw OBX-14 text (may be {@code null} or empty)
   * @return the observation instant, or the arrival time when absent/unparseable
   */
  static Instant parseTimestamp(String hl7Ts) {
    if (hl7Ts == null || hl7Ts.isBlank()) {
      return Instant.now(); // OBX-14 is optional; arrival time is the documented fallback
    }
    Matcher m = HL7_DTM.matcher(hl7Ts.trim());
    if (m.matches() && m.group(1).length() % 2 == 0) {
      String body = m.group(1);
      // Pad to full yyyyMMddHHmmss: absent month/day become 01, absent time-of-day 00.
      String padded = switch (body.length()) {
        case 4 -> body + "0101000000";
        case 6 -> body + "01000000";
        default -> body + "00000000000000".substring(body.length());
      };
      ZoneOffset offset = m.group(2) != null ? ZoneOffset.of(m.group(2)) : ZoneOffset.UTC;
      try {
        return LocalDateTime.parse(padded, HL7_TS).toInstant(offset);
      } catch (RuntimeException e) {
        // fall through to the warning below (e.g. month 13)
      }
    }
    LOGGER.log(Level.WARNING,
        "Unparseable OBX-14 timestamp \"{0}\"; falling back to arrival time", hl7Ts);
    return Instant.now();
  }
}
