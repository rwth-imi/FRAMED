# FRAMED Interoperability Plan

Standards-based interoperability for FRAMED's streaming biosignal/medical-device data,
delivered in three steps:

1. **HL7 v2.x over MLLP** — bidirectional HL7 messaging (enterprise / EHR boundary).
2. **Optional MQTT** — a lightweight streaming transport bridge (edge / telemetry boundary).
3. **IEEE 11073 SDC** — standards-conformant medical-device interoperability incl. waveforms
   (device-mesh boundary).

---

## 0. Guiding principles (apply to all three steps)

- **Edge quarantine.** Every interoperability standard lives *only* at the socket boundary.
  No standard's data model leaks onto the bus.
- **EAV invariant.** FRAMED's internal flow stays exactly as is: events are
  `DataPoint(timestamp, value, channelID, deviceID, className)` tuples on the `EventBus`,
  addressed by the existing convention `"<className>.<deviceID>.<channelID>.parsed"`. This is
  what keeps internal overhead low; "low overhead" is a property of the *bus*, not of how many
  edge classes we write.
- **Use the existing extension points — outbound is a sink, inbound is a source:**
  - **Outbound → `Dispatcher`.** Pushing FRAMED data to an external system *is* a sink. By
    extending `com.framed.io.dispatch.Dispatcher` we inherit the address-discovery
    subscription, the bounded async push queue with retry/backoff (bus thread never blocks on
    I/O), the dead-letter hooks, and JSON→`DataPoint` conversion — no bespoke queue to build.
  - **Inbound → `Protocol`.** Receiving external messages and emitting them onto the bus *is* a
    source; `connect()` opens the listener and publishes `.parsed` EAV events.
  - **Exception:** when **one transport connection inherently serves both directions** (MQTT:
    a single client both publishes and subscribes), use one bidirectional `Service` that owns
    that connection instead of opening two.
- **Discovery, not hard-wiring.** Dispatchers subscribe via `addressRegistry(group)`;
  protocols `announceAddress(group, addr)` + `publish` so existing reactors/dispatchers consume
  inbound data transparently.
- **Emission gating.** Standards at clinical cadence (HL7) must never see the raw stream.
  Outbound emission is gated by (a) *mapping presence* — only mapped channels are emitted, so
  high-rate `RealTime.*` waveforms are skipped — and (b) an explicit per-channel
  `minIntervalMs` / on-change rule applied inside `Dispatcher.push`.

### Launcher fit (a benefit of the Dispatcher/Protocol split)

Dispatchers go in the existing **`Dispatchers`** section and protocols in the existing
**`Devices`** section — both are already instantiated reflectively by `Factory`. **No launcher
change and no custom section are required.** Each class needs: public subclass of the right
base, a public constructor whose parameter names match the JSON keys (compiled with
`-parameters`, already enabled by the parent POM).

### Module layout

New module `framed-interop` (sibling of `framed-streamer`), parent `framed-parent`,
`Automatic-Module-Name: com.framed.interop`, depending on `framed-core` + `org.json`. Optional
heavy dependencies (MQTT, SDC) are isolated per package and pulled in only when that step is
built. `framed-app` depends on `framed-interop`; the existing shade config (strips
`module-info`, merges `META-INF/services`) handles it.

```
framed-interop/
  pom.xml
  src/main/java/com/framed/interop/
    mapping/                 # SHARED
      ObservationMapping.java   # channel <-> CodedConcept; forward + reverse (code->channel) index
      CodedConcept.java         # record(code, system, display, unit, valueType, mdc, kind)
    gate/EmissionGate.java   # SHARED: per-channel on-change / minInterval throttle
    hl7/    ...              # Step 1: Hl7v2Dispatcher (out) + Hl7v2Protocol (in) + shared MLLP/codec
    mqtt/   ...              # Step 2: MqttService (one bidirectional Service)
    sdc/    ...              # Step 3: SdcProviderDispatcher (out) + SdcConsumerProtocol (in)
  src/test/java/com/framed/interop/...
config/
  interop-mapping.json       # SHARED coding map, keyed by "<className>.<deviceID>.<channelID>"
  services_interop.json      # example wiring (Dispatchers + Devices entries)
```

