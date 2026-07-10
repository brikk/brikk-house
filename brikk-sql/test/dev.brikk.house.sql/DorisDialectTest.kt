package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.dialects.sql
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
