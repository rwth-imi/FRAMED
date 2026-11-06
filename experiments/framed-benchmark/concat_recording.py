#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes a recording concatenated k times, with the timeline of ReplayProtocol's ``repeat = k``.

A speed-up run at k× replays the recording k times back to back, so it lasts as long as a
real-time run but offers k times the event rate. The distributed run does this with the Java
``ReplayProtocol`` (``repeat = k``), which keeps the concatenation off the measured JVM's heap. The
single-process control replays through pyFRAMED, which has no ``repeat``, so it reads this file
instead. Both must see the same timeline, so the seam rule is ReplayProtocol's:

- events are ordered by timestamp (stable);
- seam gap = the smallest positive interval between consecutive timestamps (1 ms if none);
- cycle = last − first timestamp + seam gap; pass r shifts every timestamp by r · cycle.

Timestamps are written with microsecond precision when they have a fraction, else whole seconds,
both with a trailing ``Z``. All other fields of each event are copied unchanged.

Usage: concat_recording.py <recording.jsonl> <k> <out.jsonl>
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timedelta, timezone


def parse(text: str) -> datetime:
    t = datetime.fromisoformat(text.strip().replace("Z", "+00:00"))
    return t if t.tzinfo else t.replace(tzinfo=timezone.utc)


def fmt(t: datetime) -> str:
    t = t.astimezone(timezone.utc)
    return t.strftime("%Y-%m-%dT%H:%M:%S.%fZ" if t.microsecond else "%Y-%m-%dT%H:%M:%SZ")


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    src, k, dst = argv[1], int(argv[2]), argv[3]
    if k < 1:
        print("k must be >= 1", file=sys.stderr)
        return 2
    events = [json.loads(line) for line in open(src, encoding="utf-8") if line.strip()]
    stamps = [parse(e["timestamp"]) for e in events]
    order = sorted(range(len(events)), key=lambda i: stamps[i])
    events, stamps = [events[i] for i in order], [stamps[i] for i in order]
    gaps = [b - a for a, b in zip(stamps, stamps[1:]) if b > a]
    seam = min(gaps) if gaps else timedelta(milliseconds=1)
    cycle = stamps[-1] - stamps[0] + seam
    with open(dst, "w", encoding="utf-8") as out:
        for r in range(k):
            for e, t in zip(events, stamps):
                out.write(json.dumps({**e, "timestamp": fmt(t + r * cycle)}, separators=(",", ":")) + "\n")
    print(f"{src}: {len(events)} events x {k} -> {dst} (seam {seam.total_seconds() * 1e3:.3f} ms, "
          f"cycle {cycle.total_seconds():.3f} s)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
