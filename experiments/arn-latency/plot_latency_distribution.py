#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Plot the latency distribution of every reactor as a CDF, tail CDF or histogram.

One figure file per reactor (A1 … A13) and plot kind, for assembling sub-plot graphics
elsewhere; one line per platform and execution mode. Several results roots (e.g. the AMD and
the Raspberry Pi run) can be overlaid. Platform is encoded by colour, execution mode by line
style (solid = atomic, dashed = non-atomic). Rendered with plotly; static export needs kaleido
and a Chrome/Chromium.

Every figure of one metric shares the same x range (whole decades, ticks at 10^n), so panels
are directly comparable; ``--xrange per-reactor`` fits each figure to its own data instead.

The latency is computed exactly as in ``analyze_latency.py`` (its ``load_run`` is reused), so
the metrics and the warm-up exclusion are identical:

- ``trigger_net`` (default, primary): reaction end − actual send instant of the triggering
  instant;
- ``end_to_end``, ``trigger``, ``transport_net``, ``scheduling``, ``execution``: per FIRED
  evaluation;
- ``consumption``, ``consumption_net``: per consumed input at its first consumption, from the
  input's timestamp or (net) from its actual send instant.

**Pooling.** Unlike ``summary.csv``, which aggregates per-run percentiles over runs, the
distributions here pool all samples of all runs of a platform and mode, so a run contributes in
proportion to its number of firings. The samples are binned on a common logarithmic grid
(``--bins-per-decade``, default 50, i.e. ≈ 4.7 % relative bin width) instead of being held in
memory; the CDF is the cumulative sum of these bins. Values outside ``[--min-ms, --max-ms]`` are
counted in the outermost bins and reported on stderr.

Outputs (``<kind>`` ∈ cdf, ccdf, hist; ``<fmt>`` from ``--formats``, default pdf, svg, png):

- ``<kind>_<metric>/A01.<fmt>`` … ``A13.<fmt>``: one figure per reactor;
- ``<kind>_<metric>/legend.<fmt>``: the shared legend as a separate file (``--panel-legend``
  draws it into every figure instead);
- ``latency_bins_<metric>.csv``: the binned counts per platform, mode and reactor (the table
  behind every figure).

Usage::

    python plot_latency_distribution.py --root out/full-pi --root ../FRAMED-bench/.../full-amd \\
        --out out/figures [--metric trigger_net] [--kind cdf ccdf hist] [--workers 8]
"""

from __future__ import annotations

import argparse
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from analyze_latency import load_run
from framed_runs import LABELS, ORDER, Run, discover

FIRED_METRICS = ("trigger_net", "trigger", "end_to_end", "transport_net", "scheduling", "execution")
INPUT_METRICS = ("consumption", "consumption_net")
METRICS = FIRED_METRICS + INPUT_METRICS
MODES = {"ATOMIC": ("atomic", "solid"), "NON_ATOMIC": ("non-atomic", "dash")}
# Categorical slots 1-4 of a CVD-validated reference palette (light surface), in fixed order.
PLATFORM_COLOURS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
NAMES = {
    "A1": "etCO<sub>2</sub> limit", "A2": "SpO<sub>2</sub> limit", "A3": "PI limit",
    "A4": "S/F ratio", "A5": "S/F limit", "A6": "HR limit", "A7": "SpO<sub>2</sub> trend",
    "A8": "etCO<sub>2</sub> trend",
    "A9": "Tube dislocation", "A10": "Hemodynamic rhythm", "A11": "RR estimation",
    "A12": "RR mismatch", "A13": "Interpreter",
}


def bin_run(args: tuple[Run, str, float, np.ndarray]) -> tuple[dict, list[dict], int]:
    """Histogram one run's steady-state latencies per reactor on the common grid."""
    run, metric, warmup_s, edges = args
    _, fired, _, first, _, _ = load_run(run)
    df = first if metric in INPUT_METRICS else fired
    df = df[df["t_rel_s"] >= warmup_s]
    out, clipped, nonpos = {}, [], 0
    for reactor, g in df.groupby("reactor"):
        v = g[metric].to_numpy(float)
        v = v[~np.isnan(v)]
        nonpos += int((v <= 0).sum())
        below, above = int((v < edges[0]).sum()), int((v > edges[-1]).sum())
        if below or above:
            clipped.append({"run": run.run, "mode": run.mode, "reactor": reactor,
                            "below": below, "above": above})
        counts, _ = np.histogram(np.clip(v, edges[0], edges[-1]), bins=edges)
        out[(run.platform, run.mode, reactor)] = counts
    return out, clipped, nonpos


