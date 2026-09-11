package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryTemporalTruncationTest {
    @Test
    fun duckdbTruncatesDateDatetimeAndTimestampOnTheirOwnTiers() {
        for ((source, expected) in listOf(
            "SELECT DATE_TRUNC(DATE '2008-11-10', ISOWEEK)" to
                "SELECT CAST(DATE_TRUNC('WEEK', CAST('2008-11-10' AS DATE)) AS DATE)",
            "SELECT DATETIME_TRUNC('2023-01-01T01:01:01', HOUR)" to
                "SELECT DATE_TRUNC('HOUR', CAST('2023-01-01T01:01:01' AS TIMESTAMP))",
            "SELECT DATETIME_TRUNC(DATETIME '2008-11-10 14:30:00', WEEK(SUNDAY))" to
                "SELECT DATE_TRUNC('WEEK', CAST(CAST('2008-11-10 14:30:00' AS TIMESTAMP) AS TIMESTAMP) + INTERVAL '1' DAY) + INTERVAL '-1' DAY",
        )) check(source, expected)

        for (source in listOf(
            "SELECT TIMESTAMP_TRUNC(ts, WEEK(SUNDAY))",
            "SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00', WEEK)",
            "SELECT TIMESTAMP_TRUNC(TIMESTAMP '2008-11-10 14:30:00+00', WEEK, 'America/New_York')",
        )) {
            val generated = generate(source)
            assertTrue(generated.contains("AT TIME ZONE"), generated)
            assertTrue(generated.contains("DATE_TRUNC('WEEK'"), generated)
            assertTrue(!generated.contains("WEEK_START"), generated)
        }
    }

    @Test
    fun allWeekStartsAndIsoWeekGenerateWithoutDiagnostics() {
        val units = listOf("WEEK", "ISOWEEK") +
            listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY").map { "WEEK($it)" }
        for (unit in units) {
            for (function in listOf("DATE_TRUNC", "DATETIME_TRUNC", "TIMESTAMP_TRUNC")) {
                val value = when (function) {
                    "DATE_TRUNC" -> "DATE '2024-01-03'"
                    "DATETIME_TRUNC" -> "DATETIME '2024-01-03 12:34:56'"
                    else -> "TIMESTAMP '2024-01-03 12:34:56+00'"
                }
                val generated = generate("$function($value, $unit)")
                assertTrue(!generated.contains("WEEK_START"), generated)
            }
        }
    }

    private fun generate(source: String): String {
        val tree = Dialects.BIGQUERY.parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        val sql = generator.generate(tree)
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, tree)
        return sql
    }

    private fun check(source: String, expected: String) = assertEquals(expected, generate(source), source)
}
