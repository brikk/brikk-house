package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryParseTemporalTest {
    @Test
    fun sevenBq7RowsUseTargetTemporalAdapters() {
        val parseDatetime = "SELECT PARSE_DATETIME('%F %T', '2023-01-15 14:30:00')"
        val parseDatetimeDuckdb =
            "SELECT STRPTIME('1970 ' || '2023-01-15 14:30:00', '%Y ' || '%Y-%m-%d %H:%M:%S')"

        // bigquery-transpile-known-failures.json
        check(parseDatetime, parseDatetimeDuckdb, read = "bigquery", write = "duckdb")

        // duckdb-transpile-known-failures.json
        for ((source, expected) in listOf(
            "SELECT PARSE_TIME('%H:%M', '14:30')" to
                "SELECT CAST(STRPTIME('14:30', '%H:%M') AS TIME)",
            "SELECT PARSE_TIME('%H:%M:%E6S', '15:30:00.123456')" to
                "SELECT CAST(STRPTIME('15:30:00.123456', '%H:%M:%S.%f') AS TIME)",
            parseDatetime to parseDatetimeDuckdb,
            "SELECT PARSE_DATETIME('%a %b %e %I:%M:%S %Y', 'Thu Dec 25 07:30:00 2008')" to
                "SELECT STRPTIME('1970 ' || 'Thu Dec 25 07:30:00 2008', '%Y ' || '%a %b %-d %I:%M:%S %Y')",
            "SELECT PARSE_DATETIME('%H:%M:%E6S', '15:30:00.123456')" to
                "SELECT STRPTIME('1970 ' || '15:30:00.123456', '%Y ' || '%H:%M:%S.%f')",
        )) {
            check(source, expected, read = "bigquery", write = "duckdb")
        }

        // hive-transpile-known-failures.json. FORMAT_DATE does not accept DATETIME.
        check(
            "DATE_FORMAT('2020-01-01', 'yyyy-MM-dd HH:mm:ss')",
            "FORMAT_DATETIME('%Y-%m-%d %H:%M:%S', CAST('2020-01-01' AS DATETIME))",
            read = "hive",
            write = "bigquery",
        )
    }

    @Test
    fun nativeBigqueryRenderingStaysNative() {
        for (source in listOf(
            "SELECT PARSE_TIME('%H:%M', '14:30')",
            "SELECT PARSE_TIME('%H:%M:%E6S', '15:30:00.123456')",
            "SELECT PARSE_DATETIME('%F %T', '2023-01-15 14:30:00')",
            "SELECT PARSE_DATETIME('%H:%M:%E6S', '15:30:00.123456')",
        )) {
            check(source, source, read = "bigquery", write = "bigquery")
        }
    }

    @Test
    fun missingFieldsFullYearsMicrosecondsAndNullsKeepTheSourceAst() {
        for (source in listOf(
            "SELECT PARSE_DATETIME('%m-%d %H:%M:%S', '12-25 07:30:00')",
            "SELECT PARSE_DATETIME('%Y-%m-%d %H:%M:%S', '2023-01-15 14:30:00')",
            "SELECT PARSE_DATETIME('%H:%M:%E6S', '15:30:00.123456')",
            "SELECT PARSE_TIME('%H:%M:%E6S', '15:30:00.123456')",
            "SELECT PARSE_DATETIME('%F %T', CAST(NULL AS STRING))",
            "SELECT PARSE_TIME('%H:%M', CAST(NULL AS STRING))",
        )) {
            val tree = Dialects.BIGQUERY.parseOne(source)
            val before = tree.copy()
            val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
            generator.generate(tree)
            assertEquals(emptyList(), generator.unsupportedMessages, source)
            assertEquals(before, tree, "Source AST changed: $source")
        }
    }

    private fun check(source: String, expected: String, read: String, write: String) {
        val tree = Dialects.forName(read).parseOne(source)
        val before = tree.copy()
        val generator = Dialects.forName(write).generator(sourceDialect = read)
        assertEquals(expected, generator.generate(tree), "$read -> $write: $source")
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, tree, "Source AST changed: $source")
    }
}
