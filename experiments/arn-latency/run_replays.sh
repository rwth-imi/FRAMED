#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Replays recordings of the ventilation/pulse-oximetry dataset (doi:10.18154/RWTH-2026-06634)
# through the CDSS Acyclic Reactor Network in real time, with atomic and non-atomic reactors,
# and records every reactor evaluation off the bus (FiringRecorder, -Dframed.arn.metrics.*).
#
# Usage:
#   experiments/arn-latency/run_replays.sh <REPLAY_ROOT> <OUT_ROOT> [LIST]
#
#   REPLAY_ROOT  unpacked dataset (searched recursively for <recording>.jsonl)
#   OUT_ROOT     results; per pass <OUT_ROOT>/<LABEL>_<ATOMIC|NON_ATOMIC>/ holds
#                  *_replay.jsonl        bus output (inputs + reactor outputs)
#                  metrics/<run>_*.csv   firings, consumed inputs, per-reactor summary, the
#                                        replay's actual send instant per event (_emits.csv) and
#                                        1 Hz CPU samples of the JVM and the host (_cpu.csv)
#                  gc/<run>.log          JVM GC log
#                  config/<run>_*.json   the exact services.json and communication.json of the run
#                plus <OUT_ROOT>/<LABEL>_runs.csv (one row per run), <LABEL>_manifest.txt and
#                <LABEL>_progress.txt (runs done / total, ETA; rewritten after every run, so
#                `cat` or `watch cat` shows how far a detached run is)
#   LIST         file with one recording name per line (without .jsonl; '#' comments allowed).
#                A name listed k times is replayed k times (repetitions r0, r1, ...), which gives
#                control-vs-control pairs. Default: every recording under REPLAY_ROOT except
#                EXCLUDE.
#
# Environment:
#   LABEL      platform label of the output folders (default AMD; use PI on the Raspberry Pi)
#   PASSES     "atomic non_atomic" (default) or one of them
#   EXCLUDE    recordings skipped when no LIST is given (default: p04-PC2-knick-0 and -1, whose
#              ventilator data are partly or entirely missing after a recording dropout; see the
#              dataset README)
#   JAVA_OPTS  JVM options (default: fixed 1 GiB heap, G1)
#   JAVA_MAJOR Java major version that builds and runs the experiment (default 21); the script
#              aborts if none is found, so a run never silently uses another JVM
#   JAVA       path to that java binary (default: $JAVA_HOME/bin/java, /usr/lib/jvm/*-<major>-*,
#              then java on PATH, whichever has the required major version)
#   PORT       TCP port of the local SocketEventBus (default 4999)
#   SKIP_BUILD 1 skips the one-off `mvn package`
#
# The CDSS is taken from config/services_replay.json, so its parameters have one source. Only
# the replay device, the JSON-Lines sink and the Oxylog channel ids are adapted: the dataset keeps
# the long Medibus labels (e.g. "End-tidal CO2 concentration, etCO2"), while the current parser
# and config use short ids (e.g. "etCO2"). The repository's config/ is never modified; each run
# gets its own working directory.
#
# Resume: re-running the same command with the same OUT_ROOT skips every run that is complete
# (last attempt exited 0 in <LABEL>_runs.csv and all its output files exist) and repeats the
# others, after deleting their partial outputs. Resuming is refused if the git commit or the
# number of modified tracked files differs from the original start (RESUME_ANYWAY=1 overrides);
# each resume is appended to the manifest. To start over, use a new OUT_ROOT.
#
# Each replay runs in real time (~10-11 min). For each list entry, the two passes alternate in
# order (atomic first on even entries, non-atomic first on odd ones), so slow drifts of the
# machine do not favour one mode.
set -euo pipefail
# never end silently: name the command that aborted the script
trap 'echo "run_replays.sh: aborted (exit $?) at line $LINENO: $BASH_COMMAND" >&2' ERR

[[ -d "${1:?usage: $0 <REPLAY_ROOT> <OUT_ROOT> [LIST]}" ]] || { echo "Not a directory: $1 (from $PWD)" >&2; exit 1; }
REPLAY_ROOT="$(readlink -f "$1")"
OUT_ROOT="${2:?usage: $0 <REPLAY_ROOT> <OUT_ROOT> [LIST]}"
LIST="${3:-}"
[[ -z "$LIST" || -f "$LIST" ]] || { echo "List not found: $LIST (from $PWD)" >&2; exit 1; }
LABEL="${LABEL:-AMD}"
PASSES="${PASSES:-atomic non_atomic}"
EXCLUDE="${EXCLUDE-p04-PC2-knick-0 p04-PC2-knick-1}"
JAVA_OPTS="${JAVA_OPTS:--Xms1g -Xmx1g -XX:+UseG1GC}"
PORT="${PORT:-4999}"
JAVA_MAJOR="${JAVA_MAJOR:-21}"

