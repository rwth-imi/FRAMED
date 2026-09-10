#!/usr/bin/env python3
"""Compares two kink-classification series against each other.

``analyse-kink-tcp.py`` compares one run's sink capture against that run's single-process control
and calls any difference a divergence. That is the right check only if the control is itself
reproducible — if two controls of the same recording disagree with each other, a run-versus-control
difference says nothing about the transport.

This tool makes that question answerable by comparing *any* two series with the same alignment the
run analysis uses, so a **control-versus-control** pair can be measured on the same scale as a
**run-versus-control** pair. It also reports two quantities the run analysis cannot, because its
index is a plain dict keyed by ``(offset, channel)``:

* **collapsed** — classifications sharing an already-used ``(offset, channel)`` key. The feature
  reactor stamps every vector pending at a firing with that one firing's logical time, so a
  suppressed firing makes two vectors land on one timestamp. A dict keeps the last of them; this
  counts how many were dropped on each side before anything is compared.
* **unique keys** — the series' size after that collapse, which is what the alignment actually sees.

Usage:
    compare-series.py A.jsonl B.jsonl [--label-a control-1] [--label-b control-2] [--json out.json]
"""
from __future__ import annotations

import argparse
import csv
import importlib.util
import json
from pathlib import Path
from typing import Any, Dict, List


