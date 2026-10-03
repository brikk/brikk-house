# Handoff: SQL-05 — source-preserving executable pipe lowering

**Owner:** the `brikk-sql` agent, in its own repository. Engine tracks integration
as **ENG-01** in [TODO.md](../TODO.md). **Handoff completed:** the SQL 0.17.0
Maven Central release is integrated; the original request below is retained
as the acceptance and ownership contract.

## Completed release integration

- Runtime, compiler plugin and tooling all consume
  `dev.brikk.house:brikk-sql-jvm:0.17.0` from Maven Central, without a local
  repository or sibling source dependency.
- `Rel.render()` applies only Engine-owned slot/binding edits to the source,
  then delegates each stage to `toSourcePreservingExecutable`. This includes
  native stages, nested pipes, FROM-first normalization and explicit translation.
- Exact SQL-supplied parameter name ranges replace Engine's old marker scanner
  (struct colons, literals/comments and UTF-16 positions are covered).
- `Rel.renderWithDiagnostics()` returns per-stage regeneration/unsupported
  reports. Their ranges index each stage's edited `sourceSql`, not the composed
  result. Unsafe preservation refuses; no generator fallback hides that refusal.
- `./kotlin do assemblePluginJar`, `./kotlin build`, and the full
  `./kotlin test` pass: **107 tests, zero failures/skips**. Regression tests
  execute preserved SQL directly in DuckDB and check projection/limit boundaries,
  binding isolation, nested/CTE pipes, hints/settings and explicit translation.
- SQL and metadata class bytes in the assembled plugin were checked against the
  Maven Central 0.17.0 JARs, not merely against a Maven Local candidate.

**Candidate validation:** Maven Local `brikk-sql-jvm:0.17.0-SNAPSHOT` exposes
`SqlFragment.toSourcePreservingExecutable(target, embedded)`. Engine plugin
assembly, build and all 97 tests passed against that artifact, plus seven
temporary API probes covering native text/embedding, pipe subquery preservation
and direct DuckDB execution, nested-pipe patching, FROM-first normalization,
UTF-16 parameter ranges, explicit translation, and unsafe ClickHouse SETTINGS
movement refusal. The merged plugin class was checked against the local JAR.
The local override/probes were removed after validation. Engine now uses the
Maven Central **0.17.0** release in all three consuming modules, without Maven
Local. Permanent Engine regressions exercise the API through `Rel.render()` and
`renderWithDiagnostics()`, not just dependency compatibility. The original request
below remains useful as the acceptance and ownership contract.

## Why / current boundary

Engine consumes published `dev.brikk.house:brikk-sql-jvm:0.17.0` in its runtime,
compiler plugin and tooling. Do not wire an unpublished sibling checkout into
those modules. Implement and test the SQL primitive independently, publish a
new release when authorized, then hand back the version/API for Engine to use.

The old 0.16.0 library parsed native queries and first-class `PipeQuery` nodes,
then `ast/PipeDesugar.kt` lowered to a standard AST and regenerated the entire
SQL. `SourceMap` tracks output nodes but is best-effort; leaf position
metadata and generated span locations are **not** sufficient proof that an
arbitrary subtree can be replaced with an original source substring.

Engine's source-edit adapter changes only its own bound slot calls and colliding
scalar binding names, removes an embedded statement terminator, and adds a
line break when necessary to protect generated closing parentheses from a
trailing comment. It preserves native same-dialect stages even in mixed
native/pipe graphs. See:

- `brikk-engine/brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt`
- `brikk-engine/brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/NativeSqlEdits.kt`
- `brikk-engine/brikk-engine-kotlin/test/dev.brikk.house.sql.runtime/RelTest.kt`

**0.17.0 integration:** Engine rewrites only its bound slot and binding names in
the source, reparses that source, and calls `toSourcePreservingExecutable` for
every stage, including nested pipes, FROM-first normalization and translation.
The SQL API also supplies exact parameter-name ranges, replacing Engine's old
heuristic colon/marker scanning. Do not implement a handwritten pipe compiler
or keep consumer-specific SQL copies in Engine.

## Required SQL-side contract

Provide a public, independently tested executable-rendering API that keeps
unaffected native SQL text wherever that preservation is proved safe. An
additive opt-in on `SqlFragment.toExecutable(...)`, or a named sibling method,
is fine; return the SQL plus meaningful diagnostics (the existing
`TranspileResult` is a possible fit). Exact naming is the SQL owner's decision.

1. **Same-dialect native input:** preserve its authored text. Parsing for
   analysis does not authorize normalization of casts, function spelling,
   hints, settings, comments, aliases or parameter markers.
2. **Pipe input:** desugar using the input dialect's identifier/namespace rules
   and the existing semantic guards. Change the required structure, not native
   expression/clause/subquery spelling merely because a generator is running.
   Mixed native SQL and pipe stages, pipes in CTEs/subqueries, and native
   subqueries within pipe stages are part of this requirement.
