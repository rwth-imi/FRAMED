# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Shared helpers of the ARN experiment analyses (``analyze_latency.py``,
``analyze_ordering.py``, ``analyze_consumption.py``).

Layout written by ``run_replays.sh`` under a results root::

    <root>/<LABEL>_runs.csv                     one row per attempt (exit code, provenance)
    <root>/<LABEL>_manifest.txt
    <root>/<LABEL>_<ATOMIC|NON_ATOMIC>/
        <epochms>_<run>_replay.jsonl            bus output (inputs + reactor outputs)
        metrics/<run>_{firings,inputs,summary,emits}.csv
        config/<run>_{services,communication}.json
        gc/<run>.log

A run is ``<recording>_r<k>``. Only runs whose **last** attempt exited 0 are analysed, so the
partial outputs of a failed or interrupted attempt never enter the results, while a run that
was repeated successfully after a resume is kept.
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

LABELS = {
    "etCO2-Limit-Classifier": "A1", "SpO2-Limit-Classifier": "A2", "PI-Limit-Classifier": "A3",
    "SF-Computation-Reactor": "A4", "S/F-Limit-Classifier": "A5", "HR-Limit-Classifier": "A6",
    "spo2-Trend-Classifier": "A7", "etCO2-Trend-Classifier": "A8", "Dislocation-Classifier": "A9",
    "Hemodynamic arrhythmia": "A10", "RespiratoryRateEstimator": "A11",
    "RRMismatchClassifier": "A12", "Interpreter": "A13",
}
ORDER = [f"A{i}" for i in range(1, 14)]
STATUS_CHANNEL = "Interpretation-Status"
RUN_RE = re.compile(r"^(?P<recording>.+)_r(?P<rep>\d+)$")


@dataclass(frozen=True)
class Run:
    """One successful replay of one recording in one execution mode."""
    pass_dir: Path
    platform: str
    mode: str
    run: str
    recording: str
    rep: int

    @property
    def key(self) -> dict:
        """Identifying columns of every per-run output row."""
        return {"platform": self.platform, "mode": self.mode,
                "recording": self.recording, "rep": self.rep}

    def metrics(self, kind: str) -> Path:
        return self.pass_dir / "metrics" / f"{self.run}_{kind}.csv"

    def config(self, kind: str = "services") -> Path:
        return self.pass_dir / "config" / f"{self.run}_{kind}.json"

    def replay(self) -> Path | None:
        """The bus output; the JSON-Lines sink prefixes its name with a millisecond timestamp."""
        pat = re.compile(rf"^\d+_{re.escape(self.run)}_replay\.jsonl$")
        hits = sorted(p for p in self.pass_dir.glob(f"*_{self.run}_replay.jsonl") if pat.match(p.name))
        if len(hits) > 1:
            print(f"WARNING {self.pass_dir.name}/{self.run}: {len(hits)} replay files, using the last",
                  file=sys.stderr)
        return hits[-1] if hits else None


def successful_runs(runs_csv: Path, mode: str) -> set[str] | None:
    """Runs of one pass whose last attempt exited 0; ``None`` if the runs file is absent."""
    if not runs_csv.is_file():
        return None
    r = pd.read_csv(runs_csv)
    r = r[r["mode"].str.upper() == mode.upper()].groupby("run").tail(1)
    return set(r.loc[r["exit"] == 0, "run"])


def discover(root: Path) -> list[Run]:
    """All analysable runs under ``root``, ordered by pass and run name.

    A run is discovered by its recorder summary (``metrics/<run>_summary.csv``) and dropped
    unless its last attempt exited 0 according to ``<LABEL>_runs.csv``.
    """
    runs = []
    for pass_dir in sorted(p for p in root.iterdir() if (p / "metrics").is_dir()):
        platform, _, mode = pass_dir.name.partition("_")
        ok = successful_runs(root / f"{platform}_runs.csv", mode)
        for summary in sorted((pass_dir / "metrics").glob("*_summary.csv")):
            run = summary.name.removesuffix("_summary.csv")
            if ok is not None and run not in ok:
                print(f"skipping {pass_dir.name}/{run}: last attempt did not exit 0", file=sys.stderr)
                continue
            m = RUN_RE.match(run)
            recording, rep = (m["recording"], int(m["rep"])) if m else (run, 0)
            runs.append(Run(pass_dir, platform, mode, run, recording, rep))
    return runs


def load_replay(path: Path) -> pd.DataFrame:
    """One bus output in file (= reception) order, with UTC timestamps and the tag's microstep.

    A line without ``microstep`` (device samples of runs recorded before the sink wrote the
    field) has microstep 0.
    """
    with open(path, encoding="utf-8") as fh:
        rows = [json.loads(line) for line in fh if line.strip()]
    df = pd.DataFrame(rows, columns=["className", "value", "deviceID", "channelID", "timestamp",
                                     "microstep"])
    df["timestamp"] = pd.to_datetime(df["timestamp"], format="ISO8601", utc=True)
    df["microstep"] = pd.to_numeric(df["microstep"], errors="coerce").fillna(0).astype("int64")
    return df


def tag_flags(df: pd.DataFrame, keys: list[str]) -> pd.DataFrame:
    """Per row, the tag ``(timestamp, microstep)`` against earlier rows of the same channel.

    Rows are taken in file order; tags are compared lexicographically (Definitions 2 and 5):

    - ``older``: the tag is strictly smaller than that of the previous row (out of order);
    - ``equal``: the tag equals that of the previous row;
    - ``accepted``: the tag is strictly greater than every earlier tag of the channel, i.e. a
      consumer applying the per-channel logical-time gate in this order would accept it.
    """
    t = df["timestamp"].astype("int64").to_numpy()
    m = df["microstep"].astype("int64").to_numpy()
    # dense rank of the tag over the frame: equal tags share a rank, order is lexicographic
    order = np.lexsort((m, t))
    new = np.ones(len(order), dtype=bool)
    new[1:] = (np.diff(t[order]) != 0) | (np.diff(m[order]) != 0)
    rank = np.empty(len(order), dtype="int64")
    rank[order] = np.cumsum(new)
    rank = pd.Series(rank, index=df.index)
    by = [df[k] for k in keys]
    prev = rank.groupby(by, sort=False).shift()
    best_before = rank.groupby(by, sort=False).cummax().groupby(by, sort=False).shift()
    return pd.DataFrame({"older": (rank < prev).to_numpy(),
                         "equal": (rank == prev).to_numpy(),
                         "accepted": (best_before.isna() | (rank > best_before)).to_numpy()},
                        index=df.index)


def order_labels(labels) -> list[str]:
    """``labels`` sorted A1 … A13, unknown labels last."""
    rank = {k: i for i, k in enumerate(ORDER)}
    return sorted(set(labels), key=lambda x: rank.get(x, len(rank)))
