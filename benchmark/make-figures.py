#!/usr/bin/env python3
"""Figures for both FRAMED throughput studies, drawn with Plotly.

    python3 benchmark/make-figures.py [--out DIR]

Reads the two result CSVs and writes SVG/PDF/PNG through Kaleido. Static figures for an article, so
there is no hover layer; identity is carried by a legend *and* a direct label on every series, which
also supplies the relief the validated palette requires for the aqua slot on a light surface.

Study B is drawn from the current transport implementation only. The pre-fix TCP sweep (one
connection per message) is not plotted: the defect is fixed, its measurements describe code that no
longer exists, and carrying it as a fourth series made every remote figure a before/after comparison
rather than a characterisation of the transports.

Palette: categorical slots 1-3 (#2a78d6 blue, #eb6834 orange, #1baf7a aqua), validated all-pairs in
both modes. Ordered percentiles use the single-hue blue ordinal ramp (steps 250/450/650), never
categorical hues, because they are three levels of one measure.

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
from plotly.subplots import make_subplots

# --- palette -------------------------------------------------------------------------------
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_2 = "#52514e"
GRID = "#e5e4e0"
AXIS = "#8f8e88"
REF = "#a8a7a1"          # reference / "ideal" lines: neutral, never a series hue
SERIES = {"LOCAL": "#2a78d6", "TCP": "#eb6834", "UDP": "#1baf7a"}
# Secondary encoding, so identity never rests on hue alone and coincident series stay readable:
# LOCAL, TCP and UDP sit on exactly the same values wherever none of them is saturated.
DASH = {"LOCAL": "solid", "UDP": "dash", "TCP": "solid"}
MARKER = {"LOCAL": "circle", "UDP": "triangle-up", "TCP": "square"}
# Where series lie on identical values a later solid line hides the earlier ones entirely, so the
# remote figures draw them at decreasing widths and the coincidence reads as nested bands.
WIDTH = {"LOCAL": 4.2, "UDP": 2.4, "TCP": 1.2}
LABEL_DY = {"LOCAL": -10, "UDP": 11, "TCP": 0}
ORDINAL = ["#86b6ef", "#2a78d6", "#104281"]   # blue 250 / 450 / 650

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
    return ([symlog(v, lt) for v in vals],
            [f"{v:,.0f}" for v in vals])


def axis_style(**kw):
    base = dict(showgrid=True, gridcolor=GRID, gridwidth=0.6, zeroline=False,
                showline=True, linecolor=AXIS, linewidth=1.0, ticks="outside",
                ticklen=3, tickcolor=AXIS, tickfont=dict(color=INK_2, size=10),
                title_font=dict(color=INK_2, size=10.5))
    base.update(kw)
    return base


def figure(width_in=5.2, height_in=3.4, right_pad=76, **subplot_kw):
    """A figure on the article surface. ``right_pad`` leaves room for the direct labels."""
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
    fig.update_layout(showlegend=True,
                      legend=dict(x=x, y=y, xanchor=xanchor, yanchor=yanchor,
                                  bgcolor="rgba(0,0,0,0)", borderwidth=0,
                                  font=dict(size=9.5, color=INK_2)))


def note(fig, text, x=0.03, y=0.62, **kw):
    """A short explanatory note in paper coordinates, in ink rather than a series colour."""
    fig.add_annotation(text=text, xref="paper", yref="paper", x=x, y=y, xanchor="left",
                       showarrow=False, align="left", font=dict(size=9, color=INK_2), **kw)


def direct_label(fig, x, y, text, dx=7, dy=0, row=None, col=None):
    """Series name in ink beside the last mark; the coloured mark carries identity."""
    kw = dict(row=row, col=col) if row else {}
    fig.add_annotation(x=x, y=y, text=text, showarrow=False, xanchor="left", yanchor="middle",
                       xshift=dx, yshift=dy, font=dict(size=9.5, color=INK_2), **kw)


def vline(fig, x, **kw):
    """A recessive vertical rule at ``x``.

    Shape coordinates are raw data values even on a log axis — the opposite of annotations, which
    take log10 there. Both conventions are used in this file; do not swap them.
    """
    fig.add_shape(type="line", xref="x", yref="paper", x0=x, x1=x, y0=0, y1=1,
                  line=dict(color=AXIS, width=0.9, dash="dot"), layer="below", **kw)


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
    lx = [math.log10(v) for v in offered]

    knee = max((o for o, c, _p, b, *_ in paced if b == 0 and c >= 0.98 * o), default=None)

    # -- A1 saturation ------------------------------------------------------------------
    fig = figure(5.2, 3.4, right_pad=24)
    lim = [min(offered) * 0.7, max(offered) * 1.5]
    fig.add_trace(go.Scatter(x=lim, y=lim, mode="lines", showlegend=False, hoverinfo="skip",
                             line=dict(color=REF, width=1.2, dash="dash")))
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
        vline(fig, knee)
        note(fig, f"last load carried<br>with no backlog<br>{knee:,.0f} dp/s", x=0.05, y=0.58)
    fig.add_annotation(x=math.log10(lim[0]), y=math.log10(lim[0]), text="ideal", showarrow=False,
                       xanchor="left", yanchor="top", xshift=10, yshift=-2,
                       font=dict(size=9, color=INK_2))
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text="offered load (datapoints/s)")
    fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text="achieved (datapoints/s)")
    legend(fig)
    save(fig, out, "figA1-saturation")

    # -- A2 latency ---------------------------------------------------------------------
    fig = figure(5.2, 3.2, right_pad=44)
    vmax = max(max(p[c] for p in paced) for c in (4, 5, 6))
    for idx, (name, col, dy) in enumerate((("p50", 4, -11), ("p95", 5, 0), ("p99", 6, 11))):
        y = [symlog(p[col]) for p in paced]
        fig.add_trace(go.Scatter(x=offered, y=y, mode="lines+markers", name=f"latency {name}",
                                 line=dict(color=ORDINAL[idx], width=2),
                                 marker=dict(symbol="circle", size=8, color=ORDINAL[idx],
                                             line=dict(color=SURFACE, width=1.2))))
        direct_label(fig, lx[-1], y[-1], name, dy=dy)
    if knee:
        vline(fig, knee)
    tv, tt = symlog_ticks(vmax)
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text="offered load (datapoints/s)")
    # symlog, not log: p50 is genuinely 0 ms below the knee and a log axis cannot show zero
    # without inventing a floor for it.
    fig.update_yaxes(tickvals=tv, ticktext=tt, range=[-0.12, symlog(vmax) * 1.12],
                     title_text="emit→sink latency (ms)")
    note(fig, "1 ms = wire-timestamp resolution; 0 and 1 ms are the only<br>"
              "values resolvable below the knee", x=0.03, y=0.60)
    legend(fig)
    save(fig, out, "figA2-latency")

    # -- A3 backlog, with the zero-drop fact stated on the figure -------------------------
    fig = figure(5.2, 2.9, right_pad=24)
    bmax = max(backlog)
    fig.add_trace(go.Scatter(x=offered, y=[symlog(b) for b in backlog], mode="lines+markers",
                             line=dict(color=SERIES["LOCAL"], width=2),
                             marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                         line=dict(color=SURFACE, width=1.2))))
    tv, tt = symlog_ticks(bmax)
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text="offered load (datapoints/s)")
    fig.update_yaxes(tickvals=tv, ticktext=tt, range=[-0.25, symlog(bmax) * 1.12],
                     title_text="backlog at producer end (dp)")
    if knee:
        vline(fig, knee)
    dropped_total = sum(int(r["dropped"]) for r in rows if r["ok"])
    note(fig, f"datapoints dropped across all {sum(1 for r in rows if r['ok'])} runs: {dropped_total}",
         x=0.03, y=0.88)
    save(fig, out, "figA3-backlog")

    # -- A4 bed capacity: two panels, one y-axis each (never a dual axis) -----------------
    e5 = by(rows, experiment="E5")
    beds = sorted((med(r, "devices"), med(r, "peakThreads"), max(med(r, "lagMaxMs"), 0.5),
                   med(r, "framesBehind"), med(r, "frames")) for r in e5.values())
    if beds:
        x = [b[0] for b in beds]
        fig = figure(5.2, 4.4, right_pad=24, rows=2, cols=1, shared_xaxes=True,
                     vertical_spacing=0.06)
        for row, col_idx in ((1, 1), (2, 2)):
            fig.add_trace(go.Scatter(x=x, y=[b[col_idx] for b in beds], mode="lines+markers",
                                     line=dict(color=SERIES["LOCAL"], width=2),
                                     marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                                 line=dict(color=SURFACE, width=1.2))),
                          row=row, col=1)
        # A paper-referenced x on a subplot shape is read against that subplot's own axis, so the
        # rule is drawn in data values spanning the panel. Its label goes at the right, where the
        # band just above 16 threads is empty.
        fig.add_shape(type="line", xref="x", yref="y", x0=x[0] * 0.7, x1=x[-1] * 1.4, y0=16, y1=16,
                      line=dict(color=REF, width=1.2, dash="dash"), layer="below", row=1, col=1)
        fig.add_annotation(x=math.log10(x[-1] * 1.35), y=math.log10(16), text="16 hardware threads",
                           showarrow=False, xanchor="right", yanchor="bottom", yshift=3,
                           font=dict(size=9, color=INK_2), row=1, col=1)
        fig.update_yaxes(axis_style(type="log", dtick=1, title_text="peak live threads"), row=1, col=1)
        fig.update_yaxes(axis_style(type="log", dtick=1, title_text="worst frame lag (ms)"), row=2, col=1)
        # Explicit range: the two panels share this axis via ``matches``, and plotly's autorange
        # misplaces a shared log axis once tickvals are supplied — the marks bunch at one end.
        xr = [math.log10(x[0] * 0.7), math.log10(x[-1] * 1.4)]
        fig.update_xaxes(axis_style(type="log", range=xr, tickvals=x,
                                    ticktext=[f"{int(v)}" for v in x]), row=1, col=1)
        fig.update_xaxes(axis_style(type="log", range=xr, tickvals=x,
                                    ticktext=[f"{int(v)}" for v in x],
                                    title_text="concurrent beds at 125 Hz real time"), row=2, col=1)
        save(fig, out, "figA4-bed-capacity")

    # -- A5 device scaling ----------------------------------------------------------------
    e2 = by(rows, experiment="E2")
    dev = sorted((med(r, "devices"), med(r, "sinkSustainedDpPerSec")) for r in e2.values())
    if dev:
        x = [d[0] for d in dev]
        y = [d[1] for d in dev]
        fig = figure(5.2, 3.0, right_pad=24)
        ideal = [y[0] * k / x[0] for k in x]
        fig.add_trace(go.Scatter(x=x, y=ideal, mode="lines", showlegend=False, hoverinfo="skip",
                                 line=dict(color=REF, width=1.2, dash="dash")))
        fig.add_annotation(x=math.log10(x[-1]), y=math.log10(ideal[-1]), text="linear scaling",
                           showarrow=False, xanchor="right", yanchor="bottom", yshift=4,
                           font=dict(size=9, color=INK_2))
        fig.add_trace(go.Scatter(x=x, y=y, mode="lines+markers",
                                 line=dict(color=SERIES["LOCAL"], width=2),
                                 marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                             line=dict(color=SURFACE, width=1.2))))
        fig.update_xaxes(type="log", tickvals=x, ticktext=[f"{int(v)}" for v in x],
                         title_text="concurrent devices, unpaced")
        fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text="aggregate delivered (datapoints/s)")
        save(fig, out, "figA5-device-scaling")

    # -- A6 sink fan-out: two measures, two panels ------------------------------------------
    e3 = by(rows, experiment="E3")
    fan = sorted((med(r, "sinksPerDevice"), med(r, "sinkSustainedDpPerSec"),
                  med(r, "producerDpPerSec")) for r in e3.values())
    if fan:
        x = [f[0] for f in fan]
        fig = figure(5.2, 4.2, right_pad=24, rows=2, cols=1, shared_xaxes=True,
                     vertical_spacing=0.06)
        for row, col_idx in ((1, 1), (2, 2)):
            fig.add_trace(go.Scatter(x=x, y=[f[col_idx] for f in fan], mode="lines+markers",
                                     line=dict(color=SERIES["LOCAL"], width=2),
                                     marker=dict(symbol="circle", size=8, color=SERIES["LOCAL"],
                                                 line=dict(color=SURFACE, width=1.2))),
                          row=row, col=1)
        fig.update_yaxes(axis_style(tickformat=",", title_text="total delivered (dp/s)"), row=1, col=1)
        fig.update_yaxes(axis_style(type="log", dtick=1, tickformat=",",
                                    title_text="producer publish rate (dp/s)"), row=2, col=1)
        fig.update_xaxes(axis_style(tickvals=x, ticktext=[f"{int(v)}" for v in x]), row=1, col=1)
        fig.update_xaxes(axis_style(tickvals=x, ticktext=[f"{int(v)}" for v in x],
                                    title_text="sinks bound to the same channels"), row=2, col=1)
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

    def label_all(fig, drawn, log_y=False, dy=None):
        """Direct labels beside the last mark.

        Annotations are placed in axis coordinates, which on a log axis means log10 of the value —
        hence ``log_y``. The symlog figures pass ``False``: their axis is linear and the plotted
        values are already transformed.
        """
        for name, xs, _ys, plot_y in drawn:
            if not xs:
                continue
            y = math.log10(plot_y[-1]) if log_y else plot_y[-1]
            direct_label(fig, math.log10(xs[-1]), y, name,
                         dy=(dy or {}).get(name, LABEL_DY[name]))

    # -- B1 thread footprint: what saturation actually costs ---------------------------------
    fig = figure(5.2, 3.2, right_pad=68)
    drawn = draw(fig, "peakThreads")
    fig.add_shape(type="line", xref="paper", yref="y", x0=0, x1=1, y0=16, y1=16,
                  line=dict(color=REF, width=1.2, dash="dash"), layer="below")
    # Below the rule and to the right, where the empty quadrant under LOCAL keeps it off the data.
    fig.add_annotation(x=0.55, xref="paper", y=math.log10(16), text="16 hardware threads",
                       showarrow=False, xanchor="left", yanchor="top", yshift=-4,
                       font=dict(size=9, color=INK_2))
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text="offered load (datapoints/s)")
    fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text="peak live threads")
    note(fig, "delivery ratio is 1.0000 at every point in this figure", x=0.03, y=0.44)
    legend(fig, x=0.02, y=0.98)
    label_all(fig, drawn, log_y=True, dy={"LOCAL": 0, "UDP": 11, "TCP": -10})
    save(fig, out, "figB1-threads")

    # -- B2 achieved throughput ------------------------------------------------------------
    fig = figure(5.2, 3.4, right_pad=68)
    xs0, _ = series("LOCAL", "sinkDpPerSec")
    lim = [min(xs0) * 0.7, max(xs0) * 1.5]
    fig.add_trace(go.Scatter(x=lim, y=lim, mode="lines", showlegend=False, hoverinfo="skip",
                             line=dict(color=REF, width=1.2, dash="dash")))
    fig.add_annotation(x=math.log10(lim[0]), y=math.log10(lim[0]), text="ideal", showarrow=False,
                       xanchor="left", yanchor="top", xshift=10, yshift=-2,
                       font=dict(size=9, color=INK_2))
    drawn = draw(fig, "sinkDpPerSec")
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text="offered load (datapoints/s)")
    fig.update_yaxes(type="log", dtick=1, tickformat=",", title_text="delivered (datapoints/s)")
    legend(fig)
    label_all(fig, drawn, log_y=True, dy={"LOCAL": 0, "UDP": 11, "TCP": -10})
    save(fig, out, "figB2-throughput")

    # -- B3 latency: what each wiring costs once it saturates --------------------------------
    fig = figure(5.2, 3.4, right_pad=68)
    drawn = draw(fig, "latP95Ms", transform=symlog)
    vmax = max(max(ys) for _n, _x, ys, _p in drawn if ys)
    tv, tt = symlog_ticks(vmax)
    fig.update_xaxes(type="log", dtick=1, tickformat=",", title_text="offered load (datapoints/s)")
    # symlog, not log: the in-process bus reports 0 ms, which a log axis cannot place. The range is
    # floored just below 0 because latency cannot be negative.
    fig.update_yaxes(tickvals=tv, ticktext=tt, range=[-0.12, symlog(vmax) * 1.12],
                     title_text="emit→sink latency p95 (ms)")
    note(fig, "1 ms = timestamp resolution", x=0.03, y=0.06)
    legend(fig)
    label_all(fig, drawn, dy={"LOCAL": 0, "UDP": -11, "TCP": 11})
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
    ap.add_argument("--single", default="benchmark/results/mimic-throughput-repeat.csv")
    ap.add_argument("--pair", default="benchmark/results/socket-pair-postfix.csv")
    ap.add_argument("--out", default="benchmark/figures")
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
