#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Plot the reactor-level out-of-order rate (ppm) per platform, from ``analyze_ordering.py`` output.

One panel per platform (in ``--root`` order), one bar per reactor output channel that had at least
one out-of-order event on any platform in the selected execution mode; every bar is labelled with
``out_of_order/total``, including the zero bars, so a platform without events still shows the
denominators. Rendered with plotly; static export needs kaleido and a Chrome/Chromium.

Input: ``<root>/analysis/ordering.csv`` of each results root (scope ``reactor``).

Outputs: ``<out>/ordering_<mode>.{pdf,svg,png}`` and ``ordering_<mode>.csv`` (the plotted table).

Usage::

    python plot_ordering.py --root out/full-amd --root out/full-pi --out out/comparison \\
        [--mode NON_ATOMIC]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

from framed_runs import order_labels

NAMES = {
    "A1": "etCO2 Limit", "A2": "SpO2 Limit", "A3": "PI Limit", "A4": "SF-Ratio",
    "A5": "SF-Limit", "A6": "HR-Limit", "A7": "SpO2-Trend", "A8": "etCO2-Trend",
    "A9": "Tube-Dislocation", "A10": "Hemodynamic Rhythm", "A11": "Respiratory Rate Estimation",
    "A12": "RR-Mismatch", "A13": "Interpretation",
}
DISPLAY = {"AMD": "AMD Ryzen 7 PRO", "PI": "Raspberry Pi 5"}
MODE_NAME = {"ATOMIC": "atomic", "NON_ATOMIC": "non-atomic"}
# Same colour per platform as plot_latency_distribution.py.
PLATFORM_COLOURS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
INK, MUTED, GRID = "#22221f", "#55554f", "#e4e3df"
FONT = "Arial, Helvetica, sans-serif"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path, action="append",
                    help="results root with analysis/ordering.csv; repeat per platform")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--mode", default="NON_ATOMIC", choices=list(MODE_NAME))
    ap.add_argument("--formats", nargs="+", default=["pdf", "svg", "png"],
                    choices=["pdf", "svg", "png", "eps"])
    ap.add_argument("--width", type=int, default=700, help="figure width in px (pdf: pt)")
    ap.add_argument("--height", type=int, default=380, help="figure height in px (pdf: pt)")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    df = pd.concat([pd.read_csv(r / "analysis" / "ordering.csv") for r in args.root],
                   ignore_index=True)
    df = df[(df["scope"] == "reactor") & (df["mode"] == args.mode)]
    platforms = list(dict.fromkeys(df["platform"]))
    hit = df.groupby("label")["out_of_order"].sum()
    labels = order_labels(hit[hit > 0].index)
    if not labels:
        print(f"no out-of-order events in mode {args.mode}; nothing to plot", file=sys.stderr)
        return 0
    tab = df[df["label"].isin(labels)][["platform", "label", "className", "out_of_order", "total",
                                         "runs", "runs_with_ooo", "out_of_order_ppm"]]
    tab.to_csv(args.out / f"ordering_{args.mode.lower()}.csv", index=False)

    import plotly.graph_objects as go
    from plotly.subplots import make_subplots

    xmax = max(5.0, tab["out_of_order_ppm"].max() * 1.45)  # room for the count labels
    fig = make_subplots(rows=1, cols=len(platforms), shared_yaxes=True, horizontal_spacing=0.06,
                        subplot_titles=[f"{DISPLAY.get(p, p)}, {MODE_NAME[args.mode]}" for p in platforms])
    ylabels = [f"{l} ({NAMES.get(l, '')})" for l in labels]
    for i, p in enumerate(platforms, 1):
        sub = tab[tab["platform"] == p].set_index("label").reindex(labels)
        fig.add_trace(go.Bar(
            x=sub["out_of_order_ppm"].fillna(0), y=ylabels, orientation="h",
            marker_color=PLATFORM_COLOURS[(i - 1) % len(PLATFORM_COLOURS)], width=0.5,
            text=[f"{int(o)}/{int(t):,}" for o, t in zip(sub["out_of_order"], sub["total"])],
            textposition="outside", cliponaxis=False, textfont=dict(size=13, color=INK),
            showlegend=False), row=1, col=i)
    axis = dict(showline=True, linecolor=MUTED, ticks="outside", tickcolor=MUTED, ticklen=5,
                tickfont=dict(size=13, color=INK), gridcolor=GRID, zeroline=False)
    fig.update_xaxes(**axis, range=[0, xmax], title_text="Out-of-order rate (ppm)",
                     title_font=dict(size=14, color=INK))
    fig.update_yaxes(**{**axis, "showgrid": False}, autorange="reversed")
    fig.update_annotations(font=dict(size=14, color=INK))
    fig.update_layout(template="plotly_white", width=args.width, height=args.height,
                      font=dict(family=FONT, color=INK), bargap=0.4,
                      margin=dict(l=10, r=20, t=40, b=60),
                      plot_bgcolor="white", paper_bgcolor="white")
    for fmt in args.formats:
        fig.write_image(args.out / f"ordering_{args.mode.lower()}.{fmt}",
                        scale=3 if fmt == "png" else 1)
    print(f"wrote {args.out / f'ordering_{args.mode.lower()}'}.*", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
