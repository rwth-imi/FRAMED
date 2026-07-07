package com.framed.interop.sdc;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.Test;
import org.somda.sdc.biceps.common.MdibStateModifications;
import org.somda.sdc.biceps.common.access.MdibAccessObserver;
import org.somda.sdc.biceps.common.access.ReadTransaction;
import org.somda.sdc.biceps.common.event.MetricStateModificationMessage;
import org.somda.sdc.biceps.common.event.WaveformStateModificationMessage;
import org.somda.sdc.biceps.common.storage.PreprocessingException;
import org.somda.sdc.biceps.guice.DefaultBicepsConfigModule;
import org.somda.sdc.biceps.guice.DefaultBicepsModule;
import org.somda.sdc.biceps.model.participant.AbstractMetricValue;
import org.somda.sdc.biceps.model.participant.GenerationMode;
import org.somda.sdc.biceps.model.participant.Mdib;
import org.somda.sdc.biceps.model.participant.MeasurementValidity;
import org.somda.sdc.biceps.model.participant.NumericMetricState;
import org.somda.sdc.biceps.model.participant.NumericMetricValue;
import org.somda.sdc.biceps.model.participant.RealTimeSampleArrayMetricState;
import org.somda.sdc.biceps.model.participant.SampleArrayValue;
import org.somda.sdc.biceps.provider.access.LocalMdibAccess;
import org.somda.sdc.biceps.provider.access.factory.LocalMdibAccessFactory;
import org.somda.sdc.common.guice.DefaultCommonConfigModule;
import org.somda.sdc.common.guice.DefaultCommonModule;
import org.somda.sdc.dpws.DpwsConfig;
import org.somda.sdc.dpws.DpwsFramework;
import org.somda.sdc.dpws.client.Client;
import org.somda.sdc.dpws.client.DiscoveredDevice;
import org.somda.sdc.dpws.client.DiscoveryObserver;
import org.somda.sdc.dpws.client.event.ProbedDeviceFoundMessage;
import org.somda.sdc.dpws.device.DeviceSettings;
import org.somda.sdc.dpws.guice.DefaultDpwsModule;
import org.somda.sdc.dpws.service.HostingServiceProxy;
import org.somda.sdc.dpws.soap.wsaddressing.WsAddressingUtil;
import org.somda.sdc.dpws.soap.wsaddressing.model.EndpointReferenceType;
import org.somda.sdc.glue.common.MdibXmlIo;
import org.somda.sdc.glue.common.factory.ModificationsBuilderFactory;
import org.somda.sdc.glue.consumer.ConnectConfiguration;
import org.somda.sdc.glue.consumer.SdcRemoteDevice;
import org.somda.sdc.glue.consumer.SdcRemoteDevicesConnector;
import org.somda.sdc.glue.guice.DefaultGlueConfigModule;
import org.somda.sdc.glue.guice.DefaultGlueModule;
import org.somda.sdc.glue.guice.GlueDpwsConfigModule;
import org.somda.sdc.glue.provider.SdcDevice;
import org.somda.sdc.glue.provider.factory.SdcDeviceFactory;
import org.somda.sdc.glue.provider.plugin.SdcRequiredTypesAndScopes;
import org.somda.sdc.glue.provider.sco.OperationInvocationReceiver;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * Phase A decision-gate spike for INTEROP_PLAN Step 3 (IEEE 11073 SDC): an SDCri
 * {@link SdcDevice provider} and an SDCri consumer exchange one numeric metric and one waveform
 * (real-time sample array) over MDPWS on the loopback interface — WS-Discovery probe, MDIB
 * retrieval, WS-Eventing episodic metric report and waveform stream.
 *
 * <p>Runs only under the {@code sdc} Maven profile:
 * {@code mvn -pl framed-interop -Psdc test -Dtest=SdcriSpikeTest}. Plain HTTP (no TLS) to keep
 * the spike free of certificate handling; production use would configure {@code CryptoConfig}.</p>
 */
class SdcriSpikeTest {

