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
end_to_end         reaction end − logical timestamp of the snapshot (newest input)
transport_net      arrival of the triggering message − actual send instant
=================  ===========================================================

The replay stamps each event with its *scheduled* instant, so ``trigger`` and ``transport``
include the replay thread's wake-up delay. ``ReplayProtocol`` logs the actual send instant of
every event (``metrics/<run>_emits.csv``); the ``*_net`` metrics subtract it. Downstream
reactors inherit the source sample's timestamp, so their triggering instant is matched to the
first send of that scheduled instant. ``replay_lag`` (send − schedule, reactor ``Replay``)
quantifies the delay itself.

Per consumed input of a FIRED evaluation:

=================  ===========================================================
rule_wait          firing decision − arrival of that input
consumption        reaction end − timestamp of that input, at its first consumption.
                   This is comparable to the per-channel ``Latency`` rows of the v03 runs.
consumption_net    reaction end − actual send instant of that input, at its first
                   consumption (excludes the replay thread's wake-up delay, as
                   ``trigger_net``; matched to the send log like the triggering instant)
=================  ===========================================================

An input's first consumption is taken over *all* recorded evaluations. Inputs first taken by a
GATED evaluation are excluded from ``consumption`` and ``consumption_net``: that evaluation
published nothing, and the next FIRED evaluation that still holds the value in its snapshot can be
seconds later, which would be reported as consumption latency although the input was taken
promptly. Gated evaluations are reported in ``evaluations.csv``.

Summaries are computed **per run** (n, mean, std, p50, p90, p99, p99.9, max) and then aggregated
over runs: the median of the per-run values, with a percentile-bootstrap 95 % CI over runs.
``summary.csv`` additionally reports the **pooled** mean and standard deviation over all samples
of all runs (``mean_pooled``, ``std_pooled``; sample std, ddof = 1), as reported in the v03
manuscript. They are derived exactly from the per-run n, mean and std. Both are dominated by a few
deferred samples (see ``out/diagnose_v03/FINDINGS.md``); the percentiles are the primary measure.

Further outputs:

- ``evaluations.csv``: fired, gated and no-rule evaluations per reactor and run (recorder
  summary), with the gated share;
- ``interpreter_status.csv``: Interpreter trigger latency per status (WARNING, NO_WARNING,
  SUPPRESSED), joined by firing order;
- ``control.csv``: for recordings replayed more than once, the run-to-run differences
  (control vs control) next to the atomic vs non-atomic differences;
- ``warmup_sensitivity.csv``: trigger-latency summaries with and without the first
  ``--warmup-s`` seconds of each run;
- ``consumption_net.csv``: the ``consumption`` and ``consumption_net`` rows of ``summary.csv``
  side by side per reactor (median over runs of the per-run p50, p99, p99.9 and max), with
  the wake-up correction ``consumption − consumption_net`` at p50.

Usage::

    python analyze_latency.py --root out/pilot --out out/pilot/analysis [--warmup-s 30]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from framed_runs import LABELS, ORDER, STATUS_CHANNEL, Run, discover

QUANTILES = {"p50": 0.5, "p90": 0.9, "p99": 0.99, "p99.9": 0.999}


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

    per_run, evals, status_rows, warm = [], [], [], []
    for r in discover(args.root):
        key = r.key
        f, fired, j, first, s, lag = load_run(r)
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
        for _, row in s[s["reactor"] != "*dropped*"].iterrows():
            fired_n, gated_n = int(row["fired"]), int(row["gated"] or 0)
            evals.append({**key, "reactor": row["reactor"], "fired": fired_n, "gated": gated_n,
                          "no_rule": int(row["no_rule"] or 0),
                          "gated_share": gated_n / (fired_n + gated_n) if fired_n + gated_n else np.nan})
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
    per_run.insert(0, "label", per_run["reactor"].map(LABELS))
    per_run.to_csv(args.out / "per_run.csv", index=False)

    rows = []
    for (platform, mode, reactor, metric), g in per_run.groupby(["platform", "mode", "reactor", "metric"]):
        row = {"platform": platform, "mode": mode, "label": LABELS.get(reactor), "reactor": reactor,
               "metric": metric, "runs": len(g), "n": int(g["n"].sum())}
        row["mean_pooled"], row["std_pooled"] = pooled_mean_std(
            g["n"].to_numpy(float), g["mean"].to_numpy(float), g["std"].to_numpy(float))
        for q in ("mean", "std", "p50", "p99", "p99.9", "max"):
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
    pd.DataFrame(status_rows).to_csv(args.out / "interpreter_status.csv", index=False)
    pd.DataFrame(warm).to_csv(args.out / "warmup_sensitivity.csv", index=False)

    # consumption latency gross vs net of the replay wake-up delay, side by side
    keys = ["platform", "mode", "label", "reactor"]
    cons = agg[agg["metric"].isin(["consumption", "consumption_net"])]
    cons = cons.pivot_table(index=keys, columns="metric",
                            values=["n", "mean_pooled", "std_pooled", "mean_median", "std_median",
                                    "p50_median", "p99_median", "p99.9_median", "max_median"])
    cons.columns = [f"{m}_{v.removesuffix('_median')}" for v, m in cons.columns]
    stats = ["n", "mean_pooled", "std_pooled", "mean", "std", "p50", "p99", "p99.9", "max"]
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
        print("\nGated share of rule-satisfying evaluations (median over runs)")
        print(evals.pivot_table(index="reactor", columns="mode", values="gated_share", aggfunc="median").to_string())
        if not ctrl.empty:
            print("\nRun-to-run |Δ| of trigger p50/p99 (ms), median over reactors and recordings")
            print(ctrl.groupby("contrast")[["d_p50", "d_p99"]].median().to_string())
    return 0


if __name__ == "__main__":
    sys.exit(main())
