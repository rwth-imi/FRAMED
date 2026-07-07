package com.framed.interop.hl7;

import java.time.Instant;

/**
 * Sink for observations decoded from inbound HL7, expressed in FRAMED's EAV coordinates. The
 * implementation publishes them onto the event bus in the {@code "<className>.<deviceID>.<channelID>.parsed"}
 * convention.
 */
@FunctionalInterface
public interface ObservationSink {

  /**
   * Accepts one decoded observation.
   *
   * @param className the channel class name
   * @param deviceID  the producing device id
   * @param channelID the channel id
   * @param value     the value ({@link Double} for numeric, {@link String} otherwise)
   * @param timestamp the observation time
   */
  void accept(String className, String deviceID, String channelID, Object value, Instant timestamp);
}
