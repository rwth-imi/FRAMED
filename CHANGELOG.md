# Changelog

All notable changes to FRAMED are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Entries are scoped by **module**, describe the user/dev-visible effect (not the
diff), and flag any **config-schema or public-API change** explicitly.

## Released versions and archival identifiers

FRAMED is archived in [Software Heritage](https://archive.softwareheritage.org/browse/origin/?origin_url=https://github.com/rwth-imi/FRAMED)
rather than a DOI-minting repository, so each release is cited by **SWHID**
([ISO/IEC 18670:2025](https://www.swhid.org/)). There is no SWH counterpart to a Zenodo
concept DOI covering all versions; this table is the substitute, and the archive's
[origin page](https://archive.softwareheritage.org/browse/origin/?origin_url=https://github.com/rwth-imi/FRAMED)
is the stable human entry point.

Cite the **release** identifier. The qualified form is what resolves reliably — a bare
core identifier names the object but not where to find it.

| Version | Date | Release (`rel`) | Revision (`rev`) | Tree (`dir`) |
|---|---|---|---|---|
| 1.0.0 | _pending tag_ | _pending_ | _pending_ | _pending_ |

**Qualified identifier for 1.0.0** — _pending archival; add the `visit=swh:1:snp:…`
snapshot once the Save Code Now request reports `succeeded`._

```
swh:1:rel:<rel>;origin=https://github.com/rwth-imi/FRAMED;visit=swh:1:snp:<snp>
```

Per-module identifiers (each Maven module is a distinct archived directory, which is what
satisfies FAIR4RS F1.1 without any package registry) are listed under the release entry
below.

<!--
Maintenance template — copy entries into [Unreleased] under the right heading:

## [Unreleased]
### Added        new features / capabilities
### Changed      changes to existing behaviour
### Deprecated    soon-to-be-removed features
### Removed       removed features
### Fixed         bug fixes
### Security      vulnerability-related changes

On release: rename [Unreleased] to [X.Y.Z] - YYYY-MM-DD, add a fresh empty
[Unreleased] above it, and update the comparison links at the bottom.
-->

## [Unreleased]

_Nothing yet._

## [1.0.0] - 2026-09-21

The inaugural release: the first immutable, citable state of FRAMED. Version `1.0.0`
replaces `1.0.0-SNAPSHOT` across all twelve reactor modules — a `-SNAPSHOT` version is
mutable by definition and cannot satisfy FAIR4RS F1.2 (an identifier per version).

### Module identifiers

_Pending — computed from the `v1.0.0` tag and recorded here once it exists._

| Module | SWHID (`dir`) |
|---|---|
| `framed-core` | _pending_ |
| `framed-communicator` | _pending_ |
| `framed-streamer` | _pending_ |
| `framed-cdss` | _pending_ |
| `framed-interop` | _pending_ |
| `framed-app` | _pending_ |
| `framed-benchmark` | _pending_ |

### Changed
- **Build (all modules)**: version `1.0.0-SNAPSHOT` → **`1.0.0`**. Applies to the parent POM
  and all eleven module POMs, and to the fat-jar name referenced in the README
  (`framed-app-1.0.0-fat.jar`).

### Added
- **CI**: `.github/workflows/archive.yml` — requests a Software Heritage "Save Code Now"
  visit on every `v*` tag push and reports the visit's outcome, so a release cannot be
  tagged without being archived.
- **Docs**: Software Heritage archival badge in the README, and this released-versions
  identifier table.

### Security
- **Config**: the InfluxDB API token committed in `services_live.json`, `services_replay.json`
  and `services_data_collection.json` is replaced by the placeholder `<influxdb-api-token>`.
  **The token remains in git history and must be rotated in InfluxDB** — redacting the working
  tree does not unpublish it. CI now fails on any credential-shaped literal under `config/`.

### Removed
- **Build**: `mvnw` and `mvnw.cmd`. The wrapper never worked from a clean checkout because
  `.mvn/wrapper/` was gitignored and uncommitted; CI carried an `ignore-maven-wrapper`
  workaround for it, now also removed. **Build with system Maven 3.8+ (`mvn`).**
- **Repo**: stray editor and diagram-tool artefacts (`config/services.json.bak`, four
  `images/.$*.bkp`/`.dtmp` files). `.gitignore` gained `*.bak` and `.$*` so they stay out.

### Changed
- **Config (deployment-visible)**: `config/services.json`, the profile `Main` reads by default,
  is now a **self-contained runnable example** — `ReplayProtocol` over the committed
  `data/replay/p01-VC1-clean-0.jsonl` into `JsonlDispatcher`, with no device, database or
  network dependency. It previously pointed at a MIMIC-III record under an absolute
  `/home/<user>/…` path, so it could not run anywhere but one machine. No schema change.
- **Config**: the MIMIC profiles (`services_mimic.json`, `services_mimic_bench.json`) take their
  record from the relative `data/mimic/3000003.hea`. MIMIC-III is credentialed and may not be
  redistributed, so the dataset is not committed; `data/README.md` gives the download.

### Added
- **Docs**: `data/README.md` — provenance of the shipped replay recording (patient-simulator
  data, no personal data within the meaning of Art. 4(1) GDPR), its channel inventory, and how
  to obtain the MIMIC-III record the benchmark profiles expect.
- **CI**: a config-hygiene gate rejecting absolute machine-local paths and credential-shaped
  literals in `config/` before the build runs.
- **Docs**: `benchmark/kink-tcp/` — the **kink-replay-over-TCP experiment**, reproducing pyFRAMED's
  `services_kink_replay` deployment as two instances joined by `SocketEventBus` over TCP: a Java
  FRAMED instance replays the recorded session and sinks the classifications a pyFRAMED CDSS
  computes for it. One command renders both instances' configs, runs them, runs a single-process
  control, and reports classification agreement against that control alongside the sink's
  completeness and frame→verdict latency. Every artefact of a run — rendered configs, both logs,
  the captured classification series, the report — lands in one directory under
  `benchmark/results/`.
- **Docs**: the kink-tcp experiment records **which model artefact produced a run**. `run.json`
  carries the artefact path, the sweep CSV gains a `model` column, and the run report lists it
  beside the other parameters. The column is resolved from the run's own rendered pyFRAMED config
  rather than from `run.json`, so it is authoritative (all three reactors are pointed at one
  `modelPath`) and works retroactively on run directories measured before the runner recorded it.
  The runner's `MODEL` default moves to `config/models/kink_logreg_heldout_v1.json` — the
  development-only fit, which is the artefact a reported held-out number refers to — so the
  documented one-line invocation reproduces the tracked sweep. **CSV-schema change** for
  `benchmark/results/kink-tcp-sweep.csv`: `model` is inserted as the fourth column, after `record`.
  Tooling only — no API, behaviour, or framework config change.
- **Docs**: `benchmark/kink-tcp/analyse-kink-tcp.py` writes the sweep CSV's header when the file
  holds **no rows**, not merely when it is absent. Starting a fresh sweep by truncating the previous
  one in place (`: > file`) is the obvious thing to do and leaves a file that exists but is empty;
  the old existence check then appended to it head-less, producing a CSV whose first data row is
  silently read as its header by every reader. Tooling only.
- **Docs**: `benchmark/kink-tcp/compare-series.py` — compares *any two* classification series with
  the run analysis's own alignment, which is what makes the agreement figure interpretable: with two
  single-process controls of one recording, **control-vs-control** bounds how much of a
  run-vs-control difference is the reactor chain's own nondeterminism rather than a cost of
  distributing it. It also reports the two quantities the run analysis cannot, because its index is
  a dict keyed by `(offset, channel)`: how many classifications **collapse** onto an already-used
  key (the feature reactor stamps every vector pending at a firing with that one firing's logical
  time, so a suppressed firing puts two vectors on one timestamp), and the series' size after that
  collapse. `--self-test` checks the alignment and the collapse accounting on synthetic series,
  because no measured run collapses anything and that path would otherwise ship unexercised.
  Tooling only — no API, behaviour, or config change.
- **Docs**: `benchmark/kink-tcp/baseline.py` gains `--dispatch` (default `PER_HANDLER`, as a
  deployment runs), and records the mode in the control's `.meta.json`. The dispatch mode is the
  variable under test when two controls of one recording disagree: measured, two `SEQUENTIAL`
  controls are bit-identical while two `PER_HANDLER` controls share only 85.9 % of their decision
  instants. Tooling only.
- **framed-core**: event-bus messaging model (`EventBus` with `send`/`publish`/
  `register`/`shutdown`) and the abstract `Service` base with the
  `announceAddress`/`addressRegistry` channel-discovery handshake.
- **framed-core**: `LocalEventBus` (in-JVM) and `SocketEventBus` (peer-to-peer)
  implementations; configurable dispatch via `DispatchMode`
  (`SEQUENTIAL` / `PARALLEL` / `PER_HANDLER`) with per-handler override.
- **framed-core**: pluggable `Transport` layer — NIO and blocking TCP/UDP — with
  `Peer` and `RemoteMessage` primitives for distributed deployments.
- **framed-core**: the four extension-point base classes — `Protocol`, `Parser<T>`,
  `Writer<T>`, `Dispatcher` — plus the immutable `DataPoint` sample record.
- **framed-core**: config-driven orchestration — `ConfigLoader`, reflective
  `Factory` (matches constructor parameter names to JSON keys), `Manager`
  lifecycle registry, and the `Main` launcher.
- **framed-core**: `DeploymentValidator` SPI run once after startup; `ARN`
  validator enforcing acyclic reactor networks, discovered via `ServiceLoader`.
- **framed-core**: `Reactor` engine — firing rules (`*`, `N`, `r:v`), single
  consistent per-cycle snapshot, LIFO logical-time gate, optional atomic firing,
  and built-in per-channel/global/rule-participation latency instrumentation.
- **framed-communicator**: Draeger **Medibus** protocol over serial (jSerialComm)
  with real-time and slow-data parsers; **replay** protocol; **python** parser
  bridge; raw-byte and Medibus-parsed writers.
- **framed-communicator**: `MimicReplayProtocol` — a WFDB replay driver for the
  MIMIC-III Waveform Database that reads multi-segment records directly (no
  external tooling), decodes formats 80/16/212 to physical units via the
  segment/layout headers, and replays samples in scaled real time onto the
  EventBus as ordinary parsed `DataPoint`s, exercising the non-interop data path.
  Supporting pure decoders `WfdbHeader` and `WfdbSegmentReader` ship with unit
  tests. **New public API**; **new config schema** — instantiated from the
  `Devices` section with keys `recordPath`, `deviceID`, `className`, `channels`,
  `speed`, `maxSeconds`; a ready-to-run `config/services_mimic.json` profile is
  included.
- **framed-streamer**: `InfluxDispatcher` (InfluxDB v2), `JsonlDispatcher`
  (JSON-Lines), and `FfillDispatcher` (forward-fill), all non-blocking with a
  bounded retry queue tunable via `-Dframed.dispatcher.*`.
- **framed-streamer**: `CountingDispatcher` — an I/O-free sink that counts
  deliveries per channel, drops and parse failures, and builds an emit→sink
  latency distribution, so throughput measurements isolate the framework instead
  of the storage backend. Prints a one-line summary on `stop()` (to `stdout`, not
  the logger, which `LogManager` may already have reset inside a shutdown hook)
  and exposes the same figures via `snapshot()` / `awaitQuiescence(...)`.
  **New public API**; **new config schema** — instantiated from the `Dispatchers`
  section with the single key `devices`.
- **framed-communicator**: `MimicReplayProtocol` now measures its own **pacing
  fidelity** — per-frame lag against the scheduled emit time, frames that missed
  their slot, achieved vs. target Hz — and logs a one-line summary at the end of
  replay (**behaviour: new log line**). Available programmatically as
  `MimicReplayProtocol.pacingStats()` returning the new immutable `PacingStats`
  record. **New public API**; no config change (always on, allocation-free).
- **framed-benchmark**: **new module** for cross-module performance measurement.
  Test sources only — `jar`, `install`, `deploy` and `javadoc` are all skipped, so
  it ships nothing. Like `framed-app` it is a *consumer*: it may depend on several
  leaf modules at once (which the leaves themselves must not do), and nothing
  depends back into it, so the one-directional dependency flow is preserved.
  **Build-graph change** — a seventh reactor module.
- **framed-benchmark**: `MimicPacingBenchmark` sweeps replay speeds 1×/5×/10×/50×
  plus a max-rate run through the production `PER_HANDLER` dispatch into a
  `CountingDispatcher`, asserts zero drift and zero loss at real time, and writes
  `target/benchmark/mimic-pacing.csv`. Opt-in twice over — the class name is
  outside Surefire's default includes, so `mvn test` never runs it, and selecting
  it explicitly still skips unless `-Dmimic.record=/path/to/record.hea` points at
  a WFDB record; a checkout without the MIMIC dataset builds unchanged.
  `config/services_mimic_bench.json` plus `benchmark/run-full-app-bench.sh`
  reproduce the same measurement through `Main` on a `SocketEventBus` (the script
  backs up and restores `config/services.json`).
- **framed-cdss**: initial alarm-CDSS reactors (S/F computation, respiratory-rate
  estimation, limit/trend/rhythm/dislocation/RR-mismatch classification,
  interpretation) and supporting utilities.
- **framed-app**: runnable fat-jar assembly (shade) and launch configuration for
  the medical-device case study.
- **framed-interop** (new module): HL7 v2.x interoperability over MLLP, quarantined
  at the socket edge so the framework's internal flow stays on the EAV `DataPoint`
  model.
  - `Hl7v2Protocol` — inbound MLLP server that parses `ORU^R01`/`ADT` messages and
    republishes observations as `"<className>.<deviceID>.<channelID>.parsed"` bus
    events (the standard discovery convention), returning HL7 ACKs (`AA`/`AE`).
  - `Hl7v2Dispatcher` — outbound sink mapping datapoints to `ORU^R01` over MLLP;
    reuses the non-blocking `Dispatcher` queue/retry, emits only mapped channels,
    and throttles via an `EmissionGate` (`onChange` / `minIntervalMs`).
  - shared `ObservationMapping` (FRAMED channel ↔ LOINC/UCUM, bidirectional) and a
    dependency-free MLLP codec + HL7 v2 pipe parser / `OruBuilder` / `AckBuilder`.
  - ships with tests: socket-level simulations driving real `ORU^R01`/`ADT^A08`
    messages over MLLP, plus unit tests for the codec, mapping, gate, escaping,
    DTM/timestamp normalization, and builders; replay-driven end-to-end simulations
    feed the tracked bench recording (see `data/replay/` below) through both
    bridges — HL7 (dispatcher → MLLP socket → protocol → bus) and MQTT (outbound →
    payload → inbound) — asserting that exactly the channels of the shipped
    `config/interop-mapping.json` cross the boundary with values and observation
    times preserved.
  - `MqttService` — optional bidirectional MQTT bridge (one Paho 3.1.1 connection
    serves publish + subscribe): outbound publishes mapped datapoints to
    `"<topicPrefix>/<deviceID>/<channelID>"` (self-describing JSON payload carrying
    the coded concept), inbound republishes subscribed topics as `.parsed` bus
    events; reuses `ObservationMapping`/`EmissionGate`. Tested with an in-memory
    transport fake and end-to-end against an embedded Moquette broker.
  - **Config (new):** `config/interop-mapping.json` (channel→coded-concept map),
    `config/services_interop.json` (replay-driven interop simulation: the
    `framed-communicator` `ReplayProtocol` feeding the HL7 endpoints and the MQTT
    bridge — a config-level composition on the fat-jar classpath, no new module
    edge), and `config/services_mqtt.json` (MQTT bridge only).
- **data**: tracked replay recording `data/replay/p01-VC1-clean-0.jsonl` — one run
  of the curated bench-ventilation dataset (proband p01, volume-controlled, fault-
  free; Oxylog + PC60FW telemetry only, no personal data), with Oxylog channel ids
  normalized to the current Medibus short codes. Referenced by
  `config/services_replay.json`, `config/services_interop.json`, and the
  `framed-interop` replay simulations, replacing references to untracked files
  under the gitignored `output/` directory.
  - **New runtime dependency:** `org.eclipse.paho.client.mqttv3` (shaded into the
    fat-jar); `io.moquette:moquette-broker` is test-scope only.
  - **Dependency-graph change:** adds `framed-interop` (depends on `framed-core`
    only) and a new `framed-app → framed-interop` edge; shaded into the fat-jar.
- **framed-interop**: IEEE 11073 **SDC provider bridge** (INTEROP_PLAN Step 3,
  Phases A+B) — `com.framed.interop.sdc.SdcProviderDispatcher` exposes FRAMED as
  a BICEPS provider via SDCri (`org.somda.sdc:glue` 6.2.1, MIT): SDC consumers
  discover it over WS-Discovery, read an MDIB mirroring the mapped channels
  (metric descriptors created lazily on each channel's first datapoint), and
  subscribe to episodic metric reports and device-rate **waveform** streams
  (real-time sample arrays, gate-bypassed; chunk size and declared sample period
  configurable). Metrics/settings pass the `EmissionGate` like the other
  bridges. Currently binds plain HTTP — the SDC security profile (TLS) is
  deliberately not wired yet and is a precondition for clinical use.
  - **Config-schema change:** `interop-mapping.json` entries gained optional
    `mdc` (11073 nomenclature code; descriptor type is only coded when set) and
    `kind` (`metric`|`waveform`|`setting`, default `metric`); `code` is now
    optional (legal for SDC-only channels, which stay out of the reverse
    index). The shipped mapping adds a `kind=waveform` entry for
    `RealTime.Oxylog-3000-Plus-00.CO2_mmHg` and ships without `mdc` codes.
  - **Behaviour change:** `Hl7v2Dispatcher` and `MqttService` now skip
    `kind=waveform` mappings explicitly — waveforms cross only the SDC boundary.
  - **New optional dependency:** `org.somda.sdc:glue` is `<optional>` — neither
    downstream modules nor the fat-jar inherit the SDC stack; deployments add it
    to `framed-app` to activate the dispatcher. SDC tests (SDCri loopback spike
    + dispatcher end-to-end against a real SDCri consumer) run only under the
    `sdc` Maven profile (`mvn -pl framed-interop/sdc -am -Psdc test` since the
    per-standard module split — see *Changed*).
- **Build/CI**: Java 21 multi-module Maven reactor; GitHub Actions for build+test
  and aggregated Javadoc published to GitHub Pages.
- **framed-benchmark**: `MimicThroughputBenchmark` — a six-experiment throughput sweep over the
  MIMIC replay path (offered-load saturation, concurrent devices, sink fan-out, dispatch mode,
  real-time bed capacity, `LocalEventBus` vs `SocketEventBus`). Records datapoints/s on both sides,
  backlog at producer end, delivery ratio, drops, latency percentiles, late frames, peak heap, peak
  thread count and GC time; writes each point to CSV as it completes. Selected with
  `-Dtest=MimicThroughputBenchmark`, skips without `-Dmimic.record`. Test-only, ships nothing.
- **Docs**: `benchmark/CASE_STUDY_THROUGHPUT.md` — the executed study: method, results, seven
  findings and threats to validity, with `benchmark/analyse-throughput.py` (tables + figures from
  the CSVs), `benchmark/run-full-app-throughput.sh` (same measurement through `Main` on
  `SocketEventBus`), raw CSVs in `benchmark/results/` and figures in `benchmark/figures/`.
- **Docs**: `docs/uml/` — seven publication figures as PlantUML sources plus
  rendered PNG/SVG/EPS: module dependency graph, `framed-core` runtime and
  orchestrator, the five extension points, the implementation catalogue by
  contributing module, the interop boundary, the HL7/MLLP bridge internals, and
  a runtime sequence for one sample. `docs/uml/render.sh` re-renders them
  (fetches PlantUML on demand; uses the built-in Smetana layout, so Graphviz is
  not required). Documentation only — no API, behaviour, or config change.

- **framed-benchmark**: `SocketPairThroughputBenchmark` — the two-instance counterpart to
  `MimicThroughputBenchmark`. That sweep's bus comparison ran `SocketEventBus` with no peers
  attached, so the remote leg was never exercised; this one wires a producing instance to a reading
  instance over loopback (`LOCAL` / `NioTcpTransport` / `NioUdpTransport`) with the MIMIC replay on
  one side and a `CountingDispatcher` on the other, and reports delivery ratio as the headline
  metric rather than throughput. Three experiments (offered-load sweep, transport comparison,
  unprimed remote discovery), knobs under `-Dmimic.sp.*`, CSV per point to
  `target/benchmark/socket-pair.csv`. Selected with `-Dtest=SocketPairThroughputBenchmark`, skips
  without `-Dmimic.record`. Test-only, ships nothing.
- **Docs**: `benchmark/analyse-socket-pair.py` (tables from the socket-pair CSV) and
  `benchmark/make-figures.py` (figure generation across the benchmark CSVs). Tooling only — no API,
  behaviour, or config change.
- **Docs**: `benchmark/README.md` — the reproduction guide for both throughput studies: the
  uncommitted transport fixes a clean checkout of `83d7e85` still needs, prerequisites and pinned
  tool versions, the verified PhysioNet provenance of MIMIC record 3000125 (ODbL v1.0,
  DOI 10.13026/c2607m) with a per-record download, the exact harness invocations and their measured
  wall clock, a manifest mapping every CSV in `benchmark/results/` to the run that produced it, the
  recorded measurement host, and which figures do and do not reproduce across sessions.
- **Docs**: `benchmark/requirements.txt` — pins the figure toolchain (`plotly==7.0.0`,
  `kaleido==1.4.0`). The analysis scripts stay standard-library-only.

### Changed
- **Docs** — **the report figures use scientific axes.** Every non-linear axis now names its scale
  in its own title (`Offered load (datapoints/s, log scale)`), and every logarithmic axis sets an
  explicit, strictly positive range from the values it draws instead of taking plotly's autorange,
  which snapped outwards to whole decades and could suggest a series had been measured an order of
  magnitude below its smallest point. The symmetric-log transform is gone: it existed only to place
  a true zero on a log-like axis, and with strictly non-negative measures its linear core silently
  changed the meaning of distance near the bottom of the axis without the figure saying so.
  In its place — latency and lag are recorded in whole milliseconds, so a reported 0 ms is a
  *sub-resolution* reading: figA2/figA4/figB3 draw those points on a `<1` floor row of a log axis
  that starts below it. figA3 (backlog: exactly 0, or ~10⁶ datapoints) and figC2 (single-digit
  millisecond percentiles) span less than a decade of positive values and are now linear from zero,
  where nothing has to be censored; figA6's upper panel is likewise zero-based, so its vertical
  distances are proportional to the deliveries they represent. `make-figures.py --self-test` checks
  the axis helpers on synthetic values. Also **fixed**: figA4 clamped frame lag to a 0.5 ms floor
  before plotting, which would have hidden a sub-resolution reading silently; the clamp is removed
  and the axis handles the case. Tooling and presentation only — no measurement changed, and the
  figure file names are unchanged.
- **framed-communicator** — **`ReplayProtocol` can repeat a recording.** A speed multiplier alone
  cannot hold a benchmark's sample size: compressing the timeline shortens the run but not a
  downstream reactor's window, which is denominated in seconds and therefore swallows
  `window × speed` of recorded session before the chain emits anything. In the kink-over-TCP study
  that made the sweep incomparable with itself — at 60× the 6 s feature window consumed 360 s of the
  608 s recording, so the run produced verdicts for **41 %** of the session against 99 % at 1×, and
  the latency percentiles were drawn from 6 130 surviving events against 14 881. The new `repeat`
  key replays the recording back to back, shifting pass *r* by `r × cycle` where `cycle` is the
  recording's span plus one seam gap, so the emitted stream is a concatenation and not an overlay:
  stamps stay strictly ordered and no recorded instant is emitted twice. The seam gap is derived as
  the smallest positive interval between two distinct recorded stamps, so it is never wider than a
  transition the recording already contains and cannot register as a pause to a staleness timer.
  Passes are generated lazily over the single loaded copy rather than materialised — at 60
  repetitions of a 15 029-event session that would be ~900 k events on the very JVM whose latency is
  being measured. `targetHz` is unchanged by repetition, so a repeated run stays comparable on a
  sweep's offered-rate axis; at `repeat = 1` every emitted stamp and reported figure is identical to
  before. Note that a seam is a discontinuity in the *signal*, not just the clock, so a repeated run
  is **not** comparable against a single-pass control.
  **Config-schema change:** `ReplayProtocol` gains `repeat`, and — because `Factory` matches
  constructor parameters against config keys and leaves nothing to default — **it is required**.
  `config/services_replay.json`, `config/services_interop.json` and the kink-tcp harness template
  are updated accordingly. A defaulting overload was deliberately *not* added: with two
  constructors differing only by `repeat`, both match once the key is present and
  `Class.getConstructors()` has no defined order, so the key could be silently ignored.
- **Docs**: the kink-tcp runner couples repetition to speed. `REPEAT` defaults to
  `SPEED × TARGET_WALL / span` (with `TARGET_WALL` the recording's own span), so every paced run
  occupies the same wall clock and retains ~99 % of its session instead of 99 / 90 / **41 %** at
  1× / 10× / 60×. `JAVA_TIMEOUT` now budgets `repeat × span / speed` — the single-pass estimate
  capped a 60× run at ~225 s and would have SIGTERMed every repeated run partway through. `SPEED=0`
  and the single-process control stay at one pass, the latter because the agreement arm must see a
  stimulus identical to the control's. **CSV-schema change** for
  `benchmark/results/kink-tcp-sweep.csv`: `repeat` is inserted as the third column, after `speed`;
  the superseded sweep is kept alongside as `kink-tcp-sweep.csv.pre-repeat`.
- **Docs**: the kink-tcp sweep **re-measured on 2026-09-21** with the repetition coupling, replacing
  the tracked primary data. All three runs now occupy 608 s of wall clock and deliver **98.6 / 99.0
  / 99.0 %** of their session to the sink (1x / 10x / 60x), against 98.6 / 89.7 / **40.6 %** before,
  with 0 dropped and 0 handler errors over 218 404 classifications and the offered rate preserved to
  five significant figures (24.72 / 247.19 / 1483.12 Hz). The latency sample at 60x grows from 1 247
  events to 184 446. **This reverses the sweep's headline finding.** The superseded figures showed
  p99 falling from 3 ms to 2 ms at the highest load, which was an artefact: the percentiles were
  drawn from the 41 % of the session that survived the feature window, and the sink's `achievedDpPerS`
  divided by an active window that excluded the dead stretch, so figC3 sat on its proportionality
  line while 60 % of the session had produced no verdict at all. Measured over a full session the
  tail rises monotonically with load — p99 **5 -> 6 -> 9 ms** across the three speeds while the
  median stays flat at 3-4 ms, the signature of tail queueing. figC1-figC3 rebuilt from the new data.

- **Docs**: all three benchmark studies **re-measured on 2026-09-07**, replacing the tracked primary
  data. Study A (`mimic-throughput-repeat.csv`, 41 points / 113 runs) and Study B
  (`socket-pair-postfix.csv`, 27 points / 81 runs) reproduced their paced points to within 0.07 %
  and to the digit respectively, with the saturated points drifting as the reproducibility notes
  predict; the kink-tcp study (`kink-tcp-sweep.csv`, `kink-tcp-agreement.csv`) was re-measured
  against the `kink_logreg_heldout_v1` artefact and reproduced its conclusion structurally —
  `SEQUENTIAL` controls bit-identical, `PER_HANDLER` single-process replicates agreeing no better
  (84.1 %) than a distributed run against its control (82.3–82.4 %). Delivery was complete at every
  point of the kink matrix (0 dropped, 0 handler errors over 13 307 classifications, 1× to 60×). The
  superseded sweeps are kept untracked alongside as `*.pre-heldout` / `*.pre-20260907`, and all
  twelve report figures were rebuilt from the new data. Both READMEs' reproducibility sections,
  measured-run matrices and host records were updated; two documentation errors found against the
  new data are corrected there (the `latencyMax` stale-marker claim, which this session's six runs
  contradict as stated, and an env-table row claiming the control's dispatch mode is not settable
  through the runner, which `BASELINE_DISPATCH` has always done). Data and docs only.
- **framed-communicator** — **`ReplayProtocol` now preserves the recording's timing.** It stamped
  every replayed event with `Instant.now()` at publication, which splits a set of samples that
  shared one device frame timestamp into distinct ones and shifts every window edge by the replay
  thread's scheduling jitter — enough to change *which* results a downstream reactor network
  produces, since reactors key their windows, staleness guards and logical-time gate off those
  stamps. An event recorded at `t` is now published at, and stamped with,
  `replayStart + (t − firstRecordedTimestamp) / speed`: at `speed = 1.0` the recorded stamps
  shifted by one constant, so every interval and every shared frame timestamp is reproduced, while
  the stamps stay contemporaneous with the run and `now − timestamp` at a sink remains a real
  emit→sink latency. The protocol also announces each address once instead of on every event
  (halving its remote message rate) and logs its own pacing (`events`, `achievedHz`, `targetHz`,
  mean/max lag) before finishing, exposed as `ReplayProtocol.ReplayStats`.
  **Config-schema change:** `ReplayProtocol` gains `channels`, `speed`, `startDelaySeconds`,
  `graceSeconds` and `exitOnCompletion`, and — because `Factory` matches constructor parameters
  against config keys and leaves nothing to default — **all of them are required**.
  `config/services_replay.json` is updated accordingly.
- **framed-streamer** — **`CountingDispatcher` takes a `channels` filter.** One producer group can
  carry several stages of a pipeline (a reactor network announces every reactor's output under the
  single `CDSS` group), and an unfiltered sink then mixes the intermediate results into the latency
  distribution of the final one. The filter scopes the *figures* only: an unlisted channel still
  crosses the bus, is still parsed and still occupies the push queue, so a filtered run stays
  comparable to an unfiltered one. **Config-schema change:** `channels` is required (empty array =
  the previous, unfiltered behaviour); `config/services_mimic_bench.json` is updated accordingly.
- **Docs** — `benchmark/make-figures.py` moves the whole figure set onto one plain house style:
  stock Plotly colours (`qualitative.Plotly` for identity, `sequential.Blues` for the ordered
  latency percentiles) instead of the hand-mixed palette, white paper and white plot area with no
  tint, no explanatory prose inside the axes, and one wording per quantity across studies A, B and
  C (`Offered load (datapoints/s)`, `Throughput (datapoints/s)`, `Latency (ms)`, …). Everything the
  removed notes and direct labels used to say — the saturation knee, the hardware-thread limit, the
  decision threshold, the ideal line — is now a named legend entry or belongs in the caption.
  Tooling only; the measurements and the figure file names are unchanged.
- **Docs** — the report figures are drawn with Plotly instead of matplotlib, and
  `benchmark/make-figures.py` is now the single figure path for both studies. Same palette, same
  symlog treatment of the genuinely-zero measures; Kaleido renders through a headless Chrome, which
  the script locates itself (`--chrome` / `$FRAMED_FIGURES_CHROME` override).
- **Docs** — `benchmark/results/*.csv` are now tracked. They are primary measurements, not derived
  material: without them the reports' figures cannot be rebuilt from a clone. The run logs beside
  them stay ignored.
- **Docs** — Study A's design line is corrected from "113 operating points … 176 runs" to the
  measured **41 operating points, 113 runs** in `REPORT_COMBINED.md` and `REPORT_THROUGHPUT.md`;
  the drop-count sentence now says "in any of the 113 runs" rather than "at any of the 113 points".
- **framed-benchmark** — `SocketPairThroughputBenchmark` no longer sleeps 5 s between TCP runs. That
  pause existed because per-message connections left one socket in `TIME_WAIT` *per datapoint*; with
  pooled connections a run leaves one, so the port space no longer needs to recover between runs. Cuts
  the full 81-run sweep to under 10 minutes. Measurement semantics are unchanged — the pause was
  always between runs, never inside one.
- **Docs** — `benchmark/make-figures.py` takes `--pair-baseline` and draws Study B's figures as a
  before/after pair. Post-fix all three wirings are lossless, so the delivery-ratio figure plotted
  from current data alone would be a flat line at 1.0; the pre-fix sweep supplies the contrast. The
  pre-fix TCP series is drawn as the same entity in an earlier state (TCP's hue, separated by line and
  marker) rather than as a fourth category, coincident lossless series are nested by line width so
  none is hidden, and the latency axis no longer renders a negative region.
- **framed-communicator** — `MimicReplayProtocol` no longer re-announces a
  channel's address on **every** sample. The address is already announced up
  front from the layout header and again per segment before the frame loop, so
  the per-sample announcement only doubled event-bus traffic without adding any
  discovery. Sink binding is unchanged; the saving is proportional to the sample
  rate. **Behaviour change** (fewer messages on the `<device>.addresses` topic).
- **framed-interop** — **split into per-standard Maven artifacts.** The single
  `framed-interop` jar is now an aggregator (`packaging=pom`) with four child
  modules, so framework consumers import only the boundary they need:
  `framed-interop-common` (`ObservationMapping`/`CodedConcept` + `EmissionGate`;
  also publishes a test-jar with the shared `ReplayFixture`),
  `framed-interop-hl7`, `framed-interop-mqtt`, and `framed-interop-sdc`. Java
  package names and all class FQCNs are unchanged — existing `services*.json`
  configs keep working; only Maven coordinates changed.
  - **Dependency-graph change:** replaces the `framed-app → framed-interop` edge
    with `framed-app → framed-interop-{hl7,mqtt}` (the default fat-jar still
    ships HL7 + MQTT and no SDC stack; add `framed-interop-sdc` to `framed-app`
    to include it). Each bridge depends on `framed-core` +
    `framed-interop-common` only — bridges never depend on each other.
  - **Build change:** `org.somda.sdc:glue` is no longer `<optional>` but a
    required dependency of `framed-interop-sdc` — depending on that artifact is
    what activates SDC. `Automatic-Module-Name` is now per artifact
    (`com.framed.interop.{common,hl7,mqtt,sdc}`). SDC tests moved from the
    `src/test-sdc` source root into the sdc module's regular `src/test`, still
    gated behind the `sdc` profile (`mvn -pl framed-interop/sdc -am -Psdc test`).
- **framed-communicator** — **Medibus channel IDs are now stable short codes.**
  `ProtocolMap` exposes a structured `MedibusParam` record (`code`, `id`, `label`,
  `unit`) per protocol code instead of a single description string, and the Medibus
  parsers publish the short `id` as the `channelID` (e.g. `etCO2`, `FiO2`,
  `CO2_mmHg`, `RR`). Unknown protocol codes are now skipped-and-logged instead of
  producing a `null` channel id. The internal `poll_*` request constants were
  renamed to `UPPER_SNAKE_CASE`.
  - **Config-schema change:** Medibus channel addresses in `services*.json`
    changed — e.g.
    `Measurement.Oxylog-3000-Plus-00.End-tidal CO2 concentration, etCO2.parsed` →
    `Measurement.Oxylog-3000-Plus-00.etCO2.parsed`. The bundled `services.json`,
    `services_live.json`, and `services_replay.json` were migrated; external configs
    or recorded data referencing the old long IDs must be updated.

### Removed
- **Docs** — the matplotlib figure path in `benchmark/analyse-throughput.py` (`--figures`, writing
  `fig1-saturation` … `fig4-realtime-capacity`). It duplicated four of `make-figures.py`'s figures
  in a second style and was referenced by no report. The script keeps its tables, and no longer
  needs matplotlib at all.
- **Docs** — the pre-fix TCP series from `REPORT_COMBINED.md` and its figures. The per-message
  connection model is fixed, so its measurements describe code that no longer exists; the defect
  itself is still described under Methods. `figB1-delivery` (whose point was that comparison, and
  which without it is a flat line at 1.0) is replaced by `figB1-threads`, the peak-thread footprint
  the study's remaining-bottleneck conclusion rests on.

### Fixed
- **framed-app / config** — **`config/services_interop.json` could no longer start its replay
  device.** `ReplayProtocol` gained five required keys (`channels`, `speed`, `startDelaySeconds`,
  `graceSeconds`, `exitOnCompletion`) and only `config/services_replay.json` was updated with them;
  `Factory.instantiate` matches constructor parameters against config keys and leaves nothing to
  default, so the interop profile failed with `No matching constructor found` at startup. The entry
  now carries all nine keys, with `exitOnCompletion: false` so the HL7 and MQTT bridges outlive the
  replay they share the recording with. **Config change** to a shipped deployment profile.
- **config** — **`config/services_live.json` could not start six of its reactors.** Its
  `LimitClassificationReactor` and `TrendClassificationReactor` entries were written against an
  older per-channel-keyed API — an `inputChannels` array with `limits`/`windowSizes`/`deltas` as
  objects keyed by channel — while both constructors now take a single `inputChannel` and scalar
  limits, so `Factory` raised `No matching constructor found` for each. `config/services_replay.json`
  carries the migrated form of the same network, and the six entries are now ported from it: the
  two multi-channel limit classifiers split one-reactor-per-channel (`PulseOxi-Limit-Classifier`
  becomes `SpO2-Limit-Classifier` + `PI-Limit-Classifier`, and the `S/F` classifier's SpO2 boundary
  folds into the SpO2 classifier's `[87, 97, 98]`), the pulse-rate channel corrects from
  `Percentage_int.PC60FW.PR.parsed` to `BPM.PC60FW.PR.parsed`, and both trend windows move from
  6/8 to 10 samples. **Config change** to a shipped deployment profile, and it moves three alarm
  parameters — the values are those the replay profile already deployed, adopted deliberately
  rather than inferred. The `PulseOxi-Limit` output channel is gone; nothing in the profile
  consumed it. `Dislocation-Classifier` is untouched: its key set already matches its constructor.
- **framed-streamer** — **`CountingDispatcher.awaitQuiescence` could report "drained" while the push
  queue was still busy.** A datapoint the channel filter excluded returned before stamping the
  activity clock, so idleness was inferred from *counted* arrivals only. The filter scopes the
  figures, not the traffic — an unlisted channel's datapoints occupy the same queue — so a filtered
  sink measured with the documented `awaitQuiescence`-then-`snapshot()` pattern could return one
  quiet period into a burst of intermediate traffic and undercount. This is the configuration the
  kink CDSS sink runs in (`"channels": ["Kink", "Kink-Probability"]` against a group that also
  carries the feature and component vectors). Every arrival now marks the sink busy, counted or not;
  the counters and the latency histogram stay filtered. Same fix in `onDrop`. Regression tests
  cover both paths.
- **Docs** — **`benchmark/make-figures.py`'s Study C figures did not support the claims made from
  them.** Four defects, all in the kink figures: `figC1` drew the decision threshold at 0.3 while
  the runs were classified at 0.4 (it is now read out of the run's own rendered config, so it cannot
  drift again); `figC1` aligned each series on its own first record, contradicting the alignment the
  run report uses and quotes (it now calls the analysis's aligner, imported rather than copied);
  `figC2`/`figC3` plotted the flat-out run, which received nothing by construction, as a real
  operating point — its empty-histogram zeroes became the sweep's best apparent latency at its
  highest offered load (rows with no arrivals are now excluded, and the exclusion is reported on
  stdout); and `figC3` plotted the producer's achieved rate against its own target, which is `y = x`
  by construction of the pacing loop and says nothing about the CDSS chain (it now plots the sink's
  `achievedDpPerS` against offered load, with the proportion measured at the slowest point as the
  reference, since verdicts-out and events-in are different quantities). Figures only.
- **Docs** — **the kink study's agreement figures were stated more strongly than the data supports,
  and its primary agreement table was transcribed rather than derived.** `maxProbabilityDelta` is a
  single-sample maximum, and the two distributed-vs-single pairs' 0.446/0.442 turn out to be one
  instant each at the session's first kink onset, where the probability crosses [0, 1] within a few
  frames; every other instant agrees as closely as any single-process pair. Both tools now report
  the whole distribution (`probabilityDeltaP50/P95/P99` and a count of instants above 0.1) beside
  the maximum, the run analysis carries `compare-series.py`'s collapse counter and refuses to
  certify agreement while it is non-zero, and both carry `matchedKink`/`alignedKink` — the
  `Kink`-channel half of the aligned instants, which is the denominator a label-mismatch figure is
  actually a fraction of. `compare-series.py` gains `--csv`, so `kink-tcp-agreement.csv` is derived
  by six documented commands instead of typed up from six console outputs (which is how the README's
  "run vs run" row came to claim 2 532 aligned against the CSV's 2 550, and how two rows came to be
  ordered control-first under a run-first name). Both CSV writers now refuse a file whose header
  does not match the columns they write. **CSV-schema change** for
  `benchmark/results/kink-tcp-sweep.csv` and `kink-tcp-agreement.csv`; both are re-derived from the
  2026-09-07 run directories, and every figure the previous files carried reproduces unchanged.
- **Docs** — **four reproducibility claims in `benchmark/README.md` overstated the tracked CSVs.**
  "Paced points below saturation reproduce to the digit" holds for neither `sinkSustainedDpPerSec`
  (identical at 7 of 12 zero-backlog points) nor `deliveredDpPerSec` (within 0.07 %, differing in
  the third significant figure), and the claim now names the quantity and the tolerance. Study A's
  0.07 % agreement was attributed to "E1's fifteen paced operating points" when three of them are
  past the knee and move by 7.6–11.7 %. Study B's "all 21 paced points below transport saturation
  reproduced to the digit" was wrong on both counts — 21 is the whole paced sweep including four
  saturated points, and 3 of the 17 genuinely sub-saturation points moved in their last digit. And
  `TCP@1x-unprimed`'s loss was described as "the same 0.10 % in both" sessions and therefore
  reproducible, where the CSVs show 2 of 3 repeats losing exactly one datapoint in one session and
  1 of 3 in the other — a bounded magnitude, not a reproducible loss. `UDP@200x`'s +37 % is now
  reported for what it is: the point stopped being saturated between sessions. Docs only.
- **Docs** — **the kink study's dispatch-mode attribution is now labelled as an inference.** The
  bit-identical `SEQUENTIAL` pair is single-process only, so it cannot separate dispatch
  nondeterminism from transport-induced reordering; the README names the missing distributed
  `SEQUENTIAL` arm as the measurement that would settle both that question and the first-onset
  probability difference above. Docs only.
- **Docs** — **the kink-over-TCP experiment deployed a configuration the model artefact no longer
  matched.** `benchmark/kink-tcp/services_python.json.template` pinned `components: 8`,
  `componentSelection: "label"` and `threshold: 0.3`, while the artefact
  (`config/models/kink_logreg_v1.json`, retrained to the selected `k=10 / variance-ranked /
  threshold 0.4` configuration) provides ten variance-ranked components. Those two reactor keys are
  assertions against the artefact rather than settings, so `Kink-Reducer` refused to instantiate —
  and that did *not* stop the run: pyFRAMED's `Manager` logs the failure and wires what is left, the
  ARN still reports a network (two reactors, no edges), and the run replayed all 15 029 events for
  ten minutes producing feature vectors and **not one verdict**. The template now mirrors
  pyFRAMED's `config/services_kink_replay.json` exactly and additionally asserts `windowS`,
  `emitEvery` and `includePulseOx`, so a future artefact change fails the run instead of silently
  changing what it measures. **Config change** — to a benchmark template, not to a FRAMED schema.
  Measurements taken with the previous template are not comparable and were discarded.
- **Docs** — `run-kink-tcp-bench.sh` treated "Reactor Network instantiated" as readiness, which a
  partially-wired network also logs. It now refuses to start the replay when `python.log` carries a
  `Failed to instantiate Service` line, so an incomplete chain fails in seconds instead of after a
  full-length run whose only symptom is an empty classification count.

### Fixed
- **framed-benchmark** — `SocketPairThroughputBenchmark` wrote its CSV with the default locale, so on
  a JVM with a decimal comma (de_DE among them) every float split across two columns and shifted all
  downstream fields, silently corrupting the file the analysis scripts read. The CSV line is now
  formatted with `Locale.ROOT`.

### Fixed
- **framed-core** — **the NIO TCP transport opened one connection per message.**
  `NioTcpTransport.sendMessage` opened a fresh `SocketChannel`, connected, wrote and closed it for
  every message, so one datapoint cost one TCP connection. The receiving instance accepts on a
  single selector thread and cannot drain the listen backlog at that rate: measured against the
  MIMIC replay, TCP saturated near 7,400 dp/s and then *lost* 10–30 % of datapoints — a run
  publishing 119,125 lost 28,674 while logging **two** send failures, because a handshake completing
  into a backlog the server never accepts carries data that is discarded while `write` still returns
  successfully (142,366 kernel `ListenOverflows` across one run).
  **Fix:** one connection is now kept open per `host:port` and reused; the receiver already framed
  the stream by newlines, so **the wire format is unchanged**. Writes to one peer are serialized
  (concurrent senders would interleave partial JSON and break the framing) and blocking, which turns
  silent loss into backpressure. A dead cached connection is re-established once before the send is
  reported as failed. Re-measured: delivery ratio **1.0000 at every offered load tested** (was
  0.71–0.89 above saturation), throughput at saturation **5,236 → 46,012 dp/s (8.8×)**, and TCP now
  tracks the in-process bus exactly to 18,579 dp/s. Tunable via
  `-Dframed.transport.tcp.connectTimeoutMs` and `-Dframed.transport.tcp.maxPendingBytes`. No API or
  config change.
- **framed-core** — **`NioTcpTransport.shutdown()` leaked its accepted connections.** It closed the
  selector and the listening socket, but closing a `Selector` does not close the channels registered
  with it, so every accepted connection outlived the transport. With the previous per-message
  connections this was masked (the sender closed its own side); with pooled connections it means a
  peer keeps writing into a socket whose reader is gone — every write succeeds into the receive
  buffer and the data is silently discarded, with no exception on either side. Accepted channels are
  now tracked and closed on shutdown, so a peer sees the drop and reconnects.
- **framed-core** — **TCP reads corrupted multi-byte UTF-8 characters.** `handleRead` decoded each
  TCP chunk independently and accumulated the resulting *strings*, so a character straddling two
  reads became replacement characters. Reads are now accumulated as bytes and split on the newline
  byte before decoding — safe for UTF-8, since no byte of a multi-byte sequence can equal `0x0A`.
  Relevant for medical payloads, which carry such characters routinely (µV, °C). Longer-lived
  connections make the split far more likely, so this shipped with the pooling fix.
  Regression tests: `NioTcpTransportTest` (reuse, per-peer isolation, many messages on one
  connection, split-character decoding, peer restart).
- **framed-core** — **the NIO UDP transport received nothing.** `NioUdpTransport`'s selector loop
  cleared its `ByteBuffer` and immediately flipped it *without ever calling*
  `DatagramChannel.receive(..)`, so it decoded an empty buffer and dispatched nothing. Because the
  datagram was also never consumed, the selection key stayed ready and the loop spun at full tilt.
  Any deployment configured with `"type": "UDP"` therefore carried no remote traffic at all, and did
  so silently — no exception, no warning.
  **Fix:** the loop now receives into the buffer and drains every queued datagram before returning
  to `select()` (readiness is reported once per arrival, so a single receive per wakeup leaves the
  key hot); a datagram that fails to parse is logged and skipped instead of propagating out of the
  loop; the receive buffer grew from 4 KiB to 64 KiB, which is the practical IPv4 datagram ceiling,
  so a normal-sized message can no longer be silently truncated. No API or config change.
  Regression tests: `NioUdpTransportTest` (delivery, bulk drain, malformed datagram) and
  `SocketEventBusPeerTest` (bus-to-bus publish over each transport — the two-instance path had no
  coverage in either direction, which is how this shipped).
- **framed-core** — **channel announce/bind race at startup.** A producer announced an output
  channel and published on it immediately, while sinks bound the announced address on another
  thread; under `PER_HANDLER` (the mode `Main` runs) the samples published in that window went to an
  address with no handler and `publish` discarded them silently — no error, no drop counter, just
  missing data. Measured at 2 of 3,000,000 datapoints in a full-application MIMIC replay, and 160 of
  800 in a test with eight devices announcing concurrently.
  **Fix:** sinks now subscribe through the new `Service.subscribeToAnnouncements(group, binder)`,
  which registers with `DispatchMode.SEQUENTIAL` so the binder runs inline on the announcing thread
  and `Service.announceAddress(..)` cannot return before every sink has bound.
  **Public API (framed-core):** `Service.subscribeToAnnouncements(String, Consumer<String>)` added
  (protected). **Sinks must use it instead of `eventBus.register(addressRegistry(group), ..)`**,
  which reintroduces the race; `announceAddress`'s contract is documented accordingly. `Dispatcher`
  and `MedibusParsedWriter` were migrated, and `MedibusParsedWriter`'s bound-address list became a
  concurrent set because announcements now arrive on several producer threads rather than on one
  executor. Regression test: `AddressDiscoveryHandshakeTest` (framed-core).
- **framed-app** — **stale service profiles instantiate again.** Two silent
  failure modes had accumulated in the bundled configs: (1) lowercase
  `devices`/`writers` section keys, which the case-sensitive `Main` skips without
  warning (`services_replay.json`, `services_live.json`,
  `services_data_collection.json` started no devices or writers at all); and
  (2) reactor entries missing the `atomic` key that the `Reactor` constructors
  require since the atomic/non-atomic evaluation, making `Factory` reject every
  CDSS reactor with `No matching constructor found` (all 13 in
  `services_replay.json`, all 11 in `services_live.json`; only `services.json`
  had been migrated). Section keys are now capitalized everywhere, all reactor
  entries carry `"atomic": false` (matching `services.json`), and the serial
  `MedibusProtocol` entry was dropped from the replay profile — replay is its
  sole source. Verified by replaying `data/replay/p01-VC1-clean-0.jsonl` through
  the full CDSS chain. **Config-schema note:** section keys in `services*.json`
  must be capitalized (`Devices`, `Writers`, …), and every reactor entry must set
  `atomic`.
- **framed-interop** — **gate commits split by filter, correct in both failure
  directions.** `EmissionGate` now separates the side-effect-free check
  (`allows`) from per-filter commits: `commitAttempt` records the *interval*
  state when an emission attempt starts (a down endpoint is probed at most once
  per interval instead of once per sample, keeping the dispatcher queue drained
  during outages), and `commitValue` records the *onChange* state only after the
  receiver accepted the value (a failed **or NAK'd** send no longer suppresses
  an identical follow-up value — previously a transiently rejected stable vital
  went silent for as long as it stayed stable). `Hl7v2Dispatcher` recognizes the
  base `Dispatcher`'s retry of its own in-flight datapoint (stable control id)
  and lets it bypass the gate, bounded by a retry budget (default 30 s) after
  which the datapoint is dead-lettered so a dead endpoint cannot wedge the
  single push worker. The gate is also keyed per `deviceID.channelID` via the
  canonical `EmissionGate.keyFor`, so identically named channels on different
  devices no longer suppress each other. **Public-API change:**
  `EmissionGate.allows`/`commitAttempt`/`commitValue`/`commit`/`keyFor` added;
  `allow` remains as the check-and-commit convenience.
- **framed-interop** — **collision-safe HL7 control ids.** MSH-10 is now the
  first 80 bits of a SHA-256 over the datapoint identity (20 hex chars, within
  the HL7 v2.5 length limit) instead of a 32-bit `Objects.hash`, whose birthday
  collisions would have made de-duplicating receivers silently discard real
  observations. Retries still reuse the same id. *(Wire-visible change.)*
- **framed-interop** — **HL7 escaping, both directions.** All observation,
  mapping, patient and configuration data embedded in outbound `ORU^R01`/ACK
  messages is escaped per HL7 v2.5 §2.7 via the new `Hl7Escape` helper — the
  five delimiters (a value containing `|` or `^` previously shifted subsequent
  fields) **and CR/LF as `\X0D\`/`\X0A\` hex escapes** (an embedded newline,
  e.g. in an exception message quoted in an AE ack, previously injected a bogus
  segment break that broke the receiver's parse). The inbound side now decodes:
  `InboundRouter` unescapes OBX-5 values and ADT demographics via
  `Hl7Escape.unescape`, so string values round-trip across the boundary instead
  of surfacing as literal `\F\` sequences on the bus; unknown escape sequences
  (e.g. formatting escapes) are preserved verbatim. The configured PV1-3
  location keeps its component structure. **Public-API addition:** `Hl7Escape`
  (`field`/`components`/`unescape`).
- **framed-interop** — **observation timestamps survive the HL7 hop.** Outbound
  OBX segments now carry the observation time in OBX-14, and `InboundRouter`
  parses OBX-14 as a full HL7 DTM (explicit zone offsets honoured — including
  the ISO-style `+02`/`+02:00` variants real senders emit — and reduced
  precisions padded) instead of assuming exactly 14 UTC digits; absent or
  malformed values — including regex-matching but out-of-range offsets like
  `+1900`, which would otherwise have NAK'd the whole message mid-ingest — fall
  back to arrival time with a warning, never silently. *(Wire-visible change.)*
- **framed-interop** — **MQTT inbound timestamps normalized.** External
  timestamps are converted to the bus convention (UTC, `Timer.formatter`),
  accepting the bus format, ISO-8601 with or without offset, and the common
  epoch conventions (13 digits = milliseconds, 10 digits = seconds — numeric
  JSON timestamp fields included); previously an arbitrary external format was
  passed through unvalidated (rejected by every strict-parsing subscriber),
  epoch timestamps were silently replaced by arrival time, and the fallback
  stamped local wall-clock time instead of UTC.
- **framed-interop** — **a broker that is down at startup no longer costs the
  MQTT leg permanently.** `MqttService` used to connect eagerly in its
  constructor and `Manager` swallows constructor failures, so losing the
  startup race against the broker silently dropped the bridge until a restart.
  The constructor now survives a failed connect (warning) and a daemon thread
  retries connect+subscribe until it succeeds or the service stops; once
  connected, later drops are healed by Paho's automatic reconnect. Re-subscribes
  reuse one shared handler instance so overlapping-filter de-duplication holds.
- **framed-interop** — **batched ORU observations keep their own times.** The
  multi-observation `OruBuilder.build` overload stamped every OBX-14 with the
  single message time; `Observation` now carries a per-observation timestamp
  and each OBX is stamped from it (MSH-7 remains the message time).
  **Public-API change:** `OruBuilder.Observation` gained a `timestamp`
  component.
- **framed-interop** — `PahoMqttTransport` invokes a handler at most once per
  delivered message even when several of its topic filters overlap (previously
  each matching filter re-published the same observation onto the bus);
  `MqttService` registers one shared handler instance across its subscriptions
  so this de-duplication applies.
- **config** — `services_interop.json` example no longer points the outbound
  HL7 dispatcher at the same port as FRAMED's own inbound MLLP server, which
  would re-emit every received observation in a feedback loop (documented as a
  deployment caution on `Hl7v2Dispatcher`); the profile's external
  prerequisites (MLLP receiver on 2576, MQTT broker on 1883) are documented in
  `INTEROP_PLAN.md` → “Running the interop simulation profile”.

### Notes / known gaps

- **No backpressure to the producer under `PER_HANDLER`.** Measured in
  `benchmark/CASE_STUDY_THROUGHPUT.md`: `Dispatcher`'s bounded push queue and its drop-on-overload
  policy sit behind the bus's *unbounded* per-handler executor queue, so the shedding policy never
  engages. Overload past ~562 k datapoints/s is absorbed as heap (~3 GB) and latency (~2 s) with
  **zero** drops, and effective capacity then falls as GC time rises. Bounding the per-handler queue
  is the fix.
- **`PER_HANDLER` allocates roughly 7 threads per replayed device.** 256 concurrent 125 Hz beds cost
  1,801 live threads at only 16 % of the throughput ceiling, so thread footprint — not datapoint
  throughput — is what caps a node's bed count.

- Test coverage now also covers **framed-communicator** (`ProtocolMapTest`, WFDB
  decoders, MIMIC replay + pacing), **framed-interop** (codec/mapping/gate/builder
  unit tests + MLLP simulations) and **framed-streamer** (`CountingDispatcherTest`
  — the module's first tests); **framed-cdss** still has none.
- The millisecond histogram behind the pacing and latency summaries is duplicated
  in `framed-communicator` and `framed-streamer` (package-private
  `MillisHistogram` in each). Leaf modules must not depend on one another, and
  promoting it to `framed-core` would enlarge the exported SDK surface for a
  measurement helper; the duplication is the deliberate cheaper trade.
- `Main` reads capitalized `services.json` section keys
  (`Devices`/`Parsers`/`Writers`/`Dispatchers`/`Reactors`); lowercase sections in
  older example configs are ignored.
- Package publishing (deploy to GitHub Packages) is not wired yet.

[Unreleased]: https://github.com/rwth-imi/FRAMED/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/rwth-imi/FRAMED/releases/tag/v1.0.0
