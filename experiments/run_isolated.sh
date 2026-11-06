#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Isolates this machine and runs the experiments one after another in a detached systemd unit,
# following experiments/arn-latency/REPRODUCE.md and experiments/framed-benchmark/REPRODUCE.md.
#
# Usage (from a text console, e.g. Ctrl+Alt+F3, as the normal user; sudo is asked for once):
#   experiments/run_isolated.sh            checks, build, isolate, start (or resume) the runs
#   experiments/run_isolated.sh --status   unit state and the progress of every step
#   experiments/run_isolated.sh --restore  stop the runs, restore governor, timers and desktop
#
# Steps (STEPS, default "arn kink mimic"), each resuming where it stopped:
#   arn    experiments/arn-latency/run_replays.sh   -> experiments/arn-latency/out/full-<label>
#   kink   experiments/framed-benchmark/run_kink.sh -> experiments/framed-benchmark/out/kink-<label>
#   mimic  experiments/framed-benchmark/run_mimic.sh -> experiments/framed-benchmark/out/mimic-<label>
# A failed step does not stop the following ones; re-running this script resumes all of them.
#
# Environment (defaults in brackets):
#   LABEL          platform label [AMD]
#   STEPS          steps and order ["arn kink mimic"]
#   REPLAY_ROOT    dataset [data/replay/data, else ../FRAMED/data/replay/data]
#   MIMIC_RECORD   master header of MIMIC record 3000125
#                  [../physionet.org/files/mimic3wdb/1.0/30/3000125/3000125.hea]
#   PYFRAMED_HOME  pyFRAMED checkout [../safety-box-python]; PYTHON [its poetry environment]
#   GOVERNOR       cpufreq governor during the runs [performance]
#   SKIP_BUILD     1 skips the build (only if the jar was built from the current commit)
#   DRY_RUN        1 runs the checks and the build, then stops before isolating
#
# Isolation (runtime only; --restore or a reboot undoes it): multi-user.target (no desktop), a
# fixed governor, apt/man-db timers stopped, sleep, idle and lid switch inhibited. No CPU pinning.
# Note: isolating stops the graphical session and every program running in it.
set -euo pipefail
trap 'echo "run_isolated.sh: aborted (exit $?) at line $LINENO: $BASH_COMMAND" >&2' ERR

REPO="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
cd "$REPO"
UNIT=framed-experiments
INHIBIT_UNIT=framed-experiments-inhibit
STATE="$HOME/.cache/framed-isolation-state"
TIMERS="apt-daily.timer apt-daily-upgrade.timer man-db.timer unattended-upgrades.service"

LABEL="${LABEL:-AMD}"
STEPS="${STEPS:-arn kink mimic}"
GOVERNOR="${GOVERNOR:-performance}"
label_lc="${LABEL,,}"
ARN_OUT="experiments/arn-latency/out/full-$label_lc"
KINK_OUT="experiments/framed-benchmark/out/kink-$label_lc"
MIMIC_OUT="experiments/framed-benchmark/out/mimic-$label_lc"

