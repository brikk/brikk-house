# `brikk-chdb`: embedded chDB for Kotlin/JVM

Status: phase-0 implementation — branch `codex/brikk-chdb`. The macOS-arm64
released-library smoke test passes; consumer packaging and Linux smoke tests remain.

## Decision

Create `brikk-chdb` as a **JVM-only Kotlin library** that binds chDB's supported C
ABI through Java's final Foreign Function & Memory (FFM) API. It owns a small,
safe **in-process** Kotlin session/query API. A later `brikk-sql-verify` adapter
will use that session as the ClickHouse grammar verifier.

The module is deliberately independent of `brikk-sql`.  This lets it serve three
consumers without making the parser/transpiler load native code:

1. ClickHouse `EXPLAIN AST` verification in `brikk-sql-verify`.
2. Embedded ClickHouse actions over the files distributed with Trino by the future
   `ducklake-integrations` work.
3. Direct Kotlin applications which need a local ClickHouse execution engine.

### Explicit non-goal: JDBC

`brikk-chdb` is not a JDBC driver, and it does not expose `Connection`,
`Statement`, `PreparedStatement`, `ResultSet`, driver registration, or a
`jdbc:chdb:` URL. Its consumers are Brikk modules in the same process and should
call the small Kotlin API directly.

**TODO(dude): all of JDBC** — if a real external JDBC compatibility requirement
appears later, make it a separate design and module. It needs complete lifecycle,
metadata, result typing/streaming, batching, parameter binding, cancellation,
transactions/unsupported-operation semantics, and framework compatibility; it is
not a thin wrapper over `ChdbSession`.

## Why this binding, not the existing Java projects

