#!/usr/bin/env bash
#
# The kink-replay-over-TCP experiment: a Java FRAMED instance replays a recorded ventilation
# session and sinks the classifications a pyFRAMED CDSS computes for it, with the two instances
# joined by SocketEventBus over TCP.
#
#   Java  ─ ReplayProtocol ──publish──►  SocketEventBus :4999 ──TCP──►  pyFRAMED :5999
#                                                                          │
#                                            three kink reactors ◄─────────┘
#                                                                          │
#   Java  ◄ CountingDispatcher + JsonlDispatcher ◄──TCP──  Kink, Kink-Probability
#
# Every artefact of a run lands in one directory under benchmark/results/, including the rendered
# configs, so a run can be inspected — or repeated by hand — long after the fact.
#
# Usage:
#   bash benchmark/kink-tcp/run-kink-tcp-bench.sh                 # real time, with the control
#   SPEED=10 bash benchmark/kink-tcp/run-kink-tcp-bench.sh        # 10x, throughput
#
# Environment:
#   RECORD=/path/to/session.jsonl   recording to replay          (default: the p10 VC1 kink session)
#   SPEED=1.0                       replay speed; 0 = flat out   (default: 1.0)
#   PYFRAMED_HOME=/path/to/repo     the safety-box-python checkout
#   PYTHON=/path/to/python          interpreter with pyframed installed
#   MODEL=config/models/...json     model artefact, relative to PYFRAMED_HOME
#                                   (default: config/models/kink_logreg_heldout_v1.json)
#   JAVA_PORT / PY_PORT             bus ports                    (default: 4999 / 5999)
#   START_DELAY / GRACE             replay pre-roll / trailing grace, seconds (default: 15 / 20)
#   JAVA_TIMEOUT=<seconds>          cap on the Java run; default: pre-roll + span/speed + grace + 180
#   RUN_BASELINE=0                  skip the single-process control
#   BASELINE_DISPATCH=SEQUENTIAL    control's bus dispatch mode (default PER_HANDLER, as
#                                   deployed); SEQUENTIAL removes the cross-channel race
#   SKIP_BUILD=1                    reuse the installed SNAPSHOTs
#
# Output: benchmark/results/kink-tcp-<timestamp>/{report.md,summary.json,java.log,python.log,...}

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/benchmark/kink-tcp"
cd "$ROOT"

RECORD="${RECORD:-/home/nils/Documents/Entwicklung/safety-box/replay/p10/p10-VC1-knick-0.jsonl}"
SPEED="${SPEED:-1.0}"
PYFRAMED_HOME="${PYFRAMED_HOME:-$ROOT/../safety-box-python}"
PYTHON="${PYTHON:-}"
MODEL="${MODEL:-config/models/kink_logreg_heldout_v1.json}"
JAVA_PORT="${JAVA_PORT:-4999}"
PY_PORT="${PY_PORT:-5999}"
START_DELAY="${START_DELAY:-15}"
GRACE="${GRACE:-20}"
RUN_BASELINE="${RUN_BASELINE:-1}"
BASELINE_DISPATCH="${BASELINE_DISPATCH:-PER_HANDLER}"

# --- preflight ---------------------------------------------------------------------------------
[[ -f "$RECORD" ]] || { echo "recording not found: $RECORD (set RECORD=...)" >&2; exit 1; }
[[ -d "$PYFRAMED_HOME" ]] || { echo "pyFRAMED checkout not found: $PYFRAMED_HOME" >&2; exit 1; }
[[ -f "$PYFRAMED_HOME/$MODEL" ]] || { echo "model artefact not found: $PYFRAMED_HOME/$MODEL" >&2; exit 1; }

if [[ -z "$PYTHON" ]]; then
  # Prefer the project's poetry environment; fall back to whatever python3 has pyframed installed.
  if PYTHON_ENV="$(cd "$PYFRAMED_HOME" && poetry env info -p 2>/dev/null)" && [[ -x "$PYTHON_ENV/bin/python" ]]; then
    PYTHON="$PYTHON_ENV/bin/python"
  else
    PYTHON="$(command -v python3)"
  fi
fi
"$PYTHON" -c "import pyframed" 2>/dev/null || {
  echo "pyframed is not importable with $PYTHON — run 'poetry install' in $PYFRAMED_HOME," >&2
  echo "or set PYTHON=/path/to/an/interpreter/that/has/it" >&2
  exit 1
}

