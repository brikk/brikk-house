# DuckDB dogfood findings

## DDB-001 — bare `:name` projections conflict with DuckDB parsing in SQL 0.17.0

**Fixed and integrated with Maven Central SQL 0.18.0.** First local dogfood
finding. Positive parser, compiler and actual DuckDB execution regressions replace
the old failure expectations. See the [release acceptance record](SQL-018-RELEASE-ACCEPTANCE.md)
and the earlier [candidate verification](SQL-018-SNAPSHOT-VERIFICATION.md).
The fix landed at the SQL parser / Engine marker-contract boundary, not through
a sample-level SQL regeneration fallback. The history below describes 0.17.0.

Original failing environment: published `dev.brikk.house:brikk-sql-jvm:0.17.0`;
DuckDB JDBC is `1.5.5.0`; compiler `2.4.10`.

```kotlin
SqlFragment("SELECT :n AS n", "duckdb").toSourcePreservingExecutable()
```

Fails with:

```text
ParseError: Required keyword: 'this' missing for Alias. Line 1, Col: 6.
```

The same failure occurs for `SELECT :value AS result` and
`SELECT :n::INTEGER AS n`. Parenthesized `SELECT (:n) AS n` also fails (different
unexpected-token error). These are Engine's portable named-template spelling,
not a claim that colon parameters are DuckDB's native prepared-statement syntax.
Kotlin SQL interpolation currently emits `:name`, so a bare interpolated SELECT
projection is affected; the shared reporting sample's predicate parameters work.

The real compiler repro is:

```kotlin
@BrikkSql
fun echo(n: Int) = Sql.duckdb("SELECT $n AS n")
```

`BrikkSqlPluginTest` now verifies that this compiles, preserves the template and
retains runtime bindings. The gap was reachable from the public Kotlin surface,
not only a manually constructed fragment.

Contrast:

- `SELECT CAST(:n AS INTEGER) AS n` parses in the DuckDB dialect.
- `SELECT 1 AS n WHERE :n IS NULL` parses in the DuckDB dialect.
- `SELECT $n AS n` parses, exposes exact parameter ranges, and executes after the
  sample's parameter-only JDBC adaptation.
- All three bare colon-projection examples parse in PostgreSQL mode.

SQL 0.18.0 recognizes these named templates alongside DuckDB's prefix-alias
grammar. Changing the sample's views to copied SQL or adding ad-hoc regex/AST
regeneration would have concealed the issue; neither workaround was added.

`SampleTest.bareColonProjectionsArePreservedAndExecuteWithoutAWorkaround` and
`ParameterRegressionTest` verify bare/casted/parenthesized/repeated/Unicode
projections, exact ranges, prefix aliases, struct colons, native dollar markers,
compiler-generated composition with binding collisions, and nullable projections
without CAST workarounds. The IDE-compiler matrix also includes the public-surface
regression. The separate binding-validation test retains a typed projection to
exercise transport validation independently of the parser contract.

The four reporting views execute correctly with unchanged results. No sibling SQL
sources were edited; default builds use the published release with no Maven Local
override or snapshot dependency.
