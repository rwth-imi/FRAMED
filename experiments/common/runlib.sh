# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Shared infrastructure of the experiment runners (sourced, not executed):
#   - Java pinning:  rl_resolve_java       -> JAVA_BIN, JDK_HOME (JAVA, JAVA_HOME, JAVA_MAJOR)
#   - build:         rl_build <mvn goals>  (skipped with SKIP_BUILD=1)
#   - provenance:    rl_manifest <file> <writer-function>; refuses a resume on other code
#   - attempts:      rl_last_exit <runs.csv> col=value ...; rl_host_before / rl_host_after
#   - progress:      rl_progress_init <total> <file>; rl_progress_step <ran 0|1> <text>
#
# Conventions shared by every runner: an OUT_ROOT holds <LABEL>_manifest.txt, <LABEL>_runs.csv
# (one row per attempt) and <LABEL>_progress.txt. A unit of work is complete when its last
# attempt exited 0 and its outputs exist; re-running the identical command resumes.

# Never end silently: name the command that aborted the caller.
rl_traps() {
  # shellcheck disable=SC2064
  trap "echo \"$1: aborted (exit \$?) at line \$LINENO: \$BASH_COMMAND\" >&2" ERR
}

# The C locale keeps decimal points in the JVM's and Python's formatted numbers, which the
# analyses parse.
export LC_ALL=C.UTF-8

JAVA_MAJOR="${JAVA_MAJOR:-21}"
rl_java_major() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }

# Sets JAVA_BIN and JDK_HOME to a Java $JAVA_MAJOR runtime, or fails: a run never silently uses
# another JVM. Order: JAVA, $JAVA_HOME, /usr/lib/jvm/temurin-<major>-*, /usr/lib/jvm/*-<major>-*,
# java on PATH.
rl_resolve_java() {
  local c
  JAVA_BIN=""
  if [[ -n "${JAVA:-}" ]]; then
    if [[ -x "$JAVA" && "$(rl_java_major "$JAVA")" == "$JAVA_MAJOR" ]]; then
      JAVA_BIN="$(readlink -f "$JAVA")"
    else
      echo "JAVA=$JAVA is not a Java $JAVA_MAJOR runtime" >&2; return 1
    fi
  else
    for c in ${JAVA_HOME:+"$JAVA_HOME/bin/java"} /usr/lib/jvm/temurin-"$JAVA_MAJOR"-*/bin/java \
             /usr/lib/jvm/*-"$JAVA_MAJOR"-*/bin/java "$(command -v java || true)"; do
      if [[ -x "$c" && "$(rl_java_major "$c")" == "$JAVA_MAJOR" ]]; then JAVA_BIN="$(readlink -f "$c")"; break; fi
    done
  fi
  [[ -n "$JAVA_BIN" ]] || { echo "No Java $JAVA_MAJOR runtime found; set JAVA=/path/to/bin/java" >&2; return 1; }
  JDK_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
}

# Runs Maven in $REPO with the pinned JDK, unless SKIP_BUILD=1.
rl_build() {
  [[ "${SKIP_BUILD:-0}" == 1 ]] && return 0
  [[ -x "$JDK_HOME/bin/javac" ]] || { echo "$JDK_HOME has no javac; set JAVA to a full JDK $JAVA_MAJOR or use SKIP_BUILD=1" >&2; return 1; }
  command -v mvn >/dev/null || { echo "mvn not found on PATH ($PATH); under systemd-run pass --setenv=PATH=\$PATH, or build first and use SKIP_BUILD=1" >&2; return 1; }
  echo "building with $JDK_HOME: mvn $*"
  (cd "$REPO" && JAVA_HOME="$JDK_HOME" mvn -q "$@") || { echo "build failed (see the Maven output above)" >&2; return 1; }
}

rl_governor()     { cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo ""; }
rl_pi_temp()      { command -v vcgencmd >/dev/null && vcgencmd measure_temp | tr -dc '0-9.' || echo ""; }
rl_pi_throttled() { command -v vcgencmd >/dev/null && vcgencmd get_throttled | cut -d= -f2 || echo ""; }

