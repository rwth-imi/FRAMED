# FRAMED benchmark: running and evaluating

Two benchmark studies of the FRAMED event path, run with the same reproducibility rules as the ARN latency experiments (`experiments/arn-latency/REPRODUCE.md`):
- the code is pinned (branch `experiments/framed-benchmark`, JDK 21, and the pyFRAMED commit);
- every input is hashed into a manifest;
- each attempt is logged with the host state;
- runs resume after an interruption;
- the environment is controlled, without CPU pinning;
- every study runs on both platforms (AMD and Raspberry Pi).

| Study | Runner | Question |
|---|---|---|
| **Kink over TCP** | `run_kink.sh` | A Java replay drives a pyFRAMED kink CDSS over TCP. Does splitting the deployment change the verdicts, and what does the path cost? |
| **MIMIC throughput and pacing** | `run_mimic.sh` | Study A: one instance. Study B: two instances over the socket bus. Pacing fidelity. Full-app confirmation through `Main` and the fat jar. |

| File | Purpose |
|---|---|
| `run_kink.sh`, `kink_units.txt` | kink runner and its unit list (48 held-out recordings, repetitions, speed sweep, dispatch contrast) |
| `kink_services_java.json` | Java side of the kink deployment (replay and sinks); the reactors come from pyFRAMED's own config |
| `kink_baseline.py` | single-process control (pyFRAMED only, no Java, no socket) |
| `concat_recording.py` | k-fold concatenation of a recording for the speed-up controls |
| `kink_series.py`, `analyze_kink.py` | series alignment; agreement and sink analysis |
| `run_mimic.sh`, `mimic_services.json` | MIMIC runner and the full-app configuration |
| `analyze_mimic.py`, `tables_study_a.py`, `tables_study_b.py`, `make_figures.py` | merge, sweep comparison, tables, figures |
| `../common/runlib.sh` | runner infrastructure shared with `arn-latency/run_replays.sh` |

## 1. Requirements

**Both platforms:**
- Linux;
- JDK 21 with `javac`;
- Maven ≥ 3.8;
- `jq`, `git`, `timeout`, `sha256sum`, `python3`.

**Kink study:**
- a checkout of `safety-box-python` (pyFRAMED) at commit **`583914ca0b5dc600ee037bfc1ab3d882c8313518`**, without local changes, next to the repository (`../safety-box-python`), or set `PYFRAMED_HOME`;
- its Python environment: `poetry install` in that checkout, or set `PYTHON` to an interpreter with its dependencies.

  pyFRAMED requires **Python ≥ 3.12**. On Raspberry Pi OS (Bookworm ships 3.11), install 3.12 first, e.g. with `pyenv` or `uv`.

  The runner imports pyFRAMED from `PYFRAMED_HOME/src`, never from an installed copy. It refuses another commit or a modified checkout.
- the dataset (doi:10.18154/RWTH-2026-06634), e.g. under `data/replay/data/`, as for the ARN experiments.

**MIMIC study:**
- record **3000125** of the MIMIC-III Waveform Database v1.0 (PhysioNet, open access, ODbL; ≈ 245 MB):
  ```bash
  wget -r -N -c -np https://physionet.org/files/mimic3wdb/1.0/30/3000125/
  head -1 physionet.org/files/mimic3wdb/1.0/30/3000125/3000125.hea   # 3000125/41 4 125 85770000 ...
  ```
- **memory:** every measured JVM runs with `-Xms4g -Xmx4g`, the same on both platforms. The flat-out points of Study A retain a backlog of up to ≈ 3 GB. A Pi therefore needs ≥ 8 GB RAM; with less, set a smaller `JAVA_OPTS` on **both** platforms and report it.

**Evaluation:** Python ≥ 3.10 with the pinned packages in `requirements.txt`. `make_figures.py` also needs a Chrome/Chromium for Kaleido.

**Check that both platforms use identical inputs:** compare the `inputs (sha256)` blocks of the two manifests. For the dataset, the `sha256sum` recipe in `experiments/arn-latency/REPRODUCE.md` can also be run beforehand.

## 2. Checkout and build

```bash
git switch experiments/framed-benchmark       # or: git worktree add ../FRAMED-bench experiments/framed-benchmark
git status --short --untracked-files=no        # empty: the manifest records the state
git -C ../safety-box-python checkout 583914ca0b5dc600ee037bfc1ab3d882c8313518
JAVA_HOME=/usr/lib/jvm/<jdk-21> mvn -q -DskipTests install
```

**While the ARN experiment is running on the same machine:**
- don't switch branches in its checkout; the ARN resume would be refused;
- use a separate worktree instead;
- never run both experiments at the same time.

