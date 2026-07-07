package com.framed.interop.hl7.hl7v2;

import org.json.JSONObject;

/**
 * Static patient/encounter context used to populate the {@code PID}/{@code PV1} segments of
 * outbound messages. In this first iteration it is supplied from configuration; later it can be
 * refreshed from inbound ADT.
 *
 * @param mrn       medical record number (PID-3)
 * @param lastName  family name (PID-5)
 * @param firstName given name (PID-5)
 * @param dob       date of birth, HL7 {@code yyyyMMdd} (PID-7); may be empty
 * @param sex       administrative sex (PID-8); may be empty
 * @param location  patient location / point of care (PV1-3); may be empty
 */
public record PatientContext(String mrn, String lastName, String firstName,
                             String dob, String sex, String location) {

  /** Builds a context from a config JSON object, defaulting missing fields to empty/UNKNOWN. */
  public static PatientContext fromJson(JSONObject json) {
    if (json == null) {
      json = new JSONObject();
    }
    return new PatientContext(
        json.optString("mrn", "UNKNOWN"),
        json.optString("lastName", "UNKNOWN"),
        json.optString("firstName", ""),
        json.optString("dob", ""),
        json.optString("sex", ""),
        json.optString("location", ""));
  }
}
