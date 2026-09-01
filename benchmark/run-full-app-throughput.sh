#!/usr/bin/env bash
#
# Full-app confirmation for the throughput case study.
#
# MimicThroughputBenchmark measures the pipeline in-process. This script re-measures a few of its
# operating points the way a deployment actually runs them — com.framed.orchestrator.Main,
# config-driven instantiation via Factory, SocketEventBus — so the in-process ceiling can be
# confirmed on the production bus rather than assumed to carry over.
#
# For each speed it writes a bench config, launches the app, and extracts the producer pacing line
# and the CountingDispatcher summary. config/services.json is restored on exit, including on
# failure or Ctrl-C.
#
# Usage:
#   bash benchmark/run-full-app-throughput.sh [speed ...]      # default: 1 200 100000
#
# Environment:
#   MIMIC_RECORD=/path/to/record.hea   record to replay (default: the one in the bench config)
#   MAX_SECONDS=<record seconds>       record window per run (default 60 at 1x, scaled above)
#   SKIP_BUILD=1                       reuse the installed SNAPSHOTs
#
# Output: benchmark/results/full-app-throughput-<timestamp>.log and .csv

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SPEEDS=("$@")
[[ ${#SPEEDS[@]} -eq 0 ]] && SPEEDS=(1 200 100000)

BENCH_CONFIG="config/services_mimic_bench.json"
LIVE_CONFIG="config/services.json"
BACKUP="config/services.json.bench-backup"
RESULTS="benchmark/results"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="$RESULTS/full-app-throughput-$STAMP.log"
CSV="$RESULTS/full-app-throughput-$STAMP.csv"

[[ -f "$BENCH_CONFIG" ]] || { echo "missing $BENCH_CONFIG" >&2; exit 1; }
mkdir -p "$RESULTS"

restore() {
  if [[ -f "$BACKUP" ]]; then
    mv -f "$BACKUP" "$LIVE_CONFIG"
    echo "restored $LIVE_CONFIG"
  fi
}
trap restore EXIT
[[ -f "$LIVE_CONFIG" ]] && cp -p "$LIVE_CONFIG" "$BACKUP"

RECORD="${MIMIC_RECORD:-$(grep -o '"recordPath"[[:space:]]*:[[:space:]]*"[^"]*"' "$BENCH_CONFIG" \
  | sed 's/.*"\([^"]*\)"$/\1/')}"
[[ -f "$RECORD" ]] || { echo "record header not found: $RECORD" >&2; exit 1; }
echo "record: $RECORD" | tee "$LOG"

if [[ -z "${SKIP_BUILD:-}" ]]; then
  echo "building (set SKIP_BUILD=1 to skip)..." | tee -a "$LOG"
  mvn -q -DskipTests install
fi

echo "speed,maxSeconds,producerFrames,producerSamples,producerAchievedFrameHz,framesBehind,keptPace,sinkReceived,sinkDropped,sinkAchievedDpPerSec,latP50Ms,latP95Ms,latP99Ms" > "$CSV"

for speed in "${SPEEDS[@]}"; do
  # A bounded window per run: 60 record-seconds at 1x, and enough record time above that for the
  # run to last a few seconds of wall clock rather than finishing before the JIT warms up.
  if [[ -n "${MAX_SECONDS:-}" ]]; then
    seconds="$MAX_SECONDS"
  else
    seconds=$(awk -v s="$speed" 'BEGIN{v=(s<=1)?60:s*6; if(v>8000)v=8000; printf "%d", v}')
  fi

  echo "=== speed=${speed}x maxSeconds=${seconds} ===" | tee -a "$LOG"
  python3 - "$BENCH_CONFIG" "$LIVE_CONFIG" "$RECORD" "$speed" "$seconds" <<'PY'
import json, sys
src, dst, record, speed, seconds = sys.argv[1:6]
cfg = json.load(open(src))
dev = cfg["Devices"][0]
dev["recordPath"] = record
dev["speed"] = float(speed)
dev["maxSeconds"] = float(seconds)
json.dump(cfg, open(dst, "w"), indent=2)
PY

  set +e
  mvn -pl framed-app exec:java > "$RESULTS/.run.tmp" 2>&1
  set -e
  cat "$RESULTS/.run.tmp" >> "$LOG"

  pacing="$(grep -h "MIMIC replay pacing" "$RESULTS/.run.tmp" | tail -1 || true)"
  sink="$(grep -h "CountingDispatcher summary" "$RESULTS/.run.tmp" | tail -1 || true)"
  echo "  $pacing"
  echo "  $sink"

  python3 - "$speed" "$seconds" "$pacing" "$sink" >> "$CSV" <<'PY'
import re, sys
speed, seconds, pacing, sink = sys.argv[1:5]
def g(pattern, text, default=""):
    m = re.search(pattern, text)
    return m.group(1) if m else default
frames   = g(r"frames=(\d+)", pacing)
samples  = g(r"samples=(\d+)", pacing)
achieved = g(r"achieved=([\d.]+) Hz", pacing)
behind   = g(r"behind=(\d+)", pacing)
kept     = g(r"keptPace=(\w+)", pacing)
recv     = g(r"received=(\d+)", sink)
dropped  = g(r"dropped=(\d+)", sink)
dps      = g(r"achieved=([\d.]+) dp/s", sink)
lat      = g(r"latency\(mean/p50/p95/p99/max\)=[\d.]+/(\d+/\d+/\d+)/", sink)
p50, p95, p99 = (lat.split("/") + ["", "", ""])[:3] if lat else ("", "", "")
print(",".join([speed, seconds, frames, samples, achieved, behind, kept,
                recv, dropped, dps, p50, p95, p99]))
PY
done

rm -f "$RESULTS/.run.tmp"
echo
echo "csv: $CSV"
echo "log: $LOG"
column -s, -t "$CSV"
