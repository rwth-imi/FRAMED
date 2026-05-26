package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.streamer.model.DataPoint;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.file.StandardOpenOption.*;

public class JsonlDispatcher extends Dispatcher {

  private final Path path;
  private final Path deadLetterPath;

  private volatile FileChannel channel;

  /**
   * Force-to-disk policy:
   * - framed.jsonl.forceEveryLines (default 0 = never)
   * - framed.jsonl.forceEveryMs (default 0 = never)
   */
  private static final long FORCE_EVERY_LINES =
          Long.getLong("framed.jsonl.forceEveryLines", 0L);
  private static final long FORCE_EVERY_MS =
          Long.getLong("framed.jsonl.forceEveryMs", 0L);

  private final AtomicLong linesSinceForce = new AtomicLong(0);
  private final AtomicLong lastForceAtMs = new AtomicLong(System.currentTimeMillis());

  public JsonlDispatcher(EventBus eventBus, JSONArray devices, String path, String fileName) {
    super(eventBus, devices);

    long timeOnStart = Instant.now().toEpochMilli();
    String file = "%d_%s".formatted(timeOnStart, fileName);

    Path dir = Path.of(path);
    this.path = dir.resolve(file);
    this.deadLetterPath = dir.resolve(file + ".deadletter.jsonl");

    try {
      Files.createDirectories(dir);
      openChannel();
    } catch (IOException e) {
      // Fail fast: if we cannot open file, we cannot reliably dispatch
      throw new RuntimeException("Failed to initialize JsonlDispatcher at " + this.path, e);
    }
  }

  private void openChannel() throws IOException {
    // Keep the channel open to reduce per-write overhead and reduce rare failures due to open/close churn
    this.channel = FileChannel.open(path, CREATE, WRITE, APPEND);
  }

  @Override
  public void push(DataPoint<?> dataPoint) throws IOException {
    // Called from Dispatcher's dedicated push thread, so no additional lock needed here.
    // But safe even if you later change push executor concurrency.
    FileChannel ch = this.channel;
    if (ch == null || !ch.isOpen()) {
      openChannel();
      ch = this.channel;
    }

    // Build JSONL line
    byte[] bytes = (dataPoint.toJsonString() + "\n").getBytes(StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.wrap(bytes);

    // Ensure full write
    while (buf.hasRemaining()) {
      ch.write(buf);
    }

    maybeForce(ch);
  }

  private void maybeForce(FileChannel ch) throws IOException {
    if (FORCE_EVERY_LINES <= 0 && FORCE_EVERY_MS <= 0) return;

    long now = System.currentTimeMillis();
    long lines = linesSinceForce.incrementAndGet();

    boolean forceByLines = FORCE_EVERY_LINES > 0 && lines >= FORCE_EVERY_LINES;
    boolean forceByTime = FORCE_EVERY_MS > 0 && (now - lastForceAtMs.get()) >= FORCE_EVERY_MS;

    if (forceByLines || forceByTime) {
      // Reset counters first to avoid double forces under rare races
      linesSinceForce.set(0);
      lastForceAtMs.set(now);

      // force(false) flushes file content (metadata may still be cached)
      ch.force(false);
    }
  }

  @Override
  public void pushBatch(List<DataPoint<?>> batch) {
    // Optional improvement: implement true batching if you use it.
    // With current Dispatcher, pushes are sequential anyway; batching can reduce force() frequency.
    for (DataPoint<?> dp : batch) {
      try {
        push(dp);
      } catch (IOException e) {
        // Let Dispatcher retry only for push(dp). Since this method doesn't throw, we log dead-letter.
        onDrop(dp, e);
      }
    }
  }

  @Override
  protected void onHandlerError(String deviceID, String address, Object rawMsg, Exception e) {
    super.onHandlerError(deviceID, address, rawMsg, e);
    // Persist dead-letter entry with raw payload for forensic analysis
    try {
      JSONObject j = new JSONObject();
      j.put("ts", Instant.now().toString());
      j.put("deviceID", deviceID);
      j.put("address", address);
      j.put("error", e.toString());
      j.put("raw", String.valueOf(rawMsg));

      Files.writeString(deadLetterPath, j.toString() + "\n", CREATE, WRITE, APPEND);
    } catch (Exception ignored) {
      // Avoid recursive failure loops
    }
  }

  @Override
  protected void onDrop(DataPoint<?> dp, Throwable cause) {
    super.onDrop(dp, cause);
    // Persist dropped datapoints too (best-effort)
    try {
      String line = "{\"ts\":\"" + Instant.now() + "\",\"error\":\"" + escape(String.valueOf(cause))
              + "\",\"datapoint\":" + dp.toJsonString() + "}\n";
      Files.write(deadLetterPath, line.getBytes(StandardCharsets.UTF_8), CREATE, WRITE, APPEND);
    } catch (Exception ignored) { }
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}