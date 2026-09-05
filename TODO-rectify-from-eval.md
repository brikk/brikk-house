# TODO - rectify findings from the 2026-09-02 quality/correctness evaluation

> **Context for an agent picking this up fresh.**
> `brikk-sql` is a hand-written Kotlin port of Python `sqlglot`, pinned to
> `v30.17.0-93-gdcc36544a` (`reference/sqlglot/` is the oracle). Build/test from the
> repo root with `./kotlin build` and `./kotlin test`. Corpus gates enforce exact
> `*-known-failures.json` ledgers (see `TODO-BUGS-generation.md` for the mechanics).
>
> This file tracks defects and gaps surfaced by an independent evaluation of `main`
> at `d3df965`. Each item has a stable ID (`EVAL-NN`) so it can be addressed one at
> a time. Work an item, check it off, and reference the ID in the commit subject
> (e.g. `generator: port TsOrDsTo* handlers (EVAL-01)`).
>
> Severity: **HIGH** = wrong output / data-affecting bug; **MEDIUM** = latent bug or
> process gap that lets HIGH bugs through; **LOW** = robustness, ergonomics, hygiene.
>
> Intentional divergences registered in `docs/brikk-extensions.md` are **not** in
> scope here and must not be "fixed" toward SQLGlot.
>
> **Progress:** EVAL-01, -02, -03, -04, -05, -07 done (2026-09-02); full suite green
> (664 tests). Open: EVAL-06, -08, -09, -10, -11, -12, -13, -14, -15.

---

## Correctness

### EVAL-01 — Unported `TsOrDsTo*` base-generator handlers leak pseudo-functions — HIGH

- [x] Status: **done** (2026-09-02)
  - Ported `tsordstotimeSql` / `tsordstotimestampSql` / `tsordstodatetimeSql` into
    `generator/Generator.kt` next to `tsordstodateSql` (which also gained the missing
    `is_type(DATE)` short-circuit), registered in `generator/GeneratorTables.kt`.
  - Added `Generator.castIdempotent` (port of `exp.cast(..., dialect=)` — no double cast
    when the existing CAST is to a type the target's `typeMapping` renders identically)
    and the missing `Cast.isType` override (sqlglot: `Cast.is_type` delegates to `to`).
  - Removed DuckDB's local `TsOrDsToTimestamp` unwrap in `timeToStrSql` (a workaround for
    the missing base handler). DuckDB still lacks a `TimeStrToTime` registration
    (Python: `timestrtotime_sql`) — left as-is, noted in a TODO there.
  - 10 BigQuery transpile ledger entries (`TIME(...)`, `DATETIME(...)`, `FORMAT_DATETIME`)
    now pass and were removed from the ledger. `TODO-bigquery.md` counts updated.
  - Regression test: `brikk-sql/test/.../TsOrDsCoercionTest.kt` (5 cases x 13 targets,
    oracle-derived expectations, asserts no `TS_OR_DS_TO_` substring).

**Problem.** Python's base generator defines four handlers
(`reference/sqlglot/sqlglot/generator.py:5178-5209`): `tsordstotime_sql`,
`tsordstotimestamp_sql`, `tsordstodatetime_sql`, `tsordstodate_sql`. Brikk ports only
`tsordstodateSql` (`brikk-sql/src/dev.brikk.house.sql/generator/GeneratorTables.kt:574`,
`generator/Generator.kt:4140`). The other three nodes fall through to
`functionFallbackSql` and emit internal names no engine accepts.

**Repro.**
```
transpile("SELECT DATE_FORMAT(dt, '%Y-%m-%d') FROM t", read="mysql", write="trino")
  brikk : SELECT DATE_FORMAT(TS_OR_DS_TO_TIMESTAMP(dt), '%Y-%m-%d') FROM t
  oracle: SELECT DATE_FORMAT(CAST(dt AS TIMESTAMP), '%Y-%m-%d') FROM t

transpile("SELECT DATETIME(x), TIME(x) FROM t", read="bigquery", write="spark")
  brikk : SELECT TS_OR_DS_TO_DATETIME(x), TS_OR_DS_TO_TIME(x) FROM t
```
Leaks observed for mysql `DATE_FORMAT` -> trino/presto/postgres/spark/hive/base and
bigquery `DATETIME()`/`TIME()`/`FORMAT_TIMESTAMP()` -> trino/presto/duckdb/postgres/
spark/hive/doris/starrocks/base. Node producers: `dialects/MysqlParser.kt:874`,
`dialects/BigqueryParser.kt:222,234,1154-1156`.

