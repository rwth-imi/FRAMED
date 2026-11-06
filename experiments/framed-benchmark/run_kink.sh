#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Kink classification over TCP: a Java FRAMED instance replays a recording and sinks the kink
# verdicts that a pyFRAMED CDSS computes for it; the two instances are joined by SocketEventBus
# over TCP on loopback. Each recording is also replayed through a single-process control (the
# same reactors inside one pyFRAMED process, no Java, no socket), so the distributed output can
# be compared with it.
#
#   Java  ReplayProtocol --TCP--> pyFRAMED (FeatureScaling -> PCA -> KinkClassification)
#   Java  CountingDispatcher + JsonlDispatcher <--TCP-- Kink, Kink-Probability
#
# Usage:
#   experiments/framed-benchmark/run_kink.sh <REPLAY_ROOT> <OUT_ROOT> [UNITS]
#
#   REPLAY_ROOT  unpacked dataset (searched recursively for <recording>.jsonl)
#   OUT_ROOT     results:
#                  <LABEL>_KINK/dist/<run>/   distributed run: config/ (Java), services_python.json,
#                                             communication_python.json, run.json, java.log,
#                                             python.log, gc.log, capture/<ms>_classifications.jsonl
#                  <LABEL>_KINK/ctrl/<run>/   control: services_python.json, run.json, baseline.log,
#                                             baseline.jsonl, baseline.jsonl.meta.json
#                plus <LABEL>_manifest.txt, <LABEL>_runs.csv (one row per attempt) and
#                <LABEL>_progress.txt
#   UNITS        unit list (default: kink_units.txt next to this script); see its header
#
#   A run is named <recording>_x<speed>_r<k> (distributed) or <recording>_x<speed>_<per|seq>_r<k>
#   (control), where k counts the repetitions of that unit in the list.
#
#   Speed-up runs keep the real-time duration: at an integer speed s > 1 the recording is replayed
#   s times back to back, so the run offers s times the event rate for as long as a real-time run.
#   The distributed run uses ReplayProtocol's repeat = s; the control replays the same timeline from
#   a concatenated file (concat_recording.py, cached under <OUT_ROOT>/.inputs/). Flat out (speed 0)
#   replays the recording once.
#
# Environment:
#   LABEL            platform label (default AMD; PI on the Raspberry Pi)
#   PYFRAMED_HOME    the safety-box-python checkout (default: ../safety-box-python next to the repo)
#   PYFRAMED_COMMIT  required commit of that checkout (default 583914c...); the runner refuses
#                    another commit or local changes to tracked files
#   PYFRAMED_CONFIG  pyFRAMED services config whose Reactors are deployed
#                    (default config/services_kink_replay.json, relative to PYFRAMED_HOME)
#   PYTHON           interpreter with pyFRAMED's dependencies (default: the checkout's poetry
#                    environment, else python3). pyFRAMED itself is always imported from
#                    PYFRAMED_HOME/src, never from an installed copy.
#   JAVA_OPTS        JVM options (default: fixed 1 GiB heap, G1)
#   JAVA_MAJOR/JAVA  pinned Java runtime (default 21; see experiments/common/runlib.sh)
#   JAVA_PORT/PY_PORT  bus ports of the distributed run (default 4999 / 5999)
#   START_DELAY/GRACE  replay pre-roll / trailing grace in seconds (default 15 / 20)
#   SKIP_BUILD       1 skips the one-off `mvn package`
#
# Resume: re-running the identical command skips every complete unit (last attempt exited 0 and
# its outputs exist) and repeats the others from scratch. Resuming is refused if the FRAMED or
# the pyFRAMED commit, or the number of modified tracked files, changed (RESUME_ANYWAY=1
# overrides). For each pair, the order of distributed run and control alternates per list entry.
set -euo pipefail
REPO="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
HERE="$REPO/experiments/framed-benchmark"
# shellcheck source=../common/runlib.sh
source "$REPO/experiments/common/runlib.sh"
rl_traps run_kink.sh

