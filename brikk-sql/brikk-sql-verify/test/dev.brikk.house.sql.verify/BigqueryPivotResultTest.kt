package dev.brikk.house.sql.verify

import dev.brikk.house.sql.dialects.Dialects
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryPivotResultTest {
    /** Executes the portable PIVOT subset in DuckDB, not a live Spark server. */
    @Test
    fun generatedPivotPreservesGroupsAndAggregates() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE produce(product VARCHAR, quarter VARCHAR, sales INTEGER)")
                statement.execute("INSERT INTO produce VALUES ('a', 'Q1', 2), ('a', 'Q1', 3), ('a', 'Q2', 7), ('b', 'Q1', 9)")
                val source = "SELECT * FROM produce AS p PIVOT(SUM(sales) AS sales FOR p.quarter IN ('Q1' AS Q1, 'Q2' AS Q2))"
                for (target in listOf(Dialects.SPARK, Dialects.SPARK2)) {
                    val generator = target.generator(sourceDialect = "bigquery")
                    val sql = generator.generate(Dialects.BIGQUERY.parseOne(source))
                    assertEquals(emptyList(), generator.unsupportedMessages)
                    statement.executeQuery(sql).use { result ->
                        val actual = buildList {
                            while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
                        }
                        assertEquals(listOf(listOf("a", "5", "7"), listOf("b", "9", null)).toSet(), actual.toSet(), sql)
                    }
                }
            }
        }
    }
}
