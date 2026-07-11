package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.optimizer.Scope
import dev.brikk.house.sql.optimizer.ScopeType
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.optimizer.traverseScope
import dev.brikk.house.sql.optimizer.walkInScope
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Hand assertions against sqlglot's tests/test_optimizer.py::test_scope oracle. */
class ScopeTest {

    private fun columnName(c: Column): String {
        val table = c.text("table")
        return if (table.isNotEmpty()) "$table.${c.name}" else c.name
    }

    @Test
    fun nestedSubqueryScopeChain() {
        val scopes = traverseScope(
            parseOne("SELECT a FROM (SELECT a FROM (SELECT a FROM x) AS y) AS z")
        )
        assertEquals(3, scopes.size)
        assertEquals(
            listOf(ScopeType.DERIVED_TABLE, ScopeType.DERIVED_TABLE, ScopeType.ROOT),
            scopes.map { it.scopeType },
        )
        assertEquals(listOf("x"), scopes[0].sources.keys.toList())
        assertEquals(listOf("y"), scopes[1].sources.keys.toList())
        assertEquals(listOf("z"), scopes[2].sources.keys.toList())
        // parent chain: innermost scope hangs off the middle scope, which hangs off root
        assertTrue(scopes[0].parent === scopes[1])
        assertTrue(scopes[1].parent === scopes[2])
        assertTrue(scopes[2].parent == null)
    }

    @Test
    fun cteSourcesVisibleToMainScope() {
        // From sqlglot test_scope: 7 scopes, CTE sources q/z visible next to r/s.
        val sql = """
            WITH q AS (
              SELECT x.b FROM x
            ), r AS (
              SELECT y.b FROM y
            ), z as (
              SELECT cola, colb FROM (VALUES(1, 'test')) AS tab(cola, colb)
            )
            SELECT
              r.b,
              s.b
            FROM r
            JOIN (
              SELECT y.c AS b FROM y
            ) s
            ON s.b = r.b
            WHERE s.b > (SELECT MAX(x.a) FROM x WHERE x.b = s.b)
        """.trimIndent()
        val scopes = traverseScope(parseOne(sql))
        assertEquals(7, scopes.size)
        assertEquals(setOf("q", "z", "r", "s"), scopes[6].sources.keys)
        assertEquals(6, scopes[6].columns.size)
        assertEquals(setOf("r", "s"), scopes[6].columns.map { it.text("table") }.toSet())
        assertEquals(emptyList(), scopes[6].sourceColumns("q"))
        assertEquals(2, scopes[6].sourceColumns("r").size)
        // CTEs become Scope sources; the joined derived table too
        assertTrue(scopes[6].sources.getValue("q") is Scope)
        assertTrue(scopes[6].sources.getValue("s") is Scope)
    }

    @Test
    fun correlatedSubqueryDetection() {
        // Outer table name collides with a CTE name; correlation must still be detected.
        val sql = "WITH x AS (SELECT 1 AS id) SELECT x.id, " +
            "(SELECT MAX(x2.id) FROM x AS x2 WHERE x2.id = x.id) AS mx FROM x"
        val scopes = traverseScope(parseOne(sql))
        val subqueryScope = scopes.first { it.isSubquery }
        assertTrue(subqueryScope.isCorrelatedSubquery)
        assertTrue(subqueryScope.externalColumns.map { columnName(it) }.contains("x.id"))
    }

    @Test
    fun unionBranchScopes() {
        val scopes = traverseScope(parseOne("SELECT x FROM t UNION SELECT y FROM u"))
        assertEquals(3, scopes.size)
        assertEquals(
            listOf(ScopeType.UNION, ScopeType.UNION, ScopeType.ROOT),
            scopes.map { it.scopeType },
        )
        assertEquals(listOf("t"), scopes[0].sources.keys.toList())
        assertEquals(listOf("u"), scopes[1].sources.keys.toList())
        val root = scopes[2]
        assertEquals(2, root.unionScopes.size)
        assertTrue(root.unionScopes[0] === scopes[0])
        assertTrue(root.unionScopes[1] === scopes[1])
    }

    @Test
    fun buildScopeReturnsRootAndSelectedSources() {
        val root = buildScope(parseOne("SELECT a FROM (SELECT a FROM x) AS y"))
        assertNotNull(root)
        assertTrue(root.isRoot)
        assertTrue(root.expression is Select)
        assertEquals(listOf("y"), root.selectedSources.keys.toList())
        assertEquals(listOf("a"), root.columns.map { it.name })

        // Semi-join RHS is not a selected source but keeps a source entry.
        val semi = buildScope(parseOne("SELECT * FROM x LEFT SEMI JOIN y ON x.a = y.a"))
        assertNotNull(semi)
        assertEquals(setOf("x", "y"), semi.sources.keys)
        assertEquals(listOf("x"), semi.selectedSources.keys.toList())
        assertEquals(setOf("y"), semi.semiOrAntiJoinTables)
    }

    @Test
    fun walkInScopeStopsAtChildScopes() {
        val expression = parseOne(
            "SELECT a FROM x JOIN (SELECT b FROM y) AS s ON s.b = x.a WHERE x.c > 1"
        )
        // Walking the whole statement in scope must not descend into the derived table.
        val columns = walkInScope(expression)
            .filterIsInstance<Column>()
            .map { columnName(it) }
            .toSet()
        assertEquals(setOf("a", "s.b", "x.a", "x.c"), columns)

        // Walking from an arbitrary inner node works too.
        val where = expression.find<Where>()!!
        val whereColumns =
            walkInScope(where).filterIsInstance<Column>().map { columnName(it) }.toList()
        assertEquals(listOf("x.c"), whereColumns)

        // Tables from the walk exclude the derived table's internals.
        val tables = walkInScope(expression).filterIsInstance<Table>().map { it.name }.toList()
        assertEquals(listOf("x"), tables)
    }
}
