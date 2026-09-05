package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lightweight, no-native registry wiring test for [SqlOracles]. It asserts which verifier type
 * each engine resolves to without booting embedded PG or requiring libchdb — the ClickHouse
 * factory returns an instance even when the native library is absent (its verify() then reports
 * unavailable), and the PostgresVerifier is only constructed, never queried here.
 */
class SqlOraclesTest {

    @Test
    fun postgresResolvesToFidelityEmbeddedOracle() {
        val verifier = SqlOracles.forEngine("postgres")
        assertNotNull(verifier)
        assertTrue(verifier is PostgresVerifier, "postgres should be the embedded PG oracle")
        assertEquals("postgres", verifier.engine)
        // Non-advisory: constructing does not boot the server; closing is safe if it did.
        verifier.close()
    }

    @Test
    fun clickhouseResolvesToChdbOracle() {
        // Do NOT require libchdb — just that the factory returns a ClickhouseVerifier instance.
        val verifier = SqlOracles.forEngine("clickhouse")
        assertNotNull(verifier)
        assertTrue(verifier is ClickhouseVerifier, "clickhouse should be the chDB oracle")
        assertEquals("clickhouse", verifier.engine)
        verifier.close()
    }

    @Test
    fun trinoDorisDuckdbResolveToAuthoritativeJvmVerifiers() {
        for (engine in listOf("trino", "doris", "duckdb")) {
            val verifier = SqlOracles.forEngine(engine)
            assertNotNull(verifier, "expected a verifier for $engine")
            assertEquals(engine, verifier.engine)
            assertFalse(
                verifier is ShardingSphereVerifier,
                "$engine should use its authoritative JVM parser, not the advisory oracle",
            )
        }
    }

    @Test
    fun mysqlAndHiveResolveToAdvisoryShardingSphereVerifier() {
        for (engine in listOf("mysql", "hive")) {
            val verifier = SqlOracles.forEngine(engine)
            assertNotNull(verifier, "expected a verifier for $engine")
            assertTrue(verifier is ShardingSphereVerifier, "$engine should be the advisory ShardingSphere oracle")
            assertTrue(
                verifier.verify("SELECT * FROM t").advisory,
                "$engine results should be advisory",
            )
        }
    }

    @Test
    fun unsupportedEngineResolvesToNull() {
        assertNull(SqlOracles.forEngine("no-such-engine"))
    }

    @Test
    fun engineLookupIsCaseInsensitive() {
        assertNotNull(SqlOracles.forEngine("POSTGRES"))
        assertNotNull(SqlOracles.forEngine("Trino"))
    }
}
