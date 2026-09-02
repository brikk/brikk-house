# RESEARCH: FIR declaration generation + call refinement for inferred shape types

Status: the option-C end-to-end **works** (Sep 2026, Kotlin 2.4.0): see
`brikk-sql-compiler-plugin/test/.../BrikkSqlPluginTest.kt` (kctfork) and the
`brikk-sql-plugin-smoke` module (real toolchain). Facts marked **[verified]** were confirmed
against the 2.4.0 embeddable compiler while building it. Section "Verified in practice" lists
what the build taught us beyond the sources.

## The two extensions and why both

| | `FirDeclarationGenerationExtension` | `FirFunctionCallRefinementExtension` |
|---|---|---|
| stability | stable plugin API | `@FirExtensionApiInternals`, doc says "highly unstable and not recommended" **[verified, source]** |
| when | SUPERTYPES / STATUS phases (pre-body-resolution) | body resolve, per call: `intercept` after args resolved, `transform` at call completion **[verified, KDoc]** |
| can create | new **non-local** classes/members, by ClassId, provider-style | only **local** classes ("cannot create new top level declarations") **[verified, KDoc]** |
| can change | nothing existing (only add) | the *type of one call expression* |

Option C = "return type omitted" needs the type of `Sql.postgres(...)` changed → refinement is
mandatory. But a *local* class as the inferred return type of a top-level function is at
best approximated away, at worst an error. So: **generate the named class early (generation),
reference it from the refined call type (refinement)**. Refinement only creates local classes
where the type is genuinely call-site specific (generic trait pipes).

## Refinement mechanics (from `plugins/plugin-sandbox/.../DataFrameLikeCallsRefinementExtension.kt`) [verified, source]

- `intercept(callInfo, symbol): CallReturnType?` — return `null` to ignore. Non-null → the
  compiler *copies* the callee function with the new return type and completes the call
  against the copy. `CallReturnType(typeRef, callback)`; `callback(copiedSymbol)` is where you
  stash per-call data (sandbox uses a `FirExtensionSessionComponent` cache keyed by the copied
  symbol).
- `transform(call, originalSymbol): FirFunctionCall` — called at completion. Sandbox pattern:
  1. `call.transformCalleeReference` to point back at `originalSymbol` (the copy does not exist
     in FIR; backend must see the real callee);
  2. build local `FirRegularClass`es (`Visibilities.Local`, `EffectiveVisibility.Local`,
     `resolvePhase = BODY_RESOLVE`, origin `FirDeclarationOrigin.Plugin(key)`);
  3. wrap: `receiver.let { <classes as statements>; call }` via a hand-built
     `buildAnonymousFunctionExpression` + `buildFunctionCall` to `kotlin.let`, with the
     original call's dispatch/extension receivers rewired to the lambda's `it`.
- Local classes must have **distinct source elements**: use `KtFakeSourceElementKind.PluginGenerated.Custom`
  per class. **[verified, KDoc]**
- Members of the local classes are *not* built in `transform`; a `FirDeclarationGenerationExtension`
  supplies them via `getCallableNamesForClass` / `generateProperties`, recognizing the class
  through a `FirDeclarationDataKey` attribute set on it (sandbox: `callShapeData`).
- `ownsSymbol` / `anchorElement` / `restoreSymbol` exist so the IDE / lazy resolver can find the
  generated local classes again from the original call source. Implement mechanically.
- `CallInfo.containingDeclarations` gives the enclosing declarations → how we find the
  `@BrikkSql` function around a `Sql.postgres(...)` call.
- `CallInfo.arguments[i].resolvedType` is available in `intercept` (args resolved before the
  call itself per the KDoc ordering). This is how the call-site shape of `extractEvent(rel)` is
  computed from `rel : Rel<X>`.

Open: whether `transform` may return the call unchanged (callee reference restored, no `let`)
when no local declarations are needed. Try first; fall back to the `let` wrapper.

## Declaration generation constraints (from `docs/fir/fir-plugins.md` + KDoc) [verified]

- `getTopLevelClassIds()` must announce every ClassId up front; called as early as IMPORTS/
  SUPERTYPES. The predicate index (`session.predicateBasedProvider`) is built at
  `ANNOTATIONS_FOR_PLUGINS`, so `getSymbolsByPredicate(has(@BrikkSql))` works here. Must
  `registerPredicates` or the index is not guaranteed.
- `generateClassLikeDeclaration` may run at SUPERTYPES: everything we read from *other*
  declarations is **raw FIR** — `FirUserTypeRef`s, unresolved bodies. No lazy resolve in CLI mode.
- Therefore the SQL literal must be **syntactically** extractable from the raw body:
  `FirLiteralExpression(String)` optionally wrapped in `trimIndent()`/`trimMargin()` calls;
  expression body is a `FirSingleExpressionBlock` → `FirReturnExpression.result`. Same rule as
  learnings §9. Interpolation = not analyzable (checker error).
- Parameter types are raw too: `Rel<T>` is a `FirUserTypeRef` with qualifier `Rel` and a type
  argument `T`; `T`'s bound `HasPayload` is another `FirUserTypeRef`. Resolution by *short
  name* against the set of `@BrikkTrait`-annotated interfaces (predicate) — a deliberate demo
  shortcut, documented here. Trait property types likewise read by short name
  (`String`, `Long`, `Instant`, ...) through a fixed Kotlin↔SQL table.
- Generated declarations must be fully resolved: `FirResolvedDeclarationStatus`,
  `FirResolvedTypeRef`, `resolvePhase = BODY_RESOLVE`. Use the `createTopLevelClass` /
  `createMemberProperty` helpers from `org.jetbrains.kotlin.fir.plugin`.
