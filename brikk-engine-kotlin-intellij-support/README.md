# Brikk Kotlin IntelliJ support

Shared Brikk-specific context, interpolation and relation-shape discovery for the
DuckDB, Doris and Trino SQL plugins. This is a Java 21 library, not an IntelliJ plugin or
a compiler plugin. It registers no extensions and has no dialect-plugin dependencies.

This module is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)
and [NOTICE](NOTICE) here), not the Business Source License that covers the
rest of the repository, so the Apache-2.0 dialect plugins can embed it.

Development coordinate:

```text
dev.brikk.house:brikk-engine-kotlin-intellij-support:0.3.0-SNAPSHOT
```

Trino support requires helper `0.2.0` or newer. It recognizes `Sql.trino` as
`BrikkDialect.TRINO`, with language ID `TrinoSQL`. Released helper `0.1.0` recognizes
only DuckDB and Doris. The runtime entry point lives in `brikk-engine-kotlin`, not
in this helper artifact.

From `0.3.0`, `BrikkDialect` is a class with registry entries, not an enum:
`DUCKDB`, `DORIS`, `TRINO` and `CLICKHOUSE` (`ClickHouseSQL`). A runtime `Sql` member
that declares `@BrikkSqlDialect` is Brikk even without an entry (`Sql.postgres`): its
dialect has the language its SQL parameter's `@Language` declares, `BrikkSQL` by
default, and `registered == false`. Compare dialects with `==` and give `when` an
`else` branch. `BrikkDialect.of(sqlName)` returns the registered entry.

`ColumnContract.nullableOrNull()`, `originLabel()` (`brikk column`, or `relation
contract` for a relation-level fallback) and `kotlinTypeName` replace the plugins'
copies of those projections.

## Consume from a dialect plugin

```kotlin
repositories { mavenLocal(); mavenCentral() }

dependencies {
    implementation("dev.brikk.house:brikk-engine-kotlin-intellij-support:0.3.0-SNAPSHOT") {
        // IntelliJ supplies Kotlin. Do not bundle another stdlib in the plugin ZIP.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}
```

From `0.3.0` the helper depends on released brikk-sql core, `dev.brikk.house:brikk-sql-jvm:0.20.0`
from Maven Central, for its plain relation-facts
data. It does not depend on brikk-sql support (owner decision 24), so helper, support
and core release independently; each plugin adapts the helper's facts to support.

Same-version local rebuilds can require `--refresh-dependencies`. Remove
`mavenLocal()` or restrict its content when switching to a released Central artifact.

Call the Kotlin-aware classes only from the plugin's existing optional Kotlin
descriptor. Native SQL and DataGrip without Kotlin must not initialize these
classes. The copied contract types do not depend on Kotlin IDE APIs.

## Boundary

The helper owns:

- Exact resolved `Sql.duckdb`/`Sql.doris`/`Sql.trino`/`Sql.clickhouse` (and any other
  `@BrikkSqlDialect` runtime entry) and `@BrikkSql` recognition, including aliases.
- Stdlib trim recognition, without claiming lookalike extension functions.
- Interpolation roles, supported source/binary constants, pending resolution and
  authored host ranges. Scalar properties/getters are never evaluated.
- Effective `Rel<T>` contracts, inherited properties, type bounds, Kotlin types,
  nullability, full/partial knowledge and supported navigation targets.
- Their mapping to core's relation facts and Brikk labels (`BrikkRelationFacts`),
  the recognized/pending/not-Brikk decision (`BrikkHosts`) and the inspection-only rule
  (`BrikkInspectionView`).

The dialect plugins retain their extension registrations, injection shreds and
trimming mechanics, SQL parsing, bind token PSI, physical datasources, slot aliases,
joins, PIPE-stage propagation, SQL inspections and execution guards. Do not move
general SQL helpers into this module.

Each plugin may embed its own copy of this library. Do not exchange its objects
across isolated plugin classloaders or register competing global helper services.
Project host/injected-file keys remain owned by the dialect adapters.

## API

