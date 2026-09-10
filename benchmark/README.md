# Reproducing the FRAMED throughput measurements

Everything needed to re-run the two throughput studies and rebuild their tables and figures from
scratch. Two studies of the same data path:

- **Study A** — a producer and a sink sharing one event bus inside a single FRAMED instance
  (`MimicThroughputBenchmark`).
- **Study B** — the same services split across two instances connected by `SocketEventBus` over TCP
  and UDP, with an in-process `LOCAL` control measured in the same session
  (`SocketPairThroughputBenchmark`).

The written-up result is `REPORT_COMBINED.md` (untracked; see [What is and is not in
git](#what-is-and-is-not-in-git)).

---

## Prerequisites

| | Used for the published figures | Requirement |
|---|---|---|
| JDK | OpenJDK 25.0.1 | 21+ (the reactor targets Java 21) |
| Maven | Apache Maven 3.9.9 | 3.8+ system `mvn` — **not** `./mvnw`, whose `.mvn/wrapper/` metadata is gitignored and absent from a fresh checkout |
| Python | 3.12.7 | 3.11+ |
| Python packages | `plotly` 7.0.0, `kaleido` 1.4.0 | `pip install -r benchmark/requirements.txt` (figures only) |
| Headless browser | Chromium 150.0.7871.128 | any Chrome/Chromium — Kaleido 1.x renders through one |
| Dataset | MIMIC-III Waveform Database record 3000125 | see below |

```bash
mvn clean install -DskipTests        # from the repo root, once
pip install -r benchmark/requirements.txt
```

Both benchmark harnesses are opt-in twice over: their `*Benchmark` class names fall outside
Surefire's default includes, so `mvn test` never runs them, and each **skips rather than fails**
when `-Dmimic.record` is absent or does not resolve.

### The dataset

Verified on <https://physionet.org/content/mimic3wdb/1.0/> (2026-09-02):

> **MIMIC-III Waveform Database**, version 1.0 (published 7 April 2020).
> Open access — "anyone can access the files, as long as they conform to the terms of the specified
> license". Licence: Open Data Commons Open Database License v1.0. DOI
> [10.13026/c2607m](https://doi.org/10.13026/c2607m).
>
> Moody, B., Moody, G., Villarroel, M., Clifford, G. D., & Silva, I. (2020).
> MIMIC-III Waveform Database (version 1.0). PhysioNet. RRID:SCR_007345.

The whole database is 6.7 TB. Both studies need **one record**, ~256 MB:

```bash
# Study A and Study B: record 3000125
wget -r -N -c -np https://physionet.org/files/mimic3wdb/1.0/30/3000125/

# Only for the full-app confirmation scripts: record 3000003
wget -r -N -c -np https://physionet.org/files/mimic3wdb/1.0/30/3000003/
```

`wget -r` mirrors into `./physionet.org/files/mimic3wdb/1.0/30/3000125/…`, which is the layout every
`recordPath` in `config/` assumes. Point `-Dmimic.record` at the **master header**, not a segment:

```
…/physionet.org/files/mimic3wdb/1.0/30/3000125/3000125.hea
```

Check you have the right record — its first line names the segment count, signal count and sampling
frequency the report quotes:

```console
$ head -1 …/3000125/3000125.hea
3000125/41 4 125 85770000 17:13:30.000
```

41 segments, 4 signals, 125 Hz. The layout header `3000125_layout.hea` lists the signals as
II / III / PLETH / V. Four of the 41 segments are gaps with no `.dat`, which is normal for a
multi-segment MIMIC record and is why the directory holds 39 `.hea` against 37 `.dat`.

---

## Reproducing Study A — one instance

```bash
mvn -pl framed-benchmark test -Dtest=MimicThroughputBenchmark \
    -Dmimic.record=/abs/path/to/3000125.hea \
    -Dmimic.tp.csv=mimic-throughput-repeat.csv \
    -DargLine="-Xmx8g"
cp framed-benchmark/target/benchmark/mimic-throughput-repeat.csv benchmark/results/
```

Everything else is left at its default, which is what produced the published sweep: six experiments
(`e1`…`e6`), **41 operating points, 113 runs** — three repeats per point, except E5's five real-time
points, which run once because they are wall-clock bound. Wall clock ≈ **14 minutes** on the host
below.

The heap setting matters. The flat-out points deliberately let a backlog form, that backlog is
retained JSON, and the report's saturated points peak near 3 GB. Under a smaller heap the run dies
rather than measuring.

| Experiment | Varies | Points |
|---|---|---|
| E1 | offered load: 1× … 5000× replay speed, plus one unpaced point | 16 |
| E2 | concurrent devices, unpaced: 1, 2, 4, 8, 16 | 5 |
| E3 | sinks bound to the same channels: 1, 2, 4, 8 | 4 |
| E4 | dispatch mode at 200× and unpaced | 5 |
| E5 | concurrent devices at true 125 Hz real time: 1, 4, 16, 64, 256 | 5 |
| E6 | `LocalEventBus` against `SocketEventBus` at 1×, 200×, unpaced | 6 |

Useful knobs (full list in the class javadoc): `-Dmimic.tp.experiments=e1,e5` for a subset,
`-Dmimic.tp.repeats`, `-Dmimic.tp.speeds` to replace E1's sweep (which also drops its unpaced point),
`-Dmimic.tp.maxBeds`, `-Dmimic.tp.csv` so re-measuring one experiment does not truncate an earlier
sweep.

## Reproducing Study B — two instances over the socket bus

```bash
mvn -pl framed-benchmark test -Dtest=SocketPairThroughputBenchmark \
    -Dmimic.record=/abs/path/to/3000125.hea \
    -Dmimic.sp.csv=socket-pair-postfix.csv \
    -DargLine="-Xmx8g"
cp framed-benchmark/target/benchmark/socket-pair-postfix.csv benchmark/results/
```

Defaults again: three wirings (`LOCAL`, `TCP`, `UDP`) × seven speeds plus an unpaced and an
unprimed-start point each — **27 operating points, 81 runs**, three repeats. Wall clock ≈ **10
minutes** (573 s measured on 2026-09-07; an earlier session recorded ≈ 18 minutes, so treat this as
host- and load-dependent rather than a fixed cost).

Both instances bind loopback ports; nothing leaves the machine. Each measured point is preceded by a
priming phase that drives the channel-discovery handshake to completion, so it measures steady-state
transport behaviour rather than the binding race — except E3, which deliberately does not prime and
measures exactly that race.

Knobs: `-Dmimic.sp.experiments`, `-Dmimic.sp.repeats`, `-Dmimic.sp.speeds`,
`-Dmimic.sp.wallSeconds`, `-Dmimic.sp.dpBudget`, `-Dmimic.sp.quietSeconds` (how long the sink must be
idle before a run counts as drained — raising it separates genuine transport loss from in-flight
messages discarded at shutdown).

## Full-app confirmation

Both harnesses wire services directly. These scripts re-measure a few points the way a deployment
actually runs them — `com.framed.orchestrator.Main`, config-driven instantiation via `Factory`,
`SocketEventBus` — so the in-process ceiling is confirmed on the production path rather than assumed
to carry over.

```bash
bash benchmark/run-full-app-bench.sh            # pacing headroom  -> results/full-app-<stamp>.log
bash benchmark/run-full-app-throughput.sh       # throughput       -> results/full-app-throughput-<stamp>.{log,csv}
bash benchmark/run-full-app-throughput.sh 1 200 100000   # explicit speeds
```

Environment: `MIMIC_RECORD=/path/to/record.hea`, `MAX_SECONDS=<record seconds>`, `SKIP_BUILD=1` to
reuse the installed SNAPSHOTs. Both scripts **overwrite `config/services.json`** with a generated
bench config derived from `config/services_mimic_bench.json`, and restore it from
`config/services.json.bench-backup` on exit, including on failure and Ctrl-C. Commit or stash local
edits to `config/services.json` first. `config/services_mimic_bench.json` carries an absolute
`recordPath` from the original author's machine — override it with `MIMIC_RECORD` or edit it.

## Tables and figures

```bash
python3 benchmark/analyse-throughput.py  benchmark/results/mimic-throughput-repeat.csv
python3 benchmark/analyse-socket-pair.py benchmark/results/socket-pair-postfix.csv
python3 benchmark/make-figures.py
```

The two `analyse-*` scripts print the case-study tables (`--out FILE` writes markdown instead) and
are pure standard library. They collapse repeats of a point to the median with the min–max spread
alongside, so run-to-run variation stays visible. `analyse-throughput.py` accepts several CSVs and
merges them, later files superseding earlier ones per operating point.

`make-figures.py` writes all twelve report figures as SVG + PDF + PNG into `benchmark/figures/`:

| | Study A (`--single`) | Study B (`--pair`) | Study C (`--kink`, `--kink-run`) |
|---|---|---|---|
| default input | `results/mimic-throughput-repeat.csv` | `results/socket-pair-postfix.csv` | `results/kink-tcp-sweep.csv`; `--kink-run` names a run directory for the verdict series |
| figures | `figA1-saturation`, `figA2-latency`, `figA3-backlog`, `figA4-bed-capacity`, `figA5-device-scaling`, `figA6-fanout` | `figB1-threads`, `figB2-throughput`, `figB3-latency` | `figC1-kink-series`, `figC2-kink-latency`, `figC3-kink-saturation` |

It is the **only** figure path — Plotly throughout, no matplotlib anywhere in this directory.

**House style.** One look across all three studies, so figures can sit side by side in the article:
stock Plotly colours only (`qualitative.Plotly` for identity, `sequential.Blues` for the ordered
latency percentiles), white paper and white plot area, and **no prose inside the axes** — a figure
carries data, axes and a legend, and every reading it supports belongs in the caption. Reference
lines (`ideal`, `linear scaling`, `16 hardware threads`, `saturation knee`, `decision threshold`)
are named in the legend rather than annotated in place. Axis wording is fixed once in the `X_*`/`Y_*`
constants at the top of the script; reuse a constant instead of writing a new title for a quantity
that already has one.

**Kaleido needs a browser.** Plotly 6+ exports static images through Kaleido 1.x, which drives a
headless Chrome. The script looks for one in this order: `--chrome PATH`, then
`$FRAMED_FIGURES_CHROME`, then the binary inside a snap-packaged Chromium
(`/snap/chromium/current/usr/lib/chromium-browser/chrome`), then Plotly's own discovery. The snap
*launcher* at `/snap/bin/chromium` does not work: it does not forward the CDP pipe file descriptors,
and the browser exits immediately with `BrowserFailedError`. Naming the binary inside the snap
avoids it. On a machine with no browser at all, `plotly_get_chrome` downloads one.

---

## What was measured with what

Every CSV in `results/` and the invocation behind it. The report is built from the two marked
**bold**; the rest are the corroborating sessions its reproducibility claims rest on.

| CSV | Harness + knobs | Used for |
|---|---|---|
| **`mimic-throughput-repeat.csv`** | `MimicThroughputBenchmark`, all defaults | Study A: every table and figure A1–A6 |
| **`socket-pair-postfix.csv`** | `SocketPairThroughputBenchmark`, all defaults, post-fix transports | Study B: every table and figure B1–B3 |
| `mimic-throughput.csv` | as above, first session | reproducibility check (session 1 of 3) |
| `mimic-throughput-e1e5.csv` | `-Dmimic.tp.experiments=e1,e5` | reproducibility check (session 2 of 3) |
| `mimic-throughput-drift.csv` | `-Dmimic.tp.speeds=100000 -Dmimic.tp.repeats=10` | does the unpaced ceiling drift as the machine warms up |
| `socket-pair.csv` | `SocketPairThroughputBenchmark` **before** the TCP fix | pre-fix sweep; not used by the report or its figures |
| `socket-pair-tcpfix.csv` | `-Dmimic.sp.speeds=50,300 -Dmimic.sp.repeats=1` | spot check while developing the fix |
| `tcp-drain.csv` | `-Dmimic.sp.quietSeconds=25` at the 50× point | is the pre-fix loss real or a drain-window artefact |
| `ov.csv` | `-Dmimic.sp.speeds=100 -Dmimic.sp.repeats=1`, pre-fix | the single 119,125-datapoint run whose kernel `TcpExt.ListenOverflows` delta was sampled |
| `full-app-throughput-*.csv` | `run-full-app-throughput.sh` | full-app confirmation of the Study A ceiling |
| `*.log` | stdout of the corresponding run | segment-level detail; no command lines are recorded in them |

`socket-pair.csv` is the **pre-fix** sweep, kept as data but describing a transport that no longer
exists. Nothing defaults to it; name it explicitly if you want it.

## Measurement environment

Recorded because the absolute ceilings are this machine's, not the framework's:

```
AMD Ryzen 7 PRO 7840U (8 cores / 16 hardware threads, mobile 15–28 W class)
30 GB RAM · Linux 7.0.0-31-generic · powersave governor
OpenJDK 25.0.1 · -Xmx8g
```

Not a controlled benchmarking machine. A laptop under `powersave` throttles under sustained load, and
the report's threshold is given as a range for that reason.

## What should and should not reproduce

Established by re-running Study A in four independent sessions
(`mimic-throughput.csv`, `mimic-throughput-e1e5.csv`, and `mimic-throughput-repeat.csv` re-measured
on 2026-09-07, superseding the 2026-09-02 sweep kept beside it as
`mimic-throughput-repeat.csv.pre-20260907`):

- **Paced points below saturation reproduce to within 0.1 %**, across sessions and across machines'
  worth of variation in load. Delivery was complete at every point of every session.
- **Saturated points do not.** 562,336 dp/s offered was carried in all four sessions (zero backlog
  in eleven of twelve runs, 9,097 datapoints in the twelfth). 749,781 dp/s was carried in three
  sessions (3/3 runs each) and collapsed in one (3/3), with a backlog of 1.0–1.3 × 10⁶ datapoints.
  Outcomes were deterministic *within* a session and differed *between* sessions, which points at a
  persistent per-process or per-machine factor rather than run-to-run noise. The cause was not
  identified.
- **The unpaced ceiling drifts by several percent between sessions.** 2026-09-07 measured
  649,583 dp/s against 2026-09-02's 602,839 dp/s, **+7.8 %**, with no code change on the path.

The 2026-09-07 session sharpens the first point considerably: across E1's **twelve paced points that
build no backlog**, the two sessions' median `deliveredDpPerSec` agrees to **within 0.07 %** at every
one (e.g. 513,373 against 513,637 dp/s at 562,336 offered), and delivery was complete — ratio
1.0000 — at all 41 points of both. E1's three remaining paced points are past the knee and move by
7.6–11.7 %, which is the second bullet, not a failure of the first. The knee itself did *not* move:
both sessions carry 749,781 dp/s with zero backlog and first build one at 1,124,672 dp/s.

Which quantity is being reproduced matters at this resolution. `sinkSustainedDpPerSec` — the paced
rate the sink held — is identical to the digit at seven of the twelve, and within 0.03 % at the rest;
`deliveredDpPerSec`, which divides by the whole harness window and so carries its startup, differs in
the third significant figure. Neither is "to the digit" across the board, and a claim at that
precision has to name which one it means.

So: expect the shape of every curve to reproduce, the paced points to reproduce nearly exactly, and
the knee to be free to move. If your knee lands elsewhere, that is the expected behaviour of this
measurement on different hardware, not a failed reproduction. Treat 562,000 dp/s as the supported
single-instance figure and 749,000 dp/s as attainable but not dependable.

Study B's saturated points carry the same caveat: repeating the whole sweep in a second session moved
`LOCAL` unpaced by +6.6 % and UDP unpaced by −2.5 % with no code change on either path. Re-measured
again on 2026-09-07 (superseding `socket-pair-postfix.csv`, kept beside it as
`socket-pair-postfix.csv.pre-20260907`), **14 of the 17 paced points that stay below transport
saturation in both sessions reproduced to the digit**, and the other three moved in their last digit
only (`TCP@1x` 200.4 → 200.3, `TCP@5x` 1 701.0 → 1 700.7, `LOCAL@200x` 74 599.8 → 74 637.1 dp/s,
all ≤ 0.05 %). The saturated points moved by anything from −17.8 % to +12.6 %, with no pattern:
E1's `TCP@200x` +12.6 %, `TCP@500x` −3.9 %, `UDP@500x` +0.3 %, and E2's flat-out points `LOCAL`
−17.8 %, `UDP` +0.8 %, `TCP` −0.7 %. Which of them moves, and by how much, is not a property of the
transport — it is what "saturated" costs in reproducibility on this host.

`UDP@200x` moved further than that, and qualitatively: **it is no longer saturated.** In the earlier
session it topped out at ~54,425 dp/s against 74,562 offered; in this one it carries the full offered
74,562.5 dp/s. So UDP's knee moved from below 200× to somewhere between 200× and 500× between two
sessions of the same code — a stronger statement about what this measurement does not pin down than
the +37.0 % it looks like in isolation.

Delivery was complete at 26 of the 27 points in both sessions. The exception is `TCP@1x-unprimed`,
which deliberately skips priming and so measures the channel-discovery race: when it bites it costs
**exactly one datapoint of 1 000** (0.10 %), and whether it bites is a coin flip — two of three
repeats lost one in the earlier session, one of three in this one, so the median point is incomplete
in one session and complete in the other. The bounded *magnitude* is the intended result; the loss
itself is not reproducible run to run, and three repeats are too few to quote a rate.
