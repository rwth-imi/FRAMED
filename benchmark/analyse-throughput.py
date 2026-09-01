#!/usr/bin/env python3
"""Turns MimicThroughputBenchmark's CSV into the case-study tables and figures.

    python3 benchmark/analyse-throughput.py [csv] [--figures DIR]

Repeats of the same operating point are collapsed to their median, with the min-max spread
reported alongside so run-to-run variation stays visible. Figures are optional: without
matplotlib the tables are still written.
"""
from __future__ import annotations

import argparse
import csv
import math
import statistics
import sys
from collections import OrderedDict
from pathlib import Path

NUMERIC = {
    "repeat", "devices", "sinksPerDevice", "speed", "recordSeconds", "channels",
    "samplesPerFrame", "publishedDatapoints", "expectedDeliveries", "receivedDatapoints",
    "undelivered", "deliveryRatio", "backlogAtProducerEnd", "dropped", "handlerErrors",
    "offeredDpPerSec", "producerDpPerSec", "deliveredDpPerSec", "sinkSustainedDpPerSec",
    "latP50Ms", "latP95Ms", "latP99Ms", "latMaxMs", "lagP95Ms", "lagMaxMs",
    "framesBehind", "frames", "producerWallMs", "harnessWallMs", "peakHeapMb", "peakThreads",
    "gcCount", "gcMillis",
}


def load(path: Path) -> list[dict]:
    rows = []
    with path.open() as fh:
        for raw in csv.DictReader(fh):
            row = dict(raw)
            for key in NUMERIC:
                value = row.get(key, "")
                row[key] = float(value) if value not in ("", None) else math.nan
            row["ok"] = row.get("ok", "true").lower() == "true"
            row["keptPace"] = row.get("keptPace", "false").lower() == "true"
            rows.append(row)
    return rows


def load_all(paths: list[Path]) -> list[dict]:
    """Loads every CSV in order; a later file supersedes an earlier one for the same point.

    Re-measuring one experiment with extra counters should not mean re-running the whole sweep,
    so the analysis merges runs instead. Provenance is kept on each row.
    """
    by_key: "OrderedDict[tuple[str, str], list[dict]]" = OrderedDict()
    for path in paths:
        seen_here: set[tuple[str, str]] = set()
        for row in load(path):
            key = (row["experiment"], row["label"])
            row["source"] = path.name
            if key not in seen_here:
                seen_here.add(key)
                by_key[key] = []          # a later file replaces the earlier repeats wholesale
            by_key[key].append(row)
    return [row for rows in by_key.values() for row in rows]


def group(rows: list[dict]) -> "OrderedDict[tuple[str, str], list[dict]]":
    """Groups repeats by (experiment, label), preserving the order the sweep ran them in."""
    out: OrderedDict[tuple[str, str], list[dict]] = OrderedDict()
    for row in rows:
        out.setdefault((row["experiment"], row["label"]), []).append(row)
    return out


def agg(reps: list[dict], key: str):
    """Median of a column over the repeats, plus the min-max spread."""
    values = [r[key] for r in reps if r["ok"] and not math.isnan(r[key])]
    if not values:
        return math.nan, math.nan, math.nan
    return statistics.median(values), min(values), max(values)


def med(reps: list[dict], key: str) -> float:
    return agg(reps, key)[0]


def fmt(value: float, digits: int = 0) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return "—"
    return f"{value:,.{digits}f}"


def spread(reps: list[dict], key: str, digits: int = 0) -> str:
    """Median with the min-max range, elided when the repeats agree to the printed precision."""
    m, lo, hi = agg(reps, key)
    if math.isnan(m):
        return "—"
    if f"{lo:.{digits}f}" == f"{hi:.{digits}f}":
        return fmt(m, digits)
    return f"{fmt(m, digits)} ({fmt(lo, digits)}–{fmt(hi, digits)})"


