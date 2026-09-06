package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BigqueryRelationLoweringTest {
    @Test
    fun derivedValuesKeepRowsFieldsAndAliases() {
        for ((source, expected) in listOf(
            "SELECT t.a, t.b FROM (VALUES (1, 'x'), (2, NULL), (1, 'x')) AS t(a, b)" to
                "SELECT t.a, t.b FROM UNNEST([STRUCT(1 AS a, 'x' AS b), STRUCT(2 AS a, NULL AS b), STRUCT(1 AS a, 'x' AS b)]) AS t",
            "SELECT * FROM (VALUES (1)) AS t" to "SELECT * FROM UNNEST([STRUCT(1 AS _c0)]) AS t",
            "SELECT t.a, u.b FROM (VALUES (1)) AS t(a) CROSS JOIN (VALUES (2)) AS u(b)" to
                "SELECT t.a, u.b FROM UNNEST([STRUCT(1 AS a)]) AS t CROSS JOIN UNNEST([STRUCT(2 AS b)]) AS u",
            "SELECT t.\"odd field\" FROM (VALUES (1)) AS t(\"odd field\")" to
                "SELECT t.`odd field` FROM UNNEST([STRUCT(1 AS `odd field`)]) AS t",
            "INSERT INTO t VALUES (1), (2)" to "INSERT INTO t VALUES (1), (2)",
        )) {
            check(source, expected)
        }
    }

    @Test
    fun cteColumnsOverrideOnlyNamedPositions() {
        for ((source, expected) in listOf(
            "WITH cte(foo) AS (SELECT 1) SELECT foo FROM cte" to
                "WITH cte AS (SELECT 1 AS foo) SELECT foo FROM cte",
            "WITH cte(foo) AS (SELECT 1 AS bar) SELECT foo FROM cte" to
                "WITH cte AS (SELECT 1 AS foo) SELECT foo FROM cte",
            "WITH cte(foo) AS (SELECT 1 AS bar, 2 AS baz) SELECT foo, baz FROM cte" to
                "WITH cte AS (SELECT 1 AS foo, 2 AS baz) SELECT foo, baz FROM cte",
            "WITH cte(foo) AS (SELECT 1 UNION ALL SELECT 2) SELECT foo FROM cte" to
                "WITH cte AS (SELECT 1 AS foo UNION ALL SELECT 2) SELECT foo FROM cte",
            "WITH cte(\"odd field\") AS (SELECT 1) SELECT \"odd field\" FROM cte" to
                "WITH cte AS (SELECT 1 AS `odd field`) SELECT `odd field` FROM cte",
            "WITH cte(foo) AS (SELECT t.a FROM (VALUES (1), (2)) AS t(a)) SELECT foo FROM cte" to
                "WITH cte AS (SELECT t.a AS foo FROM UNNEST([STRUCT(1 AS a), STRUCT(2 AS a)]) AS t) SELECT foo FROM cte",
        )) {
            check(source, expected)
        }
    }

    @Test
    fun unsupportedShapesDoNotSilentlyLoseNamesOrCells() {
        for (source in listOf(
            "WITH cte(foo) AS (SELECT * FROM t) SELECT foo FROM cte",
            "WITH cte(foo) AS (SELECT t.* FROM t) SELECT foo FROM cte",
            "WITH cte(foo, bar) AS (SELECT 1) SELECT foo FROM cte",
            "WITH cte(foo) AS (SELECT 1 AS bar ORDER BY bar) SELECT foo FROM cte",
            "SELECT * FROM (VALUES (1, 2)) AS t(a)",
            "SELECT * FROM (VALUES (1), (2, 3)) AS t(a)",
        )) {
            val generator = Dialects.BIGQUERY.generator(sourceDialect = "postgres")
            generator.generate(Dialects.POSTGRES.parseOne(source))
            assertTrue(generator.unsupportedMessages.any { it.contains("BigQuery") }, source)
        }
    }

    private fun check(source: String, expected: String) {
        val tree = Dialects.POSTGRES.parseOne(source)
        val before = Dialects.POSTGRES.generate(tree)
        val generator = Dialects.BIGQUERY.generator(sourceDialect = "postgres")
        val generated = generator.generate(tree)
        assertEquals(expected, generated, source)
        assertEquals(emptyList(), generator.unsupportedMessages, source)
        assertEquals(before, Dialects.POSTGRES.generate(tree), "Source AST changed: $source")
        assertEquals(generated, Dialects.BIGQUERY.generate(Dialects.BIGQUERY.parseOne(generated)))
    }
}
