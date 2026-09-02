#!/usr/bin/env python3
"""Tables for SocketPairThroughputBenchmark: one FRAMED instance publishing to another.

    python3 benchmark/analyse-socket-pair.py [csv]

Repeats of a point collapse to their median, with the min-max spread shown where they disagree.
"""
from __future__ import annotations

import argparse
import csv
import math
import statistics
import sys
from collections import OrderedDict
from pathlib import Path

NUM = {"repeat", "speed", "recordSeconds", "samplesPerFrame", "published", "received", "missing",
       "deliveryRatio", "offeredDpPerSec", "producerDpPerSec", "sinkDpPerSec", "sendFailures",
       "latP50Ms", "latP95Ms", "latP99Ms", "latMaxMs", "harnessWallMs", "peakHeapMb", "peakThreads"}
WIRINGS = ("LOCAL", "TCP", "UDP")


def load(path: Path) -> list[dict]:
    out = []
    for raw in csv.DictReader(path.open()):
        r = dict(raw)
        for k in NUM:
            v = r.get(k, "")
            r[k] = float(v) if v not in ("", None) else math.nan
        r["ok"] = r.get("ok", "true").lower() == "true"
        out.append(r)
    return out


def group(rows):
    g = OrderedDict()
    for r in rows:
        g.setdefault((r["experiment"], r["speed"], r["wiring"]), []).append(r)
    return g


def med(reps, key):
    v = [r[key] for r in reps if r["ok"] and not math.isnan(r[key])]
    return statistics.median(v) if v else math.nan


def spread(reps, key, digits=0):
    v = [r[key] for r in reps if r["ok"] and not math.isnan(r[key])]
    if not v:
        return "—"
    lo, hi, m = min(v), max(v), statistics.median(v)
    if f"{lo:.{digits}f}" == f"{hi:.{digits}f}":
        return f"{m:,.{digits}f}"
    return f"{m:,.{digits}f} ({lo:,.{digits}f}–{hi:,.{digits}f})"


def table(lines, header, body):
    lines.append("| " + " | ".join(header) + " |")
    lines.append("|" + "|".join("---" for _ in header) + "|")
    lines += ["| " + " | ".join(row) + " |" for row in body]
    lines.append("")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    # The post-fix sweep: socket-pair.csv beside it is the superseded pre-fix one, kept as data but
    # describing a transport that no longer exists.
    ap.add_argument("csv", nargs="?", default="benchmark/results/socket-pair-postfix.csv")
    args = ap.parse_args()
    path = Path(args.csv)
    if not path.is_file():
        print(f"no such CSV: {path}", file=sys.stderr)
        return 1

    rows = load(path)
    g = group(rows)
    out: list[str] = []
    failures = [r for r in rows if not r["ok"]]

    # ---- E1: offered load against each wiring -------------------------------------------
    speeds = sorted({k[1] for k in g if k[0] == "E1"})
    if speeds:
        out.append("### Delivery and throughput against offered load\n")
        body = []
        for sp in speeds:
            base = g.get(("E1", sp, "LOCAL"), [])
            offered = med(base, "offeredDpPerSec") if base else math.nan
            row = [f"{sp:,.0f}×", f"{offered:,.0f}" if not math.isnan(offered) else "—"]
            for w in WIRINGS:
                reps = g.get(("E1", sp, w), [])
                if not reps:
                    row += ["—", "—"]
                    continue
                row.append(spread(reps, "sinkDpPerSec"))
                row.append(f"{med(reps, 'deliveryRatio'):.4f}")
            body.append(row)
        table(out, ["speed", "offered dp/s",
                    "LOCAL dp/s", "LOCAL deliv.",
                    "TCP dp/s", "TCP deliv.",
                    "UDP dp/s", "UDP deliv."], body)

    # ---- E2 / E3 -------------------------------------------------------------------------
    for exp, title in (("E2", "Unpaced (flat out)"), ("E3", "Unprimed start at 1× real time")):
        keys = [k for k in g if k[0] == exp]
        if not keys:
            continue
        out.append(f"### {title}\n")
        body = []
        for w in WIRINGS:
            reps = next((g[k] for k in keys if k[2] == w), None)
            if not reps:
                continue
            body.append([w, spread(reps, "producerDpPerSec"), spread(reps, "sinkDpPerSec"),
                         f"{med(reps, 'deliveryRatio'):.4f}", f"{med(reps, 'missing'):,.0f}",
                         f"{med(reps, 'sendFailures'):,.0f}", f"{med(reps, 'latP95Ms'):,.0f}",
                         f"{med(reps, 'peakThreads'):,.0f}"])
        table(out, ["wiring", "producer dp/s", "delivered dp/s", "delivered/published",
                    "missing dp", "send failures", "p95 ms", "peak threads"], body)

    print("\n".join(out))

    print("\n--- derived ---", file=sys.stderr)
    for w in WIRINGS:
        tracked = [sp for sp in speeds
                   if g.get(("E1", sp, w)) and med(g[("E1", sp, w)], "deliveryRatio") >= 0.9999]
        best = max((med(g[("E1", sp, w)], "sinkDpPerSec") for sp in speeds if g.get(("E1", sp, w))),
                   default=math.nan)
        if tracked:
            top = max(tracked)
            rate = med(g[("E1", top, "LOCAL")], "offeredDpPerSec")
            print(f"{w}: lossless up to {top:,.0f}x (~{rate:,.0f} dp/s); best observed {best:,.0f} dp/s",
                  file=sys.stderr)
        else:
            print(f"{w}: never lossless in this sweep", file=sys.stderr)
    if failures:
        print(f"failed runs: {len(failures)}", file=sys.stderr)
        for f in failures[:5]:
            print(f"  {f['label']}: {f['failure']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
