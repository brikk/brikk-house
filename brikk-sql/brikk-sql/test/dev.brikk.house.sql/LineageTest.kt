package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.optimizer.lineage
import dev.brikk.house.sql.optimizer.lineageAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand assertions for optimizer/Lineage.kt. Every expected string was produced by
 * Python sqlglot's lineage() (reference/sqlglot @ v30.12.0-44-g93d16591).
 */
class LineageTest {

    @Test
    fun twoHopChainThroughCte() {
        val node = lineage(
            "c",
            "WITH cte AS (SELECT a AS b FROM x) SELECT b AS c FROM cte",
            schema = mapOf("x" to mapOf("a" to "int")),
        )
        assertEquals("c", node.name)
        assertEquals("cte.b AS c", node.expression.sql())
        assertEquals(
            "WITH cte AS (SELECT x.a AS b FROM x AS x) SELECT cte.b AS c FROM cte AS cte",
            node.source.sql(),
        )

        val hop1 = node.downstream.single()
        assertEquals("cte.b", hop1.name)
        assertEquals("x.a AS b", hop1.expression.sql())
        assertEquals("SELECT x.a AS b FROM x AS x", hop1.source.sql())
        assertEquals("cte", hop1.referenceNodeName)

        val hop2 = hop1.downstream.single()
        assertEquals("x.a", hop2.name)
        assertEquals("x AS x", hop2.source.sql())
        assertTrue(hop2.downstream.isEmpty())

        // Node.walk is a preorder DFS over the chain
        assertEquals(listOf("c", "cte.b", "x.a"), node.walk().map { it.name }.toList())
    }

    @Test
    fun unionPositionalLineage() {
        val node = lineage(
            "a",
            "SELECT a FROM x UNION ALL SELECT b AS a FROM y",
            schema = mapOf("x" to mapOf("a" to "int"), "y" to mapOf("b" to "int")),
        )
        assertEquals("UNION", node.name)
        assertEquals(
            "SELECT x.a AS a FROM x AS x UNION ALL SELECT y.b AS a FROM y AS y",
            node.source.sql(),
        )
        assertEquals("x.a AS a", node.expression.sql())

        // Each union branch is resolved by ordinal position (downstream order follows
        // the branch order of the set operation).
        assertEquals(2, node.downstream.size)
        val (left, right) = node.downstream
        assertEquals("0", left.name)
        assertEquals("x.a AS a", left.expression.sql())
        assertEquals(listOf("x.a"), left.downstream.map { it.name })
        assertEquals("0", right.name)
        assertEquals("y.b AS a", right.expression.sql())
        assertEquals(listOf("y.b"), right.downstream.map { it.name })
    }

    @Test
    fun placeholderLeafForUnknownSource() {
        // `a` is unqualifiable (could come from y's star or from unknown z), so its
        // lineage dead-ends in a Placeholder leaf.
        val node = lineage(
            "a",
            "WITH y AS (SELECT * FROM x) SELECT a FROM y JOIN z USING (uid)",
        )
        assertEquals(
            "WITH y AS (SELECT * FROM x AS x) SELECT a AS a " +
                "FROM y AS y JOIN z AS z ON y.uid = z.uid",
            node.source.sql(),
        )
        val leaf = node.downstream.single()
        assertEquals("a", leaf.name)
        assertTrue(leaf.expression is Placeholder)
        assertEquals("?", leaf.source.sql())
        assertTrue(leaf.downstream.isEmpty())
    }

    @Test
    fun sourcesSplicingTagsSourceName() {
        val node = lineage(
            "a",
            "SELECT a FROM z",
            schema = mapOf("x" to mapOf("a" to "int")),
            sources = mapOf("z" to "SELECT a FROM x"),
        )
        assertEquals(
            "SELECT z.a AS a FROM (SELECT x.a AS a FROM x AS x) AS z /* source: z */",
            node.source.sql(),
        )
        assertEquals("", node.sourceName)

        val hop = node.downstream.single()
        assertEquals("z.a", hop.name)
        assertEquals("SELECT x.a AS a FROM x AS x", hop.source.sql())
        assertEquals("z", hop.sourceName)

        val leaf = hop.downstream.single()
        assertEquals("x.a", leaf.name)
        assertEquals("x AS x", leaf.source.sql())
    }

    @Test
    fun trimSelectsFlag() {
        // trim_selects=true (default) trims the node source to the relevant column
        val trimmed = lineage(
            "a",
            "SELECT a, b FROM x",
            schema = mapOf("x" to mapOf("a" to "int", "b" to "int")),
        )
        assertEquals("SELECT x.a AS a FROM x AS x", trimmed.source.sql())
        assertEquals(listOf("x.a"), trimmed.downstream.map { it.name })

        // trim_selects=false keeps the full select
        val full = lineage(
            "a",
            "SELECT a, b FROM x",
            schema = mapOf("x" to mapOf("a" to "int", "b" to "int")),
            trimSelects = false,
        )
        assertEquals("SELECT x.a AS a, x.b AS b FROM x AS x", full.source.sql())
        assertEquals("x.a AS a", full.expression.sql())
    }

    @Test
    fun allColumnsMode() {
        // sqlglot: lineage(None, sql) — every output column, shared cache
        val result = lineageAll(
            "SELECT a, b + 1 AS c FROM x",
            schema = mapOf("x" to mapOf("a" to "int", "b" to "int")),
        )
        assertEquals(listOf("a", "c"), result.keys.toList())
        assertEquals("x.b + 1 AS c", result.getValue("c").expression.sql())
        assertEquals(listOf("x.b"), result.getValue("c").downstream.map { it.name })
        assertEquals(listOf("x.a"), result.getValue("a").downstream.map { it.name })
    }
}
