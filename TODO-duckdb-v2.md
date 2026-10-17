# TODO - DuckDB v2.0 SQL and IDE support

> Research and implementation plan, September 23, 2026. DuckDB v2.0
> (Cyanoptera) is in alpha; the `v2.0-cyanoptera` branch was feature-frozen on
> September 2, with a release projected for the second half of October. This
> records what the preview and a local alpha probe establish, not a promise
> that every preview detail will survive the final release. Recheck against the
> final release notes before declaring 2.0 support complete.
>
> Scope: `brikk-sql` parsing, generation, function metadata, shape/certification,
> native DuckDB verification, and the connection-aware data the IDE plugin
> needs. The IDE plugin is a downstream consumer; keep pure Kotlin SQL code
> free of JDBC and DuckDB-native dependencies.

## Evidence and starting point

- DuckDB's [v2.0 preview](https://duckdb.org/2026/08/17/duckdb-20-highlights.html),
  [PEG parser article](https://duckdb.org/2026/08/20/duckdb-20-peg-parser.html),
  [alpha announcement](https://duckdb.org/2026/09/02/try-duckdb-20-alpha.html),
  [JSON functions article](https://duckdb.org/2026/08/18/reconciling-json.html),
  [recursive CTE semantics article](https://duckdb.org/2026/08/25/how-duckdb-runs-recursive-ctes-faster.html),
  and [lambda deprecation docs](https://duckdb.org/docs/current/sql/functions/lambda.html).
- The [grammar-extension PR #24919](https://github.com/duckdb/duckdb/pull/24919)
  merged into `v2.0-cyanoptera` on September 4. Grammar extensions are
  available through `duckdb_grammar_extensions()` but are **not activated by
  `LOAD` alone**. `active_grammar_extensions` is connection-local and defaults
  to `[]`; explicitly activating a grammar can conflict with other grammars.
  The blog's grammar-registration API was marked provisional, so the merged
  API and eventual release take precedence over its C++ example.
- [Named/default argument PR #22941](https://github.com/duckdb/duckdb/pull/22941)
  changed function call handling. Do not infer default-argument values from
  `duckdb_functions()` if the registry does not expose them.
- Local probe: official Linux CLI alpha `v2.0.0-alpha43143`, fetched via the
  [preview downloads](https://duckdb.org/install/preview.html) on September 23.
  Comparing unique `(function_name, normalized kind)` rows in its
  `duckdb_functions()` with our generated 1.5.5 definitions gives 1,098 vs 881.
  This is **not** a count of new user-callable functions: internal functions,
  collations, removed entries, and changed kinds contribute. Thirteen names
  previously catalogued as `AGGREGATE` (`lag`, `lead`, `row_number`, etc.) now
  have the native kind `window`. The alpha also reports new JSON mutation and
  reconciliation functions, `variant_*` functions, and introspection functions
  such as `duckdb_grammar_extensions`, `duckdb_triggers`, and
  `duckdb_extension_repositories`. In a stock session the grammar-extension
  list was empty and `active_grammar_extensions` was `[]`. The alpha executed
  the published trigger and DML-in-CTE shapes; an arrow lambda failed by
  default, while `lambda x: ...` worked.
- Our baseline is deliberately 1.5.5: the generated
  `brikk-sql/brikk-sql-metadata/src/dev.brikk.house.sql.metadata/GeneratedDuckdbFunctionCatalog.kt`
  contains 881 definitions / 2,682 overloads; `brikk-sql/brikk-sql-verify/module.yaml`
  pins `org.duckdb:duckdb_jdbc:1.5.5.0`. `DuckdbDialect` exposes one static
  catalog and `Dialects.forName("duckdb")` selects one versionless dialect.
  `brikk-sql` is a Kotlin port of pinned SQLGlot, not a port of DuckDB's PEG
  parser. Keep its SQLGlot parity gates and record Brikk-native 2.0 differences
  separately.

## Non-negotiable behavior

- A same-dialect standalone native query that needs no rewriting stays as
  written. Parsing it for IDE features must not silently regenerate it.
  Composition, pipe lowering, and cross-dialect translation require explicit,
  tested AST support; a raw `Command` remains a cross-dialect certification
  refusal. See `brikk-sql/brikk-sql/README.md` and
  `brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/Certify.kt`.
- Engine syntax, registered functions, session variables, and prepared-statement
  parameters are different things. Unknown function names are not automatically
  Brikk relation slots. Unsupported/unknown/engine-unavailable is not "valid".
- The effective SQL language depends on **engine version, loaded extensions,
  active grammar extensions, and connection context**. A `CONNECT` session may
  route subsequent statements to PostgreSQL or MySQL. Never infer SQL support
  solely from a `duckdb` connection URL or a downloaded extension list.
- An engine catalog is evidence of registered names, kinds, and declared
  overloads, not proof of runtime availability in an offline connection,
  null propagation, default argument values, or cross-dialect equivalence.

## Work items, in dependency order

### DDB2-1. Freeze a reproducible 1.5/2.0 comparison

- [ ] Pin an exact 2.0 alpha build and its `pragma_version()` `source_id` in a
  research fixture. Capture registry columns, `(name, kind, extension_name)`
  rows, sample overloads, keyword categories, `duckdb_extensions()`,
  `duckdb_grammar_extensions()`, `active_grammar_extensions`, and
  `duckdb_variables()`. Record which results are from an empty connection and
  which require `LOAD` or configuration. Do not overwrite the 1.5.5 fixture.
- [ ] Diff functions by `(name, kind)` **and** compare overloads, aliases,
  qualified names and extension origin. Split additions, removals, and kind
  migrations. Filter internal-only entries when presenting completion; do not
  silently drop them from an engine-exact registry without a documented rule.
- [ ] Run a small native acceptance matrix: old and new lambda syntax; JSON
  mutation and reconciliation; `VARIANT`; `$name`; triggers; `NEAREST` join;
  DML-in-CTE; nested schemas; `USING KEY` with both `UNION` forms; `FETCH FIRST`;
  expression statements; `COPY` options; `CONNECT`/`DISCONNECT`; external
  resources. Record parser acceptance separately from binder failures and
  execution results. Repeat on a release candidate/final build.

### DDB2-2. Make catalog generation and metadata version-aware

- [ ] Update `tools/generate_duckdb_functions.py` to map the native `window`
  kind to `FunctionKind.WINDOW`; keep the existing macro/table-macro treatment.
  Add a generator gate using a pinned alpha registry dump so new native kinds
  fail with a clear diagnostic rather than an accidental `KeyError`.
- [ ] Keep a version-labelled 1.5.5 catalog while generating a separate 2.0
  catalog. Decide the public lookup/selection API before changing the existing
  `DUCKDB_FUNCTION_CATALOG` default. Do not make 1.5.5 completion claim that a
  2.0 function is built in. Preserve `(name, kind)` multiplicity when a name is
  both scalar and table-valued; `FunctionCatalog.get(name)` currently returns
  one entry for a multi-kind name, so table-position resolution needs a
  kind-aware path.
- [ ] Recheck `DuckdbGrammarBuiltins.kt` against 2.0. In the alpha `if` and
  `overlay` appear in `duckdb_functions()`; grammar-builtins must stay disjoint
  from the chosen version's registry. Keep `FunctionDef` / `FunctionOverload`
  serialized additions optional/defaulted to preserve the metadata API used
  by IDE clients. Evaluate whether `extension_name`, schema, and optional
  argument information can be represented without inventing missing facts.
- [ ] Gate 1.5 and 2.0 catalogs independently in
  `brikk-sql/brikk-sql-metadata/test/dev.brikk.house.sql.metadata/FunctionCatalogTest.kt`:
  sizes/versions, `WINDOW` reclassification, selected JSON/VARIANT functions,
  per-overload argument names, table-valued and duplicate-kind lookup, and
  grammar-builtin disjointness. Refresh `ATTRIBUTIONS.md` / `vendor/README.md`
  provenance with the exact build when the 2.0 data lands.

### DDB2-3. Add a connection-aware language snapshot for IDE consumers

- [ ] Define a small immutable snapshot with engine version, current catalog
  and schema, loaded extensions, active grammar-extension names, connection
  variables, and the *effective* callable functions/overloads. Include
  provenance (built-in, loaded extension, macro) and an unavailable/unknown
  state. Keep JDBC fetching in a JVM-only adapter or the IDE, with a portable
  read-only snapshot consumed by `brikk-sql`/metadata as appropriate.
- [ ] Discover functions from the **same session** using
  `duckdb_functions()`; loaded extensions via `duckdb_extensions()`;
  grammar names via `duckdb_grammar_extensions()` plus the
  `active_grammar_extensions` setting; variables via `duckdb_variables()`.
  The grammar function lists available registrations, not their PEG rules,
  and neither a registered grammar nor an installed extension implies it is
  active. Compare connection-local state before reusing a cached snapshot;
  refresh after `LOAD`, `INSTALL`, `SET`/`RESET`, macro/variable DDL, connection
  switch, and `CONNECT`/`DISCONNECT` as applicable.
- [ ] Use the version-matched static catalog for offline completion, with
  honest unknown status for extensions/session state. When online, overlay
  session functions for completion, signature hints, and diagnostics without
  polluting the generated built-in catalog. Test two connections to the same
  engine with different loaded/active extensions.

### DDB2-4. Fix resolution and variable/slot collisions

- [ ] Make `SqlFragment.isKnownFunction`, `tableSlots`, and
  `unmappableFunctions` accept an effective catalog or equivalent resolution
  context rather than always consulting `Dialect.functionCatalog`. Keep the
  current offline API compatible. A known extension table function must not
  become a Brikk slot or an unmappable-function refusal. For an unrecognized
  `FROM name()` call, use explicitly declared Brikk inputs to decide whether
  it is a slot; otherwise report unresolved instead of assuming a slot.
- [ ] Distinguish `$name` bound parameters from DuckDB session variables by
  context. `DuckdbParser.kt` currently produces a `Placeholder` for `$name`,
  and `SqlFragment.scalarParams` includes it as `NAMED_COLON`. Verify syntax
  and shadowing against both DuckDB versions and `duckdb_variables()`; avoid
  prompting the IDE/compiler for a bind when the name resolves to a session
  variable. Keep `?`, `:name`, `$1`, and explicit Brikk interpolation behavior
  covered by regressions. Do not guess from the sigil alone.
- [ ] Test collisions between a built-in, an extension TVF, a table macro, a
  session variable, and a user-declared relation slot. Exercise the compiler
  plugin's slot checks in `brikk-engine/brikk-engine-kotlin-compiler-plugin`
  if they consume the changed classification.

### DDB2-5. Support core 2.0 SQL deliberately

- [ ] Compare `DuckdbTokenizerTables.kt`, `DuckdbParser.kt`,
  `DuckdbGenerator.kt`, shared AST and parser/generator with each DDB2-1 case.
  Classify as already structured, needs a DuckDB-specific node/override,
  safe native-only opaque statement, or unsupported composition. `OVERLAY`
  and `USING KEY` already have shared parser/generator code; verify semantics
  rather than reimplementing them.
- [ ] Prioritize queries whose internal structure powers the IDE: `NEAREST`
  joins, DML inside CTEs, nested schema qualification, `$name`, `FETCH FIRST`,
  `UNNEST` in `GROUP BY`, `VARIANT` types/functions, and the new expression
  statement. Then cover trigger bodies/transition tables, `CONNECT`, external
  resources, `COPY` options, and extension-repository statements according to
  actual consumer use. No accidental `Command` for SQL we claim to analyze.
- [ ] Generate `lambda x: ...` for DuckDB 2.0, including cross-dialect
  translations and lambdas whose AST lacks the DuckDB-specific `colon` flag;
  preserve the 1.5 behavior when explicitly targeting 1.5. Do not confuse
  JSON extraction `->` with a deprecated lambda. Add a behavior/version gate
  for `USING KEY ... UNION` so semantic certification does not assume its
  1.5 and 2.0 frontiers are equivalent.
- [ ] For same-dialect raw SQL, keep the original text through execution where
  the preservation policy allows it. For queries undergoing AST composition or
  transpilation, demand structured support or refuse with a precise reason;
  never imply that parser acceptance alone makes generation safe.

### DDB2-6. Treat extension grammar as an explicit capability

- [ ] Do **not** make the Google-style `|>` example valid for every DuckDB 2.0
  connection. The [parser article](https://duckdb.org/2026/08/20/duckdb-20-peg-parser.html)
  uses it to demonstrate an extension; Brikk already has a first-class pipe
  AST and defaults to desugaring pipe SQL for native execution. Only preserve
  or emit native pipes when the specific compatible grammar is active and
  native acceptance/semantic tests pass. Otherwise keep lowering to standard
  SQL for executable output.
- [ ] Define plugin diagnostics for inactive grammar, unknown grammar, and
  an extension grammar Brikk cannot structurally parse. For a standalone
  same-dialect query, native parser verification and original-text passthrough
  can still work. Editing/formatting, AST composition, shape/lineage and
  transpilation require targeted syntax support; surface their limits instead
  of silently rewriting unknown syntax. Keep the current refusal for raw
  `Command` in cross-dialect `certify()`.
- [ ] Test at least one *real* alpha grammar extension enabled in a connection,
  including keyword/token changes and SQL inside an otherwise ordinary query.
  Confirm independent connections with different `active_grammar_extensions`
  produce different verdicts. DuckDB's C++ grammar transformers cannot be
  directly imported into our Kotlin AST from registry rows.

### DDB2-7. Version the native verifier and acceptance gates

- [ ] Keep the 1.5.5 JDBC verifier as a baseline; add an isolated 2.0 alpha
  JDBC test target or runtime-injected connection, without classpath collisions
  between native driver versions. The [preview page](https://duckdb.org/install/preview.html)
  advertises a staged 2.0 Java artifact; pin its exact engine build and update
  it for the release. Check `DuckdbVerifier`'s `json_serialize_sql` SELECT
  channel and prepare fallback for non-SELECT, parser vs binder errors, offsets,
  and new PEG error text. Do not run user statements just to verify grammar.
- [ ] Run the 1.5 and 2.0 corpora against their respective native engines,
  including same-dialect parse/generate/reparse and result checks where SQL
  semantics changed. Preserve pinned SQLGlot corpus expectations as a
  separate parity gate; add curated DuckDB 2.0 fixtures and Brikk-native
  divergence notes instead of rewriting the SQLGlot oracle.
- [ ] Include loaded-extension and active-grammar states in verifier inputs or
  mark the verdict unavailable when the verifier's session does not match the
  editor's. `CONNECT` changes the executing backend, so an embedded unconnected
  DuckDB parser cannot certify the target server's full semantics.

## Completion criteria

- [ ] Both 1.5.5 and pinned 2.0 catalogs regenerate, pass metadata tests, and
  select by engine version without claiming an extension-only name is built in.
- [ ] IDE snapshots distinguish offline/connected, loaded/active, native/remote,
  and parameters/variables. A loaded extension TVF is neither reported as a
  missing function nor mistaken for a Brikk slot.
- [ ] Every advertised core 2.0 SQL case has a recorded parse and generation
  verdict, a real-engine acceptance test, and a documented rule for native
  passthrough vs composition. Lambda output executes with 2.0 defaults.
- [ ] Extension syntax is gated on the session's active grammar. Unknown syntax
  leaves same-dialect standalone text intact and cannot silently pass
  cross-dialect certification.
- [ ] Re-run after DuckDB's final release and reconcile the complete official
  breaking-change notes, especially lambda and storage-version behavior.

Suggested focused checks as implementation lands:

```sh
./kotlin test -m brikk-sql-metadata --include-classes='dev.brikk.house.sql.metadata.FunctionCatalogTest'
./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.Duckdb*Test'
./kotlin test -m brikk-sql-verify --include-classes='dev.brikk.house.sql.verify.SqlVerifierTest'
```

The 2.0 JDBC acceptance suite needs its own pinned build/configuration before
the last command can establish 2.0 compatibility; the current verifier only
tests 1.5.5.
