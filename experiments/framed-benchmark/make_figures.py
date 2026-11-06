#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Figures for both FRAMED throughput studies, drawn with Plotly.

    python3 make_figures.py --single <study_a.csv> --pair <study_b.csv> --out DIR

Reads the two result CSVs and writes SVG/PDF/PNG through Kaleido. Static figures for an article, so
there is no hover layer; identity is carried by the legend, reinforced by line dash and marker shape
so it never rests on hue alone.

Study B is drawn from the current transport implementation only. The pre-fix TCP sweep (one
connection per message) is not plotted: the defect is fixed, its measurements describe code that no
longer exists, and carrying it as a fourth series made every remote figure a before/after comparison
rather than a characterisation of the transports.

House style, article-wide:

* stock Plotly colours — ``qualitative.Plotly`` for identity, ``sequential.Blues`` for the ordered
  percentiles, which are three levels of one measure and so never take categorical hues;
* white paper and white plot area, no tint anywhere;
* no prose inside the axes — every reading the figure supports is stated in the caption or the
  running text, so the plot area carries only data, axes and a legend;
* one wording per quantity across the studies (the ``X_*``/``Y_*`` constants below).

Kaleido needs a Chrome/Chromium binary. ``--chrome`` (or ``$FRAMED_FIGURES_CHROME``) points at one
when the default discovery fails — notably for a snap-packaged Chromium, whose launcher does not
forward the CDP pipe file descriptors, so the binary inside the snap must be named directly.
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import statistics
from collections import OrderedDict
from pathlib import Path

import plotly.graph_objects as go
from plotly.colors import qualitative, sequential
from plotly.subplots import make_subplots

# --- palette -------------------------------------------------------------------------------
# Stock Plotly colours throughout: the categorical slots come from ``qualitative.Plotly`` in their
# published order, ordered percentiles from the ``Blues`` sequential ramp. Nothing is hand-mixed, so
# the figures match any other Plotly output in the article without a bespoke theme to maintain.
CATEGORICAL = qualitative.Plotly            # #636EFA, #EF553B, #00CC96, …
ORDINAL = [sequential.Blues[i] for i in (3, 5, 7)]

SURFACE = "white"        # figure and plot area are plain white; nothing is tinted
INK = "#000000"
INK_2 = "#444444"
GRID = "#e5e5e5"
AXIS = "#888888"
REF = "#9e9e9e"          # reference / "ideal" lines: neutral, never a series hue
SERIES = {"LOCAL": CATEGORICAL[0], "TCP": CATEGORICAL[1], "UDP": CATEGORICAL[2]}
# Secondary encoding, so identity never rests on hue alone and coincident series stay readable:
# LOCAL, TCP and UDP sit on exactly the same values wherever none of them is saturated.
DASH = {"LOCAL": "solid", "UDP": "dash", "TCP": "solid"}
MARKER = {"LOCAL": "circle", "UDP": "triangle-up", "TCP": "square"}
# Where series lie on identical values a later solid line hides the earlier ones entirely, so the
# remote figures draw them at decreasing widths and the coincidence reads as nested bands.
WIDTH = {"LOCAL": 4.2, "UDP": 2.4, "TCP": 1.2}

# --- axis labels ----------------------------------------------------------------------------
# One wording per quantity, reused by every figure of every study: the same measure must never be
# named two ways across the figure set.
X_OFFERED = "Offered load (datapoints/s)"
X_TIME = "Session time (s)"
X_DEVICES = "Concurrent devices"
X_BEDS = "Concurrent beds"
X_SINKS = "Sinks per device"
Y_THROUGHPUT = "Throughput (datapoints/s)"
Y_PUBLISH = "Publish rate (datapoints/s)"
Y_LATENCY = "Latency (ms)"
Y_BACKLOG = "Backlog (datapoints)"
Y_THREADS = "Peak live threads"
Y_LAG = "Frame lag (ms)"
Y_PROBABILITY = "Kink probability"
Y_VERDICT = "Verdict"

FONT = "DejaVu Sans, Helvetica, Arial, sans-serif"
PX = 96.0                                     # figure sizes stay in inches, as the article uses them
_CHROME: str | None = None


# --- data ----------------------------------------------------------------------------------

def load(path: Path) -> list[dict]:
    rows = []
    for raw in csv.DictReader(path.open()):
        r = dict(raw)
        for k, v in list(r.items()):
            if k in ("experiment", "label", "wiring", "dispatchMode", "bus", "failure"):
                continue
            try:
                r[k] = float(v) if v not in ("", None) else math.nan
            except ValueError:
                pass
        r["ok"] = str(raw.get("ok", "true")).lower() == "true"
        rows.append(r)
    return rows


