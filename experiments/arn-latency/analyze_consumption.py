#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2025-2026 Institute of Medical Informatics, Medical Faculty, RWTH Aachen
#
# SPDX-License-Identifier: GPL-3.0-or-later
"""Consumption completeness of the reactors, from ``run_replays.sh`` output.

Input is a results root (layout in ``framed_runs.py``); only runs whose last attempt exited 0
are used. Two complementary measures are computed per reactor and run:

**1. Consumption completeness, expected-based.** ``compl = observed / expected``
(Definition 4). ``observed`` = o(C_out), the reactor's *accepted* outputs: the outputs on the bus
whose tag ``(timestamp, microstep)`` is strictly greater than every earlier tag of the channel in
the order the sink received them, i.e. those a consumer applying the per-channel logical-time gate
would accept (``published`` counts all outputs, ``not_accepted`` the difference). ``expected`` is
the number of outputs the reactor's inputs should produce under its firing semantics:

* single input (limit classifiers):   e = o(C_in)
* OR rules (A4, A10, A12):             e = sum of input events (each input update may fire)
* Interpreter (A13):                  e = sum of input events over the whole run: one rule
  ``{(c, 1)}`` per input channel and no precondition on the other inputs, so every accepted
  update fires and publishes one ``Interpretation-Status`` record, also before the join start
* trend (A7, A8):                     e = max(0, o(C_in) − (S − 1)), S = windowSize
* dislocation (A9):                   e = min(o(SpO2-Trend), o(S/F-Limit)) + o(etCO2-Limit)
* RR estimator (A11):                 e = max(0, o(CO2-wv) − (S/2 − 1 − 60/RR · f_wv)), with
  S = windowSize, RR = median RR setting of the run, f_wv = 3.125 Hz. As in the original
  v03 analysis, the breath term is *added* to the warm-up.

Input events are counted from the reactor's join start (the latest first timestamp over all
of its input channels); a run in which an input channel is absent contributes no expected
events. For a downstream (CDSS) input channel, the input events are the messages actually
published by its producer, including those at microstep >= 1. Output events are counted over
the whole run. Reactor inputs, outputs and window
sizes are read from the **configuration that the run actually used**
(``config/<run>_services.json``); ``--config`` is only a fallback for runs recorded before the
runner saved it. Device channels are de-duplicated on (timestamp, className, channelID, value),
as in the original analysis, so first-level completeness is computed as before; CDSS channels
are not, because every published message carries its own tag.

A13 (Interpreter) is measured on ``Interpretation-Status``, which carries exactly one record per
firing (its warnings go to further channels and are not counted).

**2. Input consumption, for OR rules (A4, A10, A12, A13).** ``compl_in = consumed / input
updates``: of the input records a reactor received on the bus over the whole run (device channels
de-duplicated exactly as above, CDSS channels not), the share whose tag a FIRED evaluation
consumed (``metrics/<run>_inputs.csv``). The difference
decomposes exactly into

* ``skipped_same_tag``: further records of a tag that was consumed (only the first record of a
  tag can be accepted);
* ``skipped_late``: records of a tag that no firing consumed (discarded as older than the last
  accepted tag of the channel, or never accepted).

``coalesced`` (``sum(consumed_n) − FIRED``) counts inputs consumed by a firing together with
others. Tags are compared as (timestamp at microsecond resolution, the precision of the bus
timestamps; microstep).

**3. Discarded updates.** From the recorder (``metrics/<run>_firings.csv``, outcome
``DISCARDED``): per reactor and input channel, the updates rejected by the logical-time gate,
split into ``discarded_duplicate`` (tag equal to the last accepted tag of the channel) and
``discarded_older`` (tag smaller). These count every message the reactor received, without the
de-duplication of device records.

**4. Outputs per firing.** ``outputs / FIRED``: the accepted bus outputs divided by the firings recorded
off the bus (``metrics/<run>_summary.csv``). This isolates the reactor itself: anything below 1
is a firing that published nothing, i.e. the warm-up early returns of windowed reactors. The
Interpreter is counted by its ``Interpretation-Status`` records, one per firing.

Outputs (``--out``):

- ``consumption_counts_per_run.csv``: input and output event counts per reactor and run;
- ``consumption_per_run.csv``: expected, observed and completeness per reactor and run;
- ``consumption.csv``: completeness per platform, mode and reactor (sums over runs, plus the median
  and minimum of the per-run completeness);
- ``outputs_per_firing.csv``: outputs, FIRED and their ratio per reactor and run;
- ``input_consumption_per_run.csv``, ``input_consumption.csv``: input-side completeness of the OR
  reactors per run and per platform and mode, with the share of firings that consumed more than
  one input update;
- ``discarded_per_run.csv``, ``discarded.csv``: discarded updates per reactor and input channel,
  per run and summed per platform and mode.

Usage::

    python analyze_consumption.py --root out/full-amd --out out/full-amd/analysis \\
        [--config fallback_services.json] [--workers 8]

Requires Python >= 3.10, pandas and numpy.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from functools import partial
from pathlib import Path

import numpy as np
import pandas as pd

from framed_runs import LABELS, STATUS_CHANNEL, Run, discover, load_replay, order_labels, tag_flags

# Expected-count rule per reactor id (A1-A13).
RULES = {
    "etCO2-Limit-Classifier": "single", "SpO2-Limit-Classifier": "single",
    "PI-Limit-Classifier": "single", "SF-Computation-Reactor": "or",
    "S/F-Limit-Classifier": "single", "HR-Limit-Classifier": "single",
    "spo2-Trend-Classifier": "trend", "etCO2-Trend-Classifier": "trend",
    "Dislocation-Classifier": "dislocation", "Hemodynamic arrhythmia": "or",
    "RespiratoryRateEstimator": "rr", "RRMismatchClassifier": "or", "Interpreter": "any",
}

# Medibus waveform frame rate of the ventilator's CO2 curve (Hz), the f_wv term of the RR rule.
RR_WAVEFORM_FREQ_HZ = 3.125


def specs_from_config(config_path: Path) -> dict:
    """Per-reactor input channels, output channel and window size from a services.json.

    Bus addresses ``<className>.<deviceID>.<channelID>.parsed`` denote device channels; bare
    names denote CDSS channels, attributed to the reactor that lists them as output. Inputs are
    the constructor keys ending in ``Channel`` (except outputs) plus the firing-rule channels.
    """
    reactors = json.loads(config_path.read_text())["Reactors"]

    def outputs_of(r):
        if r["id"] == "Interpreter":
            return [STATUS_CHANNEL]
        outs = r.get("outputChannels") or [r.get("outputChannel")]
        return [o for o in outs if o]

    producer = {o: r["id"] for r in reactors for o in outputs_of(r)}

    def triple(address: str):
        if address.endswith(".parsed"):
            cls, dev, ch = address[: -len(".parsed")].split(".", 2)
            return (cls, dev, ch)
        return (producer[address], "CDSS", address)

    specs = {}
    for r in reactors:
        if r["id"] not in RULES:
            continue
        addrs = [v for k, v in r.items()
                 if isinstance(v, str) and not k.startswith("output") and k.endswith("Channel")]
        for rule in r.get("firingRules") or []:
            addrs.extend(rule.keys())
        spec = dict(inputs=[triple(a) for a in dict.fromkeys(addrs)], output=outputs_of(r)[0])
        if "windowSize" in r:
            spec["window"] = int(r["windowSize"])
        if r["id"] == "Dislocation-Classifier":
            spec["roles"] = dict(spo2_trend=r["spo2TrendChannel"], sf_limit=r["sfLimitChannel"],
                                 etco2_limit=r["etCO2LimitChannel"])
        specs[r["id"]] = spec
    missing = [rid for rid in RULES if rid not in specs]
    if missing:
        raise SystemExit(f"config {config_path} lacks reactors {missing}")
    return specs


def expected(rule: str, spec: dict, inputs: pd.DataFrame, rr_set: float) -> float:
    """Expected outputs of one reactor in one run from its join-filtered input counts."""
    n = float(inputs["event_count"].sum())
    if rule in ("single", "or", "any"):
        return n
    if rule == "trend":
        return max(0.0, n - (spec["window"] - 1))
    if rule == "dislocation":
        by_ch = inputs.groupby("channelID")["event_count"].sum()
        roles = spec["roles"]
        return float(min(by_ch.get(roles["spo2_trend"], 0), by_ch.get(roles["sf_limit"], 0))
                     + by_ch.get(roles["etco2_limit"], 0))
    if rule == "rr":
        return max(0.0, n - (spec["window"] / 2 - 1 - (60.0 / rr_set) * RR_WAVEFORM_FREQ_HZ))
    raise ValueError(rule)


def input_consumption(r: Run, specs: dict, d: pd.DataFrame) -> list[dict]:
    """Input-side completeness of the OR reactors in one run (see module docstring, measure 2)."""
    rec = pd.read_csv(r.metrics("inputs"))
    if "m" not in rec.columns:          # recorded before tags carried a microstep
        rec["m"] = 0
    fir = pd.read_csv(r.metrics("firings"))
    rows = []
    for rid, spec in specs.items():
        if RULES[rid] not in ("or", "any"):
            continue
        mine = rec[rec["reactor"] == rid]
        fired = fir[(fir["reactor"] == rid) & (fir["outcome"] == "FIRED")]
        received = consumed = same_tag = late = foreign = 0
        for cls, dev, ch in spec["inputs"]:
            name = ch if dev == "CDSS" else f"{cls}.{dev}.{ch}.parsed"
            bus = d[(d.className == cls) & (d.deviceID == dev) & (d.channelID == ch)]
            per_tag = pd.Series(list(zip(bus["timestamp"].astype("int64") // 1_000,
                                         bus["microstep"].astype("int64")))).value_counts()
            sel = mine["channel"] == name
            got = set(zip(mine.loc[sel, "ts_ns"] // 1_000, mine.loc[sel, "m"]))
            hit = per_tag.index.isin(list(got))
            received += int(per_tag.sum())
            consumed += int(per_tag[hit].clip(upper=1).sum())  # one record per consumed tag
            same_tag += int((per_tag[hit] - 1).sum())          # further records of a consumed tag
            late += int(per_tag[~hit].sum())                   # records of tags never consumed
            foreign += len(got - set(per_tag.index))
        n_fired = len(fired)
        rows.append({**r.key, "label": LABELS[rid], "reactor": rid, "input_updates": received,
                     "consumed": consumed, "skipped_same_tag": same_tag, "skipped_late": late,
                     "consumed_not_on_bus": foreign,
                     "completeness": consumed / received if received else np.nan,
                     "fired": n_fired, "coalesced": int(fired["consumed_n"].sum()) - n_fired,
                     "multi_input_firings": int((fired["consumed_n"] > 1).sum())})
        if foreign:
            print(f"WARNING {r.pass_dir.name}/{r.run} {rid}: {foreign} consumed inputs not on the bus",
                  file=sys.stderr)
    return rows


def consumption_of_run(r: Run, fallback_config: Path | None) -> dict:
    """Counts, expected/observed and outputs/FIRED of one run."""
    config = r.config() if r.config().is_file() else fallback_config
    if config is None:
        raise SystemExit(f"{r.pass_dir.name}/{r.run}: no saved config and no --config fallback")
    specs = specs_from_config(config)
    path = r.replay()
    if path is None:
        print(f"WARNING {r.pass_dir.name}/{r.run}: no replay output", file=sys.stderr)
        return {"counts": [], "per_run": [], "per_firing": [], "inputs": [], "discarded": []}

    df = load_replay(path)
    df = df[(df.className != "Annotation_test") & ~df.className.astype(str).str.contains("Latency")]
    cdss = df["deviceID"] == "CDSS"
    dev = df[~cdss].drop_duplicates(subset=["timestamp", "className", "channelID", "value"])
    d = pd.concat([dev, df[cdss]])
    groups = {k: g["timestamp"] for k, g in d.groupby(["className", "deviceID", "channelID"])}
    out_keys = ["className", "deviceID", "channelID"]
    acc = pd.concat([df.loc[cdss, out_keys], tag_flags(df[cdss], out_keys)["accepted"]], axis=1)
    accepted_out = acc.groupby(out_keys)["accepted"].sum()

    rr = d[(d.className == "Settings") & d.channelID.str.contains("Respiratory rate", na=False)]
    rr_vals = pd.to_numeric(rr["value"], errors="coerce").dropna()
    rr_set = int(round(float(rr_vals.median()))) if len(rr_vals) else None
    if rr_set is None:
        print(f"WARNING {r.pass_dir.name}/{r.run}: no RR setting; using 100 (original fallback)",
              file=sys.stderr)
        rr_set = 100

    counts, per_run = [], []
    for rid, spec in specs.items():
        n_pub = len(groups.get((rid, "CDSS", spec["output"]), ()))
        n_out = int(accepted_out.get((rid, "CDSS", spec["output"]), 0))
        counts.append({**r.key, "reactor": rid, "io": "output", "className": rid, "deviceID": "CDSS",
                       "channelID": spec["output"], "event_count": n_pub, "accepted": n_out})
        firsts = [groups[k].min() if k in groups else None for k in spec["inputs"]]
        inputs = []
        if RULES[rid] == "any":          # fires without the other inputs: count the whole run
            inputs = [{"className": k[0], "deviceID": k[1], "channelID": k[2],
                       "event_count": len(groups[k]) if k in groups else 0} for k in spec["inputs"]]
        elif all(f is not None for f in firsts):
            t_join = max(firsts)
            for k in spec["inputs"]:
                inputs.append({"className": k[0], "deviceID": k[1], "channelID": k[2],
                               "event_count": int((groups[k] >= t_join).sum())})
        counts += [{**r.key, "reactor": rid, "io": "input", **i} for i in inputs]
        inp = pd.DataFrame(inputs, columns=["className", "deviceID", "channelID", "event_count"])
        e = expected(RULES[rid], spec, inp, rr_set) if len(inp) else 0.0
        per_run.append({**r.key, "label": LABELS[rid], "reactor": rid, "rr_set": rr_set,
                        "expected": e, "observed": n_out, "published": n_pub,
                        "not_accepted": n_pub - n_out,
                        "completeness": n_out / e if e > 0 else np.nan,
                        "config": str(config)})

    # outputs per firing, from the off-bus recorder summary
    per_firing = []
    s = pd.read_csv(r.metrics("summary"))
    outs = {p["reactor"]: p["observed"] for p in per_run}
    for _, row in s[s["reactor"] != "*dropped*"].iterrows():
        fired = int(row["fired"])
        o = outs.get(row["reactor"], 0)
        per_firing.append({**r.key, "label": LABELS.get(row["reactor"]), "reactor": row["reactor"],
                           "outputs": o, "fired": fired, "outputs_per_firing": o / fired if fired else np.nan})
    return {"counts": counts, "per_run": per_run, "per_firing": per_firing,
            "inputs": input_consumption(r, specs, d), "discarded": discarded_of_run(r)}


def discarded_of_run(r: Run) -> list[dict]:
    """Discarded updates per reactor and input channel of one run (module docstring, measure 3)."""
    f = pd.read_csv(r.metrics("firings"))
    d = f[f["outcome"] == "DISCARDED"]
    if d.empty:
        return []
    dup = (d["trigger_ts_ns"] == d["gate_ts_ns"]) & (d["trigger_m"] == d["gate_m"])
    g = d.assign(duplicate=dup, older=~dup).groupby(["reactor", "trigger_channel"])
    return [{**r.key, "label": LABELS.get(rid), "reactor": rid, "channel": ch,
             "discarded": int(len(x)), "discarded_duplicate": int(x["duplicate"].sum()),
             "discarded_older": int(x["older"].sum())}
            for (rid, ch), x in g]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--config", type=Path, default=None,
                    help="services.json for runs without a saved config/<run>_services.json")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    runs = discover(args.root)
    if not runs:
        print("No runs found under", args.root, file=sys.stderr)
        return 1
    saved = sum(r.config().is_file() for r in runs)
    print(f"{len(runs)} runs; {saved} with their saved config, "
          f"{len(runs) - saved} with fallback {args.config}")
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(partial(consumption_of_run, fallback_config=args.config), runs))

    counts = pd.DataFrame([c for res in results for c in res["counts"]])
    per_run = pd.DataFrame([p for res in results for p in res["per_run"]])
    per_firing = pd.DataFrame([p for res in results for p in res["per_firing"]])
    counts.to_csv(args.out / "consumption_counts_per_run.csv", index=False)
    per_run.to_csv(args.out / "consumption_per_run.csv", index=False)
    per_firing.to_csv(args.out / "outputs_per_firing.csv", index=False)

    keys = ["platform", "mode", "label", "reactor"]
    table = per_run.groupby(keys)[["expected", "observed", "published", "not_accepted"]].sum()
    table["runs"] = per_run.groupby(keys).size()
    table["completeness_pct"] = np.where(table["expected"] > 0,
                                         100.0 * table["observed"] / table["expected"], np.nan)
    table["run_median_pct"] = 100.0 * per_run.groupby(keys)["completeness"].median()
    table["run_min_pct"] = 100.0 * per_run.groupby(keys)["completeness"].min()
    table["diff"] = table["expected"] - table["observed"]
    table = table.reset_index()
    table.to_csv(args.out / "consumption.csv", index=False)

    inp = pd.DataFrame([x for res in results for x in res["inputs"]])
    inp.to_csv(args.out / "input_consumption_per_run.csv", index=False)
    itab = inp.groupby(keys)[["input_updates", "consumed", "skipped_same_tag", "skipped_late",
                              "consumed_not_on_bus", "fired", "coalesced",
                              "multi_input_firings"]].sum()
    itab["runs"] = inp.groupby(keys).size()
    itab["completeness_pct"] = 100.0 * itab["consumed"] / itab["input_updates"]
    itab["run_min_pct"] = 100.0 * inp.groupby(keys)["completeness"].min()
    itab["multi_input_firing_pct"] = 100.0 * itab["multi_input_firings"] / itab["fired"]
    itab["diff"] = itab["input_updates"] - itab["consumed"]
    itab = itab.reset_index()
    itab.to_csv(args.out / "input_consumption.csv", index=False)

    dis_cols = ["platform", "mode", "recording", "rep", "label", "reactor", "channel", "discarded",
                "discarded_duplicate", "discarded_older"]
    dis = pd.DataFrame([x for res in results for x in res["discarded"]], columns=dis_cols)
    dis.to_csv(args.out / "discarded_per_run.csv", index=False)
    dkeys = ["platform", "mode", "label", "reactor", "channel"]
    dtab = dis.groupby(dkeys)[["discarded", "discarded_duplicate", "discarded_older"]].sum()
    dtab["runs_with_discards"] = dis.groupby(dkeys).size()
    dtab = dtab.reset_index()
    dtab.to_csv(args.out / "discarded.csv", index=False)

    with pd.option_context("display.width", 220, "display.max_columns", 30,
                           "display.float_format", "{:.2f}".format):
        print("\nConsumption completeness [%] (sum observed / sum expected)")
        p = table.pivot_table(index="label", columns="mode", values="completeness_pct")
        print(p.reindex(order_labels(table["label"])).to_string())
        print("\nInput consumption completeness of the OR reactors [%] (consumed / input updates)")
        p = itab.pivot_table(index="label", columns="mode", values="completeness_pct")
        print(p.reindex(order_labels(itab["label"])).to_string(float_format="{:.4f}".format))
        print("\nDiscarded updates per reactor (sum over runs and input channels)")
        if dtab.empty:
            print("  none")
        else:
            p = dtab.pivot_table(index="label", columns="mode", values="discarded", aggfunc="sum")
            print(p.reindex(order_labels(dtab["label"].dropna())).to_string())
        print("\nOutputs per firing (min over runs)")
        p = per_firing.pivot_table(index="label", columns="mode", values="outputs_per_firing", aggfunc="min")
        print(p.reindex(order_labels(per_firing["label"].dropna())).to_string(float_format="{:.4f}".format))
    return 0


if __name__ == "__main__":
    sys.exit(main())
