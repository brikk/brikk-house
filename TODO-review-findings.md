# Compiler plugin review findings

Review of the compiler-plugin work at `d9d3970`, using the
[review guide](docs/REVIEW-compiler-plugin.md) and its `d3df965..d9d3970` scope.
Reviewed on 2026-09-05. Resolved findings are removed from this active backlog.

The runtime/compiler/tooling/smoke modules now live under
[Brikk Engine](brikk-engine/README.md), and generic SQL lives under `brikk-sql/`.

## Open findings

### R9. Keep empty slots distinct from catalog tables

- [ ] **P2** Prevent empty slots acquiring unrelated catalog columns.

Location: [SqlFragment.kt](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt).

With an unqualified catalog table `events(event_id BIGINT)` and an empty slot
also named `events`, `SELECT * FROM events()` reports `event_id`. Skipping the
empty slot's schema entry lets its rewritten reference resolve to the physical
table. Empty slots must remain distinct from catalog tables.

### R10. Extract only direct table column definitions

- [ ] **P2** Stop promoting nested struct fields to top-level columns.

Location: [DdlCatalog.kt](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/DdlCatalog.kt).

Recursive `findAll(ColumnDef::class)` includes nested struct members.
`CREATE TABLE t (payload STRUCT(city VARCHAR))` fabricates a top-level `city`
column. Extract only direct column definitions from the table schema.

### R11. Preserve quoted DDL identifier identity

- [ ] **P2** Preserve quoting through catalog construction and schema lookup.

Location: [DdlCatalog.kt](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/DdlCatalog.kt).

Reading identifiers through `.name` drops quoting before `MappingSchema`
normalizes them. Quoted PostgreSQL tables and columns can therefore lose their
known type or collide with unquoted names that differ only by case.

### R12. Respect list-valued JSON extraction overloads

- [ ] **P2** Stop assigning a scalar type to list-valued JSON extraction.

Location: [SqlFragment.kt](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt).

DuckDB's `json_extract_string(json, ['$.a', '$.b'])` returns `VARCHAR[]`, but
the shape layer reports `TEXT` and the plugin promises `String`. Account for
the list overload or retain a conservative unknown type.

### R13. Do not close a partial shape with SELECT star

- [ ] **P2** Base shape closure on the projection rather than the stage name alone.

Location: [SqlAnalysis.kt](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlAnalysis.kt).

`FROM src() |> SELECT *` over a trait input passes through unknown extra columns,
so its generated output must remain `Partial`. Explicit projections and
aggregations may close the shape.

### R14. Ignore commented-out calls in the lazy-body reader

- [ ] **P2** Locate the actual SQL call rather than the first textual match.

Location: [SqlLiteralText.kt](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlLiteralText.kt).

The source fallback selects the first textual `Sql.<dialect>(...)`, including
calls inside comments or strings. It must scan Kotlin source and consider only
executable call sites so IDE analysis matches compilation.

## Review scope

The original review used in-process compiler invocations, reflection, runtime
rendering, SQL AST checks, and pure analysis. It did not run a live IDE session
or live database checks.
