# brikk-sql — Parsing Research Summary & Rough Plan

Status: research complete, plan is rough/for discussion.
Scope: SQL parsing side of brikk (pipe-stage parsing first, sqlglot-level parity as the horizon).
Reference repos live in `reference/` (sqlglot @ `v30.12.0-44-g93d16591` — all sqlglot claims
below are pinned to that version; the pipe operator table in particular grows most releases —
plus polyglot, sql-glot-rust, trino, doris, datafusion, calcite, googlesql).

---

## Part 1 — Research Summary

### 1. sqlglot (Python) — our match-level target

**Scale & layout.** ~65K lines of Python. Key components (this checkout is a restructured,
newer layout than PyPI docs describe):

| Component | Path | Size |
|---|---|---|
| Tokenizer config | `sqlglot/tokens.py` | 613 lines |
| Tokenizer engine | `sqlglot/tokenizer_core.py` | 1,211 lines |
| Parser | `sqlglot/parser.py` | 10,179 lines |
| Generator | `sqlglot/generator.py` | 6,243 lines |
| AST | `sqlglot/expressions/` (16 modules) | 11,764 lines, **1,036 Expression classes** |
| Dialects | `dialects/` + `parsers/` + `generators/` + `typing/` | ~31,700 lines, **32 dialects** |
| Optimizer | `sqlglot/optimizer/` (22 modules) | 8,662 lines |
| Lineage / schema / serde / diff | `lineage.py`, `schema.py`, `serde.py`, `diff.py` | ~2,000 lines |

Note: the old Rust tokenizer (`sqlglotrs`) is gone upstream — replaced by `sqlglotc`
(mypyc-compiling the same Python). Single source of truth; we only need Python semantics.

**Tokenizer.** Hand-written single-pass scanner driven by class-level tables
(`SINGLE_TOKENS`, `KEYWORDS` incl. multi-word keys, quote/comment/string configs) with a
keyword **trie** for longest-match (`sqlglot/trie.py`). `TokenType` = 445-member enum.
`Token` = `{token_type, text, line, col, start, end, comments}`. Dialects customize purely
by overriding class-level dicts.

**Parser.** Hand-written recursive descent with a fixed precedence ladder (one method per
level: assignment → disjunction → conjunction → equality → comparison → range → bitwise →
term → factor → unary → primary). Explicitly **not** Pratt (upstream AGENTS.md forbids it).
Extension points are ~15 class-level dicts dialects rebuild by splatting
(`STATEMENT_PARSERS`, `FUNCTION_PARSERS`, `RANGE_PARSERS`, `PLACEHOLDER_PARSERS`,
`PIPE_SYNTAX_TRANSFORM_PARSERS`, …). Backtracking via index save/restore. Unknown statements
fall back to `exp.Command` (raw SQL preserved). Errors: `ParseError` with structured
line/col/context, four `ErrorLevel`s.

**AST.** `Expression` base with `args: dict[str, Any]` + per-class `arg_types` schema
(required/optional keys). Conventional keys `this`/`expression`/`expressions`. Generic
`walk/find/find_all/transform/replace/copy`. Comments as a list per node; positions in a
`meta` dict. JSON serde exists (`serde.py`).

**Generator.** Method-per-expression (`column_sql` etc., auto-discovered by naming
convention) + `TRANSFORMS` dict for one-liners; dispatch table built per generator class.
Pretty-print is just separator/indent switching, not a separate pass. Dialects override by
subclassing + splatting `TRANSFORMS` + ~100 feature flags.

**Dialect mechanism.** A metaclass registers each `Dialect` subclass and wires nested
`Tokenizer/Parser/Generator` classes, inheriting from the parent dialect's components.
Real inheritance chains matter: `Trino(Presto)`, `Doris(MySQL)`, `StarRocks(MySQL)`,
`Databricks(Spark(Spark2(Hive)))`, `Redshift(Postgres)`, etc.
`transpile(read, write)` = parse with read dialect → generate with write dialect; the AST is
dialect-agnostic ("semantics, not syntax"), so there is no conversion pass — dialect-specific
rewrites live in generators/transforms.

