package com.framed.interop.hl7.hl7v2;

/**
 * HL7 v2 escaping of reserved characters (HL7 v2.5 §2.7), for embedding free-form data into
 * message fields built with the default encoding characters ({@code |^~\&}). Without escaping, a
 * value containing e.g. {@code |} shifts every subsequent field of its segment, and a value
 * containing CR/LF injects bogus segment breaks — either way corrupting the message for the
 * receiver.
 *
 * <p>{@link #field}/{@link #components} encode outbound data; {@link #unescape} decodes inbound
 * data, so values round-trip across the HL7 boundary. Unknown escape sequences (e.g. formatting
 * escapes like {@code \.br\}) are preserved verbatim by the decoder rather than dropped.</p>
 */
public final class Hl7Escape {

  private Hl7Escape() {}

  /**
   * Escapes all reserved characters, for data that occupies a whole field or component:
   * {@code \ -> \E\}, {@code | -> \F\}, {@code ^ -> \S\}, {@code ~ -> \R\}, {@code & -> \T\},
   * and the segment/line breaks {@code CR -> \X0D\}, {@code LF -> \X0A\} (hex escape, §2.7.4).
   *
   * @param text the raw text (may be {@code null})
   * @return the escaped text; {@code ""} for {@code null}
   */
  public static String field(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    // Backslash first: it is the escape character itself.
    return text.replace("\\", "\\E\\")
        .replace("|", "\\F\\")
        .replace("^", "\\S\\")
        .replace("~", "\\R\\")
        .replace("&", "\\T\\")
        .replace("\r", "\\X0D\\")
        .replace("\n", "\\X0A\\");
  }

  /**
   * Escapes each {@code ^}-separated component of an already component-structured value (e.g. a
   * configured PV1-3 location such as {@code "ICU^01^A"}), preserving the component separators
   * themselves.
   *
   * @param structured the component-structured text (may be {@code null})
   * @return the text with every component escaped; {@code ""} for {@code null}
   */
  public static String components(String structured) {
    if (structured == null || structured.isEmpty()) {
      return "";
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

  /**
   * Decodes the escape sequences produced by {@link #field} (delimiter escapes {@code \E\ \F\
   * \S\ \R\ \T\} and hex escapes {@code \Xhh..\}) back to the original text. Any other sequence —
   * unknown codes such as formatting escapes ({@code \.br\}), malformed hex, or a dangling
   * backslash — is preserved verbatim, so decoding is lossless on nonconforming input.
   *
   * @param text the escaped text (may be {@code null})
   * @return the decoded text; {@code ""} for {@code null}
   */
  public static String unescape(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    if (text.indexOf('\\') < 0) {
      return text; // fast path: nothing escaped
    }
    StringBuilder sb = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c != '\\') {
        sb.append(c);
        i++;
        continue;
      }
      int end = text.indexOf('\\', i + 1);
      if (end < 0) {
        sb.append(text, i, text.length()); // dangling backslash: keep verbatim
        break;
      }
      String decoded = decode(text.substring(i + 1, end));
      if (decoded != null) {
        sb.append(decoded);
      } else {
        sb.append(text, i, end + 1); // unknown sequence: keep verbatim, delimiters included
      }
      i = end + 1;
    }
    return sb.toString();
  }

  /** @return the decoded text of one escape token (without its backslashes), or {@code null} if unknown */
  private static String decode(String token) {
    return switch (token) {
      case "E" -> "\\";
      case "F" -> "|";
      case "S" -> "^";
      case "R" -> "~";
      case "T" -> "&";
      default -> token.length() > 1 && token.charAt(0) == 'X' ? decodeHex(token.substring(1)) : null;
    };
  }

  /** @return the characters encoded by a hex-pair sequence, or {@code null} if not valid hex pairs */
  private static String decodeHex(String hex) {
    if (hex.length() % 2 != 0) {
      return null;
    }
    StringBuilder out = new StringBuilder(hex.length() / 2);
    for (int i = 0; i < hex.length(); i += 2) {
      int hi = Character.digit(hex.charAt(i), 16);
      int lo = Character.digit(hex.charAt(i + 1), 16);
      if (hi < 0 || lo < 0) {
        return null;
      }
      out.append((char) ((hi << 4) | lo));
    }
    return out.toString();
  }
}
