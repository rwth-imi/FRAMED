#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Ordering correctness of the bus outputs written by ``run_replays.sh``.

Input is a results root (layout in ``framed_runs.py``); only runs whose last attempt exited 0
are used. For every channel ``(deviceID, className, channelID)`` of a run's bus output, taken in
file order (the order in which the JSON-Lines sink received the events), each event's tag
``(timestamp, microstep)`` is compared lexicographically with that of the previous event on the
same channel (Definition 5). Device samples carry microstep 0; a line without ``microstep``
(runs recorded before the sink wrote it) is read as microstep 0.

=================  ===========================================================
out_of_order       events whose tag is strictly smaller than that of the previous event on
                   the same channel
ties               events whose tag equals that of the previous event on the same channel.
                   Not out of order. Reactor output tags strictly increase, so ties occur on
                   device channels (duplicate device timestamps in the recordings)
total              events on the channel (K in Definition 5)
=================  ===========================================================

Ordering correctness ``ord = 1 − out_of_order / total`` (Definition 5); the out-of-order rate in
parts per million is ``(1 − ord) · 10^6``.

Scope ``reactor`` = CDSS outputs (``deviceID == "CDSS"``); scope
``device`` = replayed device channels (supplementary; checks the replay itself).

Outputs (``--out``):

- ``ordering_per_run.csv``: one row per run and channel;
- ``ordering.csv``: per platform, mode and reactor (or device class): sums over runs, ordering
  correctness ``100 · ord`` with ``ord = 1 − out_of_order / total``, the out-of-order rate
  ``(1 − ord) · 10^6`` ppm, and the number of runs with any out-of-order event.

Usage::

    python analyze_ordering.py --root out/full-amd --out out/full-amd/analysis [--workers 8]

Requires Python >= 3.10, pandas and numpy.
"""

from __future__ import annotations

import argparse
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import pandas as pd

from framed_runs import LABELS, Run, discover, load_replay, order_labels, tag_flags

KEYS = ["deviceID", "className", "channelID"]


def ordering_of_run(r: Run) -> list[dict]:
    """Out-of-order and tie counts per channel of one run, tags in file order."""
    path = r.replay()
    if path is None:
        print(f"WARNING {r.pass_dir.name}/{r.run}: no replay output", file=sys.stderr)
        return []
    d = load_replay(path)
    d = d[~d["className"].astype(str).str.contains("Latency", na=False)]
    tf = tag_flags(d, KEYS)
    flags = pd.DataFrame({"ooo": tf["older"], "tie": tf["equal"]})
    agg = pd.concat([d[KEYS], flags], axis=1).groupby(KEYS, sort=False).agg(
        out_of_order=("ooo", "sum"), ties=("tie", "sum"), total=("ooo", "size"))
    return [{**r.key, "scope": "reactor" if dev == "CDSS" else "device", "deviceID": dev,
             "className": cls, "channelID": ch, "out_of_order": int(row.out_of_order),
             "ties": int(row.ties), "total": int(row.total)}
            for (dev, cls, ch), row in agg.iterrows()]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    runs = discover(args.root)
    if not runs:
        print("No runs found under", args.root, file=sys.stderr)
        return 1
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        per_run = pd.DataFrame([row for rows in pool.map(ordering_of_run, runs) for row in rows])
    per_run.to_csv(args.out / "ordering_per_run.csv", index=False)

    per_run["any_ooo"] = per_run["out_of_order"] > 0
    run_flags = (per_run.groupby(["platform", "mode", "scope", "className", "recording", "rep"])
                 ["any_ooo"].any().groupby(["platform", "mode", "scope", "className"]).sum())
    agg = (per_run.groupby(["platform", "mode", "scope", "className"])
           [["out_of_order", "ties", "total"]].sum())
    agg["runs"] = per_run.groupby(["platform", "mode", "scope", "className"])[["recording", "rep"]] \
        .apply(lambda g: len(g.drop_duplicates()))
    agg["runs_with_ooo"] = run_flags
    agg = agg.reset_index()
    agg.insert(3, "label", agg["className"].map(LABELS).where(agg["scope"] == "reactor", "device"))
    ord_ = 1 - agg["out_of_order"] / agg["total"]
    agg["ordering_correctness_pct"] = 100.0 * ord_
    agg["out_of_order_ppm"] = (1 - ord_) * 1e6
    agg.to_csv(args.out / "ordering.csv", index=False)

    with pd.option_context("display.width", 220, "display.max_columns", 30):
        r = agg[agg["scope"] == "reactor"].copy()
        r["cell"] = r["out_of_order"].astype(str) + "/" + r["total"].astype(str)
        p = r.pivot_table(index="label", columns="mode", values="cell", aggfunc="first")
        print("\nOrdering: out-of-order / total reactor outputs")
        print(p.reindex(order_labels(r["label"])).to_string())
        t = r.pivot_table(index="label", columns="mode", values="ties", aggfunc="sum")
        print("\nEqual consecutive tags (ties, not out of order)")
        print(t.reindex(order_labels(r["label"])).to_string())
        for (platform, mode, scope), g in agg.groupby(["platform", "mode", "scope"]):
            n_ooo, n = int(g["out_of_order"].sum()), int(g["total"].sum())
            print(f"  {platform} {mode:>10} {scope:>7}: {n_ooo}/{n} out of order "
                  f"-> {100 * (1 - n_ooo / n):.4f}% correct")
    return 0


if __name__ == "__main__":
    sys.exit(main())
