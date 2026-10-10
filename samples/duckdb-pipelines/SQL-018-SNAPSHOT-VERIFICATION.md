# SQL 0.18.0-SNAPSHOT — Engine consumer verification

**Historical result: Maven Local candidate passed before formal publication.**
Superseded by [Central 0.18.0 release acceptance](SQL-018-RELEASE-ACCEPTANCE.md).

Verified `dev.brikk.house:brikk-sql-jvm:0.18.0-SNAPSHOT` against the current
Engine and DuckDB sample. This is consumer acceptance of the local candidate,
not publication, Central availability or live IDE/Doris acceptance.

## Exact candidate inputs

Both CLI and IDE plugin build stamps matched these Maven Local JAR SHA-256s:

| Artifact | SHA-256 |
| --- | --- |
| `brikk-sql-jvm-0.18.0-SNAPSHOT.jar` | `7d0546d0ff4cc70ee888350cc7ffdf3d37abc066b5dabe44b4634421c56224d1` |
| `brikk-sql-metadata-jvm-0.18.0-SNAPSHOT.jar` | `f77f8e9e36bb88383016c12640073571769ecddab4979ccfe9f51dec14e73ed4` |

The artifacts did not change during verification. All five direct Engine SQL
consumers temporarily used the same snapshot with Maven Local enabled; the
resolved dependency tree used snapshot metadata and serialization 1.11.0.
No sibling SQL source was substituted or edited.

## DDB-001 positive acceptance

- Bare `:n` / `:value` SELECT projections parse and retain exact SQL.
- `:n::INTEGER`, parenthesized `(:n)` and repeated-name projections work.
- Exact parameter/name ranges remain correct, including Unicode/comments/literals.
- Native DuckDB prefix aliases (`answer: :n + 1`), struct colons and `$n` markers
  do not become spurious bindings.
- The public `@BrikkSql` interpolation repro compiles, renders `SELECT :n AS n`,
  and retains the runtime binding map.
- An actual compiler-generated two-stage graph with colliding `n` bindings
  executes in DuckDB and returns `5` for independent values `2` and `3`; comments
  remain intact and native stages have no regeneration/unsupported reports.
- A compiler-generated nullable bare projection executes without a CAST workaround.
- The exact non-embeddable IDE compiler (`2.4.20-ij262-34`) also compiles/runs a
  dedicated bare-projection/composition/null-binding artifact fixture.

## Full gate

Plugin assembly, full build and **172 tests, zero failures/skips** passed:
the 168-test baseline with the two DDB-001 failure reproducers temporarily
converted to positive assertions, plus four additional snapshot acceptance tests.

Also passed:

- All four DuckDB reporting views, with unchanged expected rows/totals.
- **Nine** IDE-compiler artifact fixtures (eight existing plus one candidate probe).
- All **seven** schema-refresh incremental-build checks, without cleaning.
- Shaded CLI/IDE plugin provenance checks against the local candidate inputs above.

## Cleanup and release handoff

Temporary dependency/repository overrides and candidate-only probes were removed.
At the end of the snapshot check the checkout was restored to Central SQL
**0.17.0**, with its explicitly labeled DDB-001 failure reproducers. The snapshot
was neither committed as a dependency nor published by this verification.

The subsequent release step aligns all five consumers on the Central version, converts DDB-001
to permanent positive regressions (including compiler + actual execution), removes
the old failure-expectation assertions, and reruns the full gate on the published
artifacts before closing the finding.
