package com.framed.interop.hl7.hl7v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Hl7EscapeTest {

  @Test
  void escapesAllFiveReservedCharacters() {
    assertEquals("a\\F\\b", Hl7Escape.field("a|b"));
    assertEquals("a\\S\\b", Hl7Escape.field("a^b"));
    assertEquals("a\\R\\b", Hl7Escape.field("a~b"));
    assertEquals("a\\T\\b", Hl7Escape.field("a&b"));
    assertEquals("a\\E\\b", Hl7Escape.field("a\\b"));
  }

  @Test
  void escapesBackslashBeforeTheOtherSequences() {
    // A literal "\F\" in the data must not survive as an escape sequence: the backslashes are
    // escaped first, so the receiver decodes it back to the literal three characters.
    assertEquals("\\E\\F\\E\\", Hl7Escape.field("\\F\\"));
  }

  @Test
  void plainTextAndNullHandling() {
    assertEquals("etCO2 38 mm[Hg]", Hl7Escape.field("etCO2 38 mm[Hg]"), "no reserved chars: unchanged");
    assertEquals("", Hl7Escape.field(null));
    assertEquals("", Hl7Escape.field(""));
    assertEquals("", Hl7Escape.components(null));
  }

  @Test
  void componentsPreserveSeparatorsButEscapeWithin() {
    assertEquals("ICU^01^A", Hl7Escape.components("ICU^01^A"));
    assertEquals("ICU^0\\F\\1^A\\T\\B", Hl7Escape.components("ICU^0|1^A&B"));
    assertEquals("^x^", Hl7Escape.components("^x^"), "empty components survive");
  }

  @Test
  void escapesSegmentBreaks() {
    // Unescaped CR/LF splits the segment inside the MLLP frame — an injection, not a delimiter shift.
    assertEquals("a\\X0D\\b", Hl7Escape.field("a\rb"));
    assertEquals("a\\X0A\\b", Hl7Escape.field("a\nb"));
  }

  @Test
  void unescapeInvertsField() {
    for (String raw : new String[]{"A|B", "\\F\\", "a^b~c&d", "line1\r\nline2", "plain text", "|^~\\&"}) {
      assertEquals(raw, Hl7Escape.unescape(Hl7Escape.field(raw)),
          "field/unescape must round-trip: " + raw);
    }
  }

  @Test
  void unescapePreservesUnknownSequencesVerbatim() {
    assertEquals("\\H\\bold\\N\\", Hl7Escape.unescape("\\H\\bold\\N\\"), "formatting escapes untouched");
    assertEquals("a\\Fb", Hl7Escape.unescape("a\\Fb"), "dangling backslash untouched");
    assertEquals("\\XZZ\\", Hl7Escape.unescape("\\XZZ\\"), "malformed hex untouched");
    assertEquals("", Hl7Escape.unescape(null));
  }
}
