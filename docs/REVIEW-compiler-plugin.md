# Review guide: brikk-sql compiler plugin (as of `d9d3970`)

For a reviewer coming in cold. Covers what the plugin does, where the code is, what is
deliberate, and where to look hardest. Commit range: `d3df965..d9d3970` on `main`
(`git log --first-parent d3df965..d9d3970`).

Relocation note: the compiler/runtime/tooling/smoke modules now live under
[Brikk Engine](../brikk-engine/README.md); generic SQL and chDB have their own
group directories. The code map, links, and current build paths below use the new
layout. Descriptions, commit names, counts, and evidence still record `d9d3970`.
Kotlin packages, annotations, and compiler ID `dev.brikk.house.sql.compiler` are
unchanged. The later [SQL preservation requirement](../brikk-engine/README.md#sql-preservation)
is not implemented behavior established by this snapshot.

## 1. What it does, in one paragraph

A K2 compiler plugin (`brikk-sql-compiler-plugin`, ~2.5k lines) that turns a function whose
body is a single `Sql.<dialect>("...")` call, annotated `@BrikkSql`, into a typed virtual
table-valued function. At compile time it parses the SQL with brikk-sql against a schema
catalog (a DDL file passed as a plugin option), infers the output columns, generates an
interface `<FunctionName>Out` (a `Shape` when the column set is closed, a `Partial`
otherwise) and types the call as `Rel<<FunctionName>Out>`. `Rel<T>` parameters are table
inputs referenced from the SQL as slot calls (`FROM $src()`); scalar parameters, locals and
properties are named binds (`$start` -> `:start`). Generic pipes (`fun <T : HasPayload> f(src: Rel<T>)`)
get a call-site local shape via `FirFunctionCallRefinementExtension`. The IR rewrite replaces
the `Sql.x(...)` call with `Rel(sql, dialect).input("src", src).bind("start", start)`; the
runtime (`brikk-sql-runtime`) composes the `Rel` graph into a CTE chain and renders it in the
target dialect. Nothing runs at construction.

Design docs: [virtual pipelines wiring](virtual-pipelines-wiring.md) (surface + wiring, current),
[FIR refinement and generation](RESEARCH-fir-refinement-and-generation.md) (why option C, what was verified against
2.4.0), [compiler-plugin learnings](sql-compiler-plugin-learnings.md) (history; §10 describes the abandoned
`|>`-headless shorthand).

## 2. Map of the code

Current [compiler source](../brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/),
[compiler tests](../brikk-engine/brikk-engine-kotlin-compiler-plugin/test/),
[runtime](../brikk-engine/brikk-engine-kotlin/),
[smoke consumer](../brikk-engine/brikk-engine-kotlin-smoke/), and
[tooling](../brikk-engine/brikk-engine-kotlin-tooling/):

```
brikk-engine/brikk-engine-kotlin-compiler-plugin/src/dev.brikk.house.sql.compiler/
  BrikkSqlCompilerPluginRegistrar.kt   entry point (META-INF/services); registers FIR + IR
  BrikkSqlOptions.kt                   -P options: schema, schemaDialect, defaultSchema, debug
  BrikkSqlNames.kt                     ClassIds/FqNames of runtime types; XyzOut naming
  analysis/                            PURE - no FIR/IR types; unit-testable
    SqlAnalysis.kt      RawFunction -> FunctionAnalysis via SqlAnalyzer; PluginGuard (see §4)
    SqlTemplate.kt      pieces of a $-template: Text | Slot | Bind | Const -> rendered SQL
    SqlLiteralText.kt   reads Sql.x(...) out of *source text* (IDE lazy-body fallback)
    TypeMap.kt          SQL type <-> Kotlin ClassId
  fir/
    BrikkSqlSession.kt            per-FirSession state: catalog load, function index, analyses cache
    RawFir.kt                     syntactic readers over raw (unresolved) FIR
    SqlTemplateFir.kt             FirStringConcatenationCall -> SqlTemplate (raw by name / resolved by symbol)
    ShapeDeclarationGenerator.kt  generates XyzOut interfaces + properties (+ ctor for local shapes)
    BrikkSqlCallRefinement.kt     retypes Sql.x() calls and generic-pipe calls; builds local shape classes
    BrikkSqlFirExtensionRegistrar.kt  checkers: SqlLiteralCallChecker, BrikkSqlFunctionChecker
    BrikkSqlDiagnostics.kt        all [BRIKK_SQL] diagnostics
    CompilerCompat.kt             reflection shims for 2.4.10 vs 2.4.20 API differences
  ir/BrikkSqlIrGenerationExtension.kt   the rewrite to Rel(...).input(...).bind(...)
brikk-engine/brikk-engine-kotlin-compiler-plugin/test/  kctfork tests + unit tests
brikk-engine/brikk-engine-kotlin/                      Rel, Shape, Partial, annotations, Sql
brikk-engine/brikk-engine-kotlin-smoke/                real Toolchain -Xplugin consumer
brikk-engine/brikk-engine-kotlin-tooling/              assemblePluginJar / publishKefsRepo
brikk-sql/brikk-sql/src/dev.brikk.house.sql/shape/      SqlFragment, DdlCatalog, shape analysis
```

The pipeline for one `@BrikkSql fun`: `RawFir.rawFunction` (signature + template, no
resolution) -> `SqlAnalyzer.analyze` (brikk-sql `SqlFragment.outputShape` with slots bound to
the declared input shapes) -> `FunctionAnalysis` cached in `BrikkSqlSession.analyses` ->
consumed by the generator (class + properties), the refinement (return types), the checker
(diagnostics) and, independently, mirrored by the IR rewrite.

## 3. Two hard constraints that shape everything

**(a) Generation runs before resolution.** `FirDeclarationGenerationExtension` callbacks fire
at `ANNOTATIONS_FOR_PLUGINS`/`IMPORTS`; bodies and types are raw. Everything the analyzer needs
must be recoverable syntactically: `FirUserTypeRef` qualifiers (short names only), literal
strings, callee names. Hence `RawFir`, hence short-name matching for traits and `Rel`, hence
`TemplateScope.classify` by *name* in `SqlTemplateFir`. Review question: is any path reading
resolved information from a raw declaration? (`resolvedBoundsSafe` is wrapped in try/catch for
this reason.)

**(b) The plugin runs inside the IDE on a *different* compiler build.** KEFS loads the jar
(compiled against 2.4.10) into IntelliJ's Kotlin plugin (2.4.20-ij262-34). Consequences:
- API skew is a *runtime* `LinkageError`, not a compile error. `CompilerCompat` shims the two
  found so far (`KtFakeSourceElementKind.PluginGenerated` object->sealed class;
  `FirResolvedQualifier.classId` removed). A bytecode scan against 2.4.20-RC3 found no others.
- The IDE builds FIR lazily. A callee in another file has a `FirLazyBlock` body that throws on
  access; forcing its resolution mid-resolution is forbidden. `RawFir.sqlTemplateOf` reads the
  declaration's *source text* instead (`SqlLiteralText`). PSI is not used: `com.intellij.*` is
  relocated in the embeddable compiler, so PSI signatures would not link in the IDE.
- Short-lived IDE sessions (in-memory/dangling files) can have `FirFile.sourceFile == null` and
  an empty predicate-based provider. `BrikkSqlSession` has several fallbacks for this
  (process-wide schema-path memo, package-file anchors, `XyzOut -> fun xyz` by symbol provider,
  raw-annotation recognition). These were each added after a specific `idea.log` observation;
  the commit messages of `f063a26` and `34a2990` describe the symptoms.
- The IDE cwd is not the project root. A relative `schema=` path is resolved against the
  containing source file's ancestors (`resolveSchemaFile`).

## 4. "The plugin never throws" - the boundary

Every extension entry point is wrapped: `BrikkSqlCallRefinement.intercept`,
`ShapeDeclarationGenerator.generateTopLevelClassLikeDeclaration`, `BrikkSqlFunctionChecker.check`,
`BrikkSqlSession.analysisOf`, `SqlAnalyzer.analyze`. Failures become `FunctionAnalysis.error`
(reported by the checker as `SQL_ANALYSIS_FAILED`) or "no refinement" (`null`). Rationale: in
the IDE an exception is a resolve failure of the enclosing declaration, re-raised from every
highlighting pass on every keystroke.

`PluginGuard` (in `SqlAnalysis.kt`) is the policy: catches `Exception` and `LinkageError`,
rethrows cancellation (`ProcessCanceledException` matched by name - it is not on the compiler
classpath) and anything else. Every suppressed failure and every silent give-up is logged
**once per distinct message** to stderr, tagged with the jar's build stamp
(`META-INF/brikk-sql-compiler-plugin.build`, written by `assemblePluginJar`). In the IDE this
lands in `idea.log`; grep `brikk-sql compiler plugin \[`.

The build-stamp resource above is the historical name. After relocation the
resource is `META-INF/brikk-engine-kotlin-compiler-plugin.build`; the package and
log prefix remain unchanged.

Review questions: is any catch too broad (an `Error` that should propagate)? Is any
`return null` in `interceptGenericPipe` missing a `giveUp(...)` note? Could a caught failure
leave `BrikkSqlSession` caches in a state that pins a wrong result (see the
`loadedCatalog`/`analyzerCache`/`analyses` pinning rules - they deliberately do not pin an
analysis that failed only because no anchor file was available yet)?

## 5. Surface syntax decisions (recent, worth challenging)

- **Explicit slots, no implied source** (`e5ff388`..`34a2990`): every `Rel` parameter must
  appear as `FROM name()` / `JOIN name() ON`; every such call must name a `Rel` parameter.
  Both directions are errors. The old `|>`-headless form with an implicit `__src` slot is gone.
  A `Rel` parameter named like a dialect function (`now`) gets a rename hint; named like a
  catalog table (`events` vs `public.events`) works because `bindSlots` qualifies the bound
  reference with the synthetic `__slots` db (brikk-sql `SqlFragment.kt`).
- **Kotlin template entries** (`f1cc6f0`), Terpal-style (parts and references kept apart,
  never a spliced string):

  | `$x` refers to                    | becomes                          |
  |-----------------------------------|----------------------------------|
  | `Rel` param, written `$x()`       | `x()` slot                       |
  | other param / local val / property| `:x` bind + `.bind("x", x)`      |
  | `const val` (top-level, same pkg or imported) | literal value spliced as text |
  | anything else in `${...}`         | `SQL_BAD_INTERPOLATION` on the entry |

  `$x` without `()` for a Rel is an error. Plain `:x` / `x()` text still works and renders
  identically (tested). Block bodies may declare vals before the `Sql` call.
  Kotlin only treats `$ident` / `${` as entries, so `'$.user_id'`, `$$`, `$1` need no escaping.
- **`SQL_UNUSED_PARAM` is an error** (not a warning): a scalar parameter the SQL never
  references. K2 has no `UNUSED_PARAMETER`; the IDE inspection cannot see `:name` text.
- Deferred on purpose: `${expr}` support (needs a placeholder design), companion/object consts
  (currently bind), list expansion for `IN`.

## 6. Build and dev loop

Kotlin Toolchain 0.12.0, Kotlin 2.4.10 pinned in every module. No Gradle, no Python.
The commands below use current module/artifact paths; the test count is historical.
On a clean checkout, assemble the JAR before compiling the smoke consumer.

```
./kotlin do assemblePluginJar     # build/plugin/brikk-engine-kotlin-compiler-plugin-<kotlin>-<lib>.jar (smoke -Xplugin)
./kotlin check                    # everything (727 tests at d9d3970)
./kotlin do publishKefsRepo       # build/repo Maven layout for KEFS; then "KEFS: Update Plugins" in IDEA
```

`brikk-engine-kotlin-tooling` reads the module jar and resolved runtime classpath from the build
graph (`${module.jar}`, `${module.runtimeClasspath}`), so bundled dependency versions cannot
drift. Merge is not relocation - fine in-repo, not for publishing (KEFS requires relocation,
[KEFS plugin authors guide](vendor/kefs/PLUGIN_AUTHORS.md)). `ideKotlinVersion` (the IDE's compiler build) and
`libVersion` live in [brikk-engine/brikk-engine-kotlin-compiler-plugin/module.yaml](../brikk-engine/brikk-engine-kotlin-compiler-plugin/module.yaml)
under `plugins.brikk-engine-kotlin-tooling`. The local KEFS coordinate is now
`dev.brikk.house:brikk-engine-kotlin-compiler-plugin:<ide>-<lib>`; update existing
bundles to that artifact ID. The compiler ID in `-P` options stays
`dev.brikk.house.sql.compiler`.

[publish-targets.module-template.yaml](../publish-targets.module-template.yaml) exists because Toolchain 0.12 validates the publish
repository id against *every* module before filtering on `publishing.enabled`; non-published
modules apply it so `./kotlin publish <repo>` does not abort.

Version strings that must agree (hand-maintained): `settings.kotlin.version` in each module,
`kotlin-compiler-embeddable` in the plugin module, and the `-Xplugin=` path in
[brikk-engine/brikk-engine-kotlin-smoke/module.yaml](../brikk-engine/brikk-engine-kotlin-smoke/module.yaml)
(`freeCompilerArgs` cannot reference task outputs). Toolchain module names come
from leaf directories, with no `name` override. Private `brikk-engine/dogfood/`
is not included in the public manifest; see the [local registration procedure](../brikk-engine/README.md#private-consumer).

## 7. Tests - what is and is not covered

- `BrikkSqlPluginTest` (kctfork, in-process K2): the three-step demo pipeline end-to-end
  (types, generated interfaces, rendered SQL), two-input JOIN pipe, every diagnostic, `$`
  template forms incl. locals/const/rejection position, schema-file resolution with a foreign
  cwd, unreadable schema.
- `BrikkSqlCompilerPluginRegistrarTest`: registration against an *empty* `CompilerConfiguration`
  (what the IDE passes), option parsing errors.
- `SqlLiteralTextTest`: the textual fallback.
- `SmokeTest`: the real toolchain path, dialect-neutral assertions (demo.kt is on Doris for
  IDE pipe-syntax support).
- **Not covered by tests**: anything IDE-specific - lazy bodies, empty predicate index, missing
  source paths, 2.4.20 linkage. These are verified by watching `idea.log` after
  `publishKefsRepo`. A reviewer cannot reproduce them from the CLI; the notes in
  `~/.kefs/<ide-version>/reports/` and `idea.log` are the evidence.

## 8. Where I would look hardest

1. `BrikkSqlCallRefinement.buildLocalShapeClass` / `transform`: constructs FIR by hand (local
   abstract class with fake source, `FirDeclarationOrigin.Plugin`), mirrors the compiler's
   plugin-sandbox `DataFrameLikeCallsRefinementExtension`. Source offsets and the
   `pending`/`localsByName` maps are the fragile parts.
2. `BrikkSqlSession` caching and the IDE fallbacks: correctness under sessions that come and
   go; whether anything session-scoped leaked into the process-wide `RESOLVED_SCHEMAS`.
3. `SqlTemplateFir.classifyEntry` raw path: a `$name` that is *not* a param/local/const is
   classified as a bind by name. Is that the right default, or should unknown names be
   rejected until resolved?
4. `SqlFragment.bindSlots` `__slots` qualification and the empty-slot skip in `buildSchema`:
   both are brikk-sql changes made for the plugin; check they do not alter behaviour for
   existing brikk-sql callers (`DdlCatalogTest` passes, but that is thin).
5. IR: `sqlTemplate()` must stay exactly parallel to `SqlTemplateFir.read`; there is no shared
   code between them. Any divergence produces SQL at runtime that differs from what was
   type-checked.
6. `CompilerCompat` will become unnecessary once Kotlin 2.4.20 ships and the project bumps;
   until then any new `NoSuchFieldError`/`NoSuchMethodError` in `idea.log` belongs there.