**Fix.**
1. Port `tsordstotimeSql`, `tsordstotimestampSql`, `tsordstodatetimeSql` into
   `Generator.kt` next to `tsordstodateSql` (same shape: pass-through if already the
   right type, else `CAST(this AS <TYPE>)` via the dialect-aware cast); register them in
   `GeneratorTables.kt`.
2. Check whether any dialect generator (`Duckdb`, `Bigquery`, `Mysql`, `Presto`...)
   overrides these in Python and port those overrides too (`rg 'TsOrDsTo' reference/sqlglot/sqlglot/dialects/`).
3. Add fixture coverage so the gates own this: extend the mysql and bigquery
   `dialect-corpus` fixtures (or add hand cases in `test/`) for `DATE_FORMAT`,
   `DATETIME()`, `TIME()`, `FORMAT_TIMESTAMP()` across every in-scope target.

**Verify.**
`./kotlin test -m brikk-sql --include-classes='dev.brikk.house.sql.*TranspileTest'`
and confirm no `TS_OR_DS_TO_` substring is produced for any in-scope target.

---

### EVAL-02 — `Expression.objectId` is a non-atomic global counter — MEDIUM

- [x] Status: **done** (2026-09-02)
  - `Expression.kt`: `nextObjectId` is now `kotlin.concurrent.atomics.AtomicLong`
    (common stdlib, `@OptIn(ExperimentalAtomicApi)`), `objectId = incrementAndFetch()`.
  - Regression test: `brikk-sql/test@jvm/.../ConcurrencyTest.kt` — 8 threads x 64 tasks x
    100 parses assert zero duplicate ids; concurrent transpile+annotate must equal the
    single-threaded result. (Same shape reproduced 13,753 duplicates before the fix.)

**Problem.** `brikk-sql/src/dev.brikk.house.sql/ast/Expression.kt:23` declares
`private var nextObjectId: Long` and `:90` does `val objectId: Long = ++nextObjectId`.
The comment says "single-threaded use, like the tests", but this is a published library.
`objectId` is the identity key for `visited` sets and caches in
`optimizer/AnnotateTypes.kt:299,309,551,976,1030` and `optimizer/Resolver.kt:460-461`.
A collision inside one tree makes annotation silently skip a node.

**Evidence.** 8 threads x 200 `parseOne` calls: 13,753 duplicated IDs out of 661,712.

**Fix.** Make the increment atomic. The module is `kmp/lib` (JVM-only today), so either
`kotlin.concurrent.atomics.AtomicLong` (common, `@OptIn(ExperimentalAtomicApi::class)`)
or an `expect/actual` wrapping `java.util.concurrent.atomic.AtomicLong`. Alternatively
drop the counter and key caches by reference identity (`IdentityHashMap` / `===`-based
set) on JVM.

**Verify.** Add a small concurrency test (see EVAL-09) asserting zero duplicate
`objectId`s across parallel parses.

---

## Process / gates

### EVAL-03 — No CI job runs the test suite — MEDIUM

- [x] Status: **done** (2026-09-02)
  - New `.github/workflows/test.yml`: `./kotlin build` + `./kotlin test` on every
    `pull_request`, `workflow_call`, `workflow_dispatch`; caches
    `~/.cache/JetBrains/Kotlin/{cli,download.cache}`; uploads `*-ledger-actual.json` as an
    artifact on failure (exactly what the ledger workflow needs).
  - `snapshot.yml` and `release.yml` now `uses: ./.github/workflows/test.yml` and
    `needs: test` before publishing (main runs the suite exactly once per push).
  - All actions pinned to commit SHAs (`checkout` v4.2.2, `cache` v4.2.3,
    `upload-artifact` v4.6.2); `release.yml` passes `inputs.version` via `env:`.
  - Not done: registering detekt/ktlint as local-plugin checks (optional item 4).

