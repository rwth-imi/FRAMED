# ARN latency experiments: running and evaluating

The experiments replay every recording of the ventilation and pulse-oximetry dataset through the CDSS Acyclic Reactor Network in real time (recording frequency). Each recording is replayed once with atomic and once with non-atomic reactors. Every reactor evaluation is recorded off the bus. Three scripts then evaluate latency, ordering correctness and consumption completeness.

| File | Purpose |
|---|---|
| `run_replays.sh` | runs the replays and writes the raw data per run |
| `full_recordings.txt` | the full experiment: 158 recordings + 6 control repetitions (328 runs) |
| `pilot_recordings.txt` | a short pilot: 10 recordings + 3 repetitions (26 runs) |
| `analyze_latency.py` | latency per reactor and its decomposition, gating, Interpreter latency per status, control vs control, warm-up |
| `analyze_ordering.py` | out-of-order events per output and device channel |
| `analyze_consumption.py` | consumption completeness (observed / expected outputs) and outputs per firing |
| `plot_latency_distribution.py` | per-reactor latency CDF, tail CDF and histogram; overlays several results roots |
| `plot_ordering.py` | reactor-level out-of-order rate (ppm) per platform, from `ordering.csv` |
| `export_supplement.py` | combines the analysis tables of all platforms into the supplementary material |
| `data_dictionary_*.csv` | data dictionary of the supplementary tables (notes, metrics, columns); rendered to `S0_data_dictionary.xlsx` |
| `framed_runs.py` | helpers shared by the analyses |
| `diagnose_v03.py` | diagnosis of the earlier v03 latency data; not needed for new runs |

## 1. Requirements

**Run:**
- Linux;
- JDK 21 including `javac` (the script refuses any other major version; see `JAVA`, `JAVA_MAJOR` below);
- Maven ≥ 3.8;
- `jq`, `git`, `timeout`, `sha256sum`. On Debian or Raspberry Pi OS, `jq` is usually missing: `sudo apt install jq`.

On a Raspberry Pi, `vcgencmd` is used if available; the user needs to be in the `video` group.

**Disk space:** ≈ 14 MB per run, i.e. ≈ 4.6 GB for the full experiment per platform.

**Evaluation:**
- Python ≥ 3.10 with pandas and numpy; `plot_latency_distribution.py` and `plot_ordering.py` additionally need plotly and kaleido (tested: plotly 7.0.0, kaleido 1.4.0) plus a Chrome/Chromium for static export;
- tested with Python 3.12.3, pandas 2.3.3, numpy 2.4.1.

**Data:** the dataset (doi:10.18154/RWTH-2026-06634), unpacked e.g. to `data/replay/data/`. The runner searches the folder recursively for `<recording>.jsonl`.

**Recordings excluded from the full experiment:** `p04-PC2-knick-0` and `p04-PC2-knick-1`. Their ventilator data are partly or entirely missing after a recording dropout (see the dataset README).

**Check that two copies of the dataset are identical** (e.g. on two machines):

```bash
(cd data/replay/data && find . -name '*.jsonl' | sort | xargs sha256sum) > dataset.sha256   # machine A
(cd data/replay/data && sha256sum -c --quiet ../../../dataset.sha256) && echo "dataset OK"  # machine B
```

## 2. Build

```bash
git status --short --untracked-files=no      # should be empty: the manifest records the state
JAVA_HOME=/usr/lib/jvm/<jdk-21> mvn -q clean install
```

Without `SKIP_BUILD=1`, the runner packages the fat jar itself with the JDK it selected. Use `SKIP_BUILD=1` only if the jar was built after the last `git pull`.

A `systemd-run` unit does not read `~/.bashrc`, so a Maven installed outside `/usr/bin` (e.g. under `/opt`) is not on its `PATH`. In that case, build in the normal shell first and start the unit with `--setenv=SKIP_BUILD=1`, or pass `--setenv=PATH=$PATH`.

## 3. Running

### Command