def med(rows, key):
    v = [r[key] for r in rows if r["ok"] and isinstance(r.get(key), float) and not math.isnan(r[key])]
    return statistics.median(v) if v else math.nan


def by(rows, **match):
    out = OrderedDict()
    for r in rows:
        if all(str(r.get(k)) == str(v) for k, v in match.items()):
            out.setdefault(r["label"], []).append(r)
    return out


# --- axes ----------------------------------------------------------------------------------

def symlog(v: float, lt: float = 1.0) -> float:
    """Matplotlib's symlog transform: linear within ±``lt``, decades outside.

    A plain log axis cannot show zero, and several measures here are genuinely 0 — p50 latency below
    the knee, backlog below saturation. Rather than invent a floor for them, the values are
    transformed here and the axis is labelled with :func:`symlog_ticks`, so the drawn position is a
    symlog position while every tick still reads as the real quantity.
    """
    a = abs(v)
    if a <= lt:
        return v / lt
    return math.copysign(1.0 + math.log10(a / lt), v)


def symlog_ticks(vmax: float, lt: float = 1.0):
    """Tick positions/labels for a :func:`symlog` axis: 0, ``lt``, then one per decade."""
    vals = [0.0, lt]
    d = lt * 10.0
    while d <= vmax:
        vals.append(d)
        d *= 10.0
    if len(vals) == 2:
        # The data does not reach the next decade, so 0 and ``lt`` would be the only ticks and
        # everything above ``lt`` would sit on an unlabelled axis. Fill the partial decade.
        vals += [lt * m for m in (2.0, 5.0) if lt * m <= vmax]
    return ([symlog(v, lt) for v in vals],
            [f"{v:,.0f}" for v in vals])


def axis_style(**kw):
    base = dict(showgrid=True, gridcolor=GRID, gridwidth=0.6, zeroline=False,
                showline=True, linecolor=AXIS, linewidth=1.0, ticks="outside",
                ticklen=3, tickcolor=AXIS, tickfont=dict(color=INK_2, size=10),
                title_font=dict(color=INK_2, size=10.5))
    base.update(kw)
    return base


def figure(width_in=5.2, height_in=3.4, right_pad=20, **subplot_kw):
    """A figure on white paper, with no tinted surface and no annotation gutter."""
    if subplot_kw:
        fig = make_subplots(**subplot_kw)
    else:
        fig = go.Figure()
    fig.update_layout(
        width=int(width_in * PX), height=int(height_in * PX),
        paper_bgcolor=SURFACE, plot_bgcolor=SURFACE,
        font=dict(family=FONT, size=11, color=INK),
        margin=dict(l=64, r=right_pad, t=14, b=48),
        showlegend=False,
    )
    fig.update_xaxes(axis_style())
    fig.update_yaxes(axis_style())
    return fig


def legend(fig, x=0.02, y=0.98, xanchor="left", yanchor="top"):
    """Turn the legend on. It is the only text in the plot area, and carries identity alone."""
    fig.update_layout(showlegend=True,
                      legend=dict(x=x, y=y, xanchor=xanchor, yanchor=yanchor,
                                  bgcolor="rgba(0,0,0,0)", borderwidth=0,
                                  font=dict(size=9.5, color=INK_2)))


def vline(fig, x, name=None, **kw):
    """A recessive vertical rule at ``x`` (shape coordinates are raw data values).

    A shape carries no legend entry of its own, so ``name`` also adds an empty proxy trace: the rule
    is named in the legend rather than annotated inside the axes.
    """
    style = dict(color=AXIS, width=0.9, dash="dot")
    fig.add_shape(type="line", xref="x", yref="paper", x0=x, x1=x, y0=0, y1=1,
                  line=style, layer="below", **kw)
    if name:
        fig.add_trace(go.Scatter(x=[None], y=[None], mode="lines", name=name, hoverinfo="skip",
                                 line=style))


def reference(fig, x, y, name, width=1.2, **kw):
    """An ``ideal``/limit line: neutral grey, named in the legend rather than annotated in place.

    ``width`` is widened where a series lies exactly on the reference, so that the grey still reads
    as a band around the measured line instead of disappearing beneath it.
    """
    fig.add_trace(go.Scatter(x=x, y=y, mode="lines", name=name, hoverinfo="skip",
                             line=dict(color=REF, width=width, dash="dash")), **kw)


