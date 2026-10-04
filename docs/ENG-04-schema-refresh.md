# ENG-04 — offline schema refresh loop

**Status: CLI invalidation verified; live IDE/Doris gates still open.**

There is no Doris connection configuration/private consumer in this checkout and
no running KEFS-enabled IDE. Synthetic tests are not a substitute for either
acceptance gate. ENG-04 stays unchecked.

## Implemented wiring

The local `brikk-engine-kotlin-schema-inputs` Toolchain plugin is applied to
schema-consuming modules, including the public smoke consumer. Its task declares
the DDL file or captured directory as an input and registers a generated Kotlin
source root at `<consumer>/.brikk/schema-inputs` in the Toolchain model. See the
[consumer configuration](schema-cache.md#compiler-input).

The revision is a hash of the **active typing contract**: selected path, legacy
DDL options when applicable, qualified table/slot identities, ordered column
names/types/nullability and quoted identity. SQL 0.17.0 owns snapshot loading,
validation, active generation selection and locking. Engine does not scan archived
generations or copy SQL parsing logic. Capture timestamps, UUIDs and archived
files do not alter the generated revision. Identical revisions leave the source's
bytes and modification time unchanged.

The generated source contains only an opaque revision and module-disambiguating
package. Captured names/types, connection settings and absolute input paths do not
appear in it. Writes are atomic; symlink/unrecognized managed source files refuse
rather than overwrite potential user work. The generated root is ignored by git.

Configuration drift fails early: the tracked selector must match the explicit
compiler `schema` option in `freeCompilerArgs`; DDL dialect/default-schema options
must also match. First-class Maven compiler-plugin option wiring is not inspected
by this initial integration. Missing/malformed inputs fail preparation, so a
successful build cannot silently use an old generated revision.

Ordinary builds run preparation before compilation. Explicit `captureDorisSchema`
also updates the default private consumer's marker after publication and offline
validation, so a registered IDE source root has a change to observe without a
clean build. `refreshSchemaInputs` updates other configured scopes/imports offline.
Capture failure messages distinguish an unpublished capture from a snapshot that
was published before a later validation/invalidation failure.

FIR remains session-local and snapshot-pinned. This integration requests a fresh
module resolution through a changed Kotlin input; it does not mutate already
generated declarations inside a session. Concurrent refresh during a compilation
is not a transactional whole-build guarantee: finish refresh, then build.

## Verified gates

```sh
./kotlin do assemblePluginJar
./kotlin build
./kotlin test
./kotlin do verifySchemaRefresh -m brikk-engine-kotlin-compiler-plugin
./kotlin do verifyIdePlugin -m brikk-engine-kotlin-compiler-ide
./kotlin do refreshSchemaInputs -m brikk-engine-kotlin-smoke
```

- Full assembly/build and **150 tests, zero failures/skips**, including nine new
  schema-input tests. Existing schema/compiler/capture/distribution tests remain
  green. No DB connection occurs during these checks.
- Seven actual incremental Toolchain builds in one isolated synthetic project,
  with unchanged consumer Kotlin source and **no clean/cache deletion**:
  initial, unchanged, identical recapture, type/nullability/column add-remove,
  removed table, malformed snapshot, recovery. The task inspects generated
  `RecordsOut` getter descriptors, not merely timestamps or log messages.
- Changed schema changes getters (`Long` → `String`, nullable `Int` → boxed
  `Integer`) and column inventory; removed tables fail rather than preserving an
  old successful compilation. Malformed active data blocks the build; repair
  recovers. Identical recaptures leave compiled shape and marker timestamps alone.
- The harness stages exact public runtime/schema-plugin sources in a temporary
  project because Toolchain module manifests use relative globs; these copies
  are neither maintained forks nor private content. Logs/`verification.txt` stay
  in the Toolchain task output. The CI test job now runs this incremental gate.
- All seven ENG-03 artifact fixtures remain green on the actual
  `2.4.20-ij262-34` compiler. This does not test IDE cache invalidation.

## Remaining acceptance

### Live IDE

First complete ENG-03 loading acceptance for the IDE's exact compiler. Enable
schema-input tracking on a synthetic/private consumer, reload the Toolchain model
so the generated root is registered, and then:

1. Open a schema-backed query and record generated getters/completion.
2. Refresh the schema, without editing the query, cleaning or restarting the IDE.
   Confirm the revision source changes and completion reflects changed type,
   nullability, added/removed columns and a removed object.
3. Verify an identical recapture does not change the marker. Corrupt/restore only
   a synthetic snapshot, refresh its inputs, and check visible failure/recovery.
   A failed command is not evidence that the IDE discarded an old completion.
4. Preserve IDE/KEFS versions, hashes, screenshots and diagnostic reports. Do not
   interpret compiler subprocess fixtures as this result.

### Target Doris deployment

Provide the ignored environment settings described in
[connection settings](schema-cache.md#connection-settings). Use explicit capture
on the requested catalog/database with a metadata-only account; do not alter
production DDL just to test invalidation. Verify visible relation kinds, native
types, nullability and column order against server metadata, and compile the
resulting shapes for representative wide integers, decimals, dates/times and
nullable columns. Record server/account scope and counts privately, not table
metadata or credentials in public acceptance documents.

The read-only capture protocol has only synthetic evidence here. In particular,
driver MySQL-compatibility version reporting and Doris-specific view/rollup
visibility remain deployment checks. Unsupported types must remain conservative,
not be narrowed merely to make an application compile.
