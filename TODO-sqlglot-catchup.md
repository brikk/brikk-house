# SQLGlot catch-up

Tracking upstream SQLGlot changes that affect Brikk's supported parser,
transpiler, semantic-analysis, and dialect surface.

- Previous pin: `v30.17.0-72-gbac1a897b`
- Current pin: `v30.17.0-93-gdcc36544a`
- Supported dialects: base, MySQL, Doris, StarRocks, Presto, Trino, DuckDB,
  PostgreSQL, ClickHouse, Hive, Spark2, Spark, and BigQuery

## Current Sync

- [x] Regenerate AST nodes, tokenizer tables, typing metadata, and corpora at
  `dcc36544a`.
- [x] Port `4303c3f30f`: report a parse error instead of constructing an empty
  GRANT/REVOKE privilege.
- [x] Port `3762ef4361`: parse `MOD` at multiplicative precedence without
  consuming `LIMIT ... PERCENT`.
- [x] Port `d0aa2b4324`: reject invalid set-operation operands instead of
  constructing a self-referential scope graph.
- [x] Port `222eb11fc4`: parse ClickHouse `view(SELECT ...)` table functions.
- [x] Port `45158c35c7`: preserve Spark map-explode semantics when generating
  Presto/Trino `UNNEST`.
- [x] Port `5a91be3fbc`: preserve PostgreSQL `LOCK` statements as commands.
- [x] Port `9b25ca5dbe`: parse MySQL `BINARY` column attributes as constraints.
- [x] Port `67cf1ec91d`: stop `GROUP BY` from consuming query modifiers.
- [x] Port `6b9cb87bd0`: preserve PostgreSQL's quoted one-byte `"char"` type.
- [x] Regenerate PostgreSQL `BIT_OR`/`BIT_XOR` typing metadata from
  `f481c22bcb` and `5294204448`.
- [x] Reconcile exact known-failure ledgers and run `./kotlin build` plus
  `./kotlin test`.

## Deliberate Scope Boundary

Brikk transforms readable SQL into readable, semantically equivalent SQL. It
does not implement SQLGlot's query-plan optimization pipeline.

The following upstream changes are therefore not ports and require no Brikk
change:

- `2be2afc69f`: `pushdown_predicates` outer-join safety.
- `dcc36544a9`: `pushdown_projections` grouping-ordinal preservation.

Brikk has no corresponding predicate/projection pushdown passes, so it cannot
exhibit those bugs. Adding them would be a new optimizer feature and is not a
catch-up fix. The same boundary applies to SQLGlot's normalize, subquery
unnesting/merging/elimination, join optimization/elimination, CTE elimination,
canonicalization, and full simplification passes.

## Excluded Commits

- Non-ported dialects: `f131ec9f9` (Snowflake), `3110e151b` and `9fac05c06`
  (SQLite), `1e6c6c58e` (TSQL), `e2f4ad7d5` (Redshift).
- Upstream-only maintenance: `99947fbcc`, `eef60ff5c`, and `5ae73df94`.

## Durable Rules

- Treat `reference/sqlglot` as the behavioral oracle at the target pin.
- Regenerate source metadata and corpora after every pin change.
- Remove passing entries from exact known-failure ledgers; never mask new gaps.
- Preserve verifier-backed intentional divergences registered in
  `docs/brikk-extensions.md`.
