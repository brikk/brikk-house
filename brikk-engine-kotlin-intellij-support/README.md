# Brikk Kotlin IntelliJ support

Shared Brikk-specific context, interpolation and relation-shape discovery for the
DuckDB and Doris SQL plugins. This is a Java 21 library, not an IntelliJ plugin or
a compiler plugin. It registers no extensions and depends on neither dialect plugin.

This module is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)
and [NOTICE](NOTICE) here), not the Business Source License that covers the
rest of the repository, so the Apache-2.0 dialect plugins can embed it.

Development coordinate:

```text
dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0-SNAPSHOT
```

## Consume from a dialect plugin

```kotlin
repositories { mavenLocal(); mavenCentral() }

dependencies {
    implementation("dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0-SNAPSHOT") {
        // IntelliJ supplies Kotlin. Do not bundle another stdlib in the plugin ZIP.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}
```

Same-version local rebuilds can require `--refresh-dependencies`. Remove
`mavenLocal()` or restrict its content when switching to a released Central artifact.

Call the Kotlin-aware classes only from the plugin's existing optional Kotlin
descriptor. Native SQL and DataGrip without Kotlin must not initialize these
classes. The copied contract types do not depend on Kotlin IDE APIs.

## Boundary

The helper owns:

- Exact resolved `Sql.duckdb`/`Sql.doris` and `@BrikkSql` recognition, including aliases.
- Stdlib trim recognition, without claiming lookalike extension functions.
- Interpolation roles, supported source/binary constants, pending resolution and
  authored host ranges. Scalar properties/getters are never evaluated.
- Effective `Rel<T>` contracts, inherited properties, type bounds, Kotlin types,
  nullability, full/partial knowledge and supported navigation targets.

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
nonexecutable inspection placeholders. Preserve the authored Kotlin references.

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
With the current `brikk-sql` 0.18.0 parser, a slot named `rows()` can fail in a
`SELECT ... FROM` clause because `ROWS` is a SQL keyword. Rename the Kotlin
parameter and its template reference rather than relying on an editor-only
parser workaround. No shared parser change is required for this helper release.

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
classes and notices. Its POM/Gradle metadata has no IntelliJ or compiler dependency.
The local verification command also builds/runs an independent Maven Local consumer.

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
matches the reviewed `2.4.20-ij262-34` artifact. Other runtime checks omit those two
cases explicitly; they do not relabel the compiler artifact or fake generated rows.
The fixture provider substitutes the genuine plugin JAR in the IDE's compiler-plugin
cache, without needing a user's KEFS installation.

Fixture coverage includes cold analysis, both dialects, aliases, source and binary
constants, pending/unsupported distinctions, inherited bounds, full/partial shapes,
Kotlin property navigation and trait edits without SQL edits. Compiler parity for
binary constants is also checked by the real compiler-plugin test suite.

Local verification on 2026-10-10:

| Runtime build | Fixtures | Compiler-generated shape cases |
| --- | --- | --- |
| IDEA `261.25134.95` | 15 passed | Not enabled for this compiler |
| IDEA `262.9437.185` | 17 passed | Named output and refined local shape passed on `2.4.20-ij262-34` |
| IDEA `263.5701.42` | 15 passed | Not enabled for this compiler |

All fixture classes were compiled against the 261 baseline. Machine-readable
results and logs are under the verification task's per-build output directory.
These are local fixture results, not a claim that the changed GitHub workflow
has run remotely or that the peer plugins have already migrated to the helper.

## Central release

Only this module applies the publication template. Runtime, compiler artifacts,
build plugins and private consumers remain unpublished.

```sh
./publish-release.sh 0.1.0
# Explicitly publish automatically after Central validation:
./publish-release.sh 0.1.0 --auto
```

The script follows the peer SQL repo's native Toolchain publishing process. It
validates a non-SNAPSHOT SemVer, runs build/test/IDE gates, temporarily applies
release settings, verifies Maven Local output, signs and uploads only this
artifact. It restores the development template on exit. Central Portal approval
is manual by default; `--auto` explicitly enables automatic publication after
validation. No push to main triggers publication.

Required credentials are `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME`,
`KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD` and `KOTLIN_TOOLCHAIN_SIGNING_KEY`.
An encrypted key also needs `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE`. The script
accepts the older credential spellings used by the peer repo. Never commit keys
or credentials. The manual release workflow requires those organization secrets
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
