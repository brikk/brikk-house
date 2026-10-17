# SQLGlot Port Review

Reviewed 2026-09-05 at Brikk commit `d9d3970ab4551534db78059a217d78a48b8fb9b9`.
Upstream baseline: `v30.17.0-93-gdcc36544a`.

Scope: the SQLGlot-derived Kotlin core, omitted processing stages, upstream
maintenance, and the Brikk shape/runtime boundaries that consume the core.
This is a review, not a production-code change. Actions and acceptance criteria
are in [TODO-astra.md](TODO-astra.md), using the same stable `ASTRA-###` IDs.

## Findings

Priorities: P1 means wrong results, unsafe output contracts, or a broken executable
path worth addressing first. P2 means a concrete supported-route defect or a
coverage/maintenance weakness. P3 means an explicit capability decision that can
remain deferred. Architectural recommendations are identified separately.

### ASTRA-001: QUALIFY Lowering Changes LIMIT Semantics

**P1. Inherited upstream defect. Engine-confirmed.**

`eliminateQualify` moves the filter into an outer query but leaves final
`ORDER BY` and `LIMIT` inside the original SELECT. That applies the limit before
the filter instead of after it.

```sql
-- DuckDB source
SELECT x FROM (VALUES (1), (2)) AS t(x)
QUALIFY ROW_NUMBER() OVER (ORDER BY x DESC) = 1
ORDER BY x LIMIT 1

-- Generated Presto SQL
SELECT x FROM (
  SELECT x, ROW_NUMBER() OVER (ORDER BY x DESC) AS _w
  FROM (VALUES (1), (2)) AS t(x)
  ORDER BY x LIMIT 1
) AS _t WHERE _w = 1
```

Executing both statements unchanged in DuckDB 1.5.5 returns `[[2]]` for the
source and `[]` for the generated SQL. The generator reports no unsupported
messages. This defect is also present in the inspected pinned upstream helper;
matching SQLGlot is insufficient here.

