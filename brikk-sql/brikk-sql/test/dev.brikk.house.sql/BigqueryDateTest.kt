package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryDateTest {
    @Test
    fun extractsDatesWithoutDiscardingTheInstant() {
        for ((source, expected) in listOf(
            "CURRENT_DATE('UTC')" to "CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS DATE)",
            "CURRENT_DATE()" to "CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS DATE)",
            "DATE(DATETIME '2016-12-25 23:59:59')" to "CAST(CAST('2016-12-25 23:59:59' AS TIMESTAMP) AS DATE)",
            "DATE(TIMESTAMP '2016-12-25', 'America/Los_Angeles')" to
                "CAST(CAST('2016-12-25 00:00:00 UTC' AS TIMESTAMPTZ) AT TIME ZONE 'America/Los_Angeles' AS DATE)",
            "DATE('2024-01-15 23:30:00', 'Europe/Berlin')" to
                "CAST(CAST('2024-01-15 23:30:00 UTC' AS TIMESTAMPTZ) AT TIME ZONE 'Europe/Berlin' AS DATE)",
        )) {
            val tree = Dialects.BIGQUERY.parseOne(source)
            val before = tree.copy()
            val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
            assertEquals(expected, generator.generate(tree), source)
            assertEquals(emptyList(), generator.unsupportedMessages, source)
            assertEquals(before, tree)
        }
        assertEquals("CURRENT_DATE", Dialects.DUCKDB.generate(Dialects.DUCKDB.parseOne("CURRENT_DATE")))
    }

    @Test
    fun unknownTimestampTypesAreDiagnosed() {
        for (source in listOf("DATE(x)", "DATE(x, 'UTC')")) {
            val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
            generator.generate(Dialects.BIGQUERY.parseOne(source))
            assertTrue(generator.unsupportedMessages.any { it.contains("annotate unknown inputs") }, source)
        }
    }
}
