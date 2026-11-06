#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Throughput and pacing of the FRAMED event path, driven by MIMIC-III Waveform Database record
# 3000125 (125 Hz, 4 signals, 41 segments):
#
#   study-a   MimicThroughputBenchmark: one instance; offered load, devices, sinks, dispatch mode,
#             real-time beds, LocalEventBus vs SocketEventBus (experiments e1..e6, 3 repeats/point)
#   study-b   SocketPairThroughputBenchmark: two instances over the socket bus; LOCAL / TCP / UDP
#             (experiments e1..e3, 3 repeats/point)
#   pacing    MimicPacingBenchmark: pacing fidelity at 1/5/10/50x and flat out
#   full-app  the deployment path: com.framed.orchestrator.Main from the fat jar, config-driven,
#             SocketEventBus, MimicReplayProtocol -> CountingDispatcher, at speeds 1, 200, 100000
#
# Usage:
#   experiments/framed-benchmark/run_mimic.sh <RECORD_HEA> <OUT_ROOT>
#
#   RECORD_HEA   master header of record 3000125 (…/mimic3wdb/1.0/30/3000125/3000125.hea)
#   OUT_ROOT     results: <LABEL>_MIMIC/<study>/<run>.csv and <run>.log (study-a, study-b, pacing:
#                the benchmark's CSV; full-app: one row per run plus the services.json used),
#                gc/<run>.log, plus <LABEL>_manifest.txt, <LABEL>_runs.csv, <LABEL>_progress.txt
#
#   A unit is one experiment of a study (study-a e1..e6, study-b e1..e3), the pacing sweep, or one
#   full-app speed. A run is <unit>_r<k>; the whole set of units is repeated SWEEPS times
#   (r0, r1, ...), so each repetition is a complete sweep at a later time.
#
# Environment:
#   LABEL       platform label (default AMD; PI on the Raspberry Pi)
#   STUDIES     subset of "study-a study-b pacing full-app" (default all four)
#   SWEEPS      repetitions of the whole set (default 2)
#   JAVA_OPTS   JVM options of every measured JVM (default -Xms4g -Xmx4g -XX:+UseG1GC: the
#               flat-out points of study A retain a backlog of up to ~3 GB)
#   JAVA_MAJOR/JAVA  pinned Java runtime (default 21; see experiments/common/runlib.sh)
#   PORT        TCP port of the full-app SocketEventBus (default 4999)
#   BENCH_PROPS extra -D options for the benchmark classes, e.g. "-Dmimic.tp.repeats=1" for a
#               smoke test (default empty: the published designs); recorded in the manifest
#   SKIP_BUILD  1 skips the one-off `mvn install`
#
# Resume: re-running the identical command skips every complete unit (last attempt exited 0 and
# its CSV exists) and repeats the others. Resuming is refused if the git commit or the number of
# modified tracked files changed (RESUME_ANYWAY=1 overrides).
set -euo pipefail
REPO="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
HERE="$REPO/experiments/framed-benchmark"
# shellcheck source=../common/runlib.sh
source "$REPO/experiments/common/runlib.sh"
rl_traps run_mimic.sh

[[ -f "${1:?usage: $0 <RECORD_HEA> <OUT_ROOT>}" ]] || { echo "Record header not found: $1 (from $PWD)" >&2; exit 1; }
RECORD="$(readlink -f "$1")"
OUT_ROOT="${2:?usage: $0 <RECORD_HEA> <OUT_ROOT>}"
LABEL="${LABEL:-AMD}"
STUDIES="${STUDIES:-study-a study-b pacing full-app}"
SWEEPS="${SWEEPS:-2}"
JAVA_OPTS="${JAVA_OPTS:--Xms4g -Xmx4g -XX:+UseG1GC}"
PORT="${PORT:-4999}"
BENCH_PROPS="${BENCH_PROPS:-}"

