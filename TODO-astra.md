# SQLGlot Review Actions

Companion to [REVIEW-astra.md](REVIEW-astra.md), reviewed 2026-09-05 at
`d9d3970ab4551534db78059a217d78a48b8fb9b9`.

IDs are shared with the review and must not be renumbered or reused. P1/P2/P3
indicate priority, not implementation size. ASTRA-019/020 are architectural
decisions/recommendations, not claims that the existing architecture is broken.
All actions below are open; this review made no production fixes.

## First Fixes

### ASTRA-001: Preserve QUALIFY Clause Order

- [ ] P1: Move final ordering/limiting behavior to the correct query level when
  lowering QUALIFY; review DISTINCT placement and hidden helper projections too.
- [ ] Commit a result-based regression for the two-row example in the review.
  Source and lowered output must both return `2`, not an empty result.
- [ ] Cover OFFSET/FETCH and the related DISTINCT ON modifier-order path.
- [ ] Record this inherited bug as an intentional correctness divergence and track
  upstream reporting/adoption. Do not weaken the oracle gate to hide it.

### ASTRA-002: Preserve Outer-Explosion Cardinality

- [ ] P1: Port the missing empty/null `EXPLODE_OUTER` handling in
  `explodeProjectionToUnnest` for Presto/Trino.
- [ ] Verify nonempty, empty, and NULL collections, including applicable map and
  positional forms; assert rows, null values, and output column names.
- [ ] Add a separate zipped-explosion test with one empty input. Determine and
  document any additional inherited upstream defect instead of assuming the
  outer-explode fix covers it.

### ASTRA-003: Model Outer-Join Nullability

- [ ] P1: Mark columns on null-supplying sides of LEFT/RIGHT/FULL joins nullable,
  including derived-table/CTE sources and join chains.
- [ ] Retain conservative results where null-rejecting-filter analysis is absent.
  Do not turn catalog NOT NULL into a false output guarantee.
- [ ] Add shape tests and a compiler regression proving the review's LEFT JOIN
  produces `Int?`, not `Int`.

### ASTRA-004: Compute Set-Operation Output Contracts

- [ ] P1: Use reconciled result-column types for top-level set operations instead
  of reading types from the left SELECT.
- [ ] Implement operator-appropriate nullability composition in scope summaries,
  including nested/derived set operations. Do not assume a wrapper fixes it.
- [ ] Test the INT/BIGINT and non-null/NULL UNION examples; include positional and
  supported BY NAME forms, and distinguish UNION from INTERSECT/EXCEPT semantics.
- [ ] Add compiler-level checks for the resulting numeric type and nullability.

### ASTRA-005: Lower Nested Pipes At Public Entry Points

- [ ] P1: Remove root-only restrictions when executable generation or analysis
  requests recursive pipe lowering. Reuse the existing desugarer.
- [ ] Test pipes inside subqueries and CTEs through `toExecutable`,
  `transpileTo(desugarPipes=true)`, `outputShape`, lineage, and certification.
- [ ] Assert no PipeQuery nodes remain after requested lowering; check source-map
  consistency and preserve the explicit pipe-rendering mode when not requested.

### ASTRA-006: Pass Each Runtime Node's Source Dialect

- [ ] P1: Supply `sourceDialect = node.dialect` at both `Rel.render` generation
  paths, preserving per-node context in mixed-dialect chains.
- [ ] Test DuckDB-to-ClickHouse LOWER, the native ClickHouse control, and both
  mixed-chain directions. Only cross-dialect nodes should select `lowerUTF8`.
- [ ] Check other source-aware rules through the runtime API, not just direct
  generator tests. Handle diagnostic API design separately under ASTRA-020.

## Required Lowering

UNNEST normalization, CTE-column preservation, named-window expansion, and
type-sensitive rendering are required semantic lowerings, not optional
optimization. Apply them where the target dialect needs them to preserve valid
SQL and equivalent behavior; otherwise report the unsupported conversion.

### ASTRA-007: Resolve Physical Inputs By Scope

