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
