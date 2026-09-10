package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Greatest
import dev.brikk.house.sql.ast.Least
import dev.brikk.house.sql.ast.SafeDivide
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BigqueryScalarSemanticsTest {
    @Test
    fun safeDivideMatchesPinnedTargets() {
        // SQLGlot v30.17.0-93-gdcc36544a, tests/dialects/test_bigquery.py.
        for ((source, targets) in listOf(
            "SAFE_DIVIDE(x, y)" to mapOf(
                "bigquery" to "SAFE_DIVIDE(x, y)",
                "duckdb" to "CASE WHEN y <> 0 THEN x / y ELSE NULL END",
                "presto" to "IF(y <> 0, CAST(x AS DOUBLE) / y, NULL)",
                "trino" to "IF(y <> 0, CAST(x AS DOUBLE) / y, NULL)",
                "hive" to "IF(y <> 0, x / y, NULL)",
                "spark2" to "IF(y <> 0, x / y, NULL)",
                "spark" to "TRY_DIVIDE(x, y)",
                "postgres" to "CASE WHEN y <> 0 THEN CAST(x AS DOUBLE PRECISION) / y ELSE NULL END",
            ),
            "SAFE_DIVIDE(x + 1, 2 * y)" to mapOf(
                "bigquery" to "SAFE_DIVIDE(x + 1, 2 * y)",
                "duckdb" to "CASE WHEN (2 * y) <> 0 THEN (x + 1) / (2 * y) ELSE NULL END",
                "presto" to "IF((2 * y) <> 0, CAST((x + 1) AS DOUBLE) / (2 * y), NULL)",
                "trino" to "IF((2 * y) <> 0, CAST((x + 1) AS DOUBLE) / (2 * y), NULL)",
                "hive" to "IF((2 * y) <> 0, (x + 1) / (2 * y), NULL)",
                "spark2" to "IF((2 * y) <> 0, (x + 1) / (2 * y), NULL)",
                "spark" to "TRY_DIVIDE(x + 1, 2 * y)",
                "postgres" to "CASE WHEN (2 * y) <> 0 THEN CAST((x + 1) AS DOUBLE PRECISION) / (2 * y) ELSE NULL END",
            ),
        )) {
            for ((target, expected) in targets) check(source, target, expected)
        }
    }

    @Test
    fun safeDivideKeepsExistingGroupingAndFloatingPointCasts() {
        for (target in listOf("presto", "trino", "postgres")) {
            val type = if (target == "postgres") "DOUBLE PRECISION" else "DOUBLE"
            check(
                "SAFE_DIVIDE((x + 1), (2 * y))", target,
                if (target == "postgres")
                    "CASE WHEN (2 * y) <> 0 THEN CAST((x + 1) AS $type) / (2 * y) ELSE NULL END"
                else "IF((2 * y) <> 0, CAST((x + 1) AS $type) / (2 * y), NULL)",
            )
            check(
                "SAFE_DIVIDE(CAST(x AS FLOAT64), y)", target,
                if (target == "postgres")
                    "CASE WHEN y <> 0 THEN CAST(x AS $type) / y ELSE NULL END"
                else "IF(y <> 0, CAST(x AS $type) / y, NULL)",
            )
        }
    }

    @Test
    fun safeDivideLoweringDoesNotReparentOrMutateSourceOperands() {
        for (source in listOf("SAFE_DIVIDE(x, y)", "SAFE_DIVIDE(x + 1, 2 * y)")) {
            val tree = Dialects.BIGQUERY.parseOne(source) as SafeDivide
            val before = tree.copy()
            val links = tree.walk().map { it to Triple(it.parent, it.argKey, it.index) }.toList()
            for (target in listOf("duckdb", "presto", "trino", "hive", "spark2", "postgres")) {
                val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")
                val expected = generator.generate(tree)
                // Bypass generate's defensive copy to exercise the lowering itself.
                assertEquals(expected, generator.safedivideSql(tree), target)
                assertEquals(before, tree, "Source AST changed for $target: $source")
                for ((node, link) in links) {
                    assertSame(link.first, node.parent, "Source parent changed for $target: $source")
                    assertEquals(link.second, node.argKey)
                    assertEquals(link.third, node.index)
                }
            }
        }
    }

    @Test
    fun hiveUsesNativeIfWithoutChangingCaseTargets() {
        val source = "SELECT IF(flag, x, y), IF(NULL, x, y)"
        check(source, "hive", source)
        for (target in listOf("duckdb", "postgres")) {
            check(source, target, "SELECT CASE WHEN flag THEN x ELSE y END, CASE WHEN NULL THEN x ELSE y END")
        }
        val case = "SELECT CASE WHEN flag THEN x ELSE y END"
        check(case, "hive", case)
    }

    @Test
    fun greatestAndLeastPropagateNullsAtRuntime() {
        for (function in listOf("GREATEST", "LEAST")) {
            for ((arguments, condition) in listOf(
                "1, NULL, 3" to "1 IS NULL OR NULL IS NULL OR 3 IS NULL",
                "a, b, c" to "a IS NULL OR b IS NULL OR c IS NULL",
                "1, 2, 3" to "1 IS NULL OR 2 IS NULL OR 3 IS NULL",
            )) {
                val source = "SELECT $function($arguments)"
                val tree = Dialects.BIGQUERY.parseOne(source)
                val scalar = tree.find<Greatest>() ?: tree.find<Least>()!!
                assertEquals(false, scalar.args["ignore_nulls"], source)
                assertEquals(2, scalar.expressionsArg.size, source)
                check(source, "bigquery", source)
                check(source, "duckdb", "SELECT CASE WHEN $condition THEN NULL ELSE $function($arguments) END")
            }
        }
    }

    @Test
    fun nullIgnoringSourceDialectsKeepNativeGreatestAndLeast() {
        val source = "SELECT GREATEST(a, b, c), LEAST(a, b, c)"
        for (read in listOf("duckdb", "postgres")) {
            val tree = Dialects.forName(read).parseOne(source)
            for (scalar in tree.walk().filter { it is Greatest || it is Least }) {
                assertEquals(true, scalar.args["ignore_nulls"], read)
            }
            assertEquals(source, Dialects.DUCKDB.generate(tree, sourceDialect = read))
        }
    }

    private fun check(source: String, target: String, expected: String) {
        val tree: Expression = Dialects.BIGQUERY.parseOne(source)
        val before = tree.copy()
        val generator = Dialects.forName(target).generator(sourceDialect = "bigquery")
        assertEquals(expected, generator.generate(tree), "$target: $source")
        assertEquals(emptyList(), generator.unsupportedMessages, "$target: $source")
        assertEquals(before, tree, "Source AST changed for $target: $source")
    }
}
