package com.framed.interop.hl7.hl7v2;

/**
 * HL7 v2 escaping of the five reserved delimiter characters (HL7 v2.5 §2.7), for embedding
 * free-form data into message fields built with the default encoding characters
 * ({@code |^~\&}). Without escaping, a value containing e.g. {@code |} shifts every subsequent
 * field of its segment and corrupts the message for the receiver.
 */
public final class Hl7Escape {

  private Hl7Escape() {}

  /**
   * Escapes all five reserved characters, for data that occupies a whole field or component:
   * {@code \ -> \E\}, {@code | -> \F\}, {@code ^ -> \S\}, {@code ~ -> \R\}, {@code & -> \T\}.
   *
   * @param text the raw text (may be {@code null})
   * @return the escaped text, or {@code ""} for {@code null}
   */
  public static String field(String text) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    // Backslash first: it is the escape character itself.
    return text.replace("\\", "\\E\\")
        .replace("|", "\\F\\")
        .replace("^", "\\S\\")
        .replace("~", "\\R\\")
        .replace("&", "\\T\\");
  }

  /**
   * Escapes each {@code ^}-separated component of an already component-structured value (e.g. a
   * configured PV1-3 location such as {@code "ICU^01^A"}), preserving the component separators
   * themselves.
   *
   * @param structured the component-structured text (may be {@code null})
   * @return the text with every component escaped, or {@code ""} for {@code null}
   */
  public static String components(String structured) {
    if (structured == null || structured.isEmpty()) {
      return structured == null ? "" : structured;
    }
    String[] parts = structured.split("\\^", -1);
    StringBuilder sb = new StringBuilder(structured.length());
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        sb.append('^');
      }
      sb.append(field(parts[i]));
    }
    return sb.toString();
  }
}
