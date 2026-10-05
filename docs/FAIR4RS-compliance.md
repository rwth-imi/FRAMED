# FAIR4RS compliance — FRAMED `master`

Assessed at `b63fbcc` (in sync with `origin/master`), 2026-10-05.

Reference: Barker M. et al., *Introducing the FAIR Principles for research software*,
Sci Data 9, 622 (2022), doi:10.1038/s41597-022-01710-x.

| ID | Principle (abridged) | Status | Implementation in FRAMED |
|---|---|---|---|
| F1 | Globally unique, persistent identifier | Met | `swh:1:rel:1556a43e421caededbb3b9422df4514546c48396`; archived in Software Heritage |
| F1.1 | Components have distinct identifiers | Met | One `swh:1:dir:` per module (7) in `CITATION.cff` and `codemeta.json` `hasPart` |
| F1.2 | Versions have distinct identifiers | Met (one release so far) | Annotated tag `v1.0.0` pushed; SemVer in POM, CFF, codemeta |
| F2 | Rich metadata | Met | `CITATION.cff` 1.2.0, `codemeta.json` (CodeMeta 3.0), POM metadata, README |
| F3 | Metadata include the software's identifier | Met | SWHID in `CITATION.cff` `identifiers` and `codemeta.json` `identifier` |
| F4 | Metadata are FAIR, searchable, indexable | Partial | Indexed via SWH intrinsic metadata; no software-registry entry yet |
| A1 | Retrievable by identifier via standard protocol | Met | SWHID resolves over HTTPS; git over HTTPS/SSH on GitHub |
| A1.1 | Protocol open, free, universally implementable | Met | HTTPS, git |
| A1.2 | Authentication/authorisation where necessary | Met (n/a) | Public access, no restriction required |
| A2 | Metadata accessible after the software is gone | Met | SWH long-term archive retains code and metadata files |
| I1 | Domain-relevant data standards | Met | HL7 v2.x/MLLP (`ORU^R01`), LOINC/UCUM/MDC mapping, MQTT, JSON/JSON-Lines; IEEE 11073 SDC experimental only |
| I2 | Qualified references to other objects | Met | Paper DOI; dataset DOI |
| R1 | Plurality of accurate, relevant attributes | Met | Intended use (research only), requirements, keywords, funding, authors with ORCID |
| R1.1 | Clear, accessible licence | Met | GPL-3.0-or-later (`LICENSE`, SPDX headers, `REUSE.toml`, REUSE CI gate); replay data CC0-1.0 |
| R1.2 | Detailed provenance | Met | Conventional-commit history, `AUTHORS.md`, rightsholder, funder; `docs/CHANGELOG.md` committed at releases |
| R2 | Qualified references to other software | Met | Maven POMs with versioned dependencies; CI dependency-graph submission; `codemeta.json` `softwareRequirements` |
| R3 | Domain-relevant community standards | Partial | Maven/JPMS conventions, CI build and tests, published Javadoc, REUSE; no Maven Central/GitHub Packages artefact yet; test coverage will be extended |

## Open items

1. **No registry entry** (F4).
2. **R3 partial.** Publish an artefact; extend test coverage.

## Method

Each principle was rated from repository content and the Software Heritage API at the
commit above. FAIR4RS defines no normative test criteria; "Met" means verified at this
level, not a formal audit.