# ---------------------------------------------------------------------------------------------
# Tables
# ---------------------------------------------------------------------------------------------

def table(lines: list[str], header: list[str], body: list[list[str]]) -> None:
    lines.append("| " + " | ".join(header) + " |")
    lines.append("|" + "|".join("---" for _ in header) + "|")
    for row in body:
        lines.append("| " + " | ".join(row) + " |")
    lines.append("")


def late_frames(reps: list[dict]) -> str:
    """Frames that missed their slot by more than one frame period, as a share of frames emitted."""
    behind, total = med(reps, "framesBehind"), med(reps, "frames")
    if math.isnan(behind) or math.isnan(total) or total == 0:
        return "—"
    return f"{behind:,.0f} of {total:,.0f} ({100 * behind / total:.2f}%)"


def experiment_rows(grouped, experiment: str):
    return [(label, reps) for (exp, label), reps in grouped.items() if exp == experiment]


def write_tables(grouped, out: list[str]) -> dict:
    facts: dict = {}

    # -- E1 saturation ---------------------------------------------------------------------
    e1 = experiment_rows(grouped, "E1")
    if e1:
        out.append("### E1 — saturation curve (1 device, 1 sink, PER_HANDLER, LocalEventBus)\n")
        body = []
        for label, reps in e1:
            offered = med(reps, "offeredDpPerSec")
            consumed = med(reps, "sinkSustainedDpPerSec")
            ratio = consumed / offered if offered and not math.isnan(offered) else math.nan
            body.append([
                label,
                f"{med(reps, 'samplesPerFrame'):.2f}",
                fmt(offered) if not math.isnan(offered) else "flat out",
                spread(reps, "producerDpPerSec"),
                spread(reps, "sinkSustainedDpPerSec"),
                "—" if math.isnan(ratio) else f"{ratio:.3f}",
                spread(reps, "backlogAtProducerEnd"),
                fmt(med(reps, "dropped")),
                fmt(med(reps, "latP95Ms")),
                fmt(med(reps, "latP99Ms")),
                fmt(med(reps, "peakHeapMb")),
                fmt(med(reps, "gcCount")),
                fmt(med(reps, "gcMillis")),
            ])
        table(out, ["point", "dp/frame", "offered dp/s", "produced dp/s", "consumed dp/s",
                    "consumed/offered", "backlog dp", "dropped", "p95 ms", "p99 ms",
                    "peak heap MB", "GC count", "GC ms"], body)

        paced = [(label, reps) for label, reps in e1
                 if not math.isnan(med(reps, "offeredDpPerSec"))]
        tracking = [(med(reps, "offeredDpPerSec"), label) for label, reps in paced
                    if med(reps, "sinkSustainedDpPerSec") >= 0.98 * med(reps, "offeredDpPerSec")
                    and med(reps, "backlogAtProducerEnd") <= 0.001 * med(reps, "publishedDatapoints")]
        if tracking:
            facts["knee_offered"], facts["knee_label"] = max(tracking)
        flat = [reps for label, reps in e1 if "flat" in label]
        if flat:
            facts["producer_ceiling"] = med(flat[0], "producerDpPerSec")
            facts["sink_ceiling"] = med(flat[0], "sinkSustainedDpPerSec")
            facts["flat_backlog"] = med(flat[0], "backlogAtProducerEnd")
            facts["flat_heap"] = med(flat[0], "peakHeapMb")
            facts["flat_dropped"] = med(flat[0], "dropped")
        one = [reps for label, reps in e1 if label == "speed=1x"]
        if one:
            facts["bed_dp_per_sec"] = med(one[0], "offeredDpPerSec")
            facts["samples_per_frame"] = med(one[0], "samplesPerFrame")

    # -- E2 device scaling -----------------------------------------------------------------
    e2 = experiment_rows(grouped, "E2")
    if e2:
        out.append("### E2 — concurrent devices, flat out (1 sink each)\n")
        base = med(e2[0][1], "sinkSustainedDpPerSec")
        body = []
        for label, reps in e2:
            n = med(reps, "devices")
            consumed = med(reps, "sinkSustainedDpPerSec")
            body.append([
                label,
                spread(reps, "producerDpPerSec"),
                spread(reps, "sinkSustainedDpPerSec"),
                fmt(consumed / n) if n else "—",
                f"{consumed / (n * base):.2f}" if base and n else "—",
                fmt(med(reps, "dropped")),
                fmt(med(reps, "latP95Ms")),
                fmt(med(reps, "peakThreads")),
                fmt(med(reps, "peakHeapMb")),
            ])
        table(out, ["point", "produced dp/s", "consumed dp/s", "per device dp/s",
                    "scaling efficiency", "dropped", "p95 ms", "peak threads", "peak heap MB"], body)
        facts["e2_best"] = max(med(reps, "sinkSustainedDpPerSec") for _, reps in e2)

    # -- E3 fan-out ------------------------------------------------------------------------
    e3 = experiment_rows(grouped, "E3")
    if e3:
        out.append("### E3 — sink fan-out, flat out (1 device)\n")
        body = []
        for label, reps in e3:
            s = med(reps, "sinksPerDevice")
            body.append([
                label,
                spread(reps, "producerDpPerSec"),
                spread(reps, "sinkSustainedDpPerSec"),
                fmt(med(reps, "sinkSustainedDpPerSec") / s) if s else "—",
                spread(reps, "backlogAtProducerEnd"),
                fmt(med(reps, "latP95Ms")),
                fmt(med(reps, "peakThreads")),
            ])
        table(out, ["point", "produced dp/s", "delivered dp/s (all sinks)", "per sink dp/s",
                    "backlog dp", "p95 ms", "peak threads"], body)

    # -- E4 dispatch mode --------------------------------------------------------------------
    e4 = experiment_rows(grouped, "E4")
    if e4:
        out.append("### E4 — dispatch mode (1 device, 4 sinks)\n")
        body = []
        for label, reps in e4:
            ok = all(r["ok"] for r in reps)
            body.append([
                label,
                spread(reps, "producerDpPerSec"),
                spread(reps, "sinkSustainedDpPerSec"),
                f"{med(reps, 'deliveryRatio'):.4f}" if ok else "—",
                spread(reps, "backlogAtProducerEnd"),
                fmt(med(reps, "dropped")),
                fmt(med(reps, "latP95Ms")),
                fmt(med(reps, "peakThreads")),
                fmt(med(reps, "peakHeapMb")),
                "" if ok else "FAILED: " + next(r["failure"] for r in reps if not r["ok"]),
            ])
        table(out, ["point", "produced dp/s", "delivered dp/s", "delivered/published",
                    "backlog dp", "dropped", "p95 ms", "peak threads", "peak heap MB", "note"], body)

    # -- E5 real-time capacity ---------------------------------------------------------------
    e5 = experiment_rows(grouped, "E5")
    if e5:
        out.append("### E5 — real-time bed capacity (speed 1x, 1 sink per bed)\n")
        body = []
        for label, reps in e5:
            beds = med(reps, "devices")
            body.append([
                label,
                f"{med(reps, 'samplesPerFrame'):.2f}",
                fmt(med(reps, "offeredDpPerSec")),
                fmt(med(reps, "sinkSustainedDpPerSec")),
                f"{med(reps, 'deliveryRatio'):.4f}",
                fmt(med(reps, "dropped")),
                fmt(med(reps, "lagP95Ms")),
                fmt(med(reps, "lagMaxMs")),
                late_frames(reps),
                fmt(med(reps, "latP95Ms")),
                fmt(med(reps, "latP99Ms")),
                fmt(med(reps, "peakThreads")),
                fmt(med(reps, "peakThreads") / beds, 1) if beds else "—",
                fmt(med(reps, "peakHeapMb")),
            ])
        table(out, ["point", "dp/frame", "offered dp/s", "consumed dp/s", "delivered/published",
                    "dropped", "producer lag p95 ms", "producer lag max ms", "late frames",
                    "latency p95 ms", "latency p99 ms", "peak threads", "threads/bed",
                    "peak heap MB"], body)
        # A single late frame out of thousands fails PacingStats.keptPace(), which is too strict to
        # read as a capacity verdict. The deployable criterion used here: nothing dropped, everything
        # delivered, under 0.1 % of frames late, and p99 latency inside 100 ms.
        good = [med(reps, "devices") for label, reps in e5
                if all(r["ok"] for r in reps)
                and med(reps, "dropped") == 0 and med(reps, "deliveryRatio") >= 0.9999
                and (math.isnan(med(reps, "framesBehind"))
                     or med(reps, "framesBehind") <= 0.001 * med(reps, "frames"))
                and med(reps, "latP99Ms") <= 100]
        if good:
            facts["max_beds_strict"] = max(good)
        tolerant = [med(reps, "devices") for label, reps in e5
                    if all(r["ok"] for r in reps) and med(reps, "dropped") == 0
                    and med(reps, "deliveryRatio") >= 0.9999 and med(reps, "latP99Ms") <= 100]
        if tolerant:
            facts["max_beds_tolerant"] = max(tolerant)
        per_bed = [med(reps, "offeredDpPerSec") / med(reps, "devices") for label, reps in e5
                   if med(reps, "devices")]
        if per_bed:
            facts["bed_offered_dp_per_sec"] = statistics.median(per_bed)
        threads = [(med(reps, "devices"), med(reps, "peakThreads")) for label, reps in e5]
        if len(threads) >= 2:
            (n0, t0), (n1, t1) = threads[0], threads[-1]
            if n1 != n0:
                facts["threads_per_bed"] = (t1 - t0) / (n1 - n0)

    # -- E6 bus ------------------------------------------------------------------------------
    e6 = experiment_rows(grouped, "E6")
    if e6:
        out.append("### E6 — LocalEventBus vs SocketEventBus (1 device, 1 sink, no peers)\n")
        body = []
        for label, reps in e6:
            offered = med(reps, "offeredDpPerSec")
            body.append([
                label,
                fmt(offered) if not math.isnan(offered) else "flat out",
                spread(reps, "producerDpPerSec"),
                spread(reps, "sinkSustainedDpPerSec"),
                f"{med(reps, 'deliveryRatio'):.4f}",
                fmt(med(reps, "latP95Ms")),
            ])
        table(out, ["point", "offered dp/s", "produced dp/s", "consumed dp/s",
                    "delivered/published", "p95 ms"], body)
        local = {label.split("@")[1]: med(reps, "sinkSustainedDpPerSec")
                 for label, reps in e6 if label.startswith("LOCAL")}
        socket = {label.split("@")[1]: med(reps, "sinkSustainedDpPerSec")
                  for label, reps in e6 if label.startswith("SOCKET")}
        if "flat-out" in local and "flat-out" in socket and local["flat-out"]:
            facts["socket_vs_local"] = socket["flat-out"] / local["flat-out"]

    return facts