**Problem.** `.github/workflows/snapshot.yml` (push to `main`) and `release.yml`
(push to `release/**`) only run `./kotlin publish` / `publish-release.sh`. Nothing runs
`./kotlin test` or `./kotlin check`; nothing runs on pull requests. A red gate on `main`
still publishes a snapshot. Related hardening:

- `actions/checkout@v4` is a floating tag in both workflows; pin to a SHA.
- `release.yml:36-37` interpolates `${{ github.event.inputs.version }}` directly into a
  `run:` script (script-injection pattern). Pass it through `env:` instead.
- Only the builtin `tests` check is registered (`./kotlin show checks`); no
  detekt/ktlint/API-compat check.

**Fix.**
1. Add a `test` workflow on `pull_request` and `push` to `main` running `./kotlin check`.
2. Make `snapshot.yml` and `release.yml` depend on the test job (or run
   `./kotlin check` before publish).
3. Pin actions to SHAs; move the version input into `env:`.
4. (Optional) register detekt/ktlint as a local-plugin check under `checks:`.

**Verify.** Open a PR with a deliberately failing test; CI must go red.

---

### EVAL-04 — 19 in-scope transpile directions silently skipped (version-qualified keys) — LOW

- [x] Status: **done** (2026-09-02)
  - New `brikk-sql/test@jvm/.../CorpusDialects.kt`: `resolveOrSkip(name)` strips a
    `, version=...` suffix and resolves the base dialect (so the case RUNS, keyed by its full
    spelling); skips only names on the explicit `OUT_OF_SCOPE` set (16 non-ported sqlglot
    dialects); `fail()`s on anything else. Used by `TranspileCorpusGate` and
    `DatafusionFixtureTest`.
  - Result: the 19 hidden directions now run — 10 pass, 9 are version-dependent downgrades
    brikk cannot model (no dialect `version` setting) and are ledgered explicitly in the
    clickhouse/duckdb/postgres/spark transpile ledgers. Documented as the single remaining
    cluster in `TODO-BUGS-rewrites.md`.

**Problem.** Upstream `validate_all` uses keys like `"postgres, version=16"`. These
appear in `brikk-sql/testResources/dialect-corpus/*.json` (postgres 13.9/15/16/17.5,
duckdb 1.1.0/1.2, clickhouse 23.8/24.1, spark 3.0.0/4.0.0 — 19 directions total).
`TranspileCorpusGate.kt:52,74` skips any key `Dialects.forNameOrNull(...) == null`, so
these are counted as "unavailable" alongside genuinely out-of-scope snowflake/tsql skips
and never run. A typo'd dialect name in a future fixture would also skip silently.

**Fix.** In the gate, strip the `, version=...` suffix and either (a) run the case
against the base dialect when the pin doesn't affect the SQL, or (b) ledger it
explicitly. Additionally, maintain an explicit out-of-scope allow-list (snowflake, tsql,
oracle, sqlite, redshift, databricks, ...) and `fail()` on any skipped name not in it.

**Verify.** Gate summary shows 0 skips outside the allow-list.

---

### EVAL-05 — No fixture<->pin sync check; stale stamps — LOW

- [x] Status: **done** (2026-09-02)
  - New `brikk-sql/test@jvm/.../FixturePinSyncTest.kt` with `const val SQLGLOT_PIN`:
    every JSON under `testResources/` carrying a `sqlglot_version`/`version` stamp must
    equal the pin; every oracle-derived corpus must carry one (ledgers, `semantics/` and
    brikk-native DataFusion fixtures are exempt from "must be stamped"). 104 fixtures verified.
  - `tools/gen_lineage_corpus.py` now stamps `sqlglot_version` from `git describe --tags`
    (was a hardcoded `VERSION`); `lineage-corpus/base.json` regenerated at the pin (case
    content byte-identical, only the stamp changed).
  - Stale oracle stamp removed from `generator-corpus/known-failures.json` (a brikk-side
    ledger; `GeneratorIdentityCorpusTest.Ledger` no longer requires the field).
  - `reference/sqlglot` fast-forwarded to `dcc36544a` (the pin). `TODO-sqlglot-catchup.md`
    durable rules mention `SQLGLOT_PIN`.
  - Not done: `tools/extract_dialect_tests.py:459` still falls back to `"unknown"` (the new
    test would catch the resulting stamp, so it is now harmless).

