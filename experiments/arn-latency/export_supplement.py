#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Combine the analysis tables of several platforms into one supplementary-material folder.

Reads ``<root>/analysis/`` of every ``--root`` (run the three ``analyze_*.py`` scripts first),
concatenates each table over the platforms and writes:

=============================  ===========================================  ========================
file                           content                                      source table
=============================  ===========================================  ========================
S1_latency.csv                 latency statistics per reactor and metric    ``summary.csv``
S2_completeness_outputs.csv    output-based consumption completeness        ``consumption.csv``
S3_completeness_inputs.csv     input-side completeness of OR rules          ``input_consumption.csv``
S4_ordering.csv                ordering correctness                         ``ordering.csv``
S5_load.csv                    load indicators                              ``load.csv``
S6_control.csv                 run-to-run vs atomic/non-atomic differences  ``control.csv``
S0_data_dictionary.xlsx        sheets Tables, Notes, Metrics, Columns       ``data_dictionary_*.csv``
PROVENANCE.txt                 roots, run manifests, script commit
=============================  ===========================================  ========================

The tables are copied unchanged, apart from row order. The data dictionary is maintained as plain
CSV next to this script (``data_dictionary_notes.csv``, ``data_dictionary_metrics.csv``,
``data_dictionary_columns.csv``) and rendered to one workbook. The script refuses to write when a
``label`` is empty, a column name contains characters other than letters, digits and ``_``, or the
columns of a table and its dictionary entries differ.

Usage::

    python export_supplement.py --root out/full-amd --root out/full-pi --out out/supplement
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

from framed_runs import ORDER

HERE = Path(__file__).resolve().parent
TABLES = {
    "S1_latency.csv": "summary.csv",
    "S2_completeness_outputs.csv": "consumption.csv",
    "S3_completeness_inputs.csv": "input_consumption.csv",
    "S4_ordering.csv": "ordering.csv",
    "S5_load.csv": "load.csv",
    "S6_control.csv": "control.csv",
}
NAME_OK = re.compile(r"^[A-Za-z0-9_]+$")
RANK = {k: i for i, k in enumerate(ORDER)}
TITLES = {
    "S1": "Latency statistics per platform, execution mode, reactor and metric",
    "S2": "Output-based consumption completeness per reactor",
    "S3": "Input-side consumption completeness of OR-rule reactors (A4, A10, A12, A13)",
    "S4": "Ordering correctness per reactor output and device channel group",
    "S5": "Load indicators per platform and execution mode",
    "S6": "Run-to-run differences: control repetitions vs. atomic/non-atomic",
}


def write_dictionary(path: Path, tables: dict[str, pd.DataFrame]) -> None:
    """Render the CSV data dictionary to one simple workbook (Arial, header row, wrapped text)."""
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Border, Font, Side

    sheets = {
        "Tables": pd.DataFrame([{"Table": k[:2], "File": k, "Content": TITLES[k[:2]], "Rows": len(v)}
                                for k, v in tables.items()]),
        "Notes": pd.read_csv(HERE / "data_dictionary_notes.csv"),
        "Metrics": pd.read_csv(HERE / "data_dictionary_metrics.csv"),
        "Columns": pd.read_csv(HERE / "data_dictionary_columns.csv").fillna(""),
    }
    widths = {"Table": 8, "File": 30, "Content": 70, "Rows": 8, "Item": 22, "Description": 90,
              "Metric": 18, "One value per": 34, "Definition": 80, "Unit": 8, "Column": 34}
    wb = Workbook()
    wb.remove(wb.active)
    rule = Side(style="thin", color="000000")
    for name, df in sheets.items():
        ws = wb.create_sheet(name)
        ws.append(list(df.columns))
        for row in df.itertuples(index=False):
            ws.append(list(row))
        for j, col in enumerate(df.columns, 1):
            letter = ws.cell(row=1, column=j).column_letter
            ws.column_dimensions[letter].width = widths.get(col, 16)
        for r, cells in enumerate(ws.iter_rows(), 1):
            for c in cells:
                c.font = Font(name="Arial", size=10, bold=(r == 1))
                c.alignment = Alignment(wrap_text=True, vertical="top")
                if r == 1:
                    c.border = Border(top=rule, bottom=rule)
        ws.freeze_panes = "A2"
    wb.save(path)


