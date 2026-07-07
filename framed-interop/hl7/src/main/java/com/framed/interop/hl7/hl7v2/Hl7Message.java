package com.framed.interop.hl7.hl7v2;

import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight, read-oriented HL7 v2 pipe-encoded message: a list of segments, each split into
 * fields on {@code |}. Components ({@code ^}) and repetitions ({@code ~}) are exposed as raw field
 * text for callers to split as needed.
 *
 * <p>Field numbering follows HL7's convention. For most segments field <i>n</i> is at array index
 * <i>n</i> (index 0 being the segment id). {@code MSH} is special: its first {@code |} <i>is</i>
 * field 1 (the field separator), so {@code MSH-n} for {@code n >= 2} lives at array index
 * {@code n - 1}. {@link #field(String, int)} hides this difference.</p>
 */
public final class Hl7Message {

  private final List<String[]> segments;

  private Hl7Message(List<String[]> segments) {
    this.segments = segments;
  }

  /**
   * Parses a raw HL7 message. Segment separators may be {@code \r}, {@code \n} or {@code \r\n}.
   *
   * @param raw the raw message text
   * @return the parsed message
   */
  public static Hl7Message parse(String raw) {
    List<String[]> segs = new ArrayList<>();
    for (String line : raw.split("[\\r\\n]+")) {
      if (!line.isBlank()) {
        segs.add(line.split("\\|", -1));
      }
    }
    return new Hl7Message(segs);
  }

  /** @return the first segment with the given id, or {@code null}. */
  public String[] segment(String id) {
    for (String[] seg : segments) {
      if (seg.length > 0 && seg[0].equals(id)) {
        return seg;
      }
    }
    return null;
  }

  /** @return all segments with the given id, in order. */
  public List<String[]> segments(String id) {
    List<String[]> out = new ArrayList<>();
    for (String[] seg : segments) {
      if (seg.length > 0 && seg[0].equals(id)) {
        out.add(seg);
      }
    }
    return out;
  }

  /**
   * Returns field <i>n</i> of the first {@code id} segment, honouring the {@code MSH} offset.
   *
   * @param id    the segment id (e.g. {@code "MSH"}, {@code "OBR"})
   * @param field the HL7 field number (e.g. {@code 9} for MSH-9)
   * @return the field text, or {@code ""} if absent
   */
  public String field(String id, int field) {
    return field(segment(id), id, field);
  }

  /**
   * Returns field <i>n</i> of a specific segment instance, honouring the {@code MSH} offset.
   *
   * @param seg   the segment fields array (as returned by {@link #segment(String)})
   * @param id    the segment id
   * @param field the HL7 field number
   * @return the field text, or {@code ""} if absent
   */
  public static String field(String[] seg, String id, int field) {
    if (seg == null) {
      return "";
    }
    int index = "MSH".equals(id) ? field - 1 : field;
    return (index >= 0 && index < seg.length) ? seg[index] : "";
  }

  /** @return the first component (before {@code ^}) of a field value. */
  public static String firstComponent(String field) {
    if (field == null || field.isEmpty()) {
      return "";
    }
    int caret = field.indexOf('^');
    return caret >= 0 ? field.substring(0, caret) : field;
  }

  /** @return the message type triplet from MSH-9, e.g. {@code "ORU^R01"} (trailing structure dropped). */
  public String messageType() {
    String msh9 = field("MSH", 9);
    String[] parts = msh9.split("\\^", -1);
    if (parts.length >= 2) {
      return parts[0] + "^" + parts[1];
    }
    return msh9;
  }

  /** @return the message control id (MSH-10). */
  public String controlId() {
    return field("MSH", 10);
  }
}
