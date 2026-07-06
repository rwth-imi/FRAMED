package com.framed.interop.mapping;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bidirectional mapping between FRAMED channels and {@link CodedConcept}s, loaded from a JSON file.
 *
 * <p>Keys are channel identifiers in one of two forms, resolved most-specific first:</p>
 * <ul>
 *   <li>{@code "<className>.<deviceID>.<channelID>"} — device-specific</li>
 *   <li>{@code "<className>.<channelID>"} — applies to any device</li>
 * </ul>
 *
 * <p>The reverse index ({@code code -> channel}) is used for inbound HL7: a received observation's
 * code is mapped back to the FRAMED channel coordinates it should be published under.</p>
 */
public final class ObservationMapping {

  /**
   * A resolved FRAMED channel coordinate decoded from a mapping key.
   *
   * @param className the channel class name
   * @param deviceID  the producing device id, or {@code null} for a device-agnostic key
   * @param channelID the channel id
   */
  public record Channel(String className, String deviceID, String channelID) {}

  private final Map<String, CodedConcept> byKey = new HashMap<>();
  private final Map<String, Channel> codeToChannel = new HashMap<>();

  private ObservationMapping() {}

  /**
   * Loads a mapping from the given JSON file.
   *
   * @param path path to the mapping JSON (object of {@code channelKey -> concept})
   * @return the loaded mapping
   * @throws IOException if the file cannot be read
   */
  public static ObservationMapping load(Path path) throws IOException {
    return fromJson(new JSONObject(Files.readString(path)));
  }

  /**
   * Builds a mapping from an already-parsed JSON object (testing-friendly).
   *
   * @param json object of {@code channelKey -> { code, system, display, unit, valueType }}
   * @return the constructed mapping
   */
  public static ObservationMapping fromJson(JSONObject json) {
    ObservationMapping m = new ObservationMapping();
    for (String key : json.keySet()) {
      JSONObject c = json.getJSONObject(key);
      CodedConcept concept = new CodedConcept(
          c.getString("code"),
          c.optString("system", "LOINC"),
          c.optString("display", ""),
          c.optString("unit", ""),
          c.optString("valueType", "NM"));
      m.byKey.put(key, concept);
      // First mapping wins for a given code on the reverse path.
      m.codeToChannel.putIfAbsent(concept.code(), parseKey(key));
    }
    return m;
  }

  private static Channel parseKey(String key) {
    String[] parts = key.split("\\.");
    if (parts.length >= 3) {
      return new Channel(parts[0], parts[1], parts[2]);
    }
    if (parts.length == 2) {
      return new Channel(parts[0], null, parts[1]);
    }
    return new Channel("", null, key);
  }

  /**
   * Looks up the concept for a FRAMED channel, most-specific key first.
   *
   * @param className the channel's class name
   * @param deviceID  the producing device id
   * @param channelID the channel id
   * @return the concept, or empty if the channel is not mapped
   */
  public Optional<CodedConcept> lookup(String className, String deviceID, String channelID) {
    CodedConcept c = byKey.get(className + "." + deviceID + "." + channelID);
    if (c == null) {
      c = byKey.get(className + "." + channelID);
    }
    if (c == null) {
      c = byKey.get(channelID);
    }
    return Optional.ofNullable(c);
  }

  /**
   * Reverse-maps an incoming code to the FRAMED channel it should be published under.
   *
   * @param code the observation code (e.g. LOINC)
   * @return the channel coordinates, or empty if the code is unknown
   */
  public Optional<Channel> channelForCode(String code) {
    return Optional.ofNullable(codeToChannel.get(code));
  }

  /** @return the number of mapped channels. */
  public int size() {
    return byKey.size();
  }
}
