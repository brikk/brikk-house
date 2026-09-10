package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Round
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.DuckdbGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BigqueryRoundingTest {
    @Test
    fun explicitModesMatchBothBq30Fixtures() {
        // SQLGlot v30.17.0-93-gdcc36544a, tests/dialects/test_bigquery.py.
        for ((mode, function) in listOf(
            "ROUND_HALF_AWAY_FROM_ZERO" to "ROUND",
            "ROUND_HALF_EVEN" to "ROUND_EVEN",
        )) {
            val source = "SELECT ROUND(NUMERIC '2.25', 1, '$mode') AS value"
            val tree = Dialects.BIGQUERY.parseOne(source)
            val round = assertIs<Round>(tree.find<Round>())
            val literal = assertIs<Literal>(round.args["truncate"])
            assertTrue(literal.isString)
            assertEquals(mode, literal.name)
            check(source, "SELECT $function(CAST('2.25' AS DECIMAL), 1) AS value")
            assertEquals(
                "SELECT ROUND(CAST('2.25' AS NUMERIC), 1, '$mode') AS value",
                Dialects.BIGQUERY.generate(tree),
            )
        }
    }

    @Test
    fun explicitModesPreserveOperandsGroupingAndDecimalTypes() {
        for ((mode, function) in listOf(
            "ROUND_HALF_AWAY_FROM_ZERO" to "ROUND",
            "ROUND_HALF_EVEN" to "ROUND_EVEN",
        )) {
            for ((sourceArgs, targetArgs) in listOf(
                "NUMERIC '-2.25', -1" to "CAST('-2.25' AS DECIMAL), -1",
                "CAST((x + 1) * (y - 2) AS NUMERIC(18, 4)), -(s + 1)" to
                    "CAST((x + 1) * (y - 2) AS DECIMAL(18, 4)), -(s + 1)",
                "-(x + y), (s - 1)" to "-(x + y), (s - 1)",
                "CAST(NULL AS NUMERIC(18, 4)), 2" to "CAST(NULL AS DECIMAL(18, 4)), 2",
                "NUMERIC '2.125', NULL" to "CAST('2.125' AS DECIMAL), NULL",
            )) {
                check("ROUND($sourceArgs, '$mode')", "$function($targetArgs)")
            }
        }
    }

    @Test
    fun loweringDoesNotMutateOrReparentOperands() {
        for (mode in listOf("ROUND_HALF_AWAY_FROM_ZERO", "ROUND_HALF_EVEN")) {
            val tree = assertIs<Round>(Dialects.BIGQUERY.parseOne(
                "ROUND(CAST((x + 1) * (y - 2) AS NUMERIC(18, 4)), -(s + 1), '$mode')",
            ))
            val before = tree.copy()
            val links = tree.walk().map { it to Triple(it.parent, it.argKey, it.index) }.toList()
            val generator = DuckdbGenerator(sourceDialect = "bigquery")
            val expected = generator.generate(tree)
            // Bypass generate's defensive copy to exercise the lowering itself.
            assertEquals(expected, generator.roundSql(tree))
            assertEquals(before, tree)
            for ((node, link) in links) {
                assertSame(link.first, node.parent)
                assertEquals(link.second, node.argKey)
                assertEquals(link.third, node.index)
            }
        }
    }

    @Test
    fun unknownAndDynamicModesAreRetainedAndDiagnosed() {
        for (mode in listOf(
            "'UNKNOWN'", "'round_half_even'", "''", "1", "NULL", "mode",
            "ROUND_HALF_EVEN", "ROUND_HALF_AWAY_FROM_ZERO",
            "CASE WHEN flag THEN 'ROUND_HALF_EVEN' ELSE 'ROUND_HALF_AWAY_FROM_ZERO' END",
        )) {
            val source = "ROUND(x, 1, $mode)"
            val tree = Dialects.BIGQUERY.parseOne(source)
            val before = tree.copy()
            val generator = Dialects.DUCKDB.generator(sourceDialect = "bigquery")
            assertEquals(source, generator.generate(tree), source)
            assertEquals(
                listOf("DuckDB ROUND only supports literal ROUND_HALF_AWAY_FROM_ZERO or ROUND_HALF_EVEN modes"),
                generator.unsupportedMessages,
                source,
            )
            assertEquals(before, tree, source)
        }
    }

    @Test
    fun modeLessRoundKeepsNativeDuckdbBehavior() {
        for (source in listOf(
            "ROUND(2.5)", "ROUND(-2.5)", "ROUND(2.25, 1)", "ROUND(-25, -1)",
            "ROUND(CAST((x + 1) * (y - 2) AS DECIMAL(18, 4)), -(s + 1))",
            "ROUND(NULL)", "ROUND(x, NULL)",
        )) {
            check(source, source, read = "duckdb")
        }
        check("SELECT ROUND(2.25) AS value", "SELECT ROUND(2.25) AS value")
        check("SELECT ROUND(2.25, 1) AS value", "SELECT ROUND(2.25, 1) AS value")
    }

    private fun check(source: String, expected: String, read: String = "bigquery") {
        val tree = Dialects.forName(read).parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = read)
        assertEquals(expected, generator.generate(tree), "$read: $source")
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, tree, "Source AST changed: $source")
    }
}