# --- inside the unit: the steps themselves ------------------------------------------------------
if [[ "${1:-}" == --inner ]]; then
  failed=()
  for step in $STEPS; do
    echo "=== $(date -u +%FT%TZ) step $step"
    case "$step" in
      arn)   experiments/arn-latency/run_replays.sh "$REPLAY_ROOT" "$ARN_OUT" experiments/arn-latency/full_recordings.txt || failed+=(arn) ;;
      kink)  experiments/framed-benchmark/run_kink.sh "$REPLAY_ROOT" "$KINK_OUT" || failed+=(kink) ;;
      mimic) experiments/framed-benchmark/run_mimic.sh "$MIMIC_RECORD" "$MIMIC_OUT" || failed+=(mimic) ;;
    esac
  done
  echo "=== $(date -u +%FT%TZ) all steps done; failed: ${failed[*]:-none}"
  exit $(( ${#failed[@]} > 0 ))
fi

# --- status ---------------------------------------------------------------------------------------
if [[ "${1:-}" == --status ]]; then
  systemctl --no-pager status "$UNIT" 2>/dev/null | head -n 4 || echo "unit $UNIT not present"
  for f in "$ARN_OUT" "$KINK_OUT" "$MIMIC_OUT"; do
    p="$f/${LABEL}_progress.txt"
    if [[ -f "$p" ]]; then echo "--- $f"; cat "$p"; else echo "--- $f: not started"; fi
  done
  exit 0
fi

# --- restore --------------------------------------------------------------------------------------
if [[ "${1:-}" == --restore ]]; then
  sudo -v
  sudo systemctl stop "$UNIT" "$INHIBIT_UNIT" 2>/dev/null || true
  sudo systemctl reset-failed "$UNIT" "$INHIBIT_UNIT" 2>/dev/null || true
  if [[ -f "$STATE" ]]; then
    gov="$(sed -n 's/^governor=//p' "$STATE")"
    [[ -n "$gov" ]] && echo "$gov" | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null
    rm -f "$STATE"
  fi
  # shellcheck disable=SC2086
  sudo systemctl start $TIMERS 2>/dev/null || true
  echo "restored; starting the desktop (graphical.target)"
  sudo systemctl isolate graphical.target
  exit 0
fi

[[ -z "${1:-}" ]] || { echo "unknown option: $1 (use --status or --restore)" >&2; exit 2; }

# --- checks, while nothing is isolated yet -------------------------------------------------------
if systemctl is-active --quiet "$UNIT"; then
  echo "$UNIT is already running; see $0 --status" >&2; exit 1
fi
[[ -z "$(git status --porcelain --untracked-files=no)" ]] \
  || { echo "tracked files are modified; commit or stash first (the manifests record the state)" >&2; git status --short --untracked-files=no >&2; exit 1; }
for c in jq git mvn timeout sha256sum python3; do
  command -v "$c" >/dev/null || { echo "$c is required" >&2; exit 1; }
done

if [[ -z "${REPLAY_ROOT:-}" ]]; then
  for c in "$REPO/data/replay/data" "$REPO/../FRAMED/data/replay/data"; do [[ -d "$c" ]] && { REPLAY_ROOT="$c"; break; }; done
fi
case " $STEPS " in *" arn "*|*" kink "*)
  [[ -d "${REPLAY_ROOT:-}" ]] || { echo "dataset not found; set REPLAY_ROOT" >&2; exit 1; }
  REPLAY_ROOT="$(readlink -f "$REPLAY_ROOT")" ;;
esac
case " $STEPS " in *" mimic "*)
  MIMIC_RECORD="${MIMIC_RECORD:-$REPO/../physionet.org/files/mimic3wdb/1.0/30/3000125/3000125.hea}"
  [[ -f "$MIMIC_RECORD" ]] || { echo "MIMIC record not found: $MIMIC_RECORD (set MIMIC_RECORD)" >&2; exit 1; }
  MIMIC_RECORD="$(readlink -f "$MIMIC_RECORD")" ;;
esac
case " $STEPS " in *" kink "*)
  PYFRAMED_HOME="$(readlink -f "${PYFRAMED_HOME:-$REPO/../safety-box-python}")"
  [[ -d "$PYFRAMED_HOME" ]] || { echo "pyFRAMED not found: $PYFRAMED_HOME (set PYFRAMED_HOME)" >&2; exit 1; }
  want="$(sed -n 's/^PYFRAMED_COMMIT="\${PYFRAMED_COMMIT:-\([0-9a-f]*\)}"$/\1/p' experiments/framed-benchmark/run_kink.sh)"
  [[ "$(git -C "$PYFRAMED_HOME" rev-parse HEAD)" == "$want" ]] \
    || { echo "pyFRAMED is not at $want: git -C $PYFRAMED_HOME checkout $want" >&2; exit 1; }
  [[ -z "$(git -C "$PYFRAMED_HOME" status --porcelain --untracked-files=no)" ]] \
    || { echo "pyFRAMED has modified tracked files" >&2; exit 1; }
  if [[ -z "${PYTHON:-}" ]]; then
    env_dir="$(cd "$PYFRAMED_HOME" && poetry env info -p 2>/dev/null)" \
      || { echo "no poetry environment in $PYFRAMED_HOME; run 'poetry install' there or set PYTHON" >&2; exit 1; }
    PYTHON="$env_dir/bin/python"
  fi ;;