for port in "$JAVA_PORT" "$PY_PORT"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    exec 3<&- 3>&-
    echo "port $port is already in use; stop whatever holds it or set JAVA_PORT/PY_PORT" >&2
    exit 1
  fi
done

STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$ROOT/benchmark/results/kink-tcp-$STAMP"
mkdir -p "$RUN_DIR/capture"

# --- render the four configs -------------------------------------------------------------------
render() {
  sed -e "s#@RECORD@#$RECORD#g" -e "s#@SPEED@#$SPEED#g" -e "s#@MODEL@#$MODEL#g" \
      -e "s#@CAPTURE_DIR@#$RUN_DIR/capture/#g" -e "s#@JAVA_PORT@#$JAVA_PORT#g" \
      -e "s#@PY_PORT@#$PY_PORT#g" -e "s#@START_DELAY@#$START_DELAY#g" -e "s#@GRACE@#$GRACE#g" \
      "$1" > "$2"
}
render "$HERE/services_java.json.template"        "$RUN_DIR/services_java.json"
render "$HERE/communication_java.json.template"   "$RUN_DIR/communication_java.json"
render "$HERE/services_python.json.template"      "$RUN_DIR/services_python.json"
render "$HERE/communication_python.json.template" "$RUN_DIR/communication_python.json"

cat > "$RUN_DIR/run.json" <<EOF
{
  "record": "$RECORD",
  "speed": $SPEED,
  "model": "$MODEL",
  "javaPort": $JAVA_PORT,
  "pythonPort": $PY_PORT,
  "startDelaySeconds": $START_DELAY,
  "graceSeconds": $GRACE,
  "baselineDispatch": "$BASELINE_DISPATCH",
  "pyframedHome": "$PYFRAMED_HOME",
  "python": "$PYTHON",
  "started": "$(date -Is)"
}
EOF

# --- swap the deployment config, restore it whatever happens -------------------------------------
LIVE_SERVICES="config/services.json"
LIVE_COMM="config/communication.json"
BACKUP_SERVICES="config/services.json.kink-backup"
BACKUP_COMM="config/communication.json.kink-backup"
PY_PID=""

cleanup() {
  if [[ -n "$PY_PID" ]] && kill -0 "$PY_PID" 2>/dev/null; then
    kill -TERM "$PY_PID" 2>/dev/null || true
    for _ in $(seq 1 50); do kill -0 "$PY_PID" 2>/dev/null || break; sleep 0.1; done
    kill -KILL "$PY_PID" 2>/dev/null || true
  fi
  [[ -f "$BACKUP_SERVICES" ]] && mv -f "$BACKUP_SERVICES" "$LIVE_SERVICES"
  [[ -f "$BACKUP_COMM" ]] && mv -f "$BACKUP_COMM" "$LIVE_COMM"
  return 0
}
trap cleanup EXIT

[[ -f "$LIVE_SERVICES" ]] && cp -p "$LIVE_SERVICES" "$BACKUP_SERVICES"
[[ -f "$LIVE_COMM" ]] && cp -p "$LIVE_COMM" "$BACKUP_COMM"
cp "$RUN_DIR/services_java.json" "$LIVE_SERVICES"
cp "$RUN_DIR/communication_java.json" "$LIVE_COMM"

if [[ -z "${SKIP_BUILD:-}" ]]; then
  echo "building (set SKIP_BUILD=1 to skip)..."
  mvn -q -DskipTests install
fi

# --- run: pyFRAMED first, so its reactors are listening before the replay starts ------------------
echo "starting the pyFRAMED CDSS on :$PY_PORT (log: $RUN_DIR/python.log)"
( cd "$PYFRAMED_HOME" && exec "$PYTHON" -m pyframed \
    --services "$RUN_DIR/services_python.json" \
    --communication "$RUN_DIR/communication_python.json" ) > "$RUN_DIR/python.log" 2>&1 &
PY_PID=$!

for _ in $(seq 1 300); do
  grep -q "Reactor Network instantiated" "$RUN_DIR/python.log" && break
  kill -0 "$PY_PID" 2>/dev/null || { echo "pyFRAMED exited during startup:" >&2; tail -20 "$RUN_DIR/python.log" >&2; exit 1; }
  sleep 0.1
