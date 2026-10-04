# Brikk Engine

Brikk Engine is the Kotlin runtime and compiler integration for typed, composable
SQL pipelines. Generic parsing, SQL analysis, and lowering live in
[brikk-sql](https://github.com/brikk/brikk-sql); embedded ClickHouse bindings live in
[brikk-chdb](https://github.com/brikk/brikk-chdb).

Engine consumes the published `dev.brikk.house:brikk-sql-jvm:0.17.0` release;
neither external repository is required in a clean Engine checkout. Keep the SQL
dependency version aligned across the runtime, compiler-plugin, and tooling modules.

## Modules

| Module | Responsibility |
| --- | --- |
| [brikk-engine-kotlin](brikk-engine-kotlin/) | The single runtime module: `Rel`, `Shape`, `Partial`, annotations, `Sql` entrypoints, bindings, and rendering. |
| [brikk-engine-kotlin-compiler-plugin](brikk-engine-kotlin-compiler-plugin/) | FIR analysis, shape generation, checks, call refinement, and IR rewriting. |
| [brikk-engine-kotlin-compiler-ide](brikk-engine-kotlin-compiler-ide/) | Toolchain harness to compile/test the plugin against the exact configured non-embeddable IDE compiler. |
| [brikk-engine-kotlin-tooling](brikk-engine-kotlin-tooling/) | Local Toolchain tasks to assemble the plugin and publish a KEFS repository. |
| [brikk-engine-kotlin-smoke](brikk-engine-kotlin-smoke/) | Synthetic consumer compiled through the real Toolchain `-Xplugin` path. |

The relocation does not rename Kotlin packages,
`@BrikkSql`, related annotations, or compiler ID `dev.brikk.house.sql.compiler`.
Public SQL/chDB Maven IDs stay unchanged. Engine modules are not published to
Central; the assembled/local KEFS artifact is `brikk-engine-kotlin-compiler-plugin`.

Keep the runtime in one module. Future database helpers such as
`brikk-engine-doris` should come from working application code when needed. Do not
create empty adapter modules or require a materialized-view planner to start a
migration. Execution order and storage boundaries remain author choices.

## SQL preservation

The requirement is to change no more SQL than necessary: unchanged same-dialect
native queries run as written; parameterization changes only parameter
representation; relation inputs require only their slot/CTE changes; pipe lowering
changes required structure while preserving unaffected native SQL as close to the
source as possible. Important hints, comments, and statement semantics matter.
Cross-dialect changes must be requested explicitly. Parsing for checks is not
permission to regenerate, optimize, or canonicalize unchanged SQL.

Source-preserving rendering has explicit supported/refused boundaries, rather
than a blanket guarantee for every SQL form. [Rel.render()](brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt)
preserves native same-dialect stages, standalone or composed: only bound slot
names, colliding binding names and embedded statement terminators are edited.
Their parameter style, whitespace, comments, hints and native syntax stay intact.
The compiler no longer applies an unrequested outer trim. Pipes (including nested
pipes) and FROM-first normalization use SQL 0.17's `toSourcePreservingExecutable`;
the SQL library reuses proved native intervals and reports structural regeneration.
Explicit cross-dialect translation still regenerates the affected stages using
their own source dialect context. Unsafe preservation (for example, moving
ClickHouse `SETTINGS` across a pipe boundary) refuses rather than silently
falling back. See the [SQL-05 handoff](../docs/HANDOFF-SQL-05-source-preserving-lowering.md).
Driver placeholder adaptation is separate, and binding
keys must come from `bindings()` rather than being guessed from parameter names.
AST round-trip equality does not prove text preservation. Check
source/output diffs alongside result-equivalence tests for each required lowering.
Pipe lowering is the largest risk; fix failures in `brikk-sql` with regressions,
not permanent copied handwritten SQL in consumers.

Call `Rel.renderWithDiagnostics()` to inspect the SQL and every stage's
regeneration diagnostics/unsupported messages. Diagnostic ranges refer to that
stage's `sourceSql` **after** Engine's slot/binding edits, not the final composed
SQL. No stale or approximate source map is exposed as an exact composed map.

## Local development

Run from the repository root with Kotlin Toolchain 0.13.0. Module names come from
leaf directories, with no `name` override.

```sh
./kotlin do assemblePluginJar
./kotlin test -m brikk-engine-kotlin-compiler-plugin -m brikk-engine-kotlin -m brikk-engine-kotlin-smoke
./kotlin do verifyIdePlugin -m brikk-engine-kotlin-compiler-ide
./kotlin do publishKefsRepo
```

The assembled JAR is
`build/plugin/brikk-engine-kotlin-compiler-plugin-2.4.10-0.2.0.jar`. The
[smoke config](brikk-engine-kotlin-smoke/module.yaml) consumes it and uses schema
path `brikk-engine/brikk-engine-kotlin-smoke/schema/events.sql`, relative to the
repository root. The option prefix remains
`-P plugin:dev.brikk.house.sql.compiler:...`.

Both artifacts relocate SQL/serialization dependencies. CLI publication uses
`build/repo`; KEFS's IDE candidate uses `build/repo-ide/2.4.20-ij262-34` and
`dev.brikk.house:brikk-engine-kotlin-compiler-plugin:2.4.20-ij262-34-0.2.0`.
The [IDE harness config](brikk-engine-kotlin-compiler-ide/module.yaml) pins the
actual compiler and library version; publishing rejects mismatched provenance.
The candidate's compiler/artifact tests pass, but live IDE loading and hot reload
are still unverified. See [ENG-03 acceptance and the live checklist](../docs/ENG-03-distribution-and-IDE.md).

## Private consumer

`brikk-engine/dogfood/` is ignored and excluded from the public manifest. It must
remain non-published and may be absent from any public checkout. There is no
`project.local.yaml` overlay; an explicit include of a missing module fails.

Use a synthetic module to validate the setup first. Temporarily add the local
`brikk-engine/dogfood` entry to `project.yaml`, then remove it before finishing or
running public build/publish checks. With the local include present, assemble the
plugin and run the application with `./kotlin run -m dogfood`. Its module must
disable publication; private SQL, fixtures, logs, and generated artifacts must not
enter public sources or release uploads. Verify `git check-ignore` for its files
and remove the include before staging `project.yaml`.

See the [active work list](../TODO.md) and
[wiring notes](../docs/virtual-pipelines-wiring.md) for current limitations.
The [compiler-plugin review](../docs/REVIEW-compiler-plugin.md) is historical;
all its findings are resolved.

[ENG-02 hardening](../docs/ENG-02-compiler-hardening.md) covers import-aware
type identity, nullability, generic argument matching, scoped SQL checks and
literal diagnostic ranges. Dotted placeholders are deliberately diagnosed;
extract a property into a local and interpolate that local instead.

## Schema capture

The forced `./kotlin do captureDorisSchema` command captures one Doris
catalog/database into the private dogfood cache. The compiler accepts the
resulting directory through its existing `schema` option and loads it offline.
See [connection settings, refresh behavior, and limits](../docs/schema-cache.md).
