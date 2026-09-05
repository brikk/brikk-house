# brikk-house

SQL libraries, embedded chDB bindings, and Brikk Engine for typed SQL pipelines in
Kotlin. The generic SQL parser, transpiler, and optimizer is a multiplatform port of
[sqlglot](https://github.com/tobymao/sqlglot).

## Repository layout

The parent directories group modules; Kotlin Toolchain 0.12 derives each module's
name from its leaf directory, with no `name` override. Module selectors such as
`-m brikk-sql` still use that leaf name. [project.yaml](project.yaml) lists the
public modules explicitly.

| Group | Modules |
| --- | --- |
| [Brikk Engine](brikk-engine/README.md) | [brikk-engine-kotlin](brikk-engine/brikk-engine-kotlin/), [brikk-engine-kotlin-compiler-plugin](brikk-engine/brikk-engine-kotlin-compiler-plugin/), [brikk-engine-kotlin-tooling](brikk-engine/brikk-engine-kotlin-tooling/), [brikk-engine-kotlin-smoke](brikk-engine/brikk-engine-kotlin-smoke/) |
| [Generic SQL](brikk-sql/brikk-sql/README.md) | [brikk-sql](brikk-sql/brikk-sql/), [brikk-sql-metadata](brikk-sql/brikk-sql-metadata/), [brikk-sql-verify](brikk-sql/brikk-sql-verify/), [brikk-sql-oracle](brikk-sql/brikk-sql-oracle/) |
| [chDB](docs/design/brikk-chdb.md) | [brikk-chdb](brikk-chdb/brikk-chdb/), [brikk-chdb-native-linux-x64](brikk-chdb/brikk-chdb-native-linux-x64/), [brikk-chdb-native-linux-arm64](brikk-chdb/brikk-chdb-native-linux-arm64/), [brikk-chdb-native-macos-arm64](brikk-chdb/brikk-chdb-native-macos-arm64/), [brikk-chdb-native-packaging](brikk-chdb/brikk-chdb-native-packaging/) |

Brikk Engine keeps one runtime module, `brikk-engine-kotlin`. Database helpers such
as a future `brikk-engine-doris` will be extracted from working migrations, not
created as empty modules. Kotlin packages, `@BrikkSql` and related annotations,
and compiler ID `dev.brikk.house.sql.compiler` are unchanged by the relocation.
Public SQL/chDB Maven coordinates are also unchanged; the assembled compiler and
local KEFS artifact is now `brikk-engine-kotlin-compiler-plugin`.

The ignored `brikk-engine/dogfood/` consumer is private and excluded from the public
manifest. Toolchain has no `project.local.yaml` overlay and an explicit missing
module entry is an error. See the [local add/remove workflow](brikk-engine/README.md#private-consumer)
before enabling it locally. Public builds must work without it; private SQL and
schemas do not belong in public examples or artifacts.

## SQL preservation

Brikk Engine's requirement is to change no more SQL than the requested operation
needs. Same-dialect native SQL without composition or transpilation must run as
written. Parameters, relation slots, and pipe lowering permit only their necessary
changes; cross-dialect translation must be explicit. Parsing for checks does not
require regenerating SQL or applying an optimizer or canonicalization pass.

This is not implemented end-to-end: `Rel.render()` currently reparses and regenerates
all queries. AST round-trip tests do not prove source-text preservation. The
[SQL preservation policy](brikk-engine/README.md#sql-preservation)
requires output-diff checks alongside semantic tests. Pipe lowering is the highest-risk
step; failures need fixes and regressions in `brikk-sql`, not permanent handwritten
SQL copies that bypass the failed lowering.

## Libraries

The libraries are published under the **`dev.brikk.house`** group. JVM artifacts are available
today; Kotlin Multiplatform artifacts will follow once the toolchain's KMP publishing is
consumable (hence the `-jvm` suffix on the two multiplatform modules).

| Coordinates | Description |
| --- | --- |
| `dev.brikk.house:brikk-sql-jvm` | SQL parser, transpiler, and optimizer (Kotlin port of sqlglot). |
| `dev.brikk.house:brikk-sql-metadata-jvm` | Per-engine SQL function and type metadata catalogs. |
| `dev.brikk.house:brikk-sql-verify` | Native-grammar SQL verification oracles (Trino, DuckDB, Doris). |
| `dev.brikk.house:brikk-sql-oracle` | Heavyweight real-engine checks, including PostgreSQL and chDB ClickHouse. |
| `dev.brikk.house:brikk-chdb` | In-process Kotlin/JVM chDB binding. |
| `dev.brikk.house:brikk-chdb-native-linux-x64` | chDB native resource for Linux x64. |
| `dev.brikk.house:brikk-chdb-native-linux-arm64` | chDB native resource for Linux arm64. |
| `dev.brikk.house:brikk-chdb-native-macos-arm64` | chDB native resource for macOS arm64. |

### Dialects

Parse with `read=<dialect>`, generate with `write=<dialect>` (see `transpile(...)`).
Names are case-insensitive; the `""`/`sqlglot` base dialect is the common superset every
other dialect extends. Most dialects are **faithful ports** of the corresponding sqlglot
dialect, gated differentially against the Python oracle; `datafusion` is **brikk-native**
(no sqlglot counterpart).

| Dialect | Aliases | Family / base | Notes |
| --- | --- | --- | --- |
| `sqlglot` | `""` (empty) | — | Base/superset dialect; translation-only, no engine oracle. |
| `mysql` | | MySQL | |
| `doris` | | MySQL → Doris | Apache Doris; ships a version-pinned function catalog. |
| `starrocks` | | MySQL → StarRocks | Version-pinned to **StarRocks 4.1.4** (current 4.1.x). See below. |
| `postgres` | `postgresql` | PostgreSQL | |
| `duckdb` | | DuckDB | |
| `presto` | | Presto/Trino | |
| `trino` | | Presto/Trino | |
| `clickhouse` | | ClickHouse | |
| `hive` | | Hive/Spark | |
| `spark2` | | Hive/Spark | |
| `spark` | `sparksql` | Hive/Spark | |
| `bigquery` | | BigQuery | |
| `datafusion` | `arrow-datafusion` | DataFusion | **brikk-native** — no sqlglot oracle (see below). |

- **`starrocks`** — faithful port of sqlglot's StarRocks dialect (StarRocks extends
  MySQL, **not** Doris — following upstream inheritance), version-pinned to **StarRocks
  4.1.4** (the current 4.1.x patch; source tag `4.1.4` → commit `4a9848e`, Docker
  `starrocks/allin1-ubuntu:4.1.4`). Gated by the full differential suite
  (token/parser/serde/generator/transpile/annotate) and shipped with a version-pinned
  function catalog (820 builtins / 6242 overloads from the live `SHOW FULL BUILTIN
  FUNCTIONS` dump), a **partial** StarRocks↔Doris live semantic behavior-matrix scope
  (92 vectors / 82 concepts; unprobed functions refuse certification), and
  native-grammar verification against the pinned engine. See `vendor/README.md` and
  `docs/research/starrocks-engine-verification.md`.

- **`datafusion`** (alias `arrow-datafusion`) — sqlglot has no DataFusion dialect, so
  this is not a port and has **no sqlglot oracle**. It is gated instead by
  polyglot-derived fixtures + DataFusion `sqllogictest` parse-acceptance + pipe/hand
  assertions; an engine verifier is planned (phase 2). See
  [`docs/brikk-extensions.md`](docs/brikk-extensions.md) §16.

### Snapshots

Snapshots are **published** to the Central Portal snapshots repository:

```
https://central.sonatype.com/repository/maven-snapshots/
```

Current snapshot version: **`1.0.0-SNAPSHOT`**

<details>
<summary>Gradle (Kotlin DSL)</summary>

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
    }
}

