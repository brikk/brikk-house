# HANDOFF — reverse-direction rename fixes: clickhouse → doris / trino

Fresh work remaining after the forward direction + `clickhouse→duckdb` reverse were
completed (see the STATUS block in `HANDOFF-generator-rename-fixes.md`, commits `1f313ab`
… `dc23638`). Two parts:

1. **Live-verify** (doris-ducklake agent — you have live Trino 481 + Doris) that the
   reverse target names below RUN and are value-equal to the ClickHouse source.
2. **Implement** the verified renames in `DorisGenerator` / `TrinoGenerator` (brikk-sql
   agent), mirroring the already-shipped `DuckdbGenerator.ANON_FUNC_RENAMES` +
   `anonymousSql` override.

## Why this is needed
ClickHouse's camelCase names (`arraySort`, `bitCount`, `IPv4NumToString`, …) parse to
`Anonymous`, and Doris/Trino have no such name, so brikk emits an invalid uppercase
passthrough (e.g. `SELECT ARRAYSORT(a)`) when transpiling **clickhouse → doris/trino**.
This is the exact mirror of the forward gap already fixed for source→clickhouse and the
`clickhouse→duckdb` reverse.

## Part 1 — live verification (agent)
Input: `docs/research/probe-runs/reverse-doris-trino.input.tsv` — columns
`fn ⇥ clickhouse_call ⇥ clickhouse_value ⇥ doris_call ⇥ trino_call`. The
`clickhouse_value` column is the live chdb (ClickHouse 26.5.1.1) result, precomputed.
For every non-empty `doris_call` / `trino_call`, run `SELECT <call>` on the live engine
and record the output; **diff against `clickhouse_value`** (normalize array spacing
`[1, 2]`/`[1,2]`, booleans, float precision, hash hex-vs-int as usual).

Write-back: `docs/research/probe-runs/reverse-doris-trino.results.tsv` —
`fn ⇥ doris_output ⇥ trino_output ⇥ doris_matches(ch) ⇥ trino_matches(ch)`
(`<ERR:…>` for calls the engine rejects — keep the row; that means the candidate NAME is
wrong for that dialect and must be dropped/replaced, not shipped).

Notes on the candidates (verify, don't assume):
- Doris `arrayUniq → array_unique` (Doris may spell it `array_enumerate_uniq`-family or
  `size(array_distinct(...))`; confirm the exact scalar).
- Doris `splitByRegexp → split_by_regexp` arg order is `(str, pattern)` here (opposite of
  ClickHouse `(pattern, str)`) — the reverse map must swap args; verify.
- Trino `splitByRegexp → regexp_split(str, pattern)` — confirm name + order.
- Trino `arrayElement → element_at` is 1-based; ClickHouse `arrayElement` negative-index
  semantics differ (kept divergent).

## Part 2 — implement (brikk-sql agent, after verification)
For each CONFIRMED value-equal rename, add to the target generator the same mechanism as
`brikk-sql/src/dev.brikk.house.sql/dialects/DuckdbGenerator.kt` (commit `dc23638`):
```
override fun anonymousSql(expression: Anonymous): String {
    ANON_FUNC_RENAMES[expression.name]?.let { return func(it, *expression.expressionsArg.toTypedArray()) }
    return super.anonymousSql(expression)
}
// companion:
val ANON_FUNC_RENAMES = mapOf("arraySort" to "array_sort", ...)  // CH camelCase -> target
```
Rules (identical to the shipped work):
- Key = the ClickHouse spelling as it reaches the generator (camelCase, matched
  case-sensitively via `expression.name`); value = the Doris/Trino name.
- **Round-trip safety**: only add a key that the TARGET dialect neither parses to a
  canonical node nor accepts verbatim (else you corrupt native target SQL). Check with
  `transpile("SELECT <targetName>(...)", read=<target>, write=<target>)` — must be
  unchanged. Omit any CH name the target already accepts (the DuckDB fix omitted
  `gcd`/`lcm`/`isFinite` for this reason).
- Arg-order-different renames (`splitByRegexp`, `splitByString`) need a small method, not a
  plain map entry — swap the args explicitly.
- Divergent-verdict renames (e.g. `arrayElement`) still get the map entry (valid name) and
  keep their divergent hazard.
- Anything reaching the generator as a canonical NODE (not `Anonymous`) is fixed with a
  `TRANSFORMS reg(Node)` entry instead — diagnose with
  `parseOne("<chName>(...)", "clickhouse").toString()` to see node vs Anonymous.

Then: add pins to `ClickhouseRenameFixesTest` (mirror `clickhouseToDuckdb_reverse` +
`duckdb_roundTripUnchanged`), reconcile any remaining hazard notes, regenerate the
registry byte-clean (`python3 tools/generate_hazards_registry.py`; `git diff --exit-code`),
keep `./kotlin test` green (currently 575), commit + push per chunk.

## Also outstanding (source-aware backlog — NOT this pass)
`translate`, `split_by_string` (arg order), `sequence`↔`range`, `format`, `dayname`, plus
the pre-existing `lower`/`upper`/`week` and `round`/`bin`/`age`/`to_days` — all need
source-aware generation (see `SPIKE-source-aware-generator-transforms-*.md`). They are
documented as DEFERRED in the hazard notes and stay certify-guarded meanwhile.
