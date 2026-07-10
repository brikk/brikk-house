package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand assertions for the MySQL dialect wiring: identifier quoting, TIME_MAPPING
 * conversion, LIMIT syntax, and cross-dialect transpilation through the registry.
 */
class MysqlDialectTest {

    private fun roundTrip(sqlText: String): String = parseOne(sqlText, "mysql").sql("mysql")

    @Test
    fun backtickIdentifierRoundTrip() {
        assertEquals("SELECT `a` FROM `foo`", roundTrip("SELECT `a` FROM `foo`"))
    }

    @Test
    fun reservedKeywordIsBacktickQuoted() {
        // "select" is in MySQL's RESERVED_KEYWORDS, so it stays quoted even unforced
        assertEquals("SELECT `select` FROM t", roundTrip("SELECT `select` FROM t"))
    }

    @Test
    fun timeMappingConversion() {
        // mysql %i (minutes) -> python %M at parse time, and back on generation
        val ast = parseOne("SELECT STR_TO_DATE('01-2024', '%i-%Y')", "mysql")
        assertTrue(ast.sql() .contains("'%M-%Y'"), "parse converts mysql format to strftime: ${ast.sql()}")
        assertEquals("SELECT STR_TO_DATE('01-2024', '%i-%Y')", ast.sql("mysql"))
    }

    @Test
    fun limitOffsetSyntax() {
        // MySQL's old-style LIMIT offset, count normalizes to LIMIT ... OFFSET ...
        assertEquals("SELECT x FROM t LIMIT 10 OFFSET 5", roundTrip("SELECT x FROM t LIMIT 5, 10"))
    }

    @Test
    fun transpileBaseToMysql() {
        // base TIMESTAMP type maps to DATETIME in MySQL
        assertEquals(
            "CREATE TABLE t (c DATETIME)",
            transpile("CREATE TABLE t (c TIMESTAMP)", read = "", write = "mysql"),
        )
    }

    @Test
    fun transpileMysqlToBase() {
        // GROUP_CONCAT SEPARATOR parses to exp.GroupConcat and generates back to base
        assertEquals(
            "SELECT GROUP_CONCAT(x SEPARATOR ',') FROM t",
            transpile("SELECT GROUP_CONCAT(x SEPARATOR ',') FROM t", read = "mysql", write = "mysql"),
        )
        assertEquals(
            "SELECT `a` /* mysql */",
            Dialects.forName("mysql").parseOne("SELECT `a` /* mysql */").sql("mysql"),
        )
    }
}
