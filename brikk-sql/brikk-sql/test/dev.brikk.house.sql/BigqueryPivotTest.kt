package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryPivotTest {
    @Test
    fun sparkUnqualifiesOnlyPivotFields() {
        for ((source, expected) in listOf(
            "SELECT * FROM produce AS p PIVOT(SUM(p.sales) AS sales FOR p.quarter IN ('Q1' AS Q1, 'Q2' AS Q1))" to
                "SELECT * FROM produce AS p PIVOT(SUM(p.sales) AS sales FOR quarter IN ('Q1' AS Q1, 'Q2' AS Q1))",
            "SELECT * FROM produce AS p PIVOT(SUM(p.sales) AS total FOR p.`odd quarter` IN ('Q1' AS first))" to
                "SELECT * FROM produce AS p PIVOT(SUM(p.sales) AS total FOR `odd quarter` IN ('Q1' AS first))",
        )) {
            val tree = Dialects.BIGQUERY.parseOne(source)
            val before = tree.copy()
            for (target in listOf(Dialects.SPARK, Dialects.SPARK2)) {
                val generator = target.generator(sourceDialect = "bigquery")
                assertEquals(expected, generator.generate(tree))
                assertEquals(emptyList(), generator.unsupportedMessages)
                assertEquals(before, tree)
            }
        }
    }

    @Test
    fun nativeSimplePivotIsUnchanged() {
        val source = "SELECT * FROM produce PIVOT(SUM(sales) FOR quarter IN ('Q1', 'Q2'))"
        assertEquals(source, Dialects.SPARK.generate(Dialects.SPARK.parseOne(source)))
    }
}
