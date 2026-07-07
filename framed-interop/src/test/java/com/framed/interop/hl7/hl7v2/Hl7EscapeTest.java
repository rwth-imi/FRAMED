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
}
