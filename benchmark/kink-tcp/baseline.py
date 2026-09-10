#!/usr/bin/env python3
"""Produces the single-process control for the Java-replay/TCP experiment.

Runs the *same* three kink reactors, deployed from the *same* services config the distributed run
uses, but with everything in one pyFRAMED process: the recording is replayed by pyFRAMED's own
``JsonlReplayProtocol`` straight onto a local bus, with no Java instance and no socket in the path.
Whatever the distributed run produces has to match this, event for event.

**Replay it at the speed of the run it controls.** The recorded timestamps drive every window, gate
and output stamp, so the *values* do not depend on wall-clock speed — but the *series* does. A
replay running faster than the chain processes backlogs the reactors' queues, and
``FeatureScalingReactor`` publishes every feature vector pending at a firing stamped with that one
firing's logical time: under backlog several vectors collapse onto one timestamp, and the resulting
classification series is neither complete nor aligned with the one a paced replay produces. A
control replayed flat out is therefore not a control. This script verifies its own coverage — how
much of the recording the last classification actually reaches — and says so when it falls short.

Output: one JSON object per line, ``{timestamp, channelID, value, className}``, ordered by
timestamp then channel — the shape ``analyse-kink-tcp.py`` compares against the Java sink's capture.
"""
from __future__ import annotations

import argparse
import json
import logging
import socket
import sys
import threading
import time
from pathlib import Path
from typing import Any, Dict, List

from pyframed.communicator.driver.protocol.replay.jsonl_replay_protocol import JsonlReplayProtocol
from pyframed.core.remote.nio_tcp_transport import NioTcpTransport
from pyframed.core.remote.socket_event_bus import SocketEventBus
from pyframed.core.utils.dispatch_mode import DispatchMode
from pyframed.orchestrator import config_loader
from pyframed.orchestrator.manager import Manager

CLASSIFICATION_CHANNELS = ("Kink", "Kink-Probability")


