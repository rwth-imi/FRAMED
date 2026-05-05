package com.framed.streamer.dispatcher;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.streamer.Parser;
import com.framed.streamer.model.DataPoint;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Drop-in replacement:
 * - Never blocks EventBus handler on IO.
 * - Retries on transient IO errors in a dedicated worker thread.
 * - Adds bounded queue to avoid unbounded memory growth (configurable via system properties).
 */
public abstract class Dispatcher extends Service {

  /**
   * Queue size for async push jobs.
   * Increase if you have bursts, decrease if you want tighter memory bounds.
   */
  private static final int PUSH_QUEUE_CAPACITY =
          Integer.getInteger("framed.dispatcher.queueCapacity", 50_000);

  /**
   * Max backoff for IO retry.
   */
  private static final long MAX_BACKOFF_MS =
          Long.getLong("framed.dispatcher.maxBackoffMs", 5_000L);

  /**
   * Initial backoff for IO retry.
   */
  private static final long INITIAL_BACKOFF_MS =
          Long.getLong("framed.dispatcher.initialBackoffMs", 100L);

  /**
   * If true, rejects (drops) datapoints when queue is full.
   * If false, handler will block briefly trying to enqueue (safer but can slow ingestion).
   */
  private static final boolean DROP_ON_OVERLOAD =
          Boolean.parseBoolean(System.getProperty("framed.dispatcher.dropOnOverload", "false"));

  /**
   * If not dropping on overload, how long to wait to enqueue before giving up (ms).
   */
  private static final long ENQUEUE_TIMEOUT_MS =
          Long.getLong("framed.dispatcher.enqueueTimeoutMs", 250L);

  // Track addresses per device to avoid duplicate registration for same device+address
  private final ConcurrentHashMap<String, Set<String>> perDeviceAddresses = new ConcurrentHashMap<>();

  // Dedicated single-thread worker for pushes (keeps ordering stable for most dispatchers)
  private final ThreadPoolExecutor pushExecutor;

  private volatile boolean running = true;

  protected Dispatcher(EventBus eventBus, JSONArray devices) {
    super(eventBus);

    Objects.requireNonNull(eventBus, "eventBus");
    Objects.requireNonNull(devices, "devices");

    this.pushExecutor = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(PUSH_QUEUE_CAPACITY),
            new NamedThreadFactory(getClass().getSimpleName() + "-push"),
            new ThreadPoolExecutor.AbortPolicy()
    );
    this.pushExecutor.prestartAllCoreThreads();

    for (Object deviceObj : devices) {
      final String deviceID = deviceObj.toString();

      // Register device's "addresses" channel
      eventBus.register("%s.addresses".formatted(deviceID), msg -> {
        if (!running) return;

        final String address = String.valueOf(msg);
        if (address == null || address.isBlank()) return;

        // register once per device+address (does not prevent two devices using same address)
        Set<String> set = perDeviceAddresses.computeIfAbsent(deviceID, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(address)) return;

        registerAddress(eventBus, address, deviceID);
      });
    }
  }

  private void registerAddress(EventBus eventBus, String address, String deviceID) {
    eventBus.register(address, msg_ -> {
      if (!running) return;

      try {
        if (!(msg_ instanceof JSONObject body)) return;

        // Defensive copy - don't mutate shared JSON
        JSONObject enriched = new JSONObject(body.toString());
        enriched.put("deviceID", deviceID);

        DataPoint<?> dp = Parser.parse(enriched);

        // Enqueue for async push (never block EventBus thread on IO)
        enqueue(dp);

      } catch (Exception e) {
        // Don't throw out of handler
        onHandlerError(deviceID, address, msg_, e);
      }
    });
  }

  private void enqueue(DataPoint<?> dp) {
    Runnable task = () -> {
      try {
        pushWithRetry(dp);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        onDrop(dp, ie);
      } catch (Throwable t) {
        // pushWithRetry only throws InterruptedException, but keep a guard
        onDrop(dp, t);
      }
    };

    try {
      pushExecutor.execute(task);
    } catch (RejectedExecutionException rex) {
      if (DROP_ON_OVERLOAD) {
        onDrop(dp, rex);
        return;
      }

      // Try to enqueue with timeout (safer, but bounded)
      boolean enqueued = false;
      try {
        enqueued = pushExecutor.getQueue().offer(task, ENQUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }

      if (!enqueued) {
        onDrop(dp, rex);
      }
    }
  }

  private void pushWithRetry(DataPoint<?> dp) throws InterruptedException {
    long backoffMs = INITIAL_BACKOFF_MS;

    while (running) {
      try {
        push(dp);
        return;
      } catch (IOException ioe) {
        // Exponential backoff with jitter
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, backoffMs / 4));
        Thread.sleep(backoffMs + jitter);
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
      }
    }

    // If we exit due to shutdown, treat as dropped (unless you implement draining)
    onDrop(dp, new CancellationException("Dispatcher stopped"));
  }

  /**
   * Called when handler fails before enqueueing (e.g., parse error).
   * Override to write dead-letter files, metrics, etc.
   */
  protected void onHandlerError(String deviceID, String address, Object rawMsg, Exception e) {
    System.err.println("Dispatcher handler failed device=" + deviceID + " address=" + address + ": " + e.getMessage());
    e.printStackTrace();
  }

  /**
   * Called when datapoint cannot be queued or pushed.
   * Override for dead-letter storage / metrics.
   */
  protected void onDrop(DataPoint<?> dp, Throwable cause) {
    System.err.println("Dispatcher dropped datapoint: " + cause);
  }

  /**
   * Optional: call this when stopping your service to stop worker thread.
   * (Drop-in: doesn't require changes elsewhere, but recommended to call.)
   */
  public void shutdown(Duration drainTimeout) {
    running = false;
    pushExecutor.shutdown();
    try {
      if (drainTimeout != null) {
        pushExecutor.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      pushExecutor.shutdownNow();
    }
  }

  @Override
  public void stop(){
    shutdown(null);
  }

  public abstract void push(DataPoint<?> dataPoint) throws IOException;

  public abstract void pushBatch(java.util.List<DataPoint<?>> batch);


}