# ENG-03 — compiler distribution and IDE acceptance

**Status: distribution and compiler-API gates implemented; live IDE gate open.**
No IntelliJ process or KEFS installation was available during this work. Passing
an IDE compiler subprocess is not evidence that highlighting, lazy FIR,
completion or hot reload works in the IDE. Do not mark ENG-03 complete yet.

## Explicit compiler targets

| Environment | Compiler | Build/test path | Acceptance |
| --- | --- | --- | --- |
| Toolchain CLI | `2.4.10`, embeddable | Normal compiler-plugin module; shaded JAR consumed by the real smoke build | Verified locally |
| IDE candidate | `2.4.20-ij262-34`, non-embeddable | `brikk-engine-kotlin-compiler-ide`; same plugin sources compiled and artifact fixtures executed by that exact compiler | Compiler/artifact gates verified locally; live IDE unverified |

These are the entire current matrix, not a promise of support for other IDE
versions. The candidate comes from the previously configured KEFS target, not
from detecting a running IDE. Confirm **KEFS: Copy Kotlin IDE Version** before
installing it. A different version requires a reviewed matrix entry and tests.

The IDE compiler and its dependencies are resolved by Kotlin Toolchain from
`https://packages.jetbrains.team/maven/p/ij/intellij-dependencies`. It runs in a
separate JVM, both to build the plugin and to test the finished JAR. The harness
module's normal `settings.kotlin.version: 2.4.10` only builds its Toolchain graph;
it does **not** build the IDE plugin. The task checks the actual subprocess
`kotlinc-jvm` version before compiling sources against that compiler's API.

An exact, fail-closed source adapter handles the IDE API's rename from
`simpleFunctionCheckers` to `namedFunctionCheckers`. Both use the common
`FirDeclarationChecker<FirNamedFunction>` base. The IDE build opts into
`MessageCollectorAccess`; bare configuration registration remains tested. The
existing `PluginGenerated` runtime shape shim is exercised in both environments.
Unknown compiler versions or a changed adapter anchor refuse rather than guessing.

## One relocated artifact

Both environments produce `brikk-engine-kotlin-compiler-plugin-<compiler>-0.2.0.jar`:

- SQL and SQL metadata move from `dev.brikk.house.sql` to
  `dev.brikk.house.sql.compiler.internal.sql`.
- Serialization moves from `kotlinx.serialization` to
  `dev.brikk.house.sql.compiler.internal.kotlinx.serialization`.
- Compiler implementation and public runtime names **do not move**.
  `dev.brikk.house.sql.compiler` remains the compiler option/service identity;
  generated calls still use `dev.brikk.house.sql.runtime.Rel`.
- Kotlin/compiler/platform classes are not bundled. Unexpected dependency
  classes and duplicate class entries fail assembly rather than using first-wins.
- ASM remaps JVM descriptors, signatures, frames, annotations and matching
  reflection class names. Service descriptors/providers are relocated and merged.
  Signed-JAR records and module descriptors are dropped; input notices are retained.
- Kotlin metadata and `.kotlin_module` files are deliberately removed from the
  **compiler artifact**, which is not a consumable Kotlin library. This avoids
  stale encoded Kotlin type names after relocation. Normal runtime/library JARs
  are unaffected. Generated serialization code and JVM signatures remain intact;
  isolated deserialization of a public schema snapshot is tested.

Assembly uses stable ZIP ordering/timestamps. `META-INF/brikk-engine-kotlin-compiler-plugin.build`
records the actual compiler/API JAR identity and SHA-256, input hashes and relocation.
Publication checks this provenance against the filename/requested version. It
cannot publish a CLI binary as an IDE binary. Local publication writes JARs,
POMs, checksums and metadata atomically per file; the POM has no dependency bundle
for KEFS to resolve separately. Plugin log notes include the compiled-code fingerprint
to distinguish same-version rebuilds.

## Local build and publication