Evidence: [Transforms.kt:126-159](brikk-sql/src/dev.brikk.house.sql/generator/Transforms.kt#L126).
The related `eliminateDistinctOn` implementation also warrants modifier-order
tests: [Transforms.kt:420-459](brikk-sql/src/dev.brikk.house.sql/generator/Transforms.kt#L420).
Its exact result behavior was not engine-tested in this review.

Fix this as a documented correctness divergence, with result-based regression
tests and upstream reporting. Do not add the optimizer to compensate.

### ASTRA-002: EXPLODE_OUTER Loses Empty-Input Rows

**P1. Missing upstream transform branch. Generated SQL reproduced.**

Spark input:

```sql
SELECT EXPLODE_OUTER(CAST(ARRAY() AS ARRAY<INT>)) AS x
```

The Presto output cross-joins
`UNNEST(TRY_CAST(ARRAY[] AS ARRAY(INTEGER)))`. That produces no rows instead of
the required single null row. The pinned upstream transform replaces empty/null
outer-explode inputs with a null-bearing collection; the Kotlin branch is absent.
Explicit type annotation does not repair the output. Diagnostics are empty.

Evidence: [Transforms.kt:716](brikk-sql/src/dev.brikk.house.sql/generator/Transforms.kt#L716)
and [Transforms.kt:831-844](brikk-sql/src/dev.brikk.house.sql/generator/Transforms.kt#L831).

DuckDB execution confirms zero rows for empty UNNEST/cross-join controls and one
null row for an outer-join control. The complete Presto-specific output and Spark
source were not executed. Add empty and null collection cases, not just nonempty
arrays/maps. Multiple zipped explosions with one empty input deserve a separate
regression too; that related algorithmic weakness exists upstream as well.

### ASTRA-003: Outer Joins Produce False NOT NULL Contracts

**P1. Brikk-native semantic-analysis defect. API-reproduced.**

With both catalog columns declared `INT NOT NULL`:

```sql
SELECT b.x FROM a LEFT JOIN b ON a.x = b.x
```

`outputShape()` reports `x INT, nullable=false` under DuckDB, Doris, and
PostgreSQL. An unmatched left row produces NULL. The pass returns the physical
column's declared nullability without accounting for the join's null-supplying
side.

Evidence: [AnnotateNullability.kt:260-278](brikk-sql/src/dev.brikk.house.sql/optimizer/AnnotateNullability.kt#L260).
The compiler consumes this shape through
[SqlAnalysis.kt:200-203](brikk-sql-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/SqlAnalysis.kt#L200)
and [TypeMap.kt:28-42](brikk-sql-compiler-plugin/src/dev.brikk.house.sql.compiler/analysis/TypeMap.kt#L28).
By source inspection, that yields `Int` instead of `Int?`; an end-to-end compiler
invocation was not performed.

This needs relational nullability analysis, not query rewriting. Start with
conservative outer-join null extension; proving that a later filter removes nulls
can remain optional. Existing `NullabilityTest` lacks outer-join cases.

### ASTRA-004: Set-Operation Shapes Read Only The Left Branch

**P1. Brikk-native output-contract defect. API-reproduced.**

```sql
SELECT 1 AS x UNION ALL SELECT CAST(2147483648 AS BIGINT) AS x
-- outputShape: INT, nullable=false; result type must accommodate BIGINT.

SELECT 1 AS x UNION ALL SELECT NULL AS x
-- outputShape: INT, nullable=false; result contains NULL.
```

`outermostSelect()` chooses the leftmost SELECT, and output extraction takes its
types and nullability as well as its names. Left-branch names are appropriate for
ordinary positional UNION; left-branch types/nullability are not.

Evidence: [SqlFragment.kt:405-413](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L405)
and [SqlFragment.kt:597-604](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L597).
The annotator already reconciles set-operation types for derived sources at
[AnnotateTypes.kt:410-414](brikk-sql/src/dev.brikk.house.sql/optimizer/AnnotateTypes.kt#L410).
Wrapping the first query in an outer SELECT correctly produces BIGINT. Wrapping
the nullable query still reports non-null because the nullability scope summary
also reads left-branch projections:
[AnnotateNullability.kt:125-129](brikk-sql/src/dev.brikk.house.sql/optimizer/AnnotateNullability.kt#L125).

The existing coercion test covers the working derived-table type path, not the
top-level shape contract. Fix result extraction and nullability composition;
do not merely add a wrapper and assume both problems are solved.

### ASTRA-005: Nested Pipes Bypass Executable Lowering

**P1. Brikk-native stage-composition defect. API-reproduced.**

```sql
SELECT * FROM (FROM t |> SELECT a) s
```

`toExecutable("doris")` leaves `|>` in the output and reports no unsupported
messages. Certification with `desugarPipes=true` can return `ok=true`. With known
`t.a`, shape inference returns `* UNKNOWN` and lineage returns `{*=[*]}`.
The same failure occurs with a pipe inside a CTE.

The problem is the root-only `isPipe`/`tree is PipeQuery` guards:
[SqlFragment.kt:118-120](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L118)
and [SqlFragment.kt:504-507](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L504).
The recursive lowering already exists at
[PipeDesugar.kt:52-71](brikk-sql/src/dev.brikk.house.sql/ast/PipeDesugar.kt#L52).
`toStandardSql()` calls it unconditionally and handles these examples correctly.

The corpus tests invoke the desugarer directly, so they do not cover this facade
failure. Fix the entry points and test them, including source maps and
certification. `Rel.standardTree()` already calls recursive desugaring, so this
finding does not apply to that runtime method.

### ASTRA-006: Runtime Rendering Drops Source-Dialect Context

**P1. Brikk-native integration defect. Runtime-reproduced.**

For DuckDB source `SELECT LOWER(x) AS x FROM t`, targeting ClickHouse:

```sql
-- Rel.render("clickhouse")
SELECT lower(x) AS x FROM t

-- SqlFragment(..., "duckdb").transpileTo("clickhouse")
SELECT lowerUTF8(x) AS x FROM t
```

`Rel.render` omits `sourceDialect` both for a single relation and for every
generated CTE. That disables the existing source-aware conversion and changes
non-ASCII case-folding behavior. Mixed DuckDB/ClickHouse chains reproduce the
same omission. Each node needs its own source dialect, not the root's dialect.

Evidence: [Rel.kt:57-69](brikk-sql-runtime/src/dev.brikk.house.sql.runtime/Rel.kt#L57),
[Generator.kt:86-87](brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt#L86),
and [ClickhouseGenerator.kt:1091-1096](brikk-sql/src/dev.brikk.house.sql/dialects/ClickhouseGenerator.kt#L1091).
The runtime's two tests only exercise PostgreSQL-to-PostgreSQL rendering.

Separately, `render()` returns only a string and loses generator diagnostics.
That is an API limitation, not a violation of a promised strict mode. It still
matters for machine callers; see ASTRA-020.

### ASTRA-007: Physical Input Discovery Ignores CTE Scope

**P2. Brikk-native dependency-inventory defect. API-reproduced.**

```sql
SELECT a FROM t
UNION ALL
SELECT a FROM (WITH t AS (SELECT a FROM u) SELECT a FROM t) s
```

`sourceTables` and `contract.inputsUsed` report only `u`, while
`contract.dependencies` correctly includes both `t` and `u`. The CTE `t` is
local to the right-hand derived table and cannot hide the physical left input.

Evidence: [SqlFragment.kt:300-310](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L300).
This code collects all CTE aliases globally and filters table names by string
equality. Case-normalized references are another failure mode. Use the existing
scope resolver to distinguish physical tables from CTE sources. No in-repo
production consumer acting on `inputsUsed` was identified, so impact beyond the
public contract is not established.

### ASTRA-008: BigQuery UNNEST Needs Coordinated Normalization

**P2. Missing parser/generator lowering. Generated SQL reproduced.**

- BigQuery `SELECT results FROM Coordinates, Coordinates.position AS results`
  becomes a cross join to `Coordinates.position` as though it were a table when
  targeting Presto. No warning.
- BigQuery `SELECT x FROM UNNEST([1, 2]) AS x` retains an ordinary relation alias
  in Presto rather than defining the scalar output column. No warning.
- Presto `SELECT u.x FROM UNNEST(ARRAY[1, 2]) AS u(x)` loses the column alias in
  BigQuery while retaining `u.x`. This direction does warn.

The parser advertises implicit UNNEST support but leaves its normalization hook
empty: [Parser.kt:2284-2286](brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt#L2284).
Explicit aliases retain the wrong representation for this conversion at
[Parser.kt:2888-2905](brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt#L2888).
The generator correctly explains why enabling `unqualifyUnnest` alone would be
unsafe: [BigqueryGenerator.kt:585-593](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L585).

Port parser normalization, alias representation, and generator unqualification
together. These are required semantic conversions, not optional optimization.
Existing BigQuery ledgers already capture examples in this family.

### ASTRA-009: BigQuery Lacks Derived-VALUES And CTE-Column Lowering

**P2. Missing target-validity/name-preservation rewrites. Reproduced.**

PostgreSQL `SELECT * FROM (VALUES (1)) AS t` reaches BigQuery unchanged, without
a warning. BigQuery needs a supported relation form such as UNNEST over structs.

```sql
-- PostgreSQL source
WITH c(x) AS (SELECT 1) SELECT x FROM c

-- BigQuery output
WITH c AS (SELECT 1) SELECT x FROM c
```

The latter warns but drops the only definition of `x`. Upstream pushes the name
onto the inner projection. Neither case requires the full optimizer; explicit
projections do not even require schema inference. Unknown-star cases should be
handled or refused separately.

Evidence: [BigqueryGenerator.kt:35-39](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L35),
[Generator.kt:1367-1373](brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt#L1367),
and [Generator.kt:2309-2322](brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt#L2309).
This overlaps the existing BigQuery backlog, not a newly proposed optimizer.

### ASTRA-010: Temporal Rendering Drops Units Or Uses Invalid Forms

**P2. Missing/incomplete dialect mappings. Reproduced.**

```sql
-- BigQuery source
SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), MINUTE)

-- BigQuery output, silently missing its third argument
SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0))

-- Presto output, rather than DATE_DIFF(unit, start, end)
SELECT TIMESTAMPDIFF(FROM_UNIXTIME(60), FROM_UNIXTIME(0), MINUTE)
```

BigQuery's rename helper drops the `unit` argument. Presto lacks the corresponding
TimestampDiff/DatetimeDiff mappings. BigQuery week-based DATE_DIFF also produces
`WEEK_START(SUNDAY)` in Presto's unit positions rather than lowering the week
boundary semantics. All these probes had empty generator diagnostics.

Evidence: [BigqueryGenerator.kt:123-130](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L123),
[BigqueryGenerator.kt:625](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L625),
[PrestoGenerator.kt:436-444](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L436),
and [PrestoGenerator.kt:1168](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L1168).
The existing temporal backlog is important even for same-dialect generation.

### ASTRA-011: Presto Named Windows Are Not Lowered

**P2. Missing target-specific stage. Reproduced output; no live Presto check.**

```sql
SELECT SUM(x) OVER w FROM t WINDOW w AS (PARTITION BY y)
```

BigQuery-to-Presto generation leaves this unchanged with no warning. The pinned
upstream Presto SELECT pipeline applies `eliminate_window_clause`, while Kotlin
does not: [PrestoGenerator.kt:1263-1273](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L1263).

Inline the named specification for Presto, including inherited window definitions.
Do not indiscriminately apply this restriction to Trino, which supports named
windows. Keep the capability distinction in the dialects.

### ASTRA-012: Type-Sensitive Lowering Is Incomplete

**P2. Missing generator branches, not merely a missing annotation call. Reproduced.**

Selected examples still fail after explicit source-AST annotation:

- Spark-to-Presto struct-array explosion emits `AS u(e)` for a two-field row
  while preserving `SELECT e.a`. Presto's row expansion needs coordinated output
  aliases/references. See [PrestoGenerator.kt:447-505](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L447)
  and its missing `amend_exploded_column_table` at
  [PrestoGenerator.kt:1263-1273](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L1263).
- PostgreSQL-to-Presto `SELECT MD5('abc')` emits
  `LOWER(TO_HEX(MD5('abc')))`, without converting text to the required binary
  input. The check only recognizes syntactic casts:
  [PrestoGenerator.kt:888-894](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L888)
  and [Generator.kt:4838-4840](brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt#L4838).
- Spark-to-BigQuery `SELECT NAMED_STRUCT('a', 1)['a']` emits
  `SELECT STRUCT(1 AS a)['a']` rather than a struct-field access. See
  [BigqueryGenerator.kt:199-212](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L199).

All three emit no generator warning. Separately, the existing
`inheritStructFieldNames` helper is not wired into Presto's array renderer:
[Transforms.kt:600-633](brikk-sql/src/dev.brikk.house.sql/generator/Transforms.kt#L600)
and [PrestoGenerator.kt:1146-1147](brikk-sql/src/dev.brikk.house.sql/dialects/PrestoGenerator.kt#L1146).
Mixed named/unnamed row coercion needs engine-level coverage before claiming it
preserves fields.

Use existing type information in the relevant branches and request additional
analysis only where needed. Running global qualification/annotation before every
render would add cost and SQL churn without fixing these missing handlers.

### ASTRA-013: BigQuery Array Operations Silently Fall Through

**P2. Missing semantic lowerings. Generated SQL reproduced.**

| Source | BigQuery output |
|---|---|
| Spark `SELECT EXPLODE(ARRAY(1, 2)) AS x` | `SELECT EXPLODE([1, 2]) AS x` |
| Spark `SELECT FILTER(ARRAY(1, 2), x -> x > 1)` | `SELECT FILTER([1, 2], x -> x > 1)` |
| PostgreSQL `SELECT ARRAY_REMOVE(ARRAY[1, 2], 1)` | `SELECT ARRAY_REMOVE([1, 2], 1)` |

These require UNNEST or array-subquery rewrites. Generic function rendering is
not a valid fallback and reports no warning in these cases.

Evidence: [BigqueryGenerator.kt:35-39](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L35),
[BigqueryGenerator.kt:585-613](brikk-sql/src/dev.brikk.house.sql/dialects/BigqueryGenerator.kt#L585),
and [Generator.kt:5319-5336](brikk-sql/src/dev.brikk.house.sql/generator/Generator.kt#L5319).
Implement supported rewrites or emit a precise unsupported diagnostic. Do not
ban all anonymous function calls; those can be legitimate engine UDFs.

### ASTRA-014: Supported Semantic Cases Are Still Skipped

**P2. Confirmed coverage-accounting defect.**

Qualification and lineage corpus generators still hardcode eight supported
dialects, excluding some dialects now registered by Brikk. Committed artifacts
include three StarRocks qualification cases marked unsupported and four skipped
BigQuery lineage cases. The Kotlin gate trusts those exclusions.

Evidence: [gen_qualify_corpus.py:53](tools/gen_qualify_corpus.py#L53),
[gen_lineage_corpus.py:39](tools/gen_lineage_corpus.py#L39),
[QualifyCorpusTest.kt:94-103](brikk-sql/test@jvm/dev.brikk.house.sql/QualifyCorpusTest.kt#L94),
[qualify_tables.json:405-424](brikk-sql/testResources/qualify-corpus/qualify_tables.json#L405),
and [lineage-corpus/base.json:2454-2468](brikk-sql/testResources/lineage-corpus/base.json#L2454).

Also, the generated base dialect corpus contains 282 extracted transpile case
records, potentially containing multiple read/write directions, but there is no
base `TranspileCorpusGate` subclass. The direct base-corpus consumer
selects pipe identity cases:
[PipeDesugarCorpusTest.kt:30-40](brikk-sql/test@jvm/dev.brikk.house.sql/PipeDesugarCorpusTest.kt#L30).
This does not prove each assertion lacks overlapping tests elsewhere. It does
mean extraction is being mistaken for execution if those cases count as covered.

Track executed, intentionally excluded, unextractable, and oracle-failing cases
explicitly. A shared supported-scope inventory should prevent stale exclusions.

### ASTRA-015: Exact Ledgers Are Exact Only About Failing Keys

**P2. Confirmed test-strength limitation.**

`LedgerGate` compares the set of failing keys, not failure categories or output
signatures. A known output mismatch becoming an unrelated exception can remain
accepted. Maps collapse duplicate keys. Transpile keys omit originating upstream
test identity and rendering options; read-direction keys use the canonical SQL,
not the actual source input.

Evidence: [LedgerGate.kt:43-50](brikk-sql/test@jvm/dev.brikk.house.sql/LedgerGate.kt#L43),
[LedgerGate.kt:86-87](brikk-sql/test@jvm/dev.brikk.house.sql/LedgerGate.kt#L86),
and [TranspileCorpusGate.kt:47-108](brikk-sql/test@jvm/dev.brikk.house.sql/TranspileCorpusGate.kt#L47).

The extractor retains `identify`, but the transpile harness does not apply it;
`pretty` is only applied for writes. The harness also omits Brikk's source-dialect
context. That can be appropriate for an upstream-oracle gate, but it is not a test
of the source-aware production API.

Keep both gates conceptually distinct: upstream parity and Brikk public-API
behavior. Preserve case identity/options, reject duplicate IDs, and compare at
least normalized failure categories. Keep intentional divergences distinguishable
from unimplemented behavior rather than treating every exception as equivalent.

### ASTRA-016: Pin Checks Do Not Prove Reproducible Generation

**P2. Provenance limitation with a concrete stale generator header.**

The fixture pin gate checked 104 stamped fixtures successfully. It validates JSON
labels, not the upstream import path, generated Kotlin sources, input cleanliness,
or a fresh regeneration diff.

`gen_function_registry.py` hardcodes the old `v30.12.0-44-g93d16591` header; the
generated Kotlin repeats it. Missing Kotlin function classes are printed and
skipped rather than rejected. The stale header proves unreliable provenance,
not necessarily stale registry contents.

Evidence: [FixturePinSyncTest.kt:18-72](brikk-sql/test@jvm/dev.brikk.house.sql/FixturePinSyncTest.kt#L18),
[gen_function_registry.py:34-64](tools/gen_function_registry.py#L34),
and [GeneratedFunctionRegistry.kt:1-4](brikk-sql/src/dev.brikk.house.sql/parser/GeneratedFunctionRegistry.kt#L1).

Use a checked full revision and generator/input manifest, validate the imported
SQLGlot location, and provide one ordered regeneration command. A regeneration
diff check can be separate from ordinary fast tests. Engine function catalogs
have independent provenance and should not inherit SQLGlot's version label.

### ASTRA-017: Fix Tracking Exists But Is Not Cumulative Or Enforced

**P2. Maintenance recommendation grounded in the current workflow.**

There is already direct commit tracking. The catch-up document records nine
specific ports, two typing-metadata updates, and excluded optimizer/dialect
commits: [TODO-sqlglot-catchup.md:6-64](TODO-sqlglot-catchup.md#L6).
For example, upstream `4303c3f30f` connects to the malformed-GRANT guard and a
dedicated parser regression. Structural correspondence has already paid off.

What is missing is a durable, cumulative commit-to-symbol-to-regression index
and an explicit accounting of every upstream commit in each reviewed range.
Earlier detailed triage remains in Git history rather than the current index.
Generated metadata can also recognize an upstream helper by name even when its
body changes, so successful generation cannot stand in for reviewing that helper.
See [gen_typing_metadata.py:76-90](tools/gen_typing_metadata.py#L76).

Extend the current process rather than replacing it. Record upstream SHA/PR,
affected Kotlin symbols, disposition, local change, tests, and any intentional
divergence. Track upstream reporting/adoption for local fixes such as ASTRA-001.
A small append-only table is enough to start; no service is needed.

### ASTRA-018: Engine-Version Targets Remain Unsupported

**P3. Known capability gap, not an omitted optimizer stage.**

The existing non-BigQuery ledger records nine version-qualified directions where
the plain current dialect does not match an older engine. Examples include
PostgreSQL ANY_VALUE and older DuckDB/Spark renderings. Brikk has no dialect
version setting.

Evidence: [TODO-BUGS-rewrites.md:23-45](TODO-BUGS-rewrites.md#L23).
Choose an explicit engine-version support policy. Implement settings if older
targets matter; otherwise document/reject those targets. This is lower priority
than silent current-target correctness defects. The statement that other
non-BigQuery backlog work is complete is true of the recorded pinned corpus,
not all possible queries, as ASTRA-002 and ASTRA-006 demonstrate.

## Architecture Assessment

### ASTRA-019: Keep The Upstream-Shaped Core

**Recommendation. The original rationale still holds.**

I would not redesign the AST/parser/generator now. Keep upstream names,
argument keys, dialect inheritance, dispatch boundaries, and recognizable helper
algorithms. Improve the Kotlin-facing and maintenance code around them.

This is an actual port, not merely an architectural resemblance:

- `Expression` preserves dynamic args, parent links, metadata, and type slots:
  [Expression.kt:46-89](brikk-sql/src/dev.brikk.house.sql/ast/Expression.kt#L46).
- Generated nodes follow upstream expression modules and share an argument-schema
  oracle: [gen_ast_nodes.py:393-425](tools/gen_ast_nodes.py#L393).
- Parser providers and dialect subclasses retain upstream override boundaries:
  [Parser.kt:334-353](brikk-sql/src/dev.brikk.house.sql/parser/Parser.kt#L334).
- Parser and generator gates are separate; the generator loads Python AST dumps
  directly, so it can catch generator drift independently of parser drift:
  [GeneratorCorpusGate.kt:36-65](brikk-sql/test@jvm/dev.brikk.house.sql/GeneratorCorpusGate.kt#L36).

A more statically typed or immutable AST could provide real benefits: fewer
unchecked casts, fewer invalid states, safer mutation, and potentially better
memory behavior. Those are not worthless. But this review found no measurement
showing those costs dominate, and a redesign would not fix clause placement,
missing lowerings, stale coverage lists, or facade calls that skip existing stages.
It would make each future upstream algorithm require more interpretation.

The best local cleanup candidates are stage entry points, source-dialect
propagation, result-contract extraction, generated/handwritten ownership, and
shared fixture infrastructure. The repo already follows this approach with
`SqlFragment`, sidecar nullability, shared corpus gates, and handwritten
`DTypeExtensions.kt` kept outside generated output.

There is no need to preserve an awkward file layout for its own sake. Splitting
a large file or extracting a repeated helper is fine if the upstream symbol map
stays obvious. Require a concrete defect pattern, performance result, or API need
before changing the core representation.

Direct fix tracking and structural fidelity reinforce each other. Tracking tells
you what changed; correspondence makes implementing and reviewing the port cheap.
Neither substitutes for tests against actual engine behavior.

### ASTRA-020: Keep Optimization Optional, Make Required Lowering Explicit

**Recommendation. The omission of the full optimizer is correct for this product.**

The current scope decision at
[TODO-sqlglot-catchup.md:33-48](TODO-sqlglot-catchup.md#L33) is sound. Predicate and
projection pushdown, subquery unnesting/merging, join elimination, CTE elimination,
canonicalization, boolean normalization, and full simplification change query
structure extensively. There is no evidence here that adding them would improve
Brikk's main job enough to justify their semantic risk and SQL churn.

But some omitted transformations are required to preserve meaning on the target.
Calling them preprocessing rather than optimization does not make them optional.

| Stage | Current state | Recommended treatment |
|---|---|---|
| Tokenize, parse, build AST | Present with differential coverage | Retain upstream correspondence |
| Identifier normalization, scope resolution, qualification | Present | Analyze a copy; do not force full qualification into every rendered query |
| Type annotation | Present | Use it for shapes and type-sensitive lowering; preserve UNKNOWN when unresolved |
| Nullability | Brikk-native, present but relational cases incomplete | Fix ASTRA-003/004; this pass need not rewrite SQL |
| Pipe lowering | Recursive implementation exists | Fix entry-point composition, ASTRA-005 |
| Dialect-required lowerings | Partially present | Close ASTRA-001/002/008-013 or report unsupported |
| Strict column validation | Present and selectable | Keep lenient editor analysis separate from strict machine validation |
| Capability/semantic certification | Present, opt-in | Document limits and expose a diagnostic-preserving runtime path |
| Native grammar verification | Separate module | Compose where execution assurance is needed; grammar alone cannot prove equivalence |
| Full logical optimizer | Deliberately absent | Keep absent unless a specific product requirement appears |

The analysis pipeline already copies the AST before qualification/type inference:
[SqlFragment.kt:387-405](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L387)
and [SqlFragment.kt:504-508](brikk-sql/src/dev.brikk.house.sql/shape/SqlFragment.kt#L504).
That is how to get richer analysis without rewriting the user's rendered SQL.
The main transpile path remains parse-to-generator, not a mandatory optimizer.

For execution, distinguish best-effort SQL from evidence that it is runnable.
`certify` already consumes unsupported diagnostics and semantic coverage, and its
documentation explicitly leaves grammar verification to the caller:
[Certify.kt:23-45](brikk-sql/src/dev.brikk.house.sql/shape/Certify.kt#L23).
Do not claim that this stage is missing. Instead, compose it consistently where
required, preserve warnings through runtime APIs, and recognize that function
catalogs cannot detect wrong LIMIT placement or missing recursive lowering.

Prefer a small explicit stage order over a new generic pass framework. A useful
contract is: preserve author structure by default, perform the least rewrite
needed for the chosen target, and report when equivalent generation is unavailable.
This preserves logical structure, not byte-for-byte original formatting; the
current AST generator already normalizes SQL text.

## Existing Strengths

The test infrastructure is substantial. Exact-key ledgers reject both newly
failing cases and obsolete exceptions. Fixtures exercise token positions/comments,
AST schemas, parsing, generation, qualification, scopes, annotation, and lineage.
Source-aware transforms and an intentional-divergence registry already recognize
that engine correctness can outrank SQLGlot parity.

The current BigQuery backlog explicitly records 216 transpile directions/cases
across 20 routes, while the non-BigQuery rewrite backlog records nine
version-qualified cases. Those are inventories of known corpus exceptions,
not a complete map of supported SQL. Some new findings overlap those inventories;
others expose untested compositions or Brikk-native behavior.

Keep the distinction between native identity generation, cross-dialect
transpilation, and engine execution visible. A green native BigQuery generator
gate does not contradict a failing same-dialect input outside its identity corpus.

## Verification

- `./kotlin test -m brikk-sql`: **590 tests passed**, zero failed or skipped.
- `./kotlin test -m brikk-sql-runtime`: **2 tests passed**, zero failed or skipped.
- Direct Java probes exercised Kotlin parsers/generators, shapes, lineage,
  certification, and runtime rendering. Priority cases were rerun against current
  compiled class directories after the module test runs, not only packaged jars.
- DuckDB JDBC 1.5.5.0, engine 1.5.5, executed the original and unmodified generated
  QUALIFY queries and collection-cardinality controls. ASTRA-001 is result-tested.
- Selected upstream transform/parser/generator source was inspected at
  `dcc36544a`. The expected `reference/sqlglot` checkout was absent; no full upstream
  checkout, regeneration, or audit of commits after that pin was performed.
- No live Presto, Trino, BigQuery, Spark, or ClickHouse execution was performed.
  Their findings distinguish reproduced generated SQL from inferred target behavior.
- The full multi-module suite, native verifier/oracle suite, and compiler-plugin
  end-to-end tests were not run. Compiler impact above is traced through source.

Temporary probe sources are under `/tmp/opencode`:
`FreshLoweringReview.java`, `SqlLoweringReview.java`,
`SqlFragmentBoundaryReview.java`, `SqlFragmentNullabilityReview.java`, and
`RelSourceDialectReview.java`. The SQL examples above are the durable reproductions;
temporary probes are not a substitute for committed regression tests.

## Bottom Line

Keep the recognizable SQLGlot core and the deliberate no-full-optimizer boundary.
Cleaner structure has value at the integration and maintenance boundaries, where
this review found concrete failures. It does not justify replacing the core now.

Prioritize the six P1 findings, then required dialect lowerings and coverage
accounting. Make upstream delta tracking cumulative and regeneration reproducible.
The next investment should make supported behavior more correct and explainable,
not make the port look less like SQLGlot.
