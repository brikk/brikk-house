package dev.brikk.house.sql.verify

import dev.brikk.house.sql.shape.SqlFragment
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalDiffResultTest {
    private fun check(expression: String, expected: Long, timezone: String = "UTC") {
        val fragment = SqlFragment("SELECT $expression", "bigquery")
        for (target in listOf("presto", "trino")) {
            val result = fragment.transpileTo(target)
            assertTrue(result.unsupportedMessages.isEmpty(), result.unsupportedMessages.toString())
            assertFalse(result.sql.contains("WEEK_START"), result.sql)
            assertTrue(SqlVerifiers.forEngine("trino")!!.verify(result.sql).accepted, result.sql)
            val container = System.getenv("BRIKK_TRINO_CONTAINER") ?: continue
            val process = ProcessBuilder("docker", "exec", container, "trino", "--execute", result.sql,
                "--output-format", "TSV", "--timezone", timezone).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Trino query timed out")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), "${result.sql}\n$output")
                assertEquals(expected.toString(), output.trim(), result.sql)
            } finally {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun timestampUnitsSurviveNativeRenderingAndCrossDialectLowering() {
        val fragment = SqlFragment("SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), minute)", "bigquery")
        assertEquals("SELECT TIMESTAMP_DIFF(TIMESTAMP_SECONDS(60), TIMESTAMP_SECONDS(0), MINUTE)",
            fragment.transpileTo("bigquery").sql)
        assertEquals("SELECT DATE_DIFF('MINUTE', FROM_UNIXTIME(0), FROM_UNIXTIME(60))", fragment.transpileTo("presto").sql)
        for ((end, start, expected) in listOf(Triple(120, 0, 2L), Triple(0, 120, -2L), Triple(119, 60, 0L), Triple(60, 119, 0L))) {
            check("TIMESTAMP_DIFF(TIMESTAMP_SECONDS($end), TIMESTAMP_SECONDS($start), MINUTE)", expected)
        }
        check("TIMESTAMP_DIFF('2021-02-01 00:01:00', '2021-02-01 00:00:59', MINUTE)", 0)
        for (timezone in listOf("UTC", "America/New_York")) {
            check("TIMESTAMP_DIFF(TIMESTAMP_SECONDS(0), '1970-01-01 00:00:00', HOUR)", 0, timezone)
            check("TIMESTAMP_DIFF(TIMESTAMP_SECONDS(1710129600), TIMESTAMP_SECONDS(1710046800), DAY)", 0, timezone)
            check("TIMESTAMP_DIFF(TIMESTAMP_SECONDS(1710046800), TIMESTAMP_SECONDS(1710129600), DAY)", 0, timezone)
            check("TIMESTAMP_DIFF('2024-03-10 00:00:00 UTC', '2024-03-09 00:00:00 UTC', DAY)", 1, timezone)
        }
    }

    @Test
    fun calendarDifferencesCountBoundariesRatherThanElapsedUnits() {
        check("DATETIME_DIFF(DATETIME '2021-02-01 00:00:00', DATETIME '2021-01-31 00:00:00', MONTH)", 1)
        check("DATETIME_DIFF(DATETIME '2021-01-31 00:00:00', DATETIME '2021-02-01 00:00:00', MONTH)", -1)
        check("DATETIME_DIFF('2021-02-01 00:01:00', '2021-02-01 00:00:59', MINUTE)", 1)
        check("DATETIME_DIFF('2021-02-01 00:01:00', '2021-02-01 00:00:59.999999', MINUTE)", 1)
        check("DATETIME_DIFF(DATETIME '2021-02-01 00:01:00', DATETIME '2021-02-01 00:00:59.999999', MINUTE)", 1)
        check("DATETIME_DIFF(DATETIME '2017-10-15 00:00:00', DATETIME '2017-10-14 00:00:00', WEEK)", 1)
        check("DATE_DIFF('2024-01-07', '2024-01-06', WEEK)", 1)
        check("DATE_DIFF(DATE '2024-01-08', DATE '2024-01-07', ISOWEEK)", 1)
    }

    @Test
    fun eachWeekStartPreservesPositiveAndNegativeBoundaries() {
        val monday = java.time.LocalDate.of(2024, 1, 8)
        for ((i, day) in listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY").withIndex()) {
            val boundary = monday.plusDays(i.toLong())
            check("DATE_DIFF(DATE '$boundary', DATE '${boundary.minusDays(1)}', WEEK($day))", 1)
            check("DATE_DIFF(DATE '${boundary.minusDays(1)}', DATE '$boundary', WEEK($day))", -1)
        }
        assertEquals("SELECT DATE_DIFF('WEEK', CAST('2024-01-07' AS DATE), CAST('2024-01-08' AS DATE))",
            SqlFragment("SELECT DATE_DIFF('week', DATE '2024-01-07', DATE '2024-01-08')", "presto").transpileTo("presto").sql)
    }

    @Test
    fun unsupportedUnitsAndUnknownWeekStartsAreDiagnosed() {
        for (expression in listOf(
            "TIMESTAMP_DIFF(a, b, MICROSECOND)",
            "DATE_DIFF(a, b, WEEK(NOT_A_DAY))",
        )) {
            assertTrue(SqlFragment("SELECT $expression", "bigquery").transpileTo("presto").unsupportedMessages.isNotEmpty())
        }
    }
}