def collect(roots: list[Path], metric: str, warmup_s: float, edges: np.ndarray,
            workers: int) -> pd.DataFrame:
    runs = [r for root in roots for r in discover(root)]
    if not runs:
        raise SystemExit("no analysable runs under " + ", ".join(map(str, roots)))
    totals: dict[tuple, np.ndarray] = {}
    clipped, nonpos = [], 0
    jobs = [(r, metric, warmup_s, edges) for r in runs]
    with ProcessPoolExecutor(max_workers=workers) as ex:
        for k, (counts, clip, npos) in enumerate(ex.map(bin_run, jobs, chunksize=2), 1):
            for key, c in counts.items():
                totals[key] = totals.get(key, 0) + c
            clipped += clip
            nonpos += npos
            if k % 50 == 0 or k == len(jobs):
                print(f"  {k}/{len(jobs)} runs", file=sys.stderr)
    if clipped:
        c = pd.DataFrame(clipped)
        print(f"NOTE {c['below'].sum()} samples below {edges[0]:g} ms and {c['above'].sum()} above "
              f"{edges[-1]:g} ms were put in the outermost bins", file=sys.stderr)
    if nonpos:
        print(f"WARNING {nonpos} non-positive latencies (clock or matching problem?)", file=sys.stderr)

    rows = []
    for (platform, mode, reactor), counts in totals.items():
        rows.append(pd.DataFrame({
            "platform": platform, "mode": mode, "label": LABELS.get(reactor, reactor),
            "reactor": reactor, "bin_lo_ms": edges[:-1], "bin_hi_ms": edges[1:], "count": counts}))
    return pd.concat(rows, ignore_index=True)


DISPLAY = {"AMD": "AMD Ryzen 7 PRO", "PI": "Raspberry Pi 5"}
INK, MUTED, GRID = "#22221f", "#55554f", "#e4e3df"
FONT = "Arial, Helvetica, sans-serif"
YTITLE = {"cdf": "Cumulative probability", "ccdf": "Exceedance probability",
          "hist": "Fraction per bin"}
METRIC_TITLE = {"trigger_net": "Latency (ms)", "consumption": "Input-to-reaction latency (ms)",
                "consumption_net": "Input-to-reaction latency (ms)"}


def series(g: pd.DataFrame, kind: str, hist_per_decade: int, bins_per_decade: int):
    """x/y of one platform-and-mode line (None where a histogram bin is empty)."""
    g = g.sort_values("bin_lo_ms")
    c = g["count"].to_numpy(float)
    n = c.sum()
    if n == 0:
        return None
    lo, hi = g["bin_lo_ms"].to_numpy(), g["bin_hi_ms"].to_numpy()
    if kind == "hist":  # coarser bins: the fine grid is too noisy to read as a histogram
        k = max(1, round(bins_per_decade / hist_per_decade))
        m = len(c) // k * k
        c, lo, hi = c[:m].reshape(-1, k).sum(1), lo[:m:k], hi[k - 1:m:k]
    nz = np.flatnonzero(c)
    a, b = nz[0], nz[-1]
    if kind == "cdf":
        y = np.cumsum(c) / n
        return list(np.r_[lo[a], hi[a:b + 1]]), list(np.r_[0.0, y[a:b + 1]])
    if kind == "ccdf":
        y = 1.0 - np.cumsum(c) / n
        keep = np.flatnonzero(y[a:b + 1] > 0) + a
        return list(np.r_[lo[a], hi[keep]]), list(np.r_[1.0, y[keep]])
    frac = c / n  # hist: outline of the bars, broken where a bin is empty (log y)
    xs, ys = [], []
    for i in range(a, b + 1):
        if frac[i] > 0:
            xs += [lo[i], hi[i]]
            ys += [frac[i], frac[i]]
        elif xs and xs[-1] is not None:
            xs.append(None); ys.append(None)
    return xs, ys


