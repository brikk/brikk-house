# Doris consumer verification

This local, pre-publication gate runs the actual client's `DorisPipesUpgradeTest`
and `DorisPipesActionTest` against explicit candidate core and metadata JARs. It
does not publish, build upstream, or write into the active client checkout.

## Build and run

Build the candidate after finishing upstream edits. From the upstream root, use
the checked-in wrapper's task-graph command:

```sh
./kotlin task :brikk-sql:jarJvm :brikk-sql-metadata:jarJvm
```

Toolchain 0.12.0 supports `task` even though the top-level help omits it.
`package -f jar` does not register packaging tasks for these library modules.
Pass the freshly built artifacts, not an older release or Maven-local copy:

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
bash tools/verify_doris_consumer.sh \
  --client /absolute/path/to/doris-intellij-plugin \
  --core-jar "$PWD/build/tasks/_brikk-sql_jarJvm/brikk-sql-jvm.jar" \
  --metadata-jar "$PWD/build/tasks/_brikk-sql-metadata_jarJvm/brikk-sql-metadata-jvm.jar" \
  --scratch-dir /tmp
```

`--upstream ROOT` is optional and defaults to the script's parent checkout.
`--scratch-dir` is an existing parent outside the client checkout, not a reusable
run directory. The upstream's ignored `build` directory is also accepted when
temporary storage has a restrictive quota. Every invocation retains a private `doris-consumer.*`
child. Paths with spaces work. Bash, Git, standard Unix utilities, JDK 21, and
either `sha256sum` or `shasum` are required. No Python or global mise is needed.
Alternatively, from the client directory, let its mise configuration select Java:

```sh
mise exec -- bash /absolute/path/to/brikk-house/tools/verify_doris_consumer.sh \
  --client "$PWD" \
  --core-jar /absolute/path/to/brikk-house/build/tasks/_brikk-sql_jarJvm/brikk-sql-jvm.jar \
  --metadata-jar /absolute/path/to/brikk-house/build/tasks/_brikk-sql-metadata_jarJvm/brikk-sql-metadata-jvm.jar
```

The gate invokes the copied client `gradlew`, pins `JAVA_HOME` for the Gradle and
test JVMs, and sets `-Ptest.sqlTranspiler=absent`. Dependencies and the client's
pinned IDE SDK download into scratch, so allow network access and disk space.
It disables configuration/build caches and Kotlin incremental/daemon compilation.
Gradle home, project/build outputs, Java temp/home, Kotlin state, XDG directories,
and IntelliJ downloads/sandboxes stay under scratch. No live DB, PluginVerifier,
publication, or other test suite runs. Compilation still includes client sources
and test sources required by its ordinary `test` task.

## Snapshot and evidence

The allowlist covers `src/`, `vendor/`, `gradle/`, optional `scripts/`, root Gradle
build/settings files and wrappers, plus `LICENSE`, `NOTICE`, and
`THIRD_PARTY_NOTICES.md`. It copies current bytes, including dirty and untracked
inputs and ignored vendored JARs. It excludes hidden entries such as `.env`,
`.git`, `.idea`, `.gradle`, `.kotlin`, and `.intellijPlatform`, all build/cache
directories, credential/key filenames, and non-allowlisted file types. Symlinks
are rejected. Gradle properties accept only reviewed non-secret settings; wrapper
properties accept only the public Gradle distribution and checksum settings.
`omitted-inputs.txt` lists other omitted candidates. Review it if inputs change.

Only run trusted client build code and review new allowlisted sources/notices for
embedded secrets first. Filename allowlisting is not a secret scanner or an OS
sandbox. No environment, credentials, user Gradle properties, or user init scripts
are forwarded. Avoid editing client inputs while they are being copied; the
snapshot is not an atomic filesystem snapshot.

Scratch retains the client Git SHA and status with filenames only, SHA-256
manifests for copied inputs, tooling, candidate JARs and JUnit reports, the final
test classpath, `gradle.log`, and XML/HTML reports. JAR/input manifest hashes also
go to stdout. The gate excludes both root and JVM Maven coordinates in every
configuration and injects file dependencies. It verifies any IntelliJ sandbox
copies against the pinned hashes, normalizes them to exactly the two candidate
JAR paths, and rejects published coordinates or other Brikk bytecode.

Success requires both suites, every current method found in their sources, zero
failures/errors/skips, and the explicitly pinned lateral/refusal/production-dispatch
methods. Missing or renamed required methods fail closed. Exit zero plus `PASS`
is the result; a Gradle success message alone is not sufficient. Failed runs keep
their evidence. Remove only the reported scratch child when finished with it.

## Gates and limits

Accepted SQL needs native Doris grammar acceptance, strict column qualification
and exact output shape, then execution evidence. Parsing alone is not semantic
proof. The lateral cases require `e.item` to resolve and `t.*` to exclude lateral
columns across an input boundary, or an explicit handled refusal. Success through
the refusal branch does not establish lateral execution support.

Expected `UnsupportedError` is caught narrowly by the adapter and becomes
`Transpile.Err`. Production `dispatchPipeTranslation` must report it without
submission or predecessor delegation across all four action variants. Unexpected
failures must not be disguised as supported SQL by broadening that adapter catch.
These tests exercise the production dispatch helper with callbacks, not a live
JDBC console. The existing outer interceptor's broad failure/delegation fallback
is a separate risk and is not cleared by this gate.

This gate does not prove server row semantics, live execution, every PIPE shape,
full plugin-classloader isolation, companion coexistence, distribution contents,
IDE compatibility across versions, or published POM/transitive-dependency
correctness. Known-defect characterization tests can also pass. Keep upstream
semantic/execution gates and release verification separate.

The 2026-09-08 candidate run against client commit
`49caa9a8f187367393852e71cde4fb0de4696beb` ran all 36 upgrade and 9 action tests.
It failed only B14's old expectation of warning-free invalid RENAME SQL; the adapter
now returns the intended handled refusal. This is not a passing consumer gate.
See the [PIPE handoff results](doris-pipe-handoff.md) for candidate hashes, reports,
and the required downstream assertion change. Every subsequent run must establish
its own success via the retained `PASS` marker and reports.

The adapter/dispatch changes and upgrade tests now have a committed client baseline.
Mandatory downstream CI is still not enabled. Pin a reviewed client revision with
the updated B14 contract and define candidate-build provenance before enabling the
cross-repository job. The upstream boundary matrix already runs in the existing
test workflow required by releases; it does not replace the downstream adapter check.