java_major() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }
resolve_java() {
  local c
  if [[ -n "${JAVA:-}" ]]; then
    [[ -x "$JAVA" && "$(java_major "$JAVA")" == "$JAVA_MAJOR" ]] && { readlink -f "$JAVA"; return 0; }
    echo "JAVA=$JAVA is not a Java $JAVA_MAJOR runtime" >&2; return 1
  fi
  for c in ${JAVA_HOME:+"$JAVA_HOME/bin/java"} /usr/lib/jvm/temurin-"$JAVA_MAJOR"-*/bin/java \
           /usr/lib/jvm/*-"$JAVA_MAJOR"-*/bin/java "$(command -v java || true)"; do
    [[ -x "$c" && "$(java_major "$c")" == "$JAVA_MAJOR" ]] && { readlink -f "$c"; return 0; }
  done
  echo "No Java $JAVA_MAJOR runtime found; set JAVA=/path/to/bin/java" >&2; return 1
}
JAVA_BIN="$(resolve_java)" || exit 1
JDK_HOME="$(dirname "$(dirname "$JAVA_BIN")")"

REPO="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
TEMPLATE="$REPO/config/services_replay.json"
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
[[ -d "$REPLAY_ROOT" ]] || { echo "Not a directory: $REPLAY_ROOT" >&2; exit 1; }

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  [[ -x "$JDK_HOME/bin/javac" ]] || { echo "$JDK_HOME has no javac; set JAVA to a full JDK $JAVA_MAJOR or use SKIP_BUILD=1" >&2; exit 1; }
  command -v mvn >/dev/null || { echo "mvn not found on PATH ($PATH); under systemd-run pass --setenv=PATH=\$PATH, or build first and use SKIP_BUILD=1" >&2; exit 1; }
  echo "building with $JDK_HOME ..."
  (cd "$REPO" && JAVA_HOME="$JDK_HOME" mvn -q -DskipTests package) || { echo "build failed (see the Maven output above)" >&2; exit 1; }
fi
JAR="$(ls "$REPO"/framed-app/target/framed-app-*-fat.jar | head -n1)"

mkdir -p "$OUT_ROOT"
OUT_ROOT="$(readlink -f "$OUT_ROOT")"

# --- recordings ----------------------------------------------------------------------
ENTRIES=()
if [[ -n "$LIST" ]]; then
  while read -r line; do
    line="${line%%#*}"; line="${line//[[:space:]]/}"
    [[ -n "$line" ]] && ENTRIES+=("$line")
  done < "$LIST"
else
  while read -r f; do
    name="$(basename "$f" .jsonl)"
    [[ " $EXCLUDE " == *" $name "* ]] || ENTRIES+=("$name")
  done < <(find "$REPLAY_ROOT" -type f -name '*.jsonl' | sort)