```kotlin
val result = BrikkKotlinContext.inspect(host, includeShapes = false)
when (result) {
    is ContextResult.Available -> {
        val context = result.context
        // Claim only the injection for context.dialect.languageId.
        // Apply context.entries using the adapter's existing injection machinery.
        // Null replacement means keep the literal host range.
    }
    is ContextResult.Unavailable -> {
        // Resolution is pending. Do not guess names or cache a negative result.
    }
    ContextResult.NotBrikk -> {
        // Withdraw this adapter's old metadata and leave native injection alone.
    }
}
```

### Recognized, pending or not Brikk

Injection contributors should decide through `BrikkHosts.state(host)`, which applies the
pending policy over `inspect` and remembers each host's last recognized context:

```kotlin
when (val state = BrikkHosts.state(host)) {
    is BrikkHostState.Recognized -> // inject state.context.entries
    is BrikkHostState.Pending -> {
        // Brikk and inspection-only. Claim the host and retry when analysis is ready.
        // state.projection != null: recognized before; inject its entries, every
        //   interpolation PENDING with a placeholder (no remembered role is reused).
        // state.projection == null: never recognized; claim without injecting.
    }
    BrikkHostState.NotBrikk -> // withdraw; leave native injection alone
}
```

The policy is split by consequence. Injection claims err toward not claiming: while
Kotlin cannot resolve, a never-recognized host is pending only on strong evidence. Its
callee must be the runtime's `Sql.x` by explicit import or alias of `Sql`/`Sql.x`, or
fully qualified, and its annotation the runtime's `@BrikkSql` the same way, with no
lexical binding of those names in scope (parameters, locals, members, file
declarations). Star imports (a same-package or other star-imported `Sql` could win),
typealiases, competing imports and shadowed names are no claim: `NotBrikk` then means
no decision, and native injection stays as the platform makes it. In smart mode the
annotation must resolve, and an unresolved callee is pending only when its syntax is
strong or Kotlin proves its receiver is the runtime's `Sql` (also through a typealias).
A remembered projection, or a `NotBrikk` Kotlin decided, is reused while indexing only
under the same deciding syntax: annotations, callee and receiver, trims, imports,
top-level names, typealiases with their targets, and lexical bindings named like the
receiver, callee, annotations, `Sql` or `BrikkSql`. Removing `@BrikkSql`, switching
`Sql.duckdb` to `Sql.trino`, adding a parameter named `Sql` or retargeting a typealias
re-decides. SQL text edits keep the projection. `state.dialect` is the recognized or remembered
dialect, and for a never-recognized pending host a hint from its callee (`Sql.trino`,
an import alias of it), null when the callee names no registered dialect. Prefer the
native injection's language when the hint is null. `BrikkHosts.forget(host)` drops the
remembered context for a host the plugin withdraws.

### Relation facts

`BrikkRelationFacts` answers with core data (`dev.brikk.house.sql.scope.RelationFacts`)
in its own `BrikkRelationFactsResult` (`Available(facts, revision)` or `Pending`), and
navigates by provenance to platform PSI. Support has no function-backed provider, so each
plugin keeps this adapter in its optional Kotlin descriptor path and adds one stable
instance to its `RelationScopeProfile.providers`:

```kotlin
class KotlinBrikkRelationFacts : ExternalRelationFacts {
    private val brikk = BrikkRelationFacts(BrikkDialect.DUCKDB)
    override fun facts(file: PsiFile): RelationFactsResult? = when (val result = brikk.facts(file)) {
        is BrikkRelationFactsResult.Available -> RelationFactsResult.Available(result.facts, result.revision)
        is BrikkRelationFactsResult.Pending -> RelationFactsResult.Pending(result.reason)
        null -> null
    }
    override fun navigationTarget(file: PsiFile, provenance: String) = brikk.navigationTarget(file, provenance)
    override val labels = BrikkRelationFacts.LABELS.let {
        RelationColumnLabels(declared = it.declared, declaredKind = SqlLookupKind.BRIKK_COLUMN,
            contract = it.contract, contractKind = SqlLookupKind.BRIKK_COLUMN)
    }
}
```