```bash
LABEL=AMD experiments/arn-latency/run_replays.sh data/replay/data \
  experiments/arn-latency/out/full-amd experiments/arn-latency/full_recordings.txt
```

Arguments: `<REPLAY_ROOT> <OUT_ROOT> [LIST]`. Without a list, every recording except `EXCLUDE` is replayed once.

| Variable | Default | Meaning |
|---|---|---|
| `LABEL` | `AMD` | platform label of the output files (`PI` on the Raspberry Pi) |
| `PASSES` | `atomic non_atomic` | the execution modes; their order alternates per list entry |
| `EXCLUDE` | `p04-PC2-knick-0 p04-PC2-knick-1` | skipped when no list is given |
| `JAVA_MAJOR` | `21` | required Java major version |
| `JAVA` | auto | java binary: `$JAVA_HOME`, then `/usr/lib/jvm/*-21-*`, then `PATH` |
| `JAVA_OPTS` | `-Xms1g -Xmx1g -XX:+UseG1GC` | JVM options |
| `PORT` | `4999` | TCP port of the local event bus |
| `SKIP_BUILD` | `0` | `1` skips `mvn package` |

The CDSS configuration is taken from `config/services_replay.json`. Per run, only the replay device, the JSON-Lines sink and the ventilator channel IDs are adapted, because the dataset uses the long Medibus labels. The repository's `config/` is never modified.

**Run count of `full_recordings.txt`:** (158 + 6) × 2 = 328 runs.
- 158 recordings: the 160 of the dataset minus the two excluded ones;
- 6 control repetitions: recordings listed a second time (marked `# control repetition`), so that the atomic vs non-atomic difference can be read against the run-to-run difference of identical runs (`control.csv`);
- 2 passes per entry: atomic and non-atomic.

**Duration:** ≈ 10.5 min per run, i.e. ≈ 57 h per platform for `full_recordings.txt`. The control repetitions add 12 runs (≈ 2 h).

### Controlled environment

Measure on an otherwise idle machine, with the same setup for all runs of a platform. The CPUs are not pinned and boost is left at its default, so that the setup stays representative of a deployment. All of the following settings are runtime-only, and a reboot restores them.

```bash
sudo systemctl isolate multi-user.target                                    # no desktop
echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor
sudo systemctl stop apt-daily.timer apt-daily-upgrade.timer man-db.timer unattended-upgrades 2>/dev/null
sudo systemd-inhibit --what=sleep:idle sleep infinity &                     # laptops: no suspend
```

Start detached, so the run survives logouts and ssh disconnects:

```bash
sudo systemd-run --unit=arn-full -p User=$USER -p WorkingDirectory=$PWD --setenv=LABEL=PI \
  experiments/arn-latency/run_replays.sh data/replay/data \
  experiments/arn-latency/out/full-pi experiments/arn-latency/full_recordings.txt
```

Restore the governor and the timers afterwards, or reboot. A reboot ends a running experiment.

### Monitoring

```bash
cat experiments/arn-latency/out/full-pi/PI_progress.txt     # [k/328 n%] … remaining, finish ~
journalctl -u arn-full -f | grep '^\['                     # one progress line per run
tail -1 experiments/arn-latency/out/full-pi/PI_runs.csv     # exit code, temperature, throttling
sudo systemctl stop arn-full                                # stop
```

### Interruptions and resume

**Stop** (deliberately, or the machine is needed in between):

```bash
sudo systemctl stop arn-full
cat experiments/arn-latency/out/full-pi/PI_progress.txt     # where it stopped
```

**Resume** (If rebooted in between, go reset controlled environment first):
```bash
git status --short --untracked-files=no      # unchanged since the start; no git pull in between
# after a reboot: set up the controlled environment again (see above)
sudo systemctl reset-failed arn-full         # if systemd reports that the unit already exists
sudo systemd-run --unit=arn-full -p User=$USER -p WorkingDirectory=$PWD \
  --setenv=LABEL=PI --setenv=SKIP_BUILD=1 \
  experiments/arn-latency/run_replays.sh data/replay/data \
  experiments/arn-latency/out/full-pi experiments/arn-latency/full_recordings.txt
```

