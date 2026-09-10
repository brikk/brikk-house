package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.TimeDiff
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryEpochTest {
    @Test
    fun sevenBq4AssertionsRetainUnitsAndArgumentOrder() {
        // The seven BQ-4 labels in the pinned bigquery-transpile corpus. Seconds and
        // both millis renderings intentionally differ from the pin to preserve floor semantics.
        check("SELECT UNIX_DATE(DATE '2008-12-25')",
            "SELECT DATE_DIFF('DAY', CAST('1970-01-01' AS DATE), CAST('2008-12-25' AS DATE))")
        check("SELECT TIME_DIFF('12:00:00', '12:30:00', MINUTE)",
            "SELECT DATE_DIFF('MINUTE', CAST('12:30:00' AS TIME), CAST('12:00:00' AS TIME))")
        val timestamp = "CAST('2008-12-25 15:30:00+00' AS TIMESTAMPTZ)"
        check("SELECT UNIX_SECONDS('2008-12-25 15:30:00+00')",
            "SELECT CAST(EPOCH(DATE_TRUNC('SECOND', $timestamp)) AS BIGINT)")
        for (prefix in listOf("", "TIMESTAMP ")) {
            check("SELECT UNIX_MICROS(${prefix}'2008-12-25 15:30:00+00')", "SELECT EPOCH_US($timestamp)")
            check("SELECT UNIX_MILLIS(${prefix}'2008-12-25 15:30:00+00')",
                "SELECT EPOCH_MS(DATE_TRUNC('MILLISECOND', $timestamp))")
        }
    }

    @Test
    fun unzonedLiteralsUseUtcAndExplicitCastsSurvive() {
        check("UNIX_MICROS(TIMESTAMP '1970-01-01')", "EPOCH_US(CAST('1970-01-01 00:00:00 UTC' AS TIMESTAMPTZ))")
        for (value in listOf("'1970-01-01 00:00:00'", "TIMESTAMP '1970-01-01 00:00:00'",
            "CAST('1970-01-01 00:00:00' AS TIMESTAMP)")) {
            check("UNIX_MICROS($value)", "EPOCH_US(CAST('1970-01-01 00:00:00 UTC' AS TIMESTAMPTZ))")
        }
        for (zone in listOf("Z", "+05:30", " UTC", " America/New_York")) {
            check("UNIX_MICROS(TIMESTAMP '2008-12-25 15:30:00$zone')",
                "EPOCH_US(CAST('2008-12-25 15:30:00$zone' AS TIMESTAMPTZ))")
        }
        check("UNIX_MICROS(CAST(x AS TIMESTAMP))", "EPOCH_US(CAST(x AS TIMESTAMPTZ))")
        check("UNIX_MICROS(CAST(x AS DATETIME))", "EPOCH_US(CAST(x AS TIMESTAMP))")
        check("TIME_DIFF(CAST(a AS TIME), CAST(b AS TIME), MICROSECOND)",
            "DATE_DIFF('MICROSECOND', CAST(b AS TIME), CAST(a AS TIME))")
    }

    @Test
    fun parserKeepsTimeDiffEndStartAndUnit() {
        val tree = Dialects.BIGQUERY.parseOne("TIME_DIFF(finish, start, MILLISECOND)") as TimeDiff
        assertEquals("finish", (tree.thisArg as Expression).name)
        assertEquals("start", (tree.expressionArg as Expression).name)
        assertEquals("MILLISECOND", (tree.args["unit"] as Expression).name)
    }

    private fun check(source: String, expected: String) {
        val tree = Dialects.BIGQUERY.parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        assertEquals(expected, generator.generate(tree), source)
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, tree, "Source AST changed: $source")
    }
}