dependencies {
    implementation("dev.brikk.house:brikk-sql-jvm:1.0.0-SNAPSHOT")
    // implementation("dev.brikk.house:brikk-sql-metadata-jvm:1.0.0-SNAPSHOT") // transitive via brikk-sql-jvm
    // implementation("dev.brikk.house:brikk-sql-verify:1.0.0-SNAPSHOT")
}
```
</details>

<details>
<summary>Maven</summary>

```xml
<repositories>
  <repository>
    <id>central-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependency>
  <groupId>dev.brikk.house</groupId>
  <artifactId>brikk-sql-jvm</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```
</details>

### Releases

Release versions (non-`-SNAPSHOT`) are published to **Maven Central** and resolve from
`mavenCentral()` with no extra repository configuration. Latest release: **`0.10.0`**.

## Building

Run from the repository root using the pinned Kotlin Toolchain 0.12 wrapper. On a
clean checkout, assemble the compiler plugin before compiling its smoke consumer:

```bash
./kotlin do assemblePluginJar
./kotlin build   # compile every module
./kotlin test    # run every test, including the exact-ledger corpus gates
```

The build config references a gitignored `.env` as the snapshots-repo credentials file.
Keep it present for project-model loading, even for build/test.
On a fresh clone create a placeholder (real credentials are only needed to publish):

```bash
printf 'brikk.mavencentral.user=x\nbrikk.mavencentral.pass=x\n' > .env
```

## Publishing (maintainers)

The version and library publishing config live in [`publish.module-template.yaml`](publish.module-template.yaml).
Non-published public modules use [`publish-targets.module-template.yaml`](publish-targets.module-template.yaml)
for repository validation without enabling publication. Credentials come from a
gitignored `.env` for regular repos and, for Central releases, the
`KOTLIN_TOOLCHAIN_*` environment variables / org secrets.

### Snapshots

Snapshots publish automatically: **any push to `main`** runs
[`.github/workflows/snapshot.yml`](.github/workflows/snapshot.yml), which first runs the full
test suite via [`.github/workflows/test.yml`](.github/workflows/test.yml) (build + every corpus
and verify gate; also runs on every pull request) and, only if green, publishes the current
`-SNAPSHOT` to the Central snapshots repo.

To **bump the snapshot version** (e.g. after a release):

1. Edit `settings.publishing.version` in `publish.module-template.yaml` — keep the `-SNAPSHOT`
   suffix (e.g. `1.0.0-SNAPSHOT`).
2. Update the version in the consumer snippets above in this README.
3. Commit and push to `main` — the workflow publishes the new snapshot.

Publish a snapshot manually (needs `brikk.mavencentral.user`/`brikk.mavencentral.pass` in `.env`):

```bash
./kotlin publish centralSnapshots   # Central snapshots
./kotlin publish mavenLocal         # local ~/.m2 (smoke test)
```

### Releases

A release is cut from a branch named **`release/<version>`** (non-`-SNAPSHOT`):

1. Push a branch `release/<version>` (e.g. `release/0.2.0`). This runs
   [`.github/workflows/release.yml`](.github/workflows/release.yml), which runs the test suite,
   then sets the version from the branch suffix and publishes all modules to Maven Central via
   [`publish-release.sh`](publish-release.sh). (The committed template stays on `-SNAPSHOT`; the
   script sets the release version temporarily and restores the file afterward.)
2. Central validates and publishes each deployment automatically. Track progress at
   <https://central.sonatype.com/publishing/deployments>.
3. Bump `main` to the next snapshot version (see above).

Requires these org secrets: `KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME`,
`KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD`, `KOTLIN_TOOLCHAIN_SIGNING_KEY`,
`KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE`. (Toolchain **0.11** reads the Central creds under the
**no-underscore** `MAVENCENTRAL` spelling; newer versions use `MAVEN_CENTRAL`. The workflow and
`publish-release.sh` set both spellings, so either secret name works.)

Release locally instead of via CI:

```bash
export KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME=...   # Central Portal token user (0.11 spelling)
export KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD=...   # Central Portal token password
export KOTLIN_TOOLCHAIN_SIGNING_KEY="$(cat signing-key.asc)"   # ASCII-armored private key
export KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE=...   # if the key is encrypted
./publish-release.sh 0.2.0
```

On Toolchain 0.12 the release launcher invokes the eight public libraries' Portal
tasks directly because `./kotlin publish mavenCentral` fails repository validation.
Keep that explicit task list in sync when adding publishable libraries. Engine
modules and private consumers are excluded. CI checks Maven Local publication;
it does not exercise Central credentials, signing, or remote bundle acceptance.

## Brikk Engine development

```bash
./kotlin do publishKefsRepo     # the whole IDE loop: compile plugin -> assemble jar -> build/repo
./kotlin do assemblePluginJar   # just the -Xplugin jar the smoke module consumes
./kotlin check                  # everything (needs the jar above once; build/ persists)
./kotlin show commands          # lists both
```

The assembled JAR is
`build/plugin/brikk-engine-kotlin-compiler-plugin-2.4.10-0.2.0.jar`. KEFS uses
`dev.brikk.house:brikk-engine-kotlin-compiler-plugin:<ide>-0.2.0` from `build/repo`.
Update an existing KEFS bundle to the new artifact ID and rebuild; the compiler's
`-P plugin:dev.brikk.house.sql.compiler:...` option prefix stays unchanged.

See [Brikk Engine](brikk-engine/README.md) and the [current wiring notes](docs/virtual-pipelines-wiring.md). The
[review guide](docs/REVIEW-compiler-plugin.md) and [findings](TODO-review-findings.md)
record the pre-relocation review, with paths updated for navigation.
