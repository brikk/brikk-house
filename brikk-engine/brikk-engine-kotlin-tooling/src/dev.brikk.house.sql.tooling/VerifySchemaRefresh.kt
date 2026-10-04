package dev.brikk.house.sql.tooling

import dev.brikk.house.sql.shape.CapturedColumn
import dev.brikk.house.sql.shape.CapturedObject
import dev.brikk.house.sql.shape.SchemaCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Real Toolchain builds with unchanged consumer sources and no clean/cache deletion. */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun verifySchemaRefresh(
    @Input wrapper: Path,
    @Input assembledDir: Path,
    @Input runtimeModuleDir: Path,
    @Input schemaPluginDir: Path,
    libVersion: String,
    @Output outputDir: Path,
) {
    val compiler = "2.4.10"
    val artifact = assembledDir.resolve("brikk-engine-kotlin-compiler-plugin-$compiler-$libVersion.jar")
    validatePublication(artifact, compiler, compiler, libVersion)
    outputDir.createDirectories()
    val project = Files.createTempDirectory(outputDir, "synthetic-project-")
    fun copyModule(from: Path): Path {
        val target = project.resolve(from.fileName.toString()).createDirectories()
        // Toolchain project manifests use relative module globs. Reuse exact public
        // sources in this isolated fixture; never edit or include a private consumer.
        Files.walk(from).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { file ->
            val relative = from.relativize(file)
            val first = relative.first().toString()
            if (first in setOf("module.yaml", "plugin.yaml", "src", "resources") || first.startsWith("src@") || first.startsWith("resources@")) {
                val destination = target.resolve(relative)
                destination.parent.createDirectories()
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } }
        return target
    }
    val runtimeCopy = copyModule(runtimeModuleDir)
    val schemaPluginCopy = copyModule(schemaPluginDir)
    val consumer = project.resolve("schema-consumer").createDirectories()
    val src = consumer.resolve("src").createDirectories()
    project.resolve("project.yaml").writeText("""
        modules:
          - ${runtimeCopy.fileName}
          - ${schemaPluginCopy.fileName}
          - schema-consumer
        plugins:
          - ./${schemaPluginCopy.fileName}
    """.trimIndent() + "\n")
    consumer.resolve("module.yaml").writeText("""
        product: jvm/lib
        plugins:
          brikk-engine-kotlin-schema-inputs:
            enabled: true
            schemaPath: schema
        dependencies:
          - ../${runtimeCopy.fileName}
        settings:
          kotlin:
            version: $compiler
            freeCompilerArgs:
              - '-Xplugin=${artifact.toAbsolutePath()}'
              - -P
              - 'plugin:dev.brikk.house.sql.compiler:schema=${project.resolve("schema").toAbsolutePath()}'
    """.trimIndent() + "\n")
    src.resolve("consumer.kt").writeText("""
        package refresh
        import dev.brikk.house.sql.runtime.*
        @BrikkSql fun records() = Sql.doris("SELECT * FROM sample.analytics.records")
        fun id(row: RecordsOut): Any? = row.id
    """.trimIndent() + "\n")
    val sourceBytes = Files.readAllBytes(src.resolve("consumer.kt"))
    val schema = project.resolve("schema")
    val reports = mutableListOf<String>()
    fun capture(columns: List<CapturedColumn>?) = SchemaCache.replace(schema, "sample", "analytics", "doris", "synthetic-refresh",
        if (columns == null) emptyList() else listOf(CapturedObject("sample", "analytics", "records", "TABLE", columns)))
    fun build(phase: String, succeeds: Boolean = true): String {
        val log = outputDir.resolve("$phase.log")
        val process = ProcessBuilder(wrapper.toAbsolutePath().toString(), "build", "--project-dir=${project.toAbsolutePath()}", "-m", "schema-consumer")
            .directory(wrapper.toAbsolutePath().parent.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) { process.destroyForcibly(); error("Schema refresh build timed out: $phase") }
        val text = log.readText()
        check((process.exitValue() == 0) == succeeds) { "Schema refresh build $phase failed its expectation:\n$text" }
        check(Files.readAllBytes(src.resolve("consumer.kt")).contentEquals(sourceBytes)) { "Consumer sources were edited" }
        reports += "PASS $phase"
        return text
    }
    fun getters(): Pair<Map<String, String>, FileTime> {
        val file = Files.walk(project.resolve("build")).use { paths -> paths.filter { it.fileName.toString() == "RecordsOut.class" }.toList().single() }
        val methods = linkedMapOf<String, String>()
        ClassReader(Files.readAllBytes(file)).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (name.startsWith("get")) methods[name] = descriptor
                return null
            }
        }, ClassReader.SKIP_CODE)
        return methods to Files.getLastModifiedTime(file)
    }
    val before = listOf(CapturedColumn("id", "BIGINT", false), CapturedColumn("old_column", "STRING", false))
    capture(before)
    build("initial")
    check(getters().first == mapOf("getId" to "()J", "getOld_column" to "()Ljava/lang/String;"))
    val unchanged = getters().second
    val marker = consumer.resolve(".brikk/schema-inputs/BrikkSchemaInput.kt")
    val markerTime = Files.getLastModifiedTime(marker)
    build("unchanged")
    check(getters().second == unchanged) { "Unchanged build recompiled generated shapes" }
    capture(before)
    build("identical-recapture")
    check(getters().second == unchanged) { "Capture bookkeeping caused needless recompilation" }
    check(Files.getLastModifiedTime(marker) == markerTime) { "Identical capture rewrote the IDE revision source" }
    val after = listOf(CapturedColumn("id", "TEXT", false), CapturedColumn("new_column", "INT", true))
    capture(after)
    build("type-nullability-add-remove")
    check(getters().first == mapOf("getId" to "()Ljava/lang/String;", "getNew_column" to "()Ljava/lang/Integer;"))
    capture(null)
    val removed = build("removed-table", succeeds = false)
    check("Unresolved reference 'id'" in removed || "[BRIKK_SQL]" in removed) { "Missing removed-table diagnostic" }
    val scope = capture(after)
    val manifest = scope.resolve("_snapshot.json")
    val saved = manifest.readText()
    manifest.writeText("{")
    build("malformed-snapshot", succeeds = false)
    manifest.writeText(saved)
    build("recovery")
    check(getters().first == mapOf("getId" to "()Ljava/lang/String;", "getNew_column" to "()Ljava/lang/Integer;"))
    outputDir.resolve("verification.txt").writeText(reports.joinToString("\n") + "\nunchanged-consumer-source=true\nclean-performed=false\nnot-live-IDE-or-Doris-acceptance=true\n")
    println(reports.joinToString("\n"))
}
