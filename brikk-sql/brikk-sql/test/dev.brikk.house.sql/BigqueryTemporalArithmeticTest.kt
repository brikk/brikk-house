package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals

class BigqueryTemporalArithmeticTest {
    @Test
    fun bq8RowsUseTargetIntervalSyntax() {
        for ((source, target, expected) in listOf(
            Triple("SELECT TIMESTAMP_SUB(TIMESTAMP '2008-12-25 15:30:00+00', INTERVAL 10 MINUTE)", "spark",
                "SELECT CAST('2008-12-25 15:30:00+00' AS TIMESTAMP) - INTERVAL '10' MINUTE"),
            Triple("SELECT DATETIME_ADD('2023-01-01T00:00:00', INTERVAL 1 MILLISECOND)", "spark",
                "SELECT '2023-01-01T00:00:00' + INTERVAL '1' MILLISECOND"),
            Triple("SELECT DATETIME_SUB('2023-01-01T00:00:00', INTERVAL 1 MILLISECOND)", "spark",
                "SELECT '2023-01-01T00:00:00' - INTERVAL '1' MILLISECOND"),
            Triple("DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY)", "postgres", "CURRENT_DATE - INTERVAL '1 DAY'"),
            Triple("DATE_ADD(CURRENT_DATE(), INTERVAL -1 DAY)", "postgres", "CURRENT_DATE + INTERVAL '-1 DAY'"),
        )) check(source, "bigquery", target, expected)

        for ((source, expected) in listOf(
            "DATE_ADD('2020-01-01', 1)" to "DATE_ADD(CAST(CAST('2020-01-01' AS DATETIME) AS DATE), INTERVAL 1 DAY)",
            "DATE_SUB('2020-01-01', 1)" to "DATE_ADD(CAST(CAST('2020-01-01' AS DATETIME) AS DATE), INTERVAL (1 * -1) DAY)",
        )) check(source, "hive", "bigquery", expected)
        check("SELECT DATE_ADD(my_date_column, 1)", "spark", "bigquery",
            "SELECT DATE_ADD(CAST(CAST(my_date_column AS DATETIME) AS DATE), INTERVAL 1 DAY)")
    }

    @Test
    fun negativeAmountsAndSourceAstsStayIntact() {
        check("DATETIME_ADD(x, INTERVAL -2 SECOND)", "bigquery", "spark", "x + INTERVAL '-2' SECOND")
        check("DATETIME_SUB(x, INTERVAL -2 SECOND)", "bigquery", "spark", "x - INTERVAL '-2' SECOND")
    }

    private fun check(source: String, read: String, write: String, expected: String) {
        val tree = Dialects.forName(read).parseOne(source)
        val before = tree.copy()
        val generator = Dialects.forName(write).generator(sourceDialect = read)
        assertEquals(expected, generator.generate(tree), "$read -> $write: $source")
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, tree)
    }
}