**Placeholders (`:name`, `@name`, `?`).** Tokenized as COLON/PARAMETER/PLACEHOLDER;
parsed to `exp.Placeholder(this=name)` / `exp.Parameter`; generation is a token-prefix swap
per dialect (`:x` oracle → `@x` bigquery → `%(x)s` postgres). Directly relevant to our
`:varthing` binding story — sqlglot's model is sufficient and simple.

### 2. Pipe syntax (`|>`) in sqlglot — the critical finding

- `|>` is `TokenType.PIPE_GT`, defined in the **base** tokenizer (`tokens.py:227`) — every
  dialect accepts pipe syntax as input.
- All parsing is in the base parser: `_parse_pipe_syntax_query` loop (`parser.py:10035`),
  handler dict `PIPE_SYNTAX_TRANSFORM_PARSERS` (`parser.py:1230`).
- Supported operators as of 30.12.0: the `PIPE_SYNTAX_TRANSFORM_PARSERS` table has **11
  keyword handlers** — AGGREGATE (incl. GROUP BY / GROUP AND ORDER BY), AS, DISTINCT, EXTEND,
  LIMIT/OFFSET, ORDER BY, PIVOT, SELECT, TABLESAMPLE, UNPIVOT, WHERE — plus set operators
  (UNION/INTERSECT/EXCEPT) and JOIN handled in the fallback path, ≈15 operators total.
  DISTINCT is a recent addition; this table grows most releases, so re-check when re-syncing
  against upstream.
- **Not** supported in 30.12.0 (~16 of GoogleSQL's ~31 spec ops): SET, DROP, RENAME, CALL,
  WINDOW, WITH, RECURSIVE UNION, MATCH_RECOGNIZE, ASSERT, DESCRIBE, IF, and terminal ops
  (CREATE TABLE, INSERT, EXPORT DATA, FORK, TEE). SET/DROP existed and were deleted in a
  refactor.
- **sqlglot has no pipe AST nodes.** Pipe queries are desugared *during parsing* into a chain
  of `__tmp{N}` CTEs — where **consecutive operators may collapse into one CTE and the final
  stage need not be its own CTE**. So CTE count ≠ stage count: the `__tmpN` chain is a
  many-to-one projection of the pipeline and the author's stage boundaries are unrecoverable
  from the desugared AST. (Demonstrated empirically: a FROM + 3 pipe operators
  (WHERE, AGGREGATE…GROUP BY, ORDER BY) folds into a *single* `__tmp1` CTE.) Stage structure
  is destroyed at parse time.
- The only other extraction route is slicing the raw SQL on `PIPE_GT` token offsets — which
  bypasses the parser entirely and yields text blobs, not typed structure. Neither route
  yields first-class stage nodes; hence the brikk design below.
- **Generation is one-way**: sqlglot cannot emit pipe syntax, only consume it.
- Canonical spec: `reference/googlesql/docs/pipe-syntax.md` (operator list at line 260;
  terminal/semi-terminal semantics, subpipelines).
- Tests: single file `tests/dialects/test_pipe_syntax.py` (481 lines).

**Implication for brikk:** sqlglot's pipe design is the opposite of what we need. Brikk
wants stages as *first-class* values — to inspect them, delegate individual stages to
dialect-native parsers, round-trip back to pipe syntax, and feed stage shape to compiler
plugins. So: we keep pipe stages in the AST (stage list on a `PipeQuery` node), and make
"desugar to standard CTE SQL" an explicit *transform*, not a parse-time destruction. We can
still match sqlglot's desugared output (their test suite becomes our transform's test suite).

### 3. Ports of sqlglot — lessons (both are Rust)

**polyglot** (`reference/polyglot`) — faithful mechanical translation, ~250K lines of Rust
(~4x expansion over Python), 34 dialects, full pipe syntax (parse **and** generate,
`PipeOperator` node). Claims 10,220 sqlglot fixture cases at 100%.