def save(fig, out: Path, stem: str):
    for ext in ("svg", "pdf", "png"):
        path = out / f"{stem}.{ext}"
        if _CHROME:
            import kaleido
            kaleido.write_fig_sync(fig, path, opts=dict(scale=3 if ext == "png" else 1),
                                   kopts={"path": _CHROME})
        else:
            fig.write_image(path, scale=3 if ext == "png" else 1)
    print(f"  {stem}")


# =============================================================================================
# Study A — one instance, in process
# =============================================================================================

def study_a(rows, out: Path):
    e1 = by(rows, experiment="E1")
    paced = sorted(
        ((med(r, "offeredDpPerSec"), med(r, "sinkSustainedDpPerSec"), med(r, "producerDpPerSec"),
          med(r, "backlogAtProducerEnd"), med(r, "latP50Ms"), med(r, "latP95Ms"), med(r, "latP99Ms"))
         for lbl, r in e1.items() if not math.isnan(med(r, "offeredDpPerSec"))),
        key=lambda t: t[0])
    offered = [p[0] for p in paced]
    consumed = [p[1] for p in paced]
    produced = [p[2] for p in paced]
    backlog = [p[3] for p in paced]

    knee = max((o for o, c, _p, b, *_ in paced if b == 0 and c >= 0.98 * o), default=None)

    # -- A1 saturation ------------------------------------------------------------------
    fig = figure(5.2, 3.4)
    lim = [min(offered) * 0.7, max(offered) * 1.5]
    reference(fig, lim, lim, "ideal")
    # Producer drawn wider and underneath: below the knee the two series are identical to within
    # 0.1 %, and equal widths would hide one of them completely.
    fig.add_trace(go.Scatter(x=offered, y=produced, mode="lines+markers", name="published by producer",
                             line=dict(color=SERIES["TCP"], width=4.4),
                             marker=dict(symbol="square", size=10, color=SERIES["TCP"],
                                         line=dict(color=SURFACE, width=1.2))))
    fig.add_trace(go.Scatter(x=offered, y=consumed, mode="lines+markers", name="delivered to sink",
                             line=dict(color=SERIES["LOCAL"], width=1.8),
                             marker=dict(symbol="circle", size=7, color=SERIES["LOCAL"],
                                         line=dict(color=SURFACE, width=1.2))))
    if knee:
        vline(fig, knee, name="saturation knee")
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text=X_OFFERED)
    fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text=Y_THROUGHPUT)
    legend(fig)
    save(fig, out, "figA1-saturation")

    # -- A2 latency ---------------------------------------------------------------------
    fig = figure(5.2, 3.2)
    vmax = max(max(p[c] for p in paced) for c in (4, 5, 6))
    for idx, (name, col) in enumerate((("p50", 4), ("p95", 5), ("p99", 6))):
        y = [symlog(p[col]) for p in paced]
        fig.add_trace(go.Scatter(x=offered, y=y, mode="lines+markers", name=name,
                                 line=dict(color=ORDINAL[idx], width=2),
                                 marker=dict(symbol="circle", size=8, color=ORDINAL[idx],
                                             line=dict(color=SURFACE, width=1.2))))
    if knee:
        vline(fig, knee, name="saturation knee")
    tv, tt = symlog_ticks(vmax)
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text=X_OFFERED)
    # symlog, not log: p50 is genuinely 0 ms below the knee and a log axis cannot show zero
    # without inventing a floor for it.
    fig.update_yaxes(tickvals=tv, ticktext=tt, range=[-0.12, symlog(vmax) * 1.12],
                     title_text=Y_LATENCY)
    legend(fig)
    save(fig, out, "figA2-latency")

    # -- A3 backlog -----------------------------------------------------------------------
    fig = figure(5.2, 2.9)
    bmax = max(backlog)
    # Single series: the y-axis title names it, so only the knee rule needs a legend entry.
    fig.add_trace(go.Scatter(x=offered, y=[symlog(b) for b in backlog], mode="lines+markers",
                             showlegend=False,
                             line=dict(color=SERIES["LOCAL"], width=2),
                             marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                         line=dict(color=SURFACE, width=1.2))))
    tv, tt = symlog_ticks(bmax)
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text=X_OFFERED)
    fig.update_yaxes(tickvals=tv, ticktext=tt, range=[-0.25, symlog(bmax) * 1.12],
                     title_text=Y_BACKLOG)
    if knee:
        vline(fig, knee, name="saturation knee")
        legend(fig)
    save(fig, out, "figA3-backlog")

    # -- A4 bed capacity: two panels, one y-axis each (never a dual axis) -----------------
    e5 = by(rows, experiment="E5")
    beds = sorted((med(r, "devices"), med(r, "peakThreads"), max(med(r, "lagMaxMs"), 0.5),
                   med(r, "framesBehind"), med(r, "frames")) for r in e5.values())
    if beds:
        x = [b[0] for b in beds]
        fig = figure(5.2, 4.4, rows=2, cols=1, shared_xaxes=True, vertical_spacing=0.06)
        # The hardware-thread limit is a legend entry, so the panel itself stays free of text.
        reference(fig, [x[0] * 0.7, x[-1] * 1.4], [16, 16], "16 hardware threads", row=1, col=1)
        for row, col_idx in ((1, 1), (2, 2)):
            fig.add_trace(go.Scatter(x=x, y=[b[col_idx] for b in beds], mode="lines+markers",
                                     showlegend=False,
                                     line=dict(color=SERIES["LOCAL"], width=2),
                                     marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                                 line=dict(color=SURFACE, width=1.2))),
                          row=row, col=1)
        # In the upper panel, under the rising curve and above the thread rule, which is the one
        # large empty region of the figure.
        legend(fig, x=0.52, y=0.60, xanchor="left", yanchor="bottom")
        fig.update_yaxes(axis_style(type="log", dtick=1, title_text=Y_THREADS), row=1, col=1)
        fig.update_yaxes(axis_style(type="log", dtick=1, title_text=Y_LAG), row=2, col=1)
        # Explicit range: the two panels share this axis via ``matches``, and plotly's autorange
        # misplaces a shared log axis once tickvals are supplied — the marks bunch at one end.
        xr = [math.log10(x[0] * 0.7), math.log10(x[-1] * 1.4)]
        fig.update_xaxes(axis_style(type="log", range=xr, tickvals=x,
                                    ticktext=[f"{int(v)}" for v in x]), row=1, col=1)
        fig.update_xaxes(axis_style(type="log", range=xr, tickvals=x,
                                    ticktext=[f"{int(v)}" for v in x],
                                    title_text=X_BEDS), row=2, col=1)
        save(fig, out, "figA4-bed-capacity")

    # -- A5 device scaling ----------------------------------------------------------------
    e2 = by(rows, experiment="E2")
    dev = sorted((med(r, "devices"), med(r, "sinkSustainedDpPerSec")) for r in e2.values())
    if dev:
        x = [d[0] for d in dev]
        y = [d[1] for d in dev]
        fig = figure(5.2, 3.0)
        ideal = [y[0] * k / x[0] for k in x]
        reference(fig, x, ideal, "linear scaling")
        fig.add_trace(go.Scatter(x=x, y=y, mode="lines+markers", name="measured",
                                 line=dict(color=SERIES["LOCAL"], width=2),
                                 marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                             line=dict(color=SURFACE, width=1.2))))
        fig.update_xaxes(type="log", tickvals=x, ticktext=[f"{int(v)}" for v in x],
                         title_text=X_DEVICES)
        fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text=Y_THROUGHPUT)
        legend(fig)
        save(fig, out, "figA5-device-scaling")

    # -- A6 sink fan-out: two measures, two panels ------------------------------------------
    e3 = by(rows, experiment="E3")
    fan = sorted((med(r, "sinksPerDevice"), med(r, "sinkSustainedDpPerSec"),
                  med(r, "producerDpPerSec")) for r in e3.values())
    if fan:
        x = [f[0] for f in fan]
        fig = figure(5.2, 4.2, rows=2, cols=1, shared_xaxes=True, vertical_spacing=0.06)
        for row, col_idx in ((1, 1), (2, 2)):
            fig.add_trace(go.Scatter(x=x, y=[f[col_idx] for f in fan], mode="lines+markers",
                                     line=dict(color=SERIES["LOCAL"], width=2),
                                     marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                                 line=dict(color=SURFACE, width=1.2))),
                          row=row, col=1)
        fig.update_yaxes(axis_style(tickformat=",", title_text=Y_THROUGHPUT), row=1, col=1)
        fig.update_yaxes(axis_style(type="log", dtick=1, tickformat=",",
                                    title_text=Y_PUBLISH), row=2, col=1)
        fig.update_xaxes(axis_style(tickvals=x, ticktext=[f"{int(v)}" for v in x]), row=1, col=1)
        fig.update_xaxes(axis_style(tickvals=x, ticktext=[f"{int(v)}" for v in x],
                                    title_text=X_SINKS), row=2, col=1)
        save(fig, out, "figA6-fanout")


