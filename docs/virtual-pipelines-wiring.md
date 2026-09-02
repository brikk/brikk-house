# Virtual pipelines in Kotlin — wiring notes (working doc)

Status: Sep 2026. Builds on `sql-compiler-plugin-learnings.md` §9–§13 and
`parsing-research-and-plan.md` "North star".

**The minimum see-it-work slice is implemented and green** (option C, Postgres, three-step
pipeline): `brikk-sql-runtime` (Shape/Partial/Rel/Sql), the plugin's declaration generation +
call refinement + checkers + IR rewrite, DDL-file schema cache, kctfork e2e tests, and the
smoke module compiled by the real toolchain. Mechanics and verified gotchas:
`docs/RESEARCH-fir-refinement-and-generation.md`. Sections below that describe design intent
still hold; "What exists" is updated.

## What exists

- `brikk-sql/shape/` — `Shape`, `ColumnShape`, `ShapeCatalog` (+ slots), `ShapeVerdict`,
  `SqlFragment` (one statement → scalar params, TVF slots, sources, output shape, lineage,
  serializable `FragmentDescription`/`FragmentContract`), `DdlCatalog` (DDL text → catalog).
  Slots nest under a synthetic qualifier so they coexist with qualified tables. Shape-layer
  typing override: scalar JSON extraction is TEXT (dialect tables stay sqlglot-faithful).
- `brikk-sql-runtime/` — `Partial` (minimum requirements), `Shape : Partial` (full, closed),
  `@BrikkSql`, `@BrikkTrait`, `@BrikkSqlDialect`, `Sql.postgres/doris/clickhouse/duckdb`,
  `Rel<out T : Partial>(sql, dialect).input(slot, rel).bind(name, v)` with `render()` (CTE
  chain, slot → CTE name) and `bindings()`.
- `brikk-sql-compiler-plugin/` — `analysis/` (TypeMap, SqlAnalyzer over raw function facts),
  `fir/` (session component with catalog/traits/analyses; `ShapeDeclarationGenerator` emitting
  `<Fn>Out : Shape|Partial, <satisfied traits>` with abstract vals; `BrikkSqlCallRefinement`
  typing `Sql.x()` inside `@BrikkSql` as `Rel<FnOut>` and generic pipe call sites as a local
  full Shape; checkers: non-const, outside-@BrikkSql, analysis failure, unbound `:param`,
  unknown column), `ir/` (rewrite to `Rel(...).input(...).bind(...)`, constructor bodies for
  local shapes). Options: `schema`, `schemaDialect`, `defaultSchema`, `debug`.
- `brikk-sql-plugin-smoke/` — the same three-step pipeline compiled by the real toolchain via
  `-Xplugin=build/brikk-sql-compiler-plugin-2.4.0-0.1.0.jar` (merged by `tools/assemble_plugin_jar.py`
  after `./kotlin build -m brikk-sql-compiler-plugin`) + `-P plugin:...:schema=...`.

## Division of labour (proposed)

**Plugin = FIR checker + shape-type generator. brikk-sql = value, composition, render.**

- Plugin reads schema JSON (build-tool introspection cache) → `ShapeCatalog`; parses each
  literal in its dialect; qualifies against catalog + parameter shapes; annotate-types +
  lineage; reports diagnostics with sub-literal ranges.
- Plugin generates nominal shape types (§9) and checks call-site compatibility.
- IR shrinks to: embed `FragmentDescription` + typed bindings. Composition
  (`f(g(h(x)))`) is a runtime slot bind in brikk-sql; graft/desugar/render happens at
  execution. No OwnerChain / packed-AST store / `inline` machinery for MVP.
- §12 execution modes: static requirements (output ⊆ target, key lineage) are FIR checks over
  `SqlFragment` shapes/lineage; lowering (INSERT wrap, watermark inject) is runtime.
- Open: is runtime-only rendering acceptable, or do we want rendered SQL as an inspectable
  compile-time artifact (dbt-style)?

## Surface sketch

Three stages: catalog-bound source with a date range → reusable headless pipe that
`EXTEND`s JSON fields → terminating pipe that closes the shape.

