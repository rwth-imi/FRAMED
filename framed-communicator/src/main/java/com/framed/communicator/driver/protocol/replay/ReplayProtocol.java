package com.framed.communicator.driver.protocol.replay;

import com.framed.core.EventBus;
import com.framed.io.protocol.Protocol;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replays a recorded {@code .jsonl} session onto the event bus, on the parsed addresses the
 * original parsers used.
 *
 * <p>A verification and measurement harness rather than a clinical data source: it drives a
 * deployment from a real recording, so reactors and sinks can be exercised end to end without
 * hardware. Each line of the recording is republished on
 * {@code "<className>.<deviceID>.<channelID>.parsed"} with the same four-field envelope a parser
 * emits.</p>
 *
 * <h2>Replay clock</h2>
 * <p>The recording's <em>relative</em> timing is what the replay preserves, not its absolute dates.
 * For an event recorded at {@code t} the scheduled emit time is</p>
 * <pre>{@code   emit(t) = replayStart + (t - firstRecordedTimestamp) / speed}</pre>
 * <p>computed in nanoseconds and stamped at microsecond resolution — the resolution
 * {@code Timer.formatter} renders — so a recording timestamped to the microsecond is reproduced to
 * the microsecond. Pacing itself is only as fine as {@code Thread.sleep}, but the stamp does not
 * inherit that coarseness.</p>
 * <p>and that scheduled instant is also the {@code timestamp} the event carries on the bus. Two
 * consequences make this the load-bearing design decision of the class:</p>
 * <ul>
 *   <li><b>Timestamp-sensitive logic sees the recording it was recorded from.</b> At
 *       {@code speed = 1.0} the emitted stamps are the recorded stamps shifted by a constant, so
 *       every inter-sample interval — and every set of samples that shared one device frame
 *       timestamp — is reproduced exactly. Reactors whose windows, staleness timers and
 *       logical-time gates key off those stamps therefore behave as they did on the original
 *       session. Stamping with {@code Instant.now()} instead, as this class previously did, splits
 *       a shared frame timestamp into distinct ones and shifts every window edge by the scheduling
 *       jitter, which changes <em>which</em> results a reactor network produces.</li>
 *   <li><b>Downstream latency stays measurable.</b> The stamps are contemporaneous with the run, so
 *       {@code now − timestamp} at a sink is an emit&rarr;sink latency (for a sink sharing the
 *       producer's clock) rather than the age of the recording.</li>
 * </ul>
 * <p>The stamp is the <em>scheduled</em> emit time, not the actual one, so any lag of the replay
 * thread itself is included in that latency instead of being hidden by re-stamping. The lag is
 * reported separately by {@link #stats()} and in the summary logged at the end of the replay.</p>
 *
 * <p>At {@code speed <= 0} the recording is replayed as fast as it can be published; there is then
 * no schedule, and each event is stamped with the instant it is actually published. That mode
 * measures throughput, and deliberately does not reproduce the recording's timing.</p>
 *
 * <h2>Configuration keys</h2>
 * <ul>
 *   <li>{@code filePath} &mdash; the {@code .jsonl} recording to replay.</li>
 *   <li>{@code devices} &mdash; device identifiers to replay; other devices in the file are skipped.</li>
 *   <li>{@code channels} &mdash; channel identifiers to replay; empty replays every channel.</li>
 *   <li>{@code speed} &mdash; wall-clock speed multiplier ({@code 1.0} = real time, {@code 0} = as
 *       fast as possible).</li>
 *   <li>{@code startDelaySeconds} &mdash; delay before the first event, leaving remote peers and
 *       sinks time to come up.</li>
 *   <li>{@code graceSeconds} &mdash; how long to keep running after the last event, so downstream
 *       processing can finish.</li>
 *   <li>{@code exitOnCompletion} &mdash; terminate the JVM after the grace period, which lets the
 *       launcher's shutdown hook flush and summarise the sinks. Set {@code false} when the replay
 *       is one service among others that should outlive it.</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #connect()} starts one daemon-less replay thread; every publication
 * happens on it. {@link #stop()} interrupts that thread and returns without waiting for the
 * remaining events.</p>
 */
public class ReplayProtocol extends Protocol {

  private static final Logger LOGGER = Logger.getLogger(ReplayProtocol.class.getName());

  /** Settle time between announcing the addresses and publishing the first sample. */
  private static final long ANNOUNCE_SETTLE_MILLIS = 100L;

  private final Path filePath;
  private final Set<String> devices = new HashSet<>();
  private final Set<String> channels = new HashSet<>();
  private final double speed;
  private final long startDelayMillis;
  private final long graceMillis;
  private final boolean exitOnCompletion;

  private volatile Thread replayThread;
  private volatile ReplayStats stats = ReplayStats.EMPTY;
  /** Published so far; readable while the replay is still running, unlike {@link #stats}. */
  private volatile long published;

  /**
   * Creates a replay of {@code filePath}. Parameter names match the JSON configuration keys
   * resolved by {@code Factory}; the {@link EventBus} is injected automatically.
   *
   * @param id                service id
   * @param eventBus          the bus to replay onto
   * @param filePath          path to the {@code .jsonl} recording
   * @param devices           device identifiers to replay; others in the file are skipped
   * @param channels          channel identifiers to replay; empty replays every channel
   * @param speed             wall-clock speed multiplier; {@code <= 0} replays as fast as possible
   * @param startDelaySeconds delay before the first event is published
   * @param graceSeconds      how long to keep running after the last event
   * @param exitOnCompletion  whether to terminate the JVM once the grace period has elapsed
   */
  public ReplayProtocol(String id, EventBus eventBus, String filePath, JSONArray devices,
                        JSONArray channels, double speed, double startDelaySeconds,
                        double graceSeconds, boolean exitOnCompletion) {
    super(id, eventBus);
    this.filePath = Path.of(filePath);
    for (Object device : devices) {
      this.devices.add(String.valueOf(device));
    }
    for (Object channel : channels) {
      this.channels.add(String.valueOf(channel));
    }
    this.speed = speed;
    this.startDelayMillis = Math.max(0L, Math.round(startDelaySeconds * 1000.0));
    this.graceMillis = Math.max(0L, Math.round(graceSeconds * 1000.0));
    this.exitOnCompletion = exitOnCompletion;
    connect();
  }

  /** Starts the replay on its own thread; a second call while one is running does nothing. */
  @Override
  public synchronized void connect() {
    if (replayThread != null) return;
    Thread thread = new Thread(this::runReplay, "JSONL-Replay-%s".formatted(id));
    this.replayThread = thread;
    thread.start();
  }

  /** Interrupts the replay thread; does not wait for it to finish. */
  @Override
  public synchronized void stop() {
    Thread thread = this.replayThread;
    this.replayThread = null;
    if (thread != null) {
      thread.interrupt();
    }
    LOGGER.info("Replay stopped after %d events.".formatted(published));
  }

  /**
   * Returns how much the replay published and how well it held its schedule.
   *
   * <p>Safe to call from any thread at any time, but the figures are computed once the last event
   * has been published: before then this returns {@link ReplayStats#EMPTY}. Use
   * {@link #publishedEvents()} to observe progress during a run.</p>
   *
   * @return the pacing figures of this replay, or {@link ReplayStats#EMPTY} while it is still running
   */
  public ReplayStats stats() {
    return stats;
  }

  /**
   * Returns how many events have been published so far. Updated as the replay runs, so it is also
   * meaningful for a replay that was interrupted or is still in flight.
   *
   * @return the number of events published
   */
  public long publishedEvents() {
    return published;
  }

  /**
   * Blocks until the replay has published its last event.
   *
   * @param timeout how long to wait
   * @return {@code true} if the replay finished within {@code timeout}
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  public boolean awaitCompletion(Duration timeout) throws InterruptedException {
    Thread thread = this.replayThread;
    if (thread == null) return true;
    thread.join(timeout.toMillis());
    return !thread.isAlive();
  }

  private void runReplay() {
    try {
      if (!sleepMillis(startDelayMillis)) return;

      List<ReplayEvent> events = loadEvents(filePath);
      if (events.isEmpty()) {
        LOGGER.warning("Replay file contains no events for the configured devices/channels: %s"
                .formatted(filePath));
        return;
      }
      events.sort(Comparator.comparing(ReplayEvent::timestamp));
      LOGGER.info("Loaded %d events from %s; replaying at speed %s."
              .formatted(events.size(), filePath, speed <= 0 ? "max" : "%.3f×".formatted(speed)));

      announceAddresses(events);
      if (!sleepMillis(ANNOUNCE_SETTLE_MILLIS)) return;

      publishEvents(events);

      LOGGER.info("Replay finished: %s".formatted(stats));
      if (!sleepMillis(graceMillis)) return;
      if (exitOnCompletion) {
        System.exit(0);
      }
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Replay failed", e);
    }
  }

  /**
   * Announces every address the recording will publish on, once per address, before the first
   * sample. Announcing per event instead would double the message rate for no benefit — the
   * discovery handshake is idempotent, and sinks bind on the first announcement.
   */
  private void announceAddresses(List<ReplayEvent> events) {
    Map<String, Set<String>> addressesByDevice = new HashMap<>();
    for (ReplayEvent event : events) {
      addressesByDevice.computeIfAbsent(event.deviceID(), d -> new LinkedHashSet<>())
              .add(event.address());
    }
    addressesByDevice.forEach((device, addresses) ->
            addresses.forEach(address -> announceAddress(device, address)));
  }

  /** Publishes every event on its schedule, accumulating the pacing figures as it goes. */
  private void publishEvents(List<ReplayEvent> events) throws InterruptedException {
    Instant firstRecorded = events.get(0).timestamp();
    // Truncated to microseconds because that is the resolution Timer.formatter renders: a start
    // instant with nanoseconds would be rounded on the wire, and the recorded intervals — which
    // this recording carries to the microsecond — would no longer be reproduced exactly.
    Instant replayStart = Instant.now().truncatedTo(ChronoUnit.MICROS);
    long lagSumMillis = 0;
    long lagMaxMillis = Long.MIN_VALUE;
    long count = 0;
    this.published = 0;

    for (ReplayEvent event : events) {
      if (Thread.currentThread().isInterrupted()) break;

      Instant emit;
      if (speed > 0) {
        long offsetNanos = Math.round(
                Duration.between(firstRecorded, event.timestamp()).toNanos() / speed);
        emit = replayStart.plusNanos(offsetNanos);
        long delay = Duration.between(Instant.now(), emit).toMillis();
        if (delay > 0 && !sleepMillis(delay)) break;
      } else {
        emit = Instant.now().truncatedTo(ChronoUnit.MICROS);
      }

      long lag = Duration.between(emit, Instant.now()).toMillis();
      lagSumMillis += lag;
      lagMaxMillis = Math.max(lagMaxMillis, lag);
      count++;
      this.published = count;

      publishEvent(event, emit);
    }

    long wallMillis = Math.max(1L, Duration.between(replayStart, Instant.now()).toMillis());
    double recordedSpan = Duration.between(firstRecorded, events.get(events.size() - 1).timestamp())
            .toNanos() / 1e9;
    double targetHz = speed > 0 && recordedSpan > 0 ? events.size() / (recordedSpan / speed) : 0.0;
    stats = new ReplayStats(count, wallMillis / 1000.0, count * 1000.0 / wallMillis, targetHz,
            count == 0 ? 0.0 : (double) lagSumMillis / count,
            count == 0 ? 0L : lagMaxMillis);
  }

  private void publishEvent(ReplayEvent event, Instant emitTimestamp) {
    JSONObject parsed = new JSONObject();
    parsed.put("timestamp", ZonedDateTime.ofInstant(emitTimestamp, ZoneOffset.UTC).format(formatter));
    parsed.put("channelID", event.channelID());
    parsed.put("value", event.value());
    parsed.put("className", event.className());
    eventBus.publish(event.address(), parsed);
  }

  /** Sleeps, reporting whether the replay may continue. */
  private boolean sleepMillis(long millis) {
    if (millis <= 0) return !Thread.currentThread().isInterrupted();
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private List<ReplayEvent> loadEvents(Path path) throws IOException {
    if (!path.toString().endsWith(".jsonl")) {
      throw new IllegalArgumentException("Unsupported file type: %s".formatted(path));
    }

    List<ReplayEvent> events = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;

        JSONObject event = new JSONObject(line);
        String deviceID = event.optString("deviceID", "Unknown");
        String channelID = event.optString("channelID", "Unknown");
        if (!devices.isEmpty() && !devices.contains(deviceID)) continue;
        if (!channels.isEmpty() && !channels.contains(channelID)) continue;

        events.add(new ReplayEvent(Instant.parse(event.getString("timestamp")),
                event.optString("className", "Unknown"), deviceID, channelID, event.get("value")));
      }
    }
    return events;
  }

  /**
   * Figures describing one completed replay: how much was published, how fast, and how far behind
   * its own schedule the replay thread ran.
   *
   * @param events      number of events published
   * @param wallSeconds wall-clock duration of the replay
   * @param achievedHz  events published per wall-clock second
   * @param targetHz    events per second the schedule called for ({@code 0} when free-running)
   * @param meanLagMs   mean of {@code actualEmit − scheduledEmit}; negligible when the replay keeps pace
   * @param maxLagMs    largest single such lag
   */
  public record ReplayStats(long events, double wallSeconds, double achievedHz, double targetHz,
                            double meanLagMs, long maxLagMs) {

    /** The figures of a replay that has not published anything yet. */
    public static final ReplayStats EMPTY = new ReplayStats(0, 0, 0, 0, 0, 0);

    @Override
    public String toString() {
      return ("events=%d wall=%.1fs achieved=%.2f Hz target=%.2f Hz lag(mean/max)=%.1f/%d ms")
              .formatted(events, wallSeconds, achievedHz, targetHz, meanLagMs, maxLagMs);
    }
  }

  /** One recorded event, and the parsed address it is replayed on. */
  private record ReplayEvent(Instant timestamp, String className, String deviceID, String channelID,
                             Object value) {
    String address() {
      return "%s.%s.%s.parsed".formatted(className, deviceID, channelID);
    }
  }
}
