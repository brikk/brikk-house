# Offline schema capture

`captureDorisSchema` is an explicit, Doris-specific Toolchain command. It captures
the relations visible to a connection in one catalog/database and produces an
offline cache for the Kotlin compiler plugin. It is not part of build, test, or
publication, and execution avoidance is disabled: every invocation recollects
metadata.

No database row data is queried. The command uses fully qualified `SHOW FULL
TABLES` and `SHOW COLUMNS`, without `USE`, `SWITCH`, or refresh commands. Only
relation name/kind and column name/native SQL type/nullability are retained.
Column order follows the server's schema order. Raw DDL, defaults, property bags,
connection URLs, usernames, and passwords are not written into the snapshot.

The native SQL type is distinct from the JDBC transport type. Doris can send
`LARGEINT` values as `CHAR` through the MySQL protocol. The compiler maps the
normalized SQL type `INT128` to Kotlin `BigInteger`, not `Long` or `String`.
Unsigned `BIGINT` also requires `BigInteger`; unsigned `INT` requires `Long`.
A future result decoder must use the SQL contract rather than infer numeric
width from `ResultSetMetaData.getColumnClassName()` alone.

## Connection settings

Keep settings in an ignored `.env.doris` file. Do not put credentials in SQL,
module YAML, shell history, or a JDBC URL. The required environment variables are:

```sh
DORIS_JDBC_URL='jdbc:mysql://HOST:9030'
DORIS_USER='...'
DORIS_PASSWORD='...'
DORIS_CATALOG='internal'
DORIS_SCHEMA='...'
BRIKK_SCHEMA_CACHE_DIR='schema-cache'
```

Doris's database is the schema level in the cache. The connection uses its MySQL
protocol, normally port 9030. The initial command supports a single-host MySQL
JDBC URL. The driver is a dependency of build tooling only, not the runtime or
the assembled Kotlin compiler plugin.

Relative cache paths resolve under this worktree's `dogfood`.
The default is `schema-cache`. A project-relative path beginning with
`dogfood` is also accepted. Absolute paths must stay below that same
private directory; parent traversal and managed symlink paths are rejected.
Using a connection file in another worktree does not change the output base.

From the repository root, source the settings in a subshell without tracing:

```sh
(
  set +x
  set -a
  source /path/to/.env.doris
  set +a
   ./kotlin do captureDorisSchema -m brikk-engine-kotlin-compiler-plugin
)
```

The command prints counts, not SQL, object names, credentials, or raw driver
errors. A failed connection reports its phase, SQLState, and vendor error code.
It must not activate an incomplete capture. Correct the cause and invoke the
same command again to refresh.

After successful publication/offline validation, capture also atomically updates
the private consumer's `.brikk/schema-inputs/BrikkSchemaInput.kt` revision source.
If this later step fails, the error explicitly says the snapshot was already
published; retry or run the offline revision refresh before trusting completion.

## Cache layout

```text
schema-cache/
  .schema-cache.lock
  <catalog>/
    <schema>/
      _snapshot.json
      .snapshots/
        <generation-uuid>/
          objects/
            <table-or-view>.json
```

Path components use reversible UTF-8 percent encoding; identifiers remain intact
inside JSON. Each object record has a format version, source dialect, qualified
identity, the reported relation kind, and ordered columns. Native SQL types are
preserved verbatim. The loader normalizes supported types for analysis and uses
`UNKNOWN` for unsupported types rather than inventing a scalar representation.

The manifest records the active generation, object inventory, capture time,
driver-reported server version, and a credential-free endpoint fingerprint.
That reported version may be Doris's MySQL compatibility version rather than its
full release/build identifier. One output root belongs to one endpoint; choose a
different root when intentionally capturing a different endpoint.

Records are written into a new immutable generation. An atomic manifest
replacement activates the completed capture. Readers and writers coordinate
through the root lock. Removed objects disappear from the active inventory;
old generations remain for safety and are not automatically garbage-collected.
Keep the whole cache tree, including its lock and hidden generation directories,
when moving a snapshot.

## Compiler input