- AST: one enum, ~924 variants, each a `Box`ed struct with fully-typed fields. Cost: a
  hand-written 2,130-line traversal visitor that must stay in sync; dialect fields leak into
  shared nodes (ClickHouse/Oracle/T-SQL fields on `Select`).
- Dialects: config structs (`GeneratorConfig` has 116 fields — a smell) because Rust lacks
  open subclassing; all parser divergence inlined as `if dialect == X` → a 63K-line parser.rs.
- **The artifact to steal:** `tools/sqlglot-extract/extract-tests.py` — parses sqlglot's
  Python test files with the `ast` module and extracts `validate_identity`/`validate_all`
  calls into JSON fixtures; plus `tools/sqlglot-compare` (differential oracle vs live Python
  sqlglot with a `known-differences.json` ledger).
- Discipline worth copying: every function annotated with its Python counterpart
  (`Python: _parse_x`) — makes the port reviewable and re-syncable against upstream.

**sql-glot-rust** (`reference/sql-glot-rust`) — clean-room redesign, ~37K lines. Smaller
typed AST (~60 expr variants + 72 "promoted" typed functions), `Command` raw-text escape
hatch, `Commented` wrapper node. Pipe syntax is *swallowed and discarded*. Because it
redesigned the AST, it **cannot reuse sqlglot's tests** — correctness rests on a much
thinner suite. Good ideas: raw-passthrough `Command`, promote-untyped-function-to-typed-node
on demand, dialect plugin registry.

