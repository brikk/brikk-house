package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryDateResultTest {
    @Test
    fun dateExtractionDoesNotDependOnTheDuckdbSessionZone() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (zone in listOf("UTC", "America/New_York", "Pacific/Auckland")) {
                    statement.execute("SET TimeZone = '$zone'")
                    for ((source, expected) in listOf(
                        "DATE(DATETIME '2016-12-25 23:59:59')" to "2016-12-25",
                        "DATE(TIMESTAMP '2016-12-25', 'America/Los_Angeles')" to "2016-12-24",
                        "DATE('2024-01-15 23:30:00', 'Europe/Berlin')" to "2024-01-16",
                        "DATE(TIMESTAMP '2024-01-15 23:30:00+00', 'Europe/Berlin')" to "2024-01-16",
                        "DATE(TIMESTAMP '2024-01-15 00:30:00+10:00')" to "2024-01-14",
                        "DATE(PARSE_DATE('%m/%d/%Y', '05/06/2020'))" to "2020-05-06",
                        "DATE(CAST(NULL AS TIMESTAMP), 'UTC')" to null,
                        "DATE(NULL, 'UTC')" to null,
                    )) {
                        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
                        val sql = generator.generate(Dialects.BIGQUERY.parseOne("SELECT $source"))
                        assertEquals(emptyList(), generator.unsupportedMessages, sql)
                        statement.executeQuery(sql).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(expected, rows.getString(1), "$zone: $sql")
                        }
                    }
                    for (targetZone in listOf(null, "UTC", "America/Los_Angeles", "Pacific/Auckland")) {
                        val source = if (targetZone == null) "CURRENT_DATE()" else "CURRENT_DATE('$targetZone')"
                        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
                        val sql = generator.generate(Dialects.BIGQUERY.parseOne(source))
                        assertEquals(emptyList(), generator.unsupportedMessages)
                        statement.executeQuery("SELECT $sql = CAST(CURRENT_TIMESTAMP AT TIME ZONE '${targetZone ?: "UTC"}' AS DATE)").use { rows ->
                            assertTrue(rows.next())
                            assertTrue(rows.getBoolean(1), "$zone: $sql")
                        }
                    }
                }
            }
        }
    }
}