`run_mimic.sh` needs `install`, not just `package`: it runs the compiled benchmark classes offline through `surefire:test`, against the installed SNAPSHOTs. Without `SKIP_BUILD=1`, both runners build with the pinned JDK themselves.

## 3. Running

### Kink over TCP

```bash
LABEL=AMD experiments/framed-benchmark/run_kink.sh data/replay/data experiments/framed-benchmark/out/kink-amd
```

Arguments: `<REPLAY_ROOT> <OUT_ROOT> [UNITS]` (default `kink_units.txt`).

**Units** (`kink_units.txt`), one per line: `<kind> <recording> <speed> [dispatch]`.

| Kind | Meaning |
|---|---|
| `dist` | Distributed run. Java `ReplayProtocol` → TCP → pyFRAMED (FeatureScaling → PCA → KinkClassification, reactors from `config/services_kink_replay.json` with model `kink_logreg_heldout_v2`) → TCP → Java `CountingDispatcher` + `JsonlDispatcher`. |
| `ctrl` | Single-process control: the same reactors and the same config, replayed by pyFRAMED's `JsonlReplayProtocol` on a local bus (`kink_baseline.py`). Dispatch mode `PER_HANDLER` (as deployed) or `SEQUENTIAL`. |
| `pair` | One `dist` and one `ctrl`. Their order alternates per list entry. |

**Speed-up runs keep the real-time duration.** At an integer speed s > 1, the recording is replayed s times back to back:
- the distributed run uses `ReplayProtocol`'s `repeat = s`;
- the control replays the same timeline from a concatenated file, cached under `OUT_ROOT/.inputs/`. `concat_recording.py` uses `ReplayProtocol`'s seam rule: the gap is the smallest positive interval between consecutive timestamps, and pass r is shifted by r × (span + gap);
- a speed-up run thus lasts as long as a real-time run and offers s times the event rate;
- flat out (speed 0) replays the recording once.

**Content of `kink_units.txt`:**
- **48 held-out recordings** (p08, p09, p10: the participants that `kink_logreg_heldout_v2` was not fitted on), each as a `pair` at speed 1;
- **6 control repetitions** (2 per participant), each listed directly after its original. They give the run-vs-run and control-vs-control pairs;
- **speed sweep** on `p10-VC1-knick-0`: `pair` at 10× and 60× (3 each), `dist` flat out (3);
- **dispatch contrast:** 2 `SEQUENTIAL` controls of `p10-VC1-knick-0`.

**Unit count:** 65 list lines, i.e. 60 pairs (48 + 6 at speed 1, plus 6 in the sweep) and 5 single units, giving 60 × 2 + 5 = **125 units**.

| Variable | Default | Meaning |
|---|---|---|
| `LABEL` | `AMD` | platform label (`PI` on the Raspberry Pi) |
| `PYFRAMED_HOME` | `../safety-box-python` | pyFRAMED checkout |
| `PYFRAMED_COMMIT` | `583914ca…` | required commit |
| `PYFRAMED_CONFIG` | `config/services_kink_replay.json` | pyFRAMED config whose `Reactors` are deployed |
| `PYTHON` | poetry environment, else `python3` | interpreter with pyFRAMED's dependencies |
| `JAVA_OPTS` | `-Xms1g -Xmx1g -XX:+UseG1GC` | Java JVM options |
| `JAVA_PORT` / `PY_PORT` | `4999` / `5999` | bus ports |
| `START_DELAY` / `GRACE` | `15` / `20` | replay pre-roll and trailing grace (s) |
| `JAVA_MAJOR`, `JAVA`, `SKIP_BUILD` | | as in the ARN runner |

**Duration:** ≈ 11 min per unit, i.e. ≈ 22 h per platform. This estimate is based on the recording length (≈ 608 s) plus pre-roll and grace; the pilot will measure it.

### MIMIC throughput and pacing

```bash
LABEL=AMD experiments/framed-benchmark/run_mimic.sh \
  ../physionet.org/files/mimic3wdb/1.0/30/3000125/3000125.hea experiments/framed-benchmark/out/mimic-amd
```

| Unit | What runs |
|---|---|
| `study-a_e1` … `e6` | `MimicThroughputBenchmark`, one experiment per unit: e1 offered load, e2 devices, e3 sinks, e4 dispatch mode, e5 real-time beds, e6 `LocalEventBus` vs `SocketEventBus`; 3 repeats per point |
| `study-b_e1` … `e3` | `SocketPairThroughputBenchmark`: LOCAL / TCP / UDP over load, flat out, unprimed start; 3 repeats per point |
| `pacing_sweep` | `MimicPacingBenchmark`: 1, 5, 10, 50× and flat out |
| `full-app_x1`, `x200`, `x100000` | `Main` from the fat jar, config-driven (`mimic_services.json`), `SocketEventBus`, `MimicReplayProtocol` → `CountingDispatcher` |