That is the **identical command**, i.e. the same working directory, `OUT_ROOT`, list and variables:
- runs that are complete are skipped: the last attempt exited 0 and all output files exist;
- incomplete runs, such as the one that was stopped, are deleted and repeated;
- each resume is appended to the manifest with a timestamp, the jar's SHA-256 and the Java version;
- the progress file then shows the earlier runs as `skipped`.

Resuming is refused if the git commit or the number of modified tracked files has changed since the start, because a run must not mix code versions; `RESUME_ANYWAY=1` overrides this. Do not rebuild the jar before resuming. To start over, use a new `OUT_ROOT`.

The runs after a resume belong to a new session, so host state may shift absolute latencies (see Section 5). Atomic and non-atomic stay paired per recording. The `resumed:` timestamps in the manifest separate the sessions.

### Output

```
<OUT_ROOT>/
  <LABEL>_manifest.txt      commit, dirty state, jar and JDK, JVM options, OS, CPU, governor,
                            boost, CPU set, systemd target, SHA-256 of every input; resumes
  <LABEL>_runs.csv          one row per attempt: mode, run, start/end, exit code, load,
                            temperature and throttling before/after
  <LABEL>_progress.txt      progress and ETA
  <LABEL>_ATOMIC/, <LABEL>_NON_ATOMIC/
    <epochms>_<run>_replay.jsonl          bus output (inputs and reactor outputs)
    metrics/<run>_firings.csv             every evaluation: FIRED/GATED, trigger, arrival,
                                          decision and reaction instants (ns)
    metrics/<run>_inputs.csv              the inputs consumed per evaluation
    metrics/<run>_summary.csv             fired / gated / no-rule per reactor; dropped records
    metrics/<run>_emits.csv               scheduled and actual send instant of every replayed event
    config/<run>_services.json            the configuration this run used
    config/<run>_communication.json
    gc/<run>.log                          JVM GC log
```

A run is named `<recording>_r<k>`, where `k` counts the repetitions of a recording in the list.

## 4. Evaluation

Run all three analyses per results root. They read only runs whose last attempt exited 0:

```bash
cd experiments/arn-latency
for a in latency ordering consumption; do
  python analyze_$a.py --root out/full-amd --out out/full-amd/analysis
done
```

`analyze_ordering.py` and `analyze_consumption.py` take `--workers N`. `analyze_consumption.py` takes `--config <services.json>` as a fallback for runs recorded before the runner saved the configuration.

### Latency (`analyze_latency.py`)

Per FIRED evaluation (ms):

| Metric | Definition |
|---|---|
| `trigger_net` | reaction end − actual send instant of the triggering instant (**primary**) |
| `trigger` | reaction end − timestamp of the triggering message (includes the replay's wake-up delay) |
| `transport_net` / `scheduling` / `execution` | decomposition: send → arrival → decision → reaction end |
| `end_to_end` | reaction end − logical timestamp of the snapshot |
| `rule_wait` | decision − arrival, per consumed input |
| `consumption` | reaction end − input timestamp, at the input's first consumption (includes the replay's wake-up delay) |
| `consumption_net` | reaction end − actual send instant of that input, at its first consumption |

