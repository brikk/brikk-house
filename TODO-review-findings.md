# Compiler plugin review findings

Review of the compiler-plugin work at `d9d3970`, using
[review guide](docs/REVIEW-compiler-plugin.md) and its `d3df965..d9d3970` scope.
Reviewed on 2026-09-05. Original reproductions are retained below; checked items
include resolution notes and regression coverage.

Relocation note: the runtime/compiler/tooling/smoke modules now live under
[Brikk Engine](brikk-engine/README.md), and generic SQL lives under `brikk-sql/`.
Source links below point to the relocated files; line anchors, reproductions,
commit IDs, and verification evidence retain the review snapshot. Relocation
does not resolve these findings. The later [SQL preservation requirement](brikk-engine/README.md#sql-preservation)
is additional work, not behavior established by this review.

Findings R1-R14 were reproduced with temporary compiler, runtime, or parser tests.
R15 is based on static tracing. The temporary tests were removed after the review.

## High priority

### R1. Namespace bindings by relation node

- [x] **P1** Fix bindings from different stages overwriting each other.

Resolved: colliding parameter names receive deterministic per-node keys, reserved
case-insensitively. SQL placeholders and `bindings()` use the same mapping;
unbound placeholders cannot borrow another node's value. `RelTest` covers chained
and independent inputs, existing generated-looking names, and case-folding.

Location: [Rel.kt:45-48](brikk-engine/brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt#L45-L48).

`bindings()` merges node-local names with `putAll`, while rendering leaves
placeholders unchanged. A source binding `n=1` followed by a stage binding `n=2`
produces two `%(n)s` placeholders but only `{n=2}`. This also breaks joining two
calls to the same parameterized function.

Reproduction:

```kotlin
val src = Rel<Partial>("SELECT :n AS x", "postgres").bind("n", 1)
val out = Rel<Partial>("SELECT x + :n AS y FROM src()", "postgres")
    .input("src", src).bind("n", 2)
```

Both placeholder names and binding keys need matching per-node namespacing.

### R2. Preserve qualified slot references

- [x] **P1** Fix slot replacement breaking qualified column references.

Resolved: slot replacement retains explicit aliases or adds the original slot
name with its original quoting. `RelTest` executes qualified and two-input pipe
joins in embedded DuckDB and checks PostgreSQL alias quoting.

Location: [Rel.kt:79-82](brikk-engine/brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt#L79-L82).

`SELECT src.id FROM src()` renders as `SELECT src.id FROM s0`, without an alias
named `src`. The resulting SQL is invalid. The existing two-input JOIN test uses
this pattern but checks only CTE substrings.

Preserve the slot name as an alias or rewrite its column qualifiers. Add a test
that validates source resolution, not just the presence of generated CTE names.

### R3. Scope the schema memo to the current project

- [ ] **P1** Prevent schema-path memoization leaking across projects.

Location: [BrikkSqlSession.kt:101-105](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/BrikkSqlSession.kt#L101-L105).

`RESOLVED_SCHEMAS` is keyed only by the relative option string and consulted before
the current source anchor. Two compilations using the same relative path but
different project directories both used the first project's schema. Project B
generated a `Long` getter despite declaring `TEXT`.

Resolve current-project anchors first and scope the memo by project/module
identity. An anchorless session must not select an arbitrary project's file just
because its relative option string matches.

## Normal priority

### R4. Bind the referenced local instead of a shadowed parameter

- [ ] **P2** Preserve symbol identity when choosing template binding values.

Location: [BrikkSqlIrGenerationExtension.kt:170-171](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/ir/BrikkSqlIrGenerationExtension.kt#L170-L171).

Parameters are bound first, then template references are deduplicated by name.
This function binds `1`, not the referenced local value `2`, when called with `1`:

```kotlin
@BrikkSql
fun q(n: Long): Rel<Partial> {
    val n = n + 1
    return Sql.postgres("SELECT CAST($n AS BIGINT) AS n")
}
```

Resolved template expressions must not lose to same-named parameters. Define how
plain `:name` placeholders interact with shadowing rather than deduplicating
distinct Kotlin symbols blindly.

### R5. Diagnose generated output-name collisions

- [ ] **P2** Prevent overloads silently sharing an incompatible output interface.

Location: [BrikkSqlSession.kt:186-190](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/BrikkSqlSession.kt#L186-L190).

`associateBy` collapses functions with the same generated output name. These
overloads both compiled as `Rel<RowsOut>`, while `RowsOut` exposed only `getS()`:

```kotlin
@BrikkSql
fun rows(n: Long) = Sql.postgres("SELECT CAST(:n AS BIGINT) AS n")

@BrikkSql
fun rows(s: String) = Sql.postgres("SELECT CAST(:s AS TEXT) AS s")
```

Detect output-name collisions and diagnose them, or disambiguate generated
names. The check should also cover distinct function names that map to the same
output name.

### R6. Match complete class IDs in the output fallback

- [ ] **P2** Stop substituting an unrelated top-level output for a nested shape.

Location: [BrikkSqlSession.kt:244-251](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/BrikkSqlSession.kt#L244-L251).

The fallback treats `Domain.EventsOut` as the output of top-level `events()`,
ignoring the enclosing class. In the reproduction, the nested input guaranteed
only `id`, while `events()` returned `id` and `secret`. Passing the nested input
through a generic identity pipe incorrectly satisfied a trait requiring `secret`.

Require the candidate function's complete generated `ClassId` to equal the
requested ID. Reject nested/local IDs in a fallback intended for top-level
generated classes.

### R7. Avoid capture by user-defined CTEs

- [x] **P2** Allocate generated CTE names without colliding with fragment names.

Resolved: generated CTE names avoid identifiers in all lowered fragments.
`RelTest` executes inner-CTE and physical-table collision cases in embedded
DuckDB. Shared graph nodes remain shared, and cyclic inputs fail explicitly.

Location: [Rel.kt:59-60](brikk-engine/brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt#L59-L60).

Names such as `s0` are allocated without checking fragment identifiers. Given an
upstream `SELECT 1 AS id`, a downstream fragment containing
`WITH s0 AS (SELECT 2 AS id) SELECT * FROM src()` renders as:

```sql
WITH s0 AS (SELECT 1 AS id),
     s1 AS (WITH s0 AS (SELECT 2 AS id) SELECT * FROM s0)
SELECT * FROM s1
```

The rewritten input reference resolves to the inner `s0`, silently changing the
query result. Generated names must avoid collisions across fragment scopes.

### R8. Keep computed constants consistent between FIR and IR

- [ ] **P2** Prevent constant interpolation from producing different analyzed and runtime SQL.

Location: [SqlTemplateFir.kt:129-131](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/SqlTemplateFir.kt#L129-L131).

`constText` handles only literal initializers. With the following source, FIR
analyzes a bind, but IR splices the evaluated constant:

```kotlin
const val COLS = "CAST(1 AS BIGINT)" + " AS id"

@BrikkSql
fun q() = Sql.postgres("SELECT $COLS")
```

The compiled interface exposed `_col_0` while runtime SQL returned `id`.
Evaluate supported constant expressions consistently or reject them explicitly;
do not silently downgrade a constant to a bind in one phase only.

### R9. Keep empty slots distinct from catalog tables

- [ ] **P2** Prevent empty slots acquiring unrelated catalog columns.

Location: [SqlFragment.kt:568-574](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L568-L574).

With an unqualified catalog table `events(event_id BIGINT)` and an empty slot
also named `events`, `SELECT * FROM events()` reports `event_id`. Skipping the
empty slot's schema entry lets its rewritten reference resolve to the physical
table.

Reproduction:

```kotlin
val catalog = ShapeCatalog(
    tables = mapOf("events" to Shape.of("event_id" to "BIGINT")),
    slots = mapOf("events" to Shape.EMPTY),
)
SqlFragment("SELECT * FROM events()", "postgres").outputShape(catalog)
```

Empty slots must remain distinct from catalog tables even at catalog depth one.

### R10. Extract only direct table column definitions

- [ ] **P2** Stop promoting nested struct fields to top-level columns.

Location: [DdlCatalog.kt:37-38](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/DdlCatalog.kt#L37-L38).

Recursive `findAll(ColumnDef::class)` includes nested struct members.
`CREATE TABLE t (payload STRUCT(city VARCHAR))` produces top-level columns
`payload` and `city`, fabricating a column the table does not contain.

Extract only the table schema's direct column definitions. This is independent
of whether the Kotlin type mapper supports struct-valued columns.

### R11. Preserve quoted DDL identifier identity

- [ ] **P2** Preserve quoting through catalog construction and schema lookup.

Location: [DdlCatalog.kt:35-45](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/DdlCatalog.kt#L35-L45).

Reading identifiers through `.name` drops quoting before `MappingSchema`
normalizes them. Both a quoted PostgreSQL table `"Users"` and column `"Id"` lost
their known `BIGINT` type during lookup, producing `UNKNOWN`.

Test table and column quoting separately, including names that differ only by
quoted case. Retain the identifier information needed by the dialect's resolver.

### R12. Respect list-valued JSON extraction overloads

- [ ] **P2** Stop assigning a scalar type to list-valued JSON extraction.

Location: [SqlFragment.kt:589-593](brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L589-L593).

The override unconditionally assigns `TEXT` to `JSONExtractScalar`. DuckDB's
`json_extract_string(json, ['$.a', '$.b'])` returns `VARCHAR[]`, but the shape
reports `TEXT` and the plugin promises `String`.

Account for the overload or retain a conservative unknown type. This does not
require implementing general collection typing or deferred bind-list expansion.

### R13. Do not close a partial shape with SELECT star

- [ ] **P2** Base shape closure on the projection rather than the stage name alone.

Location: [SqlAnalysis.kt:220-222](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlAnalysis.kt#L220-L222).

Any SELECT stage marks the output closed. `FROM src() |> SELECT *` over a trait
input therefore generates `Shape`, despite passing through unknown extra
columns. A full, closed column-set guarantee is incorrect here.

Inspect the projection and input closure information. The conservative treatment
of ordinary non-pipe SELECT as `Partial` is not included in this finding.

### R14. Ignore commented-out calls in the lazy-body reader

- [ ] **P2** Locate the actual SQL call rather than the first textual match.

Location: [SqlLiteralText.kt:17-19](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlLiteralText.kt#L17-L19).

The regex selects the first textual `Sql.<dialect>(...)`, including occurrences
inside comments. This source makes the fallback return `stale` rather than
`fresh`:

```kotlin
@BrikkSql
fun q() =
    /* Previous version: Sql.postgres("SELECT 1 AS stale") */
    Sql.postgres("SELECT 1 AS fresh")
```

Skip comments and string contents when locating the call. The fallback must not
give IDE users a different shape from compilation.

### R15. Propagate cancellation through inner fallback handlers

- [ ] **P2** Apply the cancellation policy before converting exceptions into fallback values.

Location: [BrikkSqlSession.kt:140-144](brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/fir/BrikkSqlSession.kt#L140-L144).

`containerFileOf` catches every `Exception` and returns `null`, including
cancellation exceptions that the outer `PluginGuard` is supposed to rethrow.
Similar handlers exist in `knownSourcePaths` and the output-function fallback.

Call `rethrowIfCancellation` before converting exceptions into fallback values.
The outer guard cannot propagate an exception already swallowed by a helper.
This finding is based on static tracing, not an executed IDE cancellation test.

## Verification and scope limits

- All 36 existing tests in the compiler-plugin, runtime, and DDL-catalog suites
  passed after removing the temporary probes.
- The initial probe run had 20 failing assertions. Some covered documented or
  ambiguous limitations and were excluded from the findings above. That count
  is not a count of independent bugs.
- Probes used in-process compiler invocations, reflection, runtime rendering,
  SQL AST checks, and pure analysis. No live database execution or IDE session
  testing was performed.
- The full `./kotlin check` was not run.
- Documented demo limitations such as positional argument matching, flat column
  checking, and nullability shortcuts were excluded.
- No tracked files were changed during the review. This findings document was
  added afterward at the user's request.

Final existing-suite verification command at the reviewed snapshot:

```sh
./kotlin test -m brikk-sql-compiler-plugin -m brikk-sql-runtime -m brikk-sql -p jvm --include-classes 'dev.brikk.house.sql.compiler.*Test' --include-classes '*RelTest' --include-classes '*DdlCatalogTest'
```

Current equivalent after relocation, not rerun for this documentation update:

```sh
./kotlin test -m brikk-engine-kotlin-compiler-plugin -m brikk-engine-kotlin -m brikk-sql -p jvm --include-classes 'dev.brikk.house.sql.compiler.*Test' --include-classes '*RelTest' --include-classes '*DdlCatalogTest'
```
