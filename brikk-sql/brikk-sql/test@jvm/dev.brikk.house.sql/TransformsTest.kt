package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.generator.eliminateQualify
import dev.brikk.house.sql.generator.eliminateDistinctOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Focused transpile assertions for the generator preprocess transforms ported into
 * generator/Transforms.kt (reference/sqlglot/sqlglot/transforms.py). Every expected
 * string follows Python sqlglot v30.12.0 unless marked as a brikk extension.
 * These lock the transforms independently of the large corpus gates.
 */
class TransformsTest {

    private fun transpile(read: String, write: String, sql: String): String =
        Dialects.forName(write).generate(Dialects.forName(read).parseOne(sql))

    // sqlglot: transforms.eliminate_semi_and_anti_joins
    @Test
    fun eliminateSemiJoin() {
        assertEquals(
            "SELECT * FROM x WHERE EXISTS(SELECT 1 FROM y WHERE x.a = y.a)",
            transpile("spark", "postgres", "SELECT * FROM x SEMI JOIN y ON x.a = y.a"),
        )
    }

    @Test
    fun eliminateAntiJoin() {
        assertEquals(
            "SELECT * FROM x WHERE NOT EXISTS(SELECT 1 FROM y WHERE x.a = y.a)",
            transpile("spark", "postgres", "SELECT * FROM x ANTI JOIN y ON x.a = y.a"),
        )
    }

    // sqlglot: transforms.any_to_exists
    @Test
    fun anyToExists() {
        assertEquals(
            "SELECT * FROM t WHERE EXISTS(t.col, x -> 5 > x)",
            transpile("postgres", "spark", "SELECT * FROM t WHERE 5 > ANY(t.col)"),
        )
    }

    // sqlglot: transforms.unnest_to_explode (CROSS JOIN UNNEST -> LATERAL VIEW EXPLODE)
    @Test
    fun unnestToExplode() {
        assertEquals(
            "SELECT a FROM x LATERAL VIEW EXPLODE(arr) t AS a",
            transpile("presto", "spark", "SELECT a FROM x CROSS JOIN UNNEST(arr) AS t(a)"),
        )
    }

    // sqlglot: transforms.eliminate_distinct_on (DISTINCT ON -> ROW_NUMBER subquery)
    @Test
    fun eliminateDistinctOn() {
        // brikk extension: retain final ordering after choosing each group's row.
        assertEquals(
            "SELECT a, b FROM (SELECT a AS a, b AS b, ROW_NUMBER() OVER " +
                "(PARTITION BY a ORDER BY CASE WHEN a IS NULL THEN 1 ELSE 0 END, a, " +
                "CASE WHEN c IS NULL THEN 1 ELSE 0 END, c) AS _row_number, c AS _o FROM x) AS _t " +
                "WHERE _row_number = 1 ORDER BY CASE WHEN a IS NULL THEN 1 ELSE 0 END, a, " +
                "CASE WHEN _o IS NULL THEN 1 ELSE 0 END, _o",
            transpile("postgres", "mysql", "SELECT DISTINCT ON (a) a, b FROM x ORDER BY a, c"),
        )
    }

    @Test
    fun windowFiltersKeepFetchAndOffsetAtTheOuterLevel() {
        for (prefix in listOf("SELECT x", "SELECT DISTINCT ON (x) x")) {
            val tree = Dialects.forName("duckdb").parseOne(
                "$prefix FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 1 " +
                    "ORDER BY x OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY",
            )
            val lowered = eliminateDistinctOn(eliminateQualify(tree)) as Select
            assertNotNull(lowered.args["order"])
            assertNotNull(lowered.args["offset"])
            assertNotNull(lowered.args["limit"])
            for (inner in lowered.findAll<Select>().drop(1)) {
                assertNull(inner.args["order"])
                assertNull(inner.args["offset"])
                assertNull(inner.args["limit"])
            }
        }
    }

    @Test
    fun ambiguousStarOrderingRequiresExpansion() {
        assertFailsWith<UnsupportedError> {
            transpile("duckdb", "presto", "SELECT t.* FROM t CROSS JOIN u " +
                "QUALIFY ROW_NUMBER() OVER () > 0 ORDER BY u.y")
        }
    }

    @Test
    fun hiddenDistinctSortKeysAndUnresolvedCompoundAliasesAreRefused() {
        for (order in listOf("y", "ROW_NUMBER() OVER (ORDER BY x)")) {
            assertFailsWith<UnsupportedError> {
                transpile("duckdb", "postgres", "SELECT DISTINCT x FROM t " +
                    "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY $order")
            }
        }
        assertFailsWith<UnsupportedError> {
            transpile("duckdb", "presto", "SELECT x AS y FROM t " +
                "QUALIFY ROW_NUMBER() OVER (ORDER BY x) > 0 ORDER BY y + 1")
        }
    }

    // sqlglot: transforms.move_ctes_to_top_level (nested WITH hoisted for Spark<3/Hive)
    @Test
    fun moveCtesToTopLevel() {
        assertEquals(
            "WITH t AS (SELECT 1 AS c) SELECT * FROM (SELECT * FROM t) AS subq",
            transpile("duckdb", "spark", "SELECT * FROM (WITH t AS (SELECT 1 AS c) SELECT * FROM t) AS subq"),
        )
    }
}