**Problem.**
- `brikk-sql/testResources/generator-corpus/known-failures.json` still stamps
  `v30.12.0-44-g93d16591`.
- `tools/gen_lineage_corpus.py:26` hardcodes `VERSION = "v30.12.0-44-g93d16591"`
  instead of `git describe`; `lineage-corpus/base.json` uses key `version`, not
  `sqlglot_version`.
- `tools/extract_dialect_tests.py:459` falls back to `"unknown"` on error rather than
  aborting.
- No test asserts that every fixture's `sqlglot_version` equals the pin.
- The local `reference/sqlglot` clone is at `v30.17.0-72-gbac1a897b`; commit
  `dcc36544a` is not present, i.e. the working oracle is 21 commits behind the fixtures.

**Fix.** Add a single constant (e.g. `SQLGLOT_PIN` in test code) and a test that walks
`testResources/**/*.json` asserting the stamp. Fix the two tools. Document in
`TODO-sqlglot-catchup.md` that `reference/sqlglot` must be fast-forwarded to the pin.

---

### EVAL-06 — Stale coverage figure in TODO docs — LOW

- [ ] Status: open

**Problem.** `TODO-BUGS-generation.md:16` and `TODO-BUGS-newcoverage.md:16` say
"~94% of ~16.4k corpus cases match". Checked-in data gives 12,707 sqlglot-oracle cases
with 237 ledgered failures (98.1%); ~15.6k across all ledgered gates (~98.5%). The
figure predates the 2026-09-01 ledger clean-up.

**Fix.** Regenerate the numbers from the ledgers (or delete the sentence and point at
the gate output).

---

## Public API

### EVAL-07 — `parseOne` and `transpile` disagree on unknown dialect names — LOW/MEDIUM

- [x] Status: **done** (2026-09-02)
  - New `dialects.UnknownDialectException(dialectName)` (extends
    `IllegalArgumentException` for compatibility; message lists `Dialects.NAMES`).
  - `Dialects` is the single source of truth: `parser.TokenizerConfigs.forName` now
    delegates to it (no `else -> BASE`), and `parser.parseOne(sql, dialect)` is just
    `Dialects.forName(dialect).parseOne(sql)`. `PipeStageSplitter` inherits the strictness.
  - Test: `ParserTest.unknownDialectIsRejectedConsistentlyByEveryEntryPoint` covers
    `parseOne`, `transpile(read=)`, `transpile(write=)`, `Expression.sql`,
    `TokenizerConfigs`, and checks every `Dialects.NAMES` entry resolves.

**Problem.** `parser/Parser.kt:10687` `parseOne(sql, dialect)` falls back to
`TokenizerConfigs.forName(dialect)`, whose `else -> TokenizerConfig.BASE`
(`parser/TokenizerConfigs.kt:23`) means `parseOne(sql, "snowflake")` and even
`parseOne(sql, "postgress")` succeed silently with the base parser. `transpile(sql,
read="snowflake")` (`dialects/Dialect.kt:328`) throws `IllegalArgumentException`.

**Fix.** Make `parseOne` throw on unregistered names (the fallback comment says it was
"pre-registry behavior"; every in-scope dialect is now registered). If a lenient mode
is wanted, make it explicit (`parseOne(sql, dialect, lenientDialect = true)`).
Consider a shared `UnknownDialectException` and list the accepted names in the message.

**Verify.** Add `assertFailsWith` tests for both entry points with an unknown name.

---

### EVAL-08 — `transpile()` silently drops trailing statements; dead placeholder — LOW

- [ ] Status: open

**Problem.**
- `transpile("SELECT 1; SELECT 2")` returns `"SELECT 1"` (`Dialect.kt:328` uses
  `parseOne`). Python returns a list of all statements. KDoc notes this but callers
  will not expect silent data loss.
- `brikk-sql/src/dev.brikk.house.sql/BrikkSql.kt` is a "Placeholder to anchor the
  module" object whose only use is `test/.../BrikkSqlTest.kt`.

**Fix.** Either add `transpileAll(...): List<String>` and have `transpile` throw when
more than one statement is present, or document loudly. Delete `BrikkSql.kt` and its
test (or turn it into a real version/pin holder, e.g. `BrikkSql.SQLGLOT_PIN`).

