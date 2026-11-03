#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Diagnose the latency dispersion of the v03 CDSS replay experiments.

Reads the per-channel throughput-latency rows (``className == "Latency"``) that
``Reactor.publishPerChannelLatency`` wrote into the v03 replay outputs. These rows are the
basis of the v03 latency results. The script answers two questions:

1. Why is the standard deviation of some reactors (A4 SF-Ratio, A10 Hemodynamic Rhythm)
   orders of magnitude above their 99th percentile?
2. What is the latency of the Interpretation Reactor, which the v03 results excluded?

Each latency row is ``now - timestamp(input sample)`` at the first firing that consumed the
sample. All rows of one firing share the same row timestamp (the firing instant), so the
rows can be grouped back into firings.

Every value above ``--outlier-ms`` is classified, in this order:

``warmup``
    The firing lies in the first ``--warmup-s`` seconds of the run, or is one of the
    reactor's first ``--first-firings`` firings in the run (H1: start-up, second device
    not yet sending, JIT).
``deferred``
    Another input channel of the same firing has a latency below ``--trigger-ms``. That
    channel triggered the firing, and the outlier sample had been waiting for it (H2:
    the logical-time gate rejected the sample's own evaluation, or an AND rule waited).
``stall``
    No fast co-channel, but another reactor of the same run has an outlier within
    ``--stall-window-ms`` (H3: JVM or OS stall that delays everything).
``other``
    None of the above.

Input timestamps of downstream reactors are the logical timestamps of their upstream
reactors' outputs, so every value is cumulative from the source sample (H4). That shifts
means along the chain but produces no outliers on its own.

Usage::

    python diagnose_v03.py --data-root <dir with AMD_ATOMIC, ...> --out <dir> [--workers 8]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

EXPERIMENTS = ["AMD_ATOMIC", "AMD_NON_ATOMIC", "PI_ATOMIC", "PI_NON_ATOMIC"]

# Reactor ids of the deployed network -> labels A1-A13.
ACTOR_LABEL = {
    "etCO2-Limit-Classifier": "A1",
    "SpO2-Limit-Classifier": "A2",
    "PI-Limit-Classifier": "A3",
    "SF-Computation-Reactor": "A4",
    "S/F-Limit-Classifier": "A5",
    "HR-Limit-Classifier": "A6",
    "spo2-Trend-Classifier": "A7",
    "etCO2-Trend-Classifier": "A8",
    "Dislocation-Classifier": "A9",
    "Hemodynamic arrhythmia": "A10",
    "RespiratoryRateEstimator": "A11",
    "RRMismatchClassifier": "A12",
    "Interpreter": "Interpreter",
}

LATENCY_MARK = '"className":"Latency",'
RUN_NAME = re.compile(r"^\d+_(?P<run>.+)_replay\.jsonl$")


def read_run(path: str) -> tuple[pd.DataFrame, dict]:
    """Returns the latency rows of one replay output plus per-run device start times."""
    first_ts = None
    first_dev: dict[str, str] = {}
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if LATENCY_MARK in line:
                rec = json.loads(line)
                rows.append((rec["channelID"], rec["value"], rec["timestamp"]))
                continue
            if first_ts is None or len(first_dev) < 2:
                rec = json.loads(line)
                if first_ts is None:
                    first_ts = rec["timestamp"]
                dev = rec.get("deviceID")
                if dev in ("PC60FW", "Oxylog-3000-Plus-00") and dev not in first_dev:
                    first_dev[dev] = rec["timestamp"]

    run = RUN_NAME.match(os.path.basename(path)).group("run")
    df = pd.DataFrame(rows, columns=["channelID", "value", "ts"])
    if df.empty:
        return df, {"run": run}
    ch = df["channelID"].str.removeprefix("Latency-")
    df["actor"] = ch.str.rsplit(".", n=1).str[-1]
    df["input"] = ch.str.rsplit(".", n=1).str[0]
    df["value_ms"] = pd.to_numeric(df["value"], errors="coerce") * 1e3
    df["ts"] = pd.to_datetime(df["ts"], utc=True, format="ISO8601")
    t0 = pd.to_datetime(first_ts, utc=True, format="ISO8601")
    df["t_rel_s"] = (df["ts"] - t0).dt.total_seconds()
    df["run"] = run
    meta = {"run": run, "t0": t0}
    if len(first_dev) == 2:
        pc = pd.to_datetime(first_dev["PC60FW"], utc=True, format="ISO8601")
        oxy = pd.to_datetime(first_dev["Oxylog-3000-Plus-00"], utc=True, format="ISO8601")
        meta["oxylog_minus_pc60fw_s"] = (oxy - pc).total_seconds()
    return df.drop(columns=["channelID", "value"]), meta


def load_experiment(exp_dir: Path, workers: int) -> tuple[pd.DataFrame, pd.DataFrame]:
    paths = sorted(str(p) for p in exp_dir.glob("*_replay.jsonl"))
    with Pool(workers) as pool:
        parts = pool.map(read_run, paths, chunksize=2)
    lat = pd.concat([p[0] for p in parts if not p[0].empty], ignore_index=True)
    meta = pd.DataFrame([p[1] for p in parts])
    for col in ("actor", "input", "run"):
        lat[col] = lat[col].astype("category")
    return lat, meta


def pooled_stats(lat: pd.DataFrame) -> pd.DataFrame:
    """Pooled per-actor statistics, computed as in the v03 analysis (population std)."""
    def agg(s: pd.Series) -> pd.Series:
        v = s.to_numpy(dtype=float)
        v = v[~np.isnan(v)]
        cut = np.quantile(v, 0.9999)
        dev2 = (v - v.mean()) ** 2
        return pd.Series({
            "n": v.size,
            "mean_ms": v.mean(),
            "std_ms": v.std(),
            "p50_ms": np.quantile(v, 0.5),
            "p99_ms": np.quantile(v, 0.99),
            "max_ms": v.max(),
            "std_without_top_0.01pct_ms": v[v <= cut].std(),
            "share_of_variance_top_0.01pct": dev2[v > cut].sum() / dev2.sum() if dev2.sum() else 0.0,
        })
    return lat.groupby("actor", observed=True)["value_ms"].apply(agg).unstack()


def per_run_std(lat: pd.DataFrame) -> pd.DataFrame:
    """Distribution of the per-run std over runs, per actor."""
    s = lat.groupby(["actor", "run"], observed=True)["value_ms"].std(ddof=0)
    return s.groupby(level="actor", observed=True).describe(percentiles=[0.5, 0.9, 0.99])


def classify_outliers(lat: pd.DataFrame, args) -> pd.DataFrame:
    """Labels every latency above the outlier threshold with a cause hypothesis."""
    lat = lat.copy()
    # firing rank of each row within (run, actor); rows of one firing share ts
    firing_ts = lat.groupby(["run", "actor"], observed=True)["ts"].rank(method="dense")
    lat["firing_rank"] = firing_ts.astype(int)
    fastest = lat.groupby(["run", "actor", "ts"], observed=True)["value_ms"].transform("min")
    lat["firing_min_ms"] = fastest

    out = lat[lat["value_ms"] > args.outlier_ms].copy()
    if out.empty:
        return out

    # other actors' outliers in the same run, within the stall window
    window_ns = int(args.stall_window_ms * 1e6)
    stall = np.zeros(len(out), dtype=bool)
    for run, grp in out.groupby("run", observed=True):
        ts = grp["ts"].astype("int64").to_numpy()
        actors = grp["actor"].to_numpy()
        order = np.argsort(ts)
        ts_s, act_s = ts[order], actors[order]
        lo = np.searchsorted(ts_s, ts_s - window_ns, side="left")
        hi = np.searchsorted(ts_s, ts_s + window_ns, side="right")
        flags = np.array([np.any(act_s[a:b] != act_s[i]) for i, (a, b) in enumerate(zip(lo, hi))])
        res = np.empty_like(flags)
        res[order] = flags
        stall[out.index.get_indexer(grp.index)] = res

    warm = (out["t_rel_s"] < args.warmup_s) | (out["firing_rank"] <= args.first_firings)
    deferred = (out["firing_min_ms"] < args.trigger_ms) & (out["value_ms"] > out["firing_min_ms"])
    out["cause"] = np.select(
        [warm, deferred, stall], ["warmup", "deferred", "stall"], default="other")
    return out


def cause_summary(lat: pd.DataFrame, out: pd.DataFrame) -> pd.DataFrame:
    """Per actor and cause: outlier count and share of the actor's total squared deviation."""
    mean = lat.groupby("actor", observed=True)["value_ms"].mean()
    total = lat.assign(d2=(lat["value_ms"] - lat["actor"].map(mean).astype(float)) ** 2) \
        .groupby("actor", observed=True)["d2"].sum()
    out = out.assign(d2=(out["value_ms"] - out["actor"].map(mean).astype(float)) ** 2)
    g = out.groupby(["actor", "cause"], observed=True).agg(
        n=("value_ms", "size"),
        median_ms=("value_ms", "median"),
        max_ms=("value_ms", "max"),
        median_t_rel_s=("t_rel_s", "median"),
        d2=("d2", "sum"),
    )
    g["share_of_variance"] = g["d2"] / g.index.get_level_values("actor").map(total).to_numpy()
    return g.drop(columns="d2")


def interpreter_latency(lat: pd.DataFrame) -> pd.DataFrame:
    """End-to-end latency source sample -> Interpreter firing, pooled and per run."""
    itp = lat[lat["actor"] == "Interpreter"]
    if itp.empty:
        return pd.DataFrame()
    v = itp["value_ms"].to_numpy(dtype=float)
    per_run = itp.groupby("run", observed=True)["value_ms"]
    return pd.DataFrame([{
        "n": v.size,
        "mean_ms": v.mean(),
        "std_ms": v.std(),
        "p50_ms": np.quantile(v, 0.5),
        "p99_ms": np.quantile(v, 0.99),
        "max_ms": v.max(),
        "median_of_run_p50_ms": per_run.median().median(),
        "median_of_run_p99_ms": per_run.quantile(0.99).median(),
    }])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--data-root", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--experiments", nargs="*", default=EXPERIMENTS)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--outlier-ms", type=float, default=10.0)
    ap.add_argument("--trigger-ms", type=float, default=5.0)
    ap.add_argument("--warmup-s", type=float, default=30.0)
    ap.add_argument("--first-firings", type=int, default=3)
    ap.add_argument("--stall-window-ms", type=float, default=100.0)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    tables = {k: [] for k in ("pooled", "per_run_std", "causes", "interpreter", "meta", "outliers")}
    for exp in args.experiments:
        print(f"[{exp}] loading", file=sys.stderr)
        lat, meta = load_experiment(args.data_root / exp, args.workers)
        out = classify_outliers(lat, args)
        for key, df in (
                ("pooled", pooled_stats(lat)),
                ("per_run_std", per_run_std(lat)),
                ("causes", cause_summary(lat, out)),
                ("interpreter", interpreter_latency(lat)),
                ("meta", meta),
                ("outliers", out.drop(columns=["firing_min_ms"]))):
            df = df.reset_index() if key not in ("interpreter", "meta", "outliers") else df
            tables[key].append(df.assign(experiment=exp))

    for key, parts in tables.items():
        df = pd.concat(parts, ignore_index=True)
        if "actor" in df:
            df.insert(0, "label", df["actor"].astype(str).map(ACTOR_LABEL))
        df.to_csv(args.out / f"{key}.csv", index=False)

    pooled = pd.concat(tables["pooled"], ignore_index=True)
    pooled["label"] = pooled["actor"].astype(str).map(ACTOR_LABEL)
    with pd.option_context("display.width", 200, "display.max_columns", 20,
                           "display.float_format", "{:.3f}".format):
        print(pooled.pivot(index="label", columns="experiment", values="std_ms"))
        causes = pd.concat(tables["causes"], ignore_index=True)
        causes["label"] = causes["actor"].astype(str).map(ACTOR_LABEL)
        print(causes[causes["label"].isin(["A4", "A5", "A9", "A10", "Interpreter"])]
              .drop(columns="actor").to_string(index=False))
        print(pd.concat(tables["interpreter"], ignore_index=True).to_string(index=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
