package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryTemporalTruncationResultTest {
    @Test
    fun dateAndDatetimeWeekStartsKeepTypesAndBoundaries() {
        val starts = listOf(
            "SUNDAY" to "2023-12-31", "MONDAY" to "2024-01-01", "TUESDAY" to "2024-01-02",
            "WEDNESDAY" to "2024-01-03", "THURSDAY" to "2023-12-28", "FRIDAY" to "2023-12-29",
            "SATURDAY" to "2023-12-30",
        )
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((start, expected) in starts) {
                    check(statement, "DATE_TRUNC(DATE '2024-01-03', WEEK($start))", expected, Types.DATE)
                    check(statement, "DATETIME_TRUNC(DATETIME '2024-01-03 12:34:56', WEEK($start))",
                        "$expected 00:00:00", Types.TIMESTAMP)
                }
                check(statement, "DATE_TRUNC(DATE '2024-01-03', WEEK)", "2023-12-31", Types.DATE)
                check(statement, "DATE_TRUNC(DATE '2024-01-03', ISOWEEK)", "2024-01-01", Types.DATE)
                check(statement, "DATE_TRUNC(CAST(NULL AS DATE), WEEK(SUNDAY))", null, Types.DATE)
            }
        }
    }

    @Test
    fun timestampTruncationIsIndependentOfDuckdbSessionZone() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (session in listOf("UTC", "America/New_York", "Pacific/Auckland")) {
                    statement.execute("SET TimeZone = '$session'")
                    for ((source, expected) in listOf(
                        "TIMESTAMP_TRUNC(TIMESTAMP '2024-01-07 00:30:00+00', WEEK)" to "2024-01-07T00:00:00Z",
                        "TIMESTAMP_TRUNC(TIMESTAMP '2024-01-07 00:30:00', WEEK(SUNDAY))" to "2024-01-07T00:00:00Z",
                        "TIMESTAMP_TRUNC(TIMESTAMP '2024-01-07 00:30:00+00', ISOWEEK)" to "2024-01-01T00:00:00Z",
                        "TIMESTAMP_TRUNC(TIMESTAMP '2024-01-07 00:30:00+00', WEEK, 'America/New_York')" to "2023-12-31T05:00:00Z",
                        "TIMESTAMP_TRUNC(TIMESTAMP '2024-01-07 00:30:00+00', DAY)" to "2024-01-07T00:00:00Z",
                    )) {
                        val sql = generated(source)
                        statement.executeQuery("SELECT $sql").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expected, (rows.getObject(1) as java.time.OffsetDateTime).toInstant().toString(), "$session: $sql")
                        }
                    }
                }
            }
        }
    }

    private fun check(statement: java.sql.Statement, source: String, expected: String?, type: Int) {
        val sql = generated(source)
        statement.executeQuery("SELECT $sql").use { rows ->
            assertTrue(rows.next())
            assertEquals(type, rows.metaData.getColumnType(1), sql)
            assertEquals(expected, rows.getString(1)?.removeSuffix(".0"), sql)
        }
    }

    private fun generated(source: String): String {
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        val sql = generator.generate(Dialects.BIGQUERY.parseOne(source))
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        return sql
    }
}
