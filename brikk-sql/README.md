# brikk-sql

SQL tokenizer, parser, AST, and generator for Kotlin Multiplatform — a faithful port of
[sqlglot](https://github.com/tobymao/sqlglot) (Python, MIT, © Toby Mao) with one deliberate
extension: **BigQuery/GoogleSQL pipe syntax (`|>`) is kept first-class in the AST** instead
of being desugared away at parse time.

Parity with sqlglot is enforced by differential test gates against the pinned upstream
(`v30.12.0-44-g93d16591`): token streams, AST structure (serde-compared), and generated SQL
are verified byte-for-byte against the Python implementation across thousands of corpus
cases. See `docs/parsing-research-and-plan.md` at the repo root for architecture and status.

Targets: JVM, Linux (x64/arm64), macOS (arm64), Windows (mingw x64). Pure common Kotlin —
no platform-specific code.

## Quick start

```kotlin
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.dialects.sql

// Parse (dialect-aware) → AST
val ast = parseOne("SELECT a, COUNT(*) FROM t GROUP BY a", dialect = "mysql")

// Generate SQL from an AST, in any dialect
ast.sql(dialect = "duckdb")                 // -> SELECT a, COUNT(*) FROM t GROUP BY a
ast.sql(dialect = "postgres", pretty = true)

// One-shot transpile between dialects
transpile("SELECT `col` FROM t LIMIT 5, 10", read = "mysql", write = "postgres")
```

Available dialects (registry: `Dialects.forName`): `""`/`"sqlglot"` (base), `"mysql"`,
`"doris"`, `"presto"`, `"trino"`, `"duckdb"`, `"postgres"`/`"postgresql"`. Unknown names
throw; `Dialects.forNameOrNull` probes availability.

## Working with the AST

Every node is an `Expression` (package `dev.brikk.house.sql.ast`): a class per SQL concept
(`Select`, `Column`, `Literal`, `Join`, ... ~1,000 classes mirroring sqlglot's catalog),
holding children in an `args` map with declared `argTypes`, plus parent links, comments,
and positions.

```kotlin
import dev.brikk.house.sql.ast.*

val ast = parseOne("SELECT a, b + 1 AS c FROM d JOIN e ON d.id = e.id WHERE x > 2")

// Search
val where = ast.find(Where::class)                     // first Where node
val columns = ast.findAll(Column::class).toList()      // all column references

// Traverse (walk = DFS by default; bfs flag available on find/findAll)
ast.walk().forEach { node -> println(node.key) }       // key = lowercased class name

// Transform (copies by default; returns the new tree)
val rewritten = ast.transform { node ->
    if (node is Column && node.name == "a") parseOne("FUN(a)") else node
}

// Copy / equality are structural (parents, comments, positions excluded)
val clone = ast.copy()
check(clone == ast)
```

### Serialization

`Serde` (in `dev.brikk.house.sql.ast`) dumps/loads ASTs in the **same JSON format as
Python sqlglot's `serde`** — trees are interchangeable between the two implementations:

```kotlin
val payloads = Serde.dump(ast)          // kotlinx JsonArray
val restored = Serde.loadExpression(payloads)
check(restored == ast)
```

`Serde.stripMetaAndComments(payloads)` normalizes dumps for structural comparison
(drops positions/comments).

## Pipe syntax — the brikk extension

sqlglot parses `|>` queries but immediately desugars them into `WITH __tmpN AS (...)` CTE
chains — the author's stage boundaries are unrecoverable. brikk-sql keeps them:

```kotlin
val ast = parseOne(
    """
    FROM Produce
    |> WHERE item != :varthing
    |> AGGREGATE COUNT(*) AS num_items, SUM(sales) AS total_sales GROUP BY item
    |> ORDER BY item DESC
    """.trimIndent()
)
```

This yields a **`PipeQuery`** node (package `dev.brikk.house.sql.ast`, module
`brikk.pipes` in serde dumps — these nodes are brikk-native, they do not exist in Python
sqlglot):

- `PipeQuery.thisArg` — the head (the `FROM Produce` source; a `Select`/`Subquery`)
- `PipeQuery.expressionsArg` — the ordered stage list, one node per `|>` operator:

| Stage node | Carries |
|---|---|
| `PipeSelect` / `PipeExtend` | projections |
| `PipeWhere` | condition (`Where`) |
| `PipeAggregate` | aggregate projections + `group` / `group_and_order` (incl. `GROUP BY x AS y`) |
| `PipeOrderBy` | `Order` (last one wins on desugar) |
| `PipeLimit` / `PipeOffset` | `Limit` / `Offset` (desugar keeps min limit, sums offsets) |
| `PipeAs` | stage alias (`TableAlias`) |
| `PipeDistinct` | — |
| `PipeJoin` | `Join` |
| `PipeSetOperation` | UNION/INTERSECT/EXCEPT + ALL/DISTINCT/BY NAME/side modifiers |
| `PipeTableSample` / `PipePivot` / `PipeUnpivot` | respective clauses |

Stage nodes are ordinary `Expression`s: `walk`/`find`/`transform`/`copy`/serde all work.

```kotlin
import dev.brikk.house.sql.ast.*

val pipe = ast.find(PipeQuery::class) as PipeQuery
val stages = pipe.expressionsArg              // List<Expression>, in pipeline order

// 1. Desugar to standard SQL (byte-identical to what Python sqlglot produces)
val standard = desugarPipes(ast)              // copies by default
standard.sql()                                 // WITH __tmp1 AS (SELECT ...) SELECT * FROM ...
standard.sql(dialect = "doris")                // ...in any dialect

// 2. Or render BACK to pipe syntax (sqlglot cannot do this)
ast.sql()                                      // FROM Produce |> WHERE item != :varthing |> ...
```

Round-trip guarantee: generated pipe SQL re-parses to the identical tree.

### Lightweight stage splitting (no parser)

For tooling that only needs raw stage text (offsets, counts, JSON), the tokenizer-level
splitter avoids full parsing — `|>` inside strings, comments, quoted identifiers, and
parenthesized subpipelines never splits:

```kotlin
import dev.brikk.house.sql.parser.PipeStageSplitter

val doc = PipeStageSplitter.split(sql, dialect = "bigquery").toDocument()
doc.toJson()
// {"dialect":"bigquery","token_count":36,"pipe_operator_count":3,
//  "raw_stages":["FROM Produce","WHERE item != :varthing",...]}
```

## Placeholders and parameters

All three placeholder forms tokenize/parse/generate across dialects: positional `?`
(`Placeholder`), named `:name` (`Placeholder(this="name")`), and `@name` (`Parameter`).
These are the anchor points for brikk's binding and (future) table-valued fragment slots.

## Errors

- `ParseError` — structured (message, line, col, context highlight)
- `TokenError` — tokenizer-level failures
- `UnsupportedError` — the generator met a node it cannot render in the target dialect

Anything the parser does not yet support fails loudly with a `ParseError` raise-gate —
there is no silent misparsing.

## Semantic layer

The full sqlglot semantic pipeline is ported and oracle-gated:

- **Type inference** — `annotateTypes(ast, schema, dialect)` (`optimizer/AnnotateTypes.kt`,
  per-dialect metadata generated from sqlglot's typing tables); gated against
  Python-annotated serde dumps for every dialect corpus.
- **Qualification** — `qualify(ast, schema, dialect)` (`optimizer/Qualify.kt`:
  normalize/qualify tables + columns, schema-aware star expansion, validation); gated
  against sqlglot's own optimizer fixture files.
- **Column-level lineage** — `lineage(column, sql, schema)` (`optimizer/Lineage.kt`):
  provenance DAG through CTEs, subqueries, set ops, pivots, star expansion; 54-case
  structural parity with Python.
- **Shape contracts** — `shape/SqlFragment`: `outputShape(inputs)`, three-way shape
  comparison (SATISFIES / HAS_ADDITIONAL / HAS_LESS), TVF-style slot detection,
  `describe()`/`contract()` serializable payloads.
- **Source mapping** — AST nodes carry original source positions; `transpileTo(target,
  trackSourceMap = true)` maps errors in *generated* SQL back to the original text.
- **Native-grammar verification** — the `brikk-sql-verify` module (JVM) checks emitted
  SQL against the target engine's real parser (Doris FE, Trino, DuckDB embedded).
- **Function catalogs** — the `brikk-sql-metadata` module (featherweight, ~100KB) ships
  each engine's registered functions with signatures where extractable; powers
  engine-exact `unmappableFunctions(target)` capability checks.

## Status / known gaps

- Optimizer rules beyond the semantic pipeline are not ported (pushdown_projections/
  predicates, merge/eliminate subqueries, canonicalize, full normalize/simplify) —
  nothing currently depends on them.
- UDF typing is stubbed (`Schema.getUdfType` → UNKNOWN); function `sinceVersion`
  metadata awaits a doris-website docs source; Trino semantic profiles (null handling)
  are not exposed by `SHOW FUNCTIONS`.
- Platforms are temporarily narrowed to JVM while Amper KMP publishing matures (the
  code is pure common Kotlin; restore note in `module.yaml`).
- Per-dialect gate status lives in the `testResources/**/known-failures*.json` ledgers,
  enforced two-directionally (a stale ledger entry fails the build). Remaining entries
  are few and documented (e.g. UPDATE ... FROM rewrites, `explode_projection_to_unnest`).
- Deliberate divergences from upstream sqlglot are registered in
  `docs/brikk-extensions.md` (15 entries, most upstream-PR candidates).

## Attribution

This module is a Kotlin port of **[sqlglot](https://github.com/tobymao/sqlglot)** by Toby
Mao, licensed MIT. The architecture, AST node catalog, dialect semantics, and much of the
test corpus derive from sqlglot; ported functions carry `// sqlglot: <symbol>` provenance
comments referencing their Python counterparts, and generated tables are pinned to a
specific upstream version. Pipe-syntax semantics follow the
[GoogleSQL pipe query syntax](https://cloud.google.com/bigquery/docs/reference/standard-sql/pipe-syntax)
specification. See `ATTRIBUTIONS.md` at the repository root for license texts.