### Shared building blocks

- **`ObservationMapping`** — loads `interop-mapping.json`: maps a FRAMED channel key to a
  `CodedConcept` (code + system + display + UCUM unit + value type, plus optional `mdc`
  11073-nomenclature code and `kind` for SDC). Reverse index (code → channel) for inbound.
  Resolution is most-specific → least-specific (`className.deviceID.channelID` →
  `className.channelID`) so a code can be shared across devices.
- **`EmissionGate`** — given a channel and a new value, decides whether to emit now
  (`onChange` and/or `minIntervalMs`). Reused by all outbound paths.

```jsonc
// config/interop-mapping.json
{
  "Percentage_int.PC60FW.SpO2": { "code": "59408-5", "system": "LOINC", "display": "SpO2 by pulse oximetry", "unit": "%",     "valueType": "NM", "mdc": "150456", "kind": "metric" },
  "BPM.PC60FW.PR":              { "code": "8867-4",  "system": "LOINC", "display": "Heart rate",             "unit": "/min", "valueType": "NM", "mdc": "147842", "kind": "metric" },
  "Measurement.Oxylog-3000-Plus-00.End-tidal CO2 concentration, etCO2": { "code": "19889-5", "system": "LOINC", "display": "etCO2", "unit": "mm[Hg]", "valueType": "NM", "kind": "metric" }
}
```

---

## Step 1 — HL7 v2.x over MLLP

**Goal:** report observations outbound as `ORU^R01` over MLLP, and receive/parse inbound HL7
(`ORU^R01`, `ADT^A01/A08`) returning ACKs — internal flow stays EAV. Outbound is the MLLP
*client* (initiator); inbound is the MLLP *server* (listener) — separate sockets, so the clean
model is **`Hl7v2Dispatcher` (out) + `Hl7v2Protocol` (in)** sharing the codec and mapping.

### Package

```
hl7/
  Hl7v2Dispatcher.java   # extends Dispatcher — outbound ORU^R01 over MLLP (client)
  Hl7v2Protocol.java     # extends Protocol   — inbound MLLP server -> EAV publish + ACK
  mllp/
    MllpCodec.java       # MLLP framing: 0x0B <msg> 0x1C 0x0D; partial-read assembly
    MllpClient.java      # connect + send + read ACK + auto-reconnect (used by dispatcher)
    MllpServer.java      # ServerSocket accept loop (used by protocol)
  hl7v2/
    Hl7Message.java      # lightweight: segments split on '\r', fields on '|^~\&'; escape/unescape
    OruBuilder.java      # DataPoint + mapping -> ORU^R01 string (MSH, PID, PV1, OBR, OBX)
    AckBuilder.java      # MSA / ACK string (AA / AE / AR)
    InboundRouter.java   # parse incoming -> EAV publish (ORU) or context (ADT); build ACK
```

### Codec decision

**Lightweight, hand-rolled HL7 v2 codec** (JDK + `org.json` only) — no HAPI. The message set is
small and known (ORU out; ORU/ADT in); avoiding HAPI's object model and dependency tree keeps
the footprint minimal. Trade-off: we own escaping/encoding/ACK parsing — acceptable for this
constrained set, and `Hl7Message`/`OruBuilder` are the only classes we'd swap for HAPI's
`PipeParser` if strict conformance against arbitrary third-party senders becomes required.

### Outbound — `Hl7v2Dispatcher extends Dispatcher`

