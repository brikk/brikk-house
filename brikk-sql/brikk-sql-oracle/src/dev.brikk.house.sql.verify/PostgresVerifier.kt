package dev.brikk.house.sql.verify

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.SQLException
import org.postgresql.util.PSQLException

/**
 * Verifies SQL against a **real PostgreSQL engine** (Zonky `embedded-postgres`), because
 * Postgres has no parse-only wire API and no mature JVM binding of `libpg_query` (its real
 * parser as a C library, wrapped by Python's `pglast`). Rather than porting or FFI-ing that,
 * this oracle boots an actual PG server and discriminates grammar rejection from
 * catalog/semantic failure **by SQLSTATE**: PG parses fully before it resolves any names, so
 * a syntax-class error means the grammar rejected the SQL, while an "undefined table/column/
 * function" error means the grammar *accepted* it (and only the empty catalog complained) —
 * which is exactly what this oracle answers.
 *
 * ### How each statement is checked
 *  1. `PREPARE brikk_v_<n> AS <sql>` — PG parses AND analyzes the statement (name resolution,
 *     type checking) without executing it, so a valid SELECT against a nonexistent table
 *     fails with `42P01 undefined_table` rather than running. Each call rotates the prepared-
 *     statement name and `DEALLOCATE`s it, so no state accumulates.
 *  2. `PREPARE` only accepts *optimizable* statements (SELECT/INSERT/UPDATE/DELETE/VALUES/
 *     MERGE). Anything else — DDL (`CREATE`, `ALTER`, `DROP`), `SET`, `EXPLAIN`, etc. — fails
 *     PREPARE with `42601`, so it is retried by **direct execution inside a transaction that
 *     is always rolled back** (`BEGIN; <sql>; ROLLBACK`). This parses the DDL for real.
 *
 *     Side-effect safety: transactional DDL in PG is fully rolled back, so `CREATE TABLE`,
 *     `ALTER`, etc. leave no residue. The residual risk is the handful of statements PG
 *     cannot run inside a transaction block — `CREATE DATABASE`, `CREATE TABLESPACE`,
 *     `VACUUM`, `CREATE INDEX CONCURRENTLY`, `ALTER SYSTEM`, `REINDEX ... CONCURRENTLY`.
 *     Those raise `25001 active_sql_transaction` at execution and are treated as
 *     accepted=true (they PARSED; PG only refused the transaction context), and because they
 *     never leave the BEGIN they still cause no side effect. A genuinely non-transactional,
 *     side-effecting statement that also passes its transaction guard is not expected from
 *     the generator's corpus; if one appears it would run against the throwaway embedded
 *     instance only.
 *
 * ### SQLSTATE partition (PostgreSQL "Appendix A. PostgreSQL Error Codes")
 * Accepted=true means "the grammar parsed this". The curated [SEMANTIC_ACCEPT] set below is
 * the class of errors that can ONLY arise after a successful parse (name/type resolution,
 * catalog lookups, privilege checks, unsupported-but-parsable features). Everything in the
 * syntax family (`42601` and the parse-time subset of class 42) is a real grammar reject.
 *
 * Unknown/unlisted codes are treated **conservatively as accepted=false** ONLY for the
 * syntax code itself; every other non-semantic code is also false with the SQLSTATE and
 * message preserved. This risks a false reject on an exotic *semantic* code we didn't
 * enumerate — that is the deliberate trade: the corpus gate's known-failures ledger surfaces
 * any such misclassification (a statement we know is grammatical showing up rejected), and
 * the fix is to add its SQLSTATE to [SEMANTIC_ACCEPT]. The partition is thus self-correcting
 * against the corpus rather than guessed permissive.
 *
 * ### Lifecycle & cost
 * Boot is expensive: the first [verify] launches a native PG process (`initdb` + `postgres`),
 * ~1–3s warm, plus a one-time ~tens-of-MB binary download to
 * `~/.embedded-postgres-binaries` on the very first run ever (cached across runs). Hold ONE
 * instance for the process/session; per-[verify] cost after boot is sub-millisecond. [verify]
 * is `@Synchronized` (single shared JDBC connection, like [DuckdbVerifier]). Call [close] to
 * stop the embedded server; the JVM shutdown reclaims it otherwise. If embedded Postgres cannot
 * be extracted, loaded, started, or connected on this host, [verify] does not throw: it returns
 * `verified=false` with a warning. That is deliberately distinct from parser rejection.
 */
