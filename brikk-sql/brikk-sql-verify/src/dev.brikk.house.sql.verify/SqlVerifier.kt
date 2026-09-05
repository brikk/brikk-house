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
    /**
     * Engine identifier: `"clickhouse"`, `"doris"`, `"trino"`, `"duckdb"`, `"postgres"`,
     * `"mysql"`, or `"hive"`.
     */
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
 * @property advisory true when produced by a re-implemented grammar oracle (ShardingSphere)
 *   rather than the engine's own parser; consumers may treat an advisory `accepted=false` as a
 *   non-blocking warning rather than a hard rejection. Engine-exact/fidelity verifiers (Trino,
 *   DuckDB, Doris, embedded PostgreSQL, chDB ClickHouse) leave this false.
 */
data class VerifyResult(
    val accepted: Boolean,
    val error: String? = null,
    val line: Int? = null,
    val col: Int? = null,
    val verified: Boolean = true,
    val warning: String? = null,
    val advisory: Boolean = false,
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
     * Supported engines: `"trino"`, `"duckdb"`, `"doris"`, `"postgres"`, `"mysql"`, `"hive"`,
     * `"clickhouse"`.
     *
     * This registry returns the **portable/advisory** verifier suitable for IDE/shipped use.
     * Engines split into two tiers:
     *
     * - **Fidelity (native, non-advisory):** `"trino"`, `"duckdb"`, `"doris"` use each engine's
     *   own parser (trino-parser, embedded DuckDB, vendored Doris FE). Results have
     *   `advisory = false`.
     * - **Advisory (portable grammar oracle):** `"postgres"`, `"mysql"`, `"hive"`, `"clickhouse"`
     *   use the pure-JVM ShardingSphere SQL parser (see [ShardingSphereVerifier]). Results have
     *   `advisory = true`; consumers may treat an advisory reject as a warning. Fidelity oracles
     *   for these engines live in the heavyweight `brikk-sql-oracle` module and are reached via
     *   its `SqlOracles.forEngine` registry: `PostgresVerifier` (real embedded PostgreSQL +
     *   SQLSTATE discrimination) for the postgres corpus gate, and `ClickhouseVerifier` (chDB
     *   `EXPLAIN AST`) for offline ClickHouse fidelity checks.
     */
    fun forEngine(name: String): SqlVerifier? = when (name.lowercase()) {
        // Null when the vendored parser jar can't be located (see DorisVerifier KDoc).
        "doris" -> DorisVerifier.createOrNull()
        "trino" -> TrinoVerifier()
        "duckdb" -> DuckdbVerifier()
        // Advisory ShardingSphere grammar oracles (see ShardingSphereVerifier KDoc). The
        // engine-exact fidelity oracles (embedded PG, chDB CH) are constructed explicitly.
        "postgres" -> ShardingSphereVerifier.forEngine("postgres")
        "mysql" -> ShardingSphereVerifier.forEngine("mysql")
        "hive" -> ShardingSphereVerifier.forEngine("hive")
        "clickhouse" -> ShardingSphereVerifier.forEngine("clickhouse")
        else -> null
    }
}
