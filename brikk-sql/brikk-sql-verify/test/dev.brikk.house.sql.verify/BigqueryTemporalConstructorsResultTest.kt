package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryTemporalConstructorsResultTest {
    @Test
    fun constructorsKeepInstantAndCivilTimeSemanticsAcrossSessionZones() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (sessionZone in listOf("UTC", "America/New_York", "Pacific/Auckland")) {
                    statement.execute("SET TimeZone = '$sessionZone'")
                    for ((source, expected) in listOf(
                        "TIMESTAMP('2008-12-25 15:30:00', 'America/Los_Angeles')" to "2008-12-25T23:30:00Z",
                        "DATETIME('2020-01-01', TIME '23:59:59')" to "2020-01-01 23:59:59",
                        "DATETIME('2020-01-01', 'America/Los_Angeles')" to "2019-12-31 16:00:00",
                        "TIME(15, 30, 0)" to "15:30:00",
                    )) {
                        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
                        val sql = generator.generate(Dialects.BIGQUERY.parseOne("SELECT $source"))
                        assertEquals(emptyList(), generator.unsupportedMessages, sql)
                        statement.executeQuery(sql).use { rows ->
                            assertTrue(rows.next())
                            val actual = rows.getObject(1)
                            val text = when (source.substringBefore('(')) {
                                "TIMESTAMP" -> (actual as java.time.OffsetDateTime).toInstant().toString()
                                else -> rows.getString(1).removeSuffix(".0").let {
                                    if (it.matches(Regex("\\d{2}:\\d{2}"))) "$it:00" else it
                                }
                            }
                            assertEquals(expected, text, "$sessionZone: $sql")
                        }
                    }
                    for (source in listOf("TIME(NULL, NULL, NULL)", "DATETIME(NULL, TIME '23:59:59')")) {
                        val sql = Dialects.DUCKDB.generate(Dialects.BIGQUERY.parseOne("SELECT $source"), sourceDialect = "bigquery")
                        statement.executeQuery(sql).use { rows -> assertTrue(rows.next()); assertEquals(null, rows.getObject(1)) }
                    }
                }
            }
        }
    }
}
