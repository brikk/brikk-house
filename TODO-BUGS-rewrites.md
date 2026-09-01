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

**0 items across 0 source-to-target routes.**

The non-BigQuery mixed-dialect rewrite backlog is complete for the pinned SQLGlot version.

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
