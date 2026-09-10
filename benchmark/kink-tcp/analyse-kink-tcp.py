#!/usr/bin/env python3
"""Turns one run of the Java-replay/TCP kink experiment into a report.

Two independent questions, answered from two independent artefacts of the same run:

**Did the distributed pipeline compute the same thing?** The Java sink's capture
(``classifications.jsonl``, written by ``JsonlDispatcher``) is compared event for event against the
single-process control from ``baseline.py``. The two use different absolute time bases — the
control carries the recording's own stamps, the distributed run carries them shifted to replay
start — so both series are normalised to seconds since their own first classification before they
are aligned. That normalisation is exact rather than approximate: the replay shifts every stamp by
one constant.

**How did it perform?** The ``CountingDispatcher`` summary and the replay's pacing line are read
out of the Java log. The sink's latency is an emit->sink latency in the strict sense: the
classification carries the logical timestamp of the ventilator frame that produced it, so
``now - timestamp`` at the sink is the age of that frame when its verdict arrived — replay, TCP
hop, three reactors, TCP hop back and sink queue included.

Usage: analyse-kink-tcp.py --run-dir benchmark/results/kink-tcp-<timestamp>
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
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

#: One row per run, appended as each run completes, so a sweep is analysable before it finishes.
DEFAULT_CSV = "benchmark/results/kink-tcp-sweep.csv"

CSV_COLUMNS = [
    "run", "speed", "record", "model",
    "events", "producerAchievedHz", "producerTargetHz", "producerLagMeanMs", "producerLagMaxMs",
    "received", "dropped", "handlerErrors", "achievedDpPerS",
    "latencyMeanMs", "latencyP50Ms", "latencyP95Ms", "latencyP99Ms", "latencyMaxMs",
    "kink", "kinkProbability",
    "controlSpeed", "controlCount", "controlCoverage", "controlTruncated",
    "controlUnique", "observedUnique", "collapsedControl", "collapsedObserved",
    "matched", "matchedKink", "missing", "extra", "labelMismatches",
    "maxProbabilityDelta", "probabilityDeltaP99", "probabilityDeltaP95", "probabilityDeltaP50",
    "probabilityDeltaOver01", "agreement",
]

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


def model_artefact(run_dir: Path) -> Optional[str]:
    """The model artefact a run used, read from its own rendered pyFRAMED config.

    The three reactors are all pointed at one ``modelPath``, so the rendered config is an
    authoritative record of which artefact produced a run's verdicts — and unlike ``run.json`` it
    is present in every run directory ever produced, including those measured before the runner
    started recording it. Returns ``None`` when the config is absent or names no model, and the
    reactors' paths when, against the template, they disagree.
    """
    config = run_dir / "services_python.json"
    if not config.exists():
        return None
    try:
        reactors = json.loads(config.read_text()).get("Reactors", [])
    except (json.JSONDecodeError, OSError):
        return None
    paths = {reactor.get("modelPath") for reactor in reactors if reactor.get("modelPath")}
    return "; ".join(sorted(paths)) or None


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


def _append_csv(path: Path, row: Dict[str, Any]) -> None:
    """Appends one run to the sweep CSV, writing the header if the file carries no rows yet.

    Emptiness, not absence, is the condition: starting a fresh sweep by truncating the previous one
    in place (``: > file``) is the obvious thing to do and leaves a file that exists but holds
    nothing, which an existence check would then append to head-less — a CSV whose first data row
    is silently read as its header.

    Written with ``Locale``-free formatting for the same reason the throughput study's CSV is: a
    decimal comma would split a float across two columns and shift every downstream field.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    empty = not path.exists() or path.stat().st_size == 0
    if not empty:
        # DictWriter emits fields in CSV_COLUMNS order whatever the file's own header says, so
        # appending to a CSV written under an older schema shifts every value of the new row
        # against the header that will be used to read it. Refuse instead: the sweep is cheap to
        # re-derive from the run directories, a silently misaligned column is not.
        existing = next(csv.reader(path.open(newline="")), [])
        if existing != CSV_COLUMNS:
            raise SystemExit(
                "%s was written under a different schema (%d columns, expected %d); re-derive it "
                "or point --csv elsewhere.\n  file: %s\n  code: %s"
                % (path, len(existing), len(CSV_COLUMNS), existing, CSV_COLUMNS))
    with path.open("a", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS, extrasaction="ignore")
        if empty:
            writer.writeheader()
        writer.writerow({key: row.get(key, "") for key in CSV_COLUMNS})


