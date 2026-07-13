package dev.brikk.house.sql.verify

import org.apache.shardingsphere.sql.parser.api.CacheOption
import org.apache.shardingsphere.sql.parser.api.SQLParserEngine

/**
 * A portable, pure-JVM **grammar oracle** backed by Apache ShardingSphere's re-implemented SQL
 * parser (5.5.2). It answers "is this SQL syntactically legal for the dialect?" without booting
 * a real engine or a native library, which makes it cheap enough to ship and run in an IDE.
 *
 * This is the **advisory tier** ([VerifyResult.advisory] is always true): the parser is a port
 * of the dialect's grammar, not the engine's own parser, so it can diverge from engine-exact
 * behaviour. It is a syntax check only — there is no catalog, no name/arity resolution, and no
 * semantic analysis. Consumers may treat an advisory `accepted = false` as a non-blocking
 * warning rather than a hard rejection.
 *
 * Fidelity notes per dialect (from offline validation; these are intentional and NOT bugs to
 * "fix" here):
 *
 * - **PostgreSQL / MySQL:** grammars are excellent.
 * - **Hive:** decent, but rejects valid `LATERAL VIEW`. Requires `shardingsphere-infra-database-hive`
 *   on the classpath to load its DB-type SPI (declared in module.yaml).
 * - **ClickHouse:** imperfect — accepts invalid `SELECT FROM WHERE`, rejects valid `numbers(3)`,
 *   `PREWHERE`/`SETTINGS`, `{param:UInt32}`, and `CAST(x AS Nullable(String))`. For engine-exact
 *   ClickHouse verification use the chDB-backed `ClickhouseVerifier` in `brikk-sql-verify-chdb`.
 *
 * ShardingSphere logs cosmetic `ANTLR Tool version 4.10.1 ... does not match runtime 4.13.2`
 * warnings on first parse; they are benign — the 5.5.2 grammars parse correctly under the
 * antlr4-runtime 4.13.2 that trino-parser/Doris force onto the classpath.
 *
 * The [SQLParserEngine] is the expensive part; hold one instance per dialect and reuse it.
 */
class ShardingSphereVerifier(
    override val engine: String,
    private val databaseType: String,
) : SqlVerifier {

    private val parser: SQLParserEngine =
        SQLParserEngine(databaseType, CacheOption(CACHE_INITIAL_CAPACITY, CACHE_MAXIMUM_SIZE))

    override fun verify(sql: String): VerifyResult =
        try {
            parser.parse(sql, false)
            VerifyResult(accepted = true, advisory = true)
        } catch (error: Throwable) {
            VerifyResult(accepted = false, error = error.message, advisory = true)
        }

    /**
     * ShardingSphere has no expression-only entry point, so wrap the fragment in `SELECT` and
     * delegate. On reject we shift a reported column back past the `SELECT ` prefix (mirroring
     * ClickhouseVerifier.verifyExpression); ShardingSphere rarely reports positions, so this is
     * best-effort.
     */
    override fun verifyExpression(sql: String): VerifyResult {
        val result = verify("$EXPRESSION_PREFIX$sql")
        if (result.accepted || result.line != 1 || result.col == null) return result
        return result.copy(col = (result.col - EXPRESSION_PREFIX.length).coerceAtLeast(1))
    }

    companion object {
        private const val EXPRESSION_PREFIX = "SELECT "
        // ShardingSphere parse-tree cache sizing; matches its own default CacheOption.
        private const val CACHE_INITIAL_CAPACITY = 128
        private const val CACHE_MAXIMUM_SIZE = 1024L

        /** Maps a brikk engine name to a ShardingSphere database type, or null if unsupported. */
        fun forEngine(engine: String): ShardingSphereVerifier? = when (engine.lowercase()) {
            "postgres" -> ShardingSphereVerifier("postgres", "PostgreSQL")
            "mysql" -> ShardingSphereVerifier("mysql", "MySQL")
            "hive" -> ShardingSphereVerifier("hive", "Hive")
            "clickhouse" -> ShardingSphereVerifier("clickhouse", "ClickHouse")
            else -> null
        }
    }
}
