package com.framed.communicator.driver.protocol.medibus.utils;

/**
 * A single Medibus(.X) protocol parameter: the mapping from a one-byte protocol code to a stable,
 * address-safe channel identifier plus human-readable metadata.
 *
 * <p>The {@link #id()} is what flows downstream as the {@code channelID} (and therefore into bus
 * addresses {@code "<className>.<deviceID>.<channelID>.parsed"}), so it is kept short and stable
 * (e.g. {@code "etCO2"}) rather than the verbose description it is derived from.</p>
 *
 * @param code  the raw Medibus data code (the map key this parameter is stored under)
 * @param id    stable short channel identifier used as {@code channelID} (e.g. {@code "etCO2"})
 * @param label human-readable description (e.g. {@code "End-tidal CO2 concentration"})
 * @param unit  best-effort unit where the protocol description makes it explicit
 *              (e.g. {@code "mmHg"}); may be empty
 */
public record MedibusParam(byte code, String id, String label, String unit) {}
