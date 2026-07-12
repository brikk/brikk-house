package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * brikk extension (docs/brikk-extensions.md entry 7): first-class arrays for the Doris
 * target. sqlglot's Doris dialect inherits MySQL's array rejection and 0-based
 * INDEX_OFFSET; Doris genuinely supports arrays (1-based). Every rendering asserted here
 * is pinned against the real Doris FE parser in
 * brikk-sql-verify/test/.../SqlVerifierTest.dorisAcceptsBrikkArrayRenderings.
 */
class DorisArraysTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "doris").sql("doris")

    // -- literals ---------------------------------------------------------------------

    @Test
    fun arrayLiteralRoundTripsWithoutFlag() {
        val generator = Dialects.forName("doris").generator()
        val out = generator.generate(Dialects.forName("doris").parseOne("SELECT ARRAY(1, 2, 3)"))
        assertEquals("SELECT ARRAY(1, 2, 3)", out)
        assertEquals(emptyList(), generator.unsupportedMessages)
    }

    @Test
    fun duckdbBracketLiteralBecomesArrayConstructor() {
        assertEquals(
            "SELECT ARRAY(1, 2, 3)",
            transpile("SELECT [1, 2, 3]", read = "duckdb", write = "doris"),
        )
        assertEquals("SELECT ARRAY()", transpile("SELECT []", read = "duckdb", write = "doris"))
    }

    // -- types / casts ----------------------------------------------------------------

    @Test
    fun castToArrayTypeUsesDorisAngleBracketSyntax() {
        assertEquals(
            "SELECT CAST(x AS ARRAY<INT>)",
            transpile("SELECT CAST(x AS INT[])", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT CAST(ARRAY(1, 2) AS ARRAY<BIGINT>)",
            transpile("SELECT CAST([1, 2] AS BIGINT[])", read = "duckdb", write = "doris"),
        )
        assertEquals("SELECT CAST(x AS ARRAY<INT>)", roundTrip("SELECT CAST(x AS ARRAY<INT>)"))
    }

    @Test
    fun createTableWithArrayColumns() {
        assertEquals(
            "CREATE TABLE t (a ARRAY<INT>, b ARRAY<ARRAY<STRING>>)",
            transpile("CREATE TABLE t (a INT[], b TEXT[][])", read = "duckdb", write = "doris"),
        )
    }

    // -- subscripts (Doris arrays are 1-based, like duckdb; unlike MySQL's offset 0) ----

    @Test
    fun subscriptKeepsOneBasedIndexFromDuckdb() {
        // sqlglot (INDEX_OFFSET inherited from MySQL = 0) mis-renders this as arr[0]
        assertEquals(
            "SELECT arr[1] FROM t",
            transpile("SELECT arr[1] FROM t", read = "duckdb", write = "doris"),
        )
    }

    @Test
    fun subscriptKeepsOneBasedIndexToDuckdb() {
        // sqlglot mis-renders this as arr[2]
        assertEquals(
            "SELECT arr[1] FROM t",
            transpile("SELECT arr[1] FROM t", read = "doris", write = "duckdb"),
        )
    }

    @Test
    fun subscriptRoundTrips() {
        assertEquals("SELECT arr[1] FROM t", roundTrip("SELECT arr[1] FROM t"))
        assertEquals("SELECT ARRAY(1, 2, 3)[1]", roundTrip("SELECT ARRAY(1, 2, 3)[1]"))
    }

    // -- unnest -----------------------------------------------------------------------

    @Test
    fun tableUnnestIsRenderedForDoris() {
        // Doris's FE grammar accepts UNNEST in table position (verifier-pinned)
        val generator = Dialects.forName("doris").generator()
        val out = generator.generate(
            Dialects.forName("duckdb").parseOne("SELECT * FROM UNNEST([1, 2, 3])")
        )
        assertEquals("SELECT * FROM UNNEST(ARRAY(1, 2, 3))", out)
        assertEquals(emptyList(), generator.unsupportedMessages)
    }

    @Test
    fun scalarUnnestIsFlaggedWithAccurateMessage() {
        // No clean Doris equivalent: EXPLODE is only valid in LATERAL VIEW. The fallback
        // is still emitted (matching the Python oracle's output), but the flag no longer
        // blames MySQL's array support.
        val generator = Dialects.forName("doris").generator()
        val out = generator.generate(Dialects.forName("duckdb").parseOne("SELECT UNNEST([1, 2, 3])"))
        assertEquals("SELECT EXPLODE(ARRAY(1, 2, 3))", out)
        assertTrue(generator.unsupportedMessages.any { "LATERAL VIEW" in it })
        assertTrue(generator.unsupportedMessages.none { "MySQL" in it })
    }

    @Test
    fun lateralViewExplodeIsNotFlagged() {
        val generator = Dialects.forName("doris").generator()
        val out = generator.generate(
            Dialects.forName("doris").parseOne("SELECT c FROM t LATERAL VIEW EXPLODE(arr) tt AS c")
        )
        assertEquals("SELECT c FROM t LATERAL VIEW EXPLODE(arr) tt AS c", out)
        assertEquals(emptyList(), generator.unsupportedMessages)
    }

    // -- array ops that stay flagged (accurately) ---------------------------------------

    @Test
    fun setContainmentOperatorsKeepAccurateFlag() {
        // Doris's ARRAY_CONTAINS_ALL is order-sensitive (subsequence match), so duckdb's
        // @> set containment is still unsupported — but the message no longer blames MySQL.
        val generator = Dialects.forName("doris").generator()
        generator.generate(Dialects.forName("duckdb").parseOne("SELECT arr1 @> arr2"))
        assertTrue(generator.unsupportedMessages.any { "Doris" in it })
        assertTrue(generator.unsupportedMessages.none { "MySQL" in it })
    }
}
