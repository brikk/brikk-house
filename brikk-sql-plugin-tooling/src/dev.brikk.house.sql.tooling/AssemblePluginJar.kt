package dev.brikk.house.sql.tooling

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/**
 * Merges the compiler-plugin jar with its runtime dependencies (brikk-sql, brikk-sql-metadata,
 * kotlinx-serialization) into one jar for `-Xplugin=` consumption.
 *
 * The output is named `<artifactId>-<kotlinVersion>-<libVersion>.jar`: KEFS detects a compiler
 * plugin from the `-Xplugin` jar *file name*, matched as `<detect>-<version>.jar` with the
 * default `<kotlin-version>-<lib-version>` version pattern, so this name needs no remapping in
 * the KEFS settings.
 *
 * This is a plain merge (first entry wins), not a relocating shade. Fine for the in-repo smoke
 * module and the KEFS experiment; NOT what a published plugin needs — see
 * docs/vendor/kefs/PLUGIN_AUTHORS.md ("dependencies must be relocated").
 */
@TaskAction
fun assemblePluginJar(
    @Input pluginJar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    artifactId: String,
    kotlinVersion: String,
    libVersion: String,
    bundleExcludes: List<String>,
    @Output outputDir: Path,
) {
    val dependencyJars = runtimeClasspath.resolvedFiles
        .filter { it != pluginJar.artifact && it.name.endsWith(".jar") }
        .filterNot { jar -> bundleExcludes.any { jar.name.startsWith(it) } }
        .sortedBy { it.name }
    val sources = listOf(pluginJar.artifact) + dependencyJars

    val out = outputDir.resolve("$artifactId-$kotlinVersion-$libVersion.jar")
    outputDir.toFile().deleteRecursively()
    outputDir.createDirectories()

    val seen = HashSet<String>()
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        for (source in sources) {
            ZipFile(source.toFile()).use { jar ->
                for (entry in jar.entries().asSequence()) {
                    val name = entry.name
                    if (entry.isDirectory || name in seen || isSigningOrManifest(name)) continue
                    seen += name
                    zip.putNextEntry(ZipEntry(name).also { it.time = entry.time })
                    jar.getInputStream(entry).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
    println("wrote ${out.relativeTo(outputDir.parent.parent)} from ${sources.size} jars (${seen.size} entries)")
    println("bundled: ${sources.joinToString { it.name }}")
}

/** Signature files and the top-level manifest never survive a merge. Nested service files do. */
private fun isSigningOrManifest(name: String): Boolean =
    name == "META-INF/MANIFEST.MF" ||
        (name.startsWith("META-INF/") && name.substringAfterLast('.', "").uppercase() in setOf("SF", "RSA", "DSA", "EC"))

internal fun singleJarIn(dir: Path): Path {
    require(Files.isDirectory(dir)) { "missing $dir - run `./kotlin do assemblePluginJar` first" }
    val jars = Files.list(dir).use { s -> s.filter { it.name.endsWith(".jar") }.toList() }
    return jars.singleOrNull() ?: error("expected exactly one jar in $dir, found ${jars.map { it.name }}")
}