def _self_test() -> int:
    """Checks what a measured run cannot check about the CSV, the model column and the alignment.

    Each is a silent failure rather than a crash, which is why they get a test: a head-less CSV
    still parses (its first run is read as the header), a missing model column still writes a row,
    and two series that collapse onto shared timestamps still compare as *identical*. None of them
    shows up in a run's own report.
    """
    import tempfile

    failures = []

    # A key shared by two classifications must be counted, and must stop `compare` certifying
    # agreement: a control holding two verdicts at one instant and a run holding one are not the
    # same series, but keyed by (instant, channel) they look like one.
    def series(*items):
        return [{"epoch": epoch, "channelID": channel, "value": value}
                for epoch, channel, value in items]

    twinned = series((100.0, "Kink", 0), (100.0, "Kink", 1), (101.0, "Kink", 1))
    single = series((100.0, "Kink", 1), (101.0, "Kink", 1))
    _, collapsed = index_series(twinned, 100.0)
    if collapsed != 1:
        failures.append("one shared instant must count as one collapse, got %d" % collapsed)
    _, clean = index_series(single, 100.0)
    if clean != 0:
        failures.append("distinct instants must collapse nothing, got %d" % clean)
    result = compare(twinned, single)
    if result["collapsedControl"] != 1 or result["collapsedObserved"] != 0:
        failures.append("compare must carry the collapse counts, got %r"
                        % {k: result[k] for k in ("collapsedControl", "collapsedObserved")})
    if result["missing"] or result["extra"] or result["labelMismatches"]:
        failures.append("the collapse must be invisible to the difference sets — that is the "
                        "point of counting it — got %r" % result)
    if result["controlUnique"] != 2 or result["controlCount"] != 3:
        failures.append("unique and raw counts must differ under a collapse, got %d/%d"
                        % (result["controlUnique"], result["controlCount"]))

    # One transition-edge outlier in an otherwise exact series: the maximum must show it and the
    # percentiles must not, which is the whole reason both are reported.
    spiky = probability_delta_stats([0.0] * 99 + [0.9])
    if spiky["probabilityDeltaMax"] != 0.9 or spiky["probabilityDeltaOver01"] != 1:
        failures.append("the outlier must reach the max and the >0.1 count, got %r" % spiky)
    if spiky["probabilityDeltaP95"] != 0.0 or spiky["probabilityDeltaP50"] != 0.0:
        failures.append("a 1-in-100 outlier must not move p95 or p50, got %r" % spiky)
    if probability_delta_stats([])["probabilityDeltaMax"] != 0.0:
        failures.append("an empty delta sample must summarise to zeroes, not raise")
    with tempfile.TemporaryDirectory() as raw:
        directory = Path(raw)
        row = {"run": "r1", "speed": 1.0, "model": "m.json"}

        # A fresh sweep started by truncating the previous one in place: the file exists and is
        # empty, and the header must still be written. This is the regression.
        truncated = directory / "truncated.csv"
        truncated.touch()
        _append_csv(truncated, row)
        lines = truncated.read_text().splitlines()
        if not lines or not lines[0].startswith("run,"):
            failures.append("a truncated CSV must be re-headered, got %r" % lines[:1])
        if len(lines) != 2:
            failures.append("a truncated CSV must hold header + 1 row, got %d lines" % len(lines))

        # An absent file behaves the same way, and a populated one must not gain a second header.
        fresh = directory / "fresh.csv"
        _append_csv(fresh, row)
        _append_csv(fresh, dict(row, run="r2"))
        lines = fresh.read_text().splitlines()
        if len(lines) != 3 or lines[0].count("run,") != 1:
            failures.append("appending must add rows, not headers, got %d lines" % len(lines))
        if sum(line.startswith("run,") for line in lines) != 1:
            failures.append("exactly one header line expected: %r" % lines)
        if [r["run"] for r in csv.DictReader(fresh.open())] != ["r1", "r2"]:
            failures.append("both rows must read back under the header: %r" % lines)

        # The model column is resolved from the run's own rendered config, not from run.json.
        run_dir = directory / "run"
        run_dir.mkdir()
        if model_artefact(run_dir) is not None:
            failures.append("a run directory with no rendered config must resolve to None")
        (run_dir / "services_python.json").write_text(json.dumps(
            {"Reactors": [{"modelPath": "config/models/a.json"},
                          {"modelPath": "config/models/a.json"}]}))
        if model_artefact(run_dir) != "config/models/a.json":
            failures.append("one shared modelPath must resolve to itself, got %r"
                            % model_artefact(run_dir))
        (run_dir / "services_python.json").write_text(json.dumps(
            {"Reactors": [{"modelPath": "b.json"}, {"modelPath": "a.json"}]}))
        if model_artefact(run_dir) != "a.json; b.json":
            failures.append("disagreeing modelPaths must all be reported, got %r"
                            % model_artefact(run_dir))
        (run_dir / "services_python.json").write_text("{ not json")
        if model_artefact(run_dir) is not None:
            failures.append("an unparseable config must resolve to None, not raise")

        # A CSV written under an older schema must be refused, not appended to: DictWriter would
        # otherwise place the new columns' values under the old header's names.
        stale = directory / "stale.csv"
        stale.write_text("run,speed\nr0,1.0\n")
        try:
            _append_csv(stale, row)
            failures.append("appending to a foreign schema must raise, it did not")
        except SystemExit:
            pass

    for failure in failures:
        print("FAIL: %s" % failure)
    print("self-test: %s" % ("FAILED" if failures else "19 checks passed"))
    return 1 if failures else 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true",
                        help="check the sweep CSV's header handling, the model-column "
                             "resolution and the collapse accounting on synthetic inputs and "
                             "exit; takes no other arguments")
    parser.add_argument("--run-dir", help="the run directory the runner created")
    parser.add_argument("--csv", default=DEFAULT_CSV,
                        help="sweep CSV to append this run's row to (\"\" to skip)")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    if not args.run_dir:
        parser.error("--run-dir is required (or --self-test)")

    run_dir = Path(args.run_dir)
    baseline_path = run_dir / "baseline.jsonl"
    captures = sorted((run_dir / "capture").glob("*classifications.jsonl"))
    java_log = run_dir / "java.log"

    if not captures:
        print("no classification capture in %s/capture — did the Java sink bind 'CDSS'?" % run_dir,
              file=sys.stderr)
        return 1
    observed = load_series(captures[-1])
    control = load_series(baseline_path) if baseline_path.exists() else []
    control_meta_path = Path(str(baseline_path) + ".meta.json")
    control_meta = json.loads(control_meta_path.read_text()) if control_meta_path.exists() else {}

    figures, summary_line, pacing_line = scrape_java_log(java_log)
    metadata = json.loads((run_dir / "run.json").read_text()) if (run_dir / "run.json").exists() else {}
    metadata.setdefault("model", model_artefact(run_dir) or "")

    lines: List[str] = []
    add = lines.append
    add("# Kink replay over TCP — run %s" % run_dir.name)
    add("")
    add("| parameter | value |")
    add("| --- | --- |")
    for key in ("record", "speed", "model", "javaPort", "pythonPort", "startDelaySeconds",
                "graceSeconds", "started", "javaExitCode"):
        if key in metadata:
            add("| %s | %s |" % (key, metadata[key]))
    add("")

    add("## Classification output at the Java sink")
    add("")
    add("| channel | received |")
    add("| --- | --- |")
    for channel, count in sorted(per_channel(observed).items()):
        add("| %s | %d |" % (channel, count))
    add("")

    speed = metadata.get("speed")
    accelerated = isinstance(speed, (int, float)) and float(speed) != 1.0

    if control:
        result = compare(control, observed)
        collapsed = result["collapsedControl"] + result["collapsedObserved"]
        agreement = (not result["missing"] and not result["extra"]
                     and not result["labelMismatches"]
                     and result["maxProbabilityDelta"] <= PROBABILITY_TOLERANCE)
        add("## Agreement with the single-process control")
        add("")
        if accelerated:
            # Compressing the recording's timeline by s makes the reactors' 6 s window cover
            # 6*s seconds of recorded session, so the emission grid coarsens and the verdicts
            # legitimately differ. Reporting that as DIVERGED invites the reader to blame the
            # transport for arithmetic.
            agreement = None
            add("> **Speed %s is not comparable to a control.** The reactors' 6 s window covers "
                "6x%s seconds of recorded session at this speed, so the emission grid coarsens and "
                "the verdicts differ by construction, not by defect. Agreement is only meaningful "
                "at `speed = 1.0`; the figures below are recorded, not interpretable."
                % (speed, speed))
            add("")
        if collapsed:
            # Two classifications on one timestamp mean the comparison compared fewer events than
            # either series holds, and the ones it dropped are invisible to every figure below. A
            # clean row here would be an artefact of the keying, not a measured agreement.
            agreement = None
            add("> **%d classification(s) share a timestamp with another** (%d in the control, %d "
                "at the sink). The comparison is keyed by (instant, channel), so each collision "
                "hides one event from every figure below and the result cannot certify agreement. "
                "This is the pending-vector collapse under reactor backlog; re-run the affected "
                "side at a speed its chain can hold."
                % (collapsed, result["collapsedControl"], result["collapsedObserved"]))
            add("")
        if control_meta.get("truncated"):
            # A control whose chain never processed the whole recording is not a reference: the
            # comparison below would report the control's own shortfall as the run's divergence.
            agreement = None
            add("> **The control is truncated** — its classifications reach only %.1f s of the "
                "recording's %.1f s (%.1f %%), so it is not a valid reference and the comparison "
                "below is inconclusive. Re-run the control at the run's own speed."
                % (control_meta.get("coveredSeconds", 0.0),
                   control_meta.get("recordingSeconds", 0.0),
                   100.0 * control_meta.get("coverage", 0.0)))
            add("")
        add("| check | result |")
        add("| --- | --- |")
        add("| control classifications | %d (%d distinct instants) |"
            % (result["controlCount"], result["controlUnique"]))
        add("| observed classifications | %d (%d distinct instants) |"
            % (result["observedCount"], result["observedUnique"]))
        add("| collapsed onto a shared instant | %d control, %d observed |"
            % (result["collapsedControl"], result["collapsedObserved"]))
        add("| aligned | %d (%d on `Kink`, the rest on `Kink-Probability`) |"
            % (result["matchedCount"], result["matchedKink"]))
        add("| missing at the sink | %d |" % len(result["missing"]))
        add("| not in the control | %d |" % len(result["extra"]))
        add("| Kink label mismatches | %d of %d |"
            % (len(result["labelMismatches"]), result["matchedKink"]))
        add("| abs. probability difference p50/p95/p99 | %.3g / %.3g / %.3g |"
            % (result["probabilityDeltaP50"], result["probabilityDeltaP95"],
               result["probabilityDeltaP99"]))
        add("| max abs. probability difference | %.3g (%d instant(s) above 0.1) |"
            % (result["probabilityDeltaMax"], result["probabilityDeltaOver01"]))
        add("| control speed | %s |" % control_meta.get("speed", "unknown"))
        add("| control coverage | %.1f %% |" % (100.0 * control_meta.get("coverage", 0.0))
            if control_meta else "| control coverage | unknown |")
        add("| **verdict** | **%s** |"
            % ("not comparable (accelerated)" if agreement is None and accelerated
               else "identical" if agreement
               else "inconclusive" if agreement is None else "DIVERGED"))
        add("")
        if result["missing"][:10]:
            add("First missing (offset s, channel): %s" % (result["missing"][:10],))
            add("")
        if result["extra"][:10]:
            add("First unexpected (offset s, channel): %s" % (result["extra"][:10],))
            add("")
        if result["labelMismatches"][:10]:
            add("First label mismatches (offset s, control, observed): %s"
                % (result["labelMismatches"][:10],))
            add("")
    else:
        agreement = None
        add("## Agreement with the single-process control")
        add("")
        add("_No control in this run (`baseline.jsonl` absent); agreement was not checked._")
        add("")

    add("## Sink measurement (CountingDispatcher)")
    add("")
    if figures:
        add("| figure | value |")
        add("| --- | --- |")
        for key, value in figures.items():
            add("| %s | %s |" % (key, value))
    else:
        add("_No `CountingDispatcher summary` line in `java.log`._")
    add("")
    if pacing_line:
        add("## Producer pacing")
        add("")
        add("```")
        add(pacing_line.strip())
        add("```")
        add("")
    if summary_line:
        add("```")
        add(summary_line.strip())
        add("```")
        add("")

    report = "\n".join(lines)
    (run_dir / "report.md").write_text(report + "\n")

    row = dict(metadata)
    row.update(figures)
    row["controlCount"] = len(control)
    row["observedCount"] = len(observed)
    if agreement is not None:
        row["agreement"] = "identical" if agreement else "diverged"
    (run_dir / "summary.json").write_text(json.dumps(row, indent=2) + "\n")

    if args.csv:
        counts = per_channel(observed)
        comparison = compare(control, observed) if control else {}
        csv_row = {
            "run": run_dir.name,
            "speed": metadata.get("speed"),
            "record": metadata.get("record"),
            "model": metadata.get("model"),
            "kink": counts.get("Kink", 0),
            "kinkProbability": counts.get("Kink-Probability", 0),
            "controlSpeed": control_meta.get("speed"),
            "controlCount": len(control),
            "controlCoverage": control_meta.get("coverage"),
            "controlTruncated": control_meta.get("truncated"),
            "controlUnique": comparison.get("controlUnique"),
            "observedUnique": comparison.get("observedUnique"),
            "collapsedControl": comparison.get("collapsedControl"),
            "collapsedObserved": comparison.get("collapsedObserved"),
            "matched": comparison.get("matchedCount"),
            "matchedKink": comparison.get("matchedKink"),
            "missing": len(comparison.get("missing", [])) if comparison else None,
            "extra": len(comparison.get("extra", [])) if comparison else None,
            "labelMismatches": len(comparison.get("labelMismatches", [])) if comparison else None,
            "maxProbabilityDelta": comparison.get("maxProbabilityDelta"),
            "probabilityDeltaP99": comparison.get("probabilityDeltaP99"),
            "probabilityDeltaP95": comparison.get("probabilityDeltaP95"),
            "probabilityDeltaP50": comparison.get("probabilityDeltaP50"),
            "probabilityDeltaOver01": comparison.get("probabilityDeltaOver01"),
            "agreement": row.get(
                "agreement",
                ("not-comparable" if accelerated else "inconclusive")
                if agreement is None else None),
        }
        csv_row.update({key: figures.get(key) for key in FIGURE_PATTERNS})
        csv_row.update({key: figures.get(key) for key in PACING_PATTERNS})
        _append_csv(Path(args.csv), csv_row)
        print("appended a row to %s" % args.csv)

    print(report)
    if agreement is False:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
