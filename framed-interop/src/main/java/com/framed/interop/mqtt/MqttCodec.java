package com.framed.interop.mqtt;

import com.framed.core.utils.Timer;
import com.framed.interop.mapping.CodedConcept;
import com.framed.io.dispatch.DataPoint;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Encodes/decodes the self-describing JSON payload carried on MQTT.
 *
 * <p>A payload always carries the EAV coordinates ({@code value}, {@code timestamp},
 * {@code channelID}, {@code deviceID}, {@code className}); when the channel is mapped it also
 * carries the coded concept ({@code code}, {@code system}, {@code unit}) so the stream is
 * interoperable without an out-of-band mapping.</p>
 */
public final class MqttCodec {

  private MqttCodec() {}

  /**
   * Encodes a datapoint (with optional coded concept) to a JSON payload.
   *
   * @param dp      the datapoint
   * @param concept the coded concept, or {@code null} if the channel is unmapped
   * @return the UTF-8 JSON payload bytes
   */
  public static byte[] encode(DataPoint<?> dp, CodedConcept concept) {
    JSONObject o = new JSONObject();
    o.put("timestamp", LocalDateTime.ofInstant(dp.timestamp(), ZoneOffset.UTC).format(Timer.formatter));
    o.put("value", dp.value());
    o.put("channelID", dp.channelID());
    o.put("deviceID", dp.deviceID());
    o.put("className", dp.className());
    if (concept != null) {
      o.put("code", concept.code());
      o.put("system", concept.system());
      if (!concept.unit().isEmpty()) {
        o.put("unit", concept.unit());
      }
    }
    return o.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Decodes a JSON payload.
   *
   * @param payload the UTF-8 JSON payload bytes
   * @return the parsed object
   */
  public static JSONObject decode(byte[] payload) {
    return new JSONObject(new String(payload, StandardCharsets.UTF_8));
  }
}