esac
for s in $STEPS; do case "$s" in arn|kink|mimic) ;; *) echo "unknown step: $s" >&2; exit 1 ;; esac; done

if ls /sys/class/power_supply/*/online >/dev/null 2>&1 && ! grep -qx 1 /sys/class/power_supply/*/online; then
  echo "WARNING: not on AC power" >&2
fi
free_gb=$(df -BG --output=avail "$REPO" | tail -n1 | tr -dc 0-9)
(( free_gb >= 10 )) || { echo "only ${free_gb} GB free; the ARN step alone writes ~5 GB" >&2; exit 1; }

# --- build (unmeasured), with the JDK the runners pin ---------------------------------------------
source "$REPO/experiments/common/runlib.sh"
rl_resolve_java || exit 1
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  rl_build -DskipTests install || exit 1
fi

echo
echo "Steps: $STEPS (label $LABEL)"
echo "  dataset: ${REPLAY_ROOT:-n/a}"
echo "  mimic:   ${MIMIC_RECORD:-n/a}"
echo "  python:  ${PYTHON:-n/a}"
echo "  java:    $JAVA_BIN"
if [[ "${DRY_RUN:-0}" == 1 ]]; then echo "DRY_RUN: checks passed, nothing isolated or started"; exit 0; fi
echo "Isolating now stops the desktop and every program running in it."
read -r -p "Continue? [y/N] " answer
[[ "$answer" == [yY] ]] || { echo "aborted"; exit 1; }

# --- isolate ---------------------------------------------------------------------------------------
sudo -v
mkdir -p "$(dirname "$STATE")"
[[ -f "$STATE" ]] || echo "governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)" > "$STATE"
sudo systemctl isolate multi-user.target
echo "$GOVERNOR" | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor >/dev/null
# shellcheck disable=SC2086
sudo systemctl stop $TIMERS 2>/dev/null || true
sudo systemctl reset-failed "$INHIBIT_UNIT" 2>/dev/null || true
systemctl is-active --quiet "$INHIBIT_UNIT" \
  || sudo systemd-run --unit="$INHIBIT_UNIT" --quiet \
       systemd-inhibit --what=sleep:idle:handle-lid-switch --who=FRAMED --why="experiment runs" sleep infinity

# --- start ------------------------------------------------------------------------------------------
sudo systemctl reset-failed "$UNIT" 2>/dev/null || true
sudo systemd-run --unit="$UNIT" -p User="$USER" -p WorkingDirectory="$REPO" \
  --setenv=PATH="$PATH" --setenv=HOME="$HOME" --setenv=LABEL="$LABEL" --setenv=STEPS="$STEPS" \
  --setenv=SKIP_BUILD=1 --setenv=JAVA="$JAVA_BIN" \
  --setenv=REPLAY_ROOT="${REPLAY_ROOT:-}" --setenv=MIMIC_RECORD="${MIMIC_RECORD:-}" \
  --setenv=PYFRAMED_HOME="${PYFRAMED_HOME:-}" --setenv=PYTHON="${PYTHON:-}" \
  "$REPO/experiments/run_isolated.sh" --inner

cat <<EOF

Started: unit $UNIT ($STEPS).
  progress:  $0 --status
  live log:  journalctl -u $UNIT -f | grep -E '^\[|===|!!'
  stop:      sudo systemctl stop $UNIT        (resume: run $0 again)
  afterwards: $0 --restore                    (or reboot)
EOF
