---
name: kotlin-toolchain
description: How to build, run, test, package, lint/check/verify, manage dependencies, and configure Kotlin/Java projects with Kotlin Toolchain — JetBrains' unified Kotlin CLI (formerly Amper, now the engine inside the Toolchain). TRIGGER when the repo contains `project.yaml`, `module.yaml`, or a `./kotlin` wrapper; when the user asks to build/run/test/package a Kotlin project, add or remove dependencies, run lint/check/verification, or otherwise configure a Kotlin project that doesn't use Gradle/Maven; or when the user references Kotlin Toolchain or Amper. SKIP for Gradle/Maven Kotlin projects.
---

# Kotlin Toolchain

JetBrains' unified CLI entry point for Kotlin (JVM, Android, iOS, multiplatform) and Java projects, announced at KotlinConf'26 (May 2026, currently in Alpha). Uses declarative YAML configuration instead of Gradle build scripts. Amper, previously a standalone build tool, is now the build engine inside Kotlin Toolchain. Configuration still uses `project.yaml` and `module.yaml`, but schema and defaults can change between releases.

**Last verified: 2026-10-04 against Kotlin Toolchain 0.13.0.** Inspect the project's wrapper version and use its `--help` output before relying on newer documentation. Do not update wrappers or compiler/dependency versions unless the task calls for it.

## Installation

If the project already has `kotlin` / `kotlin.bat` wrappers, no global installation is needed: run `./kotlin` from the project root (or `kotlin.bat` on Windows). The wrapper provisions the CLI runtime and distribution; the toolchain selects or provisions the build JDK according to module settings.

For a global installation, the `kotlin` command is added to `PATH`:

```sh
# SDKMAN (macOS / Linux / WSL)
sdk update
sdk install kotlintoolchain

# macOS / Linux
curl -fsSL https://kotl.in/install.sh | sh

# Windows (PowerShell)
powershell -ExecutionPolicy ByPass -c "irm 'https://kotl.in/install.ps1' | iex"
```

The globally installed Kotlin Toolchain `kotlin` wrapper searches the current directory and its ancestors for a project (`project.yaml` or `module.yaml`) with a local `kotlin` wrapper. It reads that wrapper's version and checksum and uses the project's pinned distribution, not necessarily its global version. Prefer explicit `./kotlin` from the project root to avoid ambiguity with other programs named `kotlin` on `PATH`. Examples below use the global Toolchain command; substitute `./kotlin` when a local wrapper exists.

## CLI Commands

```sh
kotlin init                     # Create a new project from templates
kotlin build                    # Compile and link all code
kotlin run                      # Run the application
kotlin test                     # Run all tests
kotlin check                    # Run tests + all registered checks (lint, API verification, etc.)
kotlin package                  # Package applications; JVM apps get self-contained executable JARs
kotlin publish <repoId>          # Publish enabled library modules to a configured repository
kotlin clean                    # Remove build output and caches
kotlin show modules             # List project modules
kotlin show settings -m app     # Show a module's effective settings
kotlin show dependencies        # Show dependency tree
kotlin show checks              # List registered checks
kotlin show commands            # List registered custom commands
kotlin show tasks               # List tasks and their dependencies
kotlin do <command>             # Run a registered custom command
kotlin task <taskName>           # Run a task by its fully qualified name
kotlin update                   # Update Kotlin Toolchain to the latest version
kotlin generate-completion bash # Create completion scripts (bash, zsh, or fish)
```

All commands support `-h`/`--help` for detailed options. Use `-m`/`--module` where supported to scope work to a module. `update` changes the CLI scripts/distribution; it is not a routine build step.


## Project Structure

```
project-root/
├── kotlin, kotlin.bat     # Local wrappers pin the Toolchain version and checksum
├── project.yaml           # Module list and project-wide plugin registration
├── common.module-template.yaml # Optional shared module settings
├── libs.versions.toml     # Version catalog (Gradle-compatible)
├── module-name/
│   ├── module.yaml        # Module configuration
│   ├── src/               # Production sources (Kotlin + Java mixed)
│   ├── resources/         # Resources (copied into JAR)
│   ├── test/              # Test sources
│   └── testResources/     # Test-only resources
└── another-module/
    ├── module.yaml
    └── ...
```

For single-module projects, `module.yaml`, `src/`, and `test/` live at the project root.

The default module layout is `layout: default`. JVM-only `jvm/app` and `jvm/lib` modules can opt into `layout: maven-like` (`src/main/kotlin`, `src/main/java`, `src/test/kotlin`, etc.). Follow the module's actual layout rather than assuming `src/` and `test/`.

## Configuration Files

### module.yaml

Central config per module. Key sections:

