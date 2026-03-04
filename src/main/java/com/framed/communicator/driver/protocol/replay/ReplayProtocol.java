package com.framed.communicator.driver.protocol.replay;

import com.framed.communicator.driver.protocol.Protocol;
import com.framed.core.EventBus;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A replay protocol that reads either CSV (test_ph.csv) or JSONL files
 * and replays all datapoints in real time into the FRAMED EventBus,
 * using the same publish mechanism as the real PC60FW / Oxylog drivers.
 */
public class ReplayProtocol extends Protocol {

    private static final Logger LOGGER = Logger.getLogger(ReplayProtocol.class.getName());

    private final Path filePath;
    private final DateTimeFormatter csvTs = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.nnnnnnXXX");
    private final DateTimeFormatter isoTs = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final List<String> channels;

    public ReplayProtocol(String id, EventBus eventBus, String filePath, List<String> channels) {
        super(id, eventBus);
        this.filePath = Path.of(filePath);
        this.channels = channels;
        connect();
    }

    @Override
    public void connect() {
        new Thread(this::runReplay, "CSV/JSONL-Replay-Thread").start();
    }

    private void runReplay() {
        LOGGER.info("Starting replay for file: %s".formatted(filePath));

        try {
            List<ReplayEvent> events = loadEvents(filePath);

            if (events.isEmpty()) {
                LOGGER.warning("Replay file contains no events. Nothing to replay.");
                return;
            }

            // Sort globally by timestamp
            events.sort(Comparator.comparing(e -> e.timestamp));

            Instant replayStartRealTime = Instant.now();
            Instant firstEventTs = events.get(0).timestamp;

            LOGGER.info("Loaded %d events. Starting real-time replay.".formatted(events.size()));

            for (ReplayEvent ev : events) {

                // Compute real-time delay
                Duration offset = Duration.between(firstEventTs, ev.timestamp);
                Instant targetTime = replayStartRealTime.plus(offset);

                long delayMs = Duration.between(Instant.now(), targetTime).toMillis();
                if (delayMs > 0) Thread.sleep(delayMs);

                publishEvent(ev);
            }

            LOGGER.info("Replay finished successfully.");

        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Replay failed", ex);
        }
    }

    // ------------------------------------------------------------------------
    // Event publishing (USES EXACT MECHANISM YOU PROVIDED)
    // ------------------------------------------------------------------------

    private void publishEvent(ReplayEvent ev) {
        try {
            String deviceName = ev.deviceID;
            String channelID  = ev.channelID;

            // Construct address = "<device>.<channel>.parsed"
            String address = "%s.%s.parsed".formatted(deviceName, channelID);

            // Create JSON object identical to real driver output
            JSONObject parsedResult = new JSONObject();
            parsedResult.put("timestamp", ev.timestamp.toString());
            parsedResult.put("channelID", channelID);
            parsedResult.put("value", ev.value);
            parsedResult.put("className", ev.className);

            // Exactly like your real driver:
            eventBus.publish("%s.addresses".formatted(deviceName), address);
            eventBus.publish(address, parsedResult);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not publish event: %s".formatted(ev), e);
        }
    }

    // ------------------------------------------------------------------------
    // File parsing for CSV + JSONL
    // ------------------------------------------------------------------------

    private List<ReplayEvent> loadEvents(Path path) throws IOException {
        List<ReplayEvent> events = new ArrayList<>();

        if (path.toString().endsWith(".jsonl")) {
            loadJsonl(events, path);
        } else if (path.toString().endsWith(".csv")) {
            loadCsv(events, path);
        } else {
            throw new IllegalArgumentException("Unsupported file type: %s".formatted(path));
        }

        return events;
    }

    private void loadJsonl(List<ReplayEvent> events, Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                JSONObject obj = new JSONObject(line);

                Instant ts = Instant.parse(obj.getString("timestamp"));
                String className = obj.optString("className", "Unknown");
                String deviceID = obj.optString("deviceID", "Unknown");
                String channelID = obj.optString("channelID", "Unknown");
                Object value = obj.get("value");

                events.add(new ReplayEvent(ts, className, deviceID, channelID, value));
            }
        }
    }

    private void loadCsv(List<ReplayEvent> events, Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path)) {

            String header = br.readLine();
            if (header == null) return;

            // Expected columns: className,value,deviceID,channelID,timestamp
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                // Split respecting CSV
                String[] parts = line.split(",", 5);
                if (parts.length < 5) continue;

                String className = parts[1];
                String valueStr  = parts[2];
                String deviceID  = parts[3];
                String channelID = parts[4];
                String tsStr     = parts[0];

                // But your CSV as uploaded has columns differently arranged,
                // we must match actual layout:
                // 0,className,value,deviceID,channelID,timestamp
                // So re-split:
                String[] p = line.split(",", 6);
                if (p.length < 6) continue;

                className = p[1];
                valueStr  = p[2];
                deviceID  = p[3];
                channelID = p[4];
                tsStr     = p[5];

                Object val = parseValue(valueStr);
                Instant ts = parseCsvTimestamp(tsStr);

                events.add(new ReplayEvent(ts, className, deviceID, channelID, val));
            }
        }
    }

    private Object parseValue(String v) {
        try { return Integer.parseInt(v); } catch (Exception ignored) {}
        try { return Double.parseDouble(v); } catch (Exception ignored) {}
        return v;
    }

    private Instant parseCsvTimestamp(String s) {
        try {
            // Attempt ISO first (your CSV ends in +00:00)
            return Instant.parse(s);
        } catch (Exception ignored) { }

        try {
            return OffsetDateTime.parse(s, csvTs).toInstant();
        } catch (Exception ignored) { }

        LOGGER.warning("Unable to parse timestamp: " + s + " → using Instant.now()");
        return Instant.now();
    }

    // ------------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------------

    private record ReplayEvent(
            Instant timestamp,
            String className,
            String deviceID,
            String channelID,
            Object value
    ) {}
}