package dev.brikk.house.sql.tooling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/** One dependency-relocated, service-loadable artifact for the CLI compiler. */
@TaskAction
fun assemblePluginJar(
    @Input pluginJar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    @Input compileClasspath: Classpath,
    artifactId: String,
    kotlinVersion: String,
    ideKotlinVersion: String,
    @Input sourceDir: Path,
    @Input resourcesDir: Path,
    libVersion: String,
    bundleExcludes: List<String>,
    @Output outputDir: Path,
) {
    require(artifactId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid artifact ID" }
    if (ideKotlinVersion.isNotBlank()) {
        buildIdePlugin(sourceDir, resourcesDir, runtimeClasspath, artifactId, ideKotlinVersion, libVersion, outputDir)
        return
    }
    val dependencies = runtimeClasspath.resolvedFiles
        .filter { it != pluginJar.artifact && it.name.endsWith(".jar") }
        .filterNot { jar -> bundleExcludes.any { jar.name.startsWith(it) } }
        .sortedBy { it.name }
    val compilerApi = compileClasspath.resolvedFiles.singleOrNull { it.name == "kotlin-compiler-embeddable-$kotlinVersion.jar" }
        ?: error("CLI compiler API must match the module compiler version $kotlinVersion")
    shadePluginJar(listOf(pluginJar.artifact) + dependencies,
        outputDir.resolve("$artifactId-$kotlinVersion-$libVersion.jar"), kotlinVersion, libVersion,
        mapOf("compilerArtifact" to compilerApi.name, "compilerSha256" to sha256(Files.readAllBytes(compilerApi))))
}
