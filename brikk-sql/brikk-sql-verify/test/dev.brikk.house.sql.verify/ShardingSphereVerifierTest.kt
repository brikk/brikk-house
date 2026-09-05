package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the advisory ShardingSphere grammar oracle's fidelity findings for the four dialects and
 * that every result is marked [VerifyResult.advisory] = true. These are the documented
 * limitations of the advisory tier — not bugs to "fix" in this module.
 */
class ShardingSphereVerifierTest {

    // -- postgres -------------------------------------------------------------------------

    @Test
    fun postgresAcceptsValidAndRejectsInvalid() {
        val v = ShardingSphereVerifier.forEngine("postgres")!!
        val accepted = v.verify("SELECT * FROM users WHERE metadata->>'role' = 'admin'")
        assertTrue(accepted.accepted, "expected acceptance: ${accepted.error}")
        assertTrue(accepted.advisory)

        val rejected = v.verify("SELECT FROM WHERE")
        assertFalse(rejected.accepted)
        assertTrue(rejected.advisory)
    }

    // -- mysql ----------------------------------------------------------------------------

    @Test
    fun mysqlAcceptsValidAndRejectsInvalid() {
        val v = ShardingSphereVerifier.forEngine("mysql")!!
        val accepted = v.verify("SELECT `col` FROM t LIMIT 5")
        assertTrue(accepted.accepted, "expected acceptance: ${accepted.error}")
        assertTrue(accepted.advisory)

        val rejected = v.verify("SELECT FROM WHERE")
        assertFalse(rejected.accepted)
        assertTrue(rejected.advisory)
    }

    // -- hive -----------------------------------------------------------------------------

    @Test
    fun hiveAcceptsValidAndRejectsInvalid() {
        val v = ShardingSphereVerifier.forEngine("hive")!!
        val accepted = v.verify("SELECT * FROM t")
        assertTrue(accepted.accepted, "expected acceptance: ${accepted.error}")
        assertTrue(accepted.advisory)

        val rejected = v.verify("SELECT FROM WHERE")
        assertFalse(rejected.accepted)
        assertTrue(rejected.advisory)

        // Advisory limitation: Hive grammar false-rejects valid LATERAL VIEW. Pinned here so the
        // limitation is documented; do NOT treat this as a bug of the advisory tier.
        val lateralView = v.verify("SELECT t.c FROM src LATERAL VIEW EXPLODE(arr) t AS c")
        assertFalse(lateralView.accepted, "known advisory false-reject of LATERAL VIEW")
    }

    // -- clickhouse -----------------------------------------------------------------------

    @Test
    fun clickhouseAcceptsValidAndIsAdvisory() {
        val v = ShardingSphereVerifier.forEngine("clickhouse")!!
        val accepted = v.verify("SELECT lowerUTF8(x) FROM t")
        assertTrue(accepted.accepted, "expected acceptance: ${accepted.error}")
        assertTrue(accepted.advisory)

        // Advisory limitation: the ClickHouse grammar false-ACCEPTS invalid `SELECT FROM WHERE`,
        // which is exactly why ClickHouse is advisory-only here (use the chDB ClickhouseVerifier
        // in brikk-sql-verify-chdb for engine-exact fidelity). Not asserted as a rejection.
    }

    // -- registry -------------------------------------------------------------------------

    @Test
    fun forEngineRegistryReturnsAdvisoryVerifiers() {
        for (engine in listOf("postgres", "mysql", "hive", "clickhouse")) {
            val verifier = SqlVerifiers.forEngine(engine)!!
            val result = verifier.verify("SELECT * FROM t")
            assertTrue(result.advisory, "$engine should return an advisory verifier")
        }
    }

    @Test
    fun forEngineReturnsNullForUnsupported() {
        assertFalse(ShardingSphereVerifier.forEngine("nope") != null)
    }
}
