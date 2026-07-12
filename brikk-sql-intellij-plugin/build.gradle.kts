plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "dev.brikk.house"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// ---------------------------------------------------------------------------
// brikk-house toolchain bridge ("Option B").
//
// brikk-sql / brikk-sql-metadata are Kotlin Toolchain (Amper) kmp/lib modules in
// the parent directory. The toolchain cannot build IntelliJ plugins (no product
// type, no way to compile against the DataGrip distribution), and its `publish`
// command does not yet produce consumable KMP publications — so this Gradle
// build consumes the toolchain's JVM jar outputs directly, and shells out to
// `./kotlin build -p jvm` first so the jars are always fresh. Switch to
// `kotlin publish` coordinates once KMP publishing is consumable.
// ---------------------------------------------------------------------------
val brikkHouseRoot: java.io.File = rootDir.parentFile

val buildBrikkJvmJars by tasks.registering(Exec::class) {
    description = "Builds brikk-sql + brikk-sql-metadata JVM jars via the Kotlin Toolchain wrapper"
    workingDir = brikkHouseRoot
    commandLine(
        "./kotlin", "build", "-p", "jvm",
        "-m", "brikk-sql",
        "-m", "brikk-sql-metadata",
    )
    // The toolchain has its own incremental/up-to-date engine; always delegate to it.
    outputs.upToDateWhen { false }
}

val brikkJars = files(
    File(brikkHouseRoot, "build/tasks/_brikk-sql_jarJvm/brikk-sql-jvm.jar"),
    File(brikkHouseRoot, "build/tasks/_brikk-sql-metadata_jarJvm/brikk-sql-metadata-jvm.jar"),
).builtBy(buildBrikkJvmJars)

dependencies {
    implementation(brikkJars)
    // Transitive runtime deps of the brikk jars, resolved by the toolchain as
    // kotlin-stdlib 2.3.21 + kotlinx-serialization 1.10.0 (see `./kotlin show dependencies`).
    // Keep these pinned in lockstep; kotlin.stdlib.default.dependency=false makes it explicit.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // DataGrip 2026.1 (platform build 261), remote SDK — same target as the
        // sibling doris-intellij-plugin; 261/262 both run on JBR 25.
        datagrip("2026.1.3")
        bundledPlugin("com.intellij.database")
        // The database plugin's intellij.json.backend module dependency lives in the
        // JSON plugin; without it com.intellij.database won't load in unit tests.
        bundledPlugin("com.intellij.modules.json")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Dogfooding companion: the sibling doris-intellij-plugin
        // (dev.sort.doris-intellij-plugin) in the runIde sandbox, so Doris data
        // sources get their real dialect instead of platform fallbacks. Loaded from
        // its stable-named local build (run `./gradlew buildPlugin` in that repo to
        // refresh); silently skipped when absent, so clones/CI without the sibling
        // repo still build. Tests are unaffected either way — the test framework
        // only loads what idea.load.plugins.id lists.
        val dorisPluginZip = providers.gradleProperty("dorisPluginZip")
            .orElse("/home/jayson/DEV/sortdev/doris-intellij-plugin/build/distributions/doris-intellij-plugin.zip")
            .map(::File)
            .get()
        if (dorisPluginZip.exists()) {
            localPlugin(dorisPluginZip)
        } else {
            logger.lifecycle("doris-intellij-plugin zip not found at $dorisPluginZip — runIde sandbox will not include it (set -PdorisPluginZip=... or build it)")
        }
    }
}

intellijPlatform {
    // No custom settings UI yet; skip the headless searchable-options IDE launch.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            ide("DB", "2026.1.3")
        }
    }
}

tasks {
    // Stable artifact name (no version suffix) so install-from-disk always points at the same file.
    buildPlugin {
        archiveVersion = ""
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    named<Test>("test") {
        useJUnit()
        // The light test fixture doesn't enable the database plugin by default.
        systemProperty("idea.load.plugins.id", "com.intellij.database,dev.brikk.brikk-sql-intellij-plugin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Dogfooding sandbox with the PSI structure viewer, for inspecting how DataGrip's SQL
// PSI lines up with brikk-sql's AST (same convention as doris-intellij-plugin).
val runIdeWithPsiViewer by intellijPlatformTesting.runIde.registering {
    plugins {
        plugin("PsiViewer", "252.23892.248")
    }
}
