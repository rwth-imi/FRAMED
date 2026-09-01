package com.framed.io.dispatch;

import com.framed.core.EventBus;
import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the producer&rarr;sink address-discovery handshake.
 *
 * <p>A producer announces an output channel and then publishes on it. If the sink's binding runs
 * asynchronously, the samples published in between go to an address with no handler and
 * {@code publish} discards them silently — no error, no drop counter, just missing data. That was a
 * real defect: a full-application run of the MIMIC replay lost two of three million datapoints this
 * way, always at the start of a channel.</p>
 *
 * <p>These tests pin the fix: {@link Service#subscribeToAnnouncements(String, java.util.function.Consumer)}
 * registers with {@link DispatchMode#SEQUENTIAL}, so binding completes on the announcing thread
 * before {@link Service#announceAddress(String, String)} returns. They run the bus in
 * {@link DispatchMode#PER_HANDLER}, the mode {@code Main} hardcodes, because that is the mode the
 * defect appeared under.</p>
 */
class AddressDiscoveryHandshakeTest {

  private static final String DEVICE = "DEVICE-1";
  private static final String CLASS_NAME = "Waveform";
  private static final String CHANNEL = "II";

  private LocalEventBus bus;

  /** A sink that records what it was handed, so the test can assert on delivery rather than timing. */
  private static final class RecordingDispatcher extends Dispatcher {

    private final ConcurrentLinkedQueue<DataPoint<?>> received = new ConcurrentLinkedQueue<>();
    private final CountDownLatch expected;

    RecordingDispatcher(EventBus eventBus, JSONArray devices, int expectedCount) {
      super(eventBus, devices);
      this.expected = new CountDownLatch(expectedCount);
    }

    @Override
    public void push(DataPoint<?> dataPoint) {
      received.add(dataPoint);
      if (expected != null) expected.countDown();
    }

    @Override
    public void pushBatch(List<DataPoint<?>> batch) {
      batch.forEach(this::push);
    }

    boolean awaitAll(Duration timeout) throws InterruptedException {
      return expected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** Announces a channel and immediately publishes on it, exactly as the replay drivers do. */
  private static final class AnnouncingProducer extends Service {

    AnnouncingProducer(EventBus eventBus) {
      super(eventBus);
    }

    void announceThenPublish(String device, String channel, int samples) {
      String address = "%s.%s.%s.parsed".formatted(CLASS_NAME, device, channel);
      announceAddress(device, address);
      for (int i = 0; i < samples; i++) {
        JSONObject sample = new JSONObject();
        sample.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(Timer.formatter));
        sample.put("channelID", channel);
        sample.put("value", (double) i);
        sample.put("className", CLASS_NAME);
        eventBus.publish(address, sample);
      }
    }
  }

  @BeforeEach
  void setUp() {
    bus = new LocalEventBus(DispatchMode.PER_HANDLER);
  }

  @AfterEach
  void tearDown() {
    bus.shutdown();
  }

  @Test
  void deliversEverySamplePublishedImmediatelyAfterTheAnnouncement() throws Exception {
    int samples = 500;
    RecordingDispatcher sink = new RecordingDispatcher(bus, new JSONArray(List.of(DEVICE)), samples);

    new AnnouncingProducer(bus).announceThenPublish(DEVICE, CHANNEL, samples);

    assertTrue(sink.awaitAll(Duration.ofSeconds(10)),
            "sink received only %d of %d samples — the announce/bind window is open again"
                    .formatted(sink.received.size(), samples));
    assertEquals(samples, sink.received.size());
    // The very first sample is the one the race used to eat; make its loss its own failure message.
    // Compared numerically: Dispatcher re-parses the payload defensively, and org.json narrows
    // "0" back to an Integer on the way through, so the boxed types differ from what was published.
    assertEquals(0.0, ((Number) sink.received.peek().value()).doubleValue(),
            "the first sample after the announcement was lost");
  }

  @Test
  void binderRunsOnTheAnnouncingThreadSoBindingCannotLagTheProducer() {
    AtomicReference<Thread> binderThread = new AtomicReference<>();
    AtomicInteger announcements = new AtomicInteger();

    // A minimal sink: all it does is record where and how often its binder ran.
    Service sink = new Service(bus) {
      {
        subscribeToAnnouncements(DEVICE, address -> {
          binderThread.set(Thread.currentThread());
          announcements.incrementAndGet();
        });
      }
    };
    assertNotNull(sink);

    Thread announcing = Thread.currentThread();
    new AnnouncingProducer(bus).announceThenPublish(DEVICE, CHANNEL, 0);

    // Synchronous by contract: the binder has already run, on this very thread, by the time
    // announceAddress returned. No sleeping and no polling — if this needed a wait, the guarantee
    // the producers rely on would not exist.
    assertEquals(1, announcements.get(), "the binder had not run when announceAddress returned");
    assertSame(announcing, binderThread.get(),
            "the binder ran on another thread, so a producer could out-run it");
  }

  @Test
  void concurrentAnnouncementsFromSeveralDevicesAllBind() throws Exception {
    int devices = 8;
    int samplesPerDevice = 100;
    JSONArray deviceIds = new JSONArray();
    for (int d = 0; d < devices; d++) {
      deviceIds.put("DEVICE-%d".formatted(d));
    }
    RecordingDispatcher sink =
            new RecordingDispatcher(bus, deviceIds, devices * samplesPerDevice);

    // Announcements now run on the announcing thread, so several producers bind concurrently.
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> producers = new java.util.ArrayList<>();
    for (int d = 0; d < devices; d++) {
      String device = "DEVICE-%d".formatted(d);
      Thread t = new Thread(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        new AnnouncingProducer(bus).announceThenPublish(device, CHANNEL, samplesPerDevice);
      }, "producer-" + device);
      producers.add(t);
      t.start();
    }
    start.countDown();
    for (Thread t : producers) {
      t.join(Duration.ofSeconds(20).toMillis());
    }

    assertTrue(sink.awaitAll(Duration.ofSeconds(20)),
            "sink received only %d of %d samples across %d concurrently announcing devices"
                    .formatted(sink.received.size(), devices * samplesPerDevice, devices));
    assertEquals(devices * samplesPerDevice, sink.received.size());
  }
}
