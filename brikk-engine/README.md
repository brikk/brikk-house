# Brikk Engine

Brikk Engine is the Kotlin runtime and compiler integration for typed, composable
SQL pipelines. Generic parsing, SQL analysis, and lowering live in
[brikk-sql](../brikk-sql/brikk-sql/README.md); embedded ClickHouse bindings live under
[brikk-chdb](../brikk-chdb/).

## Modules

| Module | Responsibility |
| --- | --- |
| [brikk-engine-kotlin](brikk-engine-kotlin/) | The single runtime module: `Rel`, `Shape`, `Partial`, annotations, `Sql` entrypoints, bindings, and rendering. |
| [brikk-engine-kotlin-compiler-plugin](brikk-engine-kotlin-compiler-plugin/) | FIR analysis, shape generation, checks, call refinement, and IR rewriting. |
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

This policy is not yet implemented end-to-end. [Rel.render()](brikk-engine-kotlin/src/dev.brikk.house.sql.runtime/Rel.kt)
currently reparses and regenerates every fragment, including native queries with
no inputs. AST round-trip equality does not prove text preservation. Check
source/output diffs alongside result-equivalence tests for each required lowering.
Pipe lowering is the largest risk; fix failures in `brikk-sql` with regressions,
not permanent copied handwritten SQL in consumers.

## Local development

Run from the repository root with Kotlin Toolchain 0.12. Module names come from
leaf directories, with no `name` override.

```sh
./kotlin do assemblePluginJar
./kotlin test -m brikk-engine-kotlin-compiler-plugin -m brikk-engine-kotlin -m brikk-engine-kotlin-smoke
./kotlin do publishKefsRepo
```

The assembled JAR is
`build/plugin/brikk-engine-kotlin-compiler-plugin-2.4.10-0.2.0.jar`. The
[smoke config](brikk-engine-kotlin-smoke/module.yaml) consumes it and uses schema
path `brikk-engine/brikk-engine-kotlin-smoke/schema/events.sql`, relative to the
repository root. The option prefix remains
`-P plugin:dev.brikk.house.sql.compiler:...`.

KEFS uses `dev.brikk.house:brikk-engine-kotlin-compiler-plugin:<ide>-0.2.0` from
`build/repo`. Update existing bundles to this artifact ID. `ideKotlinVersion` and
`libVersion` live under `plugins.brikk-engine-kotlin-tooling` in the
[compiler module config](brikk-engine-kotlin-compiler-plugin/module.yaml).
Publishing under the IDE version does not rebuild against that compiler; the
current JAR merge also does not relocate dependencies. See the
[wiring notes](../docs/virtual-pipelines-wiring.md#local-ide-loop-kefs-hot-reload)
for the compatibility and distribution limits.

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

See the [wiring notes](../docs/virtual-pipelines-wiring.md) and the
[review findings](../TODO-review-findings.md) for current behavior and known
compiler/runtime defects.
