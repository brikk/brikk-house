package dev.brikk.house.sql.verify

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Verifies SQL against the real DuckDB parser via an embedded in-memory engine
 * (`org.duckdb:duckdb_jdbc`, pinned to 1.5.4 to match the generated DuckDB function catalog).
 *
 * Strategy (two channels, because DuckDB reports parse failures both ways):
 *  1. `SELECT json_serialize_sql(?)` — a pure parse with no binding. Crucially it does NOT
 *     throw on invalid SQL: it returns a JSON document that itself carries
 *     `{"error": true, "error_type": "parser", "error_message": ..., "position": ...}`.
 *  2. `json_serialize_sql` only supports SELECT statements (anything else yields
 *     `error_type: "not implemented"`), so non-SELECT statements fall back to a JDBC
 *     prepare, which parses AND binds without executing (no side effects). There a
 *     `Parser Error:` prefix means the grammar rejected it, while binder/catalog errors
 *     ("table does not exist") mean the grammar accepted it — which is all this oracle
 *     answers.
 *
 * Cold start: the first [verify] boots an embedded DuckDB (native library load + in-memory
 * database, ~100ms); the connection is created lazily and kept for the verifier's lifetime.
 * Thread-safety: [verify] is synchronized — a single connection is shared, and DuckDB JDBC
 * connections are not safe for concurrent statement use. Use one verifier per thread if
 * contention matters. Call [close] to release the embedded database (optional; the JVM
 * exit reclaims it otherwise).
 */
class DuckdbVerifier : SqlVerifier, AutoCloseable {
    override val engine: String = "duckdb"

    private val json = Json { ignoreUnknownKeys = true }

    private var lazyConnection: Connection? = null

    private val connection: Connection
        get() = lazyConnection ?: DriverManager.getConnection("jdbc:duckdb:").also { lazyConnection = it }

    /**
     * DuckDB has no standalone expression entry point, so the fragment is wrapped as
     * `SELECT <expr>` and verified as a statement. Error positions on line 1 are shifted
     * back by the wrapper prefix (best-effort).
     */
    override fun verifyExpression(sql: String): VerifyResult {
        val result = verify("SELECT $sql")
        if (result.accepted || result.line != 1 || result.col == null) return result
        return result.copy(col = (result.col - WRAPPER_PREFIX).coerceAtLeast(1))
    }

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        // NOTE: json_serialize_sql requires a *constant* VARCHAR argument — binding the SQL as
        // a JDBC parameter fails with "json_serialize_sql first argument must be a VARCHAR",
        // so the SQL is inlined as an escaped string literal.
        val literal = "'" + sql.replace("'", "''") + "'"
        val doc = try {
            connection.createStatement().use { st ->
                st.executeQuery("SELECT json_serialize_sql($literal)").use { rs ->
                    check(rs.next()) { "json_serialize_sql returned no row" }
                    rs.getString(1)
                }
            }
        } catch (e: SQLException) {
            // json_serialize_sql itself failed (it normally reports errors in-band).
            return VerifyResult(accepted = false, error = e.message?.trim())
        }

        val obj = json.parseToJsonElement(doc).jsonObject
        val isError = obj["error"]?.jsonPrimitive?.booleanOrNull == true
        if (!isError) return VerifyResult(accepted = true)

        val errorType = obj["error_type"]?.jsonPrimitive?.contentOrNull
        val errorMessage = obj["error_message"]?.jsonPrimitive?.contentOrNull
        if (errorType == "not implemented") {
            // Non-SELECT statement: json_serialize_sql can't handle it; fall back to prepare.
            return verifyByPrepare(sql)
        }

        val position = obj["position"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val (line, col) = position?.let { lineColOf(sql, it) } ?: (null to null)
        return VerifyResult(
            accepted = false,
            error = listOfNotNull(errorType, errorMessage).joinToString(": ").ifEmpty { doc },
            line = line,
            col = col,
        )
    }

    /**
     * Parse + bind without executing. Grammar rejection surfaces as a `Parser Error:` prefix;
     * any other failure (Binder/Catalog/Not implemented at bind time) means the parser
     * accepted the SQL, which is what this oracle reports.
     */
    private fun verifyByPrepare(sql: String): VerifyResult = try {
        connection.prepareStatement(sql).close()
        VerifyResult(accepted = true)
    } catch (e: SQLException) {
        val message = e.message?.trim().orEmpty()
        if (message.startsWith("Parser Error")) {
            val m = LINE_MARKER.find(message)
            VerifyResult(
                accepted = false,
                error = message,
                line = m?.groupValues?.get(1)?.toIntOrNull(),
                col = m?.groupValues?.get(2)?.toIntOrNull(),
            )
        } else {
            // Bound-stage failure: the grammar accepted the statement.
            VerifyResult(accepted = true)
        }
    }

    /** Converts a 0-based character offset (json_serialize_sql "position") to 1-based line/col. */
    private fun lineColOf(sql: String, offset: Int): Pair<Int?, Int?> {
        if (offset < 0 || offset > sql.length) return null to null
        var line = 1
        var lineStart = 0
        for (i in 0 until minOf(offset, sql.length)) {
            if (sql[i] == '\n') {
                line += 1
                lineStart = i + 1
            }
        }
        return line to (offset - lineStart + 1)
    }

    @Synchronized
    override fun close() {
        lazyConnection?.close()
        lazyConnection = null
    }

    private companion object {
        /** DuckDB JDBC error messages carry a `LINE n: ...` marker; column is best-effort. */
        val LINE_MARKER = Regex("""LINE (\d+):\s*(?:(\d+))?""")

        /** Length of the `SELECT ` prefix used by [verifyExpression]. */
        const val WRAPPER_PREFIX = "SELECT ".length
    }
}
