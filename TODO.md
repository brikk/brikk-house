# Work list — Brikk Engine

This is the **active index for `brikk-house`**. Keep implementation detail in the linked
design/research docs, but give each top-level piece of work a stable ID here. Check
items off only when their outcome is verified; do not use the original compiler-plugin
review as an open backlog (its 15 findings are resolved).

SQL parsing, lowering and metadata work belongs in `brikk-sql`; native chDB
work belongs in `brikk-chdb`. Separate `TODO.md` indexes have been prepared
locally in those repositories but **are not published yet**. Until they are
committed and pushed there, the [existing SQL rewrite tracker](https://github.com/brikk/brikk-sql/blob/main/TODO-BUGS-rewrites.md)
and [chDB README](https://github.com/brikk/brikk-chdb/blob/main/README.md)
are the public entry points. Do not claim the handoff complete before that.
See [the repository split](docs/repository-split.md) for ownership boundaries.

## First: correctness and production readiness

- [ ] **ENG-01 — Preserve SQL through composition and pipe lowering.** Extend the
  minimum-change rendering policy beyond standalone same-dialect `Rel.render()`:
  bindings, slots/CTEs and pipe desugaring should alter only the required parts of
  native SQL. Test authored-vs-rendered text (hints, comments, whitespace where
  relevant) *and* semantics; fix generic lowering gaps in brikk-sql, not with
  permanent consumer-side SQL copies. Details: [Engine SQL policy](brikk-engine/README.md#sql-preservation),
  [wiring notes](docs/virtual-pipelines-wiring.md#sql-preservation-requirement).
  SQL-side owner: `SQL-05` in brikk-sql's work list.
- [ ] **ENG-02 — Harden compiler analysis and diagnostics beyond the demo.** Resolve
  traits/types without short-name collisions; finish nullability checks for
  trait satisfaction (outer joins and set operations already have focused output
  tests); handle dotted binds and named/reordered generic-pipe arguments; qualify columns by
  scope; report accurate sub-literal ranges. Add a useful diagnostic for a
  call-site-local shape escaping through a plain inferred-return helper.
  Details: [demo-grade shortcuts and helper limitation](docs/RESEARCH-fir-refinement-and-generation.md#demo-grade-shortcuts-to-revisit),
  [wiring open items](docs/virtual-pipelines-wiring.md#open-items).
- [ ] **ENG-03 — Make the compiler plugin distributable and IDE-compatible.** Relocate
  dependencies into one shaded KEFS artifact, build/test against each supported
  IDE compiler version (not just name a 2.4.10 JAR after it), and validate plugin
  loading, diagnostics and hot reload in a live IDE. CLI/smoke tests alone do
  not establish IDE compatibility. Details: [KEFS requirements and current limits](docs/virtual-pipelines-wiring.md#ide-support),
  [Engine dev loop](brikk-engine/README.md#local-development).
- [ ] **ENG-04 — Close the offline-schema refresh loop.** Track captured schema
  snapshots as compiler inputs so refreshes invalidate builds and IDE completion;
  verify capture and resulting shapes against the target Doris deployment.
  Until then, a forced clean build is required after refresh. Details:
  [schema cache](docs/schema-cache.md#compiler-input).

## Decisions and later surfaces

- [ ] **ENG-05 — Decide whether rendered SQL is a compile-time artifact.** Choose
  runtime-only rendering or an inspectable compiled artifact (and its invalidation
  story) before promising dbt-style generated SQL. Details: [wiring decisions](docs/virtual-pipelines-wiring.md#division-of-labour-proposed).
- [ ] **ENG-06 — Design the next composition surface.** Step 4 (`then`/wiring) is
  deferred; specify the API and tests only after the underlying composition
  invariants in ENG-01 hold. Details: [wiring open items](docs/virtual-pipelines-wiring.md#open-items).
- [ ] **ENG-07 — Integrate multi-output pipes into Engine when SQL owns them.** Decide
  the DSL/runner surface for `FORK`/`TEE` branches (and execution/cleanup), after
  SQL-side parsing, desugaring, fidelity certification and owner decisions land.
  Details: [multi-output design](docs/design/pipe-multi-output.md)
  (`SQL-01` in brikk-sql). This is **not** a request to implement the SQL AST
  or desugar in Engine.

## Repository ownership / handoff

| Repository | Active index | Key work |
| --- | --- | --- |
| Engine (here) | This file | `ENG-01`–`ENG-07` |
| brikk-sql | `TODO.md` (prepared locally; pending separate commit/push) | Multi-output pipe semantics, source-preserving lowering, version-qualified dialect parity, BigQuery execution cases, Doris DDL edge, SQL hygiene |
| brikk-chdb | `TODO.md` (prepared locally; pending separate commit/push) | Cross-platform native integration coverage and native-artifact footprint |

Historical (not open): the [compiler-plugin review](docs/REVIEW-compiler-plugin.md)
closed all 15 findings; the SQL evaluation and dialect ledgers live with brikk-sql.
