# TODO - BigQuery SQLGlot parity

> This file owns every current SQLGlot parity case whose source or target is
> BigQuery. The oracle is the pinned SQLGlot reference at
> `v30.17.0-72-gbac1a897b` in `reference/sqlglot/`.
>
> The authoritative cases remain in
> `brikk-sql/testResources/dialect-corpus/*-transpile-known-failures.json`.
> Native BigQuery generation currently has no ledgered failures.

---

**226 transpile items across 20 source-to-target routes.**

## BigQuery as source (172)

| Route | Items |
|---|---:|
| bigquery -> duckdb | 100 |
| bigquery -> spark | 14 |
| bigquery -> presto | 12 |
| bigquery -> bigquery | 12 |
| bigquery -> trino | 7 |
| bigquery -> postgres | 6 |
| bigquery -> hive | 6 |
| bigquery -> mysql | 5 |
| bigquery -> clickhouse | 5 |
| bigquery -> spark2 | 3 |
| bigquery -> base | 2 |

## BigQuery as target (54)

The `bigquery -> bigquery` cases are counted only in the source table above.

| Route | Items |
|---|---:|
| spark -> bigquery | 14 |
| postgres -> bigquery | 11 |
| duckdb -> bigquery | 10 |
| presto -> bigquery | 9 |
| hive -> bigquery | 4 |
| trino -> bigquery | 2 |
| clickhouse -> bigquery | 2 |
| mysql -> bigquery | 1 |
| base -> bigquery | 1 |

## Main clusters

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