class PostgresVerifier : SqlVerifier, AutoCloseable {
    override val engine: String = "postgres"

    private var embedded: EmbeddedPostgres? = null
    private var lazyConnection: Connection? = null
    private var unavailableReason: String? = null
    private var counter = 0

    /** Starts embedded Postgres once; startup failure is host availability, not an SQL result. */
    private fun connectionOrNull(): Connection? {
        lazyConnection?.let { return it }
        unavailableReason?.let { return null }
        return try {
            val pg = EmbeddedPostgres.builder().start()
            embedded = pg
            pg.postgresDatabase.connection.also { lazyConnection = it }
        } catch (error: Exception) {
            markUnavailable(error)
            null
        } catch (error: LinkageError) {
            markUnavailable(error)
            null
        }
    }

    private fun markUnavailable(error: Throwable) {
        runCatching { lazyConnection?.close() }
        lazyConnection = null
        runCatching { embedded?.close() }
        embedded = null
        unavailableReason = "PostgreSQL verification was not performed: embedded Postgres could not start " +
            "(${error::class.simpleName}: ${error.message ?: "no detail"})."
    }

    /**
     * PG has no standalone expression parser over the wire, so the fragment is wrapped as
     * `SELECT <expr>` and verified as a statement. Positions on line 1 are shifted back past
     * the `SELECT ` prefix (best-effort).
     */
    override fun verifyExpression(sql: String): VerifyResult {
        val result = verify("SELECT $sql")
        val col = result.col
        if (result.accepted || result.line != 1 || col == null) return result
        return result.copy(col = (col - WRAPPER_PREFIX).coerceAtLeast(1))
    }

    @Synchronized
    override fun verify(sql: String): VerifyResult {
        val conn = connectionOrNull() ?: return VerifyResult(
            accepted = false,
            verified = false,
            warning = unavailableReason ?: "PostgreSQL verification was not performed: embedded Postgres is unavailable.",
        )
        val name = "brikk_v_${counter++}"
        try {
            conn.createStatement().use { it.execute("PREPARE $name AS $sql") }
        } catch (e: PSQLException) {
            // PREPARE only accepts optimizable statements; it rejects DDL/utility statements
            // with the SAME `42601` "syntax error at or near \"<KEYWORD>\"" it uses for a real
            // typo, so the message can't tell them apart. Instead, on ANY 42601 from PREPARE,
            // retry via rolled-back direct execution: a genuine syntax error fails there too
            // (and is classified rejected), while a valid DDL/utility statement parses cleanly.
            // Non-42601 errors from PREPARE are already a real classification (e.g. 42P01).
            if (e.sqlState == SYNTAX_ERROR) return verifyByRolledBackExecution(sql, conn)
            return classify(sql, e)
        }
        // Prepared successfully: grammar (and analysis) accepted it. Clean up.
        runCatching { conn.createStatement().use { it.execute("DEALLOCATE $name") } }
        return VerifyResult(accepted = true)
    }