done
grep -q "Reactor Network instantiated" "$RUN_DIR/python.log" || {
  echo "pyFRAMED did not report a deployed reactor network within 30 s" >&2
  tail -20 "$RUN_DIR/python.log" >&2
  exit 1
}

# A reactor that fails to instantiate does not stop the deployment: the Manager logs it and wires
# whatever is left, and the ARN still reports a network — a two-reactor one with no edges. The run
# then completes, produces feature vectors and no verdict at all, and only the empty classification
# counts in the report give it away, ten minutes later. Refuse to start instead.
if grep -q "Failed to instantiate Service" "$RUN_DIR/python.log"; then
  echo "a pyFRAMED service failed to instantiate — the reactor chain is incomplete:" >&2
  grep "Failed to instantiate Service" "$RUN_DIR/python.log" >&2
  exit 1
fi

# The replay ends the JVM itself, but a replay that cannot read its recording only logs and leaves
# the deployment running: without a cap the experiment would hang instead of failing.
if [[ -z "${JAVA_TIMEOUT:-}" ]]; then
  JAVA_TIMEOUT="$("$PYTHON" - "$RECORD" "$SPEED" "$START_DELAY" "$GRACE" <<'EOF'
import json, sys
from datetime import datetime

record, speed, start_delay, grace = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
stamps = []
with open(record) as handle:
    for line in handle:
        line = line.strip()
        if line:
            text = json.loads(line)["timestamp"]
            stamps.append(datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp())
span = (max(stamps) - min(stamps)) if stamps else 0.0
print(int(start_delay + grace + 180 + (span / speed if speed > 0 else 0)))
EOF
)"
fi

echo "starting com.framed.orchestrator.Main on :$JAVA_PORT (log: $RUN_DIR/java.log)"
echo "the replay pre-rolls ${START_DELAY}s, runs at speed $SPEED, then exits after ${GRACE}s of grace"
echo "(giving up after ${JAVA_TIMEOUT}s if it does not)"
set +e
# NOT `-am`: that pulls the framed-interop aggregator into the reactor, where exec:java has no
# mainClass. framed-app alone is enough — its dependencies are installed SNAPSHOTs.
timeout --signal=TERM --kill-after=30 "$JAVA_TIMEOUT" mvn -pl framed-app exec:java 2>&1 | tee "$RUN_DIR/java.log"
JAVA_EXIT=${PIPESTATUS[0]}
set -e
if [[ "$JAVA_EXIT" == "124" ]]; then
  echo "the Java instance did not finish within ${JAVA_TIMEOUT}s and was terminated" >&2
fi
"$PYTHON" - "$RUN_DIR/run.json" "$JAVA_EXIT" <<'EOF'
import json, sys
path, code = sys.argv[1], int(sys.argv[2])
data = json.load(open(path)); data["javaExitCode"] = code
json.dump(data, open(path, "w"), indent=2)
EOF

echo "stopping the pyFRAMED CDSS"
kill -TERM "$PY_PID" 2>/dev/null || true
wait "$PY_PID" 2>/dev/null || true
PY_PID=""

# --- the single-process control ------------------------------------------------------------------
if [[ "$RUN_BASELINE" != "0" ]]; then
  # At the run's own speed: a control replayed faster than the reactor chain processes backlogs its
  # queues, and a backlogged chain publishes several feature vectors under one firing's timestamp —
  # so a flat-out control is neither complete nor aligned with the run it is supposed to control.
  echo "running the single-process control at speed $SPEED (pyFRAMED only, no socket in the path)"
  set +e
  ( cd "$PYFRAMED_HOME" && "$PYTHON" "$HERE/baseline.py" \
      --services "$RUN_DIR/services_python.json" \
      --record "$RECORD" \
      --speed "$SPEED" \
      --dispatch "$BASELINE_DISPATCH" \
      --out "$RUN_DIR/baseline.jsonl" ) 2>&1 | tee -a "$RUN_DIR/baseline.log"
  BASELINE_EXIT=${PIPESTATUS[0]}
  set -e
  [[ "$BASELINE_EXIT" == "0" ]] || echo "control returned $BASELINE_EXIT — see $RUN_DIR/baseline.log" >&2
fi

# --- report ---------------------------------------------------------------------------------------
echo
"$PYTHON" "$HERE/analyse-kink-tcp.py" --run-dir "$RUN_DIR"
STATUS=$?
echo
echo "artefacts: $RUN_DIR"
exit $STATUS