def check_dictionary(tables: dict[str, pd.DataFrame]) -> list[str]:
    cols = pd.read_csv(HERE / "data_dictionary_columns.csv")
    problems = []
    for name, df in tables.items():
        doc = list(cols.loc[cols["Table"] == name[:2], "Column"])
        missing = [c for c in df.columns if c not in doc]
        extra = [c for c in doc if c not in df.columns]
        if missing or extra:
            problems.append(f"{name}: not in data_dictionary_columns.csv {missing}, no longer in the table {extra}")
    return problems


def sort_rows(df: pd.DataFrame) -> pd.DataFrame:
    """Platform, mode, metric/scope, then A1 … A13, then other labels."""
    keys = [c for c in ("metric", "scope", "platform", "mode", "contrast", "recording") if c in df.columns]
    if "label" in df.columns:
        df = df.assign(_rank=df["label"].map(RANK).fillna(len(RANK)))
        keys.append("_rank")
    return df.sort_values(keys, kind="stable").drop(columns="_rank", errors="ignore")


JAR = re.compile(r"^(?:.*/)?(?P<name>[^/\s]+\.jar)\s*\((?P<sha>[0-9a-f]{64})\)$")


def manifest_lines(root: Path) -> list[str]:
    """Provenance lines of the run manifests, without local paths (they name user directories)."""
    out = []
    for m in sorted(root.glob("*_manifest.txt")):
        seen = set()
        for line in m.read_text(encoding="utf-8", errors="replace").splitlines():
            key, _, value = line.partition(":")
            key, value = key.strip(), value.strip()
            if key not in ("git commit", "git branch", "git dirty", "jar", "java", "os", "cpu",
                           "started", "finished") or key in seen:
                continue
            if key == "jar":
                j = JAR.match(value)
                if not j:
                    continue
                value = f"{j['name']} (sha256 {j['sha']})"
            seen.add(key)
            out.append(f"  {key}: {value}")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path, action="append",
                    help="results root with an analysis/ folder; repeat per platform")
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    problems, frames = [], {}
    for out_name, src in TABLES.items():
        parts = []
        for root in args.root:
            path = root / "analysis" / src
            if not path.is_file():
                problems.append(f"missing {path}")
                continue
            parts.append(pd.read_csv(path))
        if not parts:
            continue
        df = pd.concat(parts, ignore_index=True)
        bad_cols = [c for c in df.columns if not NAME_OK.match(c)]
        if bad_cols:
            problems.append(f"{src}: column names {bad_cols} (re-run the analysis scripts)")
        if "label" in df.columns and df["label"].isna().any():
            problems.append(f"{src}: {int(df['label'].isna().sum())} rows without label (re-run the analysis scripts)")
        frames[out_name] = sort_rows(df)
    problems += check_dictionary(frames)
    if problems:
        print("Not written:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    for out_name, df in frames.items():
        df.to_csv(args.out / out_name, index=False)
    write_dictionary(args.out / "S0_data_dictionary.xlsx", frames)

    try:
        commit = subprocess.run(["git", "-C", str(HERE), "rev-parse", "HEAD"], capture_output=True,
                                text=True, check=True).stdout.strip()
        dirty = subprocess.run(["git", "-C", str(HERE), "status", "--porcelain", "--", "."],
                               capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        commit, dirty = "unknown", ""
    lines = [f"generated: {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
             f"analysis scripts: experiments/arn-latency at commit {commit}"
             + (" (with uncommitted changes in experiments/arn-latency)" if dirty else ""),
             "results roots:"]
    for root in args.root:
        lines.append(f"- results root {root.resolve().name}")
        lines += manifest_lines(root)
    lines += ["tables:"] + [f"- {k} <- analysis/{v} ({len(frames[k])} rows)" for k, v in TABLES.items()]
    (args.out / "PROVENANCE.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {len(frames)} tables, S0_data_dictionary.xlsx and PROVENANCE.txt to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
