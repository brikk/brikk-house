package dev.brikk.house.sql.tooling

import org.jetbrains.amper.plugins.Configurable

/**
 * Settings for the brikk-sql compiler-plugin dev-loop tasks. Set under
 * `plugins: brikk-sql-plugin-tooling:` in the applying module's `module.yaml`.
 */
@Configurable
interface Settings {
    /**
     * Kotlin compiler version of the IDE, as shown by the KEFS action "Copy Kotlin IDE Version"
     * (e.g. `2.4.20-ij262-34`). The KEFS repo publishes the jar under
     * `<ideKotlinVersion>-<libVersion>`. Empty means "use the version the plugin was compiled
     * against", which is what `assemblePluginJar` names the jar with.
     */
    val ideKotlinVersion: String get() = ""

    /** Our own version segment of the KEFS `<kotlin-version>-<lib-version>` scheme. */
    val libVersion: String get() = "0.2.0"

    /** Local Maven-layout repository for KEFS, relative to the project root. */
    val repoDir: String get() = "build/repo"

    /**
     * File-name prefixes of runtime-classpath jars NOT to merge into the plugin jar because the
     * Kotlin compiler already has them on its own classpath.
     */
    val bundleExcludes: List<String> get() = listOf("kotlin-stdlib", "annotations-")
}
