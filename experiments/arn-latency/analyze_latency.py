#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Analyse the off-bus reactor timing written by ``run_replays.sh``.

Input is a results root written by ``run_replays.sh`` (layout in ``framed_runs.py``); only
runs whose last attempt exited 0 are used. One of three analyses: ordering correctness is in
``analyze_ordering.py``, consumption completeness in ``analyze_consumption.py``.

Per FIRED evaluation (instants in ns; see ``com.framed.arn.FiringRecord``):

=================  ===========================================================
trigger            reaction end − timestamp of the triggering message
trigger_net        reaction end − actual send instant of the triggering instant by the replay
                   (primary; excludes the replay thread's wake-up delay, see below)
transport          arrival of the triggering message − its timestamp
scheduling         firing decision − arrival of the triggering message
execution          reaction end − reaction start
end_to_end         reaction end − timestamp t of the output tag (newest held input)
transport_net      arrival of the triggering message − actual send instant
=================  ===========================================================

The replay stamps each event with its *scheduled* instant, so ``trigger`` and ``transport``
include the replay thread's wake-up delay. ``ReplayProtocol`` logs the actual send instant of
every event (``metrics/<run>_emits.csv``); the ``*_net`` metrics subtract it. Downstream
reactors inherit the source sample's timestamp, so their triggering instant is matched to the
first send of that scheduled instant. ``replay_lag`` (send − schedule, reactor ``Replay``)
quantifies the delay itself (label ``Replay``; computed over all replayed events of the run,
without warm-up exclusion).

Per consumed input of a FIRED evaluation:

=================  ===========================================================
rule_wait          firing decision − arrival of that input
consumption        reaction end − timestamp of that input, at its first consumption.
                   This is comparable to the per-channel ``Latency`` rows of the v03 runs.
consumption_net    reaction end − actual send instant of that input, at its first
                   consumption (excludes the replay thread's wake-up delay, as
                   ``trigger_net``; matched to the send log like the triggering instant)
=================  ===========================================================

All latencies are measured from the timestamp ``t`` of the source sample's tag (Definition 6);
microsteps do not enter. An input is identified by (reactor, channel, ``seq``), ``seq`` being
the channel's accepted-update counter, and its first consumption is taken over all recorded
evaluations. (Runs recorded with the former reactor-level gate also logged GATED evaluations;
inputs first taken by one of those are excluded from ``consumption`` and ``consumption_net``.)

Summaries are computed **per run** (n, mean, std, p50, p90, p99, p99.9, max; the 99.9th percentile
is named ``p999`` in all column names) and then aggregated
over runs: the median of the per-run values, with a percentile-bootstrap 95 % CI over runs.
``summary.csv`` additionally reports the **pooled** mean and standard deviation over all samples
of all runs (``mean_pooled``, ``std_pooled``; sample std, ddof = 1), as reported in the v03
manuscript. They are derived exactly from the per-run n, mean and std. Both are dominated by a few
deferred samples (see ``out/diagnose_v03/FINDINGS.md``); the percentiles are the primary measure.

Further outputs:

- ``evaluations.csv``: per reactor and run, FIRED evaluations, those of them with output microstep
  >= 1 (``fired_m_ge1``: further firings at an already reached timestamp, e.g. one per further path
  of a source instant), no-rule evaluations and DISCARDED updates (recorder summary and
  ``firings.csv``), with the discarded share of all received updates;
- ``evaluations_summary.csv``: the same summed per platform, mode and reactor;
- ``interpreter_status.csv``: Interpreter trigger latency per status (WARNING, NO_WARNING,
  SUPPRESSED), joined by firing order;
- ``control.csv``: for recordings replayed more than once, the run-to-run differences
  (control vs control) next to the atomic vs non-atomic differences;
- ``load_per_run.csv``, ``load.csv``: load indicators per run and per platform and mode (median
  and maximum over runs), see below;
- ``warmup_sensitivity.csv``: trigger-latency summaries with and without the first
  ``--warmup-s`` seconds of each run;
- ``consumption_net.csv``: the ``consumption`` and ``consumption_net`` rows of ``summary.csv``
  side by side per reactor (median over runs of the per-run p50, p99, p999 and max), with
  the wake-up correction ``consumption − consumption_net`` at p50.

**Load indicators** (``load*.csv``). These columns indicate how far the reactor network is from
saturation at the replayed load, per run over its whole duration
(``duration_s`` = last − first actual send instant of the replay, from ``emits.csv``):

- ``device_events_per_s``: replayed events per second (offered load);
- ``firings_per_s``: FIRED evaluations of all reactors per second;
- ``exec_us_per_firing``: mean ``react_end − react_start`` per FIRED evaluation (reaction function
  including publication of its outputs);
- ``exec_share_pct``: Σ(``react_end − react_start``) / duration, over all reactors;
- ``busy_share_pct``: the same plus Σ(``decision − trigger_arrival``), i.e. the wall time spent in
  reactor evaluation as a share of one core.

The same quantities restricted to the steady state (firings after the first ``--warmup-s`` seconds,
the window of the latency statistics) carry the suffix ``_steady``; ``latency_mean_ms_steady`` is
the mean ``trigger_net`` over those firings. ``load.csv`` reports the median and maximum over runs
and, for the steady state, the value **pooled** over all firings of all runs (``*_pooled``).

These are wall-time intervals, not CPU time: the decision interval includes waiting for the
evaluation lock and descheduling, and the work outside the reactors (event bus, replay, sink,
JVM) and evaluations without a satisfied rule (no timing recorded) are not included. GC pauses
come from ``gc/<run>.log`` (unified JVM logging, lines ``GC(n) Pause … <ms>ms``):
``gc_pauses``, ``gc_pause_ms_total``, ``gc_pause_ms_max``, ``gc_pause_share_pct``.

**CPU** (``cpu*`` columns; runs recorded with ``metrics/<run>_cpu.csv``, 1 Hz samples of the JVM's
cumulative user + system CPU time and of the host's busy/total CPU ticks from ``/proc``). Unlike
the wall-time indicators above, this is CPU time of the whole JVM: reactors, event bus (TCP
loopback), replay, JSON-Lines sink, GC and JIT. Per run, between the first and last sample inside
the window (whole run: first to last send instant; ``_steady``: after ``--warmup-s``):

- ``cpu_cores``: JVM CPU seconds per wall second (1.0 = one core fully used);
  ``cpu_machine_pct`` the same as a share of all ``ncpu`` logical CPUs;
- ``host_cpu_pct``: busy share of the whole host (all processes);
- ``cpu_us_per_event``, ``cpu_us_per_firing``: JVM CPU time per replayed device event and per
  FIRED evaluation in the window;
- ``cpu_headroom_1core_x`` = 1 / ``cpu_cores`` and ``cpu_headroom_x`` = ``ncpu`` / ``cpu_cores``:
  factors by which the offered load could grow before one core, or all cores, would be saturated,
  **assuming CPU time scales linearly with the load**. These are extrapolations, not measured
  capacities: serial sections (atomic evaluation locks, the bus dispatch) bound the throughput
  below ``cpu_headroom_x``, and queueing raises the latency well before saturation.

``load.csv`` reports ``cpu_cores_steady``, ``host_cpu_pct_steady`` and ``proc_rss_mb_max`` (median
and maximum over runs) and pools the steady state over runs (``*_steady_pooled``: total CPU time /
total wall time, events or firings). ``cpu_runs`` counts the runs with CPU samples.

Usage::

    python analyze_latency.py --root out/pilot --out out/pilot/analysis [--warmup-s 30]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from framed_runs import LABELS, ORDER, STATUS_CHANNEL, Run, discover

QUANTILES = {"p50": 0.5, "p90": 0.9, "p99": 0.99, "p999": 0.999}


def summarise(values: np.ndarray) -> dict:
    v = values[~np.isnan(values)]
    if v.size == 0:
        return {"n": 0}
    out = {"n": int(v.size), "mean": float(v.mean()),
           "std": float(v.std(ddof=1)) if v.size > 1 else np.nan, "max": float(v.max())}
    out.update({k: float(np.quantile(v, q)) for k, q in QUANTILES.items()})
    return out


def pooled_mean_std(n: np.ndarray, mean: np.ndarray, std: np.ndarray) -> tuple[float, float]:
    """Mean and sample std (ddof = 1) of the union of several samples, from their n, mean, std."""
    ok = ~np.isnan(mean) & (n > 0)
    n, mean, std = n[ok], mean[ok], np.nan_to_num(std[ok])
    total = n.sum()
    if total == 0:
        return (np.nan, np.nan)
    m = float((n * mean).sum() / total)
    if total < 2:
        return (m, np.nan)
    ss = ((n - 1) * std ** 2 + n * (mean - m) ** 2).sum()
    return (m, float(np.sqrt(ss / (total - 1))))


def bootstrap_ci(values: np.ndarray, rng: np.random.Generator, n_boot: int = 2000) -> tuple:
    v = values[~np.isnan(values)]
    if v.size < 2:
        return (np.nan, np.nan)
    meds = np.median(rng.choice(v, size=(n_boot, v.size), replace=True), axis=1)
    return tuple(np.percentile(meds, [2.5, 97.5]))


def send_instants(emits: pd.DataFrame | None, channel: pd.Series, ts_ns: pd.Series) -> pd.Series:
    """Actual send instant of each (bus address, scheduled timestamp) pair.

    Device channels are matched exactly on (address, scheduled instant); CDSS channels, which
    inherit the source sample's timestamp under a bare channel name, fall back to the first send
    of that scheduled instant. ``NaN`` where no send is logged (or no send log exists).
    """
    if emits is None:
        return pd.Series(np.nan, index=channel.index)
    exact = emits.drop_duplicates(["address", "scheduled_ns"]).set_index(["address", "scheduled_ns"])["emitted_ns"]
    first = emits.groupby("scheduled_ns")["emitted_ns"].min()
    keys = pd.MultiIndex.from_arrays([channel, ts_ns])
    sent = pd.Series(exact.reindex(keys).to_numpy(), index=channel.index)
    return sent.fillna(ts_ns.map(first))


def load_run(r: Run):
    f = pd.read_csv(r.metrics("firings"))
    i = pd.read_csv(r.metrics("inputs"))
    s = pd.read_csv(r.metrics("summary"))
    if "logical_m" not in f.columns:    # recorded before tags carried a microstep
        f["logical_m"] = 0
    if "discarded" not in s.columns:    # former summary (gated, not discarded): unknown
        s["discarded"] = np.nan
    t0 = f["trigger_arrival_ns"].min()
    f["t_rel_s"] = (f["trigger_arrival_ns"] - t0) / 1e9
    fired = f[f["outcome"] == "FIRED"].copy()
    ms = 1e6
    fired["trigger"] = (fired["react_end_ns"] - fired["trigger_ts_ns"]) / ms
    fired["transport"] = (fired["trigger_arrival_ns"] - fired["trigger_ts_ns"]) / ms
    fired["scheduling"] = (fired["decision_ns"] - fired["trigger_arrival_ns"]) / ms
    fired["execution"] = (fired["react_end_ns"] - fired["react_start_ns"]) / ms
    fired["end_to_end"] = (fired["react_end_ns"] - fired["logical_ts_ns"]) / ms

    emits_file = r.metrics("emits")
    e = pd.read_csv(emits_file) if emits_file.exists() else None
    sent = send_instants(e, fired["trigger_channel"], fired["trigger_ts_ns"])
    fired["trigger_net"] = (fired["react_end_ns"] - sent) / ms
    fired["transport_net"] = (fired["trigger_arrival_ns"] - sent) / ms
    lag = (e["emitted_ns"] - e["scheduled_ns"]) / ms if e is not None else pd.Series(dtype=float)

    j = i.merge(fired[["reactor", "evaluation", "decision_ns", "react_end_ns", "t_rel_s"]],
                on=["reactor", "evaluation"])
    j["rule_wait"] = (j["decision_ns"] - j["arrival_ns"]) / ms
    j = j.sort_values("evaluation")
    ident = ["reactor", "channel", "seq"]
    taken = (i.merge(f[["reactor", "evaluation", "outcome"]], on=["reactor", "evaluation"])
             .sort_values("evaluation").drop_duplicates(ident))
    first_gated = taken.loc[taken["outcome"] != "FIRED", ident]
    first_consumed = j.drop_duplicates(ident).merge(first_gated, on=ident, how="left", indicator=True)
    first_consumed = first_consumed[first_consumed["_merge"] == "left_only"].drop(columns="_merge")
    first_consumed["consumption"] = (first_consumed["react_end_ns"] - first_consumed["ts_ns"]) / ms
    sent_in = send_instants(e, first_consumed["channel"], first_consumed["ts_ns"])
    first_consumed["consumption_net"] = (first_consumed["react_end_ns"] - sent_in) / ms
    return f, fired, j, first_consumed, s, lag


GC_PAUSE = re.compile(r"GC\(\d+\) Pause .* (\d+(?:\.\d+)?)ms\s*$")


def cpu_window(c: pd.DataFrame, start_ns: float, end_ns: float, events: pd.Series,
               firings: pd.Series) -> dict:
    """CPU use between the first and the last sample inside ``[start_ns, end_ns]``.

    ``events`` and ``firings`` are epoch-ns instants; those between the two samples are counted.
    Returns NaNs when fewer than two samples fall inside the window.
    """
    w = c[(c["wall_ns"] >= start_ns) & (c["wall_ns"] <= end_ns)]
    keys = ("cpu_wall_s", "cpu_s", "cpu_events", "cpu_firings", "cpu_cores", "cpu_machine_pct",
            "host_cpu_pct", "cpu_us_per_event", "cpu_us_per_firing", "cpu_headroom_1core_x",
            "cpu_headroom_x")
    if len(w) < 2:
        return dict.fromkeys(keys, np.nan)
    a, b = w.iloc[0], w.iloc[-1]
    wall = (b["wall_ns"] - a["wall_ns"]) / 1e9
    cpu = ((b["proc_utime"] + b["proc_stime"]) - (a["proc_utime"] + a["proc_stime"])) / a["clk_tck"]
    n_ev = int(events.between(a["wall_ns"], b["wall_ns"]).sum())
    n_fi = int(firings.between(a["wall_ns"], b["wall_ns"]).sum())
    cores = cpu / wall
    return dict(zip(keys, (
        wall, cpu, n_ev, n_fi, cores, 100 * cores / a["ncpu"],
        100 * (b["host_busy"] - a["host_busy"]) / (b["host_total"] - a["host_total"]),
        1e6 * cpu / n_ev if n_ev else np.nan, 1e6 * cpu / n_fi if n_fi else np.nan,
        1 / cores if cores > 0 else np.nan, a["ncpu"] / cores if cores > 0 else np.nan)))


def cpu_indicators(r: Run, f: pd.DataFrame, emitted_ns: pd.Series, warmup_s: float) -> dict:
    """CPU indicators of one run from ``metrics/<run>_cpu.csv`` (module docstring, *CPU*)."""
    path = r.metrics("cpu")
    if not path.exists():               # recorded before CPU sampling
        return {"cpu_log": False}
    c = pd.read_csv(path)
    fired = f.loc[f["outcome"] == "FIRED", "trigger_arrival_ns"]
    whole = cpu_window(c, emitted_ns.min(), emitted_ns.max(), emitted_ns, fired)
    t0 = f["trigger_arrival_ns"].min()
    steady = cpu_window(c, t0 + warmup_s * 1e9, f["trigger_arrival_ns"].max(), emitted_ns, fired)
    return {"cpu_log": True, "ncpu": int(c["ncpu"].iloc[0]),
            "proc_rss_mb_max": c["proc_rss_kb"].max() / 1024, "proc_threads_max": int(c["proc_threads"].max()),
            **whole, **{k + "_steady": v for k, v in steady.items()}}


def load_indicators(r: Run, f: pd.DataFrame, fired_all: pd.DataFrame, warmup_s: float) -> dict:
    """Load indicators of one run (module docstring, *Load indicators*)."""
    e = pd.read_csv(r.metrics("emits"), usecols=["emitted_ns"])
    duration = (e["emitted_ns"].max() - e["emitted_ns"].min()) / 1e9
    fired = f[f["outcome"] == "FIRED"]
    exec_s = (fired["react_end_ns"] - fired["react_start_ns"]).sum() / 1e9
    sched_s = (fired["decision_ns"] - fired["trigger_arrival_ns"]).sum() / 1e9
    st = fired_all[fired_all["t_rel_s"] >= warmup_s]
    window = max(f["t_rel_s"].max() - warmup_s, 0.0)
    st_busy = ((st["react_end_ns"] - st["react_start_ns"]).sum()
               + (st["decision_ns"] - st["trigger_arrival_ns"]).sum()) / 1e9
    steady = {"window_s_steady": window, "firings_steady": len(st), "busy_s_steady": st_busy,
              "latency_sum_ms_steady": float(st["trigger_net"].sum()),
              "latency_n_steady": int(st["trigger_net"].notna().sum()),
              "firings_per_s_steady": len(st) / window if window else np.nan,
              "busy_us_per_firing_steady": 1e6 * st_busy / len(st) if len(st) else np.nan,
              "busy_share_pct_steady": 100 * st_busy / window if window else np.nan,
              "latency_mean_ms_steady": float(st["trigger_net"].mean())}
    gc_log = r.pass_dir / "gc" / f"{r.run}.log"
    pauses = []
    if gc_log.exists():
        with open(gc_log, encoding="utf-8", errors="replace") as fh:
            pauses = [float(m.group(1)) for line in fh if (m := GC_PAUSE.search(line))]
    return {**r.key, "duration_s": duration, "device_events": len(e), "firings": len(fired),
            "device_events_per_s": len(e) / duration, "firings_per_s": len(fired) / duration,
            "exec_us_per_firing": 1e6 * exec_s / len(fired) if len(fired) else np.nan,
            "exec_share_pct": 100 * exec_s / duration,
            "busy_share_pct": 100 * (exec_s + sched_s) / duration,
            "gc_log": gc_log.exists(), "gc_pauses": len(pauses),
            "gc_pause_ms_total": float(sum(pauses)), "gc_pause_ms_max": max(pauses, default=0.0),
            "gc_pause_share_pct": 100 * sum(pauses) / 1e3 / duration, **steady,
            **cpu_indicators(r, f, e["emitted_ns"], warmup_s)}


def interpreter_statuses(r: Run) -> list[str]:
    """The Interpreter's status records in publication order (one per firing)."""
    path = r.replay()
    statuses: list[str] = []
    if path is None:
        return statuses
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if STATUS_CHANNEL not in line:
                continue
            rec = json.loads(line)
            if rec.get("className") == "Interpreter" and rec.get("channelID") == STATUS_CHANNEL:
                statuses.append(str(rec.get("value")))
    return statuses


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--warmup-s", type=float, default=30.0)
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)

    per_run, evals, status_rows, warm, load = [], [], [], [], []
    for r in discover(args.root):
        key = r.key
        f, fired, j, first, s, lag = load_run(r)
        load.append(load_indicators(r, f, fired, args.warmup_s))
        per_run.append({**key, "reactor": "Replay", "metric": "replay_lag",
                        **summarise(lag.to_numpy(float))})
        steady = fired[fired["t_rel_s"] >= args.warmup_s]

        for reactor, g in steady.groupby("reactor"):
            for metric in ("trigger", "trigger_net", "transport", "transport_net",
                           "scheduling", "execution", "end_to_end"):
                per_run.append({**key, "reactor": reactor, "metric": metric,
                                **summarise(g[metric].to_numpy(float))})
        for name, df in (("rule_wait", j), ("consumption", first), ("consumption_net", first)):
            df = df[df["t_rel_s"] >= args.warmup_s]
            for reactor, g in df.groupby("reactor"):
                per_run.append({**key, "reactor": reactor, "metric": name,
                                **summarise(g[name].to_numpy(float))})
        for reactor, g in fired.groupby("reactor"):
            for window, sub in (("all", g), (f">={args.warmup_s:g}s", g[g["t_rel_s"] >= args.warmup_s])):
                warm.append({**key, "reactor": reactor, "window": window,
                             **summarise(sub["trigger_net"].to_numpy(float))})

        statuses = interpreter_statuses(r)
        m_ge1 = fired[fired["logical_m"] >= 1].groupby("reactor").size()
        for _, row in s[s["reactor"] != "*dropped*"].iterrows():
            fired_n = int(row["fired"])
            discarded_n = int(row["discarded"]) if pd.notna(row["discarded"]) else np.nan
            no_rule_n = int(row["no_rule"]) if pd.notna(row["no_rule"]) else 0
            received = fired_n + no_rule_n + discarded_n
            evals.append({**key, "label": LABELS.get(row["reactor"], row["reactor"]),
                          "reactor": row["reactor"], "fired": fired_n,
                          "fired_m_ge1": int(m_ge1.get(row["reactor"], 0)), "no_rule": no_rule_n,
                          "discarded": discarded_n,
                          "discarded_share": discarded_n / received if received > 0 else np.nan})
        dropped = s.loc[s["reactor"] == "*dropped*", "fired"]
        if len(dropped) and int(dropped.iloc[0]) > 0:
            print(f"WARNING {r.pass_dir.name}/{r.run}: {int(dropped.iloc[0])} records dropped", file=sys.stderr)

        itp = fired[fired["reactor"] == "Interpreter"].sort_values("evaluation")
        if len(itp) == len(statuses):
            st = pd.Series(statuses).str.split(";").str[0].where(
                ~pd.Series(statuses).str.contains("SUPPRESSED"), "SUPPRESSED")
            df = pd.DataFrame({"status": st.values, "t_rel_s": itp["t_rel_s"].values,
                               "trigger_net": itp["trigger_net"].values,
                               "end_to_end": itp["end_to_end"].values})
            for status, g in df[df["t_rel_s"] >= args.warmup_s].groupby("status"):
                for metric in ("trigger_net", "end_to_end"):
                    status_rows.append({**key, "status": status, "metric": metric,
                                        **summarise(g[metric].to_numpy(float))})
        elif statuses:
            print(f"WARNING {r.pass_dir.name}/{r.run}: {len(statuses)} statuses vs {len(itp)} Interpreter firings",
                  file=sys.stderr)

    if not per_run:
        print("No runs found under", args.root, file=sys.stderr)
        return 1

    per_run = pd.DataFrame(per_run)
    per_run.insert(0, "label", per_run["reactor"].map(LABELS).fillna(per_run["reactor"]))
    per_run.to_csv(args.out / "per_run.csv", index=False)

    rows = []
    for (platform, mode, reactor, metric), g in per_run.groupby(["platform", "mode", "reactor", "metric"]):
        row = {"platform": platform, "mode": mode, "label": LABELS.get(reactor, reactor), "reactor": reactor,
               "metric": metric, "runs": len(g), "n": int(g["n"].sum())}
        row["mean_pooled"], row["std_pooled"] = pooled_mean_std(
            g["n"].to_numpy(float), g["mean"].to_numpy(float), g["std"].to_numpy(float))
        for q in ("mean", "std", "p50", "p99", "p999", "max"):
            vals = g[q].to_numpy(float)
            row[f"{q}_median"] = float(np.nanmedian(vals)) if np.any(~np.isnan(vals)) else np.nan
            if q in ("p50", "p99"):
                lo, hi = bootstrap_ci(vals, rng)
                row[f"{q}_ci_lo"], row[f"{q}_ci_hi"] = lo, hi
        rows.append(row)
    agg = pd.DataFrame(rows)
    agg["_order"] = agg["label"].map({k: i for i, k in enumerate(ORDER)})
    agg = agg.sort_values(["metric", "platform", "mode", "_order"]).drop(columns="_order")
    agg.to_csv(args.out / "summary.csv", index=False)

    evals = pd.DataFrame(evals)
    evals.to_csv(args.out / "evaluations.csv", index=False)
    ekeys = ["platform", "mode", "label", "reactor"]
    esum = evals.groupby(ekeys)[["fired", "fired_m_ge1", "no_rule", "discarded"]].sum()
    esum["runs"] = evals.groupby(ekeys).size()
    esum["fired_m_ge1_pct"] = 100.0 * esum["fired_m_ge1"] / esum["fired"]
    esum["discarded_ppm"] = 1e6 * esum["discarded"] / (esum["fired"] + esum["no_rule"] + esum["discarded"])
    esum = esum.reset_index()
    esum["_order"] = esum["label"].map({k: i for i, k in enumerate(ORDER)})
    esum = esum.sort_values(["platform", "mode", "_order"]).drop(columns="_order")
    esum.to_csv(args.out / "evaluations_summary.csv", index=False)
    pd.DataFrame(status_rows).to_csv(args.out / "interpreter_status.csv", index=False)
    pd.DataFrame(warm).to_csv(args.out / "warmup_sensitivity.csv", index=False)

    load = pd.DataFrame(load)
    load.to_csv(args.out / "load_per_run.csv", index=False)
    cols = ["duration_s", "device_events_per_s", "firings_per_s", "exec_us_per_firing",
            "exec_share_pct", "busy_share_pct", "gc_pauses", "gc_pause_ms_total",
            "gc_pause_ms_max", "gc_pause_share_pct", "firings_per_s_steady",
            "busy_us_per_firing_steady", "busy_share_pct_steady", "latency_mean_ms_steady"]
    lg = load.groupby(["platform", "mode"])[cols]
    load_tab = lg.median().add_suffix("_median").join(lg.max().add_suffix("_max"))
    load_tab.insert(0, "runs", load.groupby(["platform", "mode"]).size())
    pooled = load.groupby(["platform", "mode"])[["window_s_steady", "firings_steady", "busy_s_steady",
                                                   "latency_sum_ms_steady", "latency_n_steady"]].sum()
    load_tab["firings_per_s_steady_pooled"] = pooled["firings_steady"] / pooled["window_s_steady"]
    load_tab["busy_us_per_firing_steady_pooled"] = 1e6 * pooled["busy_s_steady"] / pooled["firings_steady"]
    load_tab["busy_share_pct_steady_pooled"] = 100 * pooled["busy_s_steady"] / pooled["window_s_steady"]
    load_tab["latency_mean_ms_steady_pooled"] = pooled["latency_sum_ms_steady"] / pooled["latency_n_steady"]
    if "cpu_s_steady" in load:
        cg = load.groupby(["platform", "mode"])
        load_tab["cpu_runs"] = cg["cpu_s_steady"].count()
        load_tab["ncpu"] = cg["ncpu"].max()
        for c in ("cpu_cores_steady", "host_cpu_pct_steady", "proc_rss_mb_max"):
            load_tab[c + "_median"] = cg[c].median()
            load_tab[c + "_max"] = cg[c].max()
        cp = cg[["cpu_s_steady", "cpu_wall_s_steady", "cpu_events_steady", "cpu_firings_steady"]].sum()
        load_tab["cpu_cores_steady_pooled"] = cp["cpu_s_steady"] / cp["cpu_wall_s_steady"]
        load_tab["cpu_us_per_event_steady_pooled"] = 1e6 * cp["cpu_s_steady"] / cp["cpu_events_steady"]
        load_tab["cpu_us_per_firing_steady_pooled"] = 1e6 * cp["cpu_s_steady"] / cp["cpu_firings_steady"]
        load_tab["cpu_headroom_1core_x_steady_pooled"] = 1 / load_tab["cpu_cores_steady_pooled"]
        load_tab["cpu_headroom_x_steady_pooled"] = load_tab["ncpu"] / load_tab["cpu_cores_steady_pooled"]
    load_tab.reset_index().to_csv(args.out / "load.csv", index=False)

    # consumption latency gross vs net of the replay wake-up delay, side by side
    keys = ["platform", "mode", "label", "reactor"]
    cons = agg[agg["metric"].isin(["consumption", "consumption_net"])]
    cons = cons.pivot_table(index=keys, columns="metric",
                            values=["n", "mean_pooled", "std_pooled", "mean_median", "std_median",
                                    "p50_median", "p99_median", "p999_median", "max_median"])
    cons.columns = [f"{m}_{v.removesuffix('_median')}" for v, m in cons.columns]
    stats = ["n", "mean_pooled", "std_pooled", "mean", "std", "p50", "p99", "p999", "max"]
    cons = cons[[f"{m}_{q}" for m in ("consumption", "consumption_net") for q in stats
                 if f"{m}_{q}" in cons.columns]].reset_index()
    if {"consumption_p50", "consumption_net_p50"} <= set(cons.columns):
        cons["wakeup_p50"] = cons["consumption_p50"] - cons["consumption_net_p50"]
    cons["_order"] = cons["label"].map({k: i for i, k in enumerate(ORDER)})
    cons = cons.sort_values(["platform", "mode", "_order"]).drop(columns="_order")
    cons.to_csv(args.out / "consumption_net.csv", index=False)

    # control vs control (same recording and mode, different repetition) next to mode contrast
    trig = per_run[per_run["metric"] == "trigger_net"]
    ctrl = []
    for (platform, recording, reactor), g in trig.groupby(["platform", "recording", "reactor"]):
        for mode, gm in g.groupby("mode"):
            if gm["rep"].nunique() >= 2:
                a, b = gm.sort_values("rep").iloc[:2][["p50", "p99"]].to_numpy()
                ctrl.append({"platform": platform, "recording": recording, "reactor": reactor,
                             "contrast": f"control ({mode})", "d_p50": abs(a[0] - b[0]), "d_p99": abs(a[1] - b[1])})
        r0 = g[g["rep"] == 0]
        if r0["mode"].nunique() == 2:
            a, b = r0.sort_values("mode")[["p50", "p99"]].to_numpy()
            ctrl.append({"platform": platform, "recording": recording, "reactor": reactor,
                         "contrast": "atomic vs non-atomic", "d_p50": abs(a[0] - b[0]), "d_p99": abs(a[1] - b[1])})
    ctrl = pd.DataFrame(ctrl)
    ctrl.to_csv(args.out / "control.csv", index=False)

    with pd.option_context("display.width", 220, "display.max_columns", 30, "display.float_format", "{:.3f}".format):
        t = agg[agg["metric"] == "trigger_net"]
        print("\nTrigger latency net of replay delay (ms): median over runs of per-run p50 / p99")
        print(t.pivot_table(index="label", columns="mode", values=["p50_median", "p99_median"])
              .reindex([o for o in ORDER if o in set(t["label"])]).to_string())
        t = agg[agg["metric"] == "consumption_net"]
        print("\nConsumption latency net of replay delay (ms): median over runs of per-run p50 / p99")
        print(t.pivot_table(index="label", columns="mode", values=["p50_median", "p99_median"])
              .reindex([o for o in ORDER if o in set(t["label"])]).to_string())
        for metric in ("trigger_net", "consumption_net"):
            t = agg[agg["metric"] == metric]
            print(f"\n{metric} (ms): pooled mean / std over all samples (as in the v03 manuscript)")
            print(t.pivot_table(index="label", columns="mode", values=["mean_pooled", "std_pooled"])
                  .reindex([o for o in ORDER if o in set(t["label"])]).to_string())
        print("\nDecomposition (ms, median of per-run p50)")
        rl = agg[agg["metric"] == "replay_lag"]
        print("\nReplay send delay (ms), median over runs:", rl[["mode", "p50_median", "p99_median", "max_median"]].to_string(index=False))
        dec = agg[agg["metric"].isin(["transport_net", "scheduling", "execution", "rule_wait"])]
        print(dec.pivot_table(index="label", columns=["metric", "mode"], values="p50_median")
              .reindex([o for o in ORDER if o in set(dec["label"])]).to_string())
        print("\nFirings at microstep >= 1 / all firings (sum over runs)")
        e = esum.assign(cell=esum["fired_m_ge1"].astype(str) + "/" + esum["fired"].astype(str))
        print(e.pivot_table(index="label", columns=["platform", "mode"], values="cell", aggfunc="first")
              .reindex([o for o in ORDER if o in set(e["label"])]).to_string())
        print("\nDiscarded updates (sum over runs)")
        print(esum.pivot_table(index="label", columns=["platform", "mode"], values="discarded", aggfunc="sum")
              .reindex([o for o in ORDER if o in set(esum["label"])]).to_string())
        print("\nLoad indicators (median over runs; wall time, not CPU time)")
        print(load_tab[[c + "_median" for c in ("device_events_per_s", "firings_per_s", "exec_us_per_firing",
                                                 "exec_share_pct", "busy_share_pct", "gc_pause_ms_total",
                                                 "gc_pause_ms_max")]].to_string())
        print("\nSteady state (after --warmup-s), pooled over all firings of all runs")
        print(load_tab[["firings_per_s_steady_pooled", "busy_us_per_firing_steady_pooled",
                        "busy_share_pct_steady_pooled", "latency_mean_ms_steady_pooled"]].to_string())
        if "cpu_cores_steady_pooled" in load_tab:
            print("\nCPU of the JVM, steady state, pooled over runs (headroom = linear extrapolation)")
            print(load_tab[["ncpu", "cpu_cores_steady_pooled", "cpu_us_per_event_steady_pooled",
                            "cpu_us_per_firing_steady_pooled", "cpu_headroom_1core_x_steady_pooled",
                            "cpu_headroom_x_steady_pooled", "host_cpu_pct_steady_median",
                            "proc_rss_mb_max_max"]].to_string())
        if not ctrl.empty:
            print("\nRun-to-run |Δ| of trigger p50/p99 (ms), median over reactors and recordings")
            print(ctrl.groupby("contrast")[["d_p50", "d_p99"]].median().to_string())
    return 0


if __name__ == "__main__":
    sys.exit(main())
