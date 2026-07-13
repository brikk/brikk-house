# Multi-output pipe operators (FORK / TEE / terminal ops) — design proposal

Status: proposal, for owner review. No code changes yet.
Scope: GoogleSQL's multi-output and terminal pipe operators in brikk-sql — AST, desugar,
shape layer, certification, and the Kotlin-DSL surface they must serve.
Spec: `reference/googlesql/docs/pipe-syntax.md` (anchors cited per section below).
Prior decision context: `docs/parsing-research-and-plan.md` open-question #4 said
"read side only for now; TEE/FORK later, write-side/terminal ops later." This doc is
the "later."

Why this matters more to brikk than to a transpiler: per the North-star plan
(parsing-research-and-plan.md, Part 2), brikk is a Kotlin-DSL data-pipeline tool where
every SQL element is a value with (input shape, body, output shape). FORK and TEE are
precisely the *pipeline branch points* of that model — the places where one computed
prefix feeds several sinks (tables, exports, downstream fragments, observability taps).
A transpiler can shrug at them; a pipeline composer cannot.

## Table of contents

1. [Spec semantics summary](#1-spec-semantics-summary)
2. [What breaks today](#2-what-breaks-today)
3. [Design options](#3-design-options)
   - Option A — fragment-level product, per-branch single-statement desugar
   - Option B — statement-list AST container
   - Option C — materialization plan object (non-AST IR)
   - Cross-option: prefix sharing (CTE duplication vs temp-table)
4. [Interaction with the north-star composition model](#4-interaction-with-the-north-star-composition-model)
5. [sqlglot upstream-conflict considerations](#5-sqlglot-upstream-conflict-considerations)
6. [Recommendation and phased plan](#6-recommendation-and-phased-plan)
7. [Owner decisions required](#7-owner-decisions-required)

---

## 1. Spec semantics summary

All citations: `reference/googlesql/docs/pipe-syntax.md`.

### Operator classes (`#pipe_semantics` ~101, `#terminal_operators` ~113, `#semi_terminal_operators` ~128)

- Every pipe operator consumes the input table and produces a new table; only columns of
  the immediate input are visible (~104–111).
- **Terminal operators** (~113): may appear only at the *end* of a pipe query; consume
  the input and **return no result**; allowed only in the outermost query of a query
  statement — never in subqueries or non-query statements (~118–124). Terminal ops:
  `CREATE TABLE`, `EXPORT DATA`, `INSERT`, `FORK`.
- **Semi-terminal operators** (~128): outermost-query-only like terminal ops, but they
  *return a result table* and may be followed by further operators (~136–141). The only
  one today: `TEE`.
- **Subpipelines** (`#subpipelines` ~145): `( |> op ... )` — zero or more pipe operators,
  no `FROM`/`SELECT` head; the enclosing operator implicitly supplies the input table
  (~165–169). FORK/TEE arguments are subpipelines, so branch bodies are *headless*
  pipelines. Nesting is legal: a FORK subpipeline can contain TEE, terminal ops, or
  another FORK — the output structure is a tree whose leaves are result tables and
  side-effect sinks.

### `FORK` (`#fork_pipe_operator` ~2864)

`|> FORK ( subpipeline ) [, ( subpipeline )]...`

- Terminal. Ends the main pipeline; splits into 1..N subpipelines (~2872–2876).
- **The input table is computed once**; a *copy* of the results goes to each subpipeline
  (~2874). Row order is not preserved (~2893).
- Subpipelines behave *as if run sequentially* but **without exposing side effects to
  each other**; side effects from terminal ops inside subpipelines (CREATE TABLE,
  INSERT) are **applied atomically for the entire statement** (~2878–2884).
- Result tables are returned **sequentially in written order** (~2886–2887).
- `FORK` can't be followed by other operators and can't be used in a subquery or
  anywhere a single output table is expected — *even with only one subpipeline*
  (~2889–2891). So `FORK` is structurally multi-output by fiat, not just by count.
- The spec's own equivalence (~2943–2964): a temp table for the prefix + N independent
  standard statements. That is the canonical desugar target.

### `TEE` (`#tee_pipe_operator` ~3017)

`|> TEE` or `|> TEE ( subpipeline ) [, ( subpipeline )]...`

- Semi-terminal. Runs each subpipeline over a *copy* of the input, then **passes the
  original input table to the next operator in the main pipeline** (~3032–3034). If TEE
  is last, its pass-through *is* the query result (~3034–3036).
- Bare `|> TEE` ≡ `|> TEE ()` — emit the current input as a side output and continue;
  the debugging tap (~3039–3042).
- Same side-effect isolation + whole-statement atomicity as FORK (~3044–3050).
- Output ordering: subpipeline outputs are returned **before** the main pipeline's
  output, in written order (~3052–3054). Multiple TEEs stack: each contributes its side
  outputs at its position (example ~3137–3172 shows TEE, TEE(sub), main = 3 tables).
- Row order not preserved in either the copies or the pass-through (~3056–3057).

### Terminal write ops (`#create_table_pipe_operator` ~2757, `#export_data_pipe_operator` ~2808, `#insert_pipe_operator` ~2830)

Each is defined by the spec as *exactly* its standard-syntax statement with the trailing
`AS query` / source query replaced by the pipe input:

- `|> CREATE [OR REPLACE] [TEMP] TABLE t ...` ≡ `CREATE TABLE t ... AS <pipe input>`
  (~2774–2778, equivalence example ~2796–2802).
- `|> EXPORT DATA [OPTIONS(...)]` ≡ `EXPORT DATA ... AS <pipe input>` (~2818–2822).
- `|> INSERT [INTO] t (cols) ...` ≡ `INSERT ... SELECT <pipe input>` (~2841–2845).

Crucial observation: **a terminal write op alone does NOT create multi-output.**
`FROM x |> WHERE c |> INSERT INTO t` desugars to *one* standard `Insert` statement.
Multi-output arises only from FORK/TEE (including terminal ops nested inside their
subpipelines, as in the FORK+CREATE TABLE example ~2966–2983). What terminal ops break
is a different assumption: that a `PipeQuery` desugars to a *query* (something with a
SELECT at the root and an output shape).

### Fidelity gaps any desugar must own

Three spec guarantees have no faithful expression as N independent standard statements:

1. **Compute-once** (~2874): duplicating the prefix as a CTE in each branch statement
   recomputes it per branch — observably different when the prefix is non-deterministic
   (`RAND()`, `CURRENT_TIMESTAMP`), reads mutating tables between statements, or is
   simply expensive.
2. **Atomicity** (~2881–2884): N statements are not atomic without a transaction (and
   several target engines can't wrap DDL like `CREATE TABLE` in one).
3. **Side-effect isolation between branches** (~2878–2881): sequential statements *do*
   expose earlier branches' side effects to later ones. (Only observable when one branch
   writes a table another branch reads — a pathological but legal query.)

These are not reasons to block the design; they are findings the certification layer
must surface (§3, cross-option; §6 phase 3). No real engine other than BigQuery executes
FORK/TEE natively today, so *every* consumer downstream of brikk lives with the same
approximation the spec itself publishes as "equivalent" (~2943, ~3108).

---

## 2. What breaks today

Verified against current sources:

- **`desugarPipes(Expression) -> Expression` is single-output by construction**
  (`ast/PipeDesugar.kt:41`): the stage loop threads one `query` value; every handler in
  `applyPipeStage` returns one Expression. FORK cannot return one query; TEE's side
  branches have nowhere to go. Terminal ops *can* return one Expression (an
  `Insert`/`Create`/`Export` root — the node classes exist:
  `ast/GeneratedNodesDml.kt:71,82`, `ast/GeneratedNodesDdl.kt:166`), but then:
- **`SqlFragment` assumes the statement is a query.** `outputShape()` walks to an
  `outermostSelect` and throws on anything else (`shape/SqlFragment.kt:468–480`);
  `describe()` has no notion of statement kind beyond `rootKind` (which reports the
  *pre-desugar* class, i.e. `PipeQuery`, hiding the terminal nature);
  `contract()` returns exactly one output shape. The prior agent's note is right:
  terminal ops need a `FragmentKind` on `describe()` before anything else.
- **Single-statement invariant**: `SqlFragment.ast` throws unless the parse produced
  exactly one statement (`shape/SqlFragment.kt:62–72`). Any design that desugars one
  pipe statement into N statements must not route the product back through this front
  door (it's the *fragment identity*: one source statement).
- **Parser/AST**: no `PipeFork`/`PipeTee`/terminal stage nodes exist
  (`ast/PipeNodes.kt` — 20 stage classes, none of these), and `PipeQuery.argTypes`
  requires a head (`"this" to true`, PipeNodes.kt:33) — subpipelines are headless, so
  branch bodies need either an optional-head `PipeQuery` or a distinct `Subpipeline`
  node.
- **Certify/verify/source-map** all operate on one `TranspileResult` per fragment
  (`shape/Certify.kt:169`, `TranspileResult.sourceMap` SqlFragment.kt:542) — fine per
  emitted statement, but nothing aggregates N of them.

---

## 3. Design options

Common to all options (not in dispute):

- **Parse-side AST**: three new stage-node groups in `ast/PipeNodes.kt`, registered
  under `brikk.pipes` serde module like the rest:
  - `PipeFork(expressions: List<Subpipeline>)`, `PipeTee(expressions: List<Subpipeline>)`
    — `Subpipeline` is a new node: ordered stage list, *no head* (distinct class rather
    than optional-head `PipeQuery`, so the `argTypes` contract of `PipeQuery` stays
    intact and generic walkers can't confuse a subpipeline with a full query). Bare
    `TEE` parses as `PipeTee(expressions=[])` per the spec equivalence (~3039–3042).
  - `PipeCreateTable(this: Create-shaped args)`, `PipeInsert(this: Insert-shaped args)`,
    `PipeExportData(this: Export-shaped args)` — thin wrappers whose desugar grafts the
    accumulated query into the corresponding standard node (`Create.expression`,
    `Insert.expression`, `Export.this`), exactly the spec equivalences (§1).
- **Placement validation at parse time**: terminal stages only in final position;
  FORK/TEE/terminals only in the outermost query (reject inside subqueries /
  `PipeSetOperation` operands), terminal-op-in-subpipeline allowed (spec examples
  ~2978–2982, ~3186–3189). Mirrors the spec's error posture (~118–124, ~136–141).
- **Pipe generation round-trip** (`generator/Generator.kt` `pipe*Sql` family, ~4981+):
  straightforward for all options — `|> FORK (\n  |> ...)` renders from the stage nodes;
  round-trip gates extend naturally. No option differs here; listed once.

The options differ in **what desugar produces and where the multi-output product
lives**.

### Option A — fragment-level product, per-branch single-statement desugar

The multi-output product is an API of the *shape layer*, not a new AST shape.

- **AST**: as above; no new container node.
- **Desugar**: `desugarPipes(Expression) -> Expression` keeps its signature and
  **throws** (clear `ShapeError`-style message) on FORK/TEE — it is single-output *by
  contract*, now enforced instead of silently impossible. New entry point:

  ```kotlin
  /** Desugars a (possibly multi-output) pipe statement into its ordered list of
   *  independent standard statements. Single-output statements return one element
   *  (== desugarPipes). Branch k embeds the shared prefix as its own CTE chain. */
  fun desugarPipeOutputs(expression: Expression, copy: Boolean = true): List<PipeOutputPlan>

  data class PipeOutputPlan(
      val statement: Expression,       // standalone standard-SQL statement
      val kind: FragmentKind,          // QUERY | INSERT | CREATE_TABLE | EXPORT | ...
      val path: List<Int>,             // branch address in the (possibly nested) tree
      val role: OutputRole,            // MAIN (TEE pass-through / plain pipeline) | BRANCH
  )
  ```

  Semantics: run the existing stage loop up to the FORK/TEE; snapshot the accumulated
  query as the *prefix*; for each subpipeline, `copy()` the prefix, wrap it via the
  existing `buildPipeCte` machinery, and apply the subpipeline's stages; for TEE, also
  continue the main loop on the original. Output order = spec order (TEE side outputs
  before main, FORK in written order, ~2886, ~3052). Terminal stages map onto
  `Insert`/`Create`/`Export` roots. Nested FORK/TEE recurse; `path` addresses the leaf.
- **Shape layer**: `SqlFragment` keeps its single-statement identity (the *source* is
  one statement) and grows:

  ```kotlin
  val kind: FragmentKind                  // for ALL fragments (SELECT → QUERY, etc.)
  val outputs: List<SqlOutput>            // size 1 for today's fragments
  class SqlOutput(
      val kind: FragmentKind, val role: OutputRole, val path: List<Int>,
      val fragment: SqlFragment,          // standalone: generated SQL of the branch plan
  ) { fun outputShape(inputs: ShapeCatalog): Shape? }  // null for side-effect-only
  ```

  `outputs[i].fragment` is constructed from the branch plan's generated SQL (the
  "generate + re-parse" composition path the plan doc explicitly blesses:
  parsing-research-and-plan.md "graft AST or re-parse generated SQL — both are legal").
  Existing `outputShape()`/`contract()` keep working for single-QUERY fragments;
  on multi-output or non-query fragments they throw a *directed* error pointing at
  `outputs` (owner decision #4/#5 on exact posture).
- **describe() / compiler-plugin payload**: `FragmentDescription` gains
  `kind: FragmentKind` and `outputs: List<OutputDescription>` (kind, role, path,
  stageOperators of the branch) — both additive with defaults, wire-compatible with
  persisted payloads (same trick as `ShapeComparison.nullabilityMismatches`,
  Shapes.kt:182). `contract()` grows a sibling `contracts(): List<FragmentContract>`.
- **Certify/verify**: `certify(target)` on a multi-output fragment certifies **each
  branch statement independently** and aggregates findings tagged with the branch path;
  plus the cross-option fidelity findings (below). brikk-sql-verify composes per branch
  exactly as today (each branch is one engine-parseable statement).
- **Source map**: each branch transpile carries its own `SourceMap`; because branch
  statements are built from AST nodes that (for the prefix) are *copies* of positioned
  parse nodes, spans survive copy and map back to the original pipe source. One map per
  output — no multi-statement offset math.
- **Cost**: no invariant broken anywhere; the single-statement world is untouched.
  The product type is honest: a pipe statement *is* one source artifact with N outputs.
- **Risk**: prefix duplication across `SqlOutput.fragment`s means shape resolution
  re-runs qualify/annotate per branch over the duplicated prefix — fine ("transform-land,
  not OLTP", plan doc Phase 6 perf posture).

### Option B — statement-list AST container

- **AST**: new top-level `Statements` (or `Script`) Expression node;
  `desugarPipes` returns it for FORK/TEE. Generator learns to emit `;`-joined text.
- **Desugar**: same branch extraction as A, but the product is packed into one
  Expression so the existing signature survives.
- **Why it loses**: the signature is preserved *in name only* — every consumer of
  `desugarPipes` output (qualify, annotateTypes, lineage, `outermostSelect`, the
  generator dispatch, serde gates, `transpileTo`) assumes a single statement root and
  must now branch on the container; the `SqlFragment` single-statement invariant
  (SqlFragment.kt:62) becomes a lie one level down; source maps need multi-statement
  offset bookkeeping inside one emission; and the node is a brikk-native AST class with
  no sqlglot counterpart *in the hottest sync area* (parser/generator core), maximizing
  upstream-sync friction (brikk-extensions.md protocol). A container node buys nothing
  Option A's list doesn't, and costs a pervasive invariant.
- Verdict: **reject.** (Recorded because the prior agent's notes sketched it; the
  analysis stands even if sqlglot later does something similar — see §5.)

### Option C — materialization plan object (non-AST IR)

- **Shape**: desugar produces a typed plan, not statements:

  ```kotlin
  class PipelinePlan(
      val prefix: Expression,               // the shared computation, desugared
      val branches: List<BranchPlan>,       // subpipeline stages + terminal action
      val mainBranch: Int?,                 // TEE pass-through; null for FORK
  )
  fun PipelinePlan.emit(strategy: MaterializationStrategy): List<Expression>
  enum class MaterializationStrategy { INLINE_CTE, TEMP_TABLE }
  ```

  Statement generation becomes a *strategy choice at emission time*: `INLINE_CTE`
  duplicates the prefix per branch (Option A's behavior); `TEMP_TABLE` emits
  `CREATE TEMP TABLE __brikk_prefix_N AS <prefix>` + N branch statements reading it
  (+ optional `DROP`), which preserves compute-once at the cost of requiring a
  session-scoped statement runner.
- **Why it's attractive**: it is the honest execution model — FORK/TEE *are* plan-level
  concepts, and the north-star DSL will eventually want exactly this object (a branch
  point with a shared prefix) rather than N baked strings.
- **Why not as the foundation**: it introduces a second IR between AST and text, with
  its own serde/walk/compare story, before any consumer exists that needs the
  `TEMP_TABLE` strategy (brikk has no statement-runner layer yet — nothing in brikk-sql
  executes SQL). Premature by the plan doc's own economics ("don't over-invest in
  graft-only purity").
- Verdict: **defer the object, keep the strategy.** Option A's `desugarPipeOutputs` is
  Option C's `emit(INLINE_CTE)` with the plan object flattened away. Adding
  `MaterializationStrategy` as a parameter of `desugarPipeOutputs` later (phase 3) is
  additive; if/when the DSL needs the reified plan, it can be extracted without
  reworking A's surface.

### Cross-option: prefix sharing — CTE duplication vs temp-table, and WHO decides

- **CTE duplication (inline)**: default. Pure, single-statement-per-branch, no session
  or DDL requirements, works on every target dialect, composable as fragments. Fidelity
  gaps: recompute (perf + non-determinism), no atomicity, no isolation (§1).
- **Temp-table materialization**: preserves compute-once; still not atomic (DDL);
  requires an executor session, temp-name allocation, cleanup policy, and dialect-aware
  `CREATE TEMP TABLE` support. It is an *execution* concern.
- **Who decides**: the **caller** (ultimately the pipeline runner / Kotlin DSL), never
  the desugar silently. brikk-sql's job: (a) default to inline CTE — always available,
  (b) expose the strategy knob when phase 3 lands, (c) make the fidelity gap *visible*:
  `certify()` emits a `SEMANTIC_HAZARD`-kind finding (severity: WARNING; owner decision
  #3) when a multi-output prefix contains non-deterministic functions
  (`RAND`/`CURRENT_TIMESTAMP`/`UUID`-class — a static allowlist scan of the prefix AST,
  same posture as the DATE+INTERVAL construct hazard, Certify.kt:248–350) or when
  branches carry write terminals (atomicity note). Honest verdicts, consumer-owned
  acceptance — exactly the `okAccepting` philosophy (Certify.kt:128).

---

## 4. Interaction with the north-star composition model

The north star (plan doc, Part 2): every SQL element is a value with
(input shape, body, output shape); table-valued slots are TVF-style calls bound by
callers; fragments compose by AST graft or generate+re-parse.

- **A FORK/TEE branch is a fragment — therefore a slot-provider.** Under Option A,
  `outputs[k].fragment` is a full `SqlFragment` (standalone SQL embedding the prefix),
  so it plugs into another fragment's slot with zero new machinery:

  ```kotlin
  val ingest = Sql.doris("""
      FROM raw_events
      |> WHERE dt = :day
      |> FORK
          (|> AGGREGATE COUNT(*) AS n GROUP BY kind |> INSERT INTO stats.daily),
          (|> SELECT user_id, amount)
  """)

  ingest.kind                       // FragmentKind.MULTI
  ingest.outputs.map { it.kind }    // [INSERT, QUERY]
  val enriched = Sql.doris("FROM cleaned() |> JOIN dim_users USING (user_id)")
      .bind("cleaned" to ingest.outputs[1].fragment)   // branch as slot-provider
  ```

  Shape flows through unchanged: `outputs[1].fragment.outputShape(catalog)` is the slot
  shape `enriched` resolves against; three-way `Shape.compare` verdicts
  (SATISFIES / HAS_LESS / HAS_ADDITIONAL, Shapes.kt:153) apply per branch at pipeline
  startup, exactly the pre-run check the plan doc specifies.
- **TEE is the observability tap.** The DSL sugar writes itself: a `tap {}` that
  compiles to `|> TEE (...)` (or bare `|> TEE`), leaving the main pipeline's type/shape
  untouched — which is precisely TEE's spec semantics (pass the *original* input
  downstream, ~3032). The main output stays the fragment's "primary" contract
  (`role == MAIN`); taps are side contracts the runner can wire to loggers, audit
  tables, or dev-time inspection.
- **DSL product naming**: prefer `fragment.outputs: List<SqlOutput>` over a separate
  `PipelineProduct` type — a single-output fragment is the degenerate case
  (`outputs.size == 1, role == MAIN`), so consumers write one code path. `mainOutput:
  SqlOutput?` convenience (null for FORK — it has no pass-through, ~2889) rounds it out.
  If phase-3+ needs the reified shared-prefix plan (Option C's object) for the
  temp-table runner, it becomes an *additional* view (`fragment.plan`), not a
  replacement.
- **Compiler-plugin payload**: `describe()` already serializes; the additive
  `kind` + `outputs` fields make a `Sql.dialect("...")` literal with FORK/TEE codegen
  N typed accessors (one per output, with role/kind/shape) instead of one — the
  multi-contract the task brief names. Nothing else in the payload model changes.

## 5. sqlglot upstream-conflict considerations

Facts, verified against the pinned checkout (`v30.12.0-44-g93d16591`):

- sqlglot has **no FORK/TEE/terminal-op pipe handling**: no match for `FORK`/`TEE` in
  `sqlglot/parser.py`; the `PIPE_SYNTAX_TRANSFORM_PARSERS` table stops at the read-side
  operators (plan doc §2 research). Same for polyglot (full pipe support, but no
  fork/tee in its parser). sqlparser-rs (datafusion's parser, pinned 0.62 in
  `reference/datafusion/Cargo.toml:197`) models pipe operators as AST values; whether
  its `PipeOperator` enum has since grown Fork/Tee variants must be re-checked at the
  next reference sync — it is the most likely first mover, and the only reference that
  models pipes the way we do.
- **If sqlglot ever adds these**, their architecture forces an awkward choice that we
  should *not* inherit: `_parse_pipe_syntax_query` desugars during parsing and must
  return one Expression per statement. For FORK they would have to either (a) introduce
  their first pipe AST node (breaking their own no-pipe-AST invariant), (b) desugar
  TEE by *dropping* side outputs / rejecting FORK (lossy), or (c) return a
  multi-statement product from one source statement, breaking `parse_one` semantics.
  Whatever they pick, their desugared output for TEE-degenerate cases (bare `|> TEE`
  mid-pipeline with no side effects ≡ identity) may become byte-comparable to ours —
  those are the cases to gate.
- **Extensions-registry posture** (`docs/brikk-extensions.md` entry 2 covers extended
  pipe operators; this work extends that entry): conflict risk with upstream is
  concentrated in (1) node *names* in the serde NATIVE module — `PipeFork`/`PipeTee`
  etc. collide only if upstream introduces same-named classes (registry entry 5, LOW),
  and (2) desugar-output equivalence gates if upstream's eventual desugar differs from
  our CTE-duplication scheme (HIGH, same class as the existing SET/DROP risk). Action:
  when this lands, update entry 2 with the new operators and add an explicit note that
  `desugarPipeOutputs` (multi-statement) has NO upstream counterpart by design — an
  upstream single-Expression desugar for TEE must not be adopted blindly, because
  dropping side outputs is semantically wrong for a pipeline tool even if convenient
  for a transpiler.

## 6. Recommendation and phased plan

**Recommendation: Option A** (fragment-level product, per-branch single-statement
desugar via CTE duplication), with Option C's `MaterializationStrategy` reserved as a
phase-3 additive knob and Option B rejected. Rationale in one line each: A is the only
option that adds multi-output without breaking a single existing invariant; C's plan
object has no consumer until a statement runner exists; B preserves a function signature
by poisoning every downstream consumer.

### Phase 1 — parse + validate + describe (no desugar of FORK/TEE)

- `Subpipeline`, `PipeFork`, `PipeTee`, `PipeCreateTable`, `PipeInsert`,
  `PipeExportData` nodes + serde registration + `NATIVE_EXPRESSION_CLASSES`;
  parser handlers with spec-anchor citations; placement validation (§3 common).
- Pipe *generation* for all six (round-trip gates from spec examples ~2901–2912,
  ~3066–3077, ~3137–3148, ~2784–2787).
- `FragmentKind` on `SqlFragment` + `describe()` (additive field). Terminal-op-only
  pipelines (single-output) desugar fully in this phase — they map 1:1 onto existing
  `Insert`/`Create`/`Export` nodes (§1) and unblock the shape layer's
  FragmentKind plumbing with no multi-output machinery.
- `desugarPipes` throws a directed error on `PipeFork`/`PipeTee`.
- Update `brikk-extensions.md` entry 2.
- Effort: **2–4 days** (parser+generator+nodes are mechanical against the existing 20
  stage nodes; validation and gates are the bulk).

### Phase 2 — multi-output desugar + fragment product

- `desugarPipeOutputs` (branch extraction, prefix copy, nested FORK/TEE, path/role
  assignment, spec output ordering); `SqlOutput` / `outputs` / `mainOutput` on
  `SqlFragment`; `contracts()`; `FragmentDescription.outputs`; per-branch
  `outputShape`/lineage (all via the branch fragments — reuse, not new resolution code).
- Gates: spec examples as fixtures (the FORK/TEE ↔ temp-table equivalences ~2943–2964,
  ~3108–3129 pin branch-statement semantics); serde round-trip; describe() payload
  compatibility.
- Effort: **3–5 days** (the desugar loop restructure is the risky part: today's
  in-place stage mutation must snapshot cleanly at the branch point).

### Phase 3 — certification + emission strategies

- Per-branch `certify()` aggregation with path-tagged findings; non-determinism and
  atomicity findings on multi-output prefixes (§3 cross-option); brikk-sql-verify
  wiring per branch statement.
- `MaterializationStrategy` parameter (`INLINE_CTE` default, `TEMP_TABLE` emitting the
  prefix DDL + reads) — emission only; execution/cleanup remains the runner's problem.
- Effort: **2–4 days**.

### Phase 4 (deferred, tracked) — DSL surface

- `tap {}` / branch-binding sugar in the Kotlin DSL layer; the reified `PipelinePlan`
  view if the runner needs it. Out of brikk-sql scope; listed so the API decisions in
  phases 1–3 are made facing it.

Total core effort: **7–13 days** across three independently-landable phases.

## 7. Owner decisions required

1. **Confirm Option A** (fragment-level product; reject AST container Option B; defer
   plan-object Option C to the strategy knob). Blocks everything.
2. **`outputShape()` on a non-query fragment** (e.g. `... |> INSERT INTO t`): throw a
   directed error (recommended — the write's row shape is available but is a different
   contract), or return the inserted-rows shape? Blocks phase 1.
3. **Fidelity-finding severity**: non-deterministic prefix under CTE duplication —
   WARNING (recommended; `okAccepting`-compatible) or REFUSAL? Same question for the
   multi-write atomicity note. Blocks phase 3, should be pre-decided in phase 1 docs.
4. **`outputShape()`/`contract()` on a multi-output fragment**: throw directed error
   pointing at `outputs` (recommended), or alias to `mainOutput` when one exists (TEE
   convenience, but silently wrong-ish for FORK)? Blocks phase 2.
5. **Branch addressing**: positional `path: List<Int>` (recommended, matches spec
   ordering guarantees) vs named branches (would require inventing syntax the spec
   doesn't have, e.g. abusing `|> AS` inside subpipelines). Blocks phase 2 API.
6. **Payload evolution**: additive-with-defaults on `FragmentDescription` (recommended,
   precedent: `nullabilityMismatches`) vs versioned payload schema. Blocks phase 2.
7. **Phase-2 scope cut**: allow terminal ops inside subpipelines from day one
   (recommended — the spec's flagship examples use them) or query-only branches first?
   Affects phase-2 estimate by ~1 day.
8. **Temp-table strategy naming/cleanup contract** (phase 3): `__brikk_prefix_N`
   naming, DROP emission on/off, TEMP vs permanent fallback for engines without
   session temp tables — decide when phase 3 starts, flagging now.
9. **Upstream posture**: pre-register the multi-output design in `brikk-extensions.md`
   entry 2 during phase 1 (recommended) and pin "do not adopt a lossy upstream TEE
   desugar" as sync policy — approve the policy sentence.
