package com.framed.interop.sdc;

import com.framed.core.Service;
import com.framed.core.local.LocalEventBus;
import com.framed.core.utils.DispatchMode;
import com.framed.core.utils.Timer;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.somda.sdc.biceps.common.access.MdibAccessObserver;
import org.somda.sdc.biceps.common.event.MetricStateModificationMessage;
import org.somda.sdc.biceps.common.event.WaveformStateModificationMessage;
import org.somda.sdc.biceps.guice.DefaultBicepsConfigModule;
import org.somda.sdc.biceps.guice.DefaultBicepsModule;
import org.somda.sdc.biceps.model.participant.MetricCategory;
import org.somda.sdc.biceps.model.participant.NumericMetricDescriptor;
import org.somda.sdc.biceps.model.participant.NumericMetricState;
import org.somda.sdc.biceps.model.participant.RealTimeSampleArrayMetricState;
import org.somda.sdc.common.guice.DefaultCommonConfigModule;
import org.somda.sdc.common.guice.DefaultCommonModule;
import org.somda.sdc.dpws.DpwsConfig;
import org.somda.sdc.dpws.DpwsFramework;
import org.somda.sdc.dpws.client.Client;
import org.somda.sdc.dpws.client.DiscoveredDevice;
import org.somda.sdc.dpws.client.DiscoveryObserver;
import org.somda.sdc.dpws.client.event.ProbedDeviceFoundMessage;
import org.somda.sdc.dpws.guice.DefaultDpwsModule;
import org.somda.sdc.dpws.service.HostingServiceProxy;
import org.somda.sdc.glue.consumer.ConnectConfiguration;
import org.somda.sdc.glue.consumer.SdcDiscoveryFilterBuilder;
import org.somda.sdc.glue.consumer.SdcRemoteDevice;
import org.somda.sdc.glue.consumer.SdcRemoteDevicesConnector;
import org.somda.sdc.glue.guice.DefaultGlueConfigModule;
import org.somda.sdc.glue.guice.DefaultGlueModule;
import org.somda.sdc.glue.guice.GlueDpwsConfigModule;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end simulation of the SDC boundary (INTEROP_PLAN Step 3 Phase B): FRAMED bus datapoints
 * pushed through {@link SdcProviderDispatcher} must surface on a real SDCri consumer — the metric
 * as an episodic report, the waveform as a streamed sample array — with the mapping's {@code mdc}
 * code on the descriptor type. Runs only under the {@code sdc} Maven profile.
 */
class SdcProviderDispatcherTest {

  private static final String DEVICE = "Oxylog-3000-Plus-00";
  private static final String METRIC_HANDLE = "Measurement.Oxylog-3000-Plus-00.etCO2";
  private static final String WAVEFORM_HANDLE = "RealTime.Oxylog-3000-Plus-00.CO2_mmHg";
  private static final String SYNTHETIC_MDC = "424242"; // deliberately not a real 11073 code
  private static final long WAIT_S = 15;

  @TempDir
  Path tmp;

  private static Injector consumerInjector() {
    return Guice.createInjector(
        new DefaultCommonConfigModule(),
        new DefaultGlueModule(),
        new DefaultGlueConfigModule(),
        new DefaultBicepsModule(),
        new DefaultBicepsConfigModule(),
        new DefaultCommonModule(),
        new DefaultDpwsModule(),
        new GlueDpwsConfigModule() {
          @Override
          protected void customConfigure() {
            super.customConfigure();
            bind(DpwsConfig.HTTP_SUPPORT, Boolean.class, true);
            bind(DpwsConfig.HTTPS_SUPPORT, Boolean.class, false);
          }
        });
  }