Inputs first taken by a GATED evaluation are excluded from `consumption` and `consumption_net`: nothing was published, and the next firing that still holds the value can be seconds later.
| `replay_lag` | actual − scheduled send instant (the replay's own delay) |

**Statistics:**
- the first `--warmup-s` seconds (default 30) are excluded;
- n, mean, std, p50, p90, p99, p99.9 and max are computed per run (the 99.9th percentile is named `p999` in column names);
- these are aggregated over runs as the median, with a bootstrap 95 % CI;
- `summary.csv` also gives the pooled mean and std over all samples (`mean_pooled`, `std_pooled`, sample std), as reported in the v03 manuscript.

Mean and std are dominated by a few deferred samples; the percentiles are the primary measure.

**Files:**
- `summary.csv`, `per_run.csv`;
- `evaluations.csv` (fired / gated / no-rule, gated share);
- `interpreter_status.csv` (Interpreter latency per WARNING / NO_WARNING / SUPPRESSED);
- `control.csv` (run-to-run differences: control vs control next to atomic vs non-atomic);
- `warmup_sensitivity.csv`;
- `consumption_net.csv` (`consumption` and `consumption_net` per reactor side by side, with the wake-up correction `wakeup_p50`);
- `load_per_run.csv`, `load.csv` (median and maximum over runs): load indicators, since CPU usage is not recorded. They give the offered load (`device_events_per_s`), `firings_per_s` and the mean reaction time per firing (`exec_us_per_firing`). They also give the wall time spent in reactor evaluation as a share of one core: `exec_share_pct` = Σ(reaction end − start) / run duration, and `busy_share_pct` = the same plus Σ(firing decision − trigger arrival). These are wall-time intervals, not CPU time: lock waits and descheduling are included, while the event bus, replay, sink and JVM are excluded. GC pauses (`gc_pauses`, `gc_pause_ms_total`, `gc_pause_ms_max`, `gc_pause_share_pct`) are parsed from `gc/<run>.log`. Columns ending in `_steady` cover only the firings after `--warmup-s`, the window of the latency statistics. `load.csv` also pools them over all firings of all runs (`*_steady_pooled`): firings/s, decision + reaction time per firing, share of one core, and the mean `trigger_net` latency.

### Ordering correctness (`analyze_ordering.py`)

**Out of order:** an event on a channel whose timestamp is strictly earlier than that of the previous event on the same channel, in the order the sink received them. Equal consecutive timestamps are counted separately as `ties`:
- the Interpreter publishes one status per classification path of an instant;
- the recordings contain a few duplicate device timestamps.

Scope `reactor` = CDSS outputs; scope `device` = replayed device channels.

**Files:** `ordering_per_run.csv`, `ordering.csv` (correctness in %, out-of-order events per million, runs with any out-of-order event).

### Consumption completeness (`analyze_consumption.py`)

**Observed / expected:** the reactor outputs on the bus, divided by the outputs its inputs should produce under its firing semantics, using the run's own configuration.

| Reactor type | Expected outputs |
|---|---|
| single input | input events |
| OR rules (A4, A10, A12, A13) | sum of input events |
| trend (A7, A8) | input events − (windowSize − 1) |
| dislocation (A9) | min(SpO₂ trend, S/F class) + etCO₂ class events |
| RR estimator (A11) | waveform events − (windowSize/2 − 1 − 60/RR · 3.125 Hz) |

Inputs are counted from the reactor's join start, i.e. the latest first timestamp of its inputs.

A13 (Interpreter) is reported as a check only. Its value is below 100 % by design, because one firing can consume several paths of an instant that arrive together.

**Input-side completeness (OR rules: A4, A10, A12, A13):** the input records a reactor received on the bus over the whole run (device channels de-duplicated as above, CDSS channels not), and the share of them that a FIRED evaluation consumed (`metrics/<run>_inputs.csv`). The difference between inputs and outputs is split into `coalesced` (consumed together with other inputs in one firing), `skipped_same_ts` (timestamp equal to a consumed record on the same channel) and `skipped_late` (distinct timestamp never consumed: arrived after a newer record). This follows the semantics of an OR rule, where one firing may consume several updates arriving together, and is the completeness measure for A13. Timestamps are compared at microsecond resolution. The recorder's sequence numbers are not used, because a firing can consume a value before its sequence number is incremented.

**Outputs per firing:** bus outputs / FIRED evaluations of the recorder. Values below 1 are the warm-up of windowed reactors.

**Files:** `consumption.csv`, `consumption_per_run.csv`, `consumption_counts_per_run.csv`, `outputs_per_firing.csv`, `input_consumption.csv`, `input_consumption_per_run.csv`.

### Latency distributions (`plot_latency_distribution.py`)

One figure file per reactor and plot kind, for assembling sub-plot graphics; one line per platform (colour) and execution mode (solid = atomic, dashed = non-atomic). Repeat `--root` to overlay platforms:

```bash
python plot_latency_distribution.py --root out/full-amd --root out/full-pi \
  --out out/comparison [--metric trigger_net] [--kind cdf ccdf hist] [--workers 8]
```

- the latency is computed with `analyze_latency.py`'s own loader, with the same warm-up exclusion (`--warmup-s`, default 30);
- `--metric`: `trigger_net` (default), `trigger`, `end_to_end`, `transport_net`, `scheduling`, `execution`, `consumption` or `consumption_net`;
- the samples of all runs of a platform and mode are pooled on a logarithmic grid (`--bins-per-decade`, default 50), so a run is weighted by its number of firings. This differs from `summary.csv`, which takes the median of per-run percentiles;
- all figures of a metric share one x range in whole decades, ticked and labelled 10^n (`--xrange per-reactor` fits each figure instead); histograms are summed into `--hist-bins-per-decade` bins (default 10);
- outputs: `<cdf|ccdf|hist>_<metric>/A01 … A13.{pdf,svg,png}`, a separate `legend.*` in each folder (every figure also has the legend inside its plot area; `--no-panel-legend` omits it), and the binned counts `latency_bins_<metric>.csv`; `--from-bins` re-plots from that file without re-reading the runs;
- `--formats`, `--width`, `--height` (default 520 × 380), `--title` (prints the reactor above each figure; off by default, the file name `A01`…`A13` identifies it).

### Out-of-order rate (`plot_ordering.py`)

Reads `analysis/ordering.csv` of each root (run `analyze_ordering.py` first) and plots one panel per platform with a bar per reactor that had at least one out-of-order event on any platform; every bar is labelled `out_of_order/total`, zero bars included:

```bash
python plot_ordering.py --root out/full-amd --root out/full-pi --out out/comparison [--mode NON_ATOMIC]
```

Outputs: `ordering_<mode>.{pdf,svg,png}` and the plotted table `ordering_<mode>.csv`.

### Supplementary material (`export_supplement.py`)

Run after the three analyses, with one `--root` per platform:

```bash
python export_supplement.py --root out/full-amd --root out/full-pi --out out/supplement
```

Writes `S1_latency.csv` (from `summary.csv`), `S2_completeness_outputs.csv` (`consumption.csv`), `S3_completeness_inputs.csv` (`input_consumption.csv`), `S4_ordering.csv` (`ordering.csv`), `S5_load.csv` (`load.csv`) and `S6_control.csv` (`control.csv`), each combined over the platforms.

It also writes `S0_data_dictionary.xlsx` (sheets Tables, Notes, Metrics, Columns) and `PROVENANCE.txt` (results roots, manifest commits, jar hashes, script commit). The dictionary is maintained only in `data_dictionary_notes.csv`, `data_dictionary_metrics.csv` and `data_dictionary_columns.csv`. The script refuses to write when a table has an empty `label`, a column name outside `[A-Za-z0-9_]`, or columns that differ from the dictionary, so a table from an older analysis run or an outdated dictionary is caught.

## 5. Checks before trusting a result

- `<LABEL>_runs.csv`: every run has exit 0 in its last attempt.
- `dropped` in the recorder summaries is 0; `analyze_latency.py` warns otherwise.
- On the Raspberry Pi: `throttled_before` and `throttled_after` in `<LABEL>_runs.csv` are `0x0`.
- The manifest shows `git dirty: 0` and the same commit on all platforms, and the input hashes agree between platforms.
- Compare runs within a session. Host state that the manifest does not record (background load, idle states) shifts absolute latencies between sessions, so compare atomic vs non-atomic against the control-vs-control differences.
