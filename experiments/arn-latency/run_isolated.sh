#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Isolates this machine and runs the ARN latency experiment (run_replays.sh) in a detached
# systemd unit, following the "Controlled environment" section of REPRODUCE.md. Works on the AMD
# workstation and on the Raspberry Pi 5 alike.
#
# Usage (from a text console, e.g. Ctrl+Alt+F3, or over ssh, as the normal user; sudo is asked
# for once):
#   experiments/arn-latency/run_isolated.sh <REPLAY_ROOT> <OUT_ROOT> [LIST]
#                     checks, build, isolate, start (or resume) the runs
#   experiments/arn-latency/run_isolated.sh --status [OUT_ROOT]
#                     unit state and progress (OUT_ROOT defaults to the one last started)
#   experiments/arn-latency/run_isolated.sh --restore
#                     stop the runs; restore governor, timers and the default boot target
#
#   REPLAY_ROOT  unpacked dataset (doi:10.18154/RWTH-2026-06634)
#   OUT_ROOT     results; use a new one per code version (run_replays.sh refuses to resume an
#                OUT_ROOT started at another commit)
#   LIST         recording list [experiments/arn-latency/full_recordings.txt: 164 entries x 2
#                passes = 328 runs, ~57 h]
#
# Environment (defaults in brackets):
#   LABEL        platform label [PI on aarch64, else AMD]
#   GOVERNOR     cpufreq governor during the runs [performance]
#   PASSES, EXCLUDE, JAVA_OPTS, JAVA, JAVA_MAJOR, PORT   passed on to run_replays.sh
#   SKIP_BUILD   1 skips the build (only if the jar was built from the current commit)
#   DRY_RUN      1 runs the checks and the build, then stops before isolating
#
# Re-running with the same arguments resumes: complete runs are skipped (see run_replays.sh).
#
# Isolation (runtime only; --restore or a reboot undoes it): multi-user.target (no desktop), a
# fixed governor, apt/man-db timers stopped, sleep, idle and lid switch inhibited. No CPU pinning,
# boost left at its default. Isolating stops the graphical session and every program in it.
set -euo pipefail
trap 'echo "run_isolated.sh: aborted (exit $?) at line $LINENO: $BASH_COMMAND" >&2' ERR

SELF="$(readlink -f "$0")"
REPO="$(cd "$(dirname "$SELF")/../.." && pwd)"
cd "$REPO"
UNIT=framed-arn
INHIBIT_UNIT=framed-arn-inhibit
STATE_DIR="$HOME/.cache/framed-arn-isolation"
STATE="$STATE_DIR/state"
TIMERS="apt-daily.timer apt-daily-upgrade.timer man-db.timer unattended-upgrades.service"
GOVERNOR="${GOVERNOR:-performance}"
if [[ -z "${LABEL:-}" ]]; then
  [[ "$(uname -m)" == aarch64 ]] && LABEL=PI || LABEL=AMD
fi
state_get() { [[ -f "$STATE" ]] && sed -n "s/^$1=//p" "$STATE" | head -n1 || true; }

# --- status -------------------------------------------------------------------------------------
if [[ "${1:-}" == --status ]]; then
  out="${2:-$(state_get out_root)}"
  label="$(state_get label)"; label="${label:-$LABEL}"
  systemctl --no-pager status "$UNIT" 2>/dev/null | head -n 4 || echo "unit $UNIT not present"
  if [[ -n "$out" && -f "$out/${label}_progress.txt" ]]; then
    echo "--- $out"; cat "$out/${label}_progress.txt"
  else
    echo "--- ${out:-<no OUT_ROOT known>}: no progress file (label $label)"
  fi
  exit 0
fi

# --- restore ------------------------------------------------------------------------------------
if [[ "${1:-}" == --restore ]]; then
  sudo -v
  sudo systemctl stop "$UNIT" "$INHIBIT_UNIT" 2>/dev/null || true
  sudo systemctl reset-failed "$UNIT" "$INHIBIT_UNIT" 2>/dev/null || true
  gov="$(state_get governor)"
  [[ -n "$gov" ]] && echo "$gov" | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null
  # shellcheck disable=SC2086
  sudo systemctl start $TIMERS 2>/dev/null || true
  target="$(systemctl get-default)"
  rm -f "$STATE"
  echo "restored; returning to $target"
  sudo systemctl isolate "$target"
  exit 0
fi

# --- arguments ----------------------------------------------------------------------------------
usage="usage: $0 <REPLAY_ROOT> <OUT_ROOT> [LIST] | --status [OUT_ROOT] | --restore"
[[ "${1:-}" != -* ]] || { echo "unknown option: $1" >&2; echo "$usage" >&2; exit 2; }
REPLAY_ROOT="${1:?$usage}"
OUT_ROOT="${2:?$usage}"
LIST="${3:-$REPO/experiments/arn-latency/full_recordings.txt}"
[[ -d "$REPLAY_ROOT" ]] || { echo "dataset not found: $REPLAY_ROOT" >&2; exit 1; }
[[ -f "$LIST" ]] || { echo "list not found: $LIST" >&2; exit 1; }
REPLAY_ROOT="$(readlink -f "$REPLAY_ROOT")"
OUT_ROOT="$(readlink -m "$OUT_ROOT")"
LIST="$(readlink -f "$LIST")"

