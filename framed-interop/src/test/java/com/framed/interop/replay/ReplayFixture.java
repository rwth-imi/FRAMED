package com.framed.interop.replay;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.utils.Timer;
import com.framed.interop.mapping.ObservationMapping;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-side replayer for recorded FRAMED streamer output ({@code JSON-Lines} of on-bus
 * {@code DataPoint} messages), mirroring the publish mechanism of the production
 * {@code ReplayProtocol} in {@code framed-communicator} (announce all addresses first, then
 * publish each event to {@code "<className>.<deviceID>.<channelID>.parsed"}) — re-implemented
 * here because a test dependency on {@code framed-communicator} would add a forbidden
 * leaf→leaf module edge.
 *
 * <p>The fixture ({@value #RECORDING}) is the recording the replay deployment configs use as
 * well: one run from the curated bench-ventilation dataset (proband {@code p01},
 * volume-controlled ventilation, fault-free condition) — pure device telemetry
 * (Oxylog-3000-Plus-00 ventilator, PC60FW pulse oximeter, a per-second annotation channel), no
 * personal data. Its Oxylog channel ids were normalized from the recording-time full-description
 * naming to the ids the current Medibus driver derives (see {@code ProtocolMap} in
 * {@code framed-communicator}), so the deployment mapping {@code config/interop-mapping.json}
 * applies. It contains both mapped channels and deliberately unmapped ones — high-rate
 * {@code RealTime} waveforms, text messages, the annotation channel, and the same channel names
 * under an unmapped class ({@code Settings.FiO2}, {@code Measurement.RR}) — so simulations can
 * assert that interop bridges emit exactly the mapped subset.</p>
 *
 * <p>Unlike the production replayer, events are published without real-time pacing (tests must be
 * fast) and keep their <em>original</em> recorded timestamps, so timestamp fidelity across an
 * interop boundary can be asserted.</p>
 */
public final class ReplayFixture {

  /** Repo-relative path of the replayed recording, shared with the replay deployment configs. */
  public static final String RECORDING = "data/replay/p01-VC1-clean-0.jsonl";

  private ReplayFixture() {}

  /** One recorded on-bus datapoint. */
  public record Event(Instant timestamp, String className, String deviceID, String channelID,
                      Object value) {

    /** @return the bus address the event is published to */
    public String address() {
      return "%s.%s.%s.parsed".formatted(className, deviceID, channelID);
    }

    /** @return the event as the canonical on-bus JSON message (timestamp in bus format, UTC) */
    public JSONObject toBusMessage() {
      return new JSONObject()
          .put("timestamp", LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC).format(Timer.formatter))
          .put("channelID", channelID)
          .put("value", value)
          .put("className", className);
    }
  }

  private static volatile List<Event> cached;

  /**
   * Loads the recording ({@value #RECORDING}) in recorded (timestamp) order. The recording is
   * immutable, so the parsed events are cached: repeated calls across tests do not re-read the
   * multi-megabyte file.
   *
   * @return the recorded events (unmodifiable)
   */
  public static List<Event> load() {
    List<Event> events = cached;
    if (events == null) {
      events = List.copyOf(read());
      cached = events;
    }
    return events;
  }

  private static List<Event> read() {
    try (BufferedReader br = Files.newBufferedReader(resolve(RECORDING), StandardCharsets.UTF_8)) {
      List<Event> events = new ArrayList<>();
      String line;
      while ((line = br.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JSONObject o = new JSONObject(line);
        events.add(new Event(Instant.parse(o.getString("timestamp")), o.getString("className"),
            o.getString("deviceID"), o.getString("channelID"), o.get("value")));
      }
      return events;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The subset of {@code events} the HL7 and MQTT bridges must emit: channels present in
   * {@code mapping} with a non-waveform kind (waveform-kind channels belong to the SDC bridge
   * and are skipped by HL7/MQTT). Both replay simulations partition through this method (and
   * {@link #unmapped}) so they cannot diverge on what counts as mapped.
   *
   * @param events  the recorded events
   * @param mapping the deployment mapping
   * @return the emitted events, in input order
   */
  public static List<Event> mapped(List<Event> events, ObservationMapping mapping) {
    return events.stream()
        .filter(e -> mapping.lookup(e.className(), e.deviceID(), e.channelID())
            .filter(c -> !c.isWaveform()).isPresent())
        .toList();
  }

  /**
   * The complement of {@link #mapped}: the control group that must <em>not</em> cross the HL7 or
   * MQTT boundary (unmapped channels plus waveform-kind mappings).
   *
   * @param events  the recorded events
   * @param mapping the deployment mapping
   * @return the non-emitted events, in input order
   */
  public static List<Event> unmapped(List<Event> events, ObservationMapping mapping) {
    return events.stream()
        .filter(e -> mapping.lookup(e.className(), e.deviceID(), e.channelID())
            .filter(c -> !c.isWaveform()).isEmpty())
        .toList();
  }

  /**
   * Announces every event address under its device group, exactly as a device driver would, so
   * sinks bound to those groups subscribe. On a {@code PER_HANDLER} bus, give handlers a moment
   * to register before {@link #publish publishing}.
   *
   * @param bus    the bus to announce on
   * @param events the events whose addresses to announce
   */
  public static void announce(EventBus bus, List<Event> events) {
    Map<String, Set<String>> byDevice = new HashMap<>();
    for (Event ev : events) {
      byDevice.computeIfAbsent(ev.deviceID(), d -> new HashSet<>()).add(ev.address());
    }
    for (Map.Entry<String, Set<String>> e : byDevice.entrySet()) {
      for (String address : e.getValue()) {
        bus.publish(Service.addressRegistry(e.getKey()), address);
      }
    }
  }

  /**
   * Publishes every event in recorded order as the canonical on-bus JSON message.
   *
   * @param bus    the bus to publish on
   * @param events the events to publish
   */
  public static void publish(EventBus bus, List<Event> events) {
    for (Event ev : events) {
      bus.publish(ev.address(), ev.toBusMessage());
    }
  }

  /**
   * Resolves the real deployment mapping {@code config/interop-mapping.json}, so simulations
   * exercise the mapping that actually ships.
   *
   * @return the path to {@code config/interop-mapping.json}
   */
  public static Path deploymentMapping() {
    return resolve("config/interop-mapping.json");
  }

  /**
   * Resolves a repo-relative path by walking up from the working directory (module dir under
   * Maven, repo root elsewhere).
   *
   * @param relative the repo-relative path
   * @return the resolved existing path
   */
  private static Path resolve(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        relative + " not found above " + Path.of("").toAbsolutePath());
  }
}