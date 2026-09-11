package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryLastDayIntervalTest {
    @Test
    fun bq9ExactTranspileRows() {
        val month = "SELECT LAST_DAY(CAST('2008-11-25' AS DATE), MONTH)"
        val rows = listOf(
            Triple(month, "duckdb", "SELECT LAST_DAY(CAST('2008-11-25' AS DATE))"),
            Triple(month, "clickhouse", "SELECT LAST_DAY(CAST('2008-11-25' AS Nullable(DATE)))"),
            Triple(month, "mysql", "SELECT LAST_DAY(CAST('2008-11-25' AS DATE))"),
            Triple(
                month,
                "postgres",
                "SELECT CAST(DATE_TRUNC('MONTH', CAST('2008-11-25' AS DATE)) + INTERVAL '1 MONTH' - INTERVAL '1 DAY' AS DATE)",
            ),
            Triple(month, "spark", "SELECT LAST_DAY(CAST('2008-11-25' AS DATE))"),
            Triple(
                "SELECT LAST_DAY(DATE '2008-11-10', WEEK(SUNDAY))",
                "duckdb",
                "SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((13 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)",
            ),
            Triple(
                "SELECT LAST_DAY(DATE '2008-11-10', WEEK)",
                "duckdb",
                "SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((13 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)",
            ),
            Triple(
                "SELECT LAST_DAY(DATE '2008-11-10', WEEK(MONDAY))",
                "duckdb",
                "SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((7 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)",
            ),
            Triple(
                "SELECT LAST_DAY(DATE '2008-11-10', ISOWEEK)",
                "duckdb",
                "SELECT CAST(CAST('2008-11-10' AS DATE) + INTERVAL ((7 - EXTRACT(DAYOFWEEK FROM CAST('2008-11-10' AS DATE))) % 7) DAY AS DATE)",
            ),
        )

        assertEquals(9, rows.size)
        for ((source, target, expected) in rows) check(source, target, expected)
    }

    @Test
    fun everyBigqueryWeekStartLowersToDuckdb() {
        val constants = linkedMapOf(
            "SUNDAY" to 13,
            "MONDAY" to 7,
            "TUESDAY" to 8,
            "WEDNESDAY" to 9,
            "THURSDAY" to 10,
            "FRIDAY" to 11,
            "SATURDAY" to 12,
        )
        for ((day, constant) in constants) {
            check(
                "SELECT LAST_DAY(DATE '2024-01-03', WEEK($day))",
                "duckdb",
                "SELECT CAST(CAST('2024-01-03' AS DATE) + INTERVAL (($constant - EXTRACT(DAYOFWEEK FROM CAST('2024-01-03' AS DATE))) % 7) DAY AS DATE)",
            )
        }
    }

    @Test
    fun monthOnlyTargetsDiagnoseWeekLoss() {
        for (target in listOf("clickhouse", "mysql", "postgres", "spark", "presto")) {
            val tree = Dialects.BIGQUERY.parseOne("LAST_DAY(d, WEEK(TUESDAY))")
            val before = tree.copy()
            val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")
            generator.generate(tree)
            assertEquals(listOf("Date parts are not supported in LAST_DAY."), generator.unsupportedMessages, target)
            assertEquals(before, tree, target)
        }
    }

    @Test
    fun bq10LiteralAndRuntimeIntervalsLowerToDuckdb() {
        check(
            "SELECT ts + MAKE_INTERVAL(1, 2, minute => 5, day => 3)",
            "duckdb",
            "SELECT ts + INTERVAL '1 year 2 month 5 minute 3 day'",
        )
        check(
            "MAKE_INTERVAL(year => y, day => d)",
            "duckdb",
            "(INTERVAL (y) YEAR + INTERVAL (d) DAY)",
        )
        check(
            "MAKE_INTERVAL(month => NULL)",
            "duckdb",
            "INTERVAL (NULL) MONTH",
        )
        check("MAKE_INTERVAL()", "duckdb", "INTERVAL '0 second'")
    }

    private fun check(source: String, target: String, expected: String) {
        val tree = Dialects.BIGQUERY.parseOne(source)
        val before = tree.copy()
        val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")
        assertEquals(expected, generator.generate(tree), "$target: $source")
        assertEquals(emptyList(), generator.unsupportedMessages, "$target: $source")
        assertEquals(before, tree, "$target: $source")
    }
}
