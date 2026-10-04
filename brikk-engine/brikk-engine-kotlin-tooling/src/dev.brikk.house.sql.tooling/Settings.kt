package dev.brikk.house.sql.tooling

import org.jetbrains.amper.plugins.Configurable

/**
 * Settings for the Engine compiler-plugin dev-loop tasks. Set under
 * `plugins: brikk-engine-kotlin-tooling:` in the applying module's `module.yaml`.
 */
@Configurable
interface Settings {
    /**
     * Kotlin compiler version of the IDE, as shown by the KEFS action "Copy Kotlin IDE Version"
     * (e.g. `2.4.20-ij262-34`). Non-empty selects an actual IDE compiler build
     * from the applying module's resolved classpath. It never relabels a CLI jar.
     * Empty uses the applying module's normal CLI compilation.
     */
    val ideKotlinVersion: String get() = ""

    /** Our own version segment of the KEFS `<kotlin-version>-<lib-version>` scheme. */
    val libVersion: String get() = "0.2.0"

    /** Local Maven-layout repository for KEFS, relative to the project root. */
    val repoDir: String get() = "build/repo"

    /** CLI assembly location (the synthetic consumer refers to this stable path). */
    val assembledDir: String get() = "build/plugin"

    /** One artifact ID in both compiler environments (KEFS swaps version, not identity). */
    val artifactId: String get() = "brikk-engine-kotlin-compiler-plugin"

    /**
     * File-name prefixes of runtime-classpath jars NOT to merge into the plugin jar because the
     * Kotlin compiler already has them on its own classpath.
     */
    val bundleExcludes: List<String> get() = listOf("kotlin-stdlib", "annotations-")
}