    /**
     * Runs [sql] inside a transaction that is ALWAYS rolled back, so parsing/analysis happens
     * for real with no committed side effect. Used for statements PREPARE won't take (DDL etc.).
     */
    private fun verifyByRolledBackExecution(sql: String, conn: Connection): VerifyResult {
        val restoreAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { it.execute(sql) }
            return VerifyResult(accepted = true)
        } catch (e: PSQLException) {
            // Statements that can't run inside a transaction block PARSED fine; PG only
            // objected to the transaction context (25001) — count as accepted.
            if (e.sqlState == ACTIVE_SQL_TRANSACTION) return VerifyResult(accepted = true)
            return classify(sql, e)
        } finally {
            runCatching { conn.rollback() }
            runCatching { conn.autoCommit = restoreAutoCommit }
        }
    }

    /**
     * Maps a PG error to accepted/rejected by SQLSTATE. Curated semantic codes (post-parse
     * failures) => accepted=true; syntax and everything else => accepted=false with the
     * SQLSTATE and message preserved (conservative; see class KDoc).
     */
    private fun classify(sql: String, e: PSQLException): VerifyResult {
        val state = e.sqlState
        if (state != null && state in SEMANTIC_ACCEPT) return VerifyResult(accepted = true)

        val server = e.serverErrorMessage
        // ServerErrorMessage.position is a 1-based char offset into the submitted SQL text.
        val position = server?.position?.takeIf { it > 0 }
        val (line, col) = position?.let { lineColOf(sql, it - 1) } ?: (null to null)
        val message = (server?.message ?: e.message)?.trim()
        return VerifyResult(
            accepted = false,
            error = listOfNotNull(state, message).joinToString(": "),
            line = line,
            col = col,
        )
    }

    /** Converts a 0-based char offset into 1-based (line, col). */
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
        runCatching { lazyConnection?.close() }
        lazyConnection = null
        runCatching { embedded?.close() }
        embedded = null
        unavailableReason = null
    }

    private companion object {
        const val WRAPPER_PREFIX = "SELECT ".length

        const val SYNTAX_ERROR = "42601"
        const val ACTIVE_SQL_TRANSACTION = "25001"

        /**
         * SQLSTATEs that can only occur AFTER a successful parse => grammar accepted the SQL.
         * Citations are to PostgreSQL "Appendix A. PostgreSQL Error Codes".
         *
         * Class 42 (Syntax Error or Access Rule Violation) — the *access-rule/resolution*
         * half only; `42601 syntax_error` is deliberately excluded (that is the parse reject).
         * Class 22 (Data Exception), 0A (Feature Not Supported), 3D/3F (invalid catalog/schema
         * name), 55 (object-not-in-prerequisite-state), 53 (insufficient resources), 25
         * (invalid transaction state) are all post-parse conditions.
         */
        val SEMANTIC_ACCEPT: Set<String> = setOf(
            // --- Class 42: name/type resolution & access rules (NOT syntax) ---
            "42501", // insufficient_privilege
            "42846", // cannot_coerce
            "42803", // grouping_error
            "42P20", // windowing_error
            "42P19", // invalid_recursion
            "42830", // invalid_foreign_key
            "42P18", // indeterminate_datatype
            "42P21", // collation_mismatch
            "42P22", // indeterminate_collation
            "42809", // wrong_object_type
            "428C9", // generated_always
            "42939", // reserved_name
            "42804", // datatype_mismatch
            "42P10", // invalid_column_reference
            "42611", // invalid_column_definition
            "42P17", // invalid_object_definition
            "42P16", // invalid_table_definition
            "42P15", // invalid_schema_definition
            "42P14", // invalid_prepared_statement_definition
            "42P13", // invalid_function_definition
            "42P12", // invalid_database_definition
            "42P11", // invalid_cursor_definition
            "42622", // name_too_long
            "42710", // duplicate_object
            "42712", // duplicate_alias
            "42701", // duplicate_column
            "42P03", // duplicate_cursor
            "42P04", // duplicate_database
            "42723", // duplicate_function
            "42P05", // duplicate_prepared_statement
            "42P06", // duplicate_schema
            "42P07", // duplicate_table
            "42P08", // ambiguous_parameter
            "42P09", // ambiguous_alias
            "42702", // ambiguous_column
            "42725", // ambiguous_function
            "42704", // undefined_object
            "42703", // undefined_column
            "42883", // undefined_function
            "42P01", // undefined_table
            "42P02", // undefined_parameter
            "42P22", // (also listed above; set dedups)
            // --- Class 0A: parsable-but-unimplemented feature ---
            "0A000", // feature_not_supported
            // --- Class 3D / 3F: catalog / schema name resolution ---
            "3D000", // invalid_catalog_name
            "3F000", // invalid_schema_name
            // --- Class 22: data exceptions surfaced during analysis/const-folding ---
            "22003", // numeric_value_out_of_range
            "22007", // invalid_datetime_format
            "22008", // datetime_field_overflow
            "2201B", // invalid_regular_expression
            "22023", // invalid_parameter_value
            "22021", // character_not_in_repertoire (e.g. bytes not valid for the DB encoding)
            "22P02", // invalid_text_representation (e.g. bad literal cast)
            "22P03", // invalid_binary_representation
            "22032", // invalid_json_text
            "2203A", // sql_json_member_not_found
            // --- Class 55: object not in prerequisite state (parses, wrong state) ---
            "55000", // object_not_in_prerequisite_state
            "55006", // object_in_use
            // --- Class 53: insufficient resources (runtime, post-parse) ---
            "53000", // insufficient_resources
            // --- Class 25: invalid transaction state (statement parsed) ---
            "25001", // active_sql_transaction (also short-circuited above)
            "25P02", // in_failed_sql_transaction
        )
    }
}