def _load_analysis():
    """Imports ``analyse-kink-tcp.py`` by path.

    It is a script, not a module, and its filename is not an identifier — but the alignment used
    here must be *literally* the same code as the run report's, not a copy that can drift from it.
    """
    path = Path(__file__).resolve().parent / "analyse-kink-tcp.py"
    spec = importlib.util.spec_from_file_location("kink_tcp_analysis", path)
    if spec is None or spec.loader is None:            # pragma: no cover - a broken checkout only
        raise SystemExit("cannot load %s" % path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_analysis = _load_analysis()

load_series = _analysis.load_series
PROBABILITY_TOLERANCE = _analysis.PROBABILITY_TOLERANCE
probability_delta_stats = _analysis.probability_delta_stats

#: One row per compared pair. The agreement table of the study's README is read straight off this
#: file, so the file has to be *derived* — transcribing figures from six console outputs into a
#: table by hand is how a count ends up in the narrative that the data does not carry.
AGREEMENT_COLUMNS = [
    "pair", "dispatch", "deployment", "countA", "countB", "uniqueA", "uniqueB",
    "collapsedA", "collapsedB", "aligned", "alignedKink", "alignedFraction", "onlyInA", "onlyInB",
    "labelMismatches", "maxProbabilityDelta", "probabilityDeltaP99", "probabilityDeltaP95",
    "probabilityDeltaP50", "probabilityDeltaOver01", "identical",
]


#: Keys a series by ``(ms since origin, channel)``, counting the keys that collide. Taken from the
#: run analysis rather than re-implemented, for the same reason ``_align`` is: the two tools are
#: read against each other, so a divergence between their keying would be invisible and wrong.
collapse = _analysis.index_series


def compare(a: List[Dict[str, Any]], b: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Aligns two series on their own first classification and reports where they differ."""
    if not a or not b:
        raise SystemExit("both series must be non-empty (a=%d, b=%d)" % (len(a), len(b)))
    origin_a, origin_b = _analysis._align(a, b)
    index_a, collapsed_a = collapse(a, origin_a)
    index_b, collapsed_b = collapse(b, origin_b)

    shared = sorted(set(index_a) & set(index_b))
    label_mismatches = 0
    deltas: List[float] = []
    for key in shared:
        expected, actual = index_a[key], index_b[key]
        if key[1] == "Kink":
            label_mismatches += int(expected != actual)
        elif isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
            deltas.append(abs(float(expected) - float(actual)))
        elif expected != actual:
            label_mismatches += 1
    stats = probability_delta_stats(deltas)
    max_delta = stats["probabilityDeltaMax"]

    return {
        "countA": len(a), "countB": len(b),
        "collapsedA": collapsed_a, "collapsedB": collapsed_b,
        "uniqueA": len(index_a), "uniqueB": len(index_b),
        "aligned": len(shared),
        # The denominator a label-mismatch count is a fraction of: `aligned` spans both channels.
        "alignedKink": sum(1 for key in shared if key[1] == "Kink"),
        "onlyInA": len(set(index_a) - set(index_b)),
        "onlyInB": len(set(index_b) - set(index_a)),
        "labelMismatches": label_mismatches,
        "maxProbabilityDelta": max_delta,
        **stats,
        "alignedFraction": len(shared) / max(len(index_a), len(index_b)),
        "identical": (set(index_a) == set(index_b) and label_mismatches == 0
                      and max_delta <= PROBABILITY_TOLERANCE),
    }


def _self_test() -> int:
    """Checks the two properties real runs cannot check.

    Measured series so far collapse nothing, so the collapse counter — the reason this tool exists
    alongside the run analysis — is never exercised by the data. It is checked here instead, on
    series small enough to verify by eye, together with the identity property that any comparison
    must satisfy before its disagreements mean anything.
    """
    import tempfile

    def write(directory: Path, name: str, rows: List[tuple]) -> Path:
        path = directory / name
        with path.open("w") as handle:
            for stamp, channel, value in rows:
                handle.write(json.dumps({"timestamp": stamp, "channelID": channel,
                                         "value": value}) + "\n")
        return path

    base = [("2026-01-01T00:00:00.000000Z", "Kink", 0),
            ("2026-01-01T00:00:00.320000Z", "Kink", 1),
            ("2026-01-01T00:00:00.640000Z", "Kink", 1)]
    failures = []
    with tempfile.TemporaryDirectory() as raw:
        directory = Path(raw)
        a = write(directory, "a.jsonl", base)
        # Same series, every stamp shifted by a constant: the offset normalisation must absorb it.
        shifted = write(directory, "shifted.jsonl",
                        [("2026-03-05T09:10:11.000000Z", "Kink", 0),
                         ("2026-03-05T09:10:11.320000Z", "Kink", 1),
                         ("2026-03-05T09:10:11.640000Z", "Kink", 1)])
        # Two verdicts stamped with one firing's logical time — the collapse case.
        collapsing = write(directory, "collapsing.jsonl",
                           base + [("2026-01-01T00:00:00.640000Z", "Kink", 0)])

        identity = compare(load_series(a), load_series(a))
        if not identity["identical"] or identity["aligned"] != 3:
            failures.append("a series must be identical to itself: %r" % identity)

        shift = compare(load_series(a), load_series(shifted))
        if not shift["identical"]:
            failures.append("a constant time shift must not register as a difference: %r" % shift)

        collapse_result = compare(load_series(a), load_series(collapsing))
        if collapse_result["collapsedB"] != 1 or collapse_result["uniqueB"] != 3:
            failures.append("a duplicated (offset, channel) must count as one collapse: %r"
                            % collapse_result)
        if collapse_result["countB"] != 4:
            failures.append("the raw count must stay 4: %r" % collapse_result)

    for failure in failures:
        print("FAIL: %s" % failure)
    print("self-test: %s" % ("FAILED" if failures else "4 checks passed"))
    return 1 if failures else 0


def _append_agreement(path: Path, row: Dict[str, Any]) -> None:
    """Appends one compared pair, writing the header if the file carries no rows yet.

    Emptiness rather than absence is the condition, and a file whose header does not match the
    columns is refused rather than appended to — both for the reasons the sweep CSV's writer
    documents: a head-less CSV reads its first row as a header, and a foreign header silently
    shifts every value of the new row against the names it will be read under.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    empty = not path.exists() or path.stat().st_size == 0
    if not empty:
        existing = next(csv.reader(path.open(newline="")), [])
        if existing != AGREEMENT_COLUMNS:
            raise SystemExit(
                "%s was written under a different schema (%d columns, expected %d); re-derive it "
                "or point --csv elsewhere." % (path, len(existing), len(AGREEMENT_COLUMNS)))
    with path.open("a", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=AGREEMENT_COLUMNS, extrasaction="ignore")
        if empty:
            writer.writeheader()
        writer.writerow({key: row.get(key, "") for key in AGREEMENT_COLUMNS})


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true",
                        help="check the alignment and collapse accounting on synthetic series "
                             "and exit; takes no positional arguments")
    parser.add_argument("a", nargs="?", help="the reference series (.jsonl)")
    parser.add_argument("b", nargs="?", help="the series to compare against it (.jsonl)")
    parser.add_argument("--label-a", default="A")
    parser.add_argument("--label-b", default="B")
    parser.add_argument("--json", help="also write the result as JSON here")
    parser.add_argument("--csv", help="append the pair as one row of an agreement CSV")
    parser.add_argument("--pair", help="the pair's name in the CSV (defaults to \"A vs B\")")
    parser.add_argument("--dispatch", default="", help="the pair's bus dispatch mode, for the CSV")
    parser.add_argument("--deployment", default="",
                        help="single-process / distributed / distributed-vs-single, for the CSV")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    if not args.a or not args.b:
        parser.error("two series are required (or --self-test)")

    result = compare(load_series(Path(args.a)), load_series(Path(args.b)))
    result["labelA"], result["labelB"] = args.label_a, args.label_b

    print("%s vs %s" % (args.label_a, args.label_b))
    print("  classifications        %6d / %6d" % (result["countA"], result["countB"]))
    print("  collapsed onto a used key %3d / %6d" % (result["collapsedA"], result["collapsedB"]))
    print("  distinct (offset, channel) %4d / %6d" % (result["uniqueA"], result["uniqueB"]))
    print("  aligned                %6d  (%.1f %% of the larger series)"
          % (result["aligned"], 100.0 * result["alignedFraction"]))
    print("  only in %-14s %6d" % (args.label_a, result["onlyInA"]))
    print("  only in %-14s %6d" % (args.label_b, result["onlyInB"]))
    print("  aligned on Kink        %6d  (the rest of `aligned` is Kink-Probability)"
          % result["alignedKink"])
    print("  Kink label mismatches  %6d  (of %d aligned on Kink)"
          % (result["labelMismatches"], result["alignedKink"]))
    print("  |Δ Kink-Probability| p50/p95/p99 %.3g / %.3g / %.3g"
          % (result["probabilityDeltaP50"], result["probabilityDeltaP95"],
             result["probabilityDeltaP99"]))
    print("  max |Δ Kink-Probability| %.4g  (%d instant(s) above 0.1)"
          % (result["maxProbabilityDelta"], result["probabilityDeltaOver01"]))
    print("  verdict: %s" % ("identical" if result["identical"] else "different"))

    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=2) + "\n")
    if args.csv:
        _append_agreement(Path(args.csv), dict(
            result,
            pair=args.pair or "%s vs %s" % (args.label_a, args.label_b),
            dispatch=args.dispatch, deployment=args.deployment))
        print("appended a row to %s" % args.csv)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
