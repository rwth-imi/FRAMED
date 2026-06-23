package com.framed.interop.hl7.hl7v2;

import org.json.JSONObject;

/**
 * The MSH sending/receiving application and facility identifiers (MSH-3..MSH-6).
 *
 * @param sendingApp        MSH-3
 * @param sendingFacility   MSH-4
 * @param receivingApp      MSH-5
 * @param receivingFacility MSH-6
 */
public record SendingIds(String sendingApp, String sendingFacility,
                         String receivingApp, String receivingFacility) {

  /** Builds from a config JSON object, defaulting to {@code FRAMED}/{@code HIS} identifiers. */
  public static SendingIds fromJson(JSONObject json) {
    if (json == null) {
      json = new JSONObject();
    }
    return new SendingIds(
        json.optString("sendingApp", "FRAMED"),
        json.optString("sendingFacility", "FRAMED"),
        json.optString("receivingApp", "HIS"),
        json.optString("receivingFacility", "HOSPITAL"));
  }

  /** @return the same identifiers with sender and receiver swapped (for building an ACK). */
  public SendingIds swapped() {
    return new SendingIds(receivingApp, receivingFacility, sendingApp, sendingFacility);
  }
}