rl_resolve_java || exit 1
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
command -v mvn >/dev/null || { echo "mvn not found on PATH ($PATH); under systemd-run pass --setenv=PATH=\$PATH" >&2; exit 1; }
head -n1 "$RECORD" | grep -q '^3000125/41 4 125 ' \
  || echo "WARNING: $RECORD is not record 3000125 (41 segments, 4 signals, 125 Hz): $(head -n1 "$RECORD")" >&2

# install, so `surefire:test` below runs the compiled benchmarks offline against these SNAPSHOTs
rl_build -DskipTests install || exit 1
JAR="$(ls "$REPO"/framed-app/target/framed-app-*-fat.jar | head -n1)"
[[ -d "$REPO/framed-benchmark/target/test-classes/com/framed/benchmark" ]] \
  || { echo "benchmark classes not compiled; run without SKIP_BUILD=1" >&2; exit 1; }
# Surefire resolves its JUnit provider at the first test run, not during the build; fetch it now
# (online, unmeasured: without -Dmimic.record the benchmark skips at once), so the measured units
# can run offline.
(cd "$REPO" && JAVA_HOME="$JDK_HOME" mvn -q -B -pl framed-benchmark surefire:test -Dtest=MimicPacingBenchmark) \
  > /dev/null 2>&1 || { echo "Surefire preflight failed: (cd $REPO && mvn -pl framed-benchmark surefire:test -Dtest=MimicPacingBenchmark)" >&2; exit 1; }

mkdir -p "$OUT_ROOT"
OUT_ROOT="$(readlink -f "$OUT_ROOT")"
STUDY_ROOT="$OUT_ROOT/${LABEL}_MIMIC"

# --- units -------------------------------------------------------------------------------------
UNITS=()
for s in $STUDIES; do
  case "$s" in
    study-a)  UNITS+=(study-a:e1 study-a:e2 study-a:e3 study-a:e4 study-a:e5 study-a:e6) ;;
    study-b)  UNITS+=(study-b:e1 study-b:e2 study-b:e3) ;;
    pacing)   UNITS+=(pacing:sweep) ;;
    full-app) UNITS+=(full-app:x1 full-app:x200 full-app:x100000) ;;
    *) echo "Unknown study: $s" >&2; exit 1 ;;
  esac
done

# --- provenance --------------------------------------------------------------------------------
MANIFEST="$OUT_ROOT/${LABEL}_manifest.txt"
RUNS="$OUT_ROOT/${LABEL}_runs.csv"
manifest_mimic() {
  echo "study:       MIMIC throughput and pacing"
  echo "jar:         $JAR ($(sha256sum "$JAR" | cut -d' ' -f1))"
  echo "java opts:   $JAVA_OPTS"
  echo "maven:       $(mvn -v 2>/dev/null | head -n1)"
  echo "studies:     $STUDIES"
  echo "sweeps:      $SWEEPS"
  echo "bench props: ${BENCH_PROPS:-<none>}"
  echo "record:      $RECORD ($(head -n1 "$RECORD"))"
  echo "full-app:    $(sha256sum "$HERE/mimic_services.json" | cut -d' ' -f1)  mimic_services.json"
  echo "inputs (sha256):"
  local dir; dir="$(dirname "$RECORD")"
  find "$dir" -maxdepth 1 -type f \( -name '*.hea' -o -name '*.dat' \) | sort | while read -r f; do
    rl_hash_inputs "$dir" "$f"
  done
}
resume_mimic() { echo "  jar:       $(sha256sum "$JAR" | cut -d' ' -f1)"; }
RL_SETTINGS="studies=$STUDIES; sweeps=$SWEEPS; java_opts=$JAVA_OPTS; bench_props=$BENCH_PROPS"
rl_manifest "$MANIFEST" manifest_mimic resume_mimic || exit 1
[[ -f "$RUNS" ]] || echo "label,study,unit,rep,run,start_utc,end_utc,exit,load1_before,temp_before,temp_after,throttled_before,throttled_after" > "$RUNS"

