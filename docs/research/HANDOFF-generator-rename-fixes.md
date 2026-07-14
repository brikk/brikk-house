# HANDOFF — generator rename fixes (all involved dialects, both directions)

Self-contained handoff for a fresh brikk-sql agent to execute **step #1**: update the
dialect **generators** so brikk actually EMITS the correct target-dialect function
names for the cross-engine renames we've probe-verified — in **each direction** for
every focus pair. This turns recorded semantic-identical renames into *actually-working*
transpilation.

## Repo / build
- Worktree `/home/jayson/DEV/brikk/brikk-house-wip`, git branch **`sql-focus`** (push here).
- Kotlin **Toolchain** (Amper) project — FIRST load the `kotlin-toolchain` skill. Build/test:
  `./kotlin build` / `./kotlin test` from repo root. NOT Gradle/Maven.
- JDK 25 is auto-provisioned. Native tests (chdb) need `--enable-native-access=ALL-UNNAMED`
  (already set via `settings.jvm.test.freeJvmArgs` in brikk-chdb / brikk-sql-oracle module.yaml).
- Commit + push after each coherent chunk. Keep `./kotlin test` green (currently **563 JVM**).

## What is already DONE (context)
- Live-probed cross-engine **hazard registries** for all 6 core pairs exist under
  `brikk-sql/testResources/semantics/<a>-<b>-hazards.json`:
  trino-duckdb 246, duckdb-doris 258, trino-doris 216, duckdb-clickhouse 213,
  doris-clickhouse 177, trino-clickhouse 134. Regenerate the Kotlin registry with
  `python3 tools/generate_hazards_registry.py` (BYTE-DETERMINISTIC; CI-style check:
  regenerate then `git diff --exit-code`). Counts are pinned in
  `brikk-sql-metadata/test/.../HazardRegistryTest.kt` and
  `brikk-sql/test@jvm/.../HazardsRegistrySyncTest.kt` — update pins when a JSON changes.
- The renames to fix are consolidated in **`docs/research/CLICKHOUSE-rename-map.md`**
  (~94 source→ClickHouse renames in pattern buckets) plus per-pair gap reports
  `docs/research/{duckdb,trino,doris}-clickhouse-generator-gaps.md`. Many rename hazard
  entries are already recorded (verdict identical/divergent) with an `areas:["...","rename"]`
  tag and a hazard note saying "brikk may not yet emit it — certify's unmappable check guards".
- Raw probe evidence (batches + all 4 engines' outputs) is under `docs/research/probe-runs/`.

## THE TASK (#1)
For every probe-verified rename, make the relevant **generator** emit the target name, in
**both directions** of each focus pair (duckdb, trino, doris, clickhouse). Concretely:
1. **ClickHouse-target renames** (the bulk, in `CLICKHOUSE-rename-map.md`): the `Clickhouse`
   generator (and/or source parsers) must render the canonical node to the ClickHouse name.
   Buckets by size: `array_*`/`list_*`→`array*` (27), snake→camelCase (33),
   temporal→`to<Part>` (11), `other/explicit` (23). Start with the array family (cleanest,
   systematic), then temporal, then case-by-case for the rest.
2. **Reverse direction**: e.g. if `list_sort`→`arraySort` (duckdb→clickhouse), also ensure
   `arraySort`→`list_sort` (clickhouse→duckdb) is correct. Check each generator that could
   be a target.
3. The other pairs' generators (Doris, Trino, DuckDB) likewise: any rename we recorded that
   the generator doesn't emit is a gap to fix in that generator.

### Fix location — parser vs generator (check per function)
brikk parses a call to a canonical AST node under the SOURCE dialect, then the TARGET
generator renders it. A rename gap is EITHER:
- the source parser doesn't map the name to the canonical node (leaves it `Anonymous`), OR
- the target generator's `TRANSFORMS` doesn't render that node to the target name.
Diagnose by transpiling and checking the emitted name. Prefer fixing at the correct layer:
add the source-parser mapping (so it becomes the canonical node) and/or the target
generator `TRANSFORMS`/`renameFuncSql` entry. **Template: commit `c1932ef`** ("Fix ClickHouse
generator mapping bugs") shows the exact pattern (TRANSFORMS entries, `renameFuncSql`, method
overrides) — and `0c9c309` (Doris) is the analogous doris one.

Generators: `brikk-sql/src/dev.brikk.house.sql/dialects/{Clickhouse,Doris,Trino,Duckdb}Generator.kt`
(see each `companion object TRANSFORMS`). Parsers: `…/dialects/{...}Parser.kt` +
`…/parser/ParserFunctionBuilders.kt` / `GeneratedFunctionRegistry.kt`.

### CRITICAL: source-unaware generator caveat
Generators do NOT know the source dialect, so a TRANSFORMS rewrite fires on same-dialect
generation too (ClickHouse→ClickHouse via pipe desugaring / `toStandardSql`). Do NOT add a
rewrite that changes a NATIVE function's semantics on round-trip (this bit us before — see
`docs/research/SPIKE-source-aware-generator-transforms-*.md` and the reverted
lower/upper/week). A pure RENAME to a synonym (list_sort→arraySort) is safe; a
semantics-changing rewrite is not. When unsure, verify the round-trip
`transpile(sql, read=X, write=X)` is unchanged for native inputs.