**Net lesson:** faithful structure = fixture reuse = provable parity. Kotlin has real
inheritance and sealed hierarchies, so we can keep sqlglot's dialect-subclassing shape
directly (avoiding polyglot's 116-flag structs and monolithic parser) while going typed
where it pays.

### 4. Native parsers (for stage/statement delegation)

| Engine | Tech | JVM-embeddable | Output | Pipe `\|>` |
|---|---|---|---|---|
| **Doris** `fe-sql-parser` | ANTLR 4.13.1 | Yes — deps: antlr4-runtime only; purpose-built standalone jar (not on Central yet, `mvn install` locally) | CST (visitor/listener) | No |
| **Trino** `trino-parser` | ANTLR 4 | Yes — on Maven Central, typed AST (312 node classes) + `SqlFormatter` for AST→SQL | Typed AST | No |
| **DataFusion** | sqlparser-rs 0.62 (external crate) | No (Rust; FFI only) | Rust AST | **Yes — full parse + plan** (the only one) |
| **Calcite** | JavaCC/FMPP (`Parser.jj`, 9,770 lines) | Yes but heavy (`calcite-core`, no standalone parser artifact); `babel` for permissive multi-dialect | Typed `SqlNode` | No |

- Doris entry: `org.apache.doris.sqlparser.DorisSqlParser` (`parseStatement/parseStatements/parseExpression`).
- Trino entry: `io.trino.sql.parser.SqlParser` (`createStatement/createExpression`).
- Both are JVM-only → delegation to native parsers must live in `@jvm` source sets / JVM-only
  modules; common code defines the delegation *interface*.
- **Role clarification:** native parsers are *not* how brikk supports pipes — none of the
  JVM-embeddable ones speak `|>` anyway. Their job is parsing **fragments**: expressions,
  function calls, and stage bodies (both Doris and Trino expose `parseExpression`-level entry
  points, which fits), plus full non-pipe SQL. Brikk owns the pipe layer; stages/fragments
  can be validated or parsed engine-exactly by the native parser, and partial SQL can be
  wrapped in pipe stages to make it work across dialects. The payoff is engine-accurate
  validation and better code completion in the Kotlin+SQL world.
- sqlparser-rs is a useful *reference* for pipe grammar decisions (it models pipe operators
  as AST values, like we want), even though we can't embed it.

### 5. sqlglot semantic layer (lineage, scope, types) — the later target

- **Scope** (`optimizer/scope.py`): the central abstraction — per-SELECT context with
  `sources: name → Table | nested Scope`; `traverse_scope` yields innermost-first.
- **qualify** = normalize_identifiers → qualify_tables → qualify_columns (star expansion,
  alias resolution, `table.column` rewriting) → quote_identifiers. Prerequisite for
  everything semantic.
- **annotate_types**: post-order type inference driven by schema + per-dialect
  `EXPRESSION_METADATA` + a coercion lattice.
- **lineage()** (`lineage.py`): qualify → build scopes → recursive column-node graph
  (`Node{name, expression, source, downstream}`). Schema optional (unknowns become
  placeholder leaves). This is exactly the "shape/dependency graph" brikk needs for the
  compiler-plugin and introspection goals.
- Also: `schema.py` (MappingSchema + trie lookup), `serde.py` (AST↔JSON — useful for
  cross-implementation AST comparison tests), `diff.py` (semantic AST diff), full optimizer
  pipeline (14 rules), toy executor.

### 6. Test/fixture infrastructure (compat-test path)

- sqlglot is **MIT** — fixtures can be vendored with attribution.
- `tests/fixtures/identity.sql` — 979 round-trip cases, one per line. Loader is ~40 lines.
- `tests/fixtures/optimizer/*.sql` — ~15K lines of input/output pairs with `# key: value`
  meta headers (naive `;` split — replicate exactly, don't improve).
- The big prize: **~6,900 inline `validate_identity`/`validate_all` assertions** across 33
  dialect test files (snowflake 1,370, bigquery 620, duckdb 617, …). Not machine-readable —
  extract with a Python `ast` script into versioned JSON pinned to a sqlglot commit
  (polyglot proved this works and hit 100%).
- Differential oracle: run Python sqlglot side-by-side, keep a known-differences ledger.

---

## Part 2 — Rough Plan

### North star: composable, parameterized pipe fragments (TVF model)

Not in scope for the first phases, but it shapes the data model, so pinned here. The ideal
end state (syntax illustrative, not settled):

```kotlin
val clickhouseImport = Sql.clickhouse(""" ... """)
val s3Import        = Sql.doris("""... FROM S3(...)""")

val ingestData = Sql.doris("""FROM source(dateRange) |> ...""", s3Import, dateRange)
```

Every SQL element is a value with **(input shape, body, output shape)** — behaving like a
TVF / parameterized view: pipeable, composable into bigger queries, parameterized by both
scalars (`:dateRange`-style binds) and *table-valued* inputs (slots that another fragment
plugs into).

Implications for the layers we're building now:

- **Parameter model must be two-tier.** Scalar placeholders (sqlglot's `Placeholder` /
  `Parameter` — ports directly) *and* table-valued fragment slots — occupying a
  FROM/pipe-source position, with a declared (or inferred) row shape. Likely syntax: plain
  TVF calls (`source(args)`) rather than special sigils — an *unresolved* function in a
  table position (sqlglot's `Anonymous` function fallback) is the slot-candidate hook we
  intercept and bind. User must avoid names conflicting with real engine TVFs; conflict
  detection is our job. Design in Phase 2/4 alongside the AST core.
- **Shape is the contract; shapes come from the build tool.** Workflow: the build-tool
  plugin introspects target databases and maintains an updatable **schema cache**; the IDE
  completes against that cache; the Kotlin compiler plugin codegens each fragment's shape
  from it. Output shape of a fragment = qualify + annotate_types (Phase 6) run against the
  cached schema and/or declared input shapes — so inference must also work
  schema-polymorphically for open slots. Before a pipeline runs, shapes are re-checked
  against the live target with three-way verdicts: **satisfies / has less / has additional**.
  Perf posture: this is transform-land, not OLTP/CRUD — a half-second to check 3 tables at
  pipeline startup is fine. No exotic caching cleverness needed.
- **Composition: graft AST or re-parse generated SQL — both are legal.** Plugging a fragment
  in can be an AST graft (CTE/inline subquery — same machinery as the pipe desugar
  transform) *or* generate the fuller SQL text and re-parse. The mental model: pipeline
  syntax divided by Kotlin glue code, later some runnable template logic (e.g. loop per
  field), then everything materializes with vars/bindables and **it's just SQL again before
  execution**. Keep both paths cheap; don't over-invest in graft-only purity.
- **This is the compiler-plugin payload.** `Sql.dialect("...")` literal → serialized
  {normalized SQL, parameter slots, input/output shapes, dependency graph} that the Kotlin
  compiler plugin and IDE plugin consume. Everything Phase 6 emits must serialize with this
  in mind.

### Guiding decisions (proposed)

1. **Pipe stages are first-class.** Unlike sqlglot, parse `|>` chains into a
   `PipeQuery(source, stages: List<PipeStage>)` AST. Desugaring to CTE-form standard SQL is
   a *transform* (matching sqlglot's output byte-for-byte so their pipe tests validate it).
   Generation supports both directions: pipe → standard SQL and standard SQL → pipe (long
   term). Stage-level delegation to external parsers hangs off this same structure.
2. **Stay structurally faithful to sqlglot** where it doesn't fight Kotlin: same component
   split (Tokenizer/Parser/Generator/Dialect), same extension-dict pattern (Kotlin maps built
   per dialect object), same expression-class granularity and *names*, same precedence
   ladder (recursive descent, not Pratt). Every ported function carries a
   `// sqlglot: _parse_x` provenance comment. This is what buys us their test corpus.
3. **Typed AST with a uniform child model.** Sealed `Expression` hierarchy; each node keeps
   sqlglot's `args` map (`Map<String, Any?>`) as the storage + declared `argTypes`, with
   typed accessor properties layered on top. This preserves generic
   `walk/transform/copy/serde` (no hand-written 900-arm visitor) *and* gives Kotlin-typed
   access. Code generation for the ~1,000 node classes from a spec file is on the table once
   the pattern stabilizes (decide in Phase 2, not now).
   Sealed-hierarchy notes: the closed-world constraint (all subclasses in one module) is a
   *feature* for an AST — sqlglot's dialects don't define node classes outside the core
   expressions package either, so all nodes living in `brikk-sql` matches upstream. The wins
   are on the consumption side (exhaustive `when` in generator/transform dispatch, smart
   casts); the parser side is unaffected — parsers construct nodes, they don't match on them.
   Caveat: exhaustive `when` over ~1,000 direct subtypes is not the dispatch plan — use
   sealed *layers* (Expression → Func/Condition/Query/DDL… families) for exhaustiveness
   where it's cheap, and a class-keyed dispatch map (sqlglot's `TRANSFORMS` pattern) for
   per-node generator methods.
4. **Common-first, JVM for delegation.** Tokenizer/parser/AST/generator are pure common
   Kotlin. Native-parser delegation (Doris, Trino) is a JVM-only concern behind a common
   `StageParser`/`StatementParser` interface.
5. **Compat tests are the spec.** Vendor sqlglot fixtures + build the extractor early so
   parity is measurable from the first dialect onward.

### Module layout (target)

```
brikk-sql/                          # kmp/lib — core: tokens, ast, parser, dialects, generator
  src/dev.brikk.house.sql/
    ast/                            # Expression hierarchy, walk/transform, serde
    parser/                         # tokenizer + recursive-descent parser + pipe stages
    dialects/                       # dialect registry, per-dialect tokenizer/parser/generator config
    generator/                      # (added when Phase 3 starts)
brikk-sql-compat/                   # later: vendored sqlglot fixtures + extracted corpus + harness
brikk-sql-doris / brikk-sql-trino/  # later: jvm/lib delegation adapters (ANTLR-based parsers)
brikk-sql-lineage/ (or in core)     # later: scope/qualify/lineage
```

### Phase 0 — Pipe stage splitter (MAIN PRIORITY, first deliverable)

Goal: exactly the user example — take a pipe query, produce a stage document:

```
PipeStageDocument(
  dialect = "bigquery",
  tokenCount = 36,
  pipeOperatorCount = 3,
  rawStages = ["FROM Produce", "WHERE item != :varthing", "AGGREGATE ...", "ORDER BY item DESC"],
)
```

Work items:
- Minimal but *correct* tokenizer subset: strings (all quote forms per dialect config),
  identifiers (quoted forms), comments (`--`, `/* */`, nested), numbers, parens/brackets
  depth, `|>` vs `|`/`||` disambiguation, `:name` / `@name` / `?` placeholders. Splitting on
  `|>` only at depth 0 and outside strings/comments — this is why a real tokenizer, not a
  regex, is required. Build it as the *seed of the full tokenizer port* (TokenType enum,
  Token with line/col/start/end/comments, keyword trie) so nothing is thrown away.
- Stage model: raw text slice (exact offsets into source) + token slice + detected leading
  operator keyword (FROM/WHERE/AGGREGATE/…, from the GoogleSQL spec list) + subpipeline
  awareness (parenthesized `|>` chains inside a stage stay inside that stage).
- JSON output via kotlinx.serialization matching the example shape.
- Tests: the user's example, sqlglot's `test_pipe_syntax.py` inputs (stage-split level),
  googlesql spec examples, nasty cases (`|>` inside strings/comments, nested parens,
  multi-statement input).

### Phase 1 — Full tokenizer port

- Port `tokenizer_core.py` + `tokens.py`: 445 TokenTypes, base KEYWORDS/SINGLE_TOKENS,
  trie, string/comment/heredoc configs, dialect override mechanism (immutable per-dialect
  config objects, computed once and cached).
- Compat: token-stream comparison against Python sqlglot over identity.sql (differential
  harness, even if crude).

### Phase 2 — AST core + parser core (base dialect)

- `Expression` base (args map + argTypes + typed accessors), walk/transform/replace/copy,
  comments, positions, JSON serde (mirror `serde.py` format so ASTs are directly comparable
  with Python's dumps).
- Port the expression precedence ladder + statement dispatch + the extension-dict pattern +
  `Command` fallback + error model (ErrorLevel, structured ParseError).
- Node classes on demand: start with the set needed for SELECT-family queries and grow;
  decide codegen-vs-handwritten once ~100 classes exist.
- Placeholders/parameters parity from day one (`:x`, `@x`, `?`) — brikk's binding syntax
  rides on this.

### Phase 3 — Generator + identity parity

- Port generator dispatch (TRANSFORMS map + per-node methods), pretty printing, identifier
  quoting/normalization.
- Gate: `identity.sql` (979 cases) round-trips; `pretty.sql` passes.

### Phase 4 — Pipe syntax, done right

- Parse all 15 sqlglot-supported operators into first-class `PipeStage` nodes; add the
  **read-side** GoogleSQL operators sqlglot lacks (SET, DROP, RENAME, CALL, WINDOW,
  subpipelines, …) per the spec in `reference/googlesql/docs/pipe-syntax.md`. Write-side /
  terminal ops (INSERT, CREATE TABLE, EXPORT DATA) and TEE/FORK are deliberately later —
  read side only for now (decided).
- `desugarPipes()` transform → sqlglot-identical CTE output (validated by their
  `test_pipe_syntax.py` cases, extracted).
- Pipe *generation* (standard AST → `|>` syntax) — a brikk differentiator; sqlglot can't.
- Delegation interface: a stage, or a *fragment within* a stage (expression, function call,
  stage body), can be handed (raw text + context) to an external parser and its result
  grafted back (opaque-but-typed node wrapping foreign AST/CST). Fragment granularity is the
  primary use case (see Phase 7); whole-stage delegation is the degenerate case.

### Phase 5 — Dialects, incrementally

- Priority order (decided): **mysql → doris → trino → duckdb → postgresql → clickhouse**;
  everything else whenever — explicitly not a priority for now.
  (Status: mysql, doris, presto+trino, duckdb, postgres landed with gates. Before
  clickhouse: **full `annotate_types` port** — decided full-scope, not a slice, because
  type inference is brikk's shape-contract primitive (input shape → SQL → output shape
  for pipeline construction), not merely a transpile aid. Includes the per-dialect
  `typing/*.py` EXPRESSION_METADATA tables (codegen candidate) and the COERCES_TO
  lattice. Side effect: clears the ~19 annotate_types-gated ledger cases.)
- Order works with sqlglot's inheritance chains, mostly in our favor: Doris extends MySQL
  (so mysql-first feeds doris directly); Trino extends Presto (Presto's parser/generator
  come along as an implementation detail even though Presto itself isn't a target).
- Gate per dialect: extracted `validate_identity`/`validate_all` corpus for that dialect
  passing (with a known-differences ledger, polyglot-style).

### Phase 6 — Semantic layer

- Scope → qualify (tables, columns, stars, identifiers) → annotate_types → lineage node
  graph. Optimizer rules only as needed (qualify + simplify first).
- Schema interface designed around the north-star workflow: fed by the build-tool
  introspection **cache** (serializable, updatable), consumed for IDE completion and
  compiler-plugin shape codegen. sqlglot's `MappingSchema` (nested map + trie lookup) is the
  right starting shape for the cache's in-memory form.
- Shape *comparison* is a first-class op: given expected vs actual shape, return
  **satisfies / has less / has additional** (pre-run pipeline check). Cheap is fine —
  transform workloads, not OLTP; sub-second checks at pipeline startup are acceptable.
- Output shapes (projection names/types, dependency graph) designed as serializable data —
  this is the payload the future compiler plugin and IDE plugin consume. Not building those
  now, but the data model is in scope here.

### Phase 7 — Native parser adapters (JVM)

- `brikk-sql-doris`: wrap `org.apache.doris:fe-sql-parser` (ANTLR CST + visitor).
- `brikk-sql-trino`: wrap `io.trino:trino-parser` (typed AST, on Central).
- Both implement the Phase-4 delegation interface at **fragment** granularity: expressions,
  function calls, stage bodies (`parseExpression`-level entry points), and full non-pipe
  statements. Pipes remain brikk's layer — native parsers never see `|>`; brikk wraps/unwraps
  partial SQL so pipe stages work against dialects that don't have pipes.
- Uses: engine-exact validation ("does the native engine accept this fragment?"),
  engine-accurate parsing where our port lags, and feeding precise completion/diagnostics
  into the Kotlin+SQL IDE experience.

### Cross-cutting: test strategy (start in Phase 0–1, grow forever)

1. Vendor `identity.sql`, `pretty.sql`, optimizer fixtures + a ~40-line loader (MIT, keep
   attribution; keep BSD-2 notice for jsonpath cts.json if used).
2. Build/adapt the Python `ast` extractor (see `reference/polyglot/tools/sqlglot-extract/`)
   → `dialect-corpus.json` pinned to sqlglot commit `93d16591`.
3. Differential oracle: script that runs Python sqlglot (it's sitting in `reference/`) vs
   brikk-sql on the same inputs; known-differences ledger checked into the repo.
4. AST-level compat: dump Python ASTs via `serde.py` for identity fixtures; compare with our
   serde output structurally.

### Open questions — dispositions

Reviewed with project owner; status of each:

1. AST codegen vs handwritten classes — **spike and know** (during Phase 2, once the
   SELECT-subset pattern exists).
2. `args` storage (map vs typed fields vs hybrid) — **spike and know** (same spike).
3. Pipe AST shape — **agreed**: review datafusion's consumption of sqlparser-rs pipe ops
   before fixing the node design.
4. GoogleSQL operator coverage — **decided: read side only for now.** TEE/FORK later,
   write-side/terminal ops later.
5. Dialect priority — **decided: mysql, doris, trino, duckdb, postgresql**; others not a
   priority.
6. Table-valued slot syntax — **leaning: plain TVF calls** (`source(args)`), no sigil.
   Slots surface as unresolved/undefined functions in table position (sqlglot `Anonymous`
   fallback) which we intercept and bind. Cost: user must not shadow real engine TVFs —
   conflict detection is on us. Shape declaration for non-inferable slots still open.
7. Cross-dialect composition — **deferred.** Get pipes working first; then decide whether
   we push toward full sqlglot transpile compat or lean on delegated sub-parsers first.
   The pipes milestone is the decision point.