**Sweeps:** the whole set of 13 units runs `SWEEPS` times (default 2, runs `…_r0` and `…_r1`), so each sweep is a complete measurement at a later time. The difference between sweeps is the run-to-run reference for the platform comparison.

**The speed-ups keep a constant wall-clock duration**, as in the kink study: the benchmarks size the record window as speed × target wall time (`mimic.tp.wallSeconds`, `mimic.sp.wallSeconds`); the full-app runs use 6 record seconds per unit of speed (60 s at 1×, capped at 8000 s).

| Variable | Default | Meaning |
|---|---|---|
| `LABEL` | `AMD` | platform label |
| `STUDIES` | `study-a study-b pacing full-app` | subset |
| `SWEEPS` | `2` | repetitions of the whole set |
| `JAVA_OPTS` | `-Xms4g -Xmx4g -XX:+UseG1GC` | every measured JVM (Surefire `argLine` and full-app) |
| `BENCH_PROPS` | empty | extra `-D` options of the benchmark classes, e.g. for a smoke test (recorded in the manifest; leave empty for the study) |
| `PORT`, `JAVA_MAJOR`, `JAVA`, `SKIP_BUILD` | | as above |

**Duration:** roughly 0.5–1 h per sweep on the AMD. This is extrapolated from the earlier sessions (Study A ≈ 14 min, Study B ≈ 10–18 min) and is unverified for the Pi.

### Controlled environment and start

Same rules as the ARN experiments (no CPU pinning; runtime-only settings, restored by a reboot):

```bash
sudo systemctl isolate multi-user.target
echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor
sudo systemctl stop apt-daily.timer apt-daily-upgrade.timer man-db.timer unattended-upgrades 2>/dev/null
```

Start each study detached, one after the other, never in parallel with each other or with the ARN experiment:

```bash
sudo systemd-run --unit=bench-kink -p User=$USER -p WorkingDirectory=$PWD \
  --setenv=LABEL=PI --setenv=SKIP_BUILD=1 --setenv=PATH=$PATH \
  experiments/framed-benchmark/run_kink.sh data/replay/data experiments/framed-benchmark/out/kink-pi

sudo systemd-run --unit=bench-mimic -p User=$USER -p WorkingDirectory=$PWD \
  --setenv=LABEL=PI --setenv=SKIP_BUILD=1 --setenv=PATH=$PATH \
  experiments/framed-benchmark/run_mimic.sh <path>/3000125.hea experiments/framed-benchmark/out/mimic-pi
```

`--setenv=PATH=$PATH` is required:
- `run_mimic.sh` calls Maven for every unit;
- `run_kink.sh` finds the poetry environment through `poetry`.

### Monitoring, stop and resume

```bash
cat experiments/framed-benchmark/out/kink-pi/PI_progress.txt
journalctl -u bench-kink -f | grep '^\['
sudo systemctl stop bench-kink
```

**To resume,** start the identical command again (after `sudo systemctl reset-failed bench-kink` if systemd reports that the unit exists):
- complete units are skipped: the last attempt exited 0 and the outputs exist;
- incomplete units are deleted and repeated.

**Resuming is refused** if any of these changed: the FRAMED commit, the number of modified tracked files, (for kink) the pyFRAMED commit, or the run settings in the manifest's `settings:` line:
- kink: unit list hash, `JAVA_OPTS`, `START_DELAY`, `GRACE`, `PYFRAMED_CONFIG`;
- MIMIC: `STUDIES`, `SWEEPS`, `JAVA_OPTS`, `BENCH_PROPS`.

So a smoke test with `BENCH_PROPS` can never be continued as the real study in the same `OUT_ROOT`. `RESUME_ANYWAY=1` overrides this.

**After a reboot:** set up the controlled environment again. The runs after a resume form a new session; the `resumed:` lines in the manifest separate the sessions.

### Output

