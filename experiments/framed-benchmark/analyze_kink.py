#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Agreement and sink figures of the kink-over-TCP study written by ``run_kink.sh``.

Input is one or more results roots, each holding ``<LABEL>_runs.csv`` and ``<LABEL>_KINK/``. Only
units whose **last** attempt exited 0 are used.

Agreement: every pair of series is aligned with ``kink_series.compare`` (see there). Pair types:

======================  =================================================================
run_vs_control          distributed run r<k> vs the PER_HANDLER control r<k> of the same
                        recording, at speed 1 only: does splitting the deployment change the
                        output? Above speed 1 the Java replay stamps events at their emission
                        instant (recorded time / speed) while the control keeps the recorded
                        stamps, so the reactors' 6 s window spans different amounts of recording
                        and the two series legitimately differ.
run_vs_run              two distributed runs of the same recording and speed
control_vs_control      two controls of the same recording, speed and dispatch mode: the
                        reproducibility of the deployment itself, against which run_vs_control
                        is read (PER_HANDLER is not reproducible run to run; SEQUENTIAL is)
======================  =================================================================

Per pair: ``aligned_fraction`` = aligned instants / unique instants of the larger series;
``disagreement`` = ``Kink`` label mismatches / instants aligned on ``Kink``; ``|Δp|`` p50, p95 and
max over the instants aligned on ``Kink-Probability``.

Speed-up runs (speed s > 1) replay the recording s times back to back (``run_kink.sh``), so they
last as long as a real-time run at s times the rate; run_vs_run and control_vs_control compare them
at equal speed.

Sink figures (distributed runs): received, dropped, handler errors and the frame-to-verdict
latency of the ``CountingDispatcher`` (now − logical timestamp of the ventilator frame at the
sink: replay, TCP, three reactors, TCP back, sink queue), plus the replay's pacing.

Aggregation: per platform, pair type / speed and dispatch, the median over pairs (or runs) with a
bootstrap 95 % CI of the median (``--boot`` resamples, fixed seed).

Outputs (``--out``): ``kink_runs.csv``, ``kink_controls.csv``, ``kink_pairs.csv``,
``kink_agreement.csv``, ``kink_sink.csv``.

Usage::

    python analyze_kink.py --root out/kink-amd [--root out/kink-pi] --out out/analysis-kink

