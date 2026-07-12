package dev.brikk.house.sql.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlVerifierTest {

    // -- registry --------------------------------------------------------------------------

    @Test
    fun forEngineReturnsVerifiersForSupportedEngines() {
        for (engine in listOf("trino", "duckdb", "doris")) {
            val verifier = SqlVerifiers.forEngine(engine)
            assertNotNull(verifier, "expected a verifier for $engine")
            assertEquals(engine, verifier.engine)
        }
        assertNotNull(SqlVerifiers.forEngine("TRINO"), "engine lookup is case-insensitive")
    }

    @Test
    fun forEngineReturnsNullForUnsupportedEngines() {
        // Postgres: deliberately unsupported until a mature JVM libpg_query binding exists.
        assertNull(SqlVerifiers.forEngine("postgres"))
        assertNull(SqlVerifiers.forEngine("no-such-engine"))
    }

    // -- trino ----------------------------------------------------------------------------

    @Test
    fun trinoAcceptsValidSql() {
        val result = SqlVerifiers.forEngine("trino")!!.verify("SELECT a FROM t WHERE b = 1")
        assertTrue(result.accepted)
        assertNull(result.error)
    }

    @Test
    fun trinoRejectsInvalidSqlWithErrorAndPosition() {
        val result = SqlVerifiers.forEngine("trino")!!.verify("SELECT FROM WHERE")
        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertEquals(1, result.line)
        assertEquals(8, result.col)
    }

    @Test
    fun trinoVerifiesExpressionFragments() {
        val verifier = SqlVerifiers.forEngine("trino")!!
        assertTrue(verifier.verifyExpression("JSON_QUERY(x, 'strict $.a' WITH ARRAY WRAPPER)").accepted)
        assertFalse(verifier.verify("JSON_QUERY(x, 'strict $.a' WITH ARRAY WRAPPER)").accepted, "bare expression is not a statement")
        assertFalse(verifier.verifyExpression("1 +").accepted)
    }

    // -- duckdb ---------------------------------------------------------------------------

    @Test
    fun duckdbAcceptsValidSql() {
        val verifier = SqlVerifiers.forEngine("duckdb")!!
        assertTrue(verifier.verify("SELECT a FROM t WHERE b = 1").accepted, "select goes through json_serialize_sql")
        assertTrue(verifier.verify("CREATE TABLE t (a INT)").accepted, "non-select goes through the prepare fallback")
        assertTrue(
            verifier.verify("SELECT * FROM completely_unknown_table").accepted,
            "binder errors (unknown table) are not grammar rejections",
        )
    }

    @Test
    fun duckdbRejectsInvalidSqlWithErrorAndPosition() {
        val result = SqlVerifiers.forEngine("duckdb")!!.verify("SELECT FROM WHERE")
        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertEquals(1, result.line)
        assertEquals(13, result.col)
    }

    @Test
    fun duckdbVerifiesExpressionFragments() {
        val verifier = SqlVerifiers.forEngine("duckdb")!!
        assertTrue(verifier.verifyExpression("x -> '$.family'").accepted)
        assertFalse(verifier.verifyExpression("1 +").accepted)
    }

    // -- doris ----------------------------------------------------------------------------

    @Test
    fun dorisAcceptsValidSql() {
        val result = SqlVerifiers.forEngine("doris")!!.verify("SELECT a FROM t WHERE b = 1")
        assertTrue(result.accepted)
    }

    @Test
    fun dorisRejectsInvalidSqlWithErrorAndPosition() {
        val result = SqlVerifiers.forEngine("doris")!!.verify("SELECT FROM WHERE")
        assertFalse(result.accepted)
        assertNotNull(result.error)
        assertEquals(1, result.line)
        assertEquals(8, result.col)
    }

    @Test
    fun dorisVerifiesExpressionFragments() {
        val verifier = SqlVerifiers.forEngine("doris")!!
        assertTrue(verifier.verifyExpression("DATE_TRUNC(c2, 'MONTH')").accepted)
        assertFalse(verifier.verifyExpression("1 +").accepted)
    }

    @Test
    fun dorisParserSupportsArrays() {
        // Doris's real grammar accepts arrays; our Doris dialect (MySQL-inherited) still
        // rejects them — a queued dialect fix. This pins the engine-side truth.
        val verifier = SqlVerifiers.forEngine("doris")!!
        assertTrue(verifier.verify("SELECT ARRAY(1, 2, 3)").accepted)
        assertTrue(verifier.verify("CREATE TABLE t (a ARRAY<INT>)").accepted)
    }
}