```yaml
product: jvm/app    # Other types include jvm/lib, android/app, ios/app, and kmp/lib

dependencies:
  - org.example:artifact:1.0.0           # Maven coordinates
  - //other-module                     # Module dependency (project-root-relative)
  - $libs.ktor.client                   # From version catalog
  - bom: io.ktor:ktor-bom:3.6.0          # BOM import
  - org.example:foo:1.0.0: exported      # Exposed to dependents (like Gradle api())
  - org.example:bar:1.0.0: compile-only  # Compile-only scope
  - org.example:baz:1.0.0: runtime-only  # Runtime-only scope

test-dependencies:
  - io.mockk:mockk:1.13.0

settings:
  jvm:
    mainClass: org.example.MainKt   # Optional override; default: main() in main.kt
    jdk:
      version: 21
  kotlin:
    languageVersion: "2.4"          # Optional source compatibility, not compiler version

test-settings:
  kotlin:
    languageVersion: "2.4"

repositories:
  - https://repo.example.com/maven    # Optional custom repository; replace with a real URL
```

Prefer `//path/from/project/root` for module and template references. Explicit relative paths (`./nested`, `../sibling`) are supported but discouraged for module dependencies. Dependencies must be modules within the same project. Maven Central and Google repositories are provided by default; do not add obsolete Compose repository URLs by habit.

`settings.kotlin.version` selects the compiler and standard library; `languageVersion` and `apiVersion` constrain compatibility. Preserve existing compiler pins, especially when compiler plugins depend on that compiler's API. Enable Compose only in modules that use it, with `settings.compose: enabled` or its full form.

### project.yaml

Lists module directories and registers available local build plugins (and experimental Maven plugins). Module-list paths are relative to the project root, without a `//` prefix. Globs such as `libs/*` are supported; recursive `**` globs are not. A root `module.yaml` is included implicitly.

```yaml
modules:
  - app
  - shared
```

The Toolchain version is pinned in the wrapper, not `project.yaml`. Share module settings, dependencies, or repositories via `<name>.module-template.yaml` files, applied in each module:

```yaml
apply:
  - //common.module-template.yaml
```

Templates cannot declare `product`. Inspect the merged configuration with `kotlin show settings -m <module>`.

### libs.versions.toml

Uses the Gradle version catalog format, but only `[versions]` and `[libraries]` are supported, not `[bundles]` or `[plugins]`. Use one catalog, either at the root or at `gradle/libs.versions.toml`, not both. Referenced in module.yaml as `$libs.<key>`. Built-in toolchain catalogs include `$kotlin.*` and `$compose.*`, with versions derived from settings.

## JDK Selection

Configure the build JDK under `settings.jvm.jdk`:

```yaml
settings:
  jvm:
    jdk:
      version: 21
      distributions: [temurin, zulu] # Optional ordered vendor allowlist
      selectionMode: auto
```

- `auto` (default): use `JAVA_HOME` if it matches the requirements; otherwise provision a JDK.
- `alwaysProvision`: ignore `JAVA_HOME` and use a matching provisioned JDK.
- `javaHome`: require a matching `JAVA_HOME`; fail instead of provisioning a fallback.

Use `settings.jvm.release` to specify the minimum compatible JVM release independently of the build JDK. Configure exact externally managed JDK builds with `JAVA_HOME` and `selectionMode: javaHome` only when required; do not manually install a JDK by default. `KOTLIN_CLI_JAVA_HOME` controls the CLI's own runtime, not the build JDK.

## Testing

