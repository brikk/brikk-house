# TODO - mixed-dialect transpile rewrites (in scope)

> `brikk-sql` is a Kotlin port of Python `sqlglot`, pinned to
> `v30.17.0-72-gbac1a897b`. The reference clone at `reference/sqlglot/` is the
> oracle for this backlog.
>
> This file tracks only transpile parity cases that do not involve BigQuery.
> BigQuery work is in `TODO-bigquery.md`. Brikk-native DataFusion work is in
> `TODO-datafusion.md` because SQLGlot has no DataFusion dialect.
>
> The authoritative cases are the committed
> `brikk-sql/testResources/dialect-corpus/*-transpile-known-failures.json`
> ledgers. Each concrete `*TranspileTest` enforces both directions: new failures
> are rejected, and stale ledger entries must be removed when they start passing.

---

**96 items across 27 source-to-target routes.**

| Route | Items |
|---|---:|
| spark -> duckdb | 15 |
| spark -> presto | 11 |
| spark -> spark | 9 |
| hive -> duckdb | 9 |
| duckdb -> spark | 9 |
| presto -> spark | 5 |
| postgres -> mysql | 5 |
| postgres -> spark | 3 |
| hive -> presto | 3 |
| starrocks -> starrocks | 2 |
| postgres -> spark2 | 2 |
| postgres -> presto | 2 |
| postgres -> postgres | 2 |
| postgres -> hive | 2 |
| mysql -> duckdb | 2 |
| hive -> trino | 2 |
| hive -> spark | 2 |
| duckdb -> presto | 2 |
| spark -> postgres | 1 |
| spark -> hive | 1 |
| presto -> presto | 1 |
| presto -> hive | 1 |
| postgres -> duckdb | 1 |
| postgres -> doris | 1 |
| mysql -> presto | 1 |
| mysql -> postgres | 1 |
| duckdb -> duckdb | 1 |

## Workflow

1. Pick a route above and inspect matching entries in the source dialect's
   `*-transpile-known-failures.json` ledger. A `write|target|sql` key means
   `source -> target`; a `read|source|sql` key means `source -> ledger dialect`.
2. Compare with the pinned Python oracle and port the relevant target generator
   transform or source-aware rewrite.
3. Remove passing entries from the committed ledger.
4. Run the affected concrete `*TranspileTest`; periodically run all transpile
   gates with:

   `./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.*TranspileTest'`