fi
(( ${#ENTRIES[@]} > 0 )) || { echo "No recordings selected" >&2; exit 1; }

recording_path() {
  local p
  p="$(find "$REPLAY_ROOT" -type f -name "$1.jsonl" | head -n1)"
  [[ -n "$p" ]] || { echo "Recording not found: $1" >&2; return 1; }
  echo "$p"
}
for e in "${ENTRIES[@]}"; do recording_path "$e" >/dev/null; done

# --- provenance ----------------------------------------------------------------------
pi_temp()      { command -v vcgencmd >/dev/null && vcgencmd measure_temp | tr -dc '0-9.' || echo ""; }
pi_throttled() { command -v vcgencmd >/dev/null && vcgencmd get_throttled | cut -d= -f2 || echo ""; }
governor()     { cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo ""; }

MANIFEST="$OUT_ROOT/${LABEL}_manifest.txt"
RUNS="$OUT_ROOT/${LABEL}_runs.csv"
COMMIT="$(git -C "$REPO" rev-parse HEAD)"
DIRTY="$(git -C "$REPO" status --porcelain --untracked-files=no | wc -l)"
if [[ -f "$MANIFEST" ]]; then
  # resume: the code must be the one the run started with
  orig_commit="$(sed -n 's/^git commit:  //p' "$MANIFEST" | head -n1)"
  orig_dirty="$(sed -n 's/^git dirty:   \([0-9]*\).*/\1/p' "$MANIFEST" | head -n1)"
  if [[ "$orig_commit" != "$COMMIT" || "$orig_dirty" != "$DIRTY" ]] && [[ "${RESUME_ANYWAY:-0}" != 1 ]]; then
    echo "Refusing to resume: started at $orig_commit ($orig_dirty modified), now $COMMIT ($DIRTY modified)." >&2
    echo "Use a new OUT_ROOT, or RESUME_ANYWAY=1." >&2
    exit 1
  fi
  {
    echo "resumed:     $(date -u +%FT%TZ)  commit $COMMIT, $DIRTY modified"
    echo "  jar:       $(sha256sum "$JAR" | cut -d' ' -f1)"
    echo "  java:      $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -n1))"
  } >> "$MANIFEST"
else
{
  echo "started:     $(date -u +%FT%TZ)"
  echo "git commit:  $COMMIT"
  echo "git branch:  $(git -C "$REPO" rev-parse --abbrev-ref HEAD)"
  echo "git dirty:   $DIRTY tracked files modified"
  echo "template:    $TEMPLATE ($(sha256sum "$TEMPLATE" | cut -d' ' -f1))"
  echo "jar:         $JAR ($(sha256sum "$JAR" | cut -d' ' -f1))"
  echo "java:        $("$JAVA_BIN" -version 2>&1 | head -n1)"
  echo "java opts:   $JAVA_OPTS"
  echo "os:          $(uname -srm)"
  echo "cpu:         $(lscpu 2>/dev/null | sed -n 's/^Model name:[[:space:]]*//p' | head -n1)"
  echo "cpus:        $(nproc)"
  echo "memory:      $(free -h 2>/dev/null | awk '/^Mem:/{print $2}')"
  echo "governor:    $(governor)"
  echo "cpu boost:   $(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || echo n/a)"
  echo "cpu set:     $(taskset -pc $$ 2>/dev/null | sed 's/.*: //')"
  echo "java binary: $JAVA_BIN"
  echo "systemd:     $(systemctl get-default 2>/dev/null) (active: $(systemctl is-active graphical.target 2>/dev/null))"
  echo "replay root: $REPLAY_ROOT"
  echo "passes:      $PASSES"
  echo "list:        ${LIST:-<all minus $EXCLUDE>}"
  echo "inputs (sha256):"
  printf '%s\n' "${ENTRIES[@]}" | sort -u | while read -r e; do
    p="$(recording_path "$e")"; echo "  $(sha256sum "$p" | cut -d' ' -f1)  ${p#"$REPLAY_ROOT"/}"
  done
} > "$MANIFEST"
fi
[[ -f "$RUNS" ]] || echo "label,mode,recording,rep,run,start_utc,end_utc,exit,load1_before,temp_before,temp_after,throttled_before,throttled_after" > "$RUNS"

# --- resume ---------------------------------------------------------------------------
# A run is complete when its last attempt exited 0 and every output file exists.
run_complete() {
  local mode="$1" run="$2" pass_dir="$3" code f
  code="$(awk -F, -v l="$LABEL" -v m="$mode" -v r="$run" '$1==l && $2==m && $5==r {e=$8} END {print e}' "$RUNS")"
  [[ "$code" == 0 ]] || return 1
  for f in firings inputs summary emits cpu; do [[ -s "$pass_dir/metrics/${run}_$f.csv" ]] || return 1; done
  for f in services communication; do [[ -s "$pass_dir/config/${run}_$f.json" ]] || return 1; done
  [[ -s "$pass_dir/gc/${run}.log" ]] || return 1
  [[ -n "$(replay_outputs "$pass_dir" "$run")" ]]
}
# The JSON-Lines sink prefixes its file with a millisecond timestamp.
replay_outputs() {
  local f
  for f in "$1"/*_"$2"_replay.jsonl; do
    [[ -s "$f" && "$(basename "$f")" =~ ^[0-9]+_"$2"_replay\.jsonl$ ]] && echo "$f"
  done
}
# Removes what an incomplete attempt left behind, so a repetition starts clean.
clean_partial() {
  local pass_dir="$1" run="$2" f
  rm -f "$pass_dir/metrics/${run}"_{firings,inputs,summary,emits,cpu}.csv "$pass_dir/gc/${run}.log" \
        "$pass_dir/config/${run}"_{services,communication}.json
  while read -r f; do [[ -n "$f" ]] && rm -f "$f"; done < <(replay_outputs "$pass_dir" "$run")
}

# --- CPU sampling ---------------------------------------------------------------------
# Samples, once per second until the JVM exits, the cumulative CPU time of the JVM (all threads:
# reactors, event bus, replay, sink, GC, JIT) and of the whole host, from /proc. Columns:
#   wall_ns            sample instant (epoch ns, the time base of the metrics files)
#   proc_utime/stime   JVM user/system CPU time, clock ticks (clk_tck per second)
#   proc_threads       JVM thread count; proc_rss_kb resident set size
#   host_busy/total    host CPU ticks summed over all CPUs (busy = total - idle - iowait)
# Reading /proc costs a few microseconds per sample; the sleep is the only process forked.
CLK_TCK="$(getconf CLK_TCK)"
NCPU="$(nproc)"
PAGE_KB=$(( $(getconf PAGESIZE) / 1024 ))
cpu_sampler() {
  local parent="$1" out="$2" pid="" stat f host u n sy id io irq sirq st rest total
  for _ in $(seq 100); do
    pid="$(pgrep -P "$parent" -x java | head -n1 || true)"; [[ -n "$pid" ]] && break; sleep 0.05
  done
  [[ -n "$pid" ]] || { echo "cpu_sampler: no java child of $parent" >&2; return 0; }
  echo "wall_ns,proc_utime,proc_stime,proc_threads,proc_rss_kb,host_busy,host_total,clk_tck,ncpu" > "$out"
  while read -r stat 2>/dev/null < "/proc/$pid/stat"; do
    read -r -a f <<< "${stat##*) }"            # f[0] = field 3 (state) of proc(5)
    read -r host u n sy id io irq sirq st rest < /proc/stat
    total=$(( u + n + sy + id + io + irq + sirq + st ))
    echo "${EPOCHREALTIME//[!0-9]/}000,${f[11]},${f[12]},${f[17]},$(( f[21] * PAGE_KB )),$(( total - id - io )),$total,$CLK_TCK,$NCPU" >> "$out"
    sleep 1
  done
}

# --- one replay ----------------------------------------------------------------------
run_one() {
  local mode="$1" rec="$2" rep="$3"
  local atomic; [[ "$mode" == atomic ]] && atomic=true || atomic=false
  local pass_dir="$OUT_ROOT/${LABEL}_${mode^^}"
  local run="${rec}_r${rep}"
  local work="$OUT_ROOT/.work/${LABEL}_${mode}_${run}"
  if [[ -f "$RUNS" ]] && run_complete "$mode" "$run" "$pass_dir"; then
    echo "--- skip ${LABEL} ${mode} ${run} (complete)"; skipped=$((skipped + 1)); return 0
  fi
  clean_partial "$pass_dir" "$run"
  rm -rf "$work"
  mkdir -p "$pass_dir/metrics" "$pass_dir/gc" "$pass_dir/config" "$work/config"

  jq --arg file "$(recording_path "$rec")" --arg outDir "$pass_dir/" --arg outFile "${run}_replay.jsonl" \
     --argjson atomic "$atomic" '
    def long_ids:
      gsub("Measurement\\.Oxylog-3000-Plus-00\\.etCO2\\.parsed";
           "Measurement.Oxylog-3000-Plus-00.End-tidal CO2 concentration, etCO2.parsed")
      | gsub("Measurement\\.Oxylog-3000-Plus-00\\.FiO2\\.parsed";
           "Measurement.Oxylog-3000-Plus-00.Inspiratory oxygen fraction, FiO2.parsed")
      | gsub("RealTime\\.Oxylog-3000-Plus-00\\.CO2_mmHg\\.parsed";
           "RealTime.Oxylog-3000-Plus-00.CO2 concentration, CO2 (mmHg).parsed")
      | gsub("Settings\\.Oxylog-3000-Plus-00\\.RR\\.parsed";
           "Settings.Oxylog-3000-Plus-00.Respiratory rate, RR.parsed");
    (.Devices |= map(select(.id == "Replay") | .filePath = $file | .speed = 1.0 | .repeat = 1
                     | .exitOnCompletion = true))
    | (.Dispatchers |= map(select(.id == "Json-Lines") | .path = $outDir | .fileName = $outFile))
    | (.Reactors |= map(.atomic = $atomic))
    | (.Reactors |= walk(if type == "string" then long_ids
                         elif type == "object" then with_entries(.key |= long_ids)
                         else . end))
    ' "$TEMPLATE" > "$work/config/services.json"
  jq --argjson port "$PORT" '.port = $port' "$REPO/config/communication.json" > "$work/config/communication.json"
  # the configuration this run actually used, kept with its data
  cp "$work/config/services.json" "$pass_dir/config/${run}_services.json"
  cp "$work/config/communication.json" "$pass_dir/config/${run}_communication.json"

  local load temp0 thr0 start code=0
  load="$(cut -d' ' -f1 /proc/loadavg)"; temp0="$(pi_temp)"; thr0="$(pi_throttled)"
  start="$(date -u +%FT%TZ)"
  echo "==> $(date +%T) ${LABEL} ${mode} ${run}"
  # shellcheck disable=SC2086
  (cd "$work" && exec timeout 40m "$JAVA_BIN" $JAVA_OPTS \
      "-Xlog:gc*:file=$pass_dir/gc/${run}.log:time,uptime,level,tags" \
      "-Dframed.arn.metrics.dir=$pass_dir/metrics" "-Dframed.arn.metrics.run=${run}" \
      "-Dframed.replay.emitLog=$pass_dir/metrics/${run}_emits.csv" \
      -jar "$JAR" > "$work/framed.log" 2>&1) &
  local tpid=$! spid
  cpu_sampler "$tpid" "$pass_dir/metrics/${run}_cpu.csv" &
  spid=$!
  wait "$tpid" || code=$?
  wait "$spid" || true
  echo "$LABEL,$mode,$rec,$rep,$run,$start,$(date -u +%FT%TZ),$code,$load,$temp0,$(pi_temp),$thr0,$(pi_throttled)" >> "$RUNS"
  if (( code != 0 )); then
    echo "!! ${mode} ${run} exited with ${code}; log kept in $work/framed.log" >&2
  else
    rm -rf "$work"
  fi
  return "$code"
}

declare -A seen=()
failures=0
skipped=0
read -r -a pass_list <<< "$PASSES"
TOTAL=$(( ${#ENTRIES[@]} * ${#pass_list[@]} ))
PROGRESS="$OUT_ROOT/${LABEL}_progress.txt"
done_n=0; ran_n=0; ran_secs=0; started_at="$(date -u +%FT%TZ)"

hms() { printf '%dh%02dm' $(( $1 / 3600 )) $(( $1 % 3600 / 60 )); }
# One line after every run; the ETA averages the runs replayed in this invocation (skipped
# runs take no time and are left out of the average).
progress() {
  local line pct eta="?" finish="?"
  pct=$(( 100 * done_n / TOTAL ))
  if (( ran_n > 0 )); then
    local left=$(( (TOTAL - done_n) * ran_secs / ran_n ))
    eta="$(hms "$left")"; finish="$(date -d "+${left} sec" '+%a %H:%M')"
  fi
  line="[$done_n/$TOTAL ${pct}%] ran $ran_n (avg $(( ran_n ? ran_secs / ran_n : 0 ))s), skipped $skipped, failed $failures | remaining $eta, finish ~$finish | last: $1"
  echo "$line"
  printf '%s\nlabel %s, started %s, updated %s\n' "$line" "$LABEL" "$started_at" "$(date -u +%FT%TZ)" \
    > "$PROGRESS.tmp" && mv "$PROGRESS.tmp" "$PROGRESS"
}
for i in "${!ENTRIES[@]}"; do
  rec="${ENTRIES[$i]}"
  rep="${seen[$rec]:-0}"; seen[$rec]=$((rep + 1))
  read -r -a order <<< "$PASSES"
  if (( i % 2 == 1 && ${#order[@]} == 2 )); then order=("${order[1]}" "${order[0]}"); fi
  for mode in "${order[@]}"; do
    case "$mode" in atomic|non_atomic) ;; *) echo "Unknown pass: $mode" >&2; exit 1 ;; esac
    t0=$SECONDS; skipped_before=$skipped
    run_one "$mode" "$rec" "$rep" || failures=$((failures + 1))
    done_n=$((done_n + 1))
    if (( skipped == skipped_before )); then ran_n=$((ran_n + 1)); ran_secs=$((ran_secs + SECONDS - t0)); fi
    progress "$mode ${rec}_r${rep}"
  done
done

echo "finished:    $(date -u +%FT%TZ)" >> "$MANIFEST"
echo "Done: ${#ENTRIES[@]} list entries, skipped (complete): $skipped, failures: $failures. Runs: $RUNS"
if (( failures > 0 )); then echo "$failures run(s) failed; see $RUNS and the logs in $OUT_ROOT/.work/" >&2; exit 1; fi
