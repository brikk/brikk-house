# brikk-house

SQL parser, transpiler, and optimizer for Kotlin — a multiplatform port of
[sqlglot](https://github.com/tobymao/sqlglot).

## Libraries

The libraries are published under the **`dev.brikk.house`** group. JVM artifacts are available
today; Kotlin Multiplatform artifacts will follow once the toolchain's KMP publishing is
consumable (hence the `-jvm` suffix on the two multiplatform modules).

| Coordinates | Description |
| --- | --- |
| `dev.brikk.house:brikk-sql-jvm` | SQL parser, transpiler, and optimizer (Kotlin port of sqlglot). |
| `dev.brikk.house:brikk-sql-metadata-jvm` | Per-engine SQL function and type metadata catalogs. |
| `dev.brikk.house:brikk-sql-verify` | Native-grammar SQL verification oracles (Trino, DuckDB, Doris). |

### Dialects

Most dialects are faithful ports of the corresponding sqlglot dialect, gated
differentially against the Python oracle. One is **brikk-native**:

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

Current snapshot version: **`0.5.0-SNAPSHOT`**

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
    implementation("dev.brikk.house:brikk-sql-jvm:0.5.0-SNAPSHOT")
    // implementation("dev.brikk.house:brikk-sql-metadata-jvm:0.5.0-SNAPSHOT") // transitive via brikk-sql-jvm
    // implementation("dev.brikk.house:brikk-sql-verify:0.5.0-SNAPSHOT")
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
  <version>0.5.0-SNAPSHOT</version>
</dependency>
```
</details>

### Releases

Release versions (non-`-SNAPSHOT`) are published to **Maven Central** and resolve from
`mavenCentral()` with no extra repository configuration. Latest release: **`0.4.0`**.

## Publishing (maintainers)

The version and all publishing config live in [`publish.module-template.yaml`](publish.module-template.yaml)
(shared by every module). Credentials come from a gitignored `.env` (regular repos) and,
for Central releases, the `KOTLIN_TOOLCHAIN_*` environment variables / org secrets.

### Snapshots

Snapshots publish automatically: **any push to `main`** runs
[`.github/workflows/snapshot.yml`](.github/workflows/snapshot.yml), which builds and publishes
the current `-SNAPSHOT` to the Central snapshots repo.

To **bump the snapshot version** (e.g. after a release):

1. Edit `settings.publishing.version` in `publish.module-template.yaml` — keep the `-SNAPSHOT`
   suffix (e.g. `0.5.0-SNAPSHOT`).
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
   [`.github/workflows/release.yml`](.github/workflows/release.yml), which sets the version from
   the branch suffix and publishes all modules to Maven Central via
   [`publish-release.sh`](publish-release.sh). (The committed template stays on `-SNAPSHOT`; the
   script sets the release version temporarily and restores the file afterward.)
2. Central runs in **manual** mode: finish (or drop) each deployment at
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