3. **FROM-first input:** normalize where execution requires it, preserving the
   unaffected clauses and trivia. DuckDB-native FROM-first SQL need not be
   normalized just to satisfy another engine's rules.
4. **Translation:** explicit cross-dialect requests still use source-aware
   translation; never graft original-dialect SQL into an incompatible target.
   Existing source-specific function/temporal/null semantics must not regress.
5. **Preservation proofs:** track actual syntactic ranges (UTF-16 offsets) and
   whether nodes/clauses changed. A function-name token position is not a
   whole expression range. Account for comments, operator precedence and
   removed/moved/repeated nodes. No global string replacement, ambiguous
   rendered-substring matching, or regex pipe splitting.
   Accurate named-parameter occurrence ranges remove the old Engine
   limitation: colon punctuation could be ambiguous with a renamed parameter.
   The 0.17 integration uses the SQL-supplied name ranges rather than guessing.
6. **Failure/reporting:** when preservation is not established, report which
   portion was regenerated or refuse the operation explicitly; do not silently
   claim full text preservation. Preserve engine-significant hints or fail
   clearly if the required rewrite cannot safely retain them. Fatal/cancellation
   exceptions must not be converted to a successful result.
7. **Source maps:** if supplied, they must index the final returned SQL, including
   reused native text and inserted structure. Return no map rather than a stale
   generator map whose offsets no longer correspond to the output.

Engine can apply its own slot/bind source edits before constructing the fragment
to lower; the SQL API need not know `Rel`, runtime values, or pipeline graphs.
If a general AST/source-edit primitive is added in SQL, coordinate migration
of Engine's helper rather than duplicating its future implementation.

## Acceptance cases

Use these as focused regressions in addition to the full differential and
exact-ledger suites. Check both authored/output text differences and results
or an equivalence proof; parse/generate/reparse identity alone is insufficient.

| Case | Required result |
| --- | --- |
| `select /*+ SET_VAR(exec_mem_limit=1234) */ ...` in Doris, composed but with no pipe rewrite in that stage | Preserve hint and native expression/alias spelling; the native stage must not be regenerated. |
| ClickHouse `select lower('AbC') ... SETTINGS max_threads = 1` used inside a pipe | Preserve unaffected native clauses and settings only where their original semantics/placement are valid; do not turn same-dialect `lower` into a cross-dialect rewrite. |
| `FROM (select /* keep */ 2::integer as id) AS raw |> WHERE id = :n |> SELECT id` | No executable pipe tokens; preserve the native subquery, cast/comment and bind spelling. Verify the returned row. |
| `SELECT id FROM (FROM (SELECT 1 AS id) AS raw |> SELECT id) AS nested` | Lower only the nested pipe; retain the outer/native SQL where unchanged. |
| Native `WITH`/recursive CTE containing a pipe body | Preserve CTE scope, collisions and unaffected text; do not lose recursion semantics. |
| Repeated WHERE after SELECT/EXTEND alias, LIMIT/OFFSET, DISTINCT or ORDER BY | Retain the input-row/projection boundaries proved by `PipeDesugar`; preservation is not permission to merge unsafe stages. |
| Native comments/literals containing `|>`, `;`, `:n`, `src()`, or emoji | Treat as text; UTF-16 edits must not corrupt subsequent locations. |
| A pipe stage mixing quoted slot aliases and join-qualified columns | Preserve quoting, scopes and alias resolution through the lowered CTE structure. |
| DuckDB `FROM ...`, and equivalent source under Postgres/Doris | Preserve DuckDB-native input; normalize unsupported FROM-first syntax only as needed. |
| Mixed ClickHouse/DuckDB source graph explicitly rendered to a different dialect | Each fragment uses its own source context; no source-preservation bypass of requested translation. |

Cover multiline/trailing line comments and statement terminators. Engine embeds
stage output in `WITH sN AS (...)`: the last comment must not swallow `)`, and
embedded semicolons must be removed safely without removing literal/comment text.

## Delivery / verification

- Keep changes in `brikk-sql`; do not touch the agent's unrelated ongoing work.
- Run `./kotlin build` and the full `./kotlin test`; run actual engine grammar
  verification and DuckDB result probes for the new lowering cases as applicable.
- Preserve registered intentional divergences and exact known-failure signatures.
- Supply the public signature, supported/refused boundaries, test evidence and
  **published version**. Publication needs authorization; do not republish 0.16.0.
- Engine then bumps the SQL dependency in all three consuming module configs,
  replaces the generated branch in `Rel.render()` with the new API, and reruns
  plugin assembly, build and the full suite including source/output-diff tests.

ENG-01 is now closed with the Central-backed integration above. No local SQL
source dependency or Maven Local-only JAR is part of the final delivery.
