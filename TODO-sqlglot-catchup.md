# SQLGlot catch-up

Tracking upstream SQLGlot changes that affect Brikk's supported parser,
transpiler, semantic-analysis, and dialect surface.

- Previous pin: `v30.17.0-93-gdcc36544a`
- Current pin: `v30.18.0-43-g3ca82489`
- Supported dialects: base, MySQL, Doris, StarRocks, Presto, Trino, DuckDB,
  PostgreSQL, ClickHouse, Hive, Spark2, Spark, and BigQuery

## Current Sync

- [x] Regenerate AST nodes, tokenizer tables, typing metadata, and all oracle
  corpora at `3ca824895`.
- [x] Preserve `CUBE`, `ROLLUP`, and `GROUPING SETS` order and Hive suffix syntax
  (`97941935`, `d5468304`).
- [x] Port MySQL index prefixes, `UNIQUE` options, null-order cleanup, and
  `ALTER TABLE ... COMMENT` (`9ec72cf8`, `8b374bd5`, `45595690`, `3f154bfb`).
- [x] Preserve PostgreSQL `DATE_TRUNC` zones and normalize `DATE_PART` units
  (`23911445`, `f064484b`).
- [x] Prefix structured Trino `JSON_QUERY` paths with `lax` (`3086608e`).
- [x] Add Doris 4.1 reserved keywords (`5dea5571`).
- [x] Preserve `MOD` precedence and escape complete JSON paths once
  (`b3f23fe3`, `8bf484ce`).
- [x] Make star detection iterative and render dynamic table identifiers
  (`70951c72`, `83abff65`).
- [x] Rename set-operation scopes, mark `Inline` as a UDTF, and refresh
  PostgreSQL `LOCALTIMESTAMP`/`REPLACE` typing metadata.
- [x] Drop unsupported Hive/Spark `UNIQUE` constraints during generation.
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

- Changes confined to non-ported dialects, SQLGlot's executor, integration-test
  syncs, and query-plan optimizer passes remain excluded under the boundary above.

## Durable Rules

- Treat `reference/sqlglot` as the behavioral oracle at the target pin.
- Regenerate source metadata and corpora after every pin change, and bump
  `SQLGLOT_PIN` in `brikk-sql/test@jvm/.../FixturePinSyncTest.kt` — that test
  fails if any fixture's `sqlglot_version` stamp disagrees with the pin.
- Remove passing entries from exact known-failure ledgers; never mask new gaps.
- Preserve verifier-backed intentional divergences registered in
  `docs/brikk-extensions.md`.
