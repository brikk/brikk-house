package dev.brikk.house.sql.verify

import io.trino.sql.parser.ParsingException
import io.trino.sql.parser.SqlParser

/**
 * Verifies SQL against Trino's own parser (`io.trino:trino-parser`), the exact ANTLR
 * grammar the Trino engine runs. Pinned to Trino 481, matching the vendored function
 * catalog (vendor/data/trino-functions-481.tsv).
 *
 * Cold start is just parser class loading (milliseconds); instances are cheap and
 * thread-safe ([SqlParser] is stateless across calls).
 */
class TrinoVerifier : SqlVerifier {
    override val engine: String = "trino"

    private val parser = SqlParser()

    override fun verify(sql: String): VerifyResult = parse { parser.createStatement(sql) }

    override fun verifyExpression(sql: String): VerifyResult = parse { parser.createExpression(sql) }

    private inline fun parse(block: () -> Unit): VerifyResult = try {
        block()
        VerifyResult(accepted = true)
    } catch (e: ParsingException) {
        VerifyResult(
            accepted = false,
            error = e.errorMessage ?: e.message,
            line = e.lineNumber.takeIf { it > 0 },
            col = e.columnNumber.takeIf { it > 0 },
        )
    }
}
