package dev.brikk.house.sql.verify

/**
 * A native-grammar verification oracle: answers "does the target engine's *own* parser
 * accept this SQL?" using the engine's authoritative parser, not our port of sqlglot.
 *
 * Two intended uses:
 *
 * 1. **Corpus gates** — every SQL string our generator emits for a dialect is fed through
 *    the engine's parser; rejects are real dialect bugs and must be ledgered.
 * 2. **Runtime ("transpile AND verify")** — an editor Convert action transpiles a query to
 *    the target dialect, then calls [verify] on the result before presenting it. On reject,
 *    the engine's own error message (and position, when available) is surfaced to the user,
 *    so what the editor shows is exactly what the engine would say.
 *
 * Implementations are cheap to hold on to and should be reused: cold start (parser class
 * loading, or an embedded engine boot for DuckDB) is paid once per instance/process, and
 * individual [verify] calls are then fast.
 */
interface SqlVerifier {
    /** Engine identifier: `"clickhouse"`, `"doris"`, `"trino"`, `"duckdb"`, or `"postgres"`. */
    val engine: String

    /**
     * Runs [sql] (a single statement) through the engine's own parser.
     *
     * Never throws for invalid SQL: parser rejection is reported via [VerifyResult.accepted] =
     * false with [VerifyResult.verified] = true, the engine's error message and, when the engine
     * reports one, a 1-based line and column position. A verifier which cannot be loaded reports
     * [VerifyResult.verified] = false and a warning instead; that is neither SQL acceptance nor
     * SQL rejection.
     */
    fun verify(sql: String): VerifyResult

    /**
     * Runs [sql] as a *scalar expression fragment* (not a full statement) through the
     * engine's own parser — e.g. `DAYNAME(x)` or `x -> '$.family'`. Useful for validating
     * expression-level transpilation output and editor fragments. Same error contract
     * as [verify].
     */
    fun verifyExpression(sql: String): VerifyResult
}

/**
 * Result of a [SqlVerifier.verify] call.
 *
 * @property accepted true when the engine's parser accepted the SQL. Meaningful only when
 *   [verified] is true; an unavailable verifier returns false so old boolean-only gates fail
 *   safely rather than accepting unchecked SQL.
 * @property verified false when no native/parser check was performed; inspect [warning] rather
 *   than treating it as a syntax rejection.
 * @property warning an availability diagnostic when [verified] is false.
 * @property error the engine's own error message when a verified parser rejected the SQL.
 * @property line 1-based line of the error, when the engine reports a position.
 * @property col 1-based column of the error, when the engine reports a position.
 */
data class VerifyResult(
    val accepted: Boolean,
    val error: String? = null,
    val line: Int? = null,
    val col: Int? = null,
    val verified: Boolean = true,
    val warning: String? = null,
)

/**
 * Registry of available native-grammar verifiers.
 *
 * Verifier instances are created per call — callers that verify repeatedly (editor sessions,
 * corpus gates) should hold on to the returned instance to amortize cold-start costs.
 */
object SqlVerifiers {
    /**
     * Returns a verifier for [name] (case-insensitive), or null when the engine is unsupported
     * or its optional parser resource is unavailable.
     *
     * Supported engines: `"clickhouse"`, `"doris"`, `"trino"`, `"duckdb"`, `"postgres"`.
     * `"clickhouse"` is always available as a non-throwing verifier. Until a compatible chDB
     * native library is configured through `brikk.chdb.library`, its results are marked
     * unverified with a warning; this temporary requirement goes away when brikk-chdb packages
     * the platform archives.
     *
     * `"postgres"` has no parse-only wire API and no mature JVM binding of `libpg_query`
     * (Postgres's real parser as a C library; Python's `pglast` wraps it), so [PostgresVerifier]
     * discriminates grammar rejection from catalog/semantic failure by booting a REAL embedded
     * PostgreSQL and classifying SQLSTATEs — still an engine-exact answer, never a non-native
     * grammar. Its first call is expensive (native PG boot + one-time binary download); hold
     * the instance for the session (see [PostgresVerifier] KDoc).
     */
    fun forEngine(name: String): SqlVerifier? = when (name.lowercase()) {
        "clickhouse" -> ClickhouseVerifier.create()
        // Null when the vendored parser jar can't be located (see DorisVerifier KDoc).
        "doris" -> DorisVerifier.createOrNull()
        "trino" -> TrinoVerifier()
        "duckdb" -> DuckdbVerifier()
        // Real embedded PostgreSQL + SQLSTATE discrimination (see PostgresVerifier KDoc).
        "postgres" -> PostgresVerifier()
        else -> null
    }
}
