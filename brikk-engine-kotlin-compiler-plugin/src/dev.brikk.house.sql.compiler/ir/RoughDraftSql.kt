package dev.brikk.house.sql.compiler.ir

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class RoughDraftStage(
    val function: String,
    val dialect: String,
    val sql: String,
    val inputSlots: List<String>,
    val parameterNames: List<String>,
)

internal object RoughDraftSql {
    const val HEADER = "-- BRIKK SQL ROUGH DRAFT v1\n"

    fun report(module: String, stages: List<RoughDraftStage>): String = buildString {
        append(HEADER)
        append("-- Inspection only: parameterized virtual views/pipe stages; NOT final or composed executable SQL.\n")
        append("-- Runtime chooses the graph, target dialect, CTEs and binding-name rewrites.\n")
        append("-- Runtime binding values are omitted; authored literals and SQL constants are retained.\n")
        append("-- This compiler invocation's IR slice only; not a complete module inventory or proof of a successful/current build.\n")
        append("-- Compilation: ").append(label(module)).append('\n')
        append("-- Stages: ").append(stages.size).append('\n')
        for ((index, stage) in stages.withIndex()) {
            append("\n-- Stage ").append(index + 1).append(": ").append(label(stage.function)).append('\n')
            append("-- Source dialect: ").append(label(stage.dialect)).append('\n')
            append("-- Rel input slots: ").append(names(stage.inputSlots)).append('\n')
            append("-- Named parameters: ").append(names(stage.parameterNames)).append('\n')
            append("-- Template SHA-256: ").append(sha256(stage.sql)).append('\n')
            append("-- BEGIN STAGE TEMPLATE\n")
            append(stage.sql) // Exact template passed to Rel; no lowering, composition, translation or trimming.
            if (!stage.sql.endsWith('\n')) append('\n')
            append("-- END STAGE TEMPLATE\n")
        }
    }

    /** One explicit report file avoids stale per-function files; never clean a directory. */
    fun write(path: Path, module: String, stages: List<RoughDraftStage>) {
        val target = outputPath(path, module)
        for (component in generateSequence(target) { it.parent }) {
            require(!Files.isSymbolicLink(component)) { "Draft output paths must not be symbolic links" }
        }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target) && Files.readString(target).startsWith(HEADER)) {
                "Refusing to overwrite an unrecognized draft output file"
            }
        }
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".brikk-draft-", ".tmp")
        try {
            Files.writeString(temporary, report(module, stages))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** A stable safe token keeps inherited main/test options from sharing one report. */
    fun outputPath(path: Path, module: String): Path {
        val readable = module.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-") it else '_' }
            .joinToString("").trim('.', '_', '-').take(48).ifEmpty { "module" }
        val token = "$readable-${sha256(module).take(12)}"
        return Path.of(path.toString().replace("{module}", token)).toAbsolutePath().normalize()
    }

    private fun names(values: List<String>): String = values.distinct().joinToString(", ") { label(it) }.ifEmpty { "(none)" }
    private fun label(value: String): String = value.map {
        if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it
    }.joinToString("")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