- [ ] P2: Replace global CTE-name filtering in `sourceTables` with existing scope
  resolution, retaining physical sources outside a nested CTE's visibility.
- [ ] Test the review's nested-CTE UNION, non-recursive self-shadowing, quoted/case
  normalization, and qualified physical tables.
- [ ] Require consistent scope-resolved identities in `sourceTables` and contract
  inputs, accounting separately for slots. Resolvable physical lineage leaves
  must belong to the input inventory; not every input must appear in projected
  column lineage. Do not treat unresolved placeholders as physical tables.

### ASTRA-008: Complete BigQuery UNNEST Normalization

- [ ] P2: Port implicit-to-explicit UNNEST normalization and BigQuery's output
  alias representation together with the corresponding generator transforms.
- [ ] Cover scalar arrays, arrays of structs, implicit dotted array references,
  offsets, and both BigQuery-to-Presto/Trino and reverse routes.
- [ ] Preserve legitimate struct-field qualifications. Do not enable
  `unqualifyUnnest` alone against the current parser representation.
- [ ] Reconcile affected entries in `TODO-bigquery.md` and exact failure ledgers.

### ASTRA-009: Lower BigQuery Relation Forms

- [ ] P2: Implement derived-VALUES conversion and CTE column-list pushdown for
  resolvable explicit projections.
- [ ] Preserve output names and column order; test multiple rows and types.
- [ ] Handle schema-dependent stars separately, with precise diagnostics if
  equivalent lowering cannot be determined. Never silently erase column names.

### ASTRA-010: Repair Temporal Mappings

- [ ] P2: Preserve TIMESTAMP_DIFF units in BigQuery and add Presto's corresponding
  TimestampDiff/DatetimeDiff renderers with correct argument order.
- [ ] Lower week-start semantics rather than rendering internal WeekStart nodes
  as generic function arguments.
- [ ] Test native identity and cross-dialect routes with positive/negative
  differences and week-boundary examples. Reconcile the existing temporal ledger.

### ASTRA-011: Inline Named Windows For Presto

- [ ] P2: Port `eliminate_window_clause` at the Presto boundary, covering referenced
  and inherited window specifications without confusing Trino's capabilities.
- [ ] Validate target acceptance where a Presto verifier/engine is available;
  otherwise retain explicit test/provenance limits.

### ASTRA-012: Finish Type-Sensitive Generator Branches

- [ ] P2: Repair Presto struct-array UNNEST output aliases and exploded-column
  references; wire field-name inheritance where required.
- [ ] Use inferred text/binary types for Presto MD5 conversion, preserving binary
  inputs and converting the review's text literal appropriately.
- [ ] Implement BigQuery typed STRUCT string-key access as field access.
- [ ] Test annotated and unannotated inputs, plus schema-backed columns and UNKNOWN
  cases. Require a warning/refusal where missing type evidence prevents a safe
  conversion; do not enable global optimization as a substitute.

### ASTRA-013: Lower Or Flag BigQuery Array Operations

- [ ] P2: Implement supported projection EXPLODE, array FILTER, and ARRAY_REMOVE
  conversions with correct row/null behavior.
- [ ] Until implemented, emit specific unsupported diagnostics instead of generic
  function fallback for known untranslatable builtins.
- [ ] Test actual target SQL and preserve legitimate UDF passthrough policy.

## Maintenance

### ASTRA-014: Account For Executed Coverage

- [ ] P2: Synchronize supported dialect scope across qualification/lineage corpus
  generation and Kotlin registration; revisit currently skipped supported cases.
- [ ] Wire a base transpile gate or explicitly inventory which extracted base
  assertions are deliberately unexecuted and why.
- [ ] Report executed, excluded, unextractable, and oracle-failing counts
  separately. Fail when a supported dialect is silently marked unavailable.
- [ ] Add public-entry-point tests for stage compositions so direct helper corpus
  success cannot conceal ASTRA-004/005/006-style integration gaps.

### ASTRA-015: Strengthen Case Identity And Failure Ledgers

