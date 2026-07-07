package com.framed.interop.replay;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.utils.Timer;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.Objects;
import java.util.Set;

/**
 * Test-side replayer for recorded FRAMED streamer output ({@code JSON-Lines} of on-bus
 * {@code DataPoint} messages), mirroring the publish mechanism of the production
 * {@code ReplayProtocol} in {@code framed-communicator} (announce all addresses first, then
 * publish each event to {@code "<className>.<deviceID>.<channelID>.parsed"}) — re-implemented
 * here because a test dependency on {@code framed-communicator} would add a forbidden
 * leaf→leaf module edge.
 *
 * <p>The bundled fixture ({@value #RESOURCE}) is an excerpt of a real bench ventilation
 * recording ({@code vc_cmv_ph_03032026.jsonl}): pure device telemetry (Oxylog-3000-Plus-00
 * ventilator, PC60FW pulse oximeter, CDSS-derived channels), no personal data. It contains both
 * channels present in the deployment mapping {@code config/interop-mapping.json} and deliberately
 * unmapped ones — high-rate {@code RealTime} waveforms, CDSS output, and the same channel names
 * under an unmapped class ({@code Settings.FiO2}, {@code Measurement.RR}) — so simulations can
 * assert that interop bridges emit exactly the mapped subset.</p>
 *
 * <p>Unlike the production replayer, events are published without real-time pacing (tests must be
 * fast) and keep their <em>original</em> recorded timestamps, so timestamp fidelity across an
 * interop boundary can be asserted.</p>
 */
public final class ReplayFixture {

  /** Classpath location of the bundled replay excerpt. */
  public static final String RESOURCE = "/replay/vc_cmv_excerpt.jsonl";

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

  /**
   * Loads the bundled fixture in recorded (timestamp) order.
   *
   * @return the recorded events
   */
  public static List<Event> load() {
    try (BufferedReader br = new BufferedReader(new InputStreamReader(
        Objects.requireNonNull(ReplayFixture.class.getResourceAsStream(RESOURCE),
            RESOURCE + " missing from test resources"), StandardCharsets.UTF_8))) {
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
   * Resolves the real deployment mapping {@code config/interop-mapping.json} by walking up from
   * the working directory (module dir under Maven, repo root elsewhere), so simulations exercise
   * the mapping that actually ships.
   *
   * @return the path to {@code config/interop-mapping.json}
   */
  public static Path deploymentMapping() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve("config").resolve("interop-mapping.json");
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "config/interop-mapping.json not found above " + Path.of("").toAbsolutePath());
  }
}