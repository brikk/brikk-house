# brikk-sql compiler plugin — mechanics research & bootstrap

Status: bootstrap scaffold working (July 2026). Kotlin 2.4.0, K2, FIR + IR, tested, applied to a
consumer module under Kotlin Toolchain 0.11.0.

Goal recap: functions containing `Sql.doris("""FROM t |> WHERE x >= :range.start""")` become
*virtual table-valued functions* — the plugin parses the SQL at compile time with brikk-sql,
infers input bindings (matched to Kotlin parameters) and output shape from the schema JSON,
lets these compose (one query function referenced in another's SQL, traits as `*,a,b => *,z`
transforms), and at final call sites merges ASTs, desugars pipes, and renders dialect SQL.

This document covers the **mechanics** (how such a thing is built/tested/shipped), not the
surface design.

---

## 1. The compiler landscape (Kotlin 2.4.x)

The layers that exist: **FIR** (K2 frontend, phased resolution, checkers) → **Fir2Ir** → **IR**
(backend, what plugins rewrite). "BiR" was a dead 2021–22 backend prototype, never merged;
"DiR" doesn't exist; SIR is Swift-export-internal. Plan around FIR + IR only.

The plugin API is **still unstable** ([KT-49508](https://youtrack.jetbrains.com/issue/KT-49508)
open, owned by Brian Norman at JetBrains). Everything is `@ExperimentalCompilerApi`. The
ecosystem copes by version-pinning — terpal/ExoQuery literally version their plugin
`<kotlinVersion>-<pluginVersion>` (e.g. `2.3.0-2.0.1.PL`); Metro ships per-version compat
layers. Expect churn at every Kotlin minor; pin exactly.

Extension points we care about:

| Extension | Use for us | Status |
|---|---|---|
| `CompilerPluginRegistrar` + `CommandLineProcessor` | entry point + options (schema path, flags) | the standard pair |
| `FirAdditionalCheckersExtension` | parse SQL in checkers, report diagnostics (in-IDE red squiggles under KEFS) | usable |
| `FirDeclarationGenerationExtension` | generate named row-shape types | usable, most-used |
| `FirFunctionCallRefinementExtension` | call-site-refined return types (the Kotlin DataFrame mechanism — closest to "the query defines the type") | `@FirExtensionApiInternals`, explicitly discouraged; phase-2 experiment only |
| `IrGenerationExtension` | rewrite calls: merge TVF ASTs, render final SQL, embed bindings | de-facto stable-ish |

Diagnostics **inside string literals**: fully supported — diagnostic factories take a
`SourceElementPositioningStrategy`; `OffsetsOnlyPositioningStrategy` returns arbitrary
`TextRange`s, so brikk-sql parser error offsets can map to exact positions in the raw string.
Require `"""` raw strings to keep offset math trivial. No mainstream plugin does this yet
(serialization/Compose report whole elements) — mild pioneering, but the infra is there.

Reference material: [Kotlin/compiler-plugin-template](https://github.com/Kotlin/compiler-plugin-template)
(official, on 2.4.0), `plugins/plugin-sandbox` in JetBrains/kotlin (exercises every extension —
we cribbed the 2.4.0 API shapes from it), `docs/fir/fir-plugins.md` (stale but the predicate
API section is current), Metro/DataFrame sources.

## 2. What the neighbors do (references studied)

Shallow clones under `/tmp/opencode/refs/{terpal,terpal-sql,exoquery,sqldelight,kotlin-toolchain}`.

### terpal (`sql("...$x...")` interpolation interception)
- **IR-only, zero FIR.** `Registrar` → one `IrGenerationExtension` → `VisitTransformExpressions`.
- Matches `invoke` on `ProtoInterpolator<T,R>` subtypes and top-level functions annotated
  `@InterpolatorFunction<T>` (the `Sql("...")` form — closest analogue to `Sql.doris`).
- Decomposes `IrStringConcatenation` (unwrapping `.trimIndent()`), splits into
  `parts: List<String>` / `params: List<T>` with the invariant `parts.size == params.size + 1`,
  wraps params via resolved `wrap(X): T` overloads, rewrites the call to the
  `@InterpolatorBackend` function taking lazy `() -> List<...>` lambdas.
- Frontend needs no help because the un-transformed code already typechecks: stub bodies throw
  "plugin not executed". **Types are never refined** — that's exactly what terpal *doesn't* do
  and we want to.
- Tested by applying the plugin to its own `testing` module — no compiler test framework.

### ExoQuery (compile-time query composition — the goldmine)
- Also **IR-only** (avoiding FIR was their K2 strategy; cost = no in-IDE diagnostics).
- **Carrier pattern:** each `sql { ... }` is rewritten to
  `SqlQuery.fromPackedXR("<protobuf-hex-of-AST>", RuntimeSet.Empty, params)` — the transformed
  IR is *self-describing*; downstream transforms pattern-match this shape and re-extract the AST
  from the string constant. No annotations needed within a compilation.
- **Same-compilation composition:** "OwnerChain" walk from use site → declaration body → root
  capture; eagerly transforms declarations encountered out of visit order; "replants" the packed
  AST at the call site while routing `params` through the original call.
- **Cross-file/module composition:** backend IR is ephemeral, so they persist packed ASTs in a
  MapDB file (`StoredXRs.db`) keyed by stable function signature (`callableId + param types`),
  partitioned per source set — and **require `inline`** on shared query functions so Kotlin's
  incremental compilation recompiles call sites when the function changes (stale-AST defense).
  Return types carry a BINARY-retention type annotation (`@Captured`) for detection across
  modules.
- `@SqlFragment` functions = parameterized query fragments ≈ our virtual TVFs: params erased,
  body becomes `XR.Function(args, body)`, call sites become `XR.FunctionApply` — compile-time
  beta reduction. Dialect rendering runs *inside the compiler* using the same multiplatform
  engine that ships at runtime.
- **Runtime fallback tier:** anything unresolvable becomes a tagged AST + `RuntimeSet` bindings,
  rendered at runtime with a compile warning; tests assert `Phase == CompileTime` to prevent
  silent fallback regressions. Golden testing throughout (goldens regenerated *by the plugin*),
  plus kctfork for compiler-error-message goldens.

### SQLDelight
- **No compiler plugin at all.** Gradle-task codegen from `.sq` via IntelliJ PSI run headless
  (shaded intellij-core), KotlinPoet output. Useful to us mainly for the IDE-sharing shape:
  analysis core `compileOnly` against a shaded platform jar, real platform in the IDE plugin.

## 3. Kotlin Toolchain: are we cornered? (mostly no)

- **Supported:** `settings.kotlin.compilerPlugins` in module.yaml — same pipe as
  Compose/serialization (`-Xplugin=` + `-P plugin:id:k=v`, see toolchain
  `KotlinCompilerArgs.kt`). Options, per-platform/test settings, and templates all work; there's
  a Metro test-project in the toolchain repo proving third-party plugins.
- **Limitation:** it only accepts **Maven coordinates** — a local module dependency is
  explicitly unsupported ("only external maven dependencies are supported, not modules").
  Consumption options for our locally-built plugin:
  1. `./kotlin publish mavenLocal` round-trip + `mavenLocal` repo in consumers + real
     `compilerPlugins` entry. Cleanest; two-phase; **bump the version each change** (resolution
     is coordinate-cached, jar bytes are not hashed).
  2. `freeCompilerArgs: ["-Xplugin=<path>"]` — works verbatim (no validation blocks it), path
     relative to project root works; no build ordering or jar-staleness tracking. Good enough
     for in-repo dev; this is what `brikk-sql-plugin-smoke` does.
- The `plugin.yaml` local-plugin system **cannot** inject compiler args into other modules
  today (outputs limited to generated sources/resources/cinterop). The toolchain docs
  explicitly acknowledge this; "plugin-contributed module templates" are the planned fix. A
  future brikk toolchain plugin wrapping our compiler plugin is the intended end state.
- JVM compilation runs **in-process via the Build Tools API using the embeddable compiler** →
  our plugin must be embeddable-compatible (compile against `kotlin-compiler-embeddable`; don't
  touch non-relocated `com.intellij.*`). `settings.kotlin.version: 2.4.0` per-module works
  (verified). The JetBrains Kotlin *bootstrap* repo is pre-wired for plugin resolution —
  useful to test against future compiler versions.
- Schema JSON via CLI option = the Compose `stabilityConfigurationPath` pattern. Known blind
  spot: incremental compilation doesn't track external files — schema refresh needs a version
  bump/clean or an IC-input hash (DataFrame hit this: KT-66735).

## 4. Testing strategy

Two credible stacks (never both on one classpath — `kotlin-compiler` vs
`kotlin-compiler-embeddable` clash):

1. **kctfork** (`dev.zacsweers.kctfork:core:0.13.0`, built for 2.4.0) — in-process compilation,
   plugin registrars passed *in-memory* (no jar), assert on exit code/messages, load and run
   compiled classes. What Poko/Redacted use; Metro uses it alongside the official framework.
   → **Bootstrapped now** in `brikk-sql-compiler-plugin/test`.
2. **Official framework** (`org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:2.4.0`)
   — testData dirs, `<!DIAGNOSTIC!>` markers with exact ranges, box tests, FIR/IR golden dumps,
   `// MODULE:` multi-module directives. What the official template, Metro, and all in-repo
   plugins use. Needs `kotlin-compiler` (non-embeddable) + system-property plumbing + a test
   generator `main()` — all doable under the toolchain, but in a **separate module**.
   → **Add when we start asserting exact diagnostic ranges and FIR dumps of refined types.**
   Install the [Kotlin Compiler DevKit](https://github.com/JetBrains/kotlin-compiler-devkit)
   IntelliJ plugin for the testData workflow.
   ExoQuery-style self-regenerating SQL goldens fit naturally once rendering exists.

## 5. IDE story (later, but keep in mind)

- IntelliJ is K2-only now; the Analysis API runs FIR plugins for resolve. **FIR-side work is
  what makes IDE support possible** — ExoQuery's IR-only choice is why it has none.
- Cheapest path: be loadable by **KEFS** ("Kotlin External FIR Support" marketplace plugin,
  what kotlinx.rpc uses; the toolchain docs recommend it for third-party plugins).
- Full path: own IntelliJ plugin via the `KotlinK2BundledCompilerPlugins` EP (Metro's route) —
  requires binary compat with the *IDE's bundled* compiler builds; Metro's `compiler-compat/`
  per-version `CompatContext` + ServiceLoader is the reference. This is the single most
  expensive maintenance item; defer. SQL language injection/highlighting in the literals is an
  IDE-plugin feature (LanguageInjectionContributor), orthogonal to the compiler plugin.
- DataFrame's call-site return-type refinement working in-IDE proves the deepest version of our
  feature is IDE-viable.

## 6. What exists on this branch

```
brikk-sql-compiler-plugin/          jvm/lib, Kotlin 2.4.0, compileOnly kotlin-compiler-embeddable
  src/dev.brikk.house.sql.compiler/
    BrikkSqlNames.kt                placeholder contract: intercept calls to @dev.brikk.house.sql.BrikkSql functions
    BrikkSqlOptions.kt              CommandLineProcessor: -P plugin:dev.brikk.house.sql.compiler:{debug,schema}=…
    BrikkSqlCompilerPluginRegistrar.kt
    fir/BrikkSqlDiagnostics.kt      KtDiagnosticsContainer (SQL_NOT_CONSTANT, SQL_EMPTY)
    fir/BrikkSqlFirExtensionRegistrar.kt  FirAdditionalCheckersExtension + FirFunctionCallChecker
                                    (2.4 checker API = context parameters → -Xcontext-parameters)
    ir/BrikkSqlIrGenerationExtension.kt   IR transformer: const-evaluates the SQL (incl. compile-time
                                    trimIndent/trimMargin), scaffold-rewrites the call to a marker const
  resources/META-INF/services/…     registrar + CLI processor
  test/                             6 kctfork tests: box behavior, debug-option plumbing,
                                    frontend errors (non-const / interpolated / blank), negative control
brikk-sql-plugin-smoke/             real-toolchain integration proof: applies the built jar via
                                    freeCompilerArgs -Xplugin (relative path), test observes the rewrite
```

Verify: `./kotlin test -m brikk-sql-compiler-plugin`, then
`./kotlin build -m brikk-sql-compiler-plugin && ./kotlin test -m brikk-sql-plugin-smoke`.

2.4.0 API gotchas already absorbed: register FIR via `FirExtensionRegistrarAdapter` (its
companion is the `ExtensionPointDescriptor`); `CallableSymbol.callableId` is nullable; checker
`check()` uses context parameters; unified `IrCall.arguments[param.indexInParameters]` +
`IrParameterKind` instead of receiver properties; diagnostics via `KtDiagnosticsContainer` +
`registerDiagnosticContainers(...)`.

## 7. Proposed architecture direction (mechanics, not surface design)

- **FIR checkers**: run brikk-sql's Doris parser on the literal at CHECKERS stage; validate
  `:param` bindings against enclosing function parameters and schema JSON; report with
  sub-literal ranges. Cache parses in a `FirExtensionSessionComponent`.
- **Shapes/types**: start with `FirDeclarationGenerationExtension`-generated row types +
  checker-enforced explicit return types (stable, IDE-friendly); evaluate
  `FirFunctionCallRefinementExtension` (DataFrame-style inference) as a phase-2 spike.
- **IR**: terpal-style parts/params decomposition for interpolation → parameter bindings;
  ExoQuery-style packed-AST carrier (`fromPacked("<hex>", …)`) for TVF values; OwnerChain-style
  replanting for composition; final render = brikk-sql desugar/merge/generate for the target
  dialect, embedded as constants + a typed bindings object.
- **Cross-module composition**: adopt ExoQuery's constraints deliberately — `inline` (or
  metadata-annotation stashing via `IrGeneratedDeclarationsRegistrar`) + on-disk packed-AST
  store keyed by stable signature. Decide once the composition syntax exists.
- **Fallback tier**: keep a runtime renderer so unresolvable queries degrade to a warning +
  runtime rendering (ExoQuery's Phase marker pattern), instead of hard errors everywhere.
- **brikk-sql dependency**: when the plugin starts using the parser, embed/shade it into the
  plugin jar mindful that the compiler classpath is shared across all plugins.

## 8. Open questions / risks

- Kotlin version skew: plugin is married to 2.4.0; adopt `<kotlinVersion>-<ourVersion>`
  versioning from day one.
- Toolchain gap: no first-class "local module as compiler plugin" — live with
  freeCompilerArgs/mavenLocal until toolchain plugin publication lands (explicitly planned
  upstream).
- IC blind spots: schema JSON changes and rebuilt same-version plugin jars don't invalidate
  consumer compilations — needs process discipline now, a toolchain plugin later.
- The `@BrikkSql`-annotation contract, `Sql.doris(...)` shape, TVF value type, and trait syntax
  are all placeholders pending the surface-syntax exploration.

## 9. Type-system direction (settled in discussion, Jul 2026)

- **DataFrame for how the types work, ExoQuery for how the values work.** Disjoint layers:
  FIR typing playbook from Kotlin DataFrame, IR value/composition plumbing from ExoQuery.
- Shapes are **plugin-generated types** (from SQL + schema JSON) — we own the whole hierarchy.
  Row extension = interface subtyping. Trait conformance is **materialized at generation time**
  (`FirSupertypeGenerationExtension` adds structurally-satisfied trait interfaces as supertypes
  of our own generated shape types). No ambient structural typing — Kotlin can't (a type
  parameter as upper bound admits no other bounds; no denotable intersections; bounds constrain
  but never construct).
- Discipline: **traits live upstream of (or beside) the shapes that satisfy them.** Cross-module
  *retroactive* conformance is impossible (sealed metadata); cross-module *use* is fine (edges
  travel in metadata). Late/sideways traits go through an explicit, **compile-time-checked
  `cast<T>()`** seam (checker compares both schemas field-by-field; grades: exact / widening /
  trustMe). Stronger than DataFrame's runtime-checked cast.
- Naming generated types: derive from enclosing declaration (`WatchEvents.Input/.Output`).
  Constraint (load-bearing): declaration generation runs **pre-body-resolution**, so the SQL
  literal must be *syntactically* extractable from raw FIR — const literal ± trimIndent only,
  no indirection. Anonymous mid-expression call sites: explicit T, or the refinement tier.
- Inference of `T` in `Sql.doris<T>(...): Shape<T>` = `FirFunctionCallRefinementExtension`
  (DataFrame's `@Refine`, `@FirExtensionApiInternals`). Kept strictly as an **optional
  ergonomics tier** — core path uses only stable extensions (declaration generation, supertype
  generation, checkers). DataFrame can build on the internal API because it co-evolves in-repo;
  we can't.
- Pipe-shape algebra: concrete `FROM` = closed shape (schema JSON resolves `*`);
  `WHERE/ORDER/LIMIT` shape-preserving; `SELECT`/`AGGREGATE` close; `EXTEND` = T + cols;
  genuine `*`-openness only enters via parameterized sources → generics/traits.

## 10. Surface-syntax lead: headless pipelines (source as parameter)

Idea: v1 TVFs take the source as the first *Kotlin parameter*; the SQL string is only the
pipeline (`|> WHERE … |> SELECT …`), no `FROM` line. Mechanics wins:

- Defers the hardest novel seam (resolving Kotlin names *inside* SQL text): the source arrives
  as a typed parameter; the plugin binds the parameter's shape as a synthetic input relation
  (parse as `FROM __src |> <fragment>` against the catalog).
- Signature-only analysis: parameter names/types are available pre-body-resolution, so shape
  generation stays within the raw-FIR constraint.
- **Unification**: a headless pipeline *is* a trait (`Shape<T> → Shape<f(T)>`); a FROM-ful query
  is the same object applied to a catalog-table constant. TVFs and traits collapse into one
  mechanical construct.
- Joins reopen multi-source. Middle ground before full in-string resolution: allow SQL-text
  references **restricted to parameter names** (`JOIN other ON …` where `other` is a param) —
  still signature-only lookup, no FIR body resolution needed.
- IDE note: IntelliJ language injection supports **prefix/suffix text** on injected fragments —
  the same synthetic `FROM __src |>` the compiler prepends can be the injection prefix (one
  source of truth for both). Caveat: bundled DataGrip dialects don't know pipe syntax (`|>`),
  so real completion inside pipelines likely means our own injected language backed by
  brikk-sql, not reuse of DataGrip's SQL PSI.

## 10a. Alternative surface: SQL-only model (keep on the table)

Define TVFs + traits entirely in SQL-land (own file type, GoogleSQL-flavored: `CREATE TABLE
FUNCTION` + templated `ANY TABLE` params; pipes make trait application trivially
`FROM someTvf(<params>) |> EXTEND …`), compile to Kotlin API via codegen — the SQLDelight
architecture with a pipe-SQL language. Tradeoffs: zero FIR/IR instability tax, closed-world
composition (no raw-FIR/inline/store machinery), IDE = own language plugin (needed for pipes
anyway); but loses Kotlin-native ergonomics (typed params from Kotlin values, `val` composition,
inference magic) and puts a codegen boundary in the middle.

**Non-exclusive — the de-risking frame:** let **brikk-sql own the semantic model** (catalog,
TVF objects, traits, shape algebra) regardless of surface. The Kotlin compiler plugin is then
one thin frontend (binds Kotlin signatures into the catalog, hands fragments over); SQL files
are another. If FIR churn ever becomes too expensive, the SQL-file surface still works.
Reference: `reference/googlesql` is already in-repo — align templated-TVF/trait semantics with
it and steal test cases.

SQL-file snippets referenced from Kotlin: keep the reference graph **one-directional by
construction** (either Kotlin hosts and SQL snippets are leaf includes that may not name TVFs,
or SQL hosts SQLDelight-style and Kotlin only consumes). Bidirectional means two compilers
needing each other's symbol tables mid-resolution — avoid.

## 11. Horizon (not now — do not lose)

- **Output sinks**: final stage handed to a different system the source DB can't reach
  (cross-DB movement, e.g. render stage N for DB1, ship rows, continue/land in DB2). Keep the
  final-stage boundary pluggable in the render pipeline design.
- **In-memory execution tier**: if client-side steps ever appear, they slot in as a
  DataFrame-like API between SQL stages — same generated shape types on both sides of the
  boundary, but **streaming, not set-based**.
- **Arrow streaming dataframes**: prototype learning (jayson) — only a few Kotlin DataFrame
  interface methods fundamentally break streaming and they're rarely called; upstreaming would
  require DataFrame to separate fixed-memory from streaming results and split those methods
  out. Possible later collaboration/fork point.

## 12. Execution modes (MVP-boundary requirement)

Materialization lives ONLY at the control/input boundary, never in pipeline depth. Target:
**"take this view: run as query / batch insert / incremental batch insert / CDC"** — four
interpreters over one pure, shape-typed pipeline value (logical plan vs physical execution).

| mode | executor action on AST | static requirement (compile-time checkable) |
|---|---|---|
| query | render SELECT | none |
| batch insert | wrap `INSERT INTO target SELECT …` | output shape ⊆ target shape (schema JSON) |
| incremental batch | inject watermark predicate at sources + state | predicate pushes down through all ops; AGGREGATE/JOIN need merge keys or append-only discipline |
| **collapsing materialize** (first target) | recompute affected keys (`WHERE key IN (:changed)` at sources), upsert latest, sink collapses | declared collapse key (+ version col); **key-preserving lineage** source→output; key-closure recompute (joins/aggs complete within key partition); key determines row |
| CDC / perfect IVM | evaluate over deltas (RisingWave/DBSP-style) | every operator has a delta rule (stateless free; aggregates need merge/retract; joins need two-sided state) |

Collapsing mode notes: the sink does the hard half — Doris unique-key merge-on-write and
ClickHouse ReplacingMergeTree natively implement latest-per-key. Two lowerings under identical
static requirements: **storage-collapse** (blind upsert, sink merges) vs **compute-collapse**
(keyed re-request + explicit upsert, for sinks without native collapse). Key-preserving lineage
is a specialization of brikk-sql's already-ported column-level lineage — the first-target mode
has the nearest-to-done analyzer.

- Mode compatibility = per-operator classification (stateless / accumulating / non-monotonic)
  composed up the pipeline — a **static AST analysis in brikk-sql**, surfaced as checker
  diagnostics ("cannot run as CDC: non-monotonic AGGREGATE at stage 3 without merge key").
- Rules: modes may *inject at edges* (watermark param, target binding), never rewrite pipeline
  semantics; pipeline values must stay pure (enforce later: no side-effecting constructs in TVF
  bodies — avoid the dbt disease of models embedding their own materialization).
- Formal grounding when needed: DBSP/Feldera per-operator delta rules; DB-native lowering
  targets: Doris async MVs, ClickHouse MVs.
- MVP slice likely: query + batch insert implemented; **collapsing materialize next** (its
  key-lineage analyzer is nearest to done); incremental/CDC/perfect-IVM declared-but-
  unimplemented with honest diagnostics; classifier stubbed.

## 13. Jinja/dbt-macro replacement mapping (for the MVP flow hunt)

- Column-list manipulation (star-except, reorder `a, f, g, *` → `a, f, g, * EXCEPT(a,f,g)`,
  rename-by-prefix, surrogate keys, pivots) → ordered-shape combinators, compile-time, trivial.
- Reused SQL blocks with holes (`{% macro %}`) → headless-pipeline traits.
- `ref()` / `source()` → TVF references + catalog entries (the composition model itself).
- Conditional fragments (`{% if is_incremental() %}`) → const-branch if compile-time-constant
  (dialect/env via plugin option); genuinely-runtime conditions fall to the dynamic tier and
  forfeit static shapes on that branch — track which flows need this.
- Materialization/orchestration (incremental strategy, snapshots, hooks) → §12 control layer,
  NOT pipeline scope.
- Group-by helpers: `GROUP BY ALL` precedent (BigQuery/DuckDB/Snowflake); positional variants
  ("up to foo") are shape combinators evaluated at desugar.
