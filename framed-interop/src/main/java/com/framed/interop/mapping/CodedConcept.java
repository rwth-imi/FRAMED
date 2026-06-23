package com.framed.interop.mapping;

/**
 * A coded clinical concept: how a FRAMED channel is identified in an interoperability standard.
 *
 * @param code      the concept code (e.g. a LOINC code such as {@code "59408-5"})
 * @param system    the code system (e.g. {@code "LOINC"})
 * @param display   human-readable display name
 * @param unit      unit of measure (UCUM, e.g. {@code "%"}, {@code "mm[Hg]"}); may be empty
 * @param valueType HL7 v2 value type for the OBX-2 field ({@code "NM"} numeric, {@code "ST"} string)
 */
public record CodedConcept(String code, String system, String display, String unit, String valueType) {

  /** Defaults a blank {@code valueType} to {@code "NM"} and a null {@code system} to {@code "LOINC"}. */
  public CodedConcept {
    if (valueType == null || valueType.isBlank()) {
      valueType = "NM";
    }
    if (system == null || system.isBlank()) {
      system = "LOINC";
    }
    if (unit == null) {
      unit = "";
    }
  }

  /** @return {@code true} if this concept is numeric (OBX-2 = {@code NM}). */
  public boolean isNumeric() {
    return "NM".equalsIgnoreCase(valueType);
  }
}