```
<OUT_ROOT>/
  <LABEL>_manifest.txt   commit, dirty state, JDK, JVM options, host (CPU, governor, boost, CPU set,
                         systemd target, locale), SHA-256 of every input; kink: pyFRAMED commit,
                         model and config hashes, Python packages; MIMIC: Maven, record header
  <LABEL>_runs.csv       one row per attempt: unit, start/end, exit code, load, temperature,
                         throttling before/after
  <LABEL>_progress.txt
  <LABEL>_KINK/dist/<rec>_x<speed>_r<k>/        config/ (Java), services_python.json,
                                                communication_python.json, run.json, java.log,
                                                python.log, gc.log, capture/<ms>_classifications.jsonl
  <LABEL>_KINK/ctrl/<rec>_x<speed>_<per|seq>_r<k>/  services_python.json, run.json (incl. the
                                                input's SHA-256), baseline.log, baseline.jsonl(.meta.json)
  .inputs/<rec>_x<k>.jsonl                      concatenated control inputs (speed-up runs)
  <LABEL>_MIMIC/<study>/<unit>_r<k>.csv / .log  benchmark CSV and Maven/JVM output
  <LABEL>_MIMIC/full-app/<unit>_r<k>_services.json  configuration of a full-app run
  <LABEL>_MIMIC/gc/<unit>_r<k>.log
```

## 4. Evaluation

```bash
cd experiments/framed-benchmark
python analyze_kink.py  --root out/kink-amd  --root out/kink-pi  --out out/analysis-kink
python analyze_mimic.py --root out/mimic-amd --root out/mimic-pi --out out/analysis-mimic
python make_figures.py --single out/analysis-mimic/AMD_r0_study_a.csv \
  --pair out/analysis-mimic/AMD_r0_study_b.csv --out out/analysis-mimic/figures-AMD_r0
```

Both analyses use only units whose last attempt exited 0.

### Kink (`analyze_kink.py`)

**Agreement.** Every pair of series is aligned on (milliseconds since an origin, channel). The origins are chosen among the leading events so that the most events align (`kink_series.compare`, unchanged from the earlier study).

| Pair type | Compares |
|---|---|
| `run_vs_control` | distributed run r<k> vs the PER_HANDLER control r<k>, **speed 1 only**. Above speed 1 the Java replay stamps events at their emission instant (recorded time / speed), while the control keeps the recorded stamps, so the 6-s window covers different amounts of recording. |
| `run_vs_run` | two distributed runs of the same recording and speed |
| `control_vs_control` | two controls of the same recording, speed and dispatch mode |

**How to read it:** `run_vs_control` is meaningful only against `control_vs_control`:
- `PER_HANDLER` is not reproducible run to run, because the waveform channels race to the reactor;
- `SEQUENTIAL` is reproducible.

**Metrics per pair:**
- `aligned_fraction`: aligned instants / unique instants of the larger series;
- `disagreement`: `Kink` mismatches / instants aligned on `Kink`;
- `|Δp|`: p50, p95, p99 and max of the probability difference.

**Sink metrics** (distributed runs):
- received, dropped, handler errors;
- frame-to-verdict latency of the `CountingDispatcher`: now − logical timestamp at the sink, covering the replay, both TCP hops, the three reactors and the sink queue;
- the replay's pacing.

**Aggregation:** the median over pairs or runs, per platform, pair type, speed and dispatch mode, with a bootstrap 95 % CI.

**Files:** `kink_runs.csv`, `kink_controls.csv`, `kink_pairs.csv`, `kink_agreement.csv`, `kink_sink.csv`.

### MIMIC (`analyze_mimic.py`)

**Merging:** the per-unit CSVs are merged into one CSV per platform, sweep and study, in the benchmark's own layout (`<LABEL>_r<k>_study_a.csv`, …). From these:
- `tables_study_a.py` and `tables_study_b.py` render the tables (`.md`). Repeats of a point are collapsed to the median, with the min–max spread;
- `make_figures.py` draws the Study A and B figures.

**Comparison** (`sweeps.csv`): per platform, study and operating point, the median of each sweep and the relative difference of later sweeps to r0. A difference between platforms is only meaningful where it clearly exceeds this sweep-to-sweep difference.

## 5. Checks before trusting a result

- **Runs:** in `<LABEL>_runs.csv`, the last attempt of every unit has exit 0.
- **Raspberry Pi:** `throttled_before` and `throttled_after` are `0x0`.
- **Manifests:** both platforms show the same FRAMED commit, `git dirty: 0`, the same pyFRAMED commit and model hash, and identical input hashes.
- **Kink:**
  - `kink_controls.csv` has `truncated = False`;
  - `kink_sink.csv` has `dropped = 0` and `handlerErrors = 0`;
  - `collapsed_*` in `kink_pairs.csv` is 0 at speed 1. A non-zero value means a backlog stamped several verdicts with one instant.
- **MIMIC:**
  - no `ok = false` rows in the study CSVs, unless they are expected (the earlier sessions documented UDP loss at saturated points);
  - `peakHeapMb` stays below the heap limit.
