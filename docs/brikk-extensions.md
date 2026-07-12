# brikk-sql extensions registry — deliberate divergences from sqlglot

brikk-sql is a faithful port of sqlglot (pinned: `v30.12.0-44-g93d16591`), verified by
differential gates. This file registers every place where brikk **deliberately diverges**
from or **extends beyond** sqlglot, so that upstream syncs know exactly where conflicts
can arise: when a future sqlglot version adds its own handling for one of these, the
sync MUST reconcile here (adopt upstream, keep ours, or merge) and update this registry.

All divergence sites are marked in code with the greppable phrase **`brikk extension`**
(`rg "brikk extension" brikk-sql/src`).

## 1. First-class pipe syntax (Phase 4)

- **What:** `|>` queries parse into `PipeQuery` + per-operator stage nodes instead of
  sqlglot's parse-time desugaring into `__tmpN` CTE chains. Desugaring is an explicit
  transform (`desugarPipes`) matching sqlglot's output byte-for-byte; pipe *generation*
  (AST → `|>` text) exists — sqlglot has no equivalent.
- **Where:** `ast/PipeNodes.kt`, `ast/PipeDesugar.kt`, parser `parsePipeSyntax*` handlers,
  generator `pipe*Sql` methods.
- **Conflict risk on upstream sync:** HIGH for the desugar semantics (sqlglot's pipe
  handler table grows most releases — e.g. DISTINCT was added in 30.x; new upstream
  operators must be mirrored in both our parser and `desugarPipes`, with their tests
  landing in the pipe gates). The stage-node design itself cannot conflict (native).

## 2. Extended GoogleSQL pipe operators