[[ -d "${1:?usage: $0 <REPLAY_ROOT> <OUT_ROOT> [UNITS]}" ]] || { echo "Not a directory: $1 (from $PWD)" >&2; exit 1; }
REPLAY_ROOT="$(readlink -f "$1")"
OUT_ROOT="${2:?usage: $0 <REPLAY_ROOT> <OUT_ROOT> [UNITS]}"
UNITS="${3:-$HERE/kink_units.txt}"
[[ -f "$UNITS" ]] || { echo "Unit list not found: $UNITS (from $PWD)" >&2; exit 1; }
LABEL="${LABEL:-AMD}"
PYFRAMED_HOME="$(readlink -f "${PYFRAMED_HOME:-$REPO/../safety-box-python}")"
PYFRAMED_COMMIT="${PYFRAMED_COMMIT:-583914ca0b5dc600ee037bfc1ab3d882c8313518}"
PYFRAMED_CONFIG="${PYFRAMED_CONFIG:-config/services_kink_replay.json}"
JAVA_OPTS="${JAVA_OPTS:--Xms1g -Xmx1g -XX:+UseG1GC}"
JAVA_PORT="${JAVA_PORT:-4999}"
PY_PORT="${PY_PORT:-5999}"
START_DELAY="${START_DELAY:-15}"
GRACE="${GRACE:-20}"

rl_resolve_java || exit 1
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

# --- pyFRAMED: pinned checkout, its own sources ------------------------------------------------
[[ -d "$PYFRAMED_HOME/.git" || -f "$PYFRAMED_HOME/.git" ]] || { echo "pyFRAMED checkout not found: $PYFRAMED_HOME (set PYFRAMED_HOME)" >&2; exit 1; }
PY_HEAD="$(git -C "$PYFRAMED_HOME" rev-parse HEAD)"
PY_DIRTY="$(git -C "$PYFRAMED_HOME" status --porcelain --untracked-files=no | wc -l)"
[[ "$PY_HEAD" == "$PYFRAMED_COMMIT"* ]] || { echo "pyFRAMED is at $PY_HEAD, required $PYFRAMED_COMMIT (git -C $PYFRAMED_HOME checkout $PYFRAMED_COMMIT)" >&2; exit 1; }
(( PY_DIRTY == 0 )) || { echo "pyFRAMED has $PY_DIRTY modified tracked files; the study runs on the pinned commit only" >&2; exit 1; }
PY_SERVICES_SRC="$PYFRAMED_HOME/$PYFRAMED_CONFIG"
[[ -f "$PY_SERVICES_SRC" ]] || { echo "pyFRAMED config not found: $PY_SERVICES_SRC" >&2; exit 1; }
MODEL="$(jq -r '[.Reactors[].modelPath // empty] | unique | if length == 1 then .[0] else error("reactors name \(length) model files") end' "$PY_SERVICES_SRC")"
[[ -f "$PYFRAMED_HOME/$MODEL" ]] || { echo "model not found: $PYFRAMED_HOME/$MODEL" >&2; exit 1; }

if [[ -z "${PYTHON:-}" ]]; then
  if env_dir="$(cd "$PYFRAMED_HOME" && poetry env info -p 2>/dev/null)" && [[ -x "$env_dir/bin/python" ]]; then
    PYTHON="$env_dir/bin/python"
  else
    PYTHON="$(command -v python3)"
  fi
fi
export PYTHONPATH="$PYFRAMED_HOME/src"
pyframed_file="$("$PYTHON" -c 'import pyframed; print(pyframed.__file__)' 2>/dev/null)" \
  || { echo "pyframed or its dependencies are not importable with $PYTHON (run 'poetry install' in $PYFRAMED_HOME, or set PYTHON)" >&2; exit 1; }
[[ "$(readlink -f "$pyframed_file")" == "$PYFRAMED_HOME/src/"* ]] \
  || { echo "pyframed resolves to $pyframed_file, not to $PYFRAMED_HOME/src" >&2; exit 1; }

rl_build -DskipTests package || exit 1
JAR="$(ls "$REPO"/framed-app/target/framed-app-*-fat.jar | head -n1)"

mkdir -p "$OUT_ROOT"
OUT_ROOT="$(readlink -f "$OUT_ROOT")"
STUDY="$OUT_ROOT/${LABEL}_KINK"

