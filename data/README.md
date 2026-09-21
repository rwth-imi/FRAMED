# `data/` — sample inputs for the shipped deployment profiles

Everything the default profile needs to run is committed here. The MIMIC-III profiles
need a download, because that dataset may not be redistributed.

## `replay/p01-VC1-clean-0.jsonl` — the default example

A JSON-Lines recording of one volume-controlled ventilation run on a **patient
simulator (manikin)**: a Dräger Oxylog 3000 Plus over Medibus and a Viatom PC-60FW
pulse oximeter.

**Provenance: simulation data. No human subject is involved, and the file contains no
personal data**. The `p01` prefix numbers the
*simulation run*, not a person.

| | |
|---|---|
| Records | 15 920 |
| Wall-clock span | 634.6 s (≈ 10 min 35 s) |
| Devices | `Oxylog-3000-Plus-00`, `PC60FW` |
| Slow data | `Measurement` and `Settings` (etCO2, FiO2, PEEP, PIP, MV, RR, VT, …) at ≈ 0.6 Hz |
| Waveforms | `RealTime` `CO2_mmHg`, `Flow`, `Paw` at ≈ 3 Hz |
| Oximetry | `BPM.PR`, `Percentage_int.SpO2`, `Percentage_float.PI` at ≈ 1 Hz |

Each line mirrors the `DataPoint` record, so `ReplayProtocol` republishes it without a
parser in the loop. `Annotation_test.knick` is a hand-set marker channel from the study
this recording was made for; it is inert unless a reactor subscribes to it.

`config/services.json` replays this file at `speed: 1.0` — i.e. it takes the full ~10.5
minutes, because real-time pacing is the behaviour the framework exists to provide.
Raise `speed` in that profile to compress it.

## `mimic/` — not committed

`config/services_mimic.json` and `config/services_mimic_bench.json` expect
`data/mimic/3000003.hea` (plus its `.dat` signal files). MIMIC-III Waveform Database is
credentialed and redistribution-restricted, so fetch it yourself:

```bash
mkdir -p data/mimic
wget -r -N -c -np -nH --cut-dirs=6 -P data/mimic \
  https://physionet.org/files/mimic3wdb/1.0/30/3000003/
```

`MimicPacingBenchmark` takes its record via `-Dmimic.record=` instead and *skips* rather than fails when the flag is absent.