The `schema` option accepts a DDL file or a captured directory. Enable the local
schema-inputs plugin on every consumer that uses it:

```yaml
plugins:
  brikk-engine-kotlin-schema-inputs:
    enabled: true
    schemaPath: dogfood/schema-cache

settings:
  kotlin:
    freeCompilerArgs:
      - -Xplugin=build/plugin/brikk-engine-kotlin-compiler-plugin-2.4.10-0.2.0.jar
      - -P
      - plugin:dev.brikk.house.sql.compiler:schema=dogfood/schema-cache
```

A full cache root, catalog directory, or schema directory can be selected. JSON
snapshots describe their own dialect and qualified identities; `schemaDialect`
and `defaultSchema` continue to apply only to legacy DDL files. Relative paths
use source-file ancestors before the working-directory fallback. Absolute paths
are preferable for IDE contexts that cannot provide source anchors.

Compilation does not connect to the database. Missing or malformed snapshots are
compiler diagnostics, or preparation failures for integrated consumers. Resolution is session-local; one project's relative schema
option must not reuse another project's resolved path.

The root project already registers `brikk-engine-kotlin-schema-inputs`. Its
`prepareSchemaInput` task declares the schema file/directory as an `@Input` and
registers `.brikk/schema-inputs` as **generated Kotlin sources** in Toolchain's
project model. The task runs before compilation and emits an opaque, deterministic
revision of the active typing contract. Changed types, column order/names,
nullability, quoted identities or table inventory change that source; identical
recaptures, timestamps, generation UUIDs and archived JSON do not.

The task requires an explicit compiler `schema` option in `freeCompilerArgs` and
checks it against `schemaPath`. For DDL, configure matching `schemaDialect` and
`defaultSchema` in both places. Captured directories ignore those legacy options.
First-class Maven compiler-plugin options are not inspected by this integration;
the current supported consumer wiring is the explicit `freeCompilerArgs` form.

An integrated **CLI consumer no longer needs `clean` after refresh**. A normal
build notices the changed generated source and recompiles with a fresh FIR session.
Malformed inputs fail the preparation task instead of silently compiling against
an old revision. FIR still pins one loaded catalog per session: this does not
mutate shapes that were already generated within an existing session.

For externally refreshed/imported snapshots, and to update an IDE's registered
source root without a build, use:

```sh
./kotlin do refreshSchemaInputs -m dogfood
```

Explicit capture updates the default private root automatically. If the consumer
selects a narrower catalog/schema scope or a different schema input, refresh its
configured marker afterward. All generated markers are ignored by git and contain
only a module-disambiguating package and revision, not captured metadata/credentials.

This is an **IDE invalidation candidate**, not live completion acceptance. A
registered Kotlin constant changes out-of-block source state, but KEFS lazy FIR,
completion recovery and the IDE's external file refresh still require live checks
(ENG-03 is also open). Unintegrated consumers still need a forced new compilation;
do not extend the no-clean guarantee to raw `-P schema=...` alone.

Acceptance and remaining deployment/IDE checks:
[ENG-04 refresh loop](ENG-04-schema-refresh.md).

## Initial limits

- Metadata reflects objects visible to the supplied account. Permissions can
  hide objects; successful capture does not prove unrestricted server visibility.
- Async materialized views may be reported as `BASE TABLE`. Synchronous rollup
  indexes are not separately enumerated relations. The raw reported kind is kept.
- Empty column metadata for an enumerated relation fails capture instead of
  asserting an empty schema. Unknown nullability remains unknown.
- Enumeration is repeated after column capture to detect inventory changes.
  This is not a transactional metadata snapshot: same-name DDL changes can still
  occur while metadata is being read.
- Statements and socket reads have a 60-second timeout. Capture rejects more than
  10,000 relations, 10,000 rows in one metadata result, or 100,000 total columns.
  It does not silently truncate results.
- External-catalog metadata access can contact backing systems. This command does
  not issue source-data queries or refresh operations to repair missing metadata.
- Synthetic capture, refresh, and compiler tests do not substitute for validating
  the metadata protocol against the user's Doris deployment.