```kotlin
@BrikkSql
fun eventsInRange(range: DateRange) = Sql.doris("""
    FROM rumble_import.events
    |> WHERE event_at >= :range.start AND event_at < :range.end
""")

interface HasPayload : Shape { val payload: Json }

@BrikkSql
fun <T : HasPayload> extractEvent(src: Rel<T>) = Sql.doris("""
    |> EXTEND
         json_extract_string(payload, '$.user_id')  AS user_id,
         json_extract_string(payload, '$.action')   AS action,
         json_extract_int(payload, '$.duration_ms') AS duration_ms
""")

interface LoginInput : Shape { val user_id: String; val action: String; val event_at: Instant }

@BrikkSql
fun loginDaily(src: Rel<LoginInput>) = Sql.doris("""
    |> WHERE action = 'login'
    |> AGGREGATE count(*) AS logins, max(event_at) AS last_login
       GROUP BY user_id, date_trunc('day', event_at) AS day
""")

val report = loginDaily(extractEvent(eventsInRange(lastWeek)))
```

Generated types are named from the enclosing declaration (`ExtractEvent.Added`,
`LoginDaily.Out`); the user never declares them. Headless pipes parse as
`FROM __src |> <fragment>` with `__src : T`.

### The seam: EXTEND on a generic input

`Rel<T + ExtractEvent.Added>` is not expressible in Kotlin (no denotable intersections).
Options:

