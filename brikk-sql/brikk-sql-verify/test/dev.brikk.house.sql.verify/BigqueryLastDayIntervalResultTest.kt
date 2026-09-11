package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryLastDayIntervalResultTest {
    @Test
    fun lastDayReturnsDatesForEveryWeekStartAndMonthEdge() {
        val cases = listOf(
            "LAST_DAY(DATE '2024-01-03', WEEK(SUNDAY))" to "2024-01-06",
            "LAST_DAY(DATE '2024-01-03', WEEK(MONDAY))" to "2024-01-07",
            "LAST_DAY(DATE '2024-01-03', WEEK(TUESDAY))" to "2024-01-08",
            "LAST_DAY(DATE '2024-01-03', WEEK(WEDNESDAY))" to "2024-01-09",
            "LAST_DAY(DATE '2024-01-03', WEEK(THURSDAY))" to "2024-01-03",
            "LAST_DAY(DATE '2024-01-03', WEEK(FRIDAY))" to "2024-01-04",
            "LAST_DAY(DATE '2024-01-03', WEEK(SATURDAY))" to "2024-01-05",
            "LAST_DAY(DATE '2024-01-03', WEEK)" to "2024-01-06",
            "LAST_DAY(DATE '2024-01-03', ISOWEEK)" to "2024-01-07",
            "LAST_DAY(DATE '2024-02-01', MONTH)" to "2024-02-29",
            "LAST_DAY(DATE '2024-02-29', MONTH)" to "2024-02-29",
            "LAST_DAY(DATE '2023-02-01', MONTH)" to "2023-02-28",
            "LAST_DAY(DATE '2024-12-31', MONTH)" to "2024-12-31",
            "LAST_DAY(CAST(NULL AS DATE), WEEK(SUNDAY))" to null,
            "LAST_DAY(CAST(NULL AS DATE), MONTH)" to null,
        )

        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET TimeZone = 'UTC'")
                for ((source, expected) in cases) {
                    val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
                    val sql = generator.generate(Dialects.BIGQUERY.parseOne("SELECT $source"))
                    assertEquals(emptyList(), generator.unsupportedMessages, sql)
                    statement.executeQuery(sql).use { rows ->
                        assertTrue(rows.next(), sql)
                        assertEquals("DATE", rows.metaData.getColumnTypeName(1), sql)
                        val value = rows.getObject(1)
                        val actual = if (value is java.time.OffsetDateTime) value.toLocalDateTime().toString().replace('T', ' ')
                        else rows.getString(1)?.removeSuffix(".0")
                        assertEquals(expected, actual, sql)
                    }
                }
            }
        }
    }

    @Test
    fun makeIntervalPreservesLiteralDynamicNullAndEmptyValues() {
        val cases = listOf(
            "SELECT TIMESTAMP '2020-01-31 00:00:00' + MAKE_INTERVAL(1, 2, minute => 5, day => 3)" to
                "2021-04-03T00:05:00Z",
            "SELECT DATE '2020-01-31' + MAKE_INTERVAL(month => m) FROM (SELECT 1 AS m)" to
                "2020-02-29 00:00:00",
            "SELECT DATE '2020-01-31' + MAKE_INTERVAL(month => m) FROM (SELECT CAST(NULL AS INT64) AS m)" to
                null,
            "SELECT DATE '2020-01-31' + MAKE_INTERVAL()" to "2020-01-31 00:00:00",
        )

        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET TimeZone = 'UTC'")
                for ((source, expected) in cases) {
                    val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
                    val sql = generator.generate(Dialects.BIGQUERY.parseOne(source))
                    assertEquals(emptyList(), generator.unsupportedMessages, sql)
                    statement.executeQuery(sql).use { rows ->
                        assertTrue(rows.next(), sql)
                        val value = rows.getObject(1)
                        val actual = if (value is java.time.OffsetDateTime) value.toInstant().toString()
                        else rows.getString(1)?.removeSuffix(".0")
                        assertEquals(expected, actual, sql)
                    }
                }
            }
        }
    }
}
