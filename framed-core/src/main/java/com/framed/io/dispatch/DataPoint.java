// SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.framed.io.dispatch;

import java.time.Instant;

import org.json.JSONObject;

/**
 * An immutable, timestamped value sample produced by a device channel or a reactor.
 *
 * <p>{@code (timestamp, microstep)} is the sample's logical tag (see {@code com.framed.arn.Tag}).
 * Devices and parsers produce microstep 0; a reactor produces {@code microstep >= 1} when it fires
 * more than once for the same timestamp.</p>
 *
 * @param <T>       the type of the carried value
 * @param timestamp the instant the value was observed
 * @param value     the observed value
 * @param channelID the identifier of the channel that produced the value
 * @param deviceID  the identifier of the device that produced the value
 * @param className the runtime class name describing the value's type
 * @param microstep the microstep of the sample's tag; never negative
 */
public record DataPoint<T>(Instant timestamp, T value, String channelID, String deviceID, String className,
                           long microstep) {

  /**
   * Validates the microstep.
   *
   * @throws IllegalArgumentException if {@code microstep} is negative
   */
  public DataPoint {
    if (microstep < 0) throw new IllegalArgumentException("microstep must be >= 0, was " + microstep);
  }

  /**
   * Creates a datapoint at microstep 0, the tag of every device or parser sample.
   *
   * @param timestamp the instant the value was observed
   * @param value     the observed value
   * @param channelID the identifier of the channel that produced the value
   * @param deviceID  the identifier of the device that produced the value
   * @param className the runtime class name describing the value's type
   */
  public DataPoint(Instant timestamp, T value, String channelID, String deviceID, String className) {
    this(timestamp, value, channelID, deviceID, className, 0L);
  }

  /**
   * Serializes this datapoint to its JSON string representation.
   *
   * @return a JSON string with {@code timestamp}, {@code microstep}, {@code value},
   *         {@code channelID}, {@code deviceID} and {@code className} fields
   */
  public String toJsonString() {
    JSONObject json = new JSONObject();
    json.put("timestamp", timestamp.toString());
    json.put("microstep", microstep);
    json.put("value", value);
    json.put("channelID", channelID);
    json.put("deviceID", deviceID);
    json.put("className", className);
    return json.toString();
  }
}
