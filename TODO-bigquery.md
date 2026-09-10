# TODO - BigQuery

> This file owns every current SQLGlot parity case whose source or target is
> BigQuery. The oracle is the pinned SQLGlot reference at
> `v30.17.0-93-gdcc36544a` in `reference/sqlglot/`.
>
> The authoritative cases remain in
> `brikk-sql/brikk-sql/testResources/dialect-corpus/*-transpile-known-failures.json`.
> Native BigQuery generation currently has no ledgered failures.
>
> Owner: this BigQuery workstream. Compiler/shape review belongs to the separate
> compiler-review agent. Work in batches of two or three BQ issues; verify and
> commit each completed batch before starting another. Never reuse retired BQ IDs.

---

**152 actionable failing transpile assertions across 19 source-to-target routes.**
Five additional signed assertions are protected correctness divergences, documented
in `docs/brikk-extensions.md` section 28. They are not open BQ issues. BQ-36 is an
additional execution limitation with no current parity-ledger entry.

## BigQuery as source (112)

| Route | Items |
|---|---:|
| bigquery -> duckdb | 68 |
| bigquery -> spark | 10 |
| bigquery -> presto | 3 |
| bigquery -> bigquery | 9 |
| bigquery -> trino | 1 |
| bigquery -> hive | 4 |
| bigquery -> postgres | 5 |
| bigquery -> clickhouse | 5 |
| bigquery -> mysql | 4 |
| bigquery -> spark2 | 1 |
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

## Open issues

Each signed ledger entry has an `issue` label linking it to this inventory.
Counts are failing assertions, not distinct SQL strings. Retired IDs: BQ-1 through
BQ-5, plus BQ-14, BQ-26, BQ-30, and BQ-34. Completed ASTRA history
belongs in `docs/brikk-extensions.md` and `docs/corpus-coverage.md`, not this TODO.

| ID | Issue | Assertions |
|---|---|---:|
| BQ-6 | TIMESTAMP/TIME/DATETIME constructors and STRING timezone arguments | 11 |
| BQ-7 | Temporal parsing/formatting adapters | 7 |
| BQ-8 | Temporal addition/subtraction and interval syntax | 8 |
| BQ-9 | LAST_DAY month/week lowering | 9 |
| BQ-10 | MAKE_INTERVAL lowering | 1 |
| BQ-11 | DuckDB temporal truncation and timezone handling | 10 |
| BQ-12 | Diagnose unsupported week specifications | 6 |
| BQ-13 | DuckDB array aggregation NULL handling and modifiers | 6 |
| BQ-15 | DuckDB IN/NOT IN UNNEST NULL and empty-array semantics | 3 |
| BQ-16 | DuckDB struct-array UNNEST field expansion | 7 |
| BQ-17 | Inherit struct field names across array elements | 3 |
| BQ-18 | SELECT AS STRUCT lowering | 1 |
| BQ-19 | Base-to-BigQuery nested UNNEST alias cleanup and pretty output | 1 |
| BQ-20 | Projection EXPLODE/UNNEST to BigQuery relations, including outer/zipped inputs | 12 |
| BQ-21 | PostgreSQL projected GENERATE_SERIES to BigQuery | 6 |
| BQ-22 | EDIT_DISTANCE maximum argument and target capability | 5 |
| BQ-23 | TO_HEX/LOWER_HEX operation and letter-case preservation | 13 |
| BQ-24 | SHA256/SHA512 digest generation | 12 |
| BQ-25 | Byte/escaped-string and numeric-hex literal fidelity | 6 |
| BQ-27 | DuckDB JSON scalar/array conversions | 2 |
| BQ-28 | REGEXP_EXTRACT position/occurrence | 2 |
| BQ-29 | APPROX_QUANTILES boundaries and modifiers | 5 |
| BQ-31 | SPACE, STRPOS occurrence, ARG_MAX/MIN and binary/text LENGTH helpers | 7 |
| BQ-32 | Typed date/timestamp arrays and UNNEST aliases | 6 |
| BQ-33 | Presto named-window expansion | 1 |
| BQ-35 | Into-BigQuery timezone operator lowering | 2 |
| BQ-36 | DuckDB ARRAY_TO_STRING row-dependent delimiters; currently diagnosed | execution |

Keep the existing diagnostics for unexpanded CTE stars, shadowed alias references,
and unsafe VALUES widths/modifiers. Unsupported shapes and registered intentional
divergences are not permission to weaken semantic guards for parity.

## Workflow

For a `write|target|sql` display label, the ledger file's dialect is the source.
For a `read|source|sql` label, the ledger file's dialect is the target. Select any
entry where either side is `bigquery`, compare with the pinned Python oracle,
port the behavior, remove the passing ledger entry, and run the affected
concrete transpile gate.

The `id` and `signature` fields determine approval; `case` is only a display label.
The `issue` field is ownership metadata, not an exemption or part of the signature.
Exclude rows marked `status: intentional-divergence` from the actionable inventory;
their exact failure signatures and extension rationale remain protected.
Remove only the exact stale assertion IDs. Review signature changes against the
full actual output, and retain curated explanations when updating a ledger.

Run every transpile gate (needed because cases targeting BigQuery live under
their source dialects), then the native BigQuery generator gate:

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.*TranspileTest'`

`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.BigqueryGeneratorCorpusTest'`
