package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hand assertions for the Doris dialect wiring, each verified against the Python
 * oracle (reference/sqlglot v30.12.0-44-g93d16591): reserved-keyword quoting,
 * type mapping, mysql->doris and doris->base transpilation through the registry.
 */
class DorisDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "doris").sql("doris")

    @Test
    fun dorisReservedKeywordIsBacktickQuoted() {
        // "string" is Doris-reserved (not MySQL-reserved), so it stays quoted unforced
        assertEquals("SELECT `string` FROM t", roundTrip("SELECT `string` FROM t"))
    }

    @Test
    fun mysqlGroupConcatToDoris() {
        // Doris drops the GROUP_CONCAT function parser: SEPARATOR form becomes csv args
        assertEquals(
            "SELECT GROUP_CONCAT(x, ';') FROM t",
            transpile("SELECT GROUP_CONCAT(x SEPARATOR ';') FROM t", read = "mysql", write = "doris"),
        )
    }

    @Test
    fun baseToDorisTypeMapping() {
        // TEXT -> STRING and TIMESTAMPTZ -> DATETIME under Doris's TYPE_MAPPING
        assertEquals(
            "CREATE TABLE t (c STRING, d DATETIME)",
            transpile("CREATE TABLE t (c TEXT, d TIMESTAMPTZ)", read = "", write = "doris"),
        )
    }

    @Test
    fun dorisDateTruncToBase() {
        // Doris DATE_TRUNC(datetime, unit) parses to exp.TimestampTrunc
        assertEquals(
            "TIMESTAMP_TRUNC('2010-12-02 19:28:30', HOUR)",
            transpile("DATE_TRUNC('2010-12-02 19:28:30', 'HOUR')", read = "doris", write = ""),
        )
    }

    @Test
    fun currentDateRendersWithParens() {
        // Doris removes CURRENT_DATE from NO_PAREN_FUNCTIONS: always CURRENT_DATE()
        assertEquals("SELECT CURRENT_DATE()", transpile("SELECT CURRENT_DATE", read = "mysql", write = "doris"))
    }

    @Test
    fun renameTableStripsDb() {
        // RENAME_TABLE_WITH_DB = False drops the db qualifier from the rename target
        assertEquals(
            "ALTER TABLE db.t1 RENAME t2",
            transpile("ALTER TABLE db.t1 RENAME TO db.t2", read = "mysql", write = "doris"),
        )
    }

    @Test
    fun lagGetsExplicitOffsetAndDefault() {
        // Doris always renders LAG/LEAD with explicit offset and default arguments
        assertEquals(
            "SELECT LAG(a, 1, NULL) OVER (ORDER BY b) FROM t",
            transpile("SELECT LAG(a) OVER (ORDER BY b) FROM t", read = "", write = "doris"),
        )
    }

    // ------------------------------------------------------------------
    // brikk extension (NOT sqlglot parity): FILTER -> CASE rewrite for Doris.
    // Doris has no FILTER clause; sqlglot passes it through (invalid Doris SQL).
    // ------------------------------------------------------------------

    @Test
    fun filterCountStarRewritesToCaseOne() {
        assertEquals(
            "SELECT COUNT(CASE WHEN `status` = 'ok' THEN 1 END) FROM `events`",
            transpile("SELECT COUNT(*) FILTER(WHERE status = 'ok') FROM events", read = "duckdb", write = "doris"),
        )
    }

    @Test
    fun filterSumRewritesToCaseExpr() {
        assertEquals(
            "SELECT SUM(CASE WHEN region = 'EU' THEN amount END) AS eu_total FROM sales",
            transpile(
                "SELECT SUM(amount) FILTER(WHERE region = 'EU') AS eu_total FROM sales",
                read = "duckdb",
                write = "doris",
            ),
        )
    }

    @Test
    fun filterCountDistinctRewritesInsideDistinct() {
        assertEquals(
            "SELECT COUNT(DISTINCT CASE WHEN amount > 100 THEN user_id END) FROM sales",
            transpile(
                "SELECT COUNT(DISTINCT user_id) FILTER(WHERE amount > 100) FROM sales",
                read = "duckdb",
                write = "doris",
            ),
        )
    }

    @Test
    fun filterArrayAggSimpleRewritesThroughCollectList() {
        // ARRAY_AGG -> COLLECT_LIST is the normal Doris mapping; the CASE lands inside it
        assertEquals(
            "SELECT COLLECT_LIST(CASE WHEN x > 0 THEN x END) FROM t",
            transpile("SELECT ARRAY_AGG(x) FILTER(WHERE x > 0) FROM t", read = "duckdb", write = "doris"),
        )
    }

    @Test
    fun filterOnNonAllowlistedAggregateRaisesUnsupported() {
        // GROUP_CONCAT separators are not result-identical under the CASE rewrite
        val error = assertFailsWith<UnsupportedError> {
            transpile("SELECT STRING_AGG(x, ',') FILTER(WHERE y) FROM t", read = "duckdb", write = "doris")
        }
        assertTrue(
            error.message!!.contains("Doris has no FILTER clause"),
            "unexpected message: ${error.message}",
        )
    }

    @Test
    fun filterOnOrderedArrayAggRaisesUnsupported() {
        assertFailsWith<UnsupportedError> {
            transpile("SELECT ARRAY_AGG(x ORDER BY y) FILTER(WHERE z) FROM t", read = "duckdb", write = "doris")
        }
    }
}