- **What:** SET / DROP / RENAME / CALL / WINDOW / standalone OFFSET — parsed, desugared
  (star REPLACE/EXCEPT/RENAME, TVF-arg nesting, EXTEND-like CTE), rendered. sqlglot
  raises "Unsupported pipe syntax operator" for all of these (SET/DROP existed upstream
  briefly and were removed in their #5248 refactor).
- **Where:** `ast/PipeNodes.kt` (5 nodes), parser handlers (spec citations to
  `googlesql/docs/pipe-syntax.md`), `ast/PipeDesugar.kt`, `shape/SqlFragment.kt`
  (`expandStarModifiers`).
- **Conflict risk:** HIGH if sqlglot re-adds SET/DROP (their earlier implementation used
  the same star-modifier desugar — semantics should converge, but CTE naming/shape may
  differ; our pipe gates will catch it).

## 3. Doris: FILTER (WHERE) → CASE rewrite

- **What:** `AGG(x) FILTER (WHERE c)` → `AGG(CASE WHEN c THEN x END)` (and
  `COUNT(*)` → `COUNT(CASE WHEN c THEN 1 END)`) when generating for Doris. sqlglot
  passes FILTER through unchanged, which Doris rejects (no FILTER clause). Conservative
  allowlist (COUNT/SUM/MIN/MAX/AVG/ANY_VALUE/simple ARRAY_AGG); everything else raises
  UnsupportedError — never silently-wrong SQL.
- **Where:** `dialects/DorisGenerator.kt` `filterSql` override.
- **Conflict risk:** MEDIUM — if upstream sqlglot adds Doris FILTER elimination (they
  have precedent: similar rewrites exist for other dialects), prefer upstream's version
  if result-identical, port it, and retire ours; the DorisDialectTest assertions define
  the required behavior either way.

## 4. Shape layer (pure addition)

- **What:** `shape/` package — `SqlFragment`, `Shape`, contracts, slot detection. No
  sqlglot counterpart; consumes only parity-verified primitives.
- **Conflict risk:** none directly; it inherits behavior changes from everything above.

## 5. Native pipe nodes in serde

- **What:** serde registry has a NATIVE section (module `"brikk.pipes"`) for nodes with
  no Python counterpart; `ArgTypesManifestTest` allowlists them explicitly.
- **Conflict risk:** LOW — only if upstream ever introduces same-named classes.

## 6. eliminate_qualify: outer-star duplicate-column fix

- **What:** In sqlglot's `eliminate_qualify` (QUALIFY → subquery rewrite), an original
  projection containing a star produces `SELECT *, rn FROM (subquery)` — the outer star
  already re-exports the inner `rn`, so the output gains a duplicate column and the
  result shape diverges from the original query. brikk collapses the outer projection to
  the bare star. Verified as result-shape-breaking on DuckDB→Doris/Trino by customer
  agents. **Upstream bug candidate — worth reporting to sqlglot.**
- **Where:** `generator/Transforms.kt` `eliminateQualify` (outer projection branch).
- **Deliberately kept upstream behavior:** the Case-B star leak (`SELECT * FROM t QUALIFY
  row_number() OVER (...) = 1` exports the synthetic `_w` helper through the outer star)
  is unchanged — dropping it requires schema-based star expansion; revisit if customers
  hit it.
- **Conflict risk:** MEDIUM — if upstream fixes eliminate_qualify, adopt theirs and
  retire this branch.

## 7. Doris: first-class arrays

- **What:** sqlglot's Doris dialect inherits MySQL's array rejection wholesale, but Doris
  supports arrays natively. Divergences from the Python oracle (v30.12.0-44-g93d16591),
  each rendering pinned against the real Doris FE parser
  (`SqlVerifierTest.dorisAcceptsBrikkArrayRenderings`):
  - **Array literals** render as the canonical constructor `ARRAY(1, 2, 3)` — the same
    string sqlglot's fallback emits, but *without* the "Arrays are not supported by
    MySQL" flag. (The FE parser also accepts `[1, 2, 3]`; Doris docs use `array()`.)
  - **Subscripts are 1-based:** Doris `arr[i]` / `ELEMENT_AT(arr, i)` start at 1, but
    sqlglot inherits MySQL's `INDEX_OFFSET = 0` and mis-renders duckdb `arr[1]` as
    doris `arr[0]` (and doris `arr[1]` as duckdb `arr[2]`). We set parser/generator
    index offset to 1 (`DorisParser.indexOffset` / `DorisGenerator.dialectIndexOffset`).
  - **`ARRAY<T>` type mapping and casts** (`CAST(x AS ARRAY<INT>)`, nested
    `ARRAY<ARRAY<STRING>>`, DDL columns) already worked at parity — covered by tests and
    the verifier pin, no divergence.
  - **Table-position UNNEST** (`SELECT * FROM UNNEST(ARRAY(1, 2, 3))`) is base-generator
    behavior the FE parser accepts; it only failed upstream because of the array-literal
    flag. Now clean.
  - **Scalar-position UNNEST/EXPLODE** (duckdb `SELECT unnest([1, 2, 3])` parses as
    `Explode`) still has no Doris equivalent (EXPLODE is only valid in LATERAL VIEW), so
    it stays flagged — with an accurate message instead of the MySQL one. The
    `EXPLODE(...)` fallback is still emitted (= Python oracle output). Explode inside
    `LATERAL VIEW` is not flagged.
  - **Array set-containment ops** (duckdb `@>` / `<@`) stay flagged with an accurate
    message: Doris's `ARRAY_CONTAINS_ALL` is order-sensitive (subsequence match), so
    there is no result-identical mapping.
- **Where:** `dialects/DorisGenerator.kt` (`dialectIndexOffset`, `arraySql`,
  `arrayOpUnsupportedSql`, `explodeSql` + `Explode` transform), `dialects/DorisParser.kt`
  (`indexOffset`). Tests: `DorisArraysTest.kt` (common),
  `SqlVerifierTest.dorisAcceptsBrikkArrayRenderings` (JVM, real FE parser),
  `TranspileGateApiTest.unsupportedTranslationsAreFlaggedNotSilent` (updated message).
- **Corpus/ledger impact:** none — no dialect-corpus/verify case changes outcome (the
  `doris.json` `ARRAY_SUM(..., ARRAY(2, 3))` output string is unchanged; it just loses
  the bogus warning), all ledgers stay empty/as-were.
- **Upstream-PR candidate:** yes — MySQL's `array_sql`/`INDEX_OFFSET` should be
  overridden in `sqlglot/generators/doris.py` + `Doris.INDEX_OFFSET = 1`; verifier-proven
  against Doris FE grammar `g7027772afcb`.
- **Conflict risk:** MEDIUM — if upstream adds its own Doris array handling, adopt it if
  result-identical (watch the index offset: an upstream fix that keeps offset 0 is
  *wrong* per Doris semantics and should be reported, not adopted). DorisArraysTest
  defines the required behavior.

## 8. Trino: grammar-legality fixes (JSON_QUERY wrapper, SET PROPERTIES)

- **What:** two places where sqlglot's Trino output is rejected by Trino's own grammar
  (`reference/trino/core/trino-grammar/.../SqlBase.g4`), found by the brikk-sql-verify
  gate and fixed as divergences from the Python oracle:
  - **JSON_QUERY wrapper clause:** `jsonQueryWrapperBehavior : WITHOUT ARRAY? | WITH
    (CONDITIONAL | UNCONDITIONAL)? ARRAY?` — the modifier is legal only after WITH, but
    sqlglot's `JSON_QUERY_OPTIONS` cross-products WITH/WITHOUT with every modifier and
    re-emits e.g. `WITHOUT CONDITIONAL WRAPPER`. Under WITHOUT no wrapping happens at
    all, so the modifier is vacuous: we drop it (`WITHOUT WRAPPER` /
    `WITHOUT ARRAY WRAPPER`). The upstream option-table `("CONDITIONAL", "ARRAY",
    "WRAPPED")` typo is also repaired on output (`WRAPPED` → `WRAPPER`). Parsing stays
    lenient (upstream tables untouched).
  - **ALTER TABLE ... SET PROPERTIES:** sqlglot leaves the whole statement as a raw
    `Command` passthrough (warning), re-emitting string-literal property names
    (`'foo bar' = 456`) that the grammar rejects (`property : identifier EQ
    propertyValue`). We parse it into `AlterSet` (option=PROPERTIES) and render
    string-literal keys as quoted identifiers (`"foo bar" = 456`); identifier keys and
    `= DEFAULT` values round-trip byte-identically to the oracle.
- **Where:** `dialects/TrinoGenerator.kt` (`normalizeJsonQueryWrapperOption`,
  `altersetSql`), `dialects/TrinoParser.kt` (`parseAlterTableSet`,
  `parseSetPropertyAssignment`), `parser/Parser.kt` (`parseAlterTableSet` opened).
  Tests: `TrinoDialectTest.kt` (common),
  `SqlVerifierTest.trinoAcceptsBrikkGrammarLegalityRenderings` (JVM, real trino-parser
  481, which also pins that the replaced forms are indeed rejected).
- **Corpus/ledger impact:** trino-verify-known-failures.json is now empty (both rejects
  resolved). Divergences from the oracle are ledgered in
  `generator-corpus/trino-generator-known-failures.json` (1: JSON_QUERY case),
  `parser-corpus/trino-parser-known-failures.json` (3: the SET PROPERTIES statements now
  parse into `AlterSet` instead of `Command`) and
  `annotate-corpus/known-failures-trino-annotated-serde.json` (same 3).
- **Upstream-PR candidate:** yes, both — the JSON_QUERY options table should not offer
  CONDITIONAL/UNCONDITIONAL under WITHOUT (and has a WRAPPED typo), and SET PROPERTIES
  could be parsed properly; upstream's own test inputs
  (`tests/dialects/test_trino.py:15,123`) encode the grammar-illegal forms.
- **Conflict risk:** MEDIUM — if upstream adds real SET PROPERTIES parsing or fixes the
  wrapper table, adopt theirs if the emitted SQL stays grammar-legal (the verifier tests
  define the required behavior).

## 9. Doris: CREATE TABLE `PARTITION BY` clause completion

- **What:** sqlglot emits bare `PARTITION BY (cols)` for Doris CREATE TABLE, but the FE
  grammar (`reference/doris/fe/fe-sql-parser/.../DorisParser.g4` `partitionTable`)
  requires a parenthesized partition-definition list: `PARTITION BY (RANGE | LIST)?
  identityOrFunctionList '(' partitionsDef? ')'`. We complete the clause with Doris's own
  defaults, both FE-analyzer-valid (`LogicalPlanBuilder.visitPartitionTable`,
  `PartitionTableInfo.validatePartitionInfo`):
  - column keys → `PARTITION BY (cols) ()` — the kind-less form is LIST per the FE,
    with an empty initial partition list (partitions added later);
  - a function key (e.g. `DATE_TRUNC(c2, 'MONTH')`) → `PARTITION BY RANGE (func) ()` —
    the FE auto-infers AUTO partitioning from the function expression, and the internal
    catalog rejects functions in LIST partitions, so RANGE is the only analyzer-valid
    completion.
  `CREATE MATERIALIZED VIEW ... PARTITION BY (col)` keeps the bare form (its
  `mvPartition` rule takes no definition list), and explicit
  `PARTITION BY RANGE/LIST (...) (...)` (`PartitionByRangeProperty`) is untouched.
- **Where:** `dialects/DorisGenerator.kt` `partitionedbypropertySql`. Tests:
  `DorisDialectTest` (common), `SqlVerifierTest.dorisAcceptsBrikkPartitionByRenderings`
  (JVM, real FE parser, also pins that the bare form is rejected).
- **Corpus/ledger impact:** the 3 bare-PARTITION BY rejects left
  `doris-verify-known-failures.json`; the output divergences are ledgered in
  `generator-corpus/doris-generator-known-failures.json`.
- **Upstream-PR candidate:** yes — `partitionedbyproperty_sql` in
  `sqlglot/generators/doris.py` emits grammar-invalid DDL; verifier-proven against Doris
  FE grammar `g7027772afcb`.
- **Conflict risk:** MEDIUM — if upstream fixes Doris partition rendering, adopt theirs
  if FE-parser-accepted and analyzer-valid; DorisDialectTest defines required behavior.

## 10. Doris: materialized-view column lists use bare names

- **What:** Doris MV column lists take bare column names, optionally with COMMENT
  (`DorisParser.g4` `simpleColumnDef : colName=identifier (COMMENT ...)?`) — types derive
  from the query and cannot be declared. sqlglot re-emits full typed defs (`c1 INT`),
  which the FE parser rejects. We strip the type from ColumnDefs that sit directly in a
  `CREATE MATERIALIZED VIEW` schema (dropping only what Doris cannot express; the engine
  derives the same columns from the query either way). Non-MV CREATEs are untouched.
- **Where:** `dialects/DorisGenerator.kt` `columndefSql`. Tests: `DorisDialectTest`
  (common), `SqlVerifierTest.dorisAcceptsBrikkMaterializedViewColumnRendering` (JVM,
  real FE parser, also pins that typed MV defs are rejected).
- **Corpus/ledger impact:** the divergence is ledgered in
  `generator-corpus/doris-generator-known-failures.json`. The
  `doris-verify-known-failures.json` entry for the corpus case **stays** with an updated
  reason: the upstream test input `CREATE MATERIALIZED VIEW test_table (c1 INT, c2 INT)
  KEY (c1)` has no `AS <query>`, which the `createMTMV` rule makes mandatory — a query
  cannot be derived from the AST, so the statement is inherently unverifiable; the FE
  parser now advances past the column list and rejects only at `<EOF>`.
- **Upstream-PR candidate:** yes — same `generators/doris.py` area as #9; the upstream
  test input itself is incomplete Doris DDL.
- **Conflict risk:** MEDIUM — same protocol as #9.

## Not-a-bug findings (for the record)

- **Trino `date_trunc` unit casing:** reported as "lowercase-only, case-sensitive";
  verified FALSE against reference/trino source — `DateTimeFieldProvider.match()` folds
  case via `| 0x20` (DateTimeFunctions.java:352), so `'MONTH'` matches. sqlglot's
  uppercase-unit output is valid Trino; no divergence implemented.

## Upstream sync protocol

1. Re-pin `reference/sqlglot`, regenerate all generated tables/corpora (`tools/*.py`),
   run the full gate suite.
2. `rg "brikk extension" brikk-sql/src` and revisit each site against the upstream diff
   (especially `parser.py` pipe handlers and `generators/doris.py`).
3. For each conflict: adopt upstream / keep ours / merge — then update this registry and
   the affected gate expectations in the same commit.
