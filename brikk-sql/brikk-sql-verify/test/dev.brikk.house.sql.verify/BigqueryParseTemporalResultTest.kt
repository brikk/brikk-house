package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BigqueryParseTemporalResultTest {
    @Test
    fun parsedDatetimeAndTimeDoNotDependOnTheDuckdbSessionZone() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (zone in listOf("UTC", "America/New_York", "Pacific/Auckland")) {
                    statement.execute("SET TimeZone = '$zone'")
                    check(
                        statement,
                        "SELECT " + listOf(
                            "PARSE_DATETIME('%m-%d %H:%M:%S', '12-25 07:30:00')",
                            "PARSE_DATETIME('%Y-%m-%d %H:%M:%S', '2023-01-15 14:30:00')",
                            "PARSE_DATETIME('%H:%M:%E6S', '15:30:00.123456')",
                            "PARSE_TIME('%H:%M', '14:30')",
                            "PARSE_TIME('%H:%M:%E6S', '15:30:00.123456')",
                            "PARSE_DATETIME('%F %T', CAST(NULL AS STRING))",
                            "PARSE_TIME('%H:%M', CAST(NULL AS STRING))",
                        ).joinToString(),
                        listOf(
                            "1970-12-25 07:30:00",
                            "2023-01-15 14:30:00",
                            "1970-01-01 15:30:00.123456",
                            "14:30:00",
                            "15:30:00.123456",
                            null,
                            null,
                        ),
                        zone,
                    )
                }
            }
        }
    }

    private fun check(statement: Statement, source: String, expected: List<String?>, zone: String) {
        val tree = Dialects.BIGQUERY.parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        val generated = generator.generate(tree)
        assertEquals(emptyList(), generator.unsupportedMessages, generated)
        assertEquals(before, tree, "Source AST changed: $source")
        statement.executeQuery(generated).use { result ->
            assertTrue(result.next(), generated)
            val actual = (1..result.metaData.columnCount).map {
                result.getString(it)?.removeSuffix(".0")?.let { value ->
                    if (value.matches(Regex("\\d{2}:\\d{2}"))) "$value:00" else value
                }
            }
            assertEquals(expected, actual, "$zone: $generated")
            assertFalse(result.next(), generated)
        }
    }
}
