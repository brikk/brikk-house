package dev.brikk.house.sql.verify

/**
 * Registry of high-fidelity SQL verification oracles — the "fuller" tier that layers the
 * genuinely heavy real-engine checks on top of the lightweight [SqlVerifiers] registry from
 * brikk-sql-verify.
 *
 * Where a real engine is available offline, [forEngine] returns it (authoritative,
 * non-advisory): `"postgres"` boots an embedded native PostgreSQL ([PostgresVerifier]) and
 * `"clickhouse"` runs chDB `EXPLAIN AST` in-process ([ClickhouseVerifier]). For `"trino"`,
 * `"doris"`, and `"duckdb"` the JVM parsers are already engine-exact and small enough to serve
 * both tiers, so this registry simply delegates to [SqlVerifiers.forEngine]. `"mysql"` and
 * `"hive"` have no authoritative engine wired here, so they too delegate to [SqlVerifiers] and
 * come back as the advisory ShardingSphere grammar oracle.
 *
 * This is the heavyweight tier: it pulls embedded PostgreSQL and chDB and is intended for
 * offline/extreme validation (corpus gates), not for shipping inside an IDE plugin. Ship-safe
 * consumers should use [SqlVerifiers.forEngine] instead.
 *
 * Verifier instances are created per call — callers that verify repeatedly (corpus gates)
 * should hold on to the returned instance to amortize cold-start costs (embedded-PG boot,
 * chDB native library load), and [close] any that are [AutoCloseable].
 */
object SqlOracles {
    /**
     * Returns the highest-fidelity available verifier for [name] (case-insensitive), or null
     * when the engine is unsupported.
     *
     * Supported engines: `"trino"`, `"duckdb"`, `"doris"`, `"postgres"`, `"clickhouse"`,
     * `"mysql"`, `"hive"`.
     */
    fun forEngine(name: String): SqlVerifier? = when (name.lowercase()) {
        // Engine-exact JVM parsers, authoritative on both tiers (small enough to ship).
        "trino", "doris", "duckdb" -> SqlVerifiers.forEngine(name)
        // Real embedded engines: authoritative, non-advisory.
        "postgres" -> PostgresVerifier()
        "clickhouse" -> ClickhouseVerifier.create()
        // No authoritative engine wired: falls back to the advisory ShardingSphere verifier.
        "mysql", "hive" -> SqlVerifiers.forEngine(name)
        else -> null
    }
}
