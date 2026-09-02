# HANDOFF: ClickHouse ↔ DuckDB/Trino semantic probe program

**Audience:** the agent running this probe program (needs: `clickhouse` CLI — `clickhouse-local`
mode is enough, no server; embedded DuckDB via CLI or python module; no Trino needed, see
"Trino leg" below).
**Goal:** populate the brikk-sql hazard registries for the `duckdb↔clickhouse` and
`trino↔clickhouse` dialect pairs, so `certify("clickhouse")` gains semantic findings the way
`certify("doris")` did after the doris probe program.
**Prior art (read these first):**
- `docs/research/function-semantics-trino-duckdb.md` — the methodology bar ("verified" =
  live differential probes over a divergence-pressure corpus, not doc-reading).
- `docs/research/REPORT-doris-differential-probe-2026-07-13.md` + 
  `docs/research/HANDOFF-doris-probe-continuation.md` — the most recent replication of the
  method, including its behavior-matrix suggestion.
- `docs/research/doris-probe-worklist.md` — what a worklist looks like.

## Step 0 — ClickHouse function catalog (prerequisite, also a deliverable)

brikk-sql has **no ClickHouse function catalog** yet (unlike doris/trino/duckdb). Dump it:

```bash
clickhouse local --query "
  SELECT name, is_aggregate, case_insensitive, alias_to
  FROM system.functions ORDER BY name FORMAT TSVWithNames" > clickhouse-functions.tsv
```

Commit as `vendor/data/clickhouse-functions-<version>.tsv` with the ClickHouse version
(`clickhouse local --version`) recorded in `vendor/README.md` (follow the provenance-table
pattern of the trino TSV entry). brikk-sql will generate
`GeneratedClickhouseFunctionCatalog.kt` from it — you don't need to.

## Step 1 — worklist (same-name candidates)

The mining target is names present in BOTH catalogs (bucket-A analog). Compute the
intersections of your `system.functions` dump (names + aliases, and note ClickHouse's
`case_insensitive` flag per function — case-sensitivity is itself probe-worthy) against:
- DuckDB: `python3 -c "import duckdb; ..."` over `duckdb_functions()` (or ask brikk-sql —
  `brikk-sql-metadata` ships the catalog; `FunctionCatalog.functions` names + aliases).
- Trino: `vendor/data/trino-functions-481.tsv` column 0.

Prioritize (descending suspicion):
1. Names with a prior DIVERGENT/CONDITIONAL verdict in `trino-duckdb-hazards.json` or the
   doris pair files (`lower/upper/trim` unicode, `concat`/`greatest`/`least` NULL algebra,
   `regexp_replace` first-vs-all, hash return shapes, `date_trunc`/interval behavior,
   array indexing base) — re-check those against ClickHouse FIRST; the same axes recur.
2. Aggregates (empty-group and NULL-skipping behavior) and anything string/datetime/regex.
3. The long tail alphabetically.

## Step 2 — probes (the method)

Live differential, engine vs engine, over divergence-pressure inputs:
- NULL algebra: `f(NULL)`, `f(x, NULL)`, aggregates over empty sets and all-NULL columns.
- Unicode: `İ`, `ß`, ZWJ emoji, combining marks — for every case/trim/length/reverse family fn.
- Datetime: DST boundaries, leap days, epoch negatives, `1969-12-31`, sub-second precision,
  session-timezone sensitivity (ClickHouse is timezone-typed! `DateTime('UTC')` vs session).
- Numerics: integer overflow behavior (ClickHouse wraps/saturates differently), division by
  zero (ClickHouse returns inf/nan rather than erroring in places), rounding half-even vs
  half-away.
- Arrays: 1-based indexing (ClickHouse is 1-based like Doris), out-of-bounds behavior
  (ClickHouse returns default values, not NULL/error!), empty-array edges.

```bash
clickhouse local --query "SELECT lower('İSTANBUL'), concat('a', NULL, 'c'), arrayElement([1,2],5)"
duckdb -c "SELECT lower('İSTANBUL'), concat('a', NULL, 'c'), ([1,2])[5]"
```

**Trino leg:** if you don't run live Trino, derive trino↔clickhouse verdicts by composing
your live ClickHouse results with the probe-verified Trino behavior recorded in
`trino-duckdb-hazards.json` — and flag every such row's provenance as
`composed: clickhouse-live + trino-duckdb-hazards` (the doris program did the same; the
trino agent can upgrade provenance later).

## Step 3 — deliverables (schema + raw-data requirement)

1. **Hazard rows** appended to NEW files `brikk-sql/testResources/semantics/duckdb-clickhouse-hazards.json`
   and `trino-clickhouse-hazards.json` — schema identical to the existing pair files:
   ```json
   {"source_project": "clickhouse probe program", "extracted": "<date>",
    "pairs": [{"duckdb": "<fn>", "clickhouse": "<fn>",
               "verdict": "identical|divergent|conditionally-equivalent|no-equivalent|unclear",
               "hazard": "<one-line difference or null>",
               "areas": ["null","unicode","datetime","numeric","array",...],
               "provenance": "<report>#<anchor> or probe-runs/<file>:<line>"}]}
   ```
   Key names per direction: `duckdb`/`clickhouse` and `trino`/`clickhouse` respectively.
   After adding files: `python3 tools/generate_hazards_registry.py` must run clean and
   byte-deterministically (it auto-discovers `*-hazards.json`); update the pinned counts in
   `HazardRegistryTest` / `HazardsRegistrySyncTest` (see the doris-ingestion commit
   5716270 for the exact shape of that update).
2. **RAW probe outputs committed** under `docs/research/probe-runs/` — this is a hard
   requirement learned from the doris run (it kept only summarized findings; raw data
   makes hazards a *derived, re-derivable* artifact and enables the behavior-matrix
   approach: per-engine `results/{engine}.json` so future pairs are joins, not re-probes).
3. **Report** `docs/research/REPORT-clickhouse-differential-probe-<date>.md` — per-area
   findings, method, engine versions, limits/unclears with reasons.
4. **Bug list if applicable** `docs/research/BUGS-clickhouse-generator-mappings-<date>.md` —
   any brikk-sql clickhouse rename/mapping that live probes show emits wrong-semantics SQL
   (the doris program found 6 such P1s; check brikk's actual generated SQL for the mapped
   functions, not just name equivalence — transpile via brikk-sql 0.3.0+ and run the OUTPUT).

## Notes on what we already suspect (do not assume — probe)

- ClickHouse function names are case-SENSITIVE except where `case_insensitive=1` — a
  hazard axis by itself when brikk normalizes case.
- `lower/upper` are ASCII-only in ClickHouse (`lowerUTF8` is the unicode variant) —
  likely DIVERGENT vs both duckdb and trino, but with a *different* failure mode than the
  doris/trino unicode hazards (silent ASCII-only vs full-locale folding).
- ClickHouse `arrayElement` out-of-bounds returns type-default (0, ''), not NULL.
- Division/modulo by zero and integer overflow semantics differ from both peers.
- Aggregates: ClickHouse `-If`/`-OrNull` combinators change NULL behavior; the plain forms
  may not match either peer's empty-group semantics.

Deliver everything commit-ready on a branch; the brikk-sql agent ingests, regenerates,
re-pins, and wires certify teeth exactly as done for doris.
