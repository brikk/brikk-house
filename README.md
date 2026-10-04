# brikk-house

Brikk Engine: the Kotlin runtime and compiler integration for typed, composable
SQL pipelines. Generic SQL libraries and embedded chDB bindings now have their
own repositories:

| Repository | Responsibility |
| --- | --- |
| [brikk-sql](https://github.com/brikk/brikk-sql) | Parser, AST, dialects, transpiler, optimizer, metadata, and SQL verification/oracles. |
| [brikk-chdb](https://github.com/brikk/brikk-chdb) | Embedded chDB Kotlin/JVM binding and platform-native resource JARs. |
| **brikk-house** | Engine runtime, Kotlin compiler plugin, build/IDE tooling, and smoke consumer. |

The extracted repositories preserve their relevant Git history and existing
`dev.brikk.house` Maven coordinates. Each owns its Maven Central snapshot/release
workflows. This repository no longer publishes SQL or chDB libraries.

## Modules

[project.yaml](project.yaml) explicitly includes these public modules:

- `brikk-engine/brikk-engine-kotlin`: `Rel`, `Shape`, `Partial`, annotations,
  `Sql` entrypoints, bindings, and rendering.
- `brikk-engine/brikk-engine-kotlin-compiler-plugin`: compile-time SQL analysis,
  generated shapes, checks, call refinement, and IR rewriting.
- `brikk-engine/brikk-engine-kotlin-tooling`: plugin assembly, local KEFS
  publishing, and offline schema-capture tooling.
- `brikk-engine/brikk-engine-kotlin-smoke`: synthetic consumer of the actual
  compiler-plugin build path.

Module selectors use the leaf name, for example `-m brikk-engine-kotlin`.
Kotlin packages, annotations, and compiler ID `dev.brikk.house.sql.compiler`
are unchanged. Engine modules are not published to Maven Central.

## Dependencies

Engine consumes `dev.brikk.house:brikk-sql-jvm:0.17.0` from Maven Central.
No sibling SQL/chDB checkout, Maven Local installation, credentials, or `.env`
is needed for public Engine builds. Keep the SQL version aligned in the runtime,
compiler-plugin, and tooling module configurations when upgrading.

For coordinated development, change and validate SQL in its own repository first,
publish a release, then update Engine's dependency. Independent builds must not
depend on unpublished sibling source directories.

## Build and test

Use the pinned **Kotlin Toolchain 0.13.0** wrapper from the repository root.
The CLI compiler/API remain pinned to **2.4.10**. The IDE-candidate harness
separately builds/tests against **2.4.20-ij262-34**'s actual non-embeddable API.

```sh
./kotlin do assemblePluginJar
./kotlin build
./kotlin test
```

The smoke consumer needs the assembled plugin JAR before compilation. CI runs
the same assembly/build/test sequence on pushes and pull requests.

## Local IDE development

```sh
./kotlin do verifyIdePlugin -m brikk-engine-kotlin-compiler-ide
./kotlin do verifySchemaRefresh -m brikk-engine-kotlin-compiler-plugin
./kotlin do publishKefsRepo
./kotlin check
./kotlin show commands
```

The CLI JAR remains
`build/plugin/brikk-engine-kotlin-compiler-plugin-2.4.10-0.2.0.jar`. Both builds
relocate SQL/serialization dependencies. KEFS's IDE candidate is published as
`dev.brikk.house:brikk-engine-kotlin-compiler-plugin:2.4.20-ij262-34-0.2.0` in
`build/repo-ide/2.4.20-ij262-34`; publication refuses compiler-version relabeling.
Live IDE loading/highlighting/hot reload are still unverified. See the
[ENG-03 acceptance record](docs/ENG-03-distribution-and-IDE.md).

## SQL preservation

Virtual parameterized views/pipes are checked for compatible shapes at compile
time, then glued together and rendered at runtime. Optional
`dumpSql=build/sql-drafts/{module}.draft.sql` compiler output shows rough stage
templates, not final/executable SQL. See [runtime-first and draft inspection](docs/ENG-05-runtime-first-and-drafts.md).

Parsing for checks is not permission to regenerate or optimize executable SQL.
Unchanged same-dialect native queries must run as written. Bindings, relation
slots, pipe lowering, and explicit transpilation permit only their necessary
changes. Preserve important hints, comments, and statement semantics.

`Rel.render()` preserves native same-dialect stages even in composition, editing
only bound slots, colliding binding names and embedded terminators. Pipes and
FROM-first normalization use SQL 0.17's source-preserving executable API;
explicit cross-dialect translation remains source-aware. `renderWithDiagnostics()`
reports any portions that needed regeneration and unsupported warnings per stage.
The [SQL-05 handoff](docs/HANDOFF-SQL-05-source-preserving-lowering.md) records the
ownership and acceptance contract. Check output
diffs alongside semantic tests. Fix lowering failures in `brikk-sql`, rather than
keeping permanent handwritten SQL copies in consumers.

## Private consumer

The ignored `brikk-engine/dogfood/` consumer is excluded from the public manifest
and must remain non-published. Public builds work without it. Never copy private
SQL, schemas, logs, or generated artifacts into either extracted repository.
See the [local add/remove workflow](brikk-engine/README.md#private-consumer).

## Documentation

- [Active work list](TODO.md) — `ENG-*` items with links to the SQL and chDB backlogs
- [Brikk Engine](brikk-engine/README.md)
- [Pipeline wiring](docs/virtual-pipelines-wiring.md)
- [Schema cache](docs/schema-cache.md)
- [Completed compiler-plugin review](docs/REVIEW-compiler-plugin.md)
- [SQL API and publishing](https://github.com/brikk/brikk-sql)
- [Embedded chDB API and publishing](https://github.com/brikk/brikk-chdb)