| | return type | mechanism | cost |
|---|---|---|---|
| A | `Rel<T, ExtractEvent.Added>` + `cast()` at call sites | stable extensions | noisy hover, visible seam |
| B | `Rel<ExtractEvent.Out>` with concrete trait input `Rel<EventLike>` | stable extensions | drops columns not in the trait; traits must be widened |
| C | omitted — refined per call site | `FirFunctionCallRefinementExtension` (`@FirExtensionApiInternals`, DataFrame's `@Refine`) | internal API; inferred public API needs stable names cross-module |

B cannot infer the return type: without an annotation the function types as whatever
`Sql.doris` declares; only the refinement extension can change the type of an existing call.
Minimum B form is `: Rel<Name>`; the name is arbitrary (read syntactically from the
signature), convention is `Enclosing.Out`.

**Leaning:** C on top of B's machinery. Rule: explicit return type → named generated type
(stable, cross-module); omitted → refined at call site (local convenience). If the internal
API breaks on a Kotlin bump, C-style declarations become "explicit return type required"
errors and nothing else moves. C dissolves the intersection problem; that is its real value,
not the missing annotations.

## IDE support

All FIR-based options need the IDE to run our FIR extensions; none degrade gracefully
without it (B: unresolved generated types everywhere; C: `Rel<*>`, no safety). Only IR-only
plugins (ExoQuery, terpal) or no-plugin codegen (SQLDelight) avoid this — by giving up types
or by not being a compiler plugin (= §10a SQL-file surface).

Mechanisms:
- **KEFS** (Kotlin External FIR Support, marketplace plugin; recommended by toolchain docs).
  Vendored docs: `docs/vendor/kefs/`. Loads a compiler plugin built against the *IDE's*
  compiler version, not the project's.
- **Own IntelliJ plugin** (Metro): `compiler-compat/` per-version `CompatContext` +
  ServiceLoader; relies on registry flag `kotlin.k2.only.bundled.compiler.plugins.enabled=false`
  for FIR loading. Most expensive; defer.

KEFS hard requirements (`docs/vendor/kefs/PLUGIN_AUTHORS.md`):
1. Published to a Maven repo (local dir OK). `-Xplugin=<path>` is invisible.
2. Version `<kotlin-version>-<lib-version>`, both semver. KEFS swaps the prefix for the IDE
   compiler (e.g. `2.4.0-ij253-45-0.1.0`) and looks that up; missing → silently no IDE support.
3. Compile against every supported IDE compiler build (from
   `packages.jetbrains.team/maven/p/ij/intellij-dependencies`), not just stable Kotlin.
   kotlinx-rpc: templated sources per version; Metro: compat layer.
4. External deps shaded + relocated into the jar — brikk-sql included. Mandatory.
5. Multi-artifact bundles must resolve at one version or are rejected → ship one shaded jar.

Useful: hot-reload via local repo + file watching (`PLUGIN_AUTHORS.md` §3) replaces the
"build the jar first" ritual on the IDE side; `.idea/kotlin-plugins.xml` is committable.

**Net:** the FIR path's real cost is a compiler-version matrix in CI plus a shaded single-jar
build. Required for B as much as for C.

### Local IDE loop (KEFS hot-reload) — set up, not yet exercised

```sh
./kotlin build -m brikk-sql-compiler-plugin
python3 tools/assemble_plugin_jar.py
python3 tools/publish_local_repo.py --ide-kotlin-version <from "KEFS: Copy Kotlin IDE Version">
```
publishes `dev.brikk.house:brikk-sql-compiler-plugin:<ide>-0.1.0` into `build/repo` (Maven
layout). KEFS: add `build/repo` as a Local repository and a bundle with those coordinates
("Latest" matching); leave the three replacement patterns at their defaults
(`<kotlin-version>-<lib-version>`, `<artifact-id>`, `<artifact-id>`). KEFS detects the plugin
from the `-Xplugin` jar *file name*, matched as `<detect>-<version>.jar`, which is why the
assembled jar is named `brikk-sql-compiler-plugin-2.4.0-0.1.0.jar` and not `...-all.jar`.
KEFS file-watches the repo: re-run the publish step after a plugin change. The jar is compiled against 2.4.0 regardless of the name — the first thing to
learn is whether the IDE's compiler build accepts it (the exception analyzer says so).

## Schema cache format

No industry standard. Surveyed: sqlglot `MappingSchema` (nested map, no introspection;
already ported), jOOQ `XMLDatabase` (INFORMATION_SCHEMA XML) / `DDLDatabase`, dbt
`catalog.json`, sqlx `.sqlx/query-*.json` (per-query describe cache — closest precedent for
compile-time typing from a committed file), Liquibase snapshot JSON, Atlas HCL, Iceberg/Arrow/
Substrait schemas, ODCS/datacontract YAML.

**Proposal:** cache = directory of DDL text (`SHOW CREATE TABLE` per table) + small JSON
index (source, dialect, captured-at, fingerprint). brikk-sql parses DDL → `ShapeCatalog`
(already `@Serializable`). Rationale: one parser/one type system; retains keys, partitioning,
engine (needed by §12 collapsing mode); trivially produced by any DB; PR-reviewable.
`information_schema` dumps and dbt `catalog.json` become importers that synthesize DDL.

### DDL parse coverage probe (Sep 2026, throwaway test, not committed)

Realistic `CREATE TABLE` per dialect through parse → generate → reparse:

- **ClickHouse**: full. `ReplacingMergeTree(ver)`, `ORDER BY`/`PRIMARY KEY`, `PARTITION BY`,
  `TTL`, `SETTINGS`, `CODEC`, `MATERIALIZED`, `INDEX ... TYPE bloom_filter`, `ON CLUSTER`,
  `ReplicatedMergeTree(...)`, `LowCardinality`/`Nullable`/`Array`/`Map`/`DateTime64` — all
  preserved, stable round-trip.
- **DuckDB, Trino, Postgres**: full for our purposes (nested types, constraints, Iceberg
  `WITH (...)`, `PARTITION BY RANGE`, FKs, identity).
- **Doris**: gaps (inherited from sqlglot's thin Doris dialect):
  - PARSE FAIL: `DECIMALV3(p, s)`; `BITMAP`/`HLL`/`QUANTILE_STATE`/`LARGEINT`/`IPV4`/`IPV6`
    types; aggregate-key column aggregators (`v BIGINT SUM`, `BITMAP_UNION`, `MAX`);
    `PARTITION BY RANGE(d) ()` (empty partition list, what dynamic partitioning emits);
    `INDEX idx (col) USING INVERTED PROPERTIES(...)`.
  - Falls back to opaque `Command`: `AUTO PARTITION BY RANGE (date_trunc(...)) ()`;
    `ROLLUP (...)`.
  - Lossy but harmless: `BUCKETS AUTO` dropped (absent = AUTO).
  - Generator-only: generated column renders as MySQL `GENERATED ALWAYS AS ... VIRTUAL`.
  - Works: `UNIQUE/DUPLICATE KEY`, `DISTRIBUTED BY HASH/RANDOM ... BUCKETS n`, `ENGINE=OLAP`,
    `PROPERTIES(...)`, `PARTITION BY RANGE/LIST` with explicit partitions, `COMMENT`,
    `DEFAULT`, `AUTO_INCREMENT`, `JSON`/`JSONB`/`VARIANT`, `ARRAY<>`/`MAP<>`/`STRUCT<>`,
    `DATETIME(3)`, `CREATE VIEW`, `CREATE MATERIALIZED VIEW ... BUILD/REFRESH/ON SCHEDULE`.

  Since real Doris `SHOW CREATE TABLE` output for a unique-key MoW table with dynamic
  partitioning hits three of the failures at once, Doris DDL parsing is a prerequisite for
  DDL-as-cache. Scope: type names (tokenizer/DataType), column aggregator suffix, empty
  partition list, `AUTO PARTITION`, `INDEX ... USING`, `ROLLUP`. All parser-side; generator
  fidelity is secondary (we parse the cache, we don't emit it).

### Upstream check (sqlglot main `v30.17.0-97`, 378 commits past pin `v30.12.0-44-g93d16591`)

Doris touched once since the pin (`DROP TABLE ... FORCE`). Upstream HEAD fails every case
above identically — sqlglot's Doris dialect (15-line dialect, 135-line parser: partition
property, dynamic granularity, MV BUILD/REFRESH; rest inherited from MySQL) is used for
transpiling queries *into* Doris, never for parsing Doris DDL. No upstream sync fixes this.

**TODO: Doris DDL parsing (brikk exceeds upstream here — mark `// brikk-native`).**
- Port from StarRocks (sibling dialect, never propagated to Doris): `LARGEINT → INT128`
  token; `_parse_rollup_property` + `_parse_create` for `ROLLUP (...)` (upstream #4509).
- Reuse existing tokens: `IPV4`/`IPV6` (ClickHouse tokenizer has them); `DECIMALV3` → alias
  of `DECIMAL`.
- New: `HLL`, `BITMAP`, `QUANTILE_STATE` DataType kinds; aggregate-key column aggregator
  suffix (`SUM|MAX|MIN|REPLACE|REPLACE_IF_NOT_NULL|HLL_UNION|BITMAP_UNION|QUANTILE_UNION|
  GENERIC`) as a column-constraint node; empty partition list `()`; `AUTO PARTITION BY
  RANGE(func(col))`; `INDEX name (cols) USING INVERTED PROPERTIES(...) COMMENT '...'`.
- Optionally PR the tokenizer/type bits upstream; the aggregator/inverted-index nodes are
  likely too Doris-specific for them.

**Separate TODO, do not conflate:** general resync of the port to a newer sqlglot pin
(378 commits of parser/optimizer drift; upstream also restructured into `parsers/` and
`generators/` packages).

## Open items

- Runtime-only render vs compile-time rendered artifact (see above).
- ~~Two-slot `Rel<Base, Ext>` vs refinement for EXTEND-on-generic~~ → refinement (C) built;
  works. Known limitation: a call-site local shape cannot escape through a plain helper with an
  inferred return type (approximated to `Rel<Shape>`); needs a checker hint. See RESEARCH doc.
- Demo shortcuts to harden (RESEARCH doc "Demo-grade shortcuts"): trait resolution by short
  name, nullability, dotted placeholders, positional args at generic call sites, flat column
  check, sub-literal diagnostic ranges.
- Publishing: `./kotlin publish` to a local repo dir with KEFS-compatible versioning; how to
  produce IDE-compiler-version builds under Kotlin Toolchain.
- Shading brikk-sql into the plugin jar with relocation (`tools/assemble_plugin_jar.py` is a
  plain merge; KEFS requires relocation).
- Doris DDL parser work before Doris can be the schema-cache dialect (see above).
- Step 4 (wiring / `then` operator) deferred.
