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
