package dev.brikk.house.sql.tooling

import dev.brikk.house.sql.shape.SchemaCache
import dev.brikk.house.sql.shape.CapturedColumn
import dev.brikk.house.sql.shape.CapturedObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/** Compile the same sources against the actual IDE API, not a renamed CLI binary. */
internal fun buildIdePlugin(
    sourceDir: Path,
    resourcesDir: Path,
    compilerClasspath: Classpath,
    artifactId: String,
    compilerVersion: String,
    libVersion: String,
    outputDir: Path,
) {
    val jars = compilerClasspath.resolvedFiles.filter { it.extension == "jar" }
    outputDir.createDirectories()
    requireIdeCompiler(jars, compilerVersion, outputDir)
    val raw = outputDir.resolve("unshaded.tmp") // not .jar: publication only sees the final artifact
    val classes = Files.createTempDirectory(outputDir, ".classes-")
    val compatibleSources = Files.createTempDirectory(outputDir, ".sources-")
    try {
        val sources = Files.walk(sourceDir).use { s -> s.filter { it.extension == "kt" }.sorted().map { source ->
            val target = compatibleSources.resolve(sourceDir.relativize(source))
            target.parent.createDirectories()
            val text = source.readText()
            val adapted = adaptIdeSource(source.name, text, compilerVersion)
            target.writeText(adapted)
            target
        }.toList() }
        val result = runCompiler(jars, listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17", "-Xrender-internal-diagnostic-names",
            "-Xcontext-parameters", "-classpath", jars.joinToString(File.pathSeparator),
            "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "-opt-in=org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
            "-opt-in=org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals",
            "-opt-in=org.jetbrains.kotlin.fir.symbols.SymbolInternals",
            "-opt-in=org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess",
            "-opt-in=org.jetbrains.kotlin.config.MessageCollectorAccess",
            "-d", classes.toString()) + sources.map { it.toString() }, outputDir.resolve("compile.log"))
        check(result == 0) { "IDE compiler $compilerVersion rejected plugin sources:\n${outputDir.resolve("compile.log").readText()}" }
        ZipOutputStream(Files.newOutputStream(raw)).use { zip ->
            for (root in listOf(classes, resourcesDir)) {
                Files.walk(root).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                        zip.putNextEntry(ZipEntry(root.relativize(path).toString().replace(File.separatorChar, '/')).also { it.time = 0 })
                        Files.copy(path, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
        val dependencies = jars.filter { it.name.startsWith("brikk-sql-") || it.name.startsWith("kotlinx-serialization-") }
        check(dependencies.any { it.name.startsWith("brikk-sql-jvm-") }) { "Missing SQL classpath" }
        val compiler = jars.single { it.name == "kotlin-compiler-$compilerVersion.jar" }
        shadePluginJar(listOf(raw) + dependencies, outputDir.resolve("$artifactId-$compilerVersion-$libVersion.jar"), compilerVersion, libVersion,
            mapOf("compilerArtifact" to compiler.name, "compilerSha256" to sha256(Files.readAllBytes(compiler)),
                "sourceAdapter" to "namedFunctionCheckers-ij262"))
    } finally {
        // Only private task-created scratch paths; never delete the artifact directory.
        classes.toFile().deleteRecursively()
        compatibleSources.toFile().deleteRecursively()
        Files.deleteIfExists(raw)
    }
}

internal fun adaptIdeSource(name: String, text: String, version: String): String {
    require(version == "2.4.20-ij262-34") { "No reviewed source adapter for IDE compiler $version" }
    if (name != "BrikkSqlFirExtensionRegistrar.kt") return text
    check(text.split("override val simpleFunctionCheckers").size == 2) { "Checker adapter no longer matches source" }
    return text.replace("override val simpleFunctionCheckers", "override val namedFunctionCheckers")
}

/** Out-of-process artifact tests on the real IDE compiler; not live IDE acceptance. */
@TaskAction
fun verifyIdePlugin(
    @Input assembledDir: Path,
    @Input compilerClasspath: Classpath,
    compilerVersion: String,
    artifactId: String,
    libVersion: String,
    @Input fixturesDir: Path,
    @Output outputDir: Path,
) {
    val jars = compilerClasspath.resolvedFiles.filter { it.extension == "jar" }
    outputDir.createDirectories()
    requireIdeCompiler(jars, compilerVersion, outputDir)
    val plugin = assembledDir.resolve("$artifactId-$compilerVersion-$libVersion.jar")
    require(Files.isRegularFile(plugin)) { "Missing assembled IDE artifact $plugin" }
    validatePublication(plugin, compilerVersion, compilerVersion, libVersion)
    val results = mutableListOf<String>()
    Files.list(fixturesDir).use { fixtures ->
        fixtures.filter { it.extension == "kt" }.sorted().forEach { source ->
            val sourceText = source.readText()
            val expected = sourceText.lineSequence().filter { it.startsWith("// EXPECT: ") }
                .map { it.removePrefix("// EXPECT: ") }.toList()
            val bad = expected.isNotEmpty()
            val log = outputDir.resolve("${source.name}.log")
            val apiFixture = "// PLUGIN-API" in sourceText
            val snapshot = "// SNAPSHOT" in sourceText
            val consumer = if (apiFixture) jars + listOf(plugin) else jars
            val draft = if ("// DRAFT" in sourceText) outputDir.resolve("${source.name}.draft.sql") else null
            val options = (if (snapshot) {
                val schema = outputDir.resolve("schema")
                SchemaCache.replace(schema, "sample", "analytics", "doris", "synthetic-matrix-fixture",
                    listOf(CapturedObject("sample", "analytics", "records", "TABLE", listOf(CapturedColumn("id", "BIGINT", nullable = false)))))
                listOf("-P", "plugin:dev.brikk.house.sql.compiler:schema=$schema")
            } else emptyList()) + (draft?.let { listOf("-P", "plugin:dev.brikk.house.sql.compiler:dumpSql=$it") } ?: emptyList())
            val classes = outputDir.resolve(source.name.removeSuffix(".kt"))
            val result = runCompiler(jars, listOf("-no-stdlib", "-no-reflect", "-jvm-target", "17",
                "-classpath", consumer.joinToString(File.pathSeparator), "-Xplugin=$plugin",
                "-d", classes.toString(), source.toString()) + options, log)
            val text = log.readText()
            val warnings = sourceText.lineSequence().filter { it.startsWith("// WARN: ") }.map { it.removePrefix("// WARN: ") }.toList()
            check(if (bad) result == 1 && expected.all { it in text } else result == 0) {
                "Artifact fixture ${source.name} failed (exit $result):\n$text"
            }
            check(warnings.all { it in text }) { "Expected warning missing from ${source.name}:\n$text" }
            if (draft != null && !bad) {
                val report = draft.readText()
                check(report.startsWith("-- BRIKK SQL ROUGH DRAFT v1\n")) { "Missing draft report header" }
                for (directive in sourceText.lineSequence()) {
                    if (directive.startsWith("// DRAFT-EXPECT: ")) check(directive.removePrefix("// DRAFT-EXPECT: ") in report) { "Draft content missing: $directive" }
                    if (directive.startsWith("// DRAFT-ABSENT: ")) check(directive.removePrefix("// DRAFT-ABSENT: ") !in report) { "Unexpected draft content: $directive" }
                }
            }
            sourceText.lineSequence().filter { it.startsWith("// ERROR-AT: ") }.forEach { directive ->
                val token = directive.removePrefix("// ERROR-AT: ")
                val offset = sourceText.lastIndexOf(token)
                check(offset >= 0) { "Missing diagnostic anchor '$token' in ${source.name}" }
                val before = sourceText.substring(0, offset)
                val line = before.count { it == '\n' } + 1
                val column = before.substringAfterLast('\n').length + 1 // UTF-16, as Kotlin/IDE ranges are
                check(":$line:$column: error: [BRIKK_SQL]" in text) { "Incorrect literal range for '$token':\n$text" }
            }
            check(!text.contains("internal error analyzing") && !text.contains("LinkageError") && !text.contains("Exception in thread")) {
                "Internal plugin failure in ${source.name}:\n$text"
            }
            val main = sourceText.lineSequence().firstOrNull { it.startsWith("// RUN: ") }?.removePrefix("// RUN: ")
            if (main != null) {
                val runLog = outputDir.resolve("${source.name}.run.log")
                check(runJava(consumer + listOf(classes), main, emptyList(), runLog) == 0) {
                    "Compiled fixture execution failed: ${source.name}\n${runLog.readText()}"
                }
            }
            results += "PASS ${source.name}"
        }
    }
    check(results.isNotEmpty()) { "No compiler compatibility fixtures" }
    outputDir.resolve("verification.txt").writeText("compiler=$compilerVersion\nplugin.sha256=${sha256(Files.readAllBytes(plugin))}\n" + results.joinToString("\n") + "\nnot-live-IDE-acceptance=true\n")
    println(results.joinToString("\n"))
}

private fun requireIdeCompiler(jars: List<Path>, version: String, outputDir: Path) {
    require(version.isNotBlank()) { "An exact IDE compiler version is required" }
    require(jars.any { it.name == "kotlin-compiler-$version.jar" }) { "Missing actual IDE compiler $version" }
    require(jars.none { it.name.startsWith("kotlin-compiler-embeddable-") }) { "IDE tests require the non-embeddable compiler" }
    val log = outputDir.resolve("compiler-version.log")
    check(runCompiler(jars, listOf("-version"), log) == 0 && "kotlinc-jvm $version" in log.readText()) {
        "Compiler identity mismatch: ${log.readText()}"
    }
}

private fun runCompiler(jars: List<Path>, arguments: List<String>, log: Path): Int {
    // SQL/runtime/serialization must be available to the *consumer*, not to the
    // compiler host. Otherwise parent classpath leakage could mask broken shading.
    val host = jars.filterNot { it.name.startsWith("brikk-") || it.name.startsWith("kotlinx-serialization-") }
    return runJava(host, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", arguments, log)
}

private fun runJava(classpath: List<Path>, main: String, arguments: List<String>, log: Path): Int {
    val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val process = ProcessBuilder(listOf(javaExecutable, "-Xmx2g", "-cp", classpath.joinToString(File.pathSeparator), main) + arguments)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start()
    if (!process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
        process.destroyForcibly()
        error("Compiler subprocess timed out; see $log")
    }
    return process.exitValue()
}