# ---------------------------------------------------------------------------------------------
# Figures
# ---------------------------------------------------------------------------------------------

def write_figures(grouped, outdir: Path) -> list[str]:
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib not available — skipping figures", file=sys.stderr)
        return []

    outdir.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({
        "font.family": "DejaVu Sans", "font.size": 9,
        "axes.edgecolor": "#303030", "axes.labelcolor": "#101010",
        "axes.grid": True, "grid.color": "#DDDDDD", "grid.linewidth": 0.6,
        "axes.axisbelow": True, "xtick.color": "#303030", "ytick.color": "#303030",
        "figure.facecolor": "white", "savefig.bbox": "tight", "savefig.dpi": 300,
        "legend.frameon": False,
    })
    written: list[str] = []

    def save(fig, stem):
        for ext in ("svg", "pdf", "png"):
            path = outdir / f"{stem}.{ext}"
            fig.savefig(path)
            if ext == "svg":
                written.append(str(path))
        plt.close(fig)

    # Fig 1 — saturation curve
    e1 = experiment_rows(grouped, "E1")
    paced = [(med(r, "offeredDpPerSec"), med(r, "sinkSustainedDpPerSec"),
              med(r, "producerDpPerSec"), med(r, "backlogAtProducerEnd"))
             for _, r in e1 if not math.isnan(med(r, "offeredDpPerSec"))]
    if paced:
        paced.sort()
        offered = [p[0] for p in paced]
        consumed = [p[1] for p in paced]
        produced = [p[2] for p in paced]
        backlog = [p[3] for p in paced]

        fig, (ax, bx) = plt.subplots(2, 1, figsize=(5.4, 5.2), sharex=True,
                                     gridspec_kw={"height_ratios": [2, 1]})
        lim = [min(offered) * 0.7, max(offered) * 1.4]
        ax.plot(lim, lim, color="#AAAAAA", linestyle=(0, (4, 3)), linewidth=1,
                label="ideal (consumed = offered)")
        ax.plot(offered, produced, marker="s", markersize=4, color="#777777",
                linewidth=1.2, label="producer publish rate")
        ax.plot(offered, consumed, marker="o", markersize=4, color="#101010",
                linewidth=1.4, label="sink sustained rate")
        ax.set_xscale("log"); ax.set_yscale("log")
        ax.set_ylabel("achieved throughput (datapoints/s)")
        ax.legend(loc="upper left")

        bx.plot(offered, backlog, marker="o", markersize=4, color="#101010", linewidth=1.4)
        bx.set_xscale("log")
        # symlog keeps a true zero on the axis: below the knee the backlog is exactly 0, and a plain
        # log scale would have to fake that with a floor.
        bx.set_yscale("symlog", linthresh=1)
        bx.set_xlabel("offered load (datapoints/s)")
        bx.set_ylabel("backlog at\nproducer end (dp)")

        knee = max((o for o, c, _, b in paced if b == 0 and c >= 0.98 * o), default=None)
        if knee:
            for axis in (ax, bx):
                axis.axvline(knee, color="#101010", linewidth=0.8, linestyle=(0, (1, 2)))
            ax.annotate("last offered load\nsustained with no backlog\n%s dp/s" % f"{knee:,.0f}",
                        xy=(knee, min(consumed)), xytext=(0.52, 0.13), textcoords="axes fraction",
                        fontsize=8, color="#303030")
        save(fig, "fig1-saturation")

    # Fig 2 — device scaling
    e2 = experiment_rows(grouped, "E2")
    if e2:
        n = [med(r, "devices") for _, r in e2]
        consumed = [med(r, "sinkSustainedDpPerSec") for _, r in e2]
        ideal = [consumed[0] * k / n[0] for k in n]
        fig, ax = plt.subplots(figsize=(5.0, 3.2))
        ax.plot(n, ideal, color="#AAAAAA", linestyle=(0, (4, 3)), linewidth=1, label="linear scaling")
        ax.plot(n, consumed, marker="o", markersize=4, color="#101010", linewidth=1.4,
                label="measured aggregate")
        ax.set_xscale("log", base=2); ax.set_yscale("log")
        ax.set_xticks(n); ax.set_xticklabels([f"{int(k)}" for k in n])
        ax.set_xlabel("concurrent devices"); ax.set_ylabel("aggregate throughput (dp/s)")
        ax.legend(loc="upper left")
        save(fig, "fig2-device-scaling")

    # Fig 3 — fan-out
    e3 = experiment_rows(grouped, "E3")
    if e3:
        s = [med(r, "sinksPerDevice") for _, r in e3]
        total = [med(r, "sinkSustainedDpPerSec") for _, r in e3]
        produced = [med(r, "producerDpPerSec") for _, r in e3]
        fig, ax = plt.subplots(figsize=(5.0, 3.2))
        ax.plot(s, total, marker="o", markersize=4, color="#101010", linewidth=1.4,
                label="total deliveries/s")
        ax.plot(s, produced, marker="s", markersize=4, color="#777777", linewidth=1.2,
                label="producer publish rate")
        ax.set_xticks(s); ax.set_xticklabels([f"{int(k)}" for k in s])
        ax.set_xlabel("sinks bound to the same channels"); ax.set_ylabel("datapoints/s")
        ax.legend(loc="best")
        save(fig, "fig3-fanout")

    # Fig 4 — real-time capacity: threads and latency against beds
    e5 = experiment_rows(grouped, "E5")
    if e5:
        beds = [med(r, "devices") for _, r in e5]
        threads = [med(r, "peakThreads") for _, r in e5]
        # p95 latency stays pinned at the 1 ms timestamp resolution across every bed count, so it
        # shows nothing. Worst-case producer scheduling lag is what actually degrades.
        lag_max = [max(med(r, "lagMaxMs"), 0.5) for _, r in e5]
        fig, ax = plt.subplots(figsize=(5.4, 3.2))
        ax.plot(beds, threads, marker="o", markersize=4, color="#101010", linewidth=1.4,
                label="peak live threads")
        ax.set_xscale("log", base=4); ax.set_yscale("log")
        ax.set_xticks(beds); ax.set_xticklabels([f"{int(b)}" for b in beds])
        ax.set_xlabel("concurrent beds at 125 Hz real time")
        ax.set_ylabel("peak live threads")
        bx = ax.twinx()
        bx.plot(beds, lag_max, marker="^", markersize=4, color="#777777", linewidth=1.2,
                linestyle=(0, (4, 2)), label="worst producer scheduling lag")
        bx.set_yscale("log")
        bx.set_ylabel("worst frame lag (ms)")
        bx.grid(False)
        lines = ax.get_lines() + bx.get_lines()
        ax.legend(lines, [l.get_label() for l in lines], loc="upper left")
        save(fig, "fig4-realtime-capacity")

    return written


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("csv", nargs="*",
                    default=["framed-benchmark/target/benchmark/mimic-throughput.csv"],
                    help="one or more result CSVs; a later file supersedes an earlier one "
                         "for any point it re-measures")
    ap.add_argument("--figures", default=None, help="directory to write figures into")
    ap.add_argument("--out", default=None, help="markdown file to write the tables into")
    args = ap.parse_args()

    paths = [Path(c) for c in args.csv]
    missing = [p for p in paths if not p.is_file()]
    if missing:
        print("no such CSV: " + ", ".join(str(p) for p in missing), file=sys.stderr)
        return 1

    rows = load_all(paths)
    grouped = group(rows)
    lines: list[str] = []
    facts = write_tables(grouped, lines)

    if args.figures:
        for f in write_figures(grouped, Path(args.figures)):
            print(f"wrote {f}", file=sys.stderr)

    text = "\n".join(lines)
    if args.out:
        Path(args.out).write_text(text)
        print(f"wrote {args.out}", file=sys.stderr)
    else:
        print(text)

    print("\n--- derived figures ---", file=sys.stderr)
    for key, value in facts.items():
        print(f"{key}: {value:,.4g}" if isinstance(value, float) else f"{key}: {value}",
              file=sys.stderr)
    if "sink_ceiling" in facts and facts.get("bed_offered_dp_per_sec"):
        implied = facts["sink_ceiling"] / facts["bed_offered_dp_per_sec"]
        print(f"beds_implied_by_throughput: {implied:,.0f}", file=sys.stderr)
        print("  (not attainable: the bed limit is the per-bed thread footprint, not dp/s)",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
