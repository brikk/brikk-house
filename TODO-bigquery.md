# TODO - BigQuery SQLGlot parity

> This file owns every current SQLGlot parity case whose source or target is
> BigQuery. The oracle is the pinned SQLGlot reference at
> `v30.17.0-93-gdcc36544a` in `reference/sqlglot/`.
>
> The authoritative cases remain in
> `brikk-sql/testResources/dialect-corpus/*-transpile-known-failures.json`.
> Native BigQuery generation currently has no ledgered failures.

---

**181 transpile items across 19 source-to-target routes.**

## BigQuery as source (141)

| Route | Items |
|---|---:|
| bigquery -> duckdb | 87 |
| bigquery -> spark | 12 |
| bigquery -> presto | 5 |
| bigquery -> bigquery | 9 |
| bigquery -> trino | 3 |
| bigquery -> hive | 6 |
| bigquery -> postgres | 5 |
| bigquery -> clickhouse | 5 |
| bigquery -> mysql | 4 |
| bigquery -> spark2 | 3 |
| bigquery -> base | 2 |

## BigQuery as target (40)

The `bigquery -> bigquery` cases are counted only in the source table above.

| Route | Items |
|---|---:|
| spark -> bigquery | 11 |
| postgres -> bigquery | 7 |
| duckdb -> bigquery | 7 |
| presto -> bigquery | 6 |
| hive -> bigquery | 4 |
| trino -> bigquery | 2 |
| clickhouse -> bigquery | 2 |
| base -> bigquery | 1 |

## Main clusters

ASTRA-009 closed six exact ledger keys covering seven input assertions. Derived
VALUES now become arrays of named structs, and explicit CTE column lists become
projection aliases. The BigQuery ledger has 144 keys. Unexpanded CTE stars,
shadowed aliases used in query modifiers, and mismatched VALUES widths remain
diagnosed rather than silently losing names or cells.

Execution follow-up: converting BigQuery
`SELECT t.b FROM UNNEST([STRUCT('x' AS b), STRUCT(NULL AS b)]) AS t` to
Presto/Trino casts the NULL field as BIGINT, making the array's row types
incompatible. The ASTRA-009 cross-engine execution check uses an explicit
string cast for NULL; its direct BigQuery generation test retains untyped NULL.
Fix array-wide field type reconciliation rather than changing VALUES lowering.

ASTRA-008 closed the 18 exact stale transpile keys reported in
`build/astra-008-core.log`: 16 from the BigQuery ledger and two from the Presto
ledger. By read/write ownership, these remove 11 BigQuery-source items and seven
BigQuery-target items, reducing the total from 205 to 187. The ledgers now contain
150 and four keys respectively. This does not close all struct-array or offset
conversions; remaining entries and the clusters below stay open.

ASTRA-010 closed 11 temporal cases: native TIMESTAMP_DIFF unit preservation,
Presto/Trino TimestampDiff and DatetimeDiff dispatch, and week-start alignment.
`TemporalDiffResultTest` adds executed Trino 483 checks for negative/fractional
differences, all seven week starts, microsecond boundary rounding, and elapsed
days across DST. Other temporal items in the cluster below remain open.

- BigQuery temporal semantics: `DATE_*`, `DATETIME_*`, `TIMESTAMP_*`, week
  boundaries, time zones, epoch conversion, and format strings.
- Arrays and structs: typed arrays, `STRUCT`, `UNNEST`, aliases, and null-aware
  membership rewrites, especially for DuckDB.
- Function semantics: safe division, null handling, JSON, regex, hashing,
  approximate quantiles, and function-name mappings.
- AST fidelity: optional arguments, named arguments, modifiers, and aliases
  that are currently dropped or normalized incorrectly.
- Unsupported behavior: cases where SQLGlot deliberately raises rather than
  emitting target SQL.

## Workflow

For a `write|target|sql` ledger key, the ledger file's dialect is the source.
For a `read|source|sql` key, the ledger file's dialect is the target. Select any
entry where either side is `bigquery`, compare with the pinned Python oracle,
port the behavior, remove the passing ledger entry, and run the affected
concrete transpile gate.

Run every transpile gate (needed because cases targeting BigQuery live under
their source dialects), then the native BigQuery generator gate:

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.*TranspileTest'`

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.BigqueryGeneratorCorpusTest'`