---

## Robustness tests

### EVAL-09 — No adversarial or concurrency tests — LOW

- [ ] Status: open — concurrency half done under EVAL-02 (`ConcurrencyTest.kt`); the
  adversarial-input half (deep nesting, unterminated input, huge inputs) is still open

**Problem.** `test/` has zero tests for threading, deep nesting, unterminated input, or
very large inputs. Measured: `SELECT` + 500 nested parens -> `StackOverflowError`
(Python fails at 100, so brikk is already more robust than the oracle, but an `Error`
escapes to callers). 20k chained `AND`s and a 50k-item `IN` list parse fine.

**Fix.** Add a `RobustnessTest` in `test@jvm/`:
- concurrent parse/annotate from N threads, asserting no duplicate `objectId` and
  identical output to the single-threaded result (closes EVAL-02);
- deep-nesting probe documenting the current limit (and, optionally, a depth guard in
  `Parser` that throws `ParseError("nesting too deep")` instead of `StackOverflowError`);
- unterminated string / unbalanced paren / empty input produce `TokenError`/`ParseError`
  with line/col (already true — pin it).

---

### EVAL-10 — Ledger-actual output path is CWD-dependent — LOW

- [ ] Status: open

**Problem.** `LedgerGate.kt:82` (copied in `AnnotateTypesCorpusTest.kt:138`,
`DatafusionFixtureTest.kt:119`, `LineageCorpusTest.kt:246`, `QualifyCorpusTest.kt:210`,
`ScopeCorpusTest.kt:174`, `DatafusionSltParseTest.kt:88`):
`File("build").takeIf { it.isDirectory } ?: File(".")`. Amper runs tests with CWD =
module root, so 49 `*-ledger-actual.json` files land in `brikk-sql/`. Never `mkdirs()`.
Same CWD-dependence in `TestResources.kt:16-17`.

**Fix.** Resolve a single output dir once (e.g. `System.getProperty("brikk.ledgerOut")
?: "build/ledger-actual"`), `mkdirs()` it, and route all seven gates through
`LedgerGate` (the `TODO(test-dedup)` at `LedgerGate.kt:24-29` already asks for this).
Update `.gitignore` and the TODO docs that mention the path.

---

## Verifier / oracle modules

### EVAL-11 — `DuckdbVerifier` misreports non-parser failures — LOW

- [ ] Status: open

**Problem.** `brikk-sql-verify/src/.../DuckdbVerifier.kt:99-116`: in the prepare
fallback, any `SQLException` whose message does not start with `"Parser Error"` returns
`accepted = true` — a connection/IO error becomes "SQL accepted". Conversely `:68-71`
turns a failed `json_serialize_sql` into `accepted = false`. `LINE_MARKER`
(`:140`, `LINE (\d+):\s*(?:(\d+))?`) almost never captures a column and is wrong when
it does (DuckDB prints `LINE 1: <sql>`).

**Fix.** Return `verified = false` with the reason for anything that is not a
recognised parser/binder error; fix or drop the column regex.

---

### EVAL-12 — `PostgresVerifier` platform and SQLSTATE issues — LOW

- [ ] Status: open

**Problem.**
- `brikk-sql-oracle/module.yaml:29` pins only
  `embedded-postgres-binaries-linux-amd64`; zonky 2.2.2 ships no arm64 binaries at all.
  On Apple Silicon / linux-arm64 the postgres verify gate
  (`VerifyCorpusGateTest.kt:158-176`) never checks `verified`, so it fails with every
  case unledgered instead of one clear "unavailable".
- `PostgresVerifier.kt:261` treats SQLSTATE `0A000` as accepted, but PG's own `gram.y`
  raises `ERRCODE_FEATURE_NOT_SUPPORTED` for grammar-level rejections (e.g. `MATCH
  PARTIAL`, `CREATE ASSERTION`). Class `54` (program limit) is missing. `42P22` is
  listed twice (`:226`, `:259`).
- `:118` builds `PREPARE $name AS $sql` by concatenation in autocommit; a multi-statement
  input would execute the second statement outside the rollback path.