# --- one unit ----------------------------------------------------------------------------------
csv_ok() { [[ -f "$1" ]] && (( $(wc -l < "$1") > 1 )); }

# A benchmark class through Surefire, offline and without the build lifecycle, on the pinned JDK.
# The class writes target/benchmark/<csv>; it skips (exit 0, no CSV) if the record is missing,
# which csv_ok then reports as a failure.
surefire() {
  local cls="$1" csv="$2" log="$3" gc="$4"; shift 4
  # shellcheck disable=SC2086
  (cd "$REPO" && JAVA_HOME="$JDK_HOME" timeout 3h mvn -o -B -pl framed-benchmark surefire:test \
      "-Dtest=$cls" "-Dmimic.record=$RECORD" "-DargLine=$JAVA_OPTS -Xlog:gc*:file=$gc:time,uptime,level,tags" \
      $BENCH_PROPS "$@") > "$log" 2>&1
}

# One full-app run: Main from the fat jar, config in its own working directory.
full_app() {
  local speed="$1" out_csv="$2" log="$3" gc="$4" work seconds code=0
  work="$OUT_ROOT/.work/${LABEL}_$(basename "$out_csv" .csv)"
  rm -rf "$work"; mkdir -p "$work/config"
  # 60 record seconds at 1x; above, enough for a few seconds of wall clock (capped at 8000 s)
  seconds=$(awk -v s="$speed" 'BEGIN { v = (s <= 1) ? 60 : s * 6; if (v > 8000) v = 8000; printf "%d", v }')
  jq --arg rec "$RECORD" --argjson speed "$speed" --argjson secs "$seconds" \
     '.Devices[0] |= (.recordPath = $rec | .speed = $speed | .maxSeconds = $secs)' \
     "$HERE/mimic_services.json" > "$work/config/services.json"
  jq --argjson port "$PORT" '.port = $port | .peers = []' "$REPO/config/communication.json" > "$work/config/communication.json"
  cp "$work/config/services.json" "${out_csv%.csv}_services.json"
  # shellcheck disable=SC2086
  (cd "$work" && timeout 1h "$JAVA_BIN" $JAVA_OPTS "-Xlog:gc*:file=$gc:time,uptime,level,tags" \
      -jar "$JAR") > "$log" 2>&1 || code=$?
  "$PYTHON3" - "$speed" "$seconds" "$log" > "$out_csv" <<'EOF'
import re, sys
speed, seconds, log = sys.argv[1:4]
text = open(log, errors="replace").read()
pacing = ([l for l in text.splitlines() if "MIMIC replay pacing" in l] or [""])[-1]
sink = ([l for l in text.splitlines() if "CountingDispatcher summary" in l] or [""])[-1]
def g(p, t):
    m = re.search(p, t); return m.group(1) if m else ""
lat = g(r"latency\(mean/p50/p95/p99/max\)=([\d./]+) ms", sink).split("/")
lat += [""] * (5 - len(lat))
print("speed,maxSeconds,producerFrames,producerSamples,producerAchievedFrameHz,framesBehind,keptPace,"
      "sinkReceived,sinkDropped,sinkHandlerErrors,sinkAchievedDpPerSec,latMeanMs,latP50Ms,latP95Ms,latP99Ms,latMaxMs")
print(",".join([speed, seconds, g(r"frames=(\d+)", pacing), g(r"samples=(\d+)", pacing),
                g(r"achieved=([\d.]+) Hz", pacing), g(r"behind=(\d+)", pacing),
                g(r"keptPace=(\w+)", pacing), g(r"received=(\d+)", sink), g(r"dropped=(\d+)", sink),
                g(r"handlerErrors=(\d+)", sink), g(r"achieved=([\d.]+) dp/s", sink), *lat[:5]]))
if not pacing or not sink:
    sys.exit(4)
EOF
  local parse=$?
  (( code != 0 )) || code=$parse
  (( code != 0 )) || rm -rf "$work"
  return "$code"
}
PYTHON3="$(command -v python3)"

