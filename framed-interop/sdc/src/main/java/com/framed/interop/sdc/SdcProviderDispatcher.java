package com.framed.interop.sdc;

import com.framed.core.EventBus;
import com.framed.interop.gate.EmissionGate;
import com.framed.interop.mapping.CodedConcept;
import com.framed.interop.mapping.ObservationMapping;
import com.framed.io.dispatch.DataPoint;
import com.framed.io.dispatch.Dispatcher;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.json.JSONArray;
import org.json.JSONObject;
import org.somda.sdc.biceps.common.MdibDescriptionModifications;
import org.somda.sdc.biceps.common.MdibStateModifications;
import org.somda.sdc.biceps.common.Pair;
import org.somda.sdc.biceps.guice.DefaultBicepsConfigModule;
import org.somda.sdc.biceps.guice.DefaultBicepsModule;
import org.somda.sdc.biceps.model.participant.AbstractMetricDescriptor;
import org.somda.sdc.biceps.model.participant.AbstractMetricState;
import org.somda.sdc.biceps.model.participant.AbstractMetricValue;
import org.somda.sdc.biceps.model.participant.AbstractState;
import org.somda.sdc.biceps.model.participant.ChannelDescriptor;
import org.somda.sdc.biceps.model.participant.ChannelState;
import org.somda.sdc.biceps.model.participant.CodedValue;
import org.somda.sdc.biceps.model.participant.GenerationMode;
import org.somda.sdc.biceps.model.participant.MdsDescriptor;
import org.somda.sdc.biceps.model.participant.MdsState;
import org.somda.sdc.biceps.model.participant.MeasurementValidity;
import org.somda.sdc.biceps.model.participant.MetricAvailability;
import org.somda.sdc.biceps.model.participant.MetricCategory;
import org.somda.sdc.biceps.model.participant.NumericMetricDescriptor;
import org.somda.sdc.biceps.model.participant.NumericMetricState;
import org.somda.sdc.biceps.model.participant.NumericMetricValue;
import org.somda.sdc.biceps.model.participant.RealTimeSampleArrayMetricDescriptor;
import org.somda.sdc.biceps.model.participant.RealTimeSampleArrayMetricState;
import org.somda.sdc.biceps.model.participant.SampleArrayValue;
import org.somda.sdc.biceps.model.participant.StringMetricDescriptor;
import org.somda.sdc.biceps.model.participant.StringMetricState;
import org.somda.sdc.biceps.model.participant.StringMetricValue;
import org.somda.sdc.biceps.model.participant.VmdDescriptor;
import org.somda.sdc.biceps.model.participant.VmdState;
import org.somda.sdc.biceps.provider.access.LocalMdibAccess;
import org.somda.sdc.biceps.provider.access.factory.LocalMdibAccessFactory;
import org.somda.sdc.common.guice.DefaultCommonConfigModule;
import org.somda.sdc.common.guice.DefaultCommonModule;
import org.somda.sdc.dpws.DpwsConfig;
import org.somda.sdc.dpws.DpwsFramework;
import org.somda.sdc.dpws.device.DeviceSettings;
import org.somda.sdc.dpws.guice.DefaultDpwsModule;
import org.somda.sdc.dpws.soap.wsaddressing.WsAddressingUtil;
import org.somda.sdc.dpws.soap.wsaddressing.model.EndpointReferenceType;
import org.somda.sdc.glue.guice.DefaultGlueConfigModule;
import org.somda.sdc.glue.guice.DefaultGlueModule;
import org.somda.sdc.glue.guice.GlueDpwsConfigModule;
import org.somda.sdc.glue.provider.SdcDevice;
import org.somda.sdc.glue.provider.factory.SdcDeviceFactory;
import org.somda.sdc.glue.provider.plugin.SdcRequiredTypesAndScopes;
import org.somda.sdc.glue.provider.sco.OperationInvocationReceiver;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Outbound IEEE 11073 SDC sink ({@code SdcProviderDispatcher}, INTEROP_PLAN Step 3 Phase B):
 * exposes FRAMED as a BICEPS <em>provider</em> whose MDIB mirrors the mapped channels, so any SDC
 * consumer can discover the device over WS-Discovery, read the MDIB and subscribe to episodic
 * metric reports and waveform streams (MDPWS/WS-Eventing, via SDCri).
 *
 * <p><b>MDIB shape.</b> One MDS ({@value #MDS_HANDLE}); per configured device group one VMD and
 * one channel; one metric descriptor per mapped FRAMED channel, created on the first datapoint
 * that flows (so device-agnostic mapping keys and late-announced channels work): mapping
 * {@code kind} {@code metric}/{@code setting} becomes a numeric or string metric (BICEPS metric
 * category {@code MSRMT}/{@code SET}), {@code waveform} becomes a real-time sample array.
 * Descriptors carry a coded type only when the mapping assigns an {@code mdc} nomenclature code
 * (never a guessed one); units are encoded as UCUM ({@code http://unitsofmeasure.org}).</p>
 *
 * <p><b>Cadence.</b> Waveform samples bypass the {@link EmissionGate} — SDC's waveform streams are
 * designed for device rate — and are buffered into sample arrays of {@code waveformChunkSize}
 * values. Metrics and settings pass the gate like every other interop bridge.</p>
 *
 * <p><b>Threading.</b> All MDIB writes happen on the single {@link Dispatcher} push worker;
 * SDCri handles report fan-out on its own executors, so the bus handler thread never blocks.</p>
 *
 * <p><b>Security.</b> This phase binds plain HTTP (no TLS). Productive SDC deployments require
 * the MDPWS security profile; wiring {@code CryptoConfig} is deferred to INTEROP_PLAN Step 3
 * Phase D and must be addressed before any clinical use.</p>
 */
public final class SdcProviderDispatcher extends Dispatcher {

  /** Handle of the single MDS every FRAMED channel hangs under. */
  static final String MDS_HANDLE = "framed.mds";

  private static final BigDecimal DEFAULT_RESOLUTION = new BigDecimal("0.01");

  private final ObservationMapping mapping;
  private final EmissionGate gate;
  private final LocalMdibAccess mdibAccess;
  private final DpwsFramework dpwsFramework;
  private final SdcDevice sdcDevice;
  private final Duration samplePeriod;
  private final int waveformChunkSize;

  // Touched only on the single push worker thread.
  private final Set<String> declaredHandles = new HashSet<>();
  private final Map<String, List<BigDecimal>> waveformBuffers = new HashMap<>();

  /**
   * Config-loadable constructor. Starts the DPWS stack and the SDC device immediately.
   *
   * @param eventBus               the event bus
   * @param devices                device groups whose announced channels are forwarded
   * @param mappingPath            path to the interop mapping JSON ({@code mdc}/{@code kind} aware)
   * @param epr                    device endpoint reference; empty generates a random urn:uuid
   * @param iface                  network interface name to bind (e.g. {@code eth0}); empty binds
   *                               the loopback interface
   * @param waveformSamplePeriodMs declared sample period of waveform channels, in milliseconds
   * @param waveformChunkSize      samples buffered per emitted real-time sample array
   * @param gate                   emission gate config for metrics/settings
   *                               ({@code onChange}, {@code minIntervalMs})
   */
  public SdcProviderDispatcher(EventBus eventBus, JSONArray devices, String mappingPath,
                               String epr, String iface, double waveformSamplePeriodMs,
                               double waveformChunkSize, JSONObject gate) {
    super(eventBus, devices);
    try {
      this.mapping = ObservationMapping.load(Path.of(mappingPath));
    } catch (IOException e) {
      throw new RuntimeException("Failed to load interop mapping at " + mappingPath, e);
    }
    JSONObject g = gate == null ? new JSONObject() : gate;
    this.gate = new EmissionGate(g.optBoolean("onChange", false), g.optLong("minIntervalMs", 0));
    this.samplePeriod = Duration.ofMillis(Math.max(1, (long) waveformSamplePeriodMs));
    this.waveformChunkSize = Math.max(1, (int) waveformChunkSize);

    Injector injector = createInjector();
    try {
      NetworkInterface networkInterface = (iface == null || iface.isBlank())
          ? NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress())
          : NetworkInterface.getByName(iface);
      if (networkInterface == null) {
        throw new IllegalArgumentException("Network interface not found: " + iface);
      }
      String eprAddress = (epr == null || epr.isBlank()) ? "urn:uuid:" + UUID.randomUUID() : epr;

      this.dpwsFramework = injector.getInstance(DpwsFramework.class);
      this.dpwsFramework.setNetworkInterface(networkInterface);
      this.mdibAccess = injector.getInstance(LocalMdibAccessFactory.class).createLocalMdibAccess();
      this.sdcDevice = injector.getInstance(SdcDeviceFactory.class).createSdcDevice(
          new DeviceSettings() {
            @Override
            public EndpointReferenceType getEndpointReference() {
              return injector.getInstance(WsAddressingUtil.class).createEprWithAddress(eprAddress);
            }

            @Override
            public NetworkInterface getNetworkInterface() {
              return networkInterface;
            }
          },
          mdibAccess,
          new OperationInvocationReceiver() {},
          Collections.singleton(injector.getInstance(SdcRequiredTypesAndScopes.class)));

      seedMdibTree(devices);

      dpwsFramework.startAsync().awaitRunning();
      sdcDevice.startAsync().awaitRunning();
      logger.info("SDC provider up (epr=%s, interface=%s)".formatted(eprAddress, networkInterface.getName()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to start SDC provider", e);
    }
  }

  /** One SDCri injector, HTTP binding only (see class doc on security). */
  private static Injector createInjector() {
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

  /** Builds the static MDIB skeleton: MDS plus one VMD and channel per configured device group. */
  private void seedMdibTree(JSONArray devices) throws Exception {
    MdibDescriptionModifications mods = new MdibDescriptionModifications();

    MdsDescriptor mds = new MdsDescriptor();
    mds.setHandle(MDS_HANDLE);
    MdsState mdsState = new MdsState();
    mdsState.setDescriptorHandle(MDS_HANDLE);
    mods.insert(Pair.tryFromThrowing(mds, mdsState), null);

    for (Object deviceObj : devices) {
      String device = deviceObj.toString();
      VmdDescriptor vmd = new VmdDescriptor();
      vmd.setHandle(vmdHandle(device));
      VmdState vmdState = new VmdState();
      vmdState.setDescriptorHandle(vmd.getHandle());
      mods.insert(Pair.tryFromThrowing(vmd, vmdState), MDS_HANDLE);

      ChannelDescriptor channel = new ChannelDescriptor();
      channel.setHandle(channelHandle(device));
      ChannelState channelState = new ChannelState();
      channelState.setDescriptorHandle(channel.getHandle());
      mods.insert(Pair.tryFromThrowing(channel, channelState), vmd.getHandle());
    }
    mdibAccess.writeDescription(mods);
  }

  @Override
  public void push(DataPoint<?> dataPoint) throws IOException {
    Optional<CodedConcept> conceptOpt =
        mapping.lookup(dataPoint.className(), dataPoint.deviceID(), dataPoint.channelID());
    if (conceptOpt.isEmpty()) {
      return; // unmapped channel — not part of the MDIB
    }
    CodedConcept concept = conceptOpt.get();
    String handle = metricHandle(dataPoint);
    try {
      if (concept.isWaveform()) {
        pushWaveformSample(handle, dataPoint, concept);
        return;
      }
      // Metrics/settings run at clinical cadence: gate applies. The MDIB write is local and
      // cannot fail transiently, so the check-and-commit convenience is correct here.
      String gateKey = EmissionGate.keyFor(dataPoint.deviceID(), dataPoint.channelID());
      if (!gate.allow(gateKey, dataPoint.value(), System.currentTimeMillis())) {
        return;
      }
      pushMetric(handle, dataPoint, concept);
    } catch (Exception e) {
      // A rejected MDIB write is a permanent (programming/mapping) error for this datapoint, not
      // a transient transport failure: dead-letter instead of wedging the worker in retries.
      onDrop(dataPoint, e);
    }
  }

  private void pushMetric(String handle, DataPoint<?> dp, CodedConcept concept) throws Exception {
    boolean numeric = concept.isNumeric() && toBigDecimal(dp.value()) != null;
    declareIfAbsent(handle, dp, concept, numeric ? DescriptorKind.NUMERIC : DescriptorKind.STRING);

    AbstractMetricState state;
    if (numeric) {
      NumericMetricState s = mdibAccess.getState(handle, NumericMetricState.class).orElseThrow();
      NumericMetricValue value = new NumericMetricValue();
      value.setValue(toBigDecimal(dp.value()));
      value.setDeterminationTime(dp.timestamp());
      value.setMetricQuality(quality());
      s.setMetricValue(value);
      state = s;
    } else {
      StringMetricState s = mdibAccess.getState(handle, StringMetricState.class).orElseThrow();
      StringMetricValue value = new StringMetricValue();
      value.setValue(String.valueOf(dp.value()));
      value.setDeterminationTime(dp.timestamp());
      value.setMetricQuality(quality());
      s.setMetricValue(value);
      state = s;
    }
    mdibAccess.writeStates(new MdibStateModifications.Metric(List.of(state)));
  }

  private void pushWaveformSample(String handle, DataPoint<?> dp, CodedConcept concept)
      throws Exception {
    BigDecimal sample = toBigDecimal(dp.value());
    if (sample == null) {
      return; // non-numeric waveform sample — nothing SDC could stream
    }
    declareIfAbsent(handle, dp, concept, DescriptorKind.WAVEFORM);
    List<BigDecimal> buffer = waveformBuffers.computeIfAbsent(handle, h -> new ArrayList<>());
    buffer.add(sample);
    if (buffer.size() < waveformChunkSize) {
      return;
    }
    RealTimeSampleArrayMetricState state =
        mdibAccess.getState(handle, RealTimeSampleArrayMetricState.class).orElseThrow();
    SampleArrayValue value = new SampleArrayValue();
    value.setSamples(new ArrayList<>(buffer));
    value.setDeterminationTime(Instant.now());
    value.setMetricQuality(quality());
    state.setMetricValue(value);
    buffer.clear();

    MdibStateModifications.Waveform mods = new MdibStateModifications.Waveform(new ArrayList<>());
    mods.getWaveformStates().add(state);
    mdibAccess.writeStates(mods);
  }

  private enum DescriptorKind { NUMERIC, STRING, WAVEFORM }

  /** Inserts descriptor + state for a channel on its first datapoint (see class doc). */
  private void declareIfAbsent(String handle, DataPoint<?> dp, CodedConcept concept,
                               DescriptorKind kind) throws Exception {
    if (!declaredHandles.add(handle)) {
      return;
    }
    AbstractMetricDescriptor descriptor;
    AbstractState state;
    switch (kind) {
      case NUMERIC -> {
        NumericMetricDescriptor d = new NumericMetricDescriptor();
        d.setResolution(DEFAULT_RESOLUTION);
        NumericMetricState s = new NumericMetricState();
        descriptor = d;
        state = s;
      }
      case STRING -> {
        descriptor = new StringMetricDescriptor();
        state = new StringMetricState();
      }
      default -> {
        RealTimeSampleArrayMetricDescriptor d = new RealTimeSampleArrayMetricDescriptor();
        d.setResolution(DEFAULT_RESOLUTION);
        d.setSamplePeriod(samplePeriod);
        descriptor = d;
        state = new RealTimeSampleArrayMetricState();
      }
    }
    descriptor.setHandle(handle);
    descriptor.setMetricCategory(
        concept.kind() == CodedConcept.Kind.SETTING ? MetricCategory.SET : MetricCategory.MSRMT);
    descriptor.setMetricAvailability(MetricAvailability.CONT);
    descriptor.setUnit(ucum(concept.unit()));
    if (!concept.mdc().isEmpty()) {
      // Coded type only from an explicitly assigned 11073 nomenclature code; the default BICEPS
      // coding system (MDC) applies. LOINC codes are NOT projected into the type.
      CodedValue type = new CodedValue();
      type.setCode(concept.mdc());
      descriptor.setType(type);
    }
    state.setDescriptorHandle(handle);

    MdibDescriptionModifications mods = new MdibDescriptionModifications();
    mods.insert(Pair.tryFromThrowing(descriptor, state), channelHandle(dp.deviceID()));
    mdibAccess.writeDescription(mods);
  }

  /** UCUM-coded unit; BICEPS requires a unit on every metric, so blank becomes UCUM "1". */
  private static CodedValue ucum(String unit) {
    CodedValue value = new CodedValue();
    value.setCode(unit == null || unit.isBlank() ? "1" : unit);
    value.setCodingSystem("http://unitsofmeasure.org");
    return value;
  }

  private static AbstractMetricValue.MetricQuality quality() {
    AbstractMetricValue.MetricQuality quality = new AbstractMetricValue.MetricQuality();
    quality.setMode(GenerationMode.REAL);
    quality.setValidity(MeasurementValidity.VLD);
    return quality;
  }

  private static BigDecimal toBigDecimal(Object value) {
    try {
      return new BigDecimal(String.valueOf(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String vmdHandle(String device) {
    return sanitize("vmd." + device);
  }

  private static String channelHandle(String device) {
    return sanitize("channel." + device);
  }

  /** MDIB handle for a FRAMED channel: its canonical mapping coordinates. */
  static String metricHandle(DataPoint<?> dp) {
    return sanitize(dp.className() + "." + dp.deviceID() + "." + dp.channelID());
  }

  private static String sanitize(String handle) {
    return handle.replaceAll("\\s+", "_");
  }

  @Override
  public void pushBatch(List<DataPoint<?>> batch) {
    for (DataPoint<?> dp : batch) {
      try {
        push(dp);
      } catch (IOException e) {
        onDrop(dp, e);
      }
    }
  }

  /** The provider-side MDIB, for white-box assertions in tests. */
  LocalMdibAccess mdibAccess() {
    return mdibAccess;
  }

  @Override
  public void stop() {
    try {
      sdcDevice.stopAsync().awaitTerminated();
      dpwsFramework.stopAsync().awaitTerminated();
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "SDC provider shutdown failed", e);
    }
    super.stop();
  }
}
