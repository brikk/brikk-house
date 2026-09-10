package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Datetime
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Time
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BigqueryTemporalConstructorsTest {
    @Test
    fun nativeBigqueryKeepsSecondArguments() {
        for (source in listOf(
            "SELECT DATETIME('2020-01-01', TIME '23:59:59')",
            "SELECT DATETIME('2020-01-01', 'America/Los_Angeles')",
            "SELECT STRING('2008-12-25 15:30:00', 'America/New_York')",
        )) assertEquals(source.replace("TIME '23:59:59'", "CAST('23:59:59' AS TIME)"),
            Dialects.BIGQUERY.generate(Dialects.BIGQUERY.parseOne(source)), source)
        val datetime = Dialects.BIGQUERY.parseOne("DATETIME(d, t)") as Datetime
        assertEquals("t", (datetime.expressionArg as Expression).name)
    }

    @Test
    fun duckdbSeparatesInstantsFromCivilTimes() {
        for ((source, expected) in listOf(
            "SELECT TIMESTAMP('2008-12-25 15:30:00', 'America/Los_Angeles')" to
                "SELECT CAST('2008-12-25 15:30:00' AS TIMESTAMP) AT TIME ZONE 'America/Los_Angeles'",
            "SELECT TIME(15, 30, 00)" to "SELECT MAKE_TIME(15, 30, 00)",
            "SELECT DATETIME('2020-01-01', TIME '23:59:59')" to
                "SELECT CAST(CAST('2020-01-01' AS DATE) + CAST('23:59:59' AS TIME) AS TIMESTAMP)",
            "SELECT DATETIME('2020-01-01', 'America/Los_Angeles')" to
                "SELECT CAST(CAST('2020-01-01 00:00:00 UTC' AS TIMESTAMPTZ) AT TIME ZONE 'America/Los_Angeles' AS TIMESTAMP)",
            "STRING(a)" to "CAST(a AS TEXT)",
        )) check(source, expected)
    }

    @Test
    fun prestoKeepsBigqueryTimestampWithTimeZone() {
        val tree = Dialects.BIGQUERY.parseOne("TIMESTAMP(x)")
        val before = tree.copy()
        val generator = Dialects.PRESTO.generator(sourceDialect = "bigquery")
        assertEquals("CAST(x AS TIMESTAMP WITH TIME ZONE)", generator.generate(tree))
        assertEquals(listOf("BigQuery TIMESTAMP without a zone requires a known input type to distinguish instants from UTC civil values"),
            generator.unsupportedMessages)
        assertEquals(before, tree)
    }

    @Test
    fun offsetDroppingStringConversionIsDiagnosed() {
        val source = "STRING('2008-12-25 15:30:00', 'America/New_York')"
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        assertEquals("CAST(CAST('2008-12-25 15:30:00 UTC' AS TIMESTAMPTZ) AT TIME ZONE 'America/New_York' AS TEXT)",
            generator.generate(Dialects.BIGQUERY.parseOne(source)))
        assertEquals(listOf("BigQuery STRING(timestamp, zone) requires offset-preserving formatting in DuckDB"),
            generator.unsupportedMessages)
    }

    @Test
    fun nullConstructorPartsRemainRuntimeNulls() {
        assertIs<Time>(Dialects.BIGQUERY.parseOne("TIME(NULL, NULL)"))
        check("SELECT TIME(NULL, NULL, NULL)", "SELECT MAKE_TIME(NULL, NULL, NULL)")
        check("SELECT DATETIME(NULL, TIME '23:59:59')",
            "SELECT CAST(CAST(NULL AS DATE) + CAST('23:59:59' AS TIME) AS TIMESTAMP)")
    }

    @Test
    fun ambiguousDynamicOverloadsAreDiagnosed() {
        val timestamp = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        assertEquals("CAST(x AS TIMESTAMPTZ)", timestamp.generate(Dialects.BIGQUERY.parseOne("TIMESTAMP(x)")))
        assertEquals(listOf("BigQuery TIMESTAMP without a zone requires a known input type to distinguish instants from UTC civil values"),
            timestamp.unsupportedMessages)

        val datetime = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        assertEquals("DATETIME(d, t)", datetime.generate(Dialects.BIGQUERY.parseOne("DATETIME(d, t)")))
        assertEquals(listOf("BigQuery DATETIME's second argument requires a known TIME or time-zone type"),
            datetime.unsupportedMessages)
    }

    private fun check(source: String, expected: String) {
        val tree = Dialects.BIGQUERY.parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        assertEquals(expected, generator.generate(tree), source)
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, tree)
    }
}