# --- units -------------------------------------------------------------------------------------
recording_path() {
  local p
  p="$(find "$REPLAY_ROOT" -type f -name "$1.jsonl" | head -n1)"
  [[ -n "$p" ]] || { echo "Recording not found: $1" >&2; return 1; }
  echo "$p"
}
speed_token() { awk -v s="$1" 'BEGIN { if (s == int(s)) printf "%d", s; else printf "%s", s }'; }

UNIT_LINES=()
while read -r line; do
  line="${line%%#*}"
  read -r -a f <<< "$line"
  (( ${#f[@]} == 0 )) && continue
  case "${f[0]}" in pair|dist|ctrl) ;; *) echo "Unknown unit kind in $UNITS: ${f[0]}" >&2; exit 1 ;; esac
  [[ -n "${f[2]:-}" ]] || { echo "Unit without speed in $UNITS: $line" >&2; exit 1; }
  dispatch="${f[3]:-PER_HANDLER}"
  case "$dispatch" in PER_HANDLER|SEQUENTIAL) ;; *) echo "Unknown dispatch in $UNITS: $dispatch" >&2; exit 1 ;; esac
  if ! awk -v s="${f[2]}" 'BEGIN { exit !(s >= 0 && (s <= 1 || s == int(s))) }'; then
    echo "A speed above 1 must be an integer (it is also the repeat factor) in $UNITS: $line" >&2; exit 1
  fi
  if [[ "${f[0]}" != dist ]] && awk -v s="${f[2]}" 'BEGIN { exit !(s == 0) }'; then
    echo "A control cannot run flat out (speed 0) in $UNITS: $line" >&2; exit 1
  fi
  recording_path "${f[1]}" >/dev/null
  UNIT_LINES+=("${f[0]} ${f[1]} ${f[2]} $dispatch")