From the repository root:

```sh
# CLI and IDE candidate artifacts (or select one module with -m).
./kotlin do assemblePluginJar
./kotlin build
./kotlin test

# Seven finished-artifact fixtures on the actual IDE compiler.
./kotlin do verifyIdePlugin -m brikk-engine-kotlin-compiler-ide

# Local-only Maven publication; no Central/release upload.
./kotlin do publishKefsRepo
```

| Target | Assembled directory | Local Maven repository root |
| --- | --- | --- |
| CLI | `build/plugin` | `build/repo` |
| IDE candidate | `build/plugin-ide/2.4.20-ij262-34` | `build/repo-ide/2.4.20-ij262-34` |

KEFS must use the **IDE candidate's repository root**, not an old relabeled CLI
artifact. Bundle coordinates are
`dev.brikk.house:brikk-engine-kotlin-compiler-plugin`; keep default detection and
replacement patterns. The CLI smoke filename still lets KEFS detect this bundle.
Configure the local repo and library-version matching as described in the
[vendored guide](vendor/kefs/PLUGIN_AUTHORS.md#3-plugin-hot-reload-in-kefs).
Re-run the module-scoped assembly/publication commands after plugin changes.
No user's `.idea` or global IDE settings were edited by this work.

## Automated evidence

- Full assembly/build and **141 tests, zero failures/skips** (nine new tooling
  regressions over ENG-02). The shaded CLI smoke consumer also compiled with a
  fresh build-output directory, not only from an existing compilation cache.
- Seven independent artifact fixtures on `2.4.20-ij262-34`: declaration generation,
  generic named/defaulted refinement and IR execution; expected SQL diagnostics;
  escaped/trimmed/interpolated/Unicode literal ranges; nullability refusal;
  local-shape escape warning; ServiceLoader/bare IDE-like registration; and
  relocated JSON snapshot deserialization with schema-backed generation.
- The compiler subprocess host excludes public SQL/runtime/serialization JARs;
  consumer dependencies cannot mask missing plugin dependencies. The schema unit
  test also uses an isolated classloader that cannot load public SQL/serialization.
- Fixture `verification.txt` contains the compiler version and tested plugin hash;
  compile/run logs live in Toolchain task output. These are not live IDE reports.
- `.github/workflows/test.yml` wires a separate exact IDE-compiler matrix gate and
  local publication check. Local execution is verified; the changed CI workflow
  has not been run remotely as part of this acceptance record.

## Remaining live IDE gate

Record IDE build, Kotlin compiler version, KEFS version, artifact SHA-256 and
screenshots/report paths when performing these checks:

1. Confirm the exact IDE compiler version; build/verify/publish its matching
   artifact. Add the IDE repository root and bundle in KEFS, then refresh plugins.
2. Open the synthetic smoke consumer. KEFS Diagnostics must show the correct
   loaded artifact. Verify `EventsInRangeOut`/`LoginDailyOut`, trait compatibility,
   completion and generic inline chaining without unresolved generated types.
3. Introduce/fix an unbound bind and a scoped column error in a synthetic SQL
   literal. Verify the red underline, message and recovery while editing, not
   just CLI compilation. Include a trimmed template and a generic pipe to
   exercise IDE lazy bodies/refinement.
4. Temporarily change a plugin diagnostic message, rebuild and republish the
   **same** coordinate. Without restarting the IDE, verify that the local repo
   watcher loads the new artifact and shows the new message. Record both hashes
   and loaded-code fingerprints; revert only that temporary test change afterward.
5. Check `idea.log` and `~/.kefs/<compiler-version>/reports/` for linkage,
   cancellation or resolution failures. Preserve reports and screenshots as
   evidence. Only then change the candidate to live-verified and close ENG-03.

ENG-04 schema refresh invalidation is deliberately separate: plugin hot reload
does not establish that changed offline schema snapshots invalidate resolution.
