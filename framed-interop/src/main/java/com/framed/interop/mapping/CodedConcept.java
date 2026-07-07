package com.framed.interop.mapping;

/**
 * A coded clinical concept: how a FRAMED channel is identified in an interoperability standard.
 *
 * @param code      the concept code (e.g. a LOINC code such as {@code "59408-5"})
 * @param system    the code system (e.g. {@code "LOINC"})
 * @param display   human-readable display name
 * @param unit      unit of measure (UCUM, e.g. {@code "%"}, {@code "mm[Hg]"}); may be empty
 * @param valueType HL7 v2 value type for the OBX-2 field ({@code "NM"} numeric, {@code "ST"} string)
 * @param mdc       IEEE 11073-10101 nomenclature code for SDC (empty if not assigned; SDC
 *                  descriptors carry a coded type only when this is set)
 * @param kind      what the channel is at the SDC boundary — determines the BICEPS descriptor
 *                  ({@code METRIC}/{@code SETTING} → numeric metric, {@code WAVEFORM} →
 *                  real-time sample array) and excludes waveforms from the HL7/MQTT bridges
 */
public record CodedConcept(String code, String system, String display, String unit,
                           String valueType, String mdc, Kind kind) {

  /** Channel kind at the SDC boundary (mapping JSON field {@code kind}, lowercase). */
  public enum Kind {
    /** A discrete measurement (BICEPS metric category {@code MSRMT}). */
    METRIC,
    /** A high-rate waveform (BICEPS real-time sample array); HL7/MQTT skip these. */
    WAVEFORM,
    /** A device setting (BICEPS metric category {@code SET}). */
    SETTING
  }

  /**
   * Defaults a blank {@code valueType} to {@code "NM"}, a null {@code system} to {@code "LOINC"},
   * a null {@code mdc} to empty and a null {@code kind} to {@link Kind#METRIC}.
   */
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
    if (mdc == null) {
      mdc = "";
    }
    if (kind == null) {
      kind = Kind.METRIC;
    }
  }

  /**
   * Convenience constructor for concepts without SDC attributes ({@code mdc} empty,
   * {@code kind} {@link Kind#METRIC}).
   *
   * @param code      the concept code
   * @param system    the code system
   * @param display   the display name
   * @param unit      the unit of measure
   * @param valueType the HL7 v2 value type
   */
  public CodedConcept(String code, String system, String display, String unit, String valueType) {
    this(code, system, display, unit, valueType, "", Kind.METRIC);
  }

  /** @return {@code true} if this concept is numeric (OBX-2 = {@code NM}). */
  public boolean isNumeric() {
    return "NM".equalsIgnoreCase(valueType);
  }

  /** @return {@code true} if this channel is a high-rate waveform (see {@link Kind#WAVEFORM}). */
  public boolean isWaveform() {
    return kind == Kind.WAVEFORM;
  }
}
