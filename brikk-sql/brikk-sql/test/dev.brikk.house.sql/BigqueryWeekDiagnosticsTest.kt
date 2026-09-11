package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryWeekDiagnosticsTest {
    private val warning =
        "WEEK(SUNDAY) is not supported; falling back to the default week start day"

    @Test
    fun bq12RowsDiagnoseWeekStartLossAndFallBackToWeek() {
        val rows = listOf(
            Triple("SELECT DATE_TRUNC(d, WEEK(SUNDAY))", "spark", "SELECT TRUNC(d, 'WEEK')"),
            Triple("SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))", "clickhouse", "SELECT dateTrunc('WEEK', ts)"),
            Triple(
                "SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))",
                "mysql",
                "SELECT DATE_ADD('0000-01-01 00:00:00', INTERVAL (TIMESTAMPDIFF(WEEK, '0000-01-01 00:00:00', ts)) WEEK)",
            ),
            Triple("SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))", "spark", "SELECT DATE_TRUNC('WEEK', ts)"),
            Triple("SELECT EXTRACT(WEEK(THURSDAY) FROM d)", "hive", "SELECT EXTRACT(WEEK FROM d)"),
            Triple("SELECT EXTRACT(WEEK(THURSDAY) FROM d)", "spark", "SELECT EXTRACT(WEEK FROM d)"),
        )

        assertEquals(6, rows.size)
        for ((source, target, expected) in rows) {
            val tree = Dialects.BIGQUERY.parseOne(source)
            val before = tree.copy()
            val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")

            assertEquals(expected, generator.generate(tree), "$target: $source")
            val expectedWarning = if ("THURSDAY" in source) warning.replace("SUNDAY", "THURSDAY") else warning
            assertEquals(listOf(expectedWarning), generator.unsupportedMessages, "$target: $source")
            assertEquals(before, tree, "$target: $source")
        }
    }

    @Test
    fun bigqueryKeepsNativeWeekRenderingWithoutDiagnostics() {
        val rows = listOf(
            "SELECT DATE_TRUNC(d, WEEK(SUNDAY))" to "SELECT DATE_TRUNC(d, WEEK)",
            "SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))" to "SELECT TIMESTAMP_TRUNC(ts, WEEK)",
            "SELECT EXTRACT(WEEK(THURSDAY) FROM d)" to "SELECT EXTRACT(WEEK(THURSDAY) FROM d)",
        )

        for ((source, expected) in rows) {
            val tree = Dialects.BIGQUERY.parseOne(source)
            val before = tree.copy()
            val generator = Dialects.BIGQUERY.generator(sourceDialect = "bigquery")

            assertEquals(expected, generator.generate(tree), source)
            assertEquals(emptyList(), generator.unsupportedMessages, source)
            assertEquals(before, tree, source)
        }
    }
}
