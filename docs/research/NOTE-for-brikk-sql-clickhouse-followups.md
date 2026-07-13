# Note to the brikk-sql agent — ClickHouse live-probe results + generator bugs to fix

From the ClickHouse differential-probe program (chdb = embedded clickhouse-local, the
verification instrument). This branch (`SQL-clickhouse-probe`) already **ingests, regenerates,
wires, and re-pins** — the tree is green — so this is mostly a heads-up on the higher-value
part: the **generator mapping bugs**.

## What landed (all on `SQL-clickhouse-probe`)

- **ClickHouse hazard registry is now populated** (was absent): `brikk-sql/testResources/
  semantics/{duckdb-clickhouse,trino-clickhouse}-hazards.json` — **duckdb→clickhouse 152
  pairs, trino→clickhouse 107 pairs** (bucket-A same-name). Generated `.kt` regenerated
  (`python3 tools/generate_hazards_registry.py`, byte-deterministic; `git diff --exit-code`
  clean).
- **Wired**: `HazardRegistry` now maps duckdb↔clickhouse and trino↔clickhouse (both
  directions). `tools/generate_hazards_registry.py` gained `clickhouse` in its
  PASCAL/DISPLAY dialect tables (type `GeneratedDuckdbClickhouseHazards`, display "ClickHouse").
- **Re-pinned**: `HazardRegistryTest.clickhousePairsArePopulatedByLiveProbes` (152/107 +
  verdict spot-checks) and `HazardsRegistrySyncTest.clickhouseJsonsCarryLiveProbePairs`.
  `./kotlin test -m brikk-sql-metadata` (37) and `-m brikk-sql` (437) both green.
- **Step-0 catalog**: `vendor/data/clickhouse-functions-26.5.1.1.tsv` (1787 fns) +
  `vendor/README.md` provenance. brikk-sql can generate
  `GeneratedClickhouseFunctionCatalog.kt` from it (not done here — that's a catalog task,
  not a probe deliverable).
- Evidence + method: `docs/research/REPORT-clickhouse-differential-probe-2026-07-13.md`; raw
  run artifacts under `docs/research/probe-runs/` (276 rows, 13 batches, codepoint-rendered).

## ACTION 1 — fix the generator mappings (the real value)

`docs/research/BUGS-clickhouse-generator-mappings-2026-07-13.md` — the brikk-sql ClickHouse
generator emits several **confident-but-wrong** or **invalid** mappings, verified by running
its OUTPUT against live ClickHouse:

- **P1 ships-wrong** (ClickHouse accepts, silently wrong): `lower`/`upper` (ASCII-only →
  should be `lowerUTF8`/`upperUTF8`), `round` (banker's vs half-away), `log` (natural log vs
  log10), `regexp_replace` (all vs first), `week` (Sunday-mode vs ISO → `toISOWeek`),
  `millisecond` (component vs total), `bin` (byte-padding), `to_days` (day-number vs interval).
- **P2 invalid-name** (runtime error unless the CH catalog wrongly lists it — audit like the
  doris `ARRAY_LENGTH` case): `dayofweek`→`DAY_OF_WEEK`, trino `week`→`WEEK_OF_YEAR`,
  `to_unixtime`→`TIME_TO_UNIX` (leaked internal node), `log10`→`LOG(10,x)` (no 2-arg log).
- **P3 shape**: `xor`→`a ^ b` (no `^` operator → `bitXor`), `age`→2-arg passthrough (needs 3).

**Belt-and-braces already in place**: the registry carries matching `divergent` hazards for
the ships-wrong renames, so a certify-gated consumer refuses them. The **generator still
emits wrong SQL on non-gated paths** — fix at the `renderedSql` templates.

Confirmed-correct mappings (do not touch): `length`→`CHAR_LENGTH`, duckdb `concat`→
`CONCAT(COALESCE(...))`, `instr`→`POSITION`, trino `transform`→`arrayMap`.

## Caveats / provenance

- **trino→clickhouse** verdicts are **ClickHouse live-probed vs the probe-verified Trino
  behavior** in `trino-duckdb-hazards.json` (Trino not re-run live) — flagged per row as
  `composed: clickhouse-live + trino-duckdb-hazards`. A live-Trino re-probe can upgrade them.
- ClickHouse DateTime is **timezone-typed** and Date/DateTime **floor at 1970** — datetime
  verdicts assume a pinned UTC session and 1970+ operands (see the report's datetime sections).
- No `unclear` verdicts remain.

## Context

This is the ClickHouse analogue of the doris probe program (same method, harness-in-Python
since both engines embed). For scaling to more engines cheaply, the `probe-runs/` raw TSVs
are the reusable asset — promoting them into a per-engine `results/{engine}.json` behavior
matrix would make future pairs joins rather than re-probes.
