package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryEpochResultTest {
    @Test
    fun epochsFloorNegativeFractionsAndKeepInt64Precision() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for (zone in listOf("UTC", "America/New_York")) {
                    statement.execute("SET TimeZone = '$zone'")
                    for ((timestamp, expected) in listOf(
                        "1969-12-31 23:59:58.999999" to listOf(-2L, -1001L, -1000001L),
                        "1969-12-31 23:59:59" to listOf(-1L, -1000L, -1000000L),
                        "1969-12-31 23:59:59.998999" to listOf(-1L, -2L, -1001L),
                        "1969-12-31 23:59:59.999000" to listOf(-1L, -1L, -1000L),
                        "1969-12-31 23:59:59.999001" to listOf(-1L, -1L, -999L),
                        "1969-12-31 23:59:59.999999" to listOf(-1L, -1L, -1L),
                        "1970-01-01 00:00:00" to listOf(0L, 0L, 0L),
                        "1970-01-01 00:00:00.000001" to listOf(0L, 0L, 1L),
                        "1970-01-01 00:00:00.000999" to listOf(0L, 0L, 999L),
                        "1970-01-01 00:00:00.001000" to listOf(0L, 1L, 1000L),
                        "1970-01-01 00:00:00.001001" to listOf(0L, 1L, 1001L),
                        "1970-01-01 00:00:00.999999" to listOf(0L, 999L, 999999L),
                        "1970-01-01 00:00:01" to listOf(1L, 1000L, 1000000L),
                        "9999-12-31 23:59:59.999999" to listOf(253402300799L, 253402300799999L, 253402300799999999L),
                        "0001-01-01 00:00:00.000001" to listOf(-62135596800L, -62135596800000L, -62135596799999999L),
                    )) {
                        for (operand in listOf("'$timestamp'", "TIMESTAMP '$timestamp'", "CAST('$timestamp' AS TIMESTAMP)")) {
                            check(statement, "SELECT UNIX_SECONDS($operand), UNIX_MILLIS($operand), UNIX_MICROS($operand)", expected)
                        }
                    }
                    check(statement, "SELECT UNIX_SECONDS(TIMESTAMP '1970-01-01 01:00:00+01'), " +
                        "UNIX_MICROS(TIMESTAMP '1970-01-01 00:00:00Z')", listOf(0L, 0L))
                    check(statement, "SELECT UNIX_SECONDS(TIMESTAMP '1970-01-01'), UNIX_MILLIS('1970-01-01'), UNIX_MICROS('1970-01-01')",
                        listOf(0L, 0L, 0L))
                    check(statement, "SELECT UNIX_DATE(DATE '1969-12-31'), UNIX_DATE(DATE '1970-01-01'), " +
                        "UNIX_DATE(DATE '1970-01-02')", listOf(-1L, 0L, 1L))
                    check(statement, "SELECT UNIX_SECONDS(CAST(NULL AS TIMESTAMP)), UNIX_MILLIS(CAST(NULL AS TIMESTAMP)), " +
                        "UNIX_MICROS(CAST(NULL AS TIMESTAMP)), UNIX_DATE(CAST(NULL AS DATE))", listOf(null, null, null, null))
                }
            }
        }
    }

    @Test
    fun timeDiffCountsBoundariesInBothDirections() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((unit, start, finish) in listOf(
                    Triple("HOUR", "12:59:59.999999", "13:00:00"),
                    Triple("MINUTE", "12:00:59.999999", "12:01:00"),
                    Triple("SECOND", "12:00:00.999999", "12:00:01"),
                    Triple("MILLISECOND", "12:00:00.000999", "12:00:00.001000"),
                    Triple("MICROSECOND", "12:00:00.000000", "12:00:00.000001"),
                )) {
                    check(statement, "SELECT TIME_DIFF('$finish', '$start', $unit), " +
                        "TIME_DIFF('$start', '$finish', $unit), TIME_DIFF('$start', '$start', $unit)", listOf(1L, -1L, 0L))
                }
                check(statement, "SELECT TIME_DIFF('12:00:00', '12:30:00', MINUTE), " +
                    "TIME_DIFF(TIME '12:30:00', TIME '12:00:00', MINUTE), " +
                    "TIME_DIFF(TIME '00:00:00', TIME '23:00:00', HOUR), " +
                    "TIME_DIFF(CAST(NULL AS TIME), TIME '12:00:00', SECOND)", listOf(-30L, 30L, -23L, null))
            }
        }
    }

    private fun check(statement: Statement, source: String, expected: List<Long?>) {
        val tree = Dialects.BIGQUERY.parseOne(source)
        val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
        val generated = generator.generate(tree)
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        statement.executeQuery(generated).use { result ->
            assertEquals(true, result.next(), generated)
            val actual = (1..result.metaData.columnCount).map {
                assertEquals(Types.BIGINT, result.metaData.getColumnType(it), generated)
                (result.getObject(it) as Number?)?.toLong()
            }
            assertEquals(expected, actual, generated)
            assertEquals(false, result.next(), generated)
        }
    }
}
