#!/usr/bin/env bash
#
# Full-app confirmation run for the MIMIC pacing benchmark.
#
# MimicPacingBenchmark measures the same pipeline in-process on a LocalEventBus. This script runs it
# the way a deployment actually runs — com.framed.orchestrator.Main, config-driven instantiation,
# SocketEventBus — so the in-process numbers can be confirmed rather than assumed.
#
# It swaps config/services.json for config/services_mimic_bench.json (a CountingDispatcher sink plus
# the MIMIC replay device), launches the app, and restores the original config on exit — including
# on failure or Ctrl-C.
#
# Usage:
#   bash benchmark/run-full-app-bench.sh
#
# Environment:
#   MIMIC_RECORD=/path/to/record.hea   override the record path baked into the bench config
#   SKIP_BUILD=1                       skip the `mvn install`, reuse the installed SNAPSHOTs
#
# Output: benchmark/results/full-app-<timestamp>.log, plus the two summary lines echoed at the end.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BENCH_CONFIG="config/services_mimic_bench.json"
LIVE_CONFIG="config/services.json"
BACKUP="config/services.json.bench-backup"
RESULTS="benchmark/results"
LOG="$RESULTS/full-app-$(date +%Y%m%d-%H%M%S).log"

[[ -f "$BENCH_CONFIG" ]] || { echo "missing $BENCH_CONFIG" >&2; exit 1; }
mkdir -p "$RESULTS"

# Restore the original deployment config whatever happens.
restore() {
  if [[ -f "$BACKUP" ]]; then
    mv -f "$BACKUP" "$LIVE_CONFIG"
    echo "restored $LIVE_CONFIG"
  fi
}
trap restore EXIT

if [[ -f "$LIVE_CONFIG" ]]; then
  cp -p "$LIVE_CONFIG" "$BACKUP"
fi

if [[ -n "${MIMIC_RECORD:-}" ]]; then
  [[ -f "$MIMIC_RECORD" ]] || { echo "MIMIC_RECORD not found: $MIMIC_RECORD" >&2; exit 1; }
  # Rewrite only the recordPath value; everything else comes from the bench config verbatim.
  sed -E "s#(\"recordPath\"[[:space:]]*:[[:space:]]*\")[^\"]*(\")#\1${MIMIC_RECORD//#/\\#}\2#" \
    "$BENCH_CONFIG" > "$LIVE_CONFIG"
else
  cp -p "$BENCH_CONFIG" "$LIVE_CONFIG"
fi

RECORD_PATH="$(grep -o '"recordPath"[[:space:]]*:[[:space:]]*"[^"]*"' "$LIVE_CONFIG" | sed 's/.*"\([^"]*\)"$/\1/')"
if [[ ! -f "$RECORD_PATH" ]]; then
  echo "record header not found: $RECORD_PATH" >&2
  echo "set MIMIC_RECORD=/path/to/record.hea, or edit $BENCH_CONFIG" >&2
  exit 1
fi
echo "benchmarking record: $RECORD_PATH"

if [[ -z "${SKIP_BUILD:-}" ]]; then
  echo "building (set SKIP_BUILD=1 to skip)..."
  mvn -q -DskipTests install
fi

# NOT `-am`: that pulls the framed-interop aggregator into the reactor, and exec:java fails there
# for want of a mainClass. framed-app alone is enough — its dependencies are installed SNAPSHOTs.
echo "running com.framed.orchestrator.Main (replay ends by System.exit; the shutdown hook prints"
echo "the CountingDispatcher summary). Logging to $LOG"
set +e
mvn -pl framed-app exec:java 2>&1 | tee "$LOG"
set -e

echo
echo "=== producer pacing ==="
grep -h "MIMIC replay pacing" "$LOG" || echo "(no pacing line — did the replay finish?)"
echo "=== sink ==="
grep -h "CountingDispatcher summary" "$LOG" || echo "(no sink summary — was the shutdown hook reached?)"
echo
echo "full log: $LOG"