The upstream chDB Java-driver request remains open as [chdb issue #243][issue].
It asks for a self-contained JDBC jar, but has no assignee, milestone, or linked
implementation.  chDB's current source identifies `chdb-core` as the component
that builds and ships `libchdb`; language bindings are thin users of that C ABI.

We checked out and reviewed both related prototypes on 2026-07-13:

| Project | Useful finding | Why it is not a dependency/base |
| --- | --- | --- |
| [`linux-china/chdb-java-ffm`][ffm] (last commit 2024-10-17) | Proves final Java FFM can call the older `query_stable_v2` ABI without JNI. | Requires a manually downloaded library, is tied to the old `local_result_v2` layout, parses only JSON text, and implements an incomplete JDBC layer. |
| [`chdb-io/chdb-java`][jni] (last commit 2024-08-23) | Documents the same old ABI and basic JNI plumbing. | Its JNI `DirectByteBuffer` refers to result memory immediately freed before return; its JDBC implementation is incomplete.  It must not be reused. |

Both are Apache-2.0, so they are useful implementation references, not code to
copy.  The current C-facing documentation describes a newer connection/result
API (`chdb_connect`, `chdb_query`, result accessors, result destruction, and
streaming).  Bind the header for the **pinned `chdb-core` release**, rather than
preserving either prototype's `query_stable_v2` struct layout.

## Module and API shape

```text
brikk-chdb                 JVM-only, native boundary and Kotlin query API
  └─ brikk-sql-verify      optional ClickHouse verifier adapter

brikk-sql                  remains pure Kotlin; no native dependency
```

Initial public surface:

```kotlin
interface ChdbSession : AutoCloseable {
    fun query(sql: String, format: ChdbOutputFormat = ChdbOutputFormat.JSON_COMPACT): ChdbResult
}

object Chdb {
    fun open(config: ChdbConfig = ChdbConfig()): ChdbSession
}

data class ChdbResult(
    val bytes: ByteArray,
    val rowsRead: Long,
    val bytesRead: Long,
    val elapsedSeconds: Double,
)
```

`query` throws a typed `ChdbQueryException` for engine errors; it does not return
a nullable result or expose native pointers.  `bytes` is copied from the native
buffer **before** `chdb_destroy_query_result`; no `MemorySegment`/direct buffer
can outlive its native owner.  The caller chooses ClickHouse output format
(`JSONCompact`, `JSONEachRow`, `CSV`, `ArrowStream`, and an explicitly named
escape hatch), allowing Arrow/data-file work without committing the first API to
a Kotlin row model.

`ChdbConfig` contains only explicit, reproducible settings: database path,
engine arguments/settings, and a native-library resolver.  It does not silently
download an executable or native library.

### Native boundary

Use a hand-written, narrowly scoped FFM binding against the pinned header as part
of the build/release process.  Do not check in a large jextract dump and do not
add JNI. The phase-0 implementation binds `chdb_connect`, `chdb_close_conn`,
`chdb_query`, `chdb_destroy_query_result`, and the materialized-result accessors.
The module is pinned to the released `chdb-core` **v26.5.0** header and native
archives (tag `a7bbbbb622e3f87eaa0c3a1dca31ac8461b20c61`). The macOS arm64
archive `macos-arm64-libchdb.tar.gz` was loaded directly and passed the FFM
`SELECT 6 * 7` smoke test. Its SHA-256 is
`86a77d5aa775902740d312153eddfbdc641b8fd5a2e028ddfd18d002ade9ec38`.
The narrow initial
surface intentionally excludes the `*_rows_written` / `*_bytes_written` accessors
that exist on current `main` but are not exported by that release.

- Phase 0 loads exactly one `libchdb` through an explicit path. Classpath native
  resource extraction is deferred until a platform artifact is present.
- Keep the native connection behind `ChdbSession`; serialize calls per session
  until upstream documents a stronger thread-safety contract.
- Convert ABI/null/error values at this boundary and always destroy query results
  in `finally`.
- Fail early with a diagnostic containing OS, architecture, library path, ABI
  pin, and the required `--enable-native-access=ALL-UNNAMED` JVM permission.

The project already runs its verification module on JDK 25, so the final FFM API
is available.  Native access is a runtime requirement, not a reason to introduce
JNI or a JDK-22-only module.

## Native consumer packaging is a release gate

`brikk-chdb` pins `chdb-core` **v26.5.0**. Its GitHub release supplies standalone
dynamic and static `libchdb` archives for Linux x86_64/aarch64 and macOS arm64
(and its release manifest is the checksum authority). It does not publish a
Maven-native artifact that Kotlin Toolchain can select by host. Packaging those
already-built archives for JVM consumers is now the main engineering task, not
the FFM calls.

Phase 0 supports an explicit `brikk.chdb.library` path so the ABI and API can be
proved without hiding packaging work.  A published module is not complete until
one of these is implemented:

1. Brikk-published, platform-specific native resource artifacts with explicit
   consumer selection; or
2. a single verified distribution jar containing the supported platform binaries
   and extracting only the matching binary to a content-addressed temp directory.

Never fetch a mutable `latest` release at application startup.  Every artifact
must record the upstream release, SHA-256, OS, architecture, and ClickHouse
version.  Initial support should be the platforms Brikk can test: macOS arm64,
Linux x64, and Linux arm64; Windows follows only after an upstream native binary
and CI proof exist.

## ClickHouse verifier

`brikk-sql-verify` gains a `ClickhouseVerifier` only after `brikk-chdb` has its
ABI smoke test.  Its basic operation is:

```text
generated SQL -> chDB query("EXPLAIN AST " + SQL, JSON/TSV output) -> VerifyResult
```

`EXPLAIN AST` is the ClickHouse parser and supports all query types.  Expression
fragments are wrapped as `EXPLAIN AST SELECT <expression>`.  Parse errors become
`VerifyResult(accepted = false, error, line, col)`; non-parse engine failures are
reported distinctly so a missing table is not misclassified as a grammar error.

`EXPLAIN SYNTAX` is a separately selectable, stronger smoke test.  It introduces
query-tree analysis and may therefore depend on function availability, settings,
or schemas.  It is not the default grammar gate.

No ClickHouse function catalog is required for this verifier: an AST check is
strictly parser evidence. A caller which later wants function name/arity or
capability evidence can compose a catalog or a stronger analysis mode alongside
the AST result, but that must remain visibly separate from parser acceptance.
Record `SELECT version()` from the loaded engine in verifier diagnostics so the
native-engine version is visible.

## DuckLake-integrations file path

No DuckLake/Trino dependency belongs in this first module. The durable primitive
is a stable in-process ClickHouse session that can run ClickHouse actions over
the data files distributed with Trino by `ducklake-integrations` and return bytes
in Arrow/JSON formats. `ducklake-integrations` later owns file discovery,
credential passing, table-function construction, schemas, and result mapping
above this layer.

## Validation plan

1. **ABI/load** — macOS arm64: passed against the v26.5.0 release archive with
   `SELECT 6 * 7`. Next, assert `SELECT version()` and repeated-call cleanup on
   macOS arm64, Linux x64, and Linux arm64.
2. **Data/result** — query a temporary CSV/Parquet fixture in JSON, JSONEachRow,
   and ArrowStream; validate byte lengths, error handling, and resource closure.
3. **Verifier** — valid/invalid statement and expression fixtures through
   `EXPLAIN AST`; compare error position handling with ClickHouse's output.
4. **Catalog composition** — known and unknown ClickHouse functions: catalog
   finding + AST result are reported as different evidence channels.
5. **Platform CI** — run 1–4 for every published native artifact; verify checksum,
   architecture mismatch diagnostics, and no-network runtime loading.

## Delivery sequence

1. ABI/package spike: pin current `chdb-core`, capture its header and release
   checksums, prove FFM calls with an explicit library path.
2. `brikk-chdb` core: session lifecycle, copied-byte result API, errors, and
   platform-native loader.
3. Publish one supported native platform artifact and prove it in CI; expand the
   matrix before claiming broad portability.
4. Add `ClickhouseVerifier` plus a separate known-failures ledger in
   `brikk-sql-verify`.
5. Add Arrow/data-file conveniences only when the DuckLake/Trino consumer has a
   concrete schema and lifecycle contract.

[issue]: https://github.com/chdb-io/chdb/issues/243
[ffm]: https://github.com/linux-china/chdb-java-ffm
[jni]: https://github.com/chdb-io/chdb-java