- `close()` (`:197`) resets `unavailableReason`, so a closed verifier reboots PG on the
  next call.
- KDoc (`:55-56`) and `module.yaml:23-24` describe a runtime binary download to
  `~/.embedded-postgres-binaries`; zonky resolves binaries from classpath jars.

**Fix.** Gate/skip cleanly when `verified = false` (one summary line); declare the
platform binary per-host or document the amd64-only constraint; audit the SQLSTATE
table; reject multi-statement input up front; correct the docs.

---

### EVAL-13 — `DorisVerifier` classloader leak and consumer-hostile jar lookup — LOW

- [ ] Status: open

**Problem.** `DorisVerifier.kt:118` creates a `URLClassLoader` that is never closed or
retained; every `createOrNull()` reloads all ANTLR classes. Jar resolution (`:101-134`)
walks up from CWD looking for `vendor/lib/doris-fe-sql-parser-*.jar`, so
`SqlVerifiers.forEngine("doris")` returns `null` for any Maven consumer unless
`-Dbrikk.doris.parser.jar` is set. `createOrNull`/`loadParserClass` use
`runCatching{}.getOrNull()` (swallows `Error`s), inconsistent with `:51`.
`ShardingSphereVerifier.kt:25` references a module `brikk-sql-verify-chdb` that does
not exist (it is `brikk-sql-oracle`).

**Fix.** Cache one loader per jar path (or make `DorisVerifier` `AutoCloseable` and
close it); surface the "jar not found" reason via `verified = false` rather than
`null`; narrow the `runCatching` to `Exception`; fix the stale comment.

---

### EVAL-14 — `brikk-chdb` default tests exercise no native code — LOW

- [ ] Status: open

**Problem.** `brikk-chdb/test/.../ChdbApiTest.kt:27-36` returns early unless
`-Dbrikk.chdb.integrationLibrary` is set; `brikk-chdb/module.yaml` adds neither
`--enable-native-access` nor a dependency on a `brikk-chdb-native-*` module, so
`./kotlin test` never loads `libchdb.so` and the `PackagedChdbNative` resource path
(`NativeChdb.kt:99-157`) is untested in-repo. The test comment mentions
`libchdb.dylib`; the loader requires `libchdb.so`. Extraction to a predictable path
under `java.io.tmpdir` (`:118`) has a TOCTOU window on shared hosts.

**Fix.** Add a host-platform integration test module (or a `check` that runs only when
the matching native module is built) so the packaged path is exercised at least in CI;
fix the comment; consider a per-user subdirectory with `0700` perms for extraction.

---

## Hygiene

### EVAL-15 — Small hygiene items — LOW

- [ ] `project.yaml` `modules:` list is not alphabetised (toolchain INFO on every run).
- [ ] ~32 compiler warnings in `brikk-sql/src` (redundant casts, `?.` on non-null,
      redundant `!!`, always-true conditions) — see `./kotlin build` output.
- [ ] `tools/publish_maven_local.sh:19` says `0.1.0-SNAPSHOT`; template is
      `1.0.0-SNAPSHOT`.
- [ ] `publish-release.sh:31-33` only rejects `-SNAPSHOT`; add a semver regex.
      `sed` edits (`:69-73`) depend on exact 4-space indentation.
- [ ] `brikk-chdb-native-*` publish 340-530 MB jars via the shared template with no
      in-repo consumer; `ATTRIBUTIONS.md` does not attribute the redistributed
      `libchdb.so` (ClickHouse is credited only for the functions TSV).
- [ ] `vendor/README.md` records the upstream git SHA for the Doris jar but not a
      SHA-256 of the jar itself.
- [ ] `HazardsRegistrySyncTest.kt:126-153` pins exact counts (`assertEquals(258, ...)`)
      — brittle; prefer structural assertions.
- [ ] `mise.toml` pins `kotlin = 2.4.0` (standalone compiler) while the build entry
      point is the Toolchain 0.11.0 wrapper; add a comment or drop the pin.

---

## Out of scope for this file

- `sql-compiler-plugin` branch: 4 commits / ~4k lines ahead, 214 commits behind `main`.
  Needs a rebase before it can be evaluated; track separately.
- Anything registered in `docs/brikk-extensions.md`.