- [ ] P2: Retain upstream filename/test/case identity and effective settings in
  extracted cases; reject duplicate stable IDs rather than collapsing them.
- [ ] Apply supported rendering options consistently or mark them explicitly
  unsupported. Separate SQLGlot-oracle execution from source-aware Brikk API tests.
- [ ] Enforce normalized failure categories/signatures in addition to failing-key
  sets. A known SQL mismatch becoming an unrelated exception must require review.
- [ ] Tag expected divergences separately from defects, with owner/reason and
  regression links. Preserve stale-entry rejection.

### ASTRA-016: Make Generation Reproducible

- [ ] P2: Replace the hardcoded function-registry version label with verified
  upstream provenance; reject unexpected missing generated function classes.
- [ ] Add one authoritative full-SHA/input manifest and validate the actual
  imported SQLGlot location/revision before generating.
- [ ] Provide an ordered regeneration entry point and an inventory of owned
  outputs, including generated Kotlin and optional compressed fixtures.
- [ ] Add a separate regeneration-diff check and keep engine catalog provenance
  distinct from the SQLGlot pin. Do not claim that matching labels proves parity.

### ASTRA-017: Keep A Cumulative Upstream-Fix Index

- [ ] P2: Extend the existing catch-up ledger rather than replacing working
  commit-based tracking. Preserve history in an append-only searchable index.
- [ ] For each reviewed upstream range, record SHA/PR, affected Kotlin symbols,
  disposition/reason, local implementation change, regression IDs, and conflicts
  with intentional divergences. Account for excluded commits too.
- [ ] Track upstream issue/PR status and adoption revision for local correctness
  fixes. Use helper-body change detection where generation recognizes functions
  by name but handwritten Kotlin implements their behavior.

### ASTRA-018: Decide Engine-Version Support

- [ ] P3: State supported engine versions. If older versions matter, implement
  dialect settings and execute the nine known version-qualified cases properly.
- [ ] Otherwise keep the deferral visible and reject or clearly document
  unsupported version selection. Do not describe current-dialect output as
  compatible with an older requested version without evidence.

## Architecture Decisions

### ASTRA-019: Retain Core Correspondence

- [ ] Record the decision to retain upstream names, AST argument schemas, dialect
  inheritance, dispatch boundaries, and recognizable algorithms for now.
- [ ] Permit local cleanup with an obvious upstream symbol map. Keep generated
  and handwritten ownership separate and strengthen Kotlin-facing boundaries.
- [ ] Require a measured performance problem, recurring defect class, or concrete
  API requirement before undertaking a different core AST/parser architecture.

### ASTRA-020: Separate Analysis, Lowering, And Execution Assurance

- [ ] Preserve the deliberate no-full-optimizer boundary. Classify required
  target lowerings separately from optional query-plan optimization.
- [ ] Document the small stage contract for author-facing SQL, analysis copies,
  executable SQL, and strict machine consumption. Avoid a generic pass framework
  unless real composition requirements demand one.
- [ ] Offer a diagnostic-preserving runtime route and an explicit strict policy
  without silently changing the existing best-effort string API's contract.
- [ ] Compose existing certification/native verification where required; retain
  their limits. Grammar acceptance and no refusals do not prove result equivalence.
- [ ] Add focused result tests for required rewrites, especially cardinality,
  clause order, output names, and nullability.

## Completion Rule

For every implementation action, add a focused regression, run the affected module
and corpus gates, and reconcile only the intended ledger entries. Record whether
verification is AST/string parity, target grammar acceptance, or executed result
equivalence. Do not mark an item complete just because it was added to a ledger.

Review baseline checks passed: `./kotlin test -m brikk-sql` with 590 tests and
`./kotlin test -m brikk-sql-runtime` with 2 tests. Those passing checks do not close
any of the findings above. The recommended order is ASTRA-001 through ASTRA-006,
then missing required lowerings and ASTRA-014 through ASTRA-017. Architecture
decisions should guide those fixes, not delay them behind a rewrite.
