package dev.brikk.house.sql.verify

import dev.brikk.house.chdb.Chdb
import dev.brikk.house.chdb.ChdbConfig
import dev.brikk.house.chdb.ChdbQueryException
import dev.brikk.house.chdb.ChdbSession
import dev.brikk.house.chdb.ChdbUnavailableException
import dev.brikk.house.chdb.ChdbOutputFormat

/**
 * ClickHouse's native grammar verifier, backed in-process by chDB.
 *
 * `EXPLAIN AST` invokes ClickHouse's parser without executing the submitted statement. It is a
 * syntax oracle, not a semantic validator: an unknown table/function or a setting-dependent
 * failure means ClickHouse got beyond parsing and is therefore reported as accepted. This
 * verifier has no catalog dependency; callers that need function name/arity evidence may layer
 * a catalog or a stronger analysis check on top, but neither is required for AST verification.
 *
 * A compatible `libchdb` is currently selected by [ChdbConfig.libraryPath] or the
 * `brikk.chdb.library` system property. No ClickHouse server, CLI, JDBC driver, or child process
 * is involved. Call [close] when the verifier is no longer needed.
 */
class ClickhouseVerifier private constructor(
    private val session: ChdbSession?,
    private val unavailableReason: String?,
) : SqlVerifier, AutoCloseable {
    override val engine: String = "clickhouse"

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        val activeSession = session ?: return VerifyResult(
            accepted = false,
            verified = false,
            warning = unavailableReason ?: "ClickHouse verification was not performed: libchdb is unavailable.",
        )
        return try {
            activeSession.query("EXPLAIN AST $sql", ChdbOutputFormat.TSV)
            VerifyResult(accepted = true)
        } catch (error: ChdbQueryException) {
            classify(sql, error.message.orEmpty())
        }
    }

    /** ClickHouse has no expression-only parser entry point, so wrap the fragment in SELECT. */
    override fun verifyExpression(sql: String): VerifyResult {
        val result = verify("SELECT $sql")
        val col = result.col
        if (result.accepted || result.line != 1 || col == null) return result
        return result.copy(col = (col - EXPRESSION_PREFIX.length).coerceAtLeast(1))
    }

    override fun close() {
        session?.close()
    }

    private fun classify(sql: String, message: String): VerifyResult {
        if (!isSyntaxError(message)) {
            // EXPLAIN AST did reach the engine, and this error is not from the parser. This
            // matches the contract of every verifier in this module: grammar accepted.
            return VerifyResult(accepted = true)
        }
        val lineAndCol = LINE_AND_COLUMN.find(message)
        val position = POSITION.find(message)?.groupValues?.get(1)?.toIntOrNull()
        val (line, col) = when {
            lineAndCol != null -> lineAndCol.groupValues[1].toIntOrNull() to lineAndCol.groupValues[2].toIntOrNull()
            position != null -> lineColOf(sql, position - 1)
            else -> null to null
        }
        return VerifyResult(accepted = false, error = message, line = line, col = col)
    }

    private fun lineColOf(sql: String, offset: Int): Pair<Int?, Int?> {
        if (offset !in 0..sql.length) return null to null
        var line = 1
        var lineStart = 0
        for (index in 0 until offset) {
            if (sql[index] == '\n') {
                line += 1
                lineStart = index + 1
            }
        }
        return line to offset - lineStart + 1
    }

    companion object {
        private const val EXPRESSION_PREFIX = "SELECT "
        private val POSITION = Regex("""position\s+(\d+)""", RegexOption.IGNORE_CASE)
        private val LINE_AND_COLUMN = Regex(
            """line\s+(\d+),\s*column\s+(\d+)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Creates a verifier without throwing for an unavailable native library. In that case
         * [verify] returns `verified=false` and a warning, leaving the caller free to continue
         * on unsupported platforms such as Windows.
         */
        fun create(config: ChdbConfig = ChdbConfig()): ClickhouseVerifier =
            try {
                ClickhouseVerifier(Chdb.open(config), unavailableReason = null)
            } catch (error: ChdbUnavailableException) {
                ClickhouseVerifier(session = null, unavailableReason = error.message)
            }

        private fun isSyntaxError(message: String): Boolean =
            message.contains("Syntax error", ignoreCase = true) ||
                message.contains("Code: 62", ignoreCase = true)
    }
}