  private static final String HANDLE_NUMERIC = "numeric.ch1.vmd0";
  private static final String HANDLE_WAVEFORM = "rtsa.ch0.vmd0";
  private static final long WAIT_S = 15;

  /** One SDCri Guice injector (provider and consumer each get their own), HTTP only. */
  private static Injector injector() {
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
  void providerAndConsumerExchangeMetricAndWaveform() throws Exception {
    NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
    String epr = "urn:uuid:" + UUID.randomUUID();

    // ---- Provider: MDIB from XML, exposed as an SDC device on loopback -------------------------
    Injector providerInjector = injector();
    DpwsFramework providerDpws = providerInjector.getInstance(DpwsFramework.class);
    providerDpws.setNetworkInterface(loopback);
    LocalMdibAccess mdibAccess =
        providerInjector.getInstance(LocalMdibAccessFactory.class).createLocalMdibAccess();
    SdcDevice sdcDevice = providerInjector.getInstance(SdcDeviceFactory.class).createSdcDevice(
        new DeviceSettings() {
          @Override
          public EndpointReferenceType getEndpointReference() {
            return providerInjector.getInstance(WsAddressingUtil.class).createEprWithAddress(epr);
          }

          @Override
          public NetworkInterface getNetworkInterface() {
            return loopback;
          }
        },
        mdibAccess,
        new OperationInvocationReceiver() {},
        Collections.singleton(providerInjector.getInstance(SdcRequiredTypesAndScopes.class)));

    MdibXmlIo mdibXmlIo = providerInjector.getInstance(MdibXmlIo.class);
    try (InputStream mdibStream = Objects.requireNonNull(
        SdcriSpikeTest.class.getResourceAsStream("/sdc/mdib.xml"), "spike mdib missing")) {
      Mdib mdib = mdibXmlIo.readMdib(mdibStream);
      mdibAccess.writeDescription(providerInjector.getInstance(ModificationsBuilderFactory.class)
          .createModificationsBuilder(mdib).get());
    }

    // ---- Consumer: discover over WS-Discovery, connect, subscribe reports ----------------------
    Injector consumerInjector = injector();
    DpwsFramework consumerDpws = consumerInjector.getInstance(DpwsFramework.class);
    consumerDpws.setNetworkInterface(loopback);
    Client client = consumerInjector.getInstance(Client.class);
    SdcRemoteDevicesConnector connector =
        consumerInjector.getInstance(SdcRemoteDevicesConnector.class);

    SdcRemoteDevice remoteDevice = null;
    try {
      providerDpws.startAsync().awaitRunning();
      sdcDevice.startAsync().awaitRunning();
      consumerDpws.startAsync().awaitRunning();
      client.startAsync().awaitRunning();

      // Discovery: probe and wait for our provider's EPR.
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
      client.probe(org.somda.sdc.glue.consumer.SdcDiscoveryFilterBuilder.create().get());
      assertTrue(found.await(WAIT_S, TimeUnit.SECONDS),
          "provider must be discoverable via WS-Discovery on loopback");
      client.unregisterDiscoveryObserver(observer);

      HostingServiceProxy hostingService =
          client.connect(discovered.get()).get(WAIT_S, TimeUnit.SECONDS);
      remoteDevice = connector.connect(hostingService,
              ConnectConfiguration.create(ConnectConfiguration.ALL_EPISODIC_AND_WAVEFORM_REPORTS))
          .get(WAIT_S, TimeUnit.SECONDS);

      // Report capture: one numeric metric update, one waveform update.
      CountDownLatch metricSeen = new CountDownLatch(1);
      CountDownLatch waveformSeen = new CountDownLatch(1);
      AtomicReference<BigDecimal> metricValue = new AtomicReference<>();
      AtomicReference<List<BigDecimal>> waveformSamples = new AtomicReference<>();
      MdibAccessObserver reportObserver = new MdibAccessObserver() {
        @Subscribe
        void onMetric(MetricStateModificationMessage message) {
          message.getStates().values().stream().flatMap(List::stream)
              .filter(s -> HANDLE_NUMERIC.equals(s.getDescriptorHandle()))
              .filter(s -> s instanceof NumericMetricState)
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
              .filter(s -> HANDLE_WAVEFORM.equals(s.getDescriptorHandle()))
              .map(RealTimeSampleArrayMetricState::getMetricValue)
              .filter(Objects::nonNull)
              .forEach(v -> {
                waveformSamples.set(v.getSamples());
                waveformSeen.countDown();
              });
        }
      };
      remoteDevice.getMdibAccessObservable().registerObserver(reportObserver);

      // ---- Provider emits: one metric update, one waveform update ------------------------------
      writeNumericMetric(mdibAccess, new BigDecimal("42"));
      List<BigDecimal> samples = List.of(
          new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
          new BigDecimal("4"), new BigDecimal("5"));
      writeWaveform(mdibAccess, samples);

      assertTrue(metricSeen.await(WAIT_S, TimeUnit.SECONDS),
          "episodic metric report must reach the consumer");
      assertTrue(waveformSeen.await(WAIT_S, TimeUnit.SECONDS),
          "waveform stream must reach the consumer");
      assertEquals(new BigDecimal("42"), metricValue.get(),
          "metric value must survive the SDC hop");
      assertEquals(samples, waveformSamples.get(),
          "waveform samples must survive the SDC hop");

      // The consumer's remote MDIB mirror must reflect the written value as well.
      NumericMetricState mirrored = remoteDevice.getMdibAccess()
          .getState(HANDLE_NUMERIC, NumericMetricState.class).orElseThrow();
      assertNotNull(mirrored.getMetricValue());
      assertEquals(new BigDecimal("42"), mirrored.getMetricValue().getValue());

      remoteDevice.getMdibAccessObservable().unregisterObserver(reportObserver);
    } finally {
      if (remoteDevice != null) {
        remoteDevice.stopAsync().awaitTerminated();
      }
      client.stopAsync().awaitTerminated();
      consumerDpws.stopAsync().awaitTerminated();
      sdcDevice.stopAsync().awaitTerminated();
      providerDpws.stopAsync().awaitTerminated();
    }
  }

  private static void writeNumericMetric(LocalMdibAccess mdibAccess, BigDecimal value)
      throws PreprocessingException {
    NumericMetricState state =
        mdibAccess.getState(HANDLE_NUMERIC, NumericMetricState.class).orElseThrow();
    NumericMetricValue metricValue = new NumericMetricValue();
    metricValue.setValue(value);
    metricValue.setDeterminationTime(Instant.now());
    metricValue.setMetricQuality(quality());
    state.setMetricValue(metricValue);
    mdibAccess.writeStates(new MdibStateModifications.Metric(List.of(state)));
  }

  private static void writeWaveform(LocalMdibAccess mdibAccess, List<BigDecimal> samples)
      throws PreprocessingException {
    MdibStateModifications.Waveform modifications =
        new MdibStateModifications.Waveform(new ArrayList<>());
    try (ReadTransaction tx = mdibAccess.startTransaction()) {
      RealTimeSampleArrayMetricState state =
          tx.getState(HANDLE_WAVEFORM, RealTimeSampleArrayMetricState.class).orElseThrow();
      SampleArrayValue sampleArray = new SampleArrayValue();
      sampleArray.setMetricQuality(quality());
      sampleArray.setSamples(new ArrayList<>(samples));
      sampleArray.setDeterminationTime(Instant.now());
      state.setMetricValue(sampleArray);
      modifications.getWaveformStates().add(state);
    }
    mdibAccess.writeStates(modifications);
  }

  private static AbstractMetricValue.MetricQuality quality() {
    AbstractMetricValue.MetricQuality quality = new AbstractMetricValue.MetricQuality();
    quality.setMode(GenerationMode.REAL);
    quality.setValidity(MeasurementValidity.VLD);
    return quality;
  }
}
