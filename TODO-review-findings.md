# Compiler plugin review findings

Review of the compiler-plugin work at `d9d3970`, using the
[review guide](docs/REVIEW-compiler-plugin.md) and its `d3df965..d9d3970` scope.
Reviewed on 2026-09-05. Resolved findings are removed from this active backlog.

The runtime/compiler/tooling/smoke modules now live under
[Brikk Engine](brikk-engine/README.md), and generic SQL lives under `brikk-sql/`.

## Open findings

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