- Default framework: [kotlin.test](https://kotlinlang.org/api/latest/kotlin.test/) (preconfigured — no extra dependency needed)
- JVM/Android tests use JUnit 5 by default; `settings.junit` accepts `junit-5`, `junit-4`, or `none`.
- Test sources live in `test/`
- Test dependencies go in `test-dependencies:`
- Test-specific settings go in `test-settings:`

## Checks and Linters

`kotlin check` runs all tests plus every registered check. Filter with named checks (`kotlin check detekt apiCheck`), skip with `--skip <name>` (e.g. `--skip tests`), or restrict to modules with `-m <module>` (repeatable). Use `kotlin show checks` to list what's registered. A check fails when its underlying task throws.

Kotlin Toolchain ships **no bundled linters** — `tests` is the only built-in check. Tools like detekt, ktlint, or API-compatibility verification are not preinstalled: register them as local-plugin tasks under `checks:` in `plugin.yaml`. Do not invoke linter binaries directly or wire in a Gradle plugin — both bypass the toolchain's check pipeline.

## Multiplatform

Use `kmp/lib` for a multiplatform library and explicitly list its platforms:

```yaml
product:
  type: kmp/lib
  platforms: [jvm, android, iosArm64, iosSimulatorArm64]
```

Platform-specific code uses `@platform` directory suffixes: `src@jvm/`, `src@ios/`, `src@android/`, etc. Common code in `src/` is visible to all platform-specific directories, but not vice versa.

Platform-specific dependencies and settings use the `@platform` qualifier:

```yaml
dependencies@android:
  - androidx.core:core-ktx:1.12.0
```

Android modules must configure a valid `settings.android.namespace`; application modules also need an `applicationId` (which can be derived from the namespace). Do not rely on an old hardcoded namespace default.

## iOS apps

For an `ios/app` module, a Mac with Xcode is required. Keep Swift sources in `src/`, with a `@main` entry point. Toolchain generates and manages `module.xcodeproj` beside `module.yaml`; the default target is `app`. If the project is absent on first build, it creates a default project and writes a **complete** `src/Info.plist` if that plist is also absent. The generated project sets `INFOPLIST_FILE` without enabling Xcode's `GENERATE_INFOPLIST_FILE`, so the plist must be self-contained.

Two consequences worth knowing:

- **A pre-existing `Info.plist` is used as-is and never completed.** Toolchain writes its default plist *only* when no `Info.plist` exists. If you supply your own, it must itself contain the required `CFBundle*` keys (`CFBundleIdentifier`, `CFBundleExecutable`, `CFBundleName`, …). A partial plist produces an `.app` with no bundle id, and the simulator refuses to install it:

  ```
  Simulator device failed to install the application. Missing bundle ID.
  ```

  Fresh templates include a complete plist. When migrating an existing project, check its plist rather than assuming Toolchain fills in missing keys.

- **Existing Xcode projects are validated and their managed integration can be updated, but are not fully regenerated on every build.** Preserve custom signing, bundle identifiers, and other Xcode configuration. Do not delete a customized or tracked project to force regeneration; inspect and back it up, and obtain approval before replacing it.

Existing projects need a single iOS application target, Debug/Release configurations with `KOTLIN_CLI_WRAPPER_PATH`, and the managed `Build Kotlin` phase invoking `"${KOTLIN_CLI_WRAPPER_PATH}" tool xcode-integration`. Use Xcode's archive/export workflow for App Store distribution, not Maven library publishing.

## Built-in Processing and Compiler Plugins

Use declarative built-in integrations before writing a custom build plugin:

- **KSP2:** configure `settings.kotlin.ksp.processors`, with Maven coordinates, catalog entries, or local module references. Pass options through `processorOptions`; set `version` only when necessary. KSP1 is not supported. In KMP, generated sources are available to their platform compilation, not common or intermediate fragments.
- **Java annotation processing:** configure `settings.java.annotationProcessing.processors` and `processorOptions` for JVM/Android modules. Do not assume this provides Kotlin KAPT support.
- **Built-in compiler plugins:** use settings such as `settings.kotlin.serialization`, `allOpen`, `noArg`, `powerAssert`, or `settings.compose`, as appropriate.
- **Third-party compiler plugins:** use `settings.kotlin.compilerPlugins`, not the library's Gradle plugin:

```yaml
settings:
  kotlin:
    compilerPlugins:
      - id: org.example.my.plugin
        dependency: org.example:my-plugin:1.0.0
        options:
          myKey: myValue
```

The `id` comes from the compiler plugin's `CommandLineProcessor.pluginId`; the artifact must be compatible with the module's compiler version. IDE support is best-effort and may require the Kotlin External FIR Support (KEFS) plugin with a separately compatible artifact. A successful CLI build does not prove IDE compatibility.

## Plugins

If a behavior is not natively supported by declarative settings, use Kotlin Toolchain's **local build plugin** system to extend the build. Build plugins are distinct from Kotlin compiler plugins.

1. Create a module with `product: jvm/amper-plugin` (this product name still contains `amper` in 0.13.0).
2. Include it in `project.yaml` under `modules`, and register it under `plugins` with a module reference such as `//build-logic`.
3. Enable it in each consumer module with `plugins: { build-logic: enabled }` (or the full settings form).
4. Implement Kotlin `@TaskAction` functions, annotate path inputs/outputs with `@Input`/`@Output`, and register tasks in `plugin.yaml`.
5. Register generated code/resources via `generated.sources` / `generated.resources`; register verification tasks via `checks`. Declaring a task alone does not automatically run it during compilation.

Local plugins can depend on regular modules, but must not introduce task dependency cycles. Plugin publication is not supported yet. Inspect registered checks/commands with `show checks` / `show commands`; run standalone tasks with `kotlin task :<module>:<task>@<pluginId>`.

Do **not** try to reuse or adapt a Gradle build plugin inside a Kotlin Toolchain project. Use built-in integrations or reuse its underlying library/tool through a local build plugin for unsupported build logic; do not reimplement an existing generator just because its usual wiring uses Gradle.

When a library's standard workflow includes build-time processing (code generation, schema compilation, resource transformation, etc.), preserve that workflow using a built-in integration where available, or a local build plugin for custom behavior. Do not bypass processing by hand-writing the would-be-generated code or using a degraded/runtime-only mode. Reuse the underlying generator/compiler-plugin library rather than reimplementing it when possible.

### Experimental Maven plugins (JVM only)

Toolchain can run Maven plugin goals in `jvm/app` / `jvm/lib` modules. This is an experimental, best-effort integration, not a switch to a Maven build. Register coordinates under `project.yaml`'s `mavenPlugins`, then enable/configure `<pluginArtifactId>.<goal>` under the consumer's `mavenPlugins`. Goals with supported default lifecycle phases can run automatically, including generated-source integration; goals without a default phase need explicit wiring or invocation. Use `kotlin task :<module>:<pluginArtifactId>.<goal>` to run a goal explicitly. Prefer built-in settings or local plugins when Maven-plugin limitations would compromise the workflow.

## Packaging and Publishing

- `kotlin build` produces regular module artifacts. For JVM apps, `kotlin package` creates a self-contained executable JAR with nested dependency JARs (the Spring Boot loader format, even for non-Spring apps); do not assume an unpacked/merged fat JAR.
- For library publication, enable `settings.publishing` with a `group` and `version`, configure a repository with `publish: true`, then use `kotlin publish <repoId>`. Publication is still in preview.
- Local publication uses a repository with `url: mavenLocal` and `publish: true`, then `kotlin publish mavenLocal`.
- If a published module depends on local modules, those dependencies also need publication configuration. Use `--transitive` when publishing selected modules to include their local dependencies.
- Maven Central needs its own publication configuration, POM metadata, sources, signing, and credentials; consult the publishing reference. Never put credentials or private signing keys in tracked files, and do not publish externally unless requested.

## Build Tool Policy

Treat Kotlin Toolchain as a fixed project requirement. Do not propose switching to Gradle or re-open the Kotlin Toolchain/Gradle tradeoff because a library is more commonly used with Gradle. When build-time processing is needed, implement it within the Kotlin Toolchain workflow using built-in support or an appropriate integration. Keep discussion focused on the chosen approach rather than alternative build systems, unless the user explicitly asks.

## Key Conventions

- Kotlin and Java sources can be mixed freely in the same `src/` folder
- Default entry point: `main()` in `main.kt` (compiles to `MainKt` class)
- Top-level functions in `myFile.kt` compile to class `MyFileKt`
- `exported` dependencies expose types to downstream modules; only mark `exported` if your public API uses those types

## Common Pitfalls

- **Don't run `gradle ...`** in a Kotlin Toolchain project — there is no `build.gradle(.kts)`. Use `kotlin ...`.
- **Don't hand-write code that a build-time generator should produce.** Configure the appropriate built-in integration or local plugin instead.
- **Don't install or pin a JDK manually by default.** Use `settings.jvm.jdk` selection/provisioning settings; only require `JAVA_HOME` when the project needs it.
- **Don't add `compose:` settings** to a module that doesn't actually use Compose; only enable it where needed.
- **The CLI is `kotlin`, not `kotlin-toolchain` or `amper`.** Prefer `./kotlin` for projects with a local wrapper; global Toolchain wrappers also discover project pins in ancestor directories.
- **For 0.13.0, use `layout: default`, not `layout: amper`.** The layout name changed in this release.
- **For 0.13.0, use `KOTLIN_TOOLCHAIN_BUILD_DIR`, not `AMPER_BUILD_DIR`.** Do not assume legacy Amper environment-variable names still work.
- **Quote `languageVersion` and `apiVersion` values** (for example `"2.4"`); they are strings in 0.13.0. Check release notes before relying on changed defaults or upgrading compiler-sensitive modules.

## References

- Docs: <https://kotlin-toolchain.org/dev/>
- Version-matched docs/source: <https://github.com/JetBrains/kotlin-toolchain/tree/v0.13.0/docs/src>
- Release notes: <https://github.com/JetBrains/kotlin-toolchain/releases/tag/v0.13.0>
- Source: <https://github.com/JetBrains/kotlin-toolchain>
- Processing: <https://kotlin-toolchain.org/dev/user-guide/advanced/ksp/>, <https://kotlin-toolchain.org/dev/user-guide/advanced/kotlin-compiler-plugins/>
- Build plugins: <https://kotlin-toolchain.org/dev/user-guide/plugins/quick-start/>
- Packaging: <https://kotlin-toolchain.org/dev/user-guide/product-types/jvm-app/#packaging>
- Publishing: <https://kotlin-toolchain.org/dev/user-guide/publishing/>
- Issue tracker: YouTrack project `KTC` — <https://youtrack.jetbrains.com/issues/KTC>