def decade_axis(lo_exp: int, hi_exp: int) -> dict:
    vals = [10.0 ** e for e in range(lo_exp, hi_exp + 1)]
    minor = [m * 10.0 ** e for e in range(lo_exp, hi_exp) for m in range(2, 10)]
    return dict(type="log", range=[lo_exp, hi_exp], tickvals=vals,
                ticktext=[f"10<sup>{e}</sup>" for e in range(lo_exp, hi_exp + 1)],
                minor=dict(tickvals=minor, ticks="outside", ticklen=3, tickcolor=MUTED,
                           showgrid=False))


def style_axes(fig, xaxis: dict, yaxis: dict, xtitle: str, ytitle: str) -> None:
    common = dict(showline=True, linecolor=MUTED, linewidth=1, ticks="outside", ticklen=5,
                  tickcolor=MUTED, tickfont=dict(size=14, color=INK), gridcolor=GRID,
                  zeroline=False, title_font=dict(size=15, color=INK), mirror=False)
    fig.update_xaxes(**common, **xaxis, title_text=xtitle)
    fig.update_yaxes(**common, **yaxis, title_text=ytitle)


def plot(bins: pd.DataFrame, metric: str, kind: str, out: Path, platforms: list[str],
         formats: list[str], width: int, height: int, xrange_mode: str,
         panel_legend: bool, titles: bool, hist_per_decade: int) -> None:
    import plotly.graph_objects as go

    folder = out / f"{kind}_{metric}"
    folder.mkdir(parents=True, exist_ok=True)
    colour = {p: PLATFORM_COLOURS[i % len(PLATFORM_COLOURS)] for i, p in enumerate(platforms)}
    labels = [l for l in ORDER if l in set(bins["label"])]

    def exps(df):
        nz = df[df["count"] > 0]
        return int(np.floor(np.log10(nz["bin_lo_ms"].min()))), int(np.ceil(np.log10(nz["bin_hi_ms"].max())))

    common_x = exps(bins)
    # common y floor for log y axes: smallest non-zero value over all lines
    edges = np.r_[np.sort(bins["bin_lo_ms"].unique()), bins["bin_hi_ms"].max()]
    bpd = int(round((len(edges) - 1) / np.log10(edges[-1] / edges[0])))
    lines = {k: series(g, kind, hist_per_decade, bpd)
             for k, g in bins.groupby(["label", "platform", "mode"])}
    ys_all = np.array([v for s_ in lines.values() if s_ for v in s_[1] if v is not None], float)
    y_floor = int(np.floor(np.log10(ys_all[ys_all > 0].min())))

    if kind == "cdf":
        yaxis = dict(range=[0, 1.02], tickvals=[0, 0.25, 0.5, 0.75, 1.0],
                     ticktext=["0", "0.25", "0.5", "0.75", "1"])
    else:
        top = 0 if kind == "ccdf" else int(np.ceil(np.log10(ys_all.max())))
        yaxis = decade_axis(y_floor, top)

    xtitle = METRIC_TITLE.get(metric, f"{metric} latency (ms)")
    for label in labels:
        sub = bins[bins["label"] == label]
        fig = go.Figure()
        for platform in platforms:
            for mode in MODES:  # atomic first, so the dashed non-atomic line stays visible on top
                s_ = lines.get((label, platform, mode))
                if not s_:
                    continue
                name, dash = MODES[mode]
                fig.add_trace(go.Scatter(
                    x=s_[0], y=s_[1], mode="lines", name=f"{DISPLAY.get(platform, platform)}, {name}",
                    line=dict(color=colour[platform], dash=dash, width=2.4 if mode == "ATOMIC" else 2.0),
                    connectgaps=False))
        lo, hi = common_x if xrange_mode == "common" else exps(sub)
        style_axes(fig, decade_axis(lo, hi), yaxis, xtitle, YTITLE[kind])
        title = dict(text=f"<b>{label}</b> · {NAMES.get(label, '')}", x=0.01, xanchor="left",
                     y=0.98, yanchor="top", font=dict(size=16, color=INK)) if titles else None
        fig.update_layout(
            template="plotly_white", width=width, height=height, font=dict(family=FONT, color=INK),
            title=title, showlegend=panel_legend,
            legend=dict(orientation="h", x=0, y=-0.28, yanchor="top", font=dict(size=13)),
            margin=dict(l=78, r=16, t=46 if titles else 16, b=(110 if panel_legend else 64)),
            plot_bgcolor="white", paper_bgcolor="white")
        stem = f"A{int(label[1:]):02d}"
        for fmt in formats:
            fig.write_image(folder / f"{stem}.{fmt}", scale=3 if fmt == "png" else 1)

    # shared legend as its own file
    fig = go.Figure()
    for p in platforms:
        for mode, (name, dash) in MODES.items():
            fig.add_trace(go.Scatter(x=[None], y=[None], mode="lines", name=f"{DISPLAY.get(p, p)}, {name}",
                                     line=dict(color=colour[p], dash=dash, width=2.2)))
    fig.update_layout(template="plotly_white", width=width, height=70, showlegend=True,
                      font=dict(family=FONT, size=14, color=INK),
                      legend=dict(orientation="h", x=0.5, xanchor="center", y=0.5, yanchor="middle"),
                      xaxis=dict(visible=False), yaxis=dict(visible=False),
                      margin=dict(l=0, r=0, t=0, b=0), plot_bgcolor="white", paper_bgcolor="white")
    for fmt in formats:
        fig.write_image(folder / f"legend.{fmt}", scale=3 if fmt == "png" else 1)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", type=Path, action="append", default=[],
                    help="results root written by run_replays.sh; repeat to overlay platforms")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--metric", default="trigger_net", choices=METRICS)
    ap.add_argument("--kind", nargs="+", default=["cdf", "ccdf", "hist"], choices=["cdf", "ccdf", "hist"])
    ap.add_argument("--warmup-s", type=float, default=30.0)
    ap.add_argument("--min-ms", type=float, default=1e-3)
    ap.add_argument("--max-ms", type=float, default=1e4)
    ap.add_argument("--bins-per-decade", type=int, default=50)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--formats", nargs="+", default=["pdf", "svg", "png"],
                    choices=["pdf", "svg", "png", "eps"])
    ap.add_argument("--width", type=int, default=520, help="figure width in px (pdf: pt)")
    ap.add_argument("--height", type=int, default=380, help="figure height in px (pdf: pt)")
    ap.add_argument("--hist-bins-per-decade", type=int, default=10,
                    help="histogram bin width; the fine grid is summed into these bins")
    ap.add_argument("--xrange", choices=["common", "per-reactor"], default="common")
    ap.add_argument("--panel-legend", action="store_true", help="draw the legend into every figure")
    ap.add_argument("--title", action="store_true", help="print the reactor (e.g. 'A9 · Tube dislocation') above each figure")
    ap.add_argument("--from-bins", action="store_true",
                    help="re-plot from an existing latency_bins_<metric>.csv in --out")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    bins_csv = args.out / f"latency_bins_{args.metric}.csv"
    if args.from_bins:
        bins = pd.read_csv(bins_csv)
    elif not args.root:
        ap.error("--root is required unless --from-bins is given")
    else:
        decades = np.log10(args.max_ms) - np.log10(args.min_ms)
        edges = np.logspace(np.log10(args.min_ms), np.log10(args.max_ms),
                            int(round(decades * args.bins_per_decade)) + 1)
        bins = collect(args.root, args.metric, args.warmup_s, edges, args.workers)
        bins.to_csv(bins_csv, index=False)

    platforms = list(dict.fromkeys(bins["platform"]))
    for kind in args.kind:
        plot(bins, args.metric, kind, args.out, platforms, args.formats, args.width, args.height,
             args.xrange, args.panel_legend, args.title, args.hist_bins_per_decade)
        print(f"wrote {args.out / f'{kind}_{args.metric}'}/", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
