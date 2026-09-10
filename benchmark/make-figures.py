#!/usr/bin/env python3
"""Figures for both FRAMED throughput studies, drawn with Plotly.

    python3 benchmark/make-figures.py [--out DIR]

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
* one wording per quantity across all three studies (the ``X_*``/``Y_*`` constants below).

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

# =============================================================================================
# Study C — a Java replay driving a pyFRAMED CDSS across TCP
# =============================================================================================

#: Slot for the distributed run and its single-process control, from the same categorical order as
#: Study B: slot 1 is the reference (the control), slot 2 the measured path across the wire.
KINK = {"control": CATEGORICAL[0], "distributed": CATEGORICAL[1]}


_ANALYSIS = None


def analysis():
    """Imports ``kink-tcp/analyse-kink-tcp.py`` by path, for its series reader and its aligner.

    The figure and the run report must agree on what "aligned" means: a figure drawn on one
    alignment while the report quotes another would contradict it silently, and the reader has no
    way to see which is which. So the alignment is *taken* from the analysis rather than
    re-implemented here — the same reason ``compare-series.py`` imports it.
    """
    global _ANALYSIS
    if _ANALYSIS is None:
        import importlib.util
        path = Path(__file__).resolve().parent / "kink-tcp" / "analyse-kink-tcp.py"
        spec = importlib.util.spec_from_file_location("kink_tcp_analysis", path)
        if spec is None or spec.loader is None:       # pragma: no cover - a broken checkout only
            raise SystemExit("cannot load %s" % path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _ANALYSIS = module
    return _ANALYSIS


#: Fallback decision threshold, used only when a run directory carries no rendered config. The
#: figure reads the real one out of the run itself: the threshold is a property of the model
#: artefact the run was measured against, and a hard-coded copy here has already drifted once.
DEFAULT_THRESHOLD = 0.4


def _threshold(run_dir: Path) -> float:
    """The decision threshold the run's own classifier was configured with.

    Read from the rendered ``services_python.json`` the runner copies into every run directory, so
    the reference line in the verdict panel is by construction the boundary that produced those
    verdicts. Drawing a constant instead invites exactly the mismatch that once put a 0.3 line
    under a series classified at 0.4 — a reader checking "does the label flip where the probability
    crosses the line?" would get a systematically wrong answer.
    """
    import json

    config = run_dir / "services_python.json"
    if not config.is_file():
        return DEFAULT_THRESHOLD
    try:
        reactors = json.loads(config.read_text()).get("Reactors", [])
    except (ValueError, OSError):
        return DEFAULT_THRESHOLD
    found = {float(r["threshold"]) for r in reactors if "threshold" in r}
    if len(found) != 1:
        # Nothing to draw honestly: no classifier, or two disagreeing thresholds in one network.
        print("  (figC1 — %d thresholds in %s, falling back to %s)"
              % (len(found), config.name, DEFAULT_THRESHOLD))
        return DEFAULT_THRESHOLD
    return found.pop()


def _series(records: list[dict], channel: str, origin: float) -> tuple[list[float], list[float]]:
    """``(seconds since origin, value)`` for the numeric events of one channel.

    ``origin`` comes from the shared aligner, not from the series' own first record: the two series
    can differ in their leading events, and anchoring each on its own head would offset the curves
    against each other while the report describes them as aligned.
    """
    picked = [r for r in records
              if r["channelID"] == channel and isinstance(r["value"], (int, float))]
    return [r["epoch"] - origin for r in picked], [float(r["value"]) for r in picked]


def study_c(rows, run_dir: Path | None, out: Path):
    """Figures for the kink-over-TCP experiment: the verdict series, and the sweep."""
    if run_dir is not None:
        _figC1(run_dir, out)
    # A run that received nothing has no sink-side measurement: its latency percentiles are the
    # zeroes of an empty histogram, not fast responses, and plotting them puts the sweep's best
    # apparent latency at its highest offered load. The flat-out point is such a run by
    # construction — 608 s of recording collapses into a window shorter than the model's own, so
    # the chain emits no verdict — and it belongs in the table, not on these axes.
    measured = [r for r in rows if (r.get("received") or 0) > 0]
    if len(measured) != len(rows):
        print("  (study C — %d of %d sweep rows received nothing and are left out of figC2/figC3)"
              % (len(rows) - len(measured), len(rows)))
    if measured:
        _figC2(measured, out)
        _figC3(measured, out)


def _figC1(run_dir: Path, out: Path):
    """The clinical result: the verdict series of the distributed run against its control."""
    captures = sorted((run_dir / "capture").glob("*classifications.jsonl"))
    control_path = run_dir / "baseline.jsonl"
    if not captures or not control_path.exists():
        print("  (skipping figC1 — need both a capture and a control in %s)" % run_dir)
        return

    reader = analysis()
    distributed = reader.load_series(captures[-1])
    control = reader.load_series(control_path)
    if not control or not distributed:
        print("  (skipping figC1 — a series in %s holds no classification)" % run_dir)
        return

    # The two series carry different absolute time bases and either may be missing its leading
    # events, so the origins are the pair the run report aligned on.
    control_origin, distributed_origin = reader._align(control, distributed)
    origins = {"control": control_origin, "distributed": distributed_origin}
    threshold = _threshold(run_dir)

    fig = figure(width_in=6.4, height_in=3.6, rows=2, cols=1, shared_xaxes=True,
                 row_heights=[0.72, 0.28], vertical_spacing=0.06)

    # The decision threshold is a legend entry rather than an annotation, so it is named without
    # putting text inside the panel.
    span = [0.0, max(records[-1]["epoch"] - origins[name]
                     for name, records in (("control", control), ("distributed", distributed)))]
    reference(fig, span, [threshold, threshold], "decision threshold (%g)" % threshold,
              row=1, col=1)

    for name, records, width in (("control", control, 4.2), ("distributed", distributed, 1.3)):
        x, y = _series(records, "Kink-Probability", origins[name])
        fig.add_trace(go.Scatter(x=x, y=y, mode="lines", name=name,
                                 line=dict(color=KINK[name], width=width)), row=1, col=1)
        lx, ly = _series(records, "Kink", origins[name])
        # The verdict panel repeats the same two series, so it contributes no further legend entries.
        fig.add_trace(go.Scatter(x=lx, y=ly, mode="lines", line_shape="hv", name=name,
                                 showlegend=False,
                                 line=dict(color=KINK[name], width=width)), row=2, col=1)

    fig.update_yaxes(axis_style(title_text=Y_PROBABILITY, range=[-0.04, 1.04]), row=1, col=1)
    fig.update_yaxes(axis_style(title_text=Y_VERDICT, tickvals=[0, 1], range=[-0.25, 1.25]),
                     row=2, col=1)
    fig.update_xaxes(axis_style(title_text=X_TIME), row=2, col=1)
    legend(fig, x=0.98, y=0.98, xanchor="right")
    save(fig, out, "figC1-kink-series")


def _figC2(rows, out: Path):
    """Latency percentiles against offered load, over the runs that produced verdicts."""
    points = sorted(rows, key=lambda r: _offered(r))
    x = [_offered(r) for r in points]
    fig = figure(width_in=5.4, height_in=3.4)

    ymax = max([r.get("latencyP99Ms") or 0 for r in points] + [1])
    for colour, key, label in zip(ORDINAL, ("latencyP50Ms", "latencyP95Ms", "latencyP99Ms"),
                                  ("p50", "p95", "p99")):
        y = [symlog(r.get(key) or 0.0) for r in points]
        fig.add_trace(go.Scatter(x=x, y=y, mode="lines+markers", name=label,
                                 line=dict(color=colour, width=2.0),
                                 marker=dict(symbol="circle", size=8, color=colour,
                                             line=dict(color=SURFACE, width=1.2))))

    ticks, labels = symlog_ticks(ymax)
    fig.update_yaxes(axis_style(title_text=Y_LATENCY, tickvals=ticks, ticktext=labels,
                                range=[-0.12, symlog(ymax) * 1.12]))
    fig.update_xaxes(axis_style(title_text=X_OFFERED, type="log", dtick=1, tickformat=","))
    # The percentiles sit flat and low across the whole sweep, so the upper half is the empty one.
    legend(fig)
    save(fig, out, "figC2-kink-latency")


def _figC3(rows, out: Path):
    """Whether the chain's verdict output keeps up: sink rate against offered input rate.

    The measured quantity is the *sink's* — ``achievedDpPerS``, classifications counted per second
    at the far end of replay, TCP, three reactors and TCP back. Plotting the producer's own achieved
    rate here would be tautological: the paced runs achieve their target by construction, so such a
    figure only shows that the replay thread held its own schedule and says nothing about the chain.

    The two axes are different quantities — offered events in, verdicts out — so the reference is
    not ``y = x`` but the proportion measured at the slowest point, roughly one verdict per five
    input events. A chain that keeps up stays on that line; one that falls behind drops below it.
    """
    points = sorted(rows, key=lambda r: _offered(r))
    offered = [_offered(r) for r in points]
    achieved = [r.get("achievedDpPerS") or 0.0 for r in points]

    fig = figure(width_in=5.4, height_in=3.4)
    anchor = next(((o, a) for o, a in zip(offered, achieved) if o and a), None)
    if anchor:
        ratio = anchor[1] / anchor[0]
        span = [min(offered), max(offered)]
        reference(fig, span, [ratio * span[0], ratio * span[1]],
                  "proportional to input (%.2f×)" % ratio, width=5.0)
    fig.add_trace(go.Scatter(x=offered, y=achieved, mode="lines+markers", name="achieved at sink",
                             line=dict(color=KINK["distributed"], width=1.8),
                             marker=dict(symbol="circle", size=8, color=KINK["distributed"],
                                         line=dict(color=SURFACE, width=1.2))))
    fig.update_xaxes(axis_style(title_text=X_OFFERED, type="log", dtick=1, tickformat=","))
    fig.update_yaxes(axis_style(title_text=Y_THROUGHPUT, type="log", dtick=1, tickformat=","))
    legend(fig)
    save(fig, out, "figC3-kink-saturation")


def _offered(row) -> float:
    """The input rate a run offered: the producer's target, or its achieved rate when unpaced."""
    target = row.get("producerTargetHz")
    if target and not math.isnan(target) and target > 0:
        return target
    return row.get("producerAchievedHz") or math.nan


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
    ap.add_argument("--kink", default="benchmark/results/kink-tcp-sweep.csv")
    ap.add_argument("--kink-run", default=None,
                    help="a kink run directory, for the verdict-series figure")
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
    kink_csv = Path(args.kink)
    if kink_csv.exists() or args.kink_run:
        print("study C (kink over TCP):")
        study_c(load(kink_csv) if kink_csv.exists() else [],
                Path(args.kink_run) if args.kink_run else None, out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
