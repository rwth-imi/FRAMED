# Kink classification over TCP — a Java FRAMED replay driving a pyFRAMED CDSS

A reproducible end-to-end experiment: a **Java FRAMED** instance replays a recorded ventilation
session and sinks the kink classifications that a **pyFRAMED** CDSS computes for it, the two
instances joined by `SocketEventBus` over **TCP**. It is the distributed form of the deployment
described by pyFRAMED's `config/services_kink_replay.json`, which runs replay, reactors and sink
inside one Python process.

Two questions, answered from two independent artefacts of the same run:

1. **Does splitting the deployment change the clinical output?** The classification series captured
   at the Java sink is compared event for event against a single-process control — and, because
   that comparison only means something if the control is reproducible, against a *second* control.
   **Measured: on every figure that has a same-deployment reference to read it against, no.** Under
   the deployed `PER_HANDLER` dispatch two single-process controls of one recording share only
   ~83 % of their decision instants; a distributed run against its control shares 82.3–82.4 %,
   inside that spread. On the instants both series carry, the probabilities agree to ~1e-6 at the
   median and ~0.01 at p95 — again indistinguishable between the two deployments. Two `SEQUENTIAL`
   controls are bit-identical, which *points at* the dispatch mode; that contrast has no distributed
   arm, so it is a reasoned attribution rather than a measured one. See
   [Agreement with the control](#agreement-with-the-control).
2. **What does the path cost?** A `CountingDispatcher` — a sink that does no I/O and only counts —
   reports how many classifications arrived, how many were dropped, and the distribution of
   *frame&rarr;verdict* latency.

---

## Topology

```
 Java FRAMED (com.framed.orchestrator.Main)          pyFRAMED (python -m pyframed)
 ───────────────────────────────────────────         ─────────────────────────────
  ReplayProtocol  ── 15 029 recorded events ──►  SocketEventBus :4999
      p10-VC1-knick-0.jsonl                              │  TCP
                                                         ▼
                                              SocketEventBus :5999
                                                         │
                                              FeatureScalingReactor   (Paw-paced, 6 s window)
                                                         ▼
                                              PCAReductionReactor     (10 components, variance-ranked)
                                                         ▼
                                              KinkClassificationReactor (threshold 0.4)
                                                         │  Kink, Kink-Probability
  CountingDispatcher   ◄──────── TCP ──────────────────  ┘
  JsonlDispatcher                        announced under the "CDSS" group
```

Both instances speak the same wire format — newline-framed
`{"address": …, "payload": …, "type": "send"|"publish"}` — so no bridging code exists anywhere in
this experiment. The Java sink discovers the classification channels through the ordinary
address-announcement handshake: the pyFRAMED reactors announce `Kink` and `Kink-Probability` under
the `CDSS` group, and `CountingDispatcher`/`JsonlDispatcher`, configured with `"devices": ["CDSS"]`,
bind whatever is announced there.

---

## Prerequisites

| | Requirement |
|---|---|
| JDK | 21+ (the reactor targets Java 21) |
| Maven | system `mvn` 3.8+ — **not** `./mvnw`, whose `.mvn/wrapper/` metadata is gitignored and absent from a fresh checkout |
| pyFRAMED | a [safety-box-python](../../../safety-box-python) checkout with `poetry install` run in it |
| Recording | a `.jsonl` session recorded from the Oxylog/PC60FW deployment |
| Model | the kink model artefact, `config/models/kink_logreg_heldout_v1.json` inside the pyFRAMED checkout |

The runner discovers the poetry environment itself; override with `PYTHON=/path/to/python` if the
interpreter that has `pyframed` installed lives elsewhere. Ports 4999 and 5999 must be free — the
runner refuses to start otherwise rather than silently measuring someone else's deployment.

The default recording is the p10 VC1 kink session, 15 029 events over 608 s across an
Oxylog-3000-Plus-00 (waveforms at ~3.1 Hz, measurements and settings at ~0.55 Hz) and a PC60FW
pulse oximeter at 1 Hz. Only the ventilator channels feed the model — the artefact was trained with
`include_pulse_ox: false` — but the whole recording is replayed, as the single-process deployment
does.

---

## Reproduce

```bash
# real time (~10 min), with the single-process control
bash benchmark/kink-tcp/run-kink-tcp-bench.sh

# accelerated: same path, 10x the input rate (~1 min)
SPEED=10 SKIP_BUILD=1 bash benchmark/kink-tcp/run-kink-tcp-bench.sh
```

Everything a run produces lands in `benchmark/results/kink-tcp-<timestamp>/`:

| file | what it is |
|---|---|
| `report.md` | the run's report — counts, agreement with the control, sink figures, producer pacing |
| `summary.json` | the same figures as one machine-readable row |
| `capture/*_classifications.jsonl` | every classification the Java sink received, as written by `JsonlDispatcher` |
| `baseline.jsonl` | the single-process control's classification series |
| `java.log`, `python.log` | the two instances' full output |
| `services_*.json`, `communication_*.json` | the exact configs the run used, rendered from the templates |
| `run.json` | the run's parameters |
| `baseline.jsonl.meta.json` | the control's speed, dispatch mode, count and coverage |

The runner swaps `config/services.json` and `config/communication.json` for the rendered ones —
`com.framed.orchestrator.Main` reads those two paths and nothing else — and restores them on exit,
including on failure and on Ctrl-C.

### Environment

| variable | default | meaning |
|---|---|---|
| `RECORD` | the p10 VC1 kink session | recording to replay |
| `SPEED` | `1.0` | replay speed multiplier; `0` replays flat out |
| `PYFRAMED_HOME` | `../safety-box-python` | the pyFRAMED checkout |
| `PYTHON` | the poetry environment | interpreter that has `pyframed` |
| `MODEL` | `config/models/kink_logreg_heldout_v1.json` | model artefact, relative to `PYFRAMED_HOME` |
| `JAVA_PORT` / `PY_PORT` | `4999` / `5999` | the two bus ports |
| `START_DELAY` / `GRACE` | `15` / `20` | replay pre-roll and trailing grace, in seconds |
| `JAVA_TIMEOUT` | pre-roll + span/speed + grace + 180 s | cap on the Java run, so a replay that cannot read its recording fails instead of hanging |
| `RUN_BASELINE` | `1` | set `0` to skip the control |
| `BASELINE_DISPATCH` | `PER_HANDLER` | the control's bus dispatch mode, passed through to `baseline.py --dispatch`; `SEQUENTIAL` removes the cross-channel intake race |
| `SKIP_BUILD` | unset | set `1` to reuse the installed SNAPSHOTs |

### The run matrix

- **`SPEED=1.0` — the faithful run.** The only run whose latency is a real-time latency, and the
  one that answers the clinical question. Its classification output is compared against a control
  replayed at the same speed.
- **`SPEED=10`, `SPEED=60` — throughput.** The recording's timeline is compressed by the speed
  factor, so the reactors' 6 s window covers 6·speed seconds of recorded session and the verdicts
  legitimately differ from the control. What these runs measure is whether the path keeps up: `drops`,
  `handlerErrors` and the latency percentiles at 10x and 60x the clinical input rate.
- **`SPEED=0` — flat out.** Publishes as fast as the bus accepts. Useful as a saturation probe of
  the transport and the sink; the CDSS output is meaningless at this speed, because 608 s of
  recording collapses into a window shorter than the model's own.

---

## Reading the report

### Agreement with the control

`baseline.py` runs the *same three reactors*, deployed by the *same* `Manager` from the *same*
rendered services config, inside one pyFRAMED process with pyFRAMED's own `JsonlReplayProtocol` on
a local bus — no Java instance, no socket — and at the *same replay speed*. Its output is the
reference series. Because it is paced like the run, a full reproduction costs roughly twice the
recording's duration; `RUN_BASELINE=0` skips it when only the sink figures are wanted.

The two series carry different absolute time bases: the control stamps events with the recording's
own timestamps, the distributed run with those timestamps shifted to replay start. Both are
therefore normalised to *seconds since their own first classification* before being aligned, which
is exact rather than approximate — the shift is one constant for the whole run.

`report.md` then reports missing events, unexpected events, `Kink` label mismatches and the largest
absolute difference in `Kink-Probability`. The verdict is `identical` only when all four are
clean.

**A `DIVERGED` verdict does not by itself indict the transport.** The deployed configuration is not
reproducible: `PER_HANDLER` gives each channel its own queue and thread, so the three waveforms of
one Medibus frame race to the reactor's evaluation lock, and the frame clock — a maximum over them —
advances in an order that differs between runs. A frame whose firing arrives after the clock has
already moved is suppressed by the logical-time gate, so *which* of the ~1 895 ventilator frames
emit varies. Measured over `p10-VC1-knick-0` at `speed = 1.0`:

| pair | dispatch | deployment | aligned | `Kink` disagreements | Δp p50 | Δp p95 | max Δp |
|---|---|---|---|---|---|---|---|
| replicate 1 vs 2 | **`SEQUENTIAL`** | single process | **100 %** | **0 / 1 585** | **0** | **0** | **0** |
| replicate 1 vs 2 | `PER_HANDLER` | single process | 84.1 % | 0 / 1 296 | 1.4e-08 | 0.0096 | 0.131 |
| control vs control | `PER_HANDLER` | single process | 83.6 % | 1 / 1 296 | 1.6e-07 | 0.0089 | 0.107 |
| run vs run | `PER_HANDLER` | distributed | 82.7 % | 0 / 1 275 | 1.9e-06 | 0.0109 | 0.078 |
| run vs control | `PER_HANDLER` | distributed vs. single | 82.4 %, 82.3 % | 1 / 1 261, 0 / 1 276 | 1.6e-06 | 0.0098, 0.0101 | 0.446, 0.442 |

Every row is a row of [`kink-tcp-agreement.csv`](../results/kink-tcp-agreement.csv), quoted rather
than transcribed — see [Regenerating the agreement table](#regenerating-the-agreement-table).
The disagreement denominator is `alignedKink`, the `Kink`-channel half of the aligned instants: a
`Kink` verdict can only disagree with another `Kink` verdict, so the full `aligned` count (which
also carries every `Kink-Probability`) would halve the apparent rate.

**On the two figures that disagree.** By *alignment* — the fraction of decision instants the two
series share — a distributed run against its control (82.4 %, 82.3 %) sits inside the spread of the
single-process pairs (84.1 %, 83.6 %, and 82.7 % for two distributed runs against each other). By
*max Δp* it does not: 0.446 and 0.442 against 0.078–0.131, consistently larger and in both
distributed-vs-single pairs.

That gap is a property of the statistic, not of the transport. `maxProbabilityDelta` is a
single-sample maximum, and the two 0.44 figures are one instant each — at 9.0 s and 9.3 s, the
session's first kink onset, where the probability traverses the whole [0, 1] range within a few
frames, so a one-frame difference in *which* firing emitted lands as a large pointwise difference.
Everywhere else the same two pairs agree as closely as any single-process pair: median Δp ~1.6e-06,
p95 ~0.010, p99 ~0.023 — *tighter* at p99 than control-vs-control's 0.032. Exactly one instant exceeds 0.1 in each
of the two distributed-vs-single pairs (the `probabilityDeltaOver01` column) — as it does in the
`PER_HANDLER` replicate pair and the control pair, and against zero for two distributed runs
compared with each other.

So read the distribution, and treat `max Δp` the way this study already treats `latencyMax`: a
single-sample extreme, worth recording and not worth quoting as the disagreement between two
deployments. What is *not* settled by these figures is why both distributed maxima land on the first
onset while the single-process ones land mid-session; the pre-roll differs between a run and its
control, which is a plausible cause and an unmeasured one.

Re-measured on 2026-09-07 against the `kink_logreg_heldout_v1` artefact, every alignment figure
landed within 1.5 points of the 2026-09-02 session's corresponding pair, and the ordering above —
`SEQUENTIAL` exact, `PER_HANDLER` single-process replicates no better than a distributed run against
its control — held unchanged.

**The dispatch attribution is an inference, not a measurement.** `SEQUENTIAL` is the one pair that
is bit-identical, and it is single-process only. The measurement that would actually separate
"dispatch nondeterminism" from "transport-induced reordering" is a `SEQUENTIAL` *distributed* run
against a `SEQUENTIAL` control — 100 % there would settle it, and it is also the pair that would
resolve the first-onset Δp above. That arm has not been run: `run-kink-tcp-bench.sh` passes
`BASELINE_DISPATCH` to the control only, and the Java side's dispatch mode is set in
`communication_java.json.template`. Until it exists, "the variation is a property of the dispatch
mode" is what the `SEQUENTIAL`/`PER_HANDLER` contrast makes *likely*, not what it shows.

So read a run-vs-control figure against a control-vs-control figure, never on its own.
`compare-series.py` produces both on the same scale:

```bash
# the control-vs-control reference for a pair of runs
python3 benchmark/kink-tcp/compare-series.py runA/baseline.jsonl runB/baseline.jsonl

# and the dispatch-mode contrast that attributes the variation
poetry run python benchmark/kink-tcp/baseline.py --dispatch SEQUENTIAL \
    --services runA/services_python.json --record "$RECORD" --speed 1.0 --out seq-1.jsonl
python3 benchmark/kink-tcp/compare-series.py seq-1.jsonl seq-2.jsonl
```

### Regenerating the agreement table

`kink-tcp-agreement.csv` is derived, one `--csv` append per pair, so the table above and the tracked
data cannot drift apart:

```bash
AG=benchmark/results/kink-tcp-agreement.csv && : > $AG
K=benchmark/results/kink-dispatch
A=benchmark/results/kink-tcp-20260907-104258 && B=benchmark/results/kink-tcp-20260907-110414
cmp() { python3 benchmark/kink-tcp/compare-series.py "$@"; }

cmp $K/control-SEQUENTIAL-{1,2}.jsonl  --pair "SEQUENTIAL replicate 1 vs 2" \
    --dispatch SEQUENTIAL  --deployment single-process        --csv $AG
cmp $K/control-PER_HANDLER-{1,2}.jsonl --pair "PER_HANDLER replicate 1 vs 2" \
    --dispatch PER_HANDLER --deployment single-process        --csv $AG
cmp $A/baseline.jsonl $B/baseline.jsonl --pair "control A vs control B" \
    --dispatch PER_HANDLER --deployment single-process        --csv $AG
cmp $A/capture/*classifications.jsonl $B/capture/*classifications.jsonl --pair "run A vs run B" \
    --dispatch PER_HANDLER --deployment distributed           --csv $AG
cmp $A/capture/*classifications.jsonl $A/baseline.jsonl --pair "run A vs control A" \
    --dispatch PER_HANDLER --deployment distributed-vs-single --csv $AG
cmp $B/capture/*classifications.jsonl $B/baseline.jsonl --pair "run B vs control B" \
    --dispatch PER_HANDLER --deployment distributed-vs-single --csv $AG
```

The run always goes in the `A` position of a `distributed-vs-single` pair, so `onlyInA` is the run's
own surplus and `countA` its own count. Every derived figure — `aligned`, `alignedFraction`, the
label mismatches, the Δp statistics — is symmetric and does not depend on the order.

### Sink measurement

Every reactor in a pyFRAMED network announces its output under the single `CDSS` group, so a sink
bound to that group receives the *whole chain* — the 20-dimensional feature vectors and the
8-dimensional component vectors as well as the verdicts, all of it crossing the wire twice. The
counting sink is therefore configured with `"channels": ["Kink", "Kink-Probability"]`, which scopes
the figures to the classification output without narrowing what is bound: the intermediate messages
still cross the bus, are still parsed and still occupy the push queue, they are simply not counted.
The capture file is left unfiltered, so `capture/*_classifications.jsonl` shows the complete return
stream, intermediates included, and the analysis filters it down to the two classification channels.

The `CountingDispatcher` line reports, for the classification stream only:

- **`received` / `dropped` / `handlerErrors`** — arrivals, datapoints the bounded push queue could
  not accept, and messages that failed to parse before reaching the queue.
- **`achieved dp/s`** — arrivals per second between the first and the last.
- **`latency(mean/p50/p95/p99/max)`** — `now − timestamp` at push time.

That latency is a genuine **frame&rarr;verdict** latency, not a transport hop: a reactor stamps its
output with the *logical* timestamp of the input that triggered it, so a classification carries the
timestamp of the ventilator frame it is about. `now − timestamp` at the Java sink is therefore the
age of that frame when its verdict arrived — replay publish, TCP hop out, three reactors, TCP hop
back and sink queue, all included. Producer and sink share a clock because they are the same JVM on
one host; across hosts the same figure would also carry the clock offset and must not be read as a
latency.

### Producer pacing

The replay logs `events=… wall=… achieved=… Hz target=… Hz lag(mean/max)=…` before it exits. `lag`
is `actual − scheduled` emit time: it says whether the producer itself held the input cadence, and
separates "the pipeline was slow" from "the input was never offered on time". Because the replay
stamps each event with its *scheduled* emit instant, producer lag is included in the sink latency
rather than hidden by it — the two lines are read together.

---

## Why the timing is faithful

The load-bearing detail is what timestamp a replayed event carries. `ReplayProtocol` stamps each
event with

```
emit(t) = replayStart + (t − firstRecordedTimestamp) / speed
```

At `speed = 1.0` that is the recorded timestamp shifted by one constant, which preserves two things
the CDSS depends on:

- **every inter-sample interval**, so the 6 s feature window, the `staleTimeoutS` guard and the
  inter-arrival features see the intervals they were trained on; and
- **the identity of timestamps shared by one device frame** — Paw, Flow and CO₂ arrive from one
  Medibus frame under one timestamp, and the reactors' logical-time gate admits exactly one firing
  per frame however many of the three arrive.

Stamping with `Instant.now()` at publication instead — which this protocol did until this
experiment — splits a shared frame timestamp into three distinct ones and shifts every window edge
by the scheduling jitter. That changes *which* verdicts the network produces, so the distributed run
could not have been compared against anything.

Shifting to replay start rather than keeping the recording's dates is what makes the sink latency
measurable at all: a stamp from the original session would make `now − timestamp` the age of the
recording (months), not a latency.

**The control must be replayed at the run's own speed.** The recorded timestamps drive every window,
gate and output stamp, so the *values* are not a function of wall-clock speed — but the *series* is.
A replay running faster than the chain processes backlogs the reactors' queues, and
`FeatureScalingReactor` publishes every feature vector pending at a firing stamped with that one
firing's logical time: under backlog several vectors collapse onto a single timestamp, so the series
is neither complete nor aligned with the one a paced replay produces. `baseline.py` therefore
replays at `--speed` (the runner passes the run's) and checks its own coverage — how far into the
recording its last classification reaches — reporting the control as **truncated** rather than
letting a short control masquerade as a reference. The analysis refuses to certify agreement against
a truncated control.

---

## The measured runs

`benchmark/results/` keeps only `*.csv` under version control — the classification series and logs
are bulky and regenerable — so these two files are the study's tracked primary data:

| file | what it holds |
|---|---|
| `kink-tcp-sweep.csv` | one row per run: sink latency/completeness, producer pacing, and the run's agreement figures — including the collapse counts and the Δp distribution, not only its maximum |
| `kink-tcp-agreement.csv` | the six agreement *pairs*, including the control-vs-control reference and the `SEQUENTIAL`/`PER_HANDLER` contrast; derived by the commands under [Regenerating the agreement table](#regenerating-the-agreement-table) |

Both are appended to by their tools and both refuse a file whose header does not match the columns
they write, so a schema change surfaces as a refusal to append rather than as a row whose values sit
one column off the names they will be read under.

The matrix measured on 2026-09-07, all on `p10-VC1-knick-0`, all with the **`k=10 /
variance-ranked / threshold 0.4` held-out artefact** (`kink_logreg_heldout_v1.json`, fitted on the
development participants only). The `model` column of `kink-tcp-sweep.csv` records this per row:

| run | speed | control |
|---|---|---|
| `kink-tcp-20260907-104258` | 1.0 | yes (`PER_HANDLER`) |
| `kink-tcp-20260907-110414` | 1.0 | yes (`PER_HANDLER`) |
| `kink-tcp-20260907-112543` | 1.0 | no — latency replicate |
| `kink-tcp-20260907-113628` | 10 | yes, but not comparable at this speed |
| `kink-tcp-20260907-113914` | 60 | yes, but not comparable at this speed |
| `kink-tcp-20260907-114023` | 0 | no — produces no verdict by construction |
| `kink-dispatch/control-{SEQUENTIAL,PER_HANDLER}-{1,2}.jsonl` | 1.0 | the dispatch contrast, controls only |

Delivery was complete at every point of this matrix: **0 dropped and 0 `handlerErrors`** across
13 307 classifications, from the clinical 1× up to 60×, with the producer holding its target cadence
throughout (lag mean 0.0 ms, max ≤ 2 ms). The flat-out point received nothing, by construction.

The 2026-09-02 session measured the same matrix against `kink_logreg_v1.json` (the all-participant
refit) and is superseded by this one; its sweep and agreement CSVs are kept untracked beside the
current ones as `*.csv.pre-heldout`. **Its sink latencies are systematically 2–3× higher** — p95
4 ms against 2 ms at speed 1.0 — and that difference is *not* attributable to the artefact, whose
per-firing arithmetic is identical: same 20 features, same `k=10` PCA, same logistic form, only
different coefficient values. The pyFRAMED checkout also carried uncommitted changes between the two
sessions. Treat the gap as unexplained host/Python-side variation, which is what the
quiet-host caveat below exists for, and do not read it as an effect of either model.

Measurement host: AMD Ryzen 7 PRO 7840U (8C/16T), 30 GB, Linux 7.0.0-31, OpenJDK 25.0.1,
Maven 3.9.9, Python 3.12.7; pyFRAMED `f7915cc` **plus uncommitted changes** (including
`io/dispatch/logging_dispatcher.py` and the reactor package's `__init__.py`), FRAMED `3ab35eb` plus
the uncommitted `ReplayProtocol` and `CountingDispatcher` changes this experiment needs. Because the
Python side is not at a clean commit, this session is not a controlled single-variable comparison
against 2026-09-02. Two earlier runs
(`kink-tcp-20260902-215236`, a chain that failed to wire, and `-215735`, a configuration smoke test)
are excluded from the matrix.

---

## Caveats

- **The pending-vector collapse this experiment was designed to guard against does not occur.**
  `FeatureScalingReactor` publishes every vector pending at a firing stamped with that one firing's
  logical time, so a suppressed firing can put two vectors on one timestamp — which a comparison
  keyed by timestamp would silently drop. Both tools count those collisions, off one shared
  implementation, and across every run and control measured the count is **0**
  (`collapsedA`/`collapsedB` in the agreement CSV, `collapsedControl`/`collapsedObserved` in the
  sweep). A run whose count is non-zero cannot be reported as `identical`: the analysis marks it
  *inconclusive*, because each collision hides one event from every figure in the comparison. The
  reproducibility shortfall above is a *frame skipped or not*, not a collapse.
- **`max Δp` is a single-sample statistic, like `latencyMax`.** It is recorded because a large value
  is worth knowing about, and it is not the figure to quote for how far two series disagree — see
  [Agreement with the control](#agreement-with-the-control), where both distributed maxima turn out
  to be one instant each at the session's first kink onset. Read `probabilityDeltaP95`/`P99` and the
  `probabilityDeltaOver01` count beside it.
- **The classification volume tracks the frame rate.** The model artefact emits on every firing
  (`feature_spec.emit_every: 1`), so a 608 s session yields ~1 500 classifications on each of the
  two channels — ~2.5 per second per channel, behind the ~3.1 Hz waveform pacemaker by the feature
  window's warm-up and the recording's gaps. That is still a thin stream: the experiment measures
  the *latency and completeness* of a clinical decision path, not the throughput ceiling of the
  transport — for that see the socket-pair study in [`../README.md`](../README.md).
- **The capture sink perturbs the counting sink slightly.** `JsonlDispatcher` writes the series that
  makes the agreement check possible, and it shares the JVM with `CountingDispatcher`. Each
  dispatcher has its own queue and worker thread, so the interference is a few file writes per
  second on another thread; set `"Dispatchers"` to the counting sink alone in the rendered config
  if a run must be free of it.
- **A stale marker at the end of the run.** Once the replay stops, the feature reactor's stale timer
  fires ~3 s later and publishes a `null`-valued marker down the chain. The control, which also
  outlives its replay, produces the same marker, so it appears on both sides and does not disturb
  the comparison — but it is why a run's classification count can exceed the number of verdicts,
  **and it can dominate the sink's `latencyMax`**: the marker carries the logical timestamp of the
  last real firing and is published ~3 s later, so when it arrives inside the measurement window it
  lands as a multi-second outlier in a distribution whose p99 is a few milliseconds. Whether it does
  depends on where the sink's summary falls relative to the timer: across the six runs of the
  2026-09-07 matrix it never landed and `latencyMax` stayed at 4–11 ms, while the 2026-09-02 session
  caught it once, at speed 60, as a `max` of 3 413 ms against a p99 of 3 ms. Either way, read
  `p50`/`p95`/`p99` and not `max` — a single-sample maximum that may or may not include a known
  artefact is not a figure to quote.
- **The measurement host must be quiet.** The sink latency is wall-clock; anything else competing
  for the CPU inflates the percentiles. Check what else is running before quoting a number, and
  record it beside the figures.
- **Startup announcements are refused before the Java instance is up.** `python.log` shows a few
  `Connection refused` warnings for `CDSS.addresses` at startup. They are harmless: the discovery
  handshake is idempotent and pyFRAMED re-announces on every emission, so the Java sink binds on the
  first classification at the latest, long before any verdict exists.
- **Both instances run on one host.** The latency figure is a same-clock measurement; a two-host
  variant needs a synchronised clock (or a one-way-delay estimate) before the same number means
  anything.

---

## Doing it by hand

The script exists to make the run repeatable, not to hide it. Manually:

```bash
# 1. render the four configs (or copy them out of an earlier run directory)
RUN=benchmark/results/manual && mkdir -p $RUN/capture
sed -e 's#@RECORD@#/path/to/session.jsonl#' -e 's#@SPEED@#1.0#' \
    -e "s#@CAPTURE_DIR@#$PWD/$RUN/capture/#" -e 's#@START_DELAY@#15#' -e 's#@GRACE@#20#' \
    benchmark/kink-tcp/services_java.json.template > config/services.json
sed -e 's#@JAVA_PORT@#4999#' -e 's#@PY_PORT@#5999#' \
    benchmark/kink-tcp/communication_java.json.template > config/communication.json
sed -e 's#@MODEL@#config/models/kink_logreg_heldout_v1.json#' \
    benchmark/kink-tcp/services_python.json.template > $RUN/services_python.json
sed -e 's#@JAVA_PORT@#4999#' -e 's#@PY_PORT@#5999#' \
    benchmark/kink-tcp/communication_python.json.template > $RUN/communication_python.json

# 2. the CDSS first, so its reactors are listening before the replay starts
cd ../safety-box-python && poetry run python -m pyframed \
    --services  …/$RUN/services_python.json \
    --communication …/$RUN/communication_python.json

# 3. the replay and the sinks; the replay ends the JVM, and the shutdown hook prints the summary
mvn clean install -DskipTests && mvn -pl framed-app exec:java

# 4. the control, and the report
poetry run python benchmark/kink-tcp/baseline.py \
    --services $RUN/services_python.json --record /path/to/session.jsonl --out $RUN/baseline.jsonl
python3 benchmark/kink-tcp/analyse-kink-tcp.py --run-dir $RUN

# 5. the control-vs-control reference, without which step 4's verdict cannot be read
python3 benchmark/kink-tcp/compare-series.py $RUN/baseline.jsonl <another run's>/baseline.jsonl
```

---

## What this experiment required

Both repositories needed a change before the run was possible or trustworthy; both are covered by
tests and changelog entries.

**FRAMED — `ReplayProtocol` (framed-communicator).** It re-stamped every replayed event with
`Instant.now()`, had no speed control, and re-announced every address on every event. It now
replays on the schedule above with the recorded timing preserved, takes `channels`, `speed`,
`startDelaySeconds`, `graceSeconds` and `exitOnCompletion`, announces each address once, and
reports its own pacing. **Config-schema change:** all nine keys are required, because
`Factory` matches constructor parameters against config keys and leaves nothing to default.
`config/services_replay.json` and `config/services_interop.json` were updated accordingly — the
latter is the profile that shares the recording with the HL7/MQTT bridges, and it takes
`exitOnCompletion: false` so the bridges outlive the replay.

**FRAMED — the experiment's own pyFRAMED config template.** It pinned `components: 8`,
`componentSelection: "label"` and `threshold: 0.3` against an artefact retrained to the selected
`k=10 / variance-ranked / threshold 0.4` configuration. Those two reactor keys are *assertions*
against the artefact, so `Kink-Reducer` refused to instantiate — and that did not stop the run:
pyFRAMED's `Manager` logs the failure and wires what is left, the ARN still reports a network (two
reactors, no edges), and a full-length replay produced feature vectors and not one verdict. The
template now mirrors `config/services_kink_replay.json` and additionally asserts `windowS`,
`emitEvery` and `includePulseOx`, and the runner refuses to start when `python.log` carries a
`Failed to instantiate Service` line. Measurements taken before this was found are not comparable.

**pyFRAMED — `NioTcpTransport`.** It opened a fresh TCP connection per message. Java's transport was
already fixed to keep one pooled connection per peer, for a measured reason: with a connection per
message the receiving instance accepts on a single selector thread, cannot drain the listen backlog,
and above roughly 7 400 messages/s the kernel drops SYNs — 10–30 % of messages are lost while the
send still reports success. The Python side now mirrors the Java design: one persistent connection
per peer, writes serialized on it and retried once on a fresh connection, blocking writes so a
sender outrunning its peer is throttled rather than dropped, plus the same 8 MB unframed-input cap.
The UDP receive loop now drains every queued datagram per wake-up, as the Java one does.
