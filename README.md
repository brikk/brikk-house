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

### Snapshots

Snapshots are **published** to the Central Portal snapshots repository:

```
https://central.sonatype.com/repository/maven-snapshots/
```

Current snapshot version: **`0.2.0-SNAPSHOT`**

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
    implementation("dev.brikk.house:brikk-sql-jvm:0.2.0-SNAPSHOT")
    // implementation("dev.brikk.house:brikk-sql-metadata-jvm:0.2.0-SNAPSHOT") // transitive via brikk-sql-jvm
    // implementation("dev.brikk.house:brikk-sql-verify:0.2.0-SNAPSHOT")
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
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```
</details>

### Releases

Release versions (non-`-SNAPSHOT`) are published to **Maven Central** and resolve from
`mavenCentral()` with no extra repository configuration. Latest release: **`0.1.0`**.