unit_complete() {
  local run="$1" csv="$2" code
  code="$(rl_last_exit "$RUNS" "label=$LABEL" "run=$run")"
  [[ "$code" == 0 ]] && csv_ok "$csv" && [[ -s "${csv%.csv}.log" ]]
}

# run_unit <study:unit> <rep>: 0 ok, 10 skipped (complete), else failure.
run_unit() {
  local study="${1%%:*}" unit="${1#*:}" rep="$2" run dir csv log gc code=0 name
  run="${study}_${unit}_r${rep}"
  dir="$STUDY_ROOT/$study"; csv="$dir/$run.csv"; log="$dir/$run.log"; gc="$STUDY_ROOT/gc/$run.log"
  UNIT_NAME="$run"
  if unit_complete "$run" "$csv"; then echo "--- skip ${LABEL} $run (complete)"; return 10; fi
  rm -f "$csv" "$log" "$gc" "${csv%.csv}_services.json"
  mkdir -p "$dir" "$STUDY_ROOT/gc"
  echo "==> $(date +%T) ${LABEL} $run"
  rl_host_before
  name="${LABEL}_${run}.csv"
  case "$study" in
    study-a) surefire MimicThroughputBenchmark "$name" "$log" "$gc" "-Dmimic.tp.experiments=$unit" "-Dmimic.tp.csv=$name" || code=$? ;;
    study-b) surefire SocketPairThroughputBenchmark "$name" "$log" "$gc" "-Dmimic.sp.experiments=$unit" "-Dmimic.sp.csv=$name" || code=$? ;;
    pacing)  name="mimic-pacing.csv"; rm -f "$REPO/framed-benchmark/target/benchmark/$name"
             surefire MimicPacingBenchmark "$name" "$log" "$gc" || code=$? ;;
    full-app) full_app "${unit#x}" "$csv" "$log" "$gc" || code=$? ;;
  esac
  if [[ "$study" != full-app ]]; then
    if (( code == 0 )) && csv_ok "$REPO/framed-benchmark/target/benchmark/$name"; then
      mv "$REPO/framed-benchmark/target/benchmark/$name" "$csv"
    elif (( code == 0 )); then
      echo "no CSV from $run (did the benchmark skip? see $log)" >&2; code=4
    fi
  fi
  local after; after="$(rl_host_after)"
  echo "$LABEL,$study,$unit,$rep,$run,$RL_START,${after%%,*},$code,$RL_LOAD,$RL_TEMP0,$(cut -d, -f2 <<< "$after"),$RL_THR0,$(cut -d, -f3 <<< "$after")" >> "$RUNS"
  (( code == 0 )) || echo "!! $run exited with $code; see $log" >&2
  return "$code"
}

# --- main loop ---------------------------------------------------------------------------------
rl_progress_init $(( ${#UNITS[@]} * SWEEPS )) "$OUT_ROOT/${LABEL}_progress.txt"
for (( rep = 0; rep < SWEEPS; rep++ )); do
  for u in "${UNITS[@]}"; do
    rl_unit_start; code=0
    run_unit "$u" "$rep" || code=$?
    case "$code" in 0) outcome=ran ;; 10) outcome=skipped ;; *) outcome=failed ;; esac
    rl_progress_step "$outcome" "$UNIT_NAME"
  done
done

echo "finished:    $(date -u +%FT%TZ)" >> "$MANIFEST"
echo "Done: $(( ${#UNITS[@]} * SWEEPS )) units, skipped (complete): $RL_SKIPPED, failures: $RL_FAILED. Runs: $RUNS"
if (( RL_FAILED > 0 )); then echo "$RL_FAILED unit(s) failed; see $RUNS" >&2; exit 1; fi