# --- checks, while nothing is isolated yet ------------------------------------------------------
if systemctl is-active --quiet "$UNIT"; then
  echo "$UNIT is already running; see $0 --status" >&2; exit 1
fi
[[ -z "$(git status --porcelain --untracked-files=no)" ]] \
  || { echo "tracked files are modified; commit or stash first (the manifest records the state)" >&2
       git status --short --untracked-files=no >&2; exit 1; }
for c in jq git mvn timeout sha256sum systemd-run; do
  command -v "$c" >/dev/null || { echo "$c is required" >&2; exit 1; }
done
[[ -e /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]] \
  || echo "WARNING: no cpufreq interface; the governor is not set" >&2
if ls /sys/class/power_supply/*/online >/dev/null 2>&1 && ! grep -qx 1 /sys/class/power_supply/*/online; then
  echo "WARNING: not on AC power" >&2
fi
mkdir -p "$OUT_ROOT"
free_gb=$(df -BG --output=avail "$OUT_ROOT" | tail -n1 | tr -dc 0-9)
(( free_gb >= 10 )) || { echo "only ${free_gb} GB free at $OUT_ROOT; a full run writes ~5 GB" >&2; exit 1; }
if [[ -f "$OUT_ROOT/${LABEL}_manifest.txt" ]]; then
  echo "OUT_ROOT has a manifest: the runs resume (same commit required)"
fi

# --- build (unmeasured), with the JDK run_replays.sh will use ------------------------------------
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
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  [[ -x "$JDK_HOME/bin/javac" ]] || { echo "$JDK_HOME has no javac; set JAVA to a full JDK $JAVA_MAJOR" >&2; exit 1; }
  echo "building with $JDK_HOME ..."
  JAVA_HOME="$JDK_HOME" mvn -q -DskipTests clean package || { echo "build failed" >&2; exit 1; }
fi
ls "$REPO"/framed-app/target/framed-app-*-fat.jar >/dev/null 2>&1 \
  || { echo "no fat jar in framed-app/target; build without SKIP_BUILD" >&2; exit 1; }

entries=$(grep -vc '^[[:space:]]*\(#\|$\)' "$LIST" || true)
echo
echo "ARN latency runs (label $LABEL)"
echo "  commit:   $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))"
echo "  dataset:  $REPLAY_ROOT"
echo "  output:   $OUT_ROOT"
echo "  list:     $LIST ($entries entries x passes '${PASSES:-atomic non_atomic}')"
echo "  java:     $JAVA_BIN"
echo "  governor: $GOVERNOR"
if [[ "${DRY_RUN:-0}" == 1 ]]; then echo "DRY_RUN: checks passed, nothing isolated or started"; exit 0; fi
echo "Isolating stops the desktop and every program running in it."
read -r -p "Continue? [y/N] " answer
[[ "$answer" == [yY] ]] || { echo "aborted"; exit 1; }

# --- isolate --------------------------------------------------------------------------------------
sudo -v
mkdir -p "$STATE_DIR"
old_gov="$(state_get governor)"
[[ -n "$old_gov" ]] || old_gov="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || true)"
printf 'governor=%s\nout_root=%s\nlabel=%s\n' "$old_gov" "$OUT_ROOT" "$LABEL" > "$STATE"
sudo systemctl isolate multi-user.target
ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null 2>&1 \
  && echo "$GOVERNOR" | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null
# shellcheck disable=SC2086
sudo systemctl stop $TIMERS 2>/dev/null || true
sudo systemctl reset-failed "$INHIBIT_UNIT" 2>/dev/null || true
systemctl is-active --quiet "$INHIBIT_UNIT" \
  || sudo systemd-run --unit="$INHIBIT_UNIT" --quiet \
       systemd-inhibit --what=sleep:idle:handle-lid-switch --who=FRAMED --why="ARN latency runs" sleep infinity

# --- start ----------------------------------------------------------------------------------------
passthrough=()
for v in PASSES EXCLUDE JAVA_OPTS PORT; do
  [[ -n "${!v+x}" ]] && passthrough+=(--setenv="$v=${!v}")
done
sudo systemctl reset-failed "$UNIT" 2>/dev/null || true
sudo systemd-run --unit="$UNIT" -p User="$USER" -p WorkingDirectory="$REPO" \
  --setenv=PATH="$PATH" --setenv=HOME="$HOME" --setenv=LABEL="$LABEL" \
  --setenv=SKIP_BUILD=1 --setenv=JAVA="$JAVA_BIN" --setenv=JAVA_MAJOR="$JAVA_MAJOR" \
  "${passthrough[@]}" \
  "$REPO/experiments/arn-latency/run_replays.sh" "$REPLAY_ROOT" "$OUT_ROOT" "$LIST"

cat <<EOF

Started: unit $UNIT.
  progress:   $0 --status
  live log:   journalctl -u $UNIT -f | grep -E '^\[|!!'
  stop:       sudo systemctl stop $UNIT       (resume: run the same command again)
  afterwards: $0 --restore                   (or reboot)
EOF