```java
public Hl7v2Dispatcher(EventBus eventBus, JSONArray devices,        // base: discovery + async queue
                       String host, int port,                       // MLLP server to send to
                       String mappingPath, JSONObject patient,       // PID/PV1 defaults
                       JSONObject sendingIds,                        // MSH-3/4/5/6
                       JSONObject gate) { ... }                      // { minIntervalMs, onChange }
```

- Ctor: `super(eventBus, devices)` (inherits discovery + push queue), load `ObservationMapping`,
  build `EmissionGate`, seed MSH/PID/PV1, open `MllpClient`.
- `push(DataPoint dp)` (called on the base's worker thread): look up channel key; **unmapped →
  return** (waveform filter). Apply `EmissionGate`; if it passes, `OruBuilder` builds an
  `ORU^R01` (`OBX-3`=code^display^system, `OBX-5`=value, `OBX-6`=UCUM unit, `OBX-11`=`F`);
  `MllpClient.send` frames + sends + reads ACK. On `AE/AR` or transport failure → **throw
  `IOException`** so the base retries with backoff; give-ups hit `onDrop` (dead-letter).
- Optional coalescing: buffer due datapoints for a short window into one `ORU^R01` with multiple
  `OBX` to cut round-trips (internal time/size buffer; the base calls `push` per datapoint).
- `stop()`: close `MllpClient`, then `super.stop()`.

### Inbound — `Hl7v2Protocol extends Protocol`

```java
public Hl7v2Protocol(String id, EventBus eventBus, int port,
                     String mappingPath, JSONObject patient) { super(id, eventBus); connect(); }
```

- `connect()`: start `MllpServer` on `port`; frames handed to `InboundRouter`.
- `InboundRouter.handle(frame)`:
  - **`ORU^R01`** → per `OBX`, reverse-map code → `channelID`/`className`, then
    `announceAddress(deviceID, addr)` + `publish(addr, {timestamp, channelID, value, className})`
    in the `.parsed` convention. Downstream consumes transparently.
  - **`ADT^A01/A08`** → if `patient.ingestADT`, publish demographics as EAV datapoints
    (`Patient.<id>.MRN.parsed`, …); else ignore.
  - Always reply with an `ACK` (`AA`, or `AE` with detail) over the same connection.
- `stop()`: stop `MllpServer`.

### Tests

- Unit: `MllpCodec` frame/deframe incl. partial reads; `ObservationMapping` forward+reverse;
  `OruBuilder` field placement; unmapped channel skipped; `EmissionGate` logic.
- Inbound: canned `ADT^A08` + `ORU^R01` strings → assert published EAV channel/value and ACK.
- Loopback integration (Maven-profile-guarded so CI doesn't bind a port): `Hl7v2Protocol` on an
  ephemeral port + `Hl7v2Dispatcher` pointed at it over a `LocalEventBus`; publish a `DataPoint`
  → assert valid ORU out, re-emerges as the right `.parsed` EAV event, `AA` ACK received.

### Deliverables

`framed-interop` module; `hl7/**`; `config/interop-mapping.json`; example `Hl7v2Dispatcher` +
`Hl7v2Protocol` entries in `config/services_interop.json`; `framed-app` depends on
`framed-interop`; root `pom.xml` adds the module.

---

## Step 2 — Optional MQTT support

**Goal:** a lightweight streaming bridge for the edge/telemetry boundary. **Exception to the
Dispatcher/Protocol split:** a single MQTT client connection serves both publish and subscribe,
so this is **one bidirectional `MqttService extends Service`** owning that one connection
(opening a separate Dispatcher + Protocol would create two broker connections).

### Package & dependency

```
mqtt/
  MqttService.java       # extends Service — bidirectional bridge over one Paho client
  MqttCodec.java         # EAV DataPoint <-> MQTT payload (JSON: value, ts, code, system, unit)
```

Add `org.eclipse.paho:org.eclipse.paho.mqttv5.client` to the parent `dependencyManagement` and
to `framed-interop` as an **optional** dependency. Because it's optional, `framed-app` must
declare Paho directly to shade it into the fat-jar when MQTT is used.

### `MqttService`

```java
public MqttService(EventBus eventBus, String id, String brokerUrl, String clientId,
                   JSONArray devices,           // outbound EAV groups (discovery)
                   JSONArray subscribeTopics,   // inbound topics (may be empty)
                   String topicPrefix, int qos, // e.g. "framed", QoS 1
                   String mappingPath, JSONObject gate, boolean includeUnmapped) { ... }
```

- **Outbound:** subscribe to device groups via discovery; on datapoint apply `EmissionGate`;
  publish to `"<topicPrefix>/<deviceID>/<channelID>"`. Payload is self-describing JSON
  (`value`, `timestamp`, and the `CodedConcept` if mapped). MQTT's per-message overhead is
  small, so `includeUnmapped` *may* allow full-stream telemetry; default mapped-only. Paho's
  async client is already off the bus thread (no extra executor needed).
- **Inbound:** for each `subscribeTopics` entry, decode payload, reverse-map `code` →
  `channelID`/`className` (or derive from topic), `announceAddress` + `publish` as `.parsed`.
- `stop()`: disconnect the Paho client.

### Tests

- Unit: `MqttCodec` round-trip; topic construction; gate reuse.
- Integration (profile-guarded): embedded broker (Moquette / HiveMQ test) → publish a DataPoint,
  assert topic + payload; inbound message re-emerges as an EAV event.

### Note

MQTT carries **no clinical semantics** itself — the `CodedConcept` in the payload (LOINC / 11073
nomenclature) is what makes it interoperable. Cheapest standards-adjacent streaming path; ships
independently of Steps 1 and 3.

---

## Step 3 — IEEE 11073 SDC integration (plan)

**Goal:** standards-conformant device interoperability — metrics, **waveforms**, alarms — at the
device-mesh boundary. Heaviest step; phased and gated on a spike. Provider (out) and consumer
(in) are separate roles, so the Dispatcher/Protocol split applies: **`SdcProviderDispatcher`
(out) + `SdcConsumerProtocol` (in)**.

### Background

SDC is a stack: **BICEPS** (IEEE 11073-10207, the MDIB domain model — descriptors + states for
metrics, waveforms, alerts), **MDPWS** (11073-20702, web-services transport binding),
**11073-20701** (architecture/glue: DPWS/SOAP, WS-Discovery, WS-Eventing for streaming
notifications), and **11073-10101 nomenclature** (coded terminology — the streaming analogue of
LOINC; our mapping carries `mdc` codes for this). Real-time data flows as WS-Eventing episodic
metric reports and periodic **waveform streams** — genuinely designed for high-rate device data,
unlike HL7.

### Library spike (first task — de-risk before committing)

Evaluate a Java SDC implementation (primary candidate: **SDCri**, the SDC reference
implementation) rather than building BICEPS from scratch. Verify license compatibility (FRAMED
is GPL-2.0), Java 21 support, and maintenance status. **Decision gate:** no Phase B without a
working spike (provider + consumer exchanging one metric + one waveform). Fallback if no viable
lib: bridge SDC externally via the Step-2 MQTT service rather than implementing SDC in-process.

### Phased plan

- **Phase A — Spike & decision (time-boxed).** SDCri provider + consumer in a scratch test;
  stream one metric + one waveform; confirm license/JDK fit. Output: go/no-go + chosen dep.
- **Phase B — `SdcProviderDispatcher extends Dispatcher`.** Build an **MDIB** from config +
  mapping: each emitted channel becomes a BICEPS metric descriptor or real-time sample-array
  (waveform) descriptor, coded via the `mdc` field. `push(dp)` updates the corresponding MDIB
  *state*; SDCri emits the WS-Eventing report. Waveforms (`RealTime.*`) — which HL7 deliberately
  drops — are first-class here (buffer samples into real-time sample arrays at device rate). The
  `EmissionGate` is bypassed for waveforms (SDC handles full rate) but still applies to derived
  metrics.
- **Phase C — `SdcConsumerProtocol extends Protocol` (optional).** WS-Discovery to find remote
  providers; subscribe to metrics/waveforms; republish into FRAMED as `.parsed` EAV via reverse
  `mdc`→channel mapping. Lets FRAMED consume other vendors' SDC devices.
- **Phase D — Conformance & safety.** Map CDSS outputs to BICEPS alert system descriptors;
  document FRAMED's regulatory posture (SDC is used in regulated POC contexts — scope what
  FRAMED claims vs. leaves to integrators).

### Mapping extension

`interop-mapping.json` entries already reserve optional `mdc` (11073 nomenclature) and `kind`
(`metric` | `waveform` | `setting`) fields, so the same file drives HL7, MQTT, and SDC.

### Tests

- Phase A spike test (provider↔consumer loopback).
- Per-phase: MDIB construction from mapping; `push` → state update emits expected report;
  inbound report → EAV publish. SDC tests run only under a dedicated (heavy) Maven profile.

### Risk note

SDC is large, with a steep learning curve and a heavy transport (SOAP/DPWS). Keep it **isolated**
in `interop/sdc/**` with optional dependencies so Steps 1–2 never inherit its weight. If the
spike fails, the MQTT-bridge fallback preserves the interoperability goal without in-process SDC.

---

## Cross-cutting

### Wiring into the launcher (no launcher change)

| Class | Base | Launcher section |
|---|---|---|
| `Hl7v2Dispatcher`, `SdcProviderDispatcher` | `Dispatcher` | `Dispatchers` |
| `Hl7v2Protocol`, `SdcConsumerProtocol`     | `Protocol`   | `Devices` |
| `MqttService`                              | `Service`    | `Devices` (bare `Service` fits the source-like slot) |

All loaded reflectively by `Factory` (public subclass, public ctor, param names == JSON keys,
`-parameters` on). Dispatchers/protocols drop straight into existing sections; only `MqttService`
is a bare `Service`, placed under `Devices`.

### Config

- `config/interop-mapping.json` — shared coding map (LOINC + UCUM, plus `mdc`/`kind` for SDC).
- `config/services_interop.json` — example `Dispatchers` + `Devices` entries; the working
  `config/services.json` is left untouched.

### Build / POM changes

- Root `pom.xml`: add `framed-interop` to `<modules>`; add managed versions for Paho (Step 2)
  and the SDC lib (Step 3) in `dependencyManagement`.
- `framed-interop/pom.xml`: `framed-core` + `org.json` (always); Paho optional (Step 2); SDC lib
  optional (Step 3).
- `framed-app/pom.xml`: depend on `framed-interop` (and declare Paho / SDC directly when those
  optional steps are used, so they shade into the fat-jar).

### Sequencing & effort (rough)

| Step | Scope | Relative effort | Independent? |
|---|---|---|---|
| 1. HL7/MLLP | `Hl7v2Dispatcher` + `Hl7v2Protocol` + codec + mapping + tests | M | yes (ships first) |
| 2. MQTT | one `MqttService` + Paho + tests | S | yes (optional add-on) |
| 3. SDC | spike → provider dispatcher → consumer protocol → conformance | L (phased) | gated on spike |

Recommended order **1 → 2 → 3**: Steps 1–2 share the mapping + gate + the Dispatcher/Protocol
pattern and deliver value immediately; Step 3 reuses the (extended) mapping but is gated on a
spike.

### Out of scope (initial iterations)

FHIR (Observation / R5 Subscriptions); HL7 v2 query (QBP); TLS (MLLPS / MQTT-over-TLS hardening
beyond basic config); multi-patient routing by MRN; persistent store-and-forward beyond the
in-memory retry queue. All documented as follow-ups.