Requires Python >= 3.10, pandas and numpy.
"""

from __future__ import annotations

import argparse
import json
import sys
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd

from kink_series import compare, load_series, per_channel, scrape_java_log


def successful_units(root: Path) -> pd.DataFrame:
    """One row per unit whose last attempt exited 0, with its directory."""
    rows = []
    for runs_csv in sorted(root.glob("*_runs.csv")):
        r = pd.read_csv(runs_csv, dtype={"run": str, "dispatch": str})
        if "kind" not in r.columns:
            continue
        last = r.groupby(["label", "kind", "run"], sort=False).tail(1)
        for row in last.itertuples(index=False):
            if row.exit != 0:
                print(f"skipping {row.label} {row.kind} {row.run}: last attempt exited {row.exit}",
                      file=sys.stderr)
                continue
            d = root / f"{row.label}_KINK" / row.kind / row.run
            if not d.is_dir():
                print(f"skipping {row.label} {row.kind} {row.run}: no directory {d}", file=sys.stderr)
                continue
            rows.append({"platform": row.label, "kind": row.kind, "recording": row.recording,
                         "speed": float(row.speed), "dispatch": row.dispatch, "rep": int(row.rep),
                         "run": row.run, "dir": d})
    return pd.DataFrame(rows)


def series_of(u) -> list:
    if u.kind == "ctrl":
        return load_series(u.dir / "baseline.jsonl")
    captures = sorted((u.dir / "capture").glob("*_classifications.jsonl"))
    if len(captures) != 1:
        raise RuntimeError(f"{u.dir}: expected one capture, found {len(captures)}")
    return load_series(captures[0])


def pair_row(kind: str, a, b, sa: list, sb: list) -> dict:
    c = compare(sa, sb)
    larger = max(c["controlUnique"], c["observedUnique"])
    return {
        "platform": a.platform, "pair": kind, "recording": a.recording, "speed": a.speed,
        "dispatch": b.dispatch if b.kind == "ctrl" else a.dispatch if a.kind == "ctrl" else "",
        "a": f"{a.kind}/{a.run}", "b": f"{b.kind}/{b.run}",
        "unique_a": c["controlUnique"], "unique_b": c["observedUnique"],
        "collapsed_a": c["collapsedControl"], "collapsed_b": c["collapsedObserved"],
        "aligned": c["matchedCount"], "aligned_kink": c["matchedKink"],
        "aligned_fraction": c["matchedCount"] / larger if larger else np.nan,
        "only_in_a": len(c["missing"]), "only_in_b": len(c["extra"]),
        "label_mismatches": len(c["labelMismatches"]),
        "disagreement": len(c["labelMismatches"]) / c["matchedKink"] if c["matchedKink"] else np.nan,
        "dp_p50": c["probabilityDeltaP50"], "dp_p95": c["probabilityDeltaP95"],
        "dp_p99": c["probabilityDeltaP99"], "dp_max": c["probabilityDeltaMax"],
        "dp_over_0_1": c["probabilityDeltaOver01"],
    }


def boot_median(x: np.ndarray, n: int, rng: np.random.Generator) -> tuple[float, float, float]:
    x = x[~np.isnan(x)]
    if len(x) == 0:
        return (np.nan, np.nan, np.nan)
    if len(x) == 1 or n <= 0:
        return (float(np.median(x)), np.nan, np.nan)
    m = np.median(rng.choice(x, size=(n, len(x)), replace=True), axis=1)
    return (float(np.median(x)), float(np.percentile(m, 2.5)), float(np.percentile(m, 97.5)))


def aggregate(df: pd.DataFrame, by: list[str], cols: list[str], n_boot: int,
              extra: dict | None = None) -> pd.DataFrame:
    rng = np.random.default_rng(20261104)
    out = []
    for key, g in df.groupby(by, dropna=False):
        row = dict(zip(by, key if isinstance(key, tuple) else (key,)))
        row["n"] = len(g)
        for c in cols:
            med, lo, hi = boot_median(g[c].to_numpy(dtype=float), n_boot, rng)
            row[f"{c}_median"], row[f"{c}_ci_lo"], row[f"{c}_ci_hi"] = med, lo, hi
        for name, (col, fn) in (extra or {}).items():
            row[name] = g[col].agg(fn)
        out.append(row)
    return pd.DataFrame(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path, action="append")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--boot", type=int, default=2000)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    units = pd.concat([successful_units(r) for r in args.root], ignore_index=True)
    if units.empty:
        print("No successful units under", args.root, file=sys.stderr)
        return 1
    series = {(u.platform, u.kind, u.run): series_of(u) for u in units.itertuples(index=False)}

    # --- per unit ------------------------------------------------------------------------------
    runs, controls = [], []
    for u in units.itertuples(index=False):
        s = series[(u.platform, u.kind, u.run)]
        counts = per_channel(s)
        base = {"platform": u.platform, "recording": u.recording, "speed": u.speed, "rep": u.rep,
                "run": u.run, "kink": counts.get("Kink", 0),
                "kink_probability": counts.get("Kink-Probability", 0)}
        if u.kind == "dist":
            figures, summary, pacing = scrape_java_log(u.dir / "java.log")
            if summary is None:
                print(f"WARNING {u.run}: no CountingDispatcher summary", file=sys.stderr)
            runs.append({**base, **figures})
        else:
            meta = json.loads((u.dir / "baseline.jsonl.meta.json").read_text())
            controls.append({**base, "dispatch": u.dispatch, "events": meta.get("eventsReplayed"),
                             "coverage": meta.get("coverage"), "truncated": meta.get("truncated")})
    runs, controls = pd.DataFrame(runs), pd.DataFrame(controls)
    runs.to_csv(args.out / "kink_runs.csv", index=False)
    controls.to_csv(args.out / "kink_controls.csv", index=False)

    # --- pairs ---------------------------------------------------------------------------------
    pairs = []
    dist = units[units["kind"] == "dist"]
    ctrl = units[units["kind"] == "ctrl"]

    def s(u):
        return series[(u.platform, u.kind, u.run)]

    for d in dist.itertuples(index=False):
        if d.speed != 1:
            continue
        m = ctrl[(ctrl.platform == d.platform) & (ctrl.recording == d.recording)
                 & (ctrl.speed == d.speed) & (ctrl.dispatch == "PER_HANDLER") & (ctrl.rep == d.rep)]
        for c in m.itertuples(index=False):
            pairs.append(pair_row("run_vs_control", c, d, s(c), s(d)))
    for kind, frame, keys in (("run_vs_run", dist, ["platform", "recording", "speed"]),
                              ("control_vs_control", ctrl, ["platform", "recording", "speed", "dispatch"])):
        for _, g in frame.groupby(keys):
            for a, b in combinations(sorted(g.itertuples(index=False), key=lambda u: u.rep), 2):
                pairs.append(pair_row(kind, a, b, s(a), s(b)))
    pairs = pd.DataFrame(pairs)
    pairs.to_csv(args.out / "kink_pairs.csv", index=False)

    # --- aggregates ----------------------------------------------------------------------------
    if not pairs.empty:
        agree = aggregate(pairs, ["platform", "pair", "speed", "dispatch"],
                          ["aligned_fraction", "disagreement", "dp_p50", "dp_p95"], args.boot,
                          {"dp_max_max": ("dp_max", "max"),
                           "label_mismatches_sum": ("label_mismatches", "sum"),
                           "aligned_kink_sum": ("aligned_kink", "sum")})
        agree.to_csv(args.out / "kink_agreement.csv", index=False)
    if not runs.empty:
        lat = [c for c in ("latencyP50Ms", "latencyP95Ms", "latencyP99Ms", "latencyMaxMs",
                           "achievedDpPerS", "producerLagMaxMs") if c in runs.columns]
        sink = aggregate(runs, ["platform", "speed"], lat, args.boot,
                         {k: (k, "sum") for k in ("received", "dropped", "handlerErrors")
                          if k in runs.columns})
        sink.to_csv(args.out / "kink_sink.csv", index=False)

    with pd.option_context("display.width", 220, "display.max_columns", 40):
        if not pairs.empty:
            cols = ["platform", "pair", "speed", "dispatch", "n", "aligned_fraction_median",
                    "aligned_fraction_ci_lo", "aligned_fraction_ci_hi", "disagreement_median",
                    "dp_p95_median", "dp_max_max"]
            print("\nAgreement (median over pairs, bootstrap 95 % CI)")
            print(agree[cols].to_string(index=False))
        if not runs.empty:
            print("\nSink (median over distributed runs)")
            print(sink.to_string(index=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