# Host state around one attempt: rl_host_before sets RL_LOAD, RL_TEMP0, RL_THR0 and RL_START;
# rl_host_after prints "<end_utc>,<temp_after>,<throttled_after>".
rl_host_before() {
  RL_LOAD="$(cut -d' ' -f1 /proc/loadavg)"; RL_TEMP0="$(rl_pi_temp)"; RL_THR0="$(rl_pi_throttled)"
  RL_START="$(date -u +%FT%TZ)"
}
rl_host_after() { echo "$(date -u +%FT%TZ),$(rl_pi_temp),$(rl_pi_throttled)"; }

# The common head of every manifest: code, runtime and host.
rl_manifest_host() {
  echo "started:     $(date -u +%FT%TZ)"
  echo "git commit:  $RL_COMMIT"
  echo "git branch:  $(git -C "$REPO" rev-parse --abbrev-ref HEAD)"
  echo "git dirty:   $RL_DIRTY tracked files modified"
  echo "java:        $("$JAVA_BIN" -version 2>&1 | head -n1)"
  echo "java binary: $JAVA_BIN"
  echo "os:          $(uname -srm)"
  echo "cpu:         $(lscpu 2>/dev/null | sed -n 's/^Model name:[[:space:]]*//p' | head -n1)"
  echo "cpus:        $(nproc)"
  echo "memory:      $(free -h 2>/dev/null | awk '/^Mem:/{print $2}')"
  echo "governor:    $(rl_governor)"
  echo "cpu boost:   $(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || echo n/a)"
  echo "cpu set:     $(taskset -pc $$ 2>/dev/null | sed 's/.*: //')"
  echo "systemd:     $(systemctl get-default 2>/dev/null) (active: $(systemctl is-active graphical.target 2>/dev/null))"
  echo "locale:      $LC_ALL"
}

# rl_manifest <file> <writer> [resume-lines-function]
# New OUT_ROOT: writes rl_manifest_host followed by the caller's <writer> output. Existing
# manifest: refuses unless the commit, the number of modified tracked files and the caller's
# RL_SETTINGS (the design-relevant settings, one line) are unchanged (RESUME_ANYWAY=1 overrides),
# then appends a "resumed:" entry plus the optional resume lines.
rl_manifest() {
  local file="$1" writer="$2" resume="${3:-}" orig_commit orig_dirty orig_settings
  RL_COMMIT="$(git -C "$REPO" rev-parse HEAD)"
  RL_DIRTY="$(git -C "$REPO" status --porcelain --untracked-files=no | wc -l)"
  if [[ -f "$file" ]]; then
    orig_commit="$(sed -n 's/^git commit:  //p' "$file" | head -n1)"
    orig_dirty="$(sed -n 's/^git dirty:   \([0-9]*\).*/\1/p' "$file" | head -n1)"
    if [[ "$orig_commit" != "$RL_COMMIT" || "$orig_dirty" != "$RL_DIRTY" ]] && [[ "${RESUME_ANYWAY:-0}" != 1 ]]; then
      echo "Refusing to resume: started at $orig_commit ($orig_dirty modified), now $RL_COMMIT ($RL_DIRTY modified)." >&2
      echo "Use a new OUT_ROOT, or RESUME_ANYWAY=1." >&2
      return 1
    fi
    # Manifests written before the settings line existed carry none; nothing to compare then.
    orig_settings="$(sed -n 's/^settings:    //p' "$file" | head -n1)"
    if [[ -n "$orig_settings" && "$orig_settings" != "${RL_SETTINGS:-}" ]] && [[ "${RESUME_ANYWAY:-0}" != 1 ]]; then
      echo "Refusing to resume: the run settings changed." >&2
      echo "  started with: $orig_settings" >&2
      echo "  now:          ${RL_SETTINGS:-}" >&2
      echo "Use the original settings, a new OUT_ROOT, or RESUME_ANYWAY=1." >&2
      return 1
    fi
    {
      echo "resumed:     $(date -u +%FT%TZ)  commit $RL_COMMIT, $RL_DIRTY modified"
      echo "  java:      $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -n1))"
      [[ -z "$resume" ]] || "$resume"
    } >> "$file"
  else
    { rl_manifest_host; echo "settings:    ${RL_SETTINGS:-}"; "$writer"; } > "$file"
  fi
}

