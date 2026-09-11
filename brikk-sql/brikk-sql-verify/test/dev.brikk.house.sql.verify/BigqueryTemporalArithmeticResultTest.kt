package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryTemporalArithmeticResultTest {
    @Test
    fun sparkPortableArithmeticKeepsSignsAndUnits() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                for ((source, expected) in listOf(
                    "DATETIME_ADD(TIMESTAMP '2023-01-01 00:00:00', INTERVAL 1 MILLISECOND)" to "2023-01-01 00:00:00.001",
                    "DATETIME_SUB(TIMESTAMP '2023-01-01 00:00:00', INTERVAL 1 MILLISECOND)" to "2022-12-31 23:59:59.999",
                    "DATETIME_ADD(TIMESTAMP '2023-01-01 00:00:00', INTERVAL -2 SECOND)" to "2022-12-31 23:59:58",
                )) {
                    val sql = Dialects.SPARK.generate(Dialects.BIGQUERY.parseOne("SELECT $source"), sourceDialect = "bigquery")
                    statement.executeQuery(sql).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, rows.getString(1).removeSuffix(".0"), sql)
                    }
                }
            }
        }
    }
}
