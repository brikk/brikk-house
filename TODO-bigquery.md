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

**19 actionable failing transpile assertions across 4 source-to-target routes.**
Twelve additional signed assertions are protected correctness divergences, documented
in `docs/brikk-extensions.md` sections 28, 30, and 32. They are not open BQ issues. BQ-36
and BQ-37 are execution limitations with no current parity-ledger entries.

## BigQuery as source (0)

No signed source-direction parity failures remain.

## BigQuery as target (19)

The `bigquery -> bigquery` cases are counted only in the source table above.

| Route | Items |
|---|---:|
| spark -> bigquery | 8 |
| postgres -> bigquery | 6 |
| duckdb -> bigquery | 4 |
| base -> bigquery | 1 |

## Open issues

Each signed ledger entry has an `issue` label linking it to this inventory.
Counts are failing assertions, not distinct SQL strings. Retired IDs: BQ-1 through
BQ-18, and BQ-22 through BQ-35. Completed ASTRA history
belongs in `docs/brikk-extensions.md` and `docs/corpus-coverage.md`, not this TODO.

| ID | Issue | Assertions |
|---|---|---:|
| BQ-19 | Base-to-BigQuery nested UNNEST alias cleanup and pretty output | 1 |
| BQ-20 | Projection EXPLODE/UNNEST to BigQuery relations, including outer/zipped inputs | 12 |
| BQ-21 | PostgreSQL projected GENERATE_SERIES to BigQuery | 6 |
| BQ-36 | DuckDB ARRAY_TO_STRING row-dependent delimiters; currently diagnosed | execution |
| BQ-37 | DuckDB offset-preserving STRING(timestamp, zone) formatting; currently diagnosed | execution |
| BQ-38 | Schema-driven TIMESTAMP/DATETIME overload resolution for unknown inputs; currently diagnosed | execution |

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