## METHOD per rename (fix → verify → reconcile)
1. Add the parser/generator mapping.
2. **Verify** with the local engines: `transpile("SELECT <call>", read=SRC, write=TGT)` now
   emits the target name; run the emitted SQL on the target engine and the source call on the
   source engine; diff. Local engines available in Python: **chdb** (ClickHouse 26.5.1.1) and
   **duckdb 1.5.4**. For Trino/Doris you have no local engine — hand a probe batch to the
   **doris-ducklake agent** (it runs live Trino 481 + Doris; see the existing
   `HANDOFF-*-mass.md` for the batch/writeback pattern under `docs/research/probe-runs/`).
3. **Reconcile the hazard**: the recorded rename entry can drop the "brikk may not yet emit"
   note; if the fix makes a divergent-only-by-name case now correct, re-verify the verdict.
   Regenerate the registry (byte-clean), update pins.
4. Add/extend a regression test pinning the corrected rendering (mirror
   `ClickhouseGeneratorMappingBugsTest` / `DorisGeneratorMappingBugsTest`).

## Honesty rules (keep the registry trustworthy)
- Only record `identical` when probe-verified equal; keep divergent where semantics differ
  even after the rename (e.g. `weekday`→`toDayOfWeek` numbering, `date_format` specifiers).
- Auto-probes are SINGLE-INPUT — treat their verdicts as candidates; for anything you flip to
  `identical` that gates certify, prefer a second input. Provenance-tag everything.
- Do not blind-fix: if a "rename" target has different semantics, it's divergent, not a fix.

## Tooling notes / gotchas
- Temp probe/transpile helpers: write throwaway `*Test.kt` (class name MUST end in `Test` for
  toolchain discovery); read/write under `/tmp/opencode`; DELETE them before committing.
- chdb TSV bytes: decode with `errors="backslashreplace"`; normalize when diffing (booleans
  1/true, NULL vs `\N`, float precision, array spacing `[1, 2]`/`[1,2]`, hex-vs-raw-bytes for
  hashes → treat as conditionally-equivalent representation).
- The vendored catalogs for name lookup: `vendor/data/clickhouse-functions-26.5.1.1.tsv`
  (1788), `vendor/data/trino-functions-481.tsv` (746), `GeneratedDorisFunctionCatalog.kt`.
- certify policy #2 (`Certify.kt`): a divergent hazard with a dedicated target renderer is a
  non-blocking WARNING, without one a REFUSAL. Adding a renderer changes this — re-run
  `CertifyTest` and the corpus gate (`VerifyCorpusGateTest`, now in `brikk-sql-oracle`).

## Suggested order
1. `array_*`/`list_*`→`array*` (duckdb+doris → clickhouse), verify via chdb+duckdb locally.
2. temporal `to<Part>` (duckdb+doris → clickhouse).
3. snake→camelCase batch (verify each; some are semantics-changing, not pure renames).
4. Reverse directions (clickhouse→duckdb/doris) + the trino side (probe batch to the agent).
5. After each chunk: regenerate registry byte-clean, update pins, `./kotlin test` green, commit+push.

## Return
Per-chunk: functions fixed (which generator/parser), verification evidence, hazards
reconciled, new test pins, `./kotlin test` counts. Keep raw probe data under probe-runs/.
