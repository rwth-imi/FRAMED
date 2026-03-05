package com.framed.communicator.driver.protocol.replay;

import com.framed.communicator.driver.protocol.Protocol;
import com.framed.core.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
    private final List<String> devices = new ArrayList<>();

    public ReplayProtocol(String id, EventBus eventBus, String filePath, JSONArray devices) {
        super(id, eventBus);
        this.filePath = Path.of(filePath);
        for (Object o : devices) {
            this.devices.add(String.valueOf(o));  // safe conversion
        }
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
            ZonedDateTime firstEventTs = events.get(0).timestamp;

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
            parsedResult.put("timestamp", ZonedDateTime.now().format(formatter));
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
                ZonedDateTime timestamp = ZonedDateTime.ofInstant(ts, ZoneOffset.UTC);
                String className = obj.optString("className", "Unknown");
                String deviceID = obj.optString("deviceID", "Unknown");
                String channelID = obj.optString("channelID", "Unknown");
                Object value = obj.get("value");
                if(devices.contains(deviceID)) {
                    events.add(new ReplayEvent(timestamp, className, deviceID, channelID, value));
                }
            }
        }
    }


    private record ReplayEvent(
            ZonedDateTime timestamp,
            String className,
            String deviceID,
            String channelID,
            Object value
    ) {}
}