done < "$UNITS"
(( ${#UNIT_LINES[@]} > 0 )) || { echo "No units in $UNITS" >&2; exit 1; }

# --- provenance --------------------------------------------------------------------------------
MANIFEST="$OUT_ROOT/${LABEL}_manifest.txt"
RUNS="$OUT_ROOT/${LABEL}_runs.csv"
if [[ -f "$MANIFEST" && "${RESUME_ANYWAY:-0}" != 1 ]]; then
  orig_py="$(sed -n 's/^pyframed:    \([0-9a-f]*\).*/\1/p' "$MANIFEST" | head -n1)"
  [[ "$orig_py" == "$PY_HEAD" ]] || { echo "Refusing to resume: pyFRAMED started at $orig_py, now $PY_HEAD." >&2; exit 1; }
fi
python_packages() {
  "$PYTHON" -c 'import importlib.metadata as m
for d in sorted(m.distributions(), key=lambda d: d.metadata["Name"].lower()): print("  %s==%s" % (d.metadata["Name"], d.version))'
}
manifest_kink() {
  echo "study:       kink over TCP"
  echo "jar:         $JAR ($(sha256sum "$JAR" | cut -d' ' -f1))"
  echo "java opts:   $JAVA_OPTS"
  echo "pyframed:    $PY_HEAD ($PYFRAMED_HOME, $PY_DIRTY modified)"
  echo "py config:   $PYFRAMED_CONFIG ($(sha256sum "$PY_SERVICES_SRC" | cut -d' ' -f1))"
  echo "model:       $MODEL ($(sha256sum "$PYFRAMED_HOME/$MODEL" | cut -d' ' -f1))"
  echo "python:      $PYTHON ($("$PYTHON" --version 2>&1))"
  echo "ports:       java $JAVA_PORT, python $PY_PORT"
  echo "replay:      start delay ${START_DELAY}s, grace ${GRACE}s"
  echo "replay root: $REPLAY_ROOT"
  echo "units:       $UNITS ($(sha256sum "$UNITS" | cut -d' ' -f1))"
  echo "python packages:"
  python_packages
  echo "inputs (sha256):"
  local e
  for e in "${UNIT_LINES[@]}"; do read -r -a f <<< "$e"; echo "${f[1]}"; done | sort -u | while read -r e; do
    rl_hash_inputs "$REPLAY_ROOT" "$(recording_path "$e")"
  done
}
resume_kink() { echo "  jar:       $(sha256sum "$JAR" | cut -d' ' -f1)"; echo "  pyframed:  $PY_HEAD"; }
RL_SETTINGS="units_sha256=$(sha256sum "$UNITS" | cut -d' ' -f1); java_opts=$JAVA_OPTS; start_delay=$START_DELAY; grace=$GRACE; py_config=$PYFRAMED_CONFIG"
rl_manifest "$MANIFEST" manifest_kink resume_kink || exit 1
[[ -f "$RUNS" ]] || echo "label,kind,recording,speed,dispatch,rep,run,start_utc,end_utc,exit,load1_before,temp_before,temp_after,throttled_before,throttled_after" > "$RUNS"

# --- helpers -----------------------------------------------------------------------------------
port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Seconds from the first to the last event of a recording.
record_span() {
  "$PYTHON" - "$1" <<'EOF'
import json, sys
from datetime import datetime
stamps = [datetime.fromisoformat(json.loads(l)["timestamp"].replace("Z", "+00:00")).timestamp()
          for l in open(sys.argv[1]) if l.strip()]
print(int(max(stamps) - min(stamps)) if stamps else 0)
EOF
}

# Repeat factor of a speed: the speed itself above 1, else 1.
repeat_of() { awk -v s="$1" 'BEGIN { printf "%d", (s > 1) ? s : 1 }'; }

# The control's input at repeat factor k: the recording itself, or its k-fold concatenation.
control_input() {
  local rec="$1" k="$2" src dst
  src="$(recording_path "$rec")"
  if (( k == 1 )); then echo "$src"; return 0; fi
  dst="$OUT_ROOT/.inputs/${rec}_x${k}.jsonl"
  if [[ ! -s "$dst" ]]; then
    mkdir -p "$OUT_ROOT/.inputs"
    "$PYTHON" "$HERE/concat_recording.py" "$src" "$k" "$dst.tmp" >&2 && mv "$dst.tmp" "$dst"
  fi
  echo "$dst"
}

render_python_services() {
  # Only the reactors: their input arrives from the Java instance over the socket.
  jq '{Reactors: .Reactors}' "$PY_SERVICES_SRC" > "$1"
}

unit_complete() {
  local kind="$1" run="$2" dir="$3" code
  code="$(rl_last_exit "$RUNS" "label=$LABEL" "kind=$kind" "run=$run")"
  [[ "$code" == 0 ]] || return 1
  if [[ "$kind" == dist ]]; then
    [[ -s "$dir/run.json" && -s "$dir/services_python.json" && -s "$dir/config/services.json" ]] || return 1
    grep -q "CountingDispatcher summary" "$dir/java.log" 2>/dev/null || return 1
    compgen -G "$dir/capture/*_classifications.jsonl" >/dev/null
  else
    [[ -s "$dir/run.json" && -s "$dir/baseline.jsonl" && -s "$dir/baseline.jsonl.meta.json" ]]
  fi
}

PY_PID=""
stop_python() {
  [[ -n "$PY_PID" ]] || return 0
  kill -TERM "$PY_PID" 2>/dev/null || true
  local _; for _ in $(seq 1 100); do kill -0 "$PY_PID" 2>/dev/null || break; sleep 0.1; done
  kill -KILL "$PY_PID" 2>/dev/null || true
  wait "$PY_PID" 2>/dev/null || true
  PY_PID=""
}
trap stop_python EXIT

# One distributed run; prints nothing on success, returns its exit code.
run_dist() {
  local rec="$1" speed="$2" dir="$3" record span timeout_s k code=0
  record="$(recording_path "$rec")"; k="$(repeat_of "$speed")"
  mkdir -p "$dir/config" "$dir/capture"
  jq --arg file "$record" --argjson speed "$speed" --argjson delay "$START_DELAY" \
     --argjson grace "$GRACE" --arg capture "$dir/capture/" --argjson repeat "$k" '
       (.Devices[0] |= (.filePath = $file | .speed = $speed | .repeat = $repeat
                        | .startDelaySeconds = $delay | .graceSeconds = $grace))
       | (.Dispatchers |= map(if .id == "Classification-Capture" then .path = $capture else . end))
     ' "$HERE/kink_services_java.json" > "$dir/config/services.json"
  jq -n --argjson port "$JAVA_PORT" --argjson peer "$PY_PORT" \
     '{type: "TCP", port: $port, peers: [{host: "127.0.0.1", port: $peer}]}' > "$dir/config/communication.json"
  render_python_services "$dir/services_python.json"
  jq -n --argjson port "$PY_PORT" --argjson peer "$JAVA_PORT" \
     '{type: "TCP", port: $port, peers: [{host: "127.0.0.1", port: $peer}]}' > "$dir/communication_python.json"
  span="$(record_span "$record")"
  timeout_s=$(awk -v d="$START_DELAY" -v g="$GRACE" -v s="$speed" -v t="$span" -v k="$k" \
                'BEGIN { printf "%d", d + g + 180 + (s > 0 ? k * (t + 1) / s : 0) }')
  jq -n --arg record "$record" --argjson speed "$speed" --arg model "$MODEL" \
     --argjson javaPort "$JAVA_PORT" --argjson pyPort "$PY_PORT" --argjson delay "$START_DELAY" \
     --argjson grace "$GRACE" --argjson span "$span" --argjson timeout "$timeout_s" \
     --argjson repeat "$k" \
     '{kind: "dist", record: $record, speed: $speed, repeat: $repeat, model: $model, javaPort: $javaPort,
       pythonPort: $pyPort, startDelaySeconds: $delay, graceSeconds: $grace,
       recordingSeconds: $span, javaTimeoutSeconds: $timeout}' > "$dir/run.json"

  local p; for p in "$JAVA_PORT" "$PY_PORT"; do
    port_free "$p" || { echo "port $p is in use; stop whatever holds it" >&2; return 2; }
  done

  # pyFRAMED first, so its reactors listen before the replay starts.
  ( cd "$PYFRAMED_HOME" && exec "$PYTHON" -m pyframed --services "$dir/services_python.json" \
      --communication "$dir/communication_python.json" ) > "$dir/python.log" 2>&1 &
  PY_PID=$!
  local _; for _ in $(seq 1 300); do
    grep -q "Reactor Network instantiated" "$dir/python.log" && break
    kill -0 "$PY_PID" 2>/dev/null || { echo "pyFRAMED exited during startup; see $dir/python.log" >&2; PY_PID=""; return 3; }
    sleep 0.1
  done
  grep -q "Reactor Network instantiated" "$dir/python.log" || { echo "pyFRAMED did not deploy within 30 s" >&2; stop_python; return 3; }
  # A reactor that fails to instantiate leaves a truncated chain that still runs; refuse it.
  if grep -q "Failed to instantiate Service" "$dir/python.log"; then
    echo "a pyFRAMED reactor failed to instantiate; see $dir/python.log" >&2; stop_python; return 3
  fi

  # shellcheck disable=SC2086
  (cd "$dir" && timeout --kill-after=30 "$timeout_s" "$JAVA_BIN" $JAVA_OPTS \
      "-Xlog:gc*:file=$dir/gc.log:time,uptime,level,tags" -jar "$JAR" > "$dir/java.log" 2>&1) || code=$?
  if ! kill -0 "$PY_PID" 2>/dev/null; then
    echo "pyFRAMED ended before the replay did; see $dir/python.log" >&2; PY_PID=""; (( code )) || code=3
  fi
  stop_python
  if (( code == 0 )) && ! grep -q "CountingDispatcher summary" "$dir/java.log"; then
    echo "no sink summary in $dir/java.log" >&2; code=4
  fi
  if (( code == 0 )) && ! compgen -G "$dir/capture/*_classifications.jsonl" >/dev/null; then
    echo "no classifications captured in $dir/capture" >&2; code=4
  fi
  return "$code"
}

# One single-process control; exit 3 from kink_baseline.py means it did not cover the recording.
run_ctrl() {
  local rec="$1" speed="$2" dispatch="$3" dir="$4" record span timeout_s k code=0
  k="$(repeat_of "$speed")"
  record="$(control_input "$rec" "$k")" || return 5
  mkdir -p "$dir"
  render_python_services "$dir/services_python.json"
  span="$(record_span "$record")"
  timeout_s=$(awk -v s="$speed" -v t="$span" 'BEGIN { printf "%d", 900 + t / s }')
  jq -n --arg record "$record" --argjson speed "$speed" --arg model "$MODEL" --arg dispatch "$dispatch" \
     --argjson span "$span" --argjson repeat "$k" --arg source "$(recording_path "$rec")" \
     --arg sha "$(sha256sum "$record" | cut -d' ' -f1)" \
     '{kind: "ctrl", record: $record, recordSha256: $sha, source: $source, speed: $speed,
       repeat: $repeat, model: $model, dispatch: $dispatch, recordingSeconds: $span}' > "$dir/run.json"
  ( cd "$PYFRAMED_HOME" && timeout --kill-after=30 "$timeout_s" "$PYTHON" "$HERE/kink_baseline.py" \
      --services "$dir/services_python.json" --record "$record" --speed "$speed" \
      --dispatch "$dispatch" --out "$dir/baseline.jsonl" ) > "$dir/baseline.log" 2>&1 || code=$?
  return "$code"
}

# run_unit <kind> <rec> <speed> <dispatch> <rep>: 0 ok, 10 skipped (complete), else failure.
run_unit() {
  local kind="$1" rec="$2" speed="$3" dispatch="$4" rep="$5" tok run dir code=0
  tok="$(speed_token "$speed")"
  if [[ "$kind" == dist ]]; then run="${rec}_x${tok}_r${rep}"
  else run="${rec}_x${tok}_$([[ "$dispatch" == SEQUENTIAL ]] && echo seq || echo per)_r${rep}"; fi
  dir="$STUDY/$kind/$run"
  UNIT_NAME="$kind $run"
  if unit_complete "$kind" "$run" "$dir"; then echo "--- skip ${LABEL} $kind $run (complete)"; return 10; fi
  rm -rf "$dir"
  echo "==> $(date +%T) ${LABEL} $kind $run"
  rl_host_before
  if [[ "$kind" == dist ]]; then run_dist "$rec" "$speed" "$dir" || code=$?
  else run_ctrl "$rec" "$speed" "$dispatch" "$dir" || code=$?; fi
  local after; after="$(rl_host_after)"
  echo "$LABEL,$kind,$rec,$speed,$dispatch,$rep,$run,$RL_START,${after%%,*},$code,$RL_LOAD,$RL_TEMP0,$(cut -d, -f2 <<< "$after"),$RL_THR0,$(cut -d, -f3 <<< "$after")" >> "$RUNS"
  (( code == 0 )) || echo "!! $kind $run exited with $code; outputs kept in $dir" >&2
  return "$code"
}

# --- main loop ---------------------------------------------------------------------------------
total=0
for e in "${UNIT_LINES[@]}"; do [[ "$e" == pair* ]] && total=$((total + 2)) || total=$((total + 1)); done
rl_progress_init "$total" "$OUT_ROOT/${LABEL}_progress.txt"
declare -A seen=()
for i in "${!UNIT_LINES[@]}"; do
  read -r kind rec speed dispatch <<< "${UNIT_LINES[$i]}"
  if [[ "$kind" == pair ]]; then
    kinds=(dist ctrl); (( i % 2 == 1 )) && kinds=(ctrl dist)
  else
    kinds=("$kind")
  fi
  for k in "${kinds[@]}"; do
    key="$k $rec $speed"; [[ "$k" != ctrl ]] || key+=" $dispatch"
    rep="${seen[$key]:-0}"; seen[$key]=$((rep + 1))
    rl_unit_start; code=0
    run_unit "$k" "$rec" "$speed" "$dispatch" "$rep" || code=$?
    case "$code" in 0) outcome=ran ;; 10) outcome=skipped ;; *) outcome=failed ;; esac
    rl_progress_step "$outcome" "$UNIT_NAME"
  done
done

echo "finished:    $(date -u +%FT%TZ)" >> "$MANIFEST"
echo "Done: $total units, skipped (complete): $RL_SKIPPED, failures: $RL_FAILED. Runs: $RUNS"
if (( RL_FAILED > 0 )); then echo "$RL_FAILED unit(s) failed; see $RUNS" >&2; exit 1; fi