# =============================================================================================
# Study B — two instances over the socket bus
# =============================================================================================

def study_b(rows, out: Path):
    """Study B figures: the in-process bus and the two remote transports, as they stand today."""
    speeds = sorted({r["speed"] for r in rows if r["experiment"] == "E1"})

    def series(w, col):
        xs, ys = [], []
        for sp in speeds:
            reps = [r for r in rows if r["experiment"] == "E1" and r["wiring"] == w and r["speed"] == sp]
            # x is always the offered load from the LOCAL run, so every wiring shares one abscissa.
            base = [r for r in rows if r["experiment"] == "E1" and r["wiring"] == "LOCAL" and r["speed"] == sp]
            off = med(base, "offeredDpPerSec")
            v = med(reps, col)
            if not math.isnan(off) and not math.isnan(v):
                xs.append(off)
                ys.append(v)
        return xs, ys

    def draw(fig, col, transform=None, wirings=("LOCAL", "UDP", "TCP")):
        """Plot each wiring widest-first, so coincident series read as nested bands."""
        drawn = []
        for w in wirings:
            xs, ys = series(w, col)
            plot_y = [transform(v) for v in ys] if transform else ys
            fig.add_trace(go.Scatter(
                x=xs, y=plot_y, mode="lines+markers", name=w,
                line=dict(color=SERIES[w], width=WIDTH[w], dash=DASH[w]),
                marker=dict(symbol=MARKER[w], size=8, color=SERIES[w],
                            line=dict(color=SURFACE, width=1.2))))
            drawn.append((w, xs, ys, plot_y))
        return drawn

    # -- B1 thread footprint: what saturation actually costs ---------------------------------
    fig = figure(5.2, 3.2)
    xs0, _ = series("LOCAL", "peakThreads")
    reference(fig, [min(xs0) * 0.7, max(xs0) * 1.5], [16, 16], "16 hardware threads")
    draw(fig, "peakThreads")
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text=X_OFFERED)
    fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text=Y_THREADS)
    legend(fig, x=0.02, y=0.98)
    save(fig, out, "figB1-threads")

    # -- B2 achieved throughput ------------------------------------------------------------
    fig = figure(5.2, 3.4)
    xs0, _ = series("LOCAL", "sinkDpPerSec")
    lim = [min(xs0) * 0.7, max(xs0) * 1.5]
    reference(fig, lim, lim, "ideal")
    draw(fig, "sinkDpPerSec")
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text=X_OFFERED)
    fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text=Y_THROUGHPUT)
    legend(fig)
    save(fig, out, "figB2-throughput")

    # -- B3 latency: what each wiring costs once it saturates --------------------------------
    fig = figure(5.2, 3.4)
    drawn = draw(fig, "latP95Ms", transform=symlog)
    vmax = max(max(ys) for _n, _x, ys, _p in drawn if ys)
    tv, tt = symlog_ticks(vmax)
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text=X_OFFERED)
    # symlog, not log: the in-process bus reports 0 ms, which a log axis cannot place. The range is
    # floored just below 0 because latency cannot be negative.
    fig.update_yaxes(tickvals=tv, ticktext=tt, range=[-0.12, symlog(vmax) * 1.12],
                     title_text=Y_LATENCY)
    legend(fig)
    save(fig, out, "figB3-latency")


# --- entry point -----------------------------------------------------------------------------

def find_chrome(explicit: str | None) -> str | None:
    """Resolve a Chrome/Chromium for Kaleido, preferring an explicit path.

    Returns ``None`` to let Plotly discover one itself. The snap Chromium launcher is skipped in
    favour of the binary inside the snap: the launcher does not forward the CDP pipe descriptors and
    the browser exits immediately.
    """
    if explicit:
        return explicit
    env = os.environ.get("FRAMED_FIGURES_CHROME")
    if env:
        return env
    snap = Path("/snap/chromium/current/usr/lib/chromium-browser/chrome")
    return str(snap) if snap.is_file() else None


def main() -> int:
    global _CHROME
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--single", required=True, help="merged Study A CSV (analyze_mimic.py)")
    ap.add_argument("--pair", required=True, help="merged Study B CSV (analyze_mimic.py)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--chrome", default=None,
                    help="Chrome/Chromium binary for Kaleido's static export")
    args = ap.parse_args()

    _CHROME = find_chrome(args.chrome)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    print("study A (one instance):")
    study_a(load(Path(args.single)), out)
    print("study B (two instances):")
    study_b(load(Path(args.pair)), out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