- Each `Rel` parameter of the host's function is a slot fact keyed by its name (`src()`).
  `Shape` contracts are `FULL`, `Partial` ones and bounds `PARTIAL`, unsupported ones
  `UNKNOWN` with no columns.
- `sqlType` is set only when the contract knows it (never today: Kotlin types prove no
  SQL type); `displayType` is the Kotlin simple type name; nullability is copied.
- A column with a Kotlin property is `DECLARED`, its provenance navigating to the
  property; a column known only through its relation (generated) is `RELATION_CONTRACT`,
  navigating like the relation to the `Rel` parameter. `navigationTarget` reads the
  current facts (cached while PSI is unchanged, so find usages asking per candidate costs
  a lookup and a smart pointer) and only their targets: a retyped `Rel` navigates to the
  new contract, a renamed column's old provenance and pending facts have no target. The
  first call after an edit computes the facts, as the next analysis would.
- Facts are `Pending` while the host is pending (`BrikkHosts.state`) or any contract is
  unavailable; pending results are never cached. Available facts are cached per host
  until PSI or roots change. The revision is a hash of the facts, so it survives
  reparsed hosts and unrelated edits and changes when a contract does.
- `BrikkRelationFacts.LABELS` is plain data: `brikk column (<rel>)` and `relation contract
  (<rel>)`, and `brikkColumnStyle` (the bricks icon, support's `SqlLookupKind.BRIKK_COLUMN`). Support keeps a column's
  origin and provenance through lineage-known renames, so renamed Kotlin columns keep the
  label. Computed and physical columns use support's defaults.
- The constructor's dialect restricts facts to that dialect's hosts; null serves any.
  `BrikkRelationFacts.factsOf(relations)` maps a context's relations without a file.

### Pending causes and actions

Every pending result carries a typed `BrikkPendingCause` beside its text (on
`ContextResult.Unavailable`, `ShapeResult.Unavailable`, `BrikkHostState.Pending` and
`BrikkRelationFactsResult.Pending`): `INDEXING`, `ANALYSIS_PROHIBITED` (a write action
such as a rename), `UNRESOLVED`, `RUNTIME_ENTRY_MISSING`, `GENERATED_SHAPES` or
`COMPILER_PLUGIN_MISSING`. Tooltips switch on it instead of parsing messages.

`BrikkHosts.actionNeeded(file)` (and `actionsNeeded(file)`, in priority order) returns
what the user must fix, as plain `BrikkAction` data for a banner: `kind`, `message`,
`fixText`, `docsUrl`, `buildFileUrl` (the nearest `module.yaml`, Gradle or Maven build
file) and, for a dialect, `dialect`. Only reliably detected, user-fixable conditions:

- `RUNTIME_OUTDATED`: Kotlin proves the receiver is the runtime's `Sql` but the called
  entry point does not exist.
- `COMPILER_PLUGIN_MISSING`: a relation's row type does not resolve and the IDE says the
  module registers no Brikk FIR extension. When the IDE cannot say, it is never reported. Detected while reading shapes (relation facts or
  `inspect(includeShapes = true)`), not by recognition alone.
- `DIALECT_PLUGIN_MISSING`: no installed plugin provides the language of a recognized
  host's dialect. A plugin may show it only for dialects it cares about.

Indexing and other pending states are never actions. The answer reads what analysis
already recorded on the file's hosts: no analysis, cached until PSI or a record changes,
safe in dumb mode.

No helper entry point runs Kotlin analysis while it is prohibited. During a write action
`BrikkRelationFacts` serves still-valid cached facts, else the last facts computed for
the host, else `Pending(ANALYSIS_PROHIBITED)`; nothing served that way is cached as
fresh, and navigation targets (smart pointers) follow a rename.

### Inspection-only SQL

Execution guards share one rule, `BrikkInspectionView.isInspectionOnly(file, offset)`,
which errs toward Brikk: a false positive only blocks Execute until indexing ends.
`file` is an injected fragment (judged by its host) or a Kotlin file (judged by the
string at `offset`, the selection start or else the caret). The string and every
string template enclosing it are checked; any one blocks. A template blocks when it is a
recognized Brikk host of any dialect (including `Sql.postgres` and other plugins'
dialects) or pending, and, while Kotlin cannot resolve, on weak evidence: a callee named
`Sql.x` or reached through an import, alias or in-file typealias chain of an `Sql`,
`Sql.x` or any explicitly imported name, in a function whose annotation is named
`BrikkSql` or reached the same way. Star imports count and shadowing is ignored; a
`NotBrikk` that syntax decided while Kotlin could not resolve is never trusted.
Parentheses around the argument count as the Brikk host.
`isInspectionOnly(host)` judges a host directly. It replaces each plugin's own-profile
check and the Transpiler's `KotlinInspectionHostGuard` body.

Use `includeShapes = false` on hot injection-recognition paths that do not need
columns. The default includes `context.relations`, keyed by the enclosing
function's parameter names. Dedicated calls are:

```kotlin
BrikkRelationShapes.ofParameter(parameter)
BrikkRelationShapes.ofExpression(expression)
```

The expression operation reads refined local shape types without approximating
them to public supertypes. Inside a generic function, inputs come from declared
bounds, not a union of its callers' shapes.

`TemplateEntry.range` uses UTF-16 offsets relative to the host string, including
the opening quote delimiter. `$src` referring to a `Rel` parameter is a relation
slot and must be followed by `()`. Ordinary values become `:name`; literal SQL
constants become text. Pending and unsupported entries have distinct roles and
nonexecutable inspection placeholders; `TemplateEntry.placeholder` marks them, for
support's `HostSqlEntry(placeholder = true)`. Preserve the authored Kotlin references.

`BrikkContext.trimSteps` lists the trims in call order with their margin prefix
(`BrikkTrim("trimMargin", "#")` for `trimMargin("#")`, `|` by default, null for
`trimIndent`), for support's `HostSqlTrim.Margin(prefix)`. Only a literal prefix is a
supported trim. `trims` keeps the bare names.

Read navigation locations inside a normal IDE read action. They are current Kotlin
source locations backed by smart pointers. A generated column can instead have
`RELATION_FALLBACK` navigation; this is not a fabricated SQL column definition.

## Uncertainty and limits

`ShapeResult` distinguishes available, unavailable, unsupported and non-relation
types. Bare `Rel<Shape>`, `Rel<Partial>` and star projections have no finite
inventory; they are partial with no known columns. A missing property on a partial
contract is not proof that the runtime database row lacks that column.

An empty compiler-generated member scope is conservatively unavailable in v1.
The public symbol view does not distinguish every legitimate zero-column generated
row from incomplete initial generation; the helper does not turn that uncertainty
into a full empty inventory.

Kotlin properties expose Kotlin type/nullability guarantees, not exact SQL types.
`String` can represent text, JSON or UUID. `BigDecimal` does not recover precision
or scale. Nullable getters can represent conservative unknown SQL nullability.
`exactSqlType` and `quotedSqlIdentity` are deliberately absent, and enumeration
order is not a SQL ordinal guarantee.

The compiler through KEFS supplies generated Kotlin symbols. The helper does not
reflect into its classloader, read private FIR state, load SQL drafts, run a
database query or build runtime `Rel` objects. It does not make injected SQL
executable. Keep both plugins' existing execution guards.

Use non-keyword relation parameter names such as `src`, `events` or `customers`.
With the `brikk-sql` 0.18.0 parser, a slot named `rows()` can fail in a
`SELECT ... FROM` clause because `ROWS` is a SQL keyword. Rename the Kotlin
parameter and its template reference rather than relying on an editor-only
parser workaround. No shared parser change is required for this helper release.

## Non-stable API use

The helper uses one non-stable API family: the Analysis API platform interface
(`@KaPlatformInterface`, opted in), all of it in `BrikkPlatformApi`:

- `KotlinCompilerPluginsProvider.getRegisteredExtensions` with
  `FirExtensionRegistrarAdapter`, to tell whether the Brikk compiler plugin is active.
- `KaAnalysisPermissionChecker.isAnalysisAllowed`, to avoid analysis in write actions.

Each call is guarded: a missing or changed API, a linkage error or any exception on an
IDE version makes the answer unknown (cancellation still propagates). Unknown plugin
state never reports `COMPILER_PLUGIN_MISSING`; unknown permission falls back to "no
write action in progress". Everything else uses the stable Analysis API and platform.

## Build and publish locally

From the repository root with Kotlin Toolchain 0.13.0:

```sh
./kotlin build -m brikk-engine-kotlin-intellij-support
./kotlin publish mavenLocal -m brikk-engine-kotlin-intellij-support
./kotlin do verifyLocalIntellijPublication -m brikk-engine-kotlin-intellij-support
```

Compilation targets the IDEA 261 API used by both peer plugins. A build-only
Toolchain plugin prepares pinned, SHA-256-verified JetBrains API binaries under
`build/intellij-api`. An opaque generated revision orders compilation after
preparation and invalidates it when that API changes. No SDK source stubs are used.

The prepared SDK is a compiler classpath input, not a Maven dependency. Toolchain
0.13 emits compile-only Maven dependencies as `provided` POM entries; no local SDK
coordinate should be published here. The distributed JAR contains only helper
classes and notices. Its POM/Gradle metadata has no IntelliJ or compiler dependency;
its only library dependency is brikk-sql core (a release must not depend on a snapshot). Because the module's `-classpath`
replaces the toolchain's, the preparation task also merges the module's resolved Maven
dependencies into `build/intellij-api/helper-dependencies.jar` for compilation, and the
IDE fixtures run with them on the classpath.
The local verification command also builds/runs an independent Maven Local consumer.

The binary JAR contains the module's Apache LICENSE and NOTICE under `META-INF`.
Toolchain 0.13's sources JAR includes static source roots, not generated resources,
so matching copies live in `src/META-INF` and appear under `main/META-INF` there.
If the authoritative module LICENSE or NOTICE changes, update those source copies
too. A build-time check rejects missing/stale copies, and publication verification
compares both JARs byte-for-byte against the authoritative files. Neither archive
is modified after native checksum/signature generation.

## Real IDE checks

```sh
./kotlin check intellijSupport -m brikk-engine-kotlin-intellij-support
```

On Linux x64, the check provisions the pinned IDEA 2026.1.3 SDK if none is supplied.
An existing SDK can be selected without editing the build:

```sh
BRIKK_INTELLIJ_SDK=/path/to/idea-2026.1.3 \
  ./kotlin check intellijSupport -m brikk-engine-kotlin-intellij-support
```

For newer runtime checks, set `BRIKK_INTELLIJ_COMPILE_SDK` to that same 261 SDK.
Fixtures compile against 261 and run against `BRIKK_INTELLIJ_SDK`. Configuration
and system caches live in separate per-build test directories, not the user's profile.

The source-generated named/local shape lane runs only when the actual IDE compiler
matches the reviewed `2.4.20-ij262-34` artifact. Other runtime checks omit those
cases explicitly; they do not relabel the compiler artifact or fake generated rows.
The fixture provider substitutes the genuine plugin JAR in the IDE's compiler-plugin
cache, without needing a user's KEFS installation.

Fixture coverage includes cold analysis, every registered dialect, an unregistered
runtime dialect, aliases, source and binary
constants, pending/unsupported distinctions, inherited bounds, full/partial shapes,
Kotlin property navigation and trait edits without SQL edits. Compiler parity for
binary constants is also checked by the real compiler-plugin test suite.

Local verification on 2026-10-13 (helper `0.3.0-SNAPSHOT` against brikk-sql core
`0.20.0-SNAPSHOT` from Maven Local):

| Runtime build | Fixtures | Compiler-generated shape cases |
| --- | --- | --- |
| IDEA `261.25134.95` | 100 passed | Not enabled for this compiler |
| IDEA `262.9437.185` | 104 passed | Named output, refined local shape, Trino output and active-plugin detection passed on `2.4.20-ij262-34` |
| IDEA `263.5701.42` | 100 passed | Not enabled for this compiler (`2.5.0-dev-7866`) |

The fixtures cover every registered dialect including ClickHouse, an unregistered
runtime dialect, the pending policy (projection, evidence, validated negatives,
ownership edits), the inspection-view rule (nested literals, parentheses, non-Kotlin
hosts, the strong/weak evidence split and typealias chains) and the relation facts
(mapping, revision, current-only navigation). Earlier,
on 2026-10-10, helper `0.2.0` passed 15/17/15 fixtures on IDEA 261/262/263.

The helper now depends on released core `0.20.0` from Maven Central. Until the owner
publishes brikk-sql core `0.20.0`, the helper (and the Test workflow) cannot resolve it
and does not build; the results above were against the same core source as a snapshot.

Trino `0.2.0-SNAPSHOT` validation passed 17 fixtures on IDEA `261.25134.95` and
20 on `262.9437.185`, including a genuine compiler-generated Trino output shape.
All 184 normal tests, 11 exact IDE-compiler fixtures and the independent Maven
Local consumer passed. This snapshot was published to Maven Local, not Central.

All fixture classes were compiled against the 261 baseline. Machine-readable
results and logs are under the verification task's per-build output directory.
These are local fixture results, not a claim that the changed GitHub workflow
has run remotely or that the peer plugins have already migrated to the helper.

## Central release

Only this module applies the publication template. Runtime, compiler artifacts,
build plugins and private consumers remain unpublished.

Prerequisite for `0.3.0`: brikk-sql core `dev.brikk.house:brikk-sql-jvm:0.20.0` must be on
Maven Central first; the helper resolves it from there and its release POM exports it.

```sh
# Unsigned local candidate: release version 0.3.0 into Maven Local, resolving the
# core 0.20.0 candidate from Maven Local. No credentials, nothing uploaded.
./publish-release.sh 0.3.0 --local
# Signed Central upload, automatic publication after validation:
./publish-release.sh 0.3.0 --auto
# Signed Central upload, manual Portal approval:
./publish-release.sh 0.3.0
```

The script follows the peer SQL repo's native Toolchain publishing process. It
validates a non-SNAPSHOT SemVer and runs the release-configuration tests, then
temporarily replaces the publication template with release settings (restored on
exit): for `--local`, the release version and a Maven Local resolver for released
dependencies; otherwise the release version, Central and signing. It builds the helper
only, publishes it to Maven Local and runs `verifyLocalIntellijPublication` (POM limited
to core and the stdlib, no snapshot dependency in a release, notices, an independent
consumer), then for Central signs and uploads only this artifact. Private engine and
compiler suites are not part of the release path; the Test workflow covers them on
ordinary pushes. The Maven Local resolver exists only in the temporary local
override; the helper source resolves core from Central.

The `Release IntelliJ support` workflow runs only that scoped release with `--auto`:
push `release/<version>` or dispatch it with input `version`. Signing and Central
credentials come from the Brikk organization secrets. No push to main triggers
publication.

Required credentials are `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME`,
`KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD` and `KOTLIN_TOOLCHAIN_SIGNING_KEY`.
An encrypted key also needs `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE`. The script
accepts the older credential spellings used by the peer repo. Never commit keys
or credentials. The release workflow requires those organization secrets
to be available to this repository.

## Migration guide

DuckDB's `BrikkDuckdbKotlinContext` and Doris's `BrikkDorisTemplate` should delegate
recognition and entry classification to this API. Keep their tested injection
performers for now; Doris delegates range handling to Kotlin while DuckDB tracks
rendered character origins. That mechanical difference is not a reason to keep
two Brikk semantic implementations.

Project the returned context into each plugin's existing editing profile and
diagnostic UI. Use the relation map as statement-local slot schemas, then let the
dialect resolver own aliases, outer-join nullability and PIPE stage scopes.

The richer contract is documented in
[IDE-relation-shape-contract.md](../docs/IDE-relation-shape-contract.md).
