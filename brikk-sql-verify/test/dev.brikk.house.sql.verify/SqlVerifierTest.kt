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
        // Doris's real grammar accepts arrays; the Doris dialect now emits them first-class
        // (brikk extension, docs/brikk-extensions.md entry 7). This pins the engine-side truth.
        val verifier = SqlVerifiers.forEngine("doris")!!
        assertTrue(verifier.verify("SELECT ARRAY(1, 2, 3)").accepted)
        assertTrue(verifier.verify("CREATE TABLE t (a ARRAY<INT>)").accepted)
    }

    @Test
    fun dorisAcceptsBrikkArrayRenderings() {
        // brikk extension (docs/brikk-extensions.md entry 7): every array rendering the
        // Doris generator emits (asserted in brikk-sql's DorisArraysTest) must be accepted
        // by the real Doris FE parser. Keep in sync with DorisArraysTest.
        val verifier = SqlVerifiers.forEngine("doris")!!
        val renderings = listOf(
            // array literals (canonical constructor form)
            "SELECT ARRAY(1, 2, 3)",
            "SELECT ARRAY()",
            // ARRAY<T> type mapping: casts + DDL, including nesting
            "SELECT CAST(x AS ARRAY<INT>)",
            "SELECT CAST(ARRAY(1, 2) AS ARRAY<BIGINT>)",
            "CREATE TABLE t (a ARRAY<INT>, b ARRAY<ARRAY<STRING>>)",
            // subscript access (1-based in Doris)
            "SELECT arr[1] FROM t",
            "SELECT ARRAY(1, 2, 3)[1]",
            // table-position UNNEST
            "SELECT * FROM UNNEST(ARRAY(1, 2, 3))",
            "SELECT * FROM UNNEST(arr) AS t(c)",
            // LATERAL VIEW EXPLODE pass-through
            "SELECT c FROM t LATERAL VIEW EXPLODE(arr) tt AS c",
            // scalar-position EXPLODE fallback (flagged by the generator, still emitted;
            // the FE grammar accepts the shape)
            "SELECT EXPLODE(ARRAY(1, 2, 3))",
        )
        for (sql in renderings) {
            val result = verifier.verify(sql)
            assertTrue(result.accepted, "Doris FE parser rejected `$sql`: ${result.error}")
        }
    }
}
