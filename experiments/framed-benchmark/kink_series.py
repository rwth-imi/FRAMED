# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Comparison of kink-classification series and the Java log figures of the kink-over-TCP study.

Two series (a distributed run's sink capture, or a single-process control) carry different
absolute time bases: the control keeps the recording's own stamps, the distributed run carries
them shifted to replay start. ``compare`` therefore aligns both on (milliseconds since an origin,
channel), choosing the origins among the leading events that align the most events, and reports
missing / extra events, ``Kink`` label mismatches and the distribution of ``|Δ Kink-Probability|``.

``scrape_java_log`` reads the ``CountingDispatcher`` summary (received, dropped, frame-to-verdict
latency) and the replay's pacing line out of a distributed run's Java log.

Used by ``analyze_kink.py``; the functions are those of the earlier per-run report, unchanged.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

#: Probabilities are floats computed by the same code on the same inputs, so they should be bit
#: identical; this tolerance only absorbs the JSON round trip.
PROBABILITY_TOLERANCE = 1e-9

#: How many leading events to try as the alignment anchor. Anchoring naively on each series' first
#: event breaks catastrophically if one series is missing its head: every later event would then
#: appear both missing and unexpected. Trying a handful of anchors and keeping the one that aligns
#: most events turns that failure into the single missing event it actually is.
ANCHOR_CANDIDATES = 10


#: The replay's own summary line, e.g.
#: ``Replay finished: events=15029 wall=608.0s achieved=24.72 Hz target=24.72 Hz lag(mean/max)=0.2/22 ms``
PACING_PATTERNS = {
    "events": r"events=(\d+)",
    "producerAchievedHz": r"achieved=([\d.]+) Hz",
    "producerTargetHz": r"target=([\d.]+) Hz",
    "producerLagMeanMs": r"lag\(mean/max\)=([\d.-]+)/",
    "producerLagMaxMs": r"lag\(mean/max\)=[\d.-]+/([\d-]+) ms",
}

FIGURE_PATTERNS = {
    "received": r"received=(\d+)",
    "dropped": r"dropped=(\d+)",
    "handlerErrors": r"handlerErrors=(\d+)",
    "achievedDpPerS": r"achieved=([\d.]+) dp/s",
    "latencyMeanMs": r"latency\(mean/p50/p95/p99/max\)=([\d.]+)/",
    "latencyP50Ms": r"latency\(mean/p50/p95/p99/max\)=[\d.]+/(\d+)/",
    "latencyP95Ms": r"latency\(mean/p50/p95/p99/max\)=[\d.]+/\d+/(\d+)/",
    "latencyP99Ms": r"latency\(mean/p50/p95/p99/max\)=[\d.]+/\d+/\d+/(\d+)/",
    "latencyMaxMs": r"latency\(mean/p50/p95/p99/max\)=[\d.]+/\d+/\d+/\d+/(\d+) ms",
}


def parse_iso(text: str) -> float:
    """Epoch seconds from either spelling the two sides emit (``...Z`` or bare microseconds)."""
    candidate = text.strip()
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    from datetime import datetime, timezone

    moment = datetime.fromisoformat(candidate)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.timestamp()


def load_series(path: Path) -> List[Dict[str, Any]]:
    """Reads a classification capture as ``{epoch, channelID, value}``, sorted by time."""
    records = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        event = json.loads(line)
        channel = event.get("channelID")
        if channel not in ("Kink", "Kink-Probability"):
            continue
        records.append({
            "epoch": parse_iso(event["timestamp"]),
            "channelID": channel,
            "value": event.get("value"),
        })
    records.sort(key=lambda item: (item["epoch"], item["channelID"]))
    return records


def index_series(series: List[Dict[str, Any]],
                 origin: float) -> Tuple[Dict[Tuple[float, str], Any], int]:
    """Keys a series by (milliseconds since ``origin``, channel), counting the keys that collide.

    Both sides derive their stamps from the same recorded timestamps — the distributed run's are
    those stamps shifted by one constant — so once the two origins are matched the keys coincide
    exactly at millisecond resolution rather than approximately.

    The collision count is not incidental. ``FeatureScalingReactor`` publishes every vector pending
    at a firing stamped with that one firing's logical time, so under backlog two classifications
    can share a timestamp — and a comparison keyed by timestamp would silently keep one of them.
    Without the count, a series carrying two verdicts at one instant and a series carrying one
    compare as *identical*. ``compare`` therefore reports it and the report refuses to certify
    agreement while it is non-zero.
    """
    index: Dict[Tuple[float, str], Any] = {}
    collapsed = 0
    for item in series:
        key = (round(item["epoch"] - origin, 3), item["channelID"])
        if key in index:
            collapsed += 1
        index[key] = item["value"]
    return index, collapsed


def probability_delta_stats(deltas: List[float]) -> Dict[str, float]:
    """Summarises ``|Δ Kink-Probability|`` over the aligned instants.

    The maximum alone is not a comparator. It is a single sample, and the probability traverses the
    whole [0, 1] range within a few frames at a kink onset, so at a transition edge a one-frame
    difference in *which* firing emitted shows up as a large pointwise difference while every other
    instant agrees to 1e-6. Quoting the max as the disagreement between two deployments is the same
    error as quoting ``latencyMax`` as the latency — which is why the percentiles travel with it.
    """
    if not deltas:
        return {"probabilityDeltaMax": 0.0, "probabilityDeltaP99": 0.0,
                "probabilityDeltaP95": 0.0, "probabilityDeltaP50": 0.0,
                "probabilityDeltaOver01": 0}
    ordered = sorted(deltas)

    def percentile(fraction: float) -> float:
        # Nearest-rank on the sorted sample: no interpolation, so every reported figure is one
        # actually measured difference rather than a value between two of them.
        index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
        return ordered[index]

    return {
        "probabilityDeltaMax": ordered[-1],
        "probabilityDeltaP99": percentile(0.99),
        "probabilityDeltaP95": percentile(0.95),
        "probabilityDeltaP50": percentile(0.50),
        # How many instants exceed a tenth of the [0, 1] range: the count that says whether a large
        # maximum is a lone transition-edge sample or a systematic offset.
        "probabilityDeltaOver01": sum(1 for value in ordered if value > 0.1),
    }


def _align(control: List[Dict[str, Any]], observed: List[Dict[str, Any]]) -> Tuple[float, float]:
    """Picks the pair of origins that aligns the most events.

    The two series carry different absolute time bases and either may be missing its first events,
    so the origins are searched over the leading events of both rather than assumed to be the first
    of each.
    """
    best = (control[0]["epoch"], observed[0]["epoch"], -1)
    for control_anchor in control[:ANCHOR_CANDIDATES]:
        for observed_anchor in observed[:ANCHOR_CANDIDATES]:
            if control_anchor["channelID"] != observed_anchor["channelID"]:
                continue
            control_origin, observed_origin = control_anchor["epoch"], observed_anchor["epoch"]
            matched = len(set(index_series(control, control_origin)[0])
                          & set(index_series(observed, observed_origin)[0]))
            if matched > best[2]:
                best = (control_origin, observed_origin, matched)
    return best[0], best[1]


def compare(control: List[Dict[str, Any]], observed: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Aligns the two series on (offset, channel) and reports where they differ."""
    control_origin, observed_origin = _align(control, observed)
    control_index, control_collapsed = index_series(control, control_origin)
    observed_index, observed_collapsed = index_series(observed, observed_origin)
    missing = sorted(set(control_index) - set(observed_index))
    extra = sorted(set(observed_index) - set(control_index))
    shared = sorted(set(control_index) & set(observed_index))

    label_mismatches = []
    probability_deltas: List[float] = []
    for key in shared:
        expected, actual = control_index[key], observed_index[key]
        if key[1] == "Kink":
            if expected != actual:
                label_mismatches.append((key[0], expected, actual))
        else:
            if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
                probability_deltas.append(abs(float(expected) - float(actual)))
            elif expected != actual:
                label_mismatches.append((key[0], expected, actual))
    delta_stats = probability_delta_stats(probability_deltas)

    return {
        "controlCount": len(control),
        "observedCount": len(observed),
        # The unique-key counts are what "matchedCount" is a fraction of; the raw counts above are
        # not comparable with it whenever either side collapsed a key.
        "controlUnique": len(control_index),
        "observedUnique": len(observed_index),
        "collapsedControl": control_collapsed,
        "collapsedObserved": observed_collapsed,
        "matchedCount": len(shared),
        # `matchedCount` spans both channels, so it is twice the denominator a label-mismatch
        # figure is a fraction of. Quoting the wrong one halves an apparent disagreement rate.
        "matchedKink": sum(1 for key in shared if key[1] == "Kink"),
        "missing": missing,
        "extra": extra,
        "labelMismatches": label_mismatches,
        "maxProbabilityDelta": delta_stats["probabilityDeltaMax"],
        **delta_stats,
    }


def per_channel(series: List[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for record in series:
        counts[record["channelID"]] = counts.get(record["channelID"], 0) + 1
    return counts


def scrape_java_log(path: Path) -> Tuple[Dict[str, Any], Optional[str], Optional[str]]:
    """Pulls the sink summary and the replay pacing line out of the Java log."""
    text = path.read_text(errors="replace") if path.exists() else ""
    summary_line = next((line for line in text.splitlines()
                         if "CountingDispatcher summary" in line), None)
    pacing_line = next((line for line in text.splitlines() if "Replay finished:" in line), None)

    figures: Dict[str, Any] = {}
    if summary_line:
        for name, pattern in FIGURE_PATTERNS.items():
            match = re.search(pattern, summary_line)
            if match:
                value = match.group(1)
                figures[name] = float(value) if "." in value else int(value)
    if pacing_line:
        for name, pattern in PACING_PATTERNS.items():
            match = re.search(pattern, pacing_line)
            if match:
                value = match.group(1)
                figures[name] = float(value) if "." in value else int(value)
    return figures, summary_line, pacing_line
