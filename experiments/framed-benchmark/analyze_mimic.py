#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Merges and compares the MIMIC throughput and pacing results written by ``run_mimic.sh``.

Input is one or more results roots, each holding ``<LABEL>_runs.csv`` and ``<LABEL>_MIMIC/``. Only
units whose **last** attempt exited 0 are used.

Outputs (``--out``):

- ``<LABEL>_r<k>_study_a.csv``, ``…_study_b.csv``, ``…_pacing.csv``, ``…_full_app.csv``: one
  merged CSV per platform, sweep and study, in the benchmark's own column layout. These are the
  inputs of ``tables_study_a.py``, ``tables_study_b.py`` and ``make_figures.py``;
- ``<LABEL>_r<k>_study_a.md``, ``…_study_b.md``: the tables, rendered by those scripts;
- ``sweeps.csv``: per platform, study, experiment and operating point, the median over the
  within-sweep repeats for every sweep, and the relative difference of each later sweep to
  sweep r0. This is the run-to-run reference: a platform difference (AMD vs Pi) is only meaningful
  where it clearly exceeds the difference between two sweeps of one platform.

Usage::

    python analyze_mimic.py --root out/mimic-amd [--root out/mimic-pi] --out out/analysis-mimic

Requires Python >= 3.10, pandas and numpy.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent

#: Per study: the columns identifying an operating point, and the metrics compared across sweeps.
POINT = {
    "study_a": (["experiment", "label"],
                ["deliveredDpPerSec", "sinkSustainedDpPerSec", "deliveryRatio", "latP50Ms",
                 "latP99Ms", "peakHeapMb"]),
    "study_b": (["experiment", "label"],
                ["sinkDpPerSec", "deliveryRatio", "latP50Ms", "latP99Ms", "peakThreads"]),
    "pacing": (["speed"], None),
    "full_app": (["speed"], ["producerAchievedFrameHz", "sinkAchievedDpPerSec", "latP50Ms",
                             "latP99Ms", "sinkDropped"]),
}
STUDY_DIR = {"study_a": "study-a", "study_b": "study-b", "pacing": "pacing", "full_app": "full-app"}


def successful(root: Path) -> pd.DataFrame:
    rows = []
    for runs_csv in sorted(root.glob("*_runs.csv")):
        r = pd.read_csv(runs_csv, dtype={"unit": str, "run": str})
        if "study" not in r.columns:
            continue
        last = r.groupby(["label", "run"], sort=False).tail(1)
        for row in last.itertuples(index=False):
            if row.exit != 0:
                print(f"skipping {row.label} {row.run}: last attempt exited {row.exit}", file=sys.stderr)
                continue
            csv = root / f"{row.label}_MIMIC" / row.study / f"{row.run}.csv"
            if not csv.is_file():
                print(f"skipping {row.label} {row.run}: no {csv}", file=sys.stderr)
                continue
            rows.append({"platform": row.label, "study": row.study.replace("-", "_"),
                         "unit": row.unit, "sweep": int(row.rep), "csv": csv})
    return pd.DataFrame(rows)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path, action="append")
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    units = pd.concat([successful(r) for r in args.root], ignore_index=True)
    if units.empty:
        print("No successful units under", args.root, file=sys.stderr)
        return 1

    merged: dict[tuple[str, int, str], pd.DataFrame] = {}
    for (platform, sweep, study), g in units.groupby(["platform", "sweep", "study"]):
        frame = pd.concat([pd.read_csv(p) for p in g["csv"]], ignore_index=True)
        merged[(platform, sweep, study)] = frame
        path = args.out / f"{platform}_r{sweep}_{study}.csv"
        frame.to_csv(path, index=False)
        script = {"study_a": "tables_study_a.py", "study_b": "tables_study_b.py"}.get(study)
        if script:
            md = path.with_suffix(".md")
            cmd = [sys.executable, str(HERE / script), str(path)]
            if study == "study_a":
                cmd += ["--out", str(md)]
                subprocess.run(cmd, check=True, stderr=subprocess.DEVNULL)
            else:
                md.write_text(subprocess.run(cmd, check=True, capture_output=True, text=True).stdout)
        print(f"wrote {path.name} ({len(frame)} rows)")

    # --- sweep-to-sweep differences ------------------------------------------------------------
    rows = []
    for (platform, sweep, study), frame in merged.items():
        keys, metrics = POINT[study]
        if metrics is None:
            metrics = [c for c in frame.columns if c not in keys
                       and pd.api.types.is_numeric_dtype(frame[c]) and not pd.api.types.is_bool_dtype(frame[c])]
        metrics = [m for m in metrics if m in frame.columns]
        med = frame.groupby(keys, sort=False)[metrics].median(numeric_only=True).reset_index()
        for rec in med.to_dict("records"):
            point = " ".join(f"{k}={rec[k]}" for k in keys)
            for m in metrics:
                rows.append({"platform": platform, "study": study, "point": point,
                             "metric": m, "sweep": sweep, "median": rec[m]})
    if rows:
        long = pd.DataFrame(rows)
        wide = long.pivot_table(index=["platform", "study", "point", "metric"], columns="sweep",
                                values="median", aggfunc="first")
        wide.columns = [f"r{c}" for c in wide.columns]
        for c in wide.columns[1:]:
            wide[f"{c}_vs_r0_pct"] = 100.0 * (wide[c] - wide["r0"]) / wide["r0"].abs()
        wide.reset_index().to_csv(args.out / "sweeps.csv", index=False)
        print(f"wrote sweeps.csv ({len(wide)} rows)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