# rl_last_exit <runs.csv> col=value ... : the exit code of the last row matching every pair
# (empty if none). Columns are looked up by the header, so callers keep their own layouts.
rl_last_exit() {
  local file="$1"; shift
  [[ -f "$file" ]] || return 0
  awk -F, -v conds="$*" '
    NR == 1 { for (i = 1; i <= NF; i++) col[$i] = i; n = split(conds, c, " ");
              for (j = 1; j <= n; j++) { split(c[j], kv, "="); key[j] = col[kv[1]]; val[j] = kv[2] }
              next }
    { ok = 1; for (j = 1; j <= n; j++) if ($(key[j]) != val[j]) { ok = 0; break }
      if (ok) e = $(col["exit"]) }
    END { print e }' "$file"
}

# Progress: one line per unit on stdout and in <file> (rewritten atomically, so `cat` or
# `watch cat` shows how far a detached run is). The ETA averages the units executed in this
# invocation; skipped (complete) units take no time and are left out.
rl_hms() { printf '%dh%02dm' $(( $1 / 3600 )) $(( $1 % 3600 / 60 )); }
rl_progress_init() {
  RL_TOTAL="$1"; RL_PROGRESS="$2"
  RL_DONE=0; RL_RAN=0; RL_RAN_SECS=0; RL_SKIPPED=0; RL_FAILED=0; RL_T0=$SECONDS
  RL_STARTED="$(date -u +%FT%TZ)"
}
# Call before a unit; then rl_progress_step <outcome: ran|skipped|failed> <text>.
rl_unit_start() { RL_T0=$SECONDS; }
rl_progress_step() {
  local outcome="$1" text="$2" line pct eta="?" finish="?"
  RL_DONE=$((RL_DONE + 1))
  case "$outcome" in
    skipped) RL_SKIPPED=$((RL_SKIPPED + 1)) ;;
    failed)  RL_FAILED=$((RL_FAILED + 1)); RL_RAN=$((RL_RAN + 1)); RL_RAN_SECS=$((RL_RAN_SECS + SECONDS - RL_T0)) ;;
    *)       RL_RAN=$((RL_RAN + 1)); RL_RAN_SECS=$((RL_RAN_SECS + SECONDS - RL_T0)) ;;
  esac
  pct=$(( 100 * RL_DONE / RL_TOTAL ))
  if (( RL_RAN > 0 )); then
    local left=$(( (RL_TOTAL - RL_DONE) * RL_RAN_SECS / RL_RAN ))
    eta="$(rl_hms "$left")"; finish="$(date -d "+${left} sec" '+%a %H:%M')"
  fi
  line="[$RL_DONE/$RL_TOTAL ${pct}%] ran $RL_RAN (avg $(( RL_RAN ? RL_RAN_SECS / RL_RAN : 0 ))s), skipped $RL_SKIPPED, failed $RL_FAILED | remaining $eta, finish ~$finish | last: $text"
  echo "$line"
  printf '%s\nlabel %s, started %s, updated %s\n' "$line" "$LABEL" "$RL_STARTED" "$(date -u +%FT%TZ)" \
    > "$RL_PROGRESS.tmp" && mv "$RL_PROGRESS.tmp" "$RL_PROGRESS"
}

# sha256 lines of files, relative to a root: rl_hash_inputs <root> <file>...
rl_hash_inputs() {
  local root="$1" p; shift
  for p in "$@"; do echo "  $(sha256sum "$p" | cut -d' ' -f1)  ${p#"$root"/}"; done
}