- Generated classes are converted to IR automatically → they exist as bytecode (the e2e test
  can reflect on `EventsInRangeOut` and see the getters + `Shape`/trait supertypes).

## Shape vs Partial rule for generated output types

- `Shape` (full, closed): FROM-ful query (catalog resolves `*`), or a pipe with a SELECT/
  AGGREGATE stage (column set replaced), when the function is not generic.
- `Partial` (minimum guaranteed): generic pipe (`fun <T : Trait> f(src: Rel<T>)`): the declared
  type carries only bound-columns + added columns; the *call site* gets a refined local `Shape`
  with the real input's columns + added columns.
- Trait conformance is materialized at generation: every `@BrikkTrait` interface whose
  properties are structurally satisfied (name + mapped type) becomes a supertype of the
  generated class (§9 of the learnings doc).

## Verified in practice (2.4.0)

- **Returning the call unchanged from `transform` works** when no local declarations are needed
  (case 1, `Sql.postgres(...)` -> `Rel<FnOut>`): restore the callee reference to the original
  symbol and return `call`; the refined `resolvedType` sticks and the function's inferred
  return type becomes `Rel<EventsInRangeOut>` (checked via `Method.genericReturnType`).
- **Local classes must be `ClassKind.CLASS`** (abstract): "Interface 'X' cannot be local" is a
  frontend error. An abstract class can still implement `Shape` + the trait interfaces.
- **Local classes need a source**: declaration checkers throw "source must not be null". Use
  `call.source.fakeElement(KtFakeSourceElementKind.PluginGenerated, Custom.Initialized(start, start))`
  for the class and a plain `fakeElement(PluginGenerated)` for the wrapping lambda so the two
  are distinct. 2.4.0 has no `PluginGenerated.Custom` (that is master-only).
- **Local classes need a constructor with a body**: the backend asserts "Expected at least one
  constructor calling super" and then "Expected exactly one delegating constructor call". Fix:
  the generation extension announces `SpecialNames.INIT` + `createConstructor(isPrimary=true)`
  for those classes, and the `IrGenerationExtension` fills the empty body with
  `irDelegatingConstructorCall(Any.<init>)` + `IrInstanceInitializerCallImpl`.
- **`kotlin.run { }` wrapper** (no receiver) is simpler than the sandbox's `let`; the lambda is
  `Function0<R>`, `InlineStatus.Inline`, `EXACTLY_ONCE`.
- **Parameter type refs are already resolved** when analysis runs after TYPES: readers must
  handle `FirResolvedTypeRef` (incl. `ConeTypeParameterType` -> type-parameter name) as well as
  raw `FirUserTypeRef`.
- **Do not cache predicate results too early.** `hasPackage` is queried at IMPORTS, before the
  predicate index exists; a `lazy` that caches an empty list there silently disables the whole
  plugin. `hasPackage` returns false (generated classes live in the user's packages) and the
  symbol lookups only cache non-empty results.
- **`CheckerContext.containingDeclarations` is `List<FirBasedSymbol<*>>`** in 2.4.0 (symbols),
  while `CallInfo.containingDeclarations` is `List<FirDeclaration>`. `filterIsInstance` on the
  wrong type compiles and silently returns nothing.
- Renames vs older sources: `FirSimpleFunction` -> `FirNamedFunction`; `ConeClassLikeTypeImpl`
  lives in `fir.types.impl`; `FirClass.declarations` needs `@OptIn(DirectDeclarationsAccess)`;
  `DeclarationIrBuilder` is in `backend.common.lower`; `IrStatement` in `org.jetbrains.kotlin.ir`.
- **Diagnostics with `warning1` did not surface in kctfork's `messages`** while `error1` did
  (not investigated; debug output goes through the message collector in IR instead).

### Limitation found: local shapes cannot escape a plain helper

```kotlin
fun mid(a: Instant, b: Instant) = extractEvent(eventsInRange(a, b))   // plain fun, inferred type
fun useMid(...) = loginDaily(mid(a, b))   // error: actual 'Rel<Shape>', expected 'Rel<LoginInput>'
```

Kotlin approximates a local class escaping through an inferred return type to its first
supertype (`Shape`), dropping the traits. Same limitation as DataFrame. Options: chain inline;
make `mid` a `@BrikkSql` pipe (named, non-local output); or write an explicit type. A checker
with that hint is a good follow-up. Documented by a test.

### Demo-grade shortcuts to revisit

- Traits are resolved by **short name** from the `@BrikkTrait` predicate set; trait property
  types by short name through `TypeMap` (`String`, `Long`, `Instant`, ...). Real resolution
  needs `FirUserTypeRef` -> ClassId through the file's imports, or reading resolved refs later.
- Generated property nullability: only UNKNOWN-typed columns are nullable; output shapes do not
  surface nullability yet (`ColumnShape.nullable` is null after `outputShape`).
- `:name.path` placeholders bind on the first segment only.
- Named/reordered arguments at generic pipe call sites are matched positionally.
- Trait satisfaction ignores nullability.
- Column-reference check is a flat name check (input/catalog/alias names), not a scope-aware
  qualification.

## Runtime value

`Rel<out T : Partial>(sql, dialect)` + `.input(slot, rel)` + `.bind(name, value)`, chosen so the
IR rewrite is a chain of ordinary calls (no vararg/array construction). Headless pipes are
stored with the `FROM __src()` prefix the plugin prepends; rendering binds slot `__src` to the
CTE name of the input. Rendering = brikk-sql desugar → CTE chain → target dialect.