def _free_port() -> int:
    """An unused loopback port: the control bus needs one, but nothing ever connects to it."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


#: How far short of the recording's end the last classification may fall before the control counts
#: as truncated. The chain needs a window's worth of input before its first verdict and stops
#: producing once the input stops, so a few seconds of shortfall is structural, not truncation.
COVERAGE_SLACK_S = 15.0


def _coverage(record: str, records: List[Dict[str, Any]]) -> Dict[str, Any]:
    """How much of the recording the collected classifications actually span."""
    stamps = []
    with open(record) as handle:
        for line in handle:
            line = line.strip()
            if line:
                stamps.append(_epoch(json.loads(line)["timestamp"]))
    recording_seconds = (max(stamps) - min(stamps)) if stamps else 0.0
    if not records:
        return {"recordingSeconds": recording_seconds, "coveredSeconds": 0.0,
                "coverage": 0.0, "truncated": recording_seconds > COVERAGE_SLACK_S}

    reached = max(_epoch(str(item["timestamp"])) for item in records) - min(stamps)
    coverage = reached / recording_seconds if recording_seconds else 1.0
    return {
        "recordingSeconds": recording_seconds,
        "coveredSeconds": reached,
        "coverage": coverage,
        "truncated": (recording_seconds - reached) > COVERAGE_SLACK_S,
    }


def _epoch(text: str) -> float:
    """Epoch seconds from either timestamp spelling in play (trailing ``Z`` or bare microseconds)."""
    from datetime import datetime, timezone

    candidate = text.strip()
    if candidate.endswith("Z"):
        candidate = candidate[:-1] + "+00:00"
    moment = datetime.fromisoformat(candidate)
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.timestamp()


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--services", required=True, help="pyFRAMED services config (Reactors section)")
    parser.add_argument("--record", required=True, help="the .jsonl recording to replay")
    parser.add_argument("--out", required=True, help="where to write the collected classifications")
    parser.add_argument("--speed", type=float, default=1.0,
                        help="replay speed; must match the run being controlled (0 = flat out, "
                             "which backlogs the chain and invalidates the control)")
    parser.add_argument("--quiet-period", type=float, default=6.0,
                        help="stop once no classification has arrived for this many seconds")
    parser.add_argument("--timeout", type=float, default=600.0,
                        help="give up waiting for the chain to drain after this many seconds")
    parser.add_argument("--dispatch", default="PER_HANDLER",
                        choices=[mode.name for mode in DispatchMode],
                        help="bus dispatch mode for the control (default: PER_HANDLER, as a "
                             "deployment runs; SEQUENTIAL removes the cross-channel intake race)")
    parser.add_argument("--log-level", default="WARNING")
    args = parser.parse_args(argv)

    logging.basicConfig(level=args.log_level.upper(),
                        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s")

    services = config_loader.load_config(args.services)
    config_loader.validate_service_configs(services)

    # PER_HANDLER by default, as a deployment runs; ordering across channels is restored by sorting
    # on write. The mode is selectable because it is the variable under test when two controls of
    # one recording disagree: under PER_HANDLER each channel has its own queue and thread, so the
    # three waveforms of one frame race to the reactor and the frame clock — a max over them —
    # advances in an order that differs run to run. SEQUENTIAL serializes every channel onto one
    # dispatch thread, which removes that race without changing what is published.
    bus = SocketEventBus(NioTcpTransport(_free_port()), DispatchMode[args.dispatch.upper()])

    collected: List[Dict[str, Any]] = []
    lock = threading.Lock()
    last_arrival = [time.monotonic()]

    def collector(payload: Any) -> None:
        if isinstance(payload, dict):
            with lock:
                collected.append(payload)
                last_arrival[0] = time.monotonic()

    for channel in CLASSIFICATION_CHANNELS:
        bus.register(channel, collector)

    manager = Manager(services, bus)
    manager.instantiate("Reactors")
    manager.validate()

    replay = JsonlReplayProtocol(
        id="Oxylog-3000-Plus-00",
        event_bus=bus,
        record_path=args.record,
        speed=args.speed,
        announce=False,
        autostart=True,
    )
    if not replay.join(timeout=3600.0):
        print("baseline: replay did not finish within an hour", file=sys.stderr)
        return 1

    # Wait for the chain to go quiet rather than for a fixed grace period. Under PER_HANDLER
    # dispatch each reactor has its own queue, and a replay running flat out finishes long before
    # the network behind it has processed what it published: stopping on a timer would truncate the
    # control at an arbitrary point, and by a different amount on every run. The quiet period must
    # also outlast the reactor's stale timeout, so that the one stale marker the chain emits after
    # the recording ends is either always collected or never — it is what makes the count stable.
    with lock:
        last_arrival[0] = time.monotonic()
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        with lock:
            idle = time.monotonic() - last_arrival[0]
        if idle >= args.quiet_period:
            break
        time.sleep(0.1)
    else:
        print("baseline: chain still producing after %.0f s; the control may be truncated"
              % args.timeout, file=sys.stderr)

    manager.stop_all()
    bus.shutdown()

    with lock:
        records = sorted(collected, key=lambda item: (str(item.get("timestamp")),
                                                      str(item.get("channelID"))))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as handle:
        for record in records:
            handle.write(json.dumps(record) + "\n")

    per_channel = {channel: sum(1 for r in records if r.get("channelID") == channel)
                   for channel in CLASSIFICATION_CHANNELS}
    coverage = _coverage(args.record, records)
    meta = {
        "speed": args.speed,
        "dispatch": args.dispatch,
        "eventsReplayed": replay.events_published,
        "classifications": len(records),
        "perChannel": per_channel,
        **coverage,
    }
    Path(str(out) + ".meta.json").write_text(json.dumps(meta, indent=2) + "\n")

    print("baseline: %d events replayed, %d classifications collected %s -> %s"
          % (replay.events_published, len(records), per_channel, out))
    print("baseline: covers %.1f s of the recording's %.1f s (%.1f %%)"
          % (coverage["coveredSeconds"], coverage["recordingSeconds"],
             100.0 * coverage["coverage"]))
    if coverage["truncated"]:
        print("baseline: TRUNCATED — the chain did not process the whole recording, so this is not "
              "a valid control. Replay it at the speed of the run it controls.", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