  @Test
  void mappedChannelsSurfaceAsSdcMetricAndWaveform() throws Exception {
    Path mappingPath = tmp.resolve("mapping.json");
    Files.writeString(mappingPath, """
        {
          "Measurement.Oxylog-3000-Plus-00.etCO2":
            {"code":"19889-5","unit":"mm[Hg]","kind":"metric","mdc":"%s"},
          "RealTime.Oxylog-3000-Plus-00.CO2_mmHg":
            {"unit":"mm[Hg]","kind":"waveform"}
        }""".formatted(SYNTHETIC_MDC));

    LocalEventBus bus = new LocalEventBus(DispatchMode.PER_HANDLER);
    String epr = "urn:uuid:" + UUID.randomUUID();
    SdcProviderDispatcher dispatcher = new SdcProviderDispatcher(bus,
        new JSONArray().put(DEVICE), mappingPath.toString(), epr, "", 20, 5, new JSONObject());

    Injector injector = consumerInjector();
    DpwsFramework consumerDpws = injector.getInstance(DpwsFramework.class);
    consumerDpws.setNetworkInterface(
        NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress()));
    Client client = injector.getInstance(Client.class);
    SdcRemoteDevice remoteDevice = null;
    try {
      consumerDpws.startAsync().awaitRunning();
      client.startAsync().awaitRunning();

      // The dispatcher's SDC device must be discoverable.
      CountDownLatch found = new CountDownLatch(1);
      AtomicReference<DiscoveredDevice> discovered = new AtomicReference<>();
      DiscoveryObserver observer = new DiscoveryObserver() {
        @Subscribe
        void deviceFound(ProbedDeviceFoundMessage message) {
          if (epr.equals(message.getPayload().getEprAddress())) {
            discovered.set(message.getPayload());
            found.countDown();
          }
        }
      };
      client.registerDiscoveryObserver(observer);
      client.probe(SdcDiscoveryFilterBuilder.create().get());
      assertTrue(found.await(WAIT_S, TimeUnit.SECONDS), "SDC provider must be discoverable");
      client.unregisterDiscoveryObserver(observer);

      HostingServiceProxy hosting = client.connect(discovered.get()).get(WAIT_S, TimeUnit.SECONDS);
      remoteDevice = injector.getInstance(SdcRemoteDevicesConnector.class)
          .connect(hosting,
              ConnectConfiguration.create(ConnectConfiguration.ALL_EPISODIC_AND_WAVEFORM_REPORTS))
          .get(WAIT_S, TimeUnit.SECONDS);

      CountDownLatch metricSeen = new CountDownLatch(1);
      CountDownLatch waveformSeen = new CountDownLatch(1);
      AtomicReference<BigDecimal> metricValue = new AtomicReference<>();
      AtomicReference<List<BigDecimal>> samples = new AtomicReference<>();
      MdibAccessObserver reports = new MdibAccessObserver() {
        @Subscribe
        void onMetric(MetricStateModificationMessage message) {
          message.getStates().values().stream().flatMap(List::stream)
              .filter(s -> METRIC_HANDLE.equals(s.getDescriptorHandle()))
              .filter(NumericMetricState.class::isInstance)
              .map(s -> ((NumericMetricState) s).getMetricValue())
              .filter(Objects::nonNull)
              .forEach(v -> {
                metricValue.set(v.getValue());
                metricSeen.countDown();
              });
        }

        @Subscribe
        void onWaveform(WaveformStateModificationMessage message) {
          message.getStates().values().stream().flatMap(List::stream)
              .filter(s -> WAVEFORM_HANDLE.equals(s.getDescriptorHandle()))
              .map(RealTimeSampleArrayMetricState::getMetricValue)
              .filter(Objects::nonNull)
              .forEach(v -> {
                samples.set(v.getSamples());
                waveformSeen.countDown();
              });
        }
      };
      remoteDevice.getMdibAccessObservable().registerObserver(reports);

      // Drive the FRAMED side: announce, let the dispatcher bind, publish datapoints.
      String metricChannel = "Measurement.%s.etCO2.parsed".formatted(DEVICE);
      String waveformChannel = "RealTime.%s.CO2_mmHg.parsed".formatted(DEVICE);
      bus.publish(Service.addressRegistry(DEVICE), metricChannel);
      bus.publish(Service.addressRegistry(DEVICE), waveformChannel);
      Thread.sleep(300);

      bus.publish(metricChannel, sample("etCO2", "Measurement", 38));
      for (int i = 1; i <= 5; i++) {
        bus.publish(waveformChannel, sample("CO2_mmHg", "RealTime", i));
      }

      assertTrue(metricSeen.await(WAIT_S, TimeUnit.SECONDS),
          "metric datapoint must arrive as an episodic SDC report");
      assertTrue(waveformSeen.await(WAIT_S, TimeUnit.SECONDS),
          "5 waveform samples (= chunk size) must arrive as one sample array");
      assertEquals(0, new BigDecimal("38").compareTo(metricValue.get()));
      assertEquals(List.of(1, 2, 3, 4, 5),
          samples.get().stream().map(BigDecimal::intValue).toList());

      // The lazily created descriptor must carry the mapping's SDC attributes.
      NumericMetricDescriptor descriptor = remoteDevice.getMdibAccess()
          .getDescriptor(METRIC_HANDLE, NumericMetricDescriptor.class).orElseThrow();
      assertEquals(MetricCategory.MSRMT, descriptor.getMetricCategory());
      assertNotNull(descriptor.getType(), "mdc code must be projected onto the descriptor type");
      assertEquals(SYNTHETIC_MDC, descriptor.getType().getCode());

      remoteDevice.getMdibAccessObservable().unregisterObserver(reports);
    } finally {
      if (remoteDevice != null) {
        remoteDevice.stopAsync().awaitTerminated();
      }
      client.stopAsync().awaitTerminated();
      consumerDpws.stopAsync().awaitTerminated();
      dispatcher.stop();
      bus.shutdown();
    }
  }

  private static JSONObject sample(String channelID, String className, int value) {
    return new JSONObject()
        .put("timestamp", LocalDateTime.now().format(Timer.formatter))
        .put("channelID", channelID)
        .put("value", value)
        .put("className", className);
  }
}
