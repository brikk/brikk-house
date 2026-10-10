# SQL 0.18.0 — Maven Central acceptance

**Accepted: published SQL 0.18.0, with DDB-001 fixed.**

Maven Central's POM/module/JAR publications were confirmed for `brikk-sql`,
`brikk-sql-jvm`, `brikk-sql-metadata` and `brikk-sql-metadata-jvm` before adoption.
All five direct Engine SQL consumers now use `dev.brikk.house:brikk-sql-jvm:0.18.0`:
runtime, compiler plugin, schema-inputs plugin, build tooling and IDE harness.
There is no Maven Local override, snapshot dependency or sibling-source substitution.

## Released bytes

Direct downloads from `https://repo.maven.apache.org/maven2/dev/brikk/house`
matched these SHA-256s:

| Artifact | SHA-256 |
| --- | --- |
| `brikk-sql-jvm-0.18.0.jar` | `7d0546d0ff4cc70ee888350cc7ffdf3d37abc066b5dabe44b4634421c56224d1` |
| `brikk-sql-metadata-jvm-0.18.0.jar` | `f77f8e9e36bb88383016c12640073571769ecddab4979ccfe9f51dec14e73ed4` |

Both match the earlier snapshot candidate's code bytes. The **rebuilt CLI and IDE
shaded plugin build stamps independently match these Central inputs**. The resolved
dependency graph contains release SQL/metadata 0.18.0 and serialization 1.11.0,
not 0.17.0 or 0.18.0-SNAPSHOT.

## Permanent regressions

The old DDB-001 failure expectations are replaced with positive tests:

- Bare/interpolated/parenthesized/casted/repeated named SELECT projections.
- Exact occurrence/name ranges around literals, comments and Unicode.
- Native prefix aliases, struct colons and `$name` markers without extra bindings.
- Compiler-generated nullable projections and two-stage composition with
  same-name independent bindings, executed against DuckDB without CAST/SQL-copy
  workarounds for the bare-projection case.
- A permanent non-embeddable IDE-compiler fixture checks the same public Kotlin
  surface, rendering and binding names.

No full SQL regeneration or changed template-marker convention was added to
Engine. The sample's driver bridge still edits only exact parameter ranges.

## Verified gate

```sh
./kotlin show dependencies --all-modules
./kotlin do assemblePluginJar
./kotlin build
./kotlin test
./kotlin run -m duckdb-pipelines --jvm-args=--enable-native-access=ALL-UNNAMED -- --sql
./kotlin do verifyIdePlugin -m brikk-engine-kotlin-compiler-ide
./kotlin do verifySchemaRefresh -m brikk-engine-kotlin-compiler-plugin
./kotlin do publishKefsRepo
```

- **172 tests, zero failures/skips**, including twelve sample execution/regression tests.
- **Nine** finished-artifact fixtures on the actual `2.4.20-ij262-34` IDE compiler.
- All **seven** no-clean incremental schema refresh checks.
- Four reporting views execute with unchanged expected rows/totals. Native source
  SQL comments survive; driver adaptation changes placeholders only. Shared JSON /
  cleaning pipe regeneration is reported and all views report zero unsupported
  preservation messages.
- Local-only CLI/IDE KEFS repositories were refreshed with the rebuilt artifacts;
  no Engine artifact was uploaded to Central and no global IDE settings changed.

This is not live IDE/KEFS or target Doris acceptance. ENG-03/ENG-04 remain open
for those gates. ENG-06 stays deferred pending actual dogfood experience; no new
wiring DSL was added.

## Review next

Start with [the sample's review checklist](README.md#suggested-first-review):
the common extraction/cleaning functions and four report definitions in `Views.kt`,
fixture/business semantics and expected rows, then the narrow JDBC bridge.
Run with `--sql` to inspect the actual graph's SQL, not just compiler drafts.
Continue adding realistic views/edge cases before widening execution/wiring APIs.
