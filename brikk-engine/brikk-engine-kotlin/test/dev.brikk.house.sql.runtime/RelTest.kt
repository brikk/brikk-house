package dev.brikk.house.sql.runtime

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelTest {

    @Test
    fun nativeSqlKeepsItsExactTextInTheSameDialect() {
        val queries = mapOf(
            "postgres" to " \n-- leading\nselect /* keep */ 1::int as \"n\"; -- trailing\n ",
            "duckdb" to "\tselect  1::INTEGER AS n;\n",
            "doris" to " select /*+ SET_VAR(exec_mem_limit=1234) */ 1 as n;\n",
            "clickhouse" to "select lower('AbC') AS n SETTINGS max_threads = 1;\n",
        )
        for ((dialect, sql) in queries) assertEquals(sql, Rel<Partial>(sql, dialect).render(), dialect)
        assertEquals(queries.getValue("postgres"), Rel<Partial>(queries.getValue("postgres"), "postgresql").render("postgres"))
    }

    @Test
    fun nativeParametersDoNotCauseUnrelatedSqlRewriting() {
        val sql = " \nselect :n::BIGINT AS n, ':n |> text' AS note /* :n */;\n "
        val query = Rel<Partial>(sql, "postgres").bind("n", 3)
        assertEquals(sql, query.render())
        assertEquals(mapOf("n" to 3), query.bindings())
        assertEquals(3, scalar(query))
    }

    @Test
    fun explicitTranslationAndNestedPipesStillLower() {
        val sql = " select 1::INTEGER AS n; "
        val translated = Rel<Partial>(sql, "postgres").render("doris")
        assertTrue(translated != sql)
        assertContains(translated, "CAST(1 AS INT)")

        val nested = Rel<Partial>(
            "SELECT id FROM (FROM (SELECT 1 AS id) AS raw |> SELECT id) AS nested", "postgres",
        )
        assertTrue(!nested.render().contains("|>"), nested.render())
        assertEquals(1, scalar(nested))
    }

    @Test
    fun fromFirstIsNativeOnlyWhereTheTargetSupportsIt() {
        val sql = "FROM (SELECT 1 AS id) AS source"
        assertEquals(sql, Rel<Partial>(sql, "duckdb").render())
        for (dialect in listOf("postgres", "doris")) {
            assertTrue(Rel<Partial>(sql, dialect).render().startsWith("SELECT * FROM"))
            val nested = Rel<Partial>("WITH q AS ($sql SELECT id) SELECT id FROM q", dialect)
            assertContains(nested.render(), "WITH q AS (SELECT id FROM")
        }
    }

    @Test
    fun singleStageRendersDirectly() {
        val src = Rel<Partial>("FROM public.events |> WHERE event_at >= :start", "postgres").bind("start", 1)
        val sql = src.render()
        assertEquals("SELECT * FROM public.events WHERE event_at >= %(start)s", sql)
        assertEquals(mapOf("start" to 1), src.bindings())
    }

    @Test
    fun threeStageChainRendersAsCtes() {
        val src = Rel<Partial>("FROM public.events |> WHERE event_at >= :start", "postgres").bind("start", "2026-01-01")
        val ext = Rel<Partial>(
            "FROM src() |> EXTEND payload->>'user_id' AS user_id, payload->>'action' AS action",
            "postgres",
        ).input("src", src)
        val agg = Rel<Partial>(
            "FROM events() |> WHERE action = 'login' |> AGGREGATE count(*) AS logins GROUP BY user_id",
            "postgres",
        ).input("events", ext)

        val sql = agg.render()
        assertTrue(sql.startsWith("WITH s0 AS (SELECT * FROM public.events WHERE event_at >= %(start)s), s1 AS ("), sql)
        assertTrue(sql.contains("FROM s0"), sql)
        assertTrue(sql.contains("FROM s1"), sql)
        assertTrue(sql.endsWith(" SELECT * FROM s2"), sql)
        assertTrue(!sql.contains("src()", ignoreCase = true) && !sql.contains("events()", ignoreCase = true), sql)
        assertEquals(mapOf("start" to "2026-01-01"), agg.bindings())
    }

    @Test
    fun stageLocalBindingsDoNotOverwriteEachOther() {
        val src = Rel<Partial>("SELECT :n AS x", "postgres").bind("n", 1)
        val out = Rel<Partial>("SELECT x + :n AS y FROM src()", "postgres")
            .input("src", src).bind("n", 2)
        assertEquals(3, scalar(out))
        val sql = out.render()
        val names = SqlFragment(sql, "postgres").scalarParams.mapNotNull { it.name }
        assertEquals(2, names.toSet().size, sql)
        assertEquals(setOf(1, 2), out.bindings().values.toSet())
        assertEquals(names.toSet(), out.bindings().keys)
        assertEquals(sql, out.render())
    }

    @Test
    fun independentCallsToTheSameSourceHaveIndependentBindings() {
        fun source(value: Int) = Rel<Partial>("SELECT :n AS id", "postgres").bind("n", value)
        val out = Rel<Partial>("SELECT a.id + b.id AS total FROM a() CROSS JOIN b()", "postgres")
            .input("a", source(3)).input("b", source(4))
        assertEquals(7, scalar(out))
    }

    @Test
    fun existingPlaceholderNamesAreReservedAndLiteralTextIsNotRewritten() {
        val src = Rel<Partial>("SELECT :n AS x", "postgres").bind("n", 1)
        val out = Rel<Partial>(
            "SELECT x + :n + :__brikk_bind_0_0 AS y, ':n' AS literal FROM src()", "postgres",
        ).input("src", src).bind("n", 2).bind("__brikk_bind_0_0", 10)
        assertEquals(13, scalar(out))
        assertTrue(out.render().contains("':n'"), out.render())
        assertEquals(10, out.bindings()["__brikk_bind_0_0"])

        val unbound = Rel<Partial>("SELECT x + :n + :__brikk_bind_0_0 FROM src()", "postgres")
            .input("src", src).bind("n", 2)
        assertTrue("__brikk_bind_0_0" !in unbound.bindings())
        assertTrue(SqlFragment(unbound.render(), "postgres").scalarParams.any { it.name == "__brikk_bind_0_0" })
    }

    @Test
    fun atStyleBindingsAreAlsoLocalToTheirStage() {
        val src = Rel<Partial>("SELECT @n AS x", "doris").bind("n", 1)
        val out = Rel<Partial>("SELECT x + @n AS y FROM src()", "doris")
            .input("src", src).bind("n", 2)
        assertEquals(3, scalar(out))
    }

    @Test
    fun parameterNamesCannotCollideAfterCaseFolding() {
        val src = Rel<Partial>("SELECT :n AS x", "postgres").bind("n", 1)
        val out = Rel<Partial>("SELECT x + :n + :__BRIKK_BIND_0_0 FROM src()", "postgres")
            .input("src", src).bind("n", 2).bind("__BRIKK_BIND_0_0", 10)
        assertEquals(13, scalar(out))
        assertEquals(3, scalar(Rel<Partial>("SELECT :n + :N", "postgres").bind("n", 1).bind("N", 2)))
    }

    @Test
    fun anUnboundNameDoesNotBorrowAnUpstreamValue() {
        val src = Rel<Partial>("SELECT :n AS x", "postgres").bind("n", 1)
        val out = Rel<Partial>("SELECT x + :n FROM src()", "postgres").input("src", src)
        val names = SqlFragment(out.render(), "postgres").scalarParams.mapNotNull { it.name }.toSet()
        assertEquals(2, names.size)
        assertEquals(1, out.bindings().size)
        assertEquals(1, (names - out.bindings().keys).size)
    }

    @Test
    fun slotQualifiersAndExplicitAliasesResolveAfterComposition() {
        val src = Rel<Partial>("SELECT 5 AS id", "postgres")
        assertEquals(5, scalar(Rel<Partial>("SELECT src.id FROM src()", "postgres").input("src", src)))
        assertEquals(5, scalar(Rel<Partial>("SELECT chosen.id FROM src() AS chosen", "postgres").input("src", src)))
        val out = Rel<Partial>("FROM src() |> JOIN dim() ON src.id = dim.id |> SELECT src.id", "postgres")
            .input("src", src).input("dim", Rel<Partial>("SELECT 5 AS id", "postgres"))
        assertEquals(5, scalar(out))
    }

    @Test
    fun implicitSlotAliasesKeepTheirOriginalQuoting() {
        val src = Rel<Partial>("SELECT 5 AS id", "postgres")
        val plain = Rel<Partial>("SELECT Foo\$bar.id FROM Foo\$bar()", "postgres").input("Foo\$bar", src).render()
        assertContains(plain, "AS Foo\$bar")
        val alias = SqlFragment(plain, "postgres").ast.findAll(TableAlias::class).single { it.name == "Foo\$bar" }
        assertEquals(false, (alias.thisArg as Identifier).quoted)
        val quoted = Rel<Partial>("SELECT \"Mixed\".id FROM \"Mixed\"()", "postgres").input("Mixed", src).render()
        assertContains(quoted, "AS \"Mixed\"")
    }

    @Test
    fun userCtesAndPhysicalTablesCannotCaptureGeneratedNames() {
        val src = Rel<Partial>("SELECT 1 AS id", "postgres")
        val out = Rel<Partial>("WITH s0 AS (SELECT 2 AS id) SELECT * FROM src()", "postgres")
            .input("src", src)
        assertEquals(1, scalar(out))
        val nested = Rel<Partial>(
            "SELECT src.id FROM src() CROSS JOIN (WITH s0 AS (SELECT 9 AS id) SELECT * FROM s0) AS local_rows", "postgres",
        ).input("src", src)
        assertEquals(1, scalar(nested))

        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE s0 AS SELECT 7 AS id")
                val physical = Rel<Partial>("SELECT id FROM s0", "postgres")
                val query = Rel<Partial>("SELECT src.id FROM src()", "postgres").input("src", physical)
                statement.executeQuery(query.render("duckdb")).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(7, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun sharedInputsHaveOneBindingAndCyclesFailClearly() {
        val src = Rel<Partial>("SELECT :n AS id", "postgres").bind("n", 3)
        val out = Rel<Partial>("SELECT a.id + b.id FROM a() CROSS JOIN b()", "postgres")
            .input("a", src).input("b", src)
        assertEquals(6, scalar(out))
        assertEquals(mapOf("n" to 3), out.bindings())

        val cyclic = Rel<Partial>("SELECT * FROM src()", "postgres")
        cyclic.input("src", cyclic)
        assertEquals("Cyclic Rel inputs", assertFailsWith<IllegalArgumentException> { cyclic.render() }.message)
        assertFailsWith<IllegalArgumentException> { cyclic.bindings() }
    }

    /** DuckDB JDBC requires numeric parameters; map parsed names to indexes by binding key. */
    private fun scalar(rel: Rel<*>): Int {
        val bindings = rel.bindings()
        val indexes = bindings.keys.withIndex().associate { it.value to it.index + 1 }
        val tree = SqlFragment(rel.render("duckdb"), "duckdb").ast
        val params = tree.findAll(Placeholder::class).toList()
        val names = params.map { it.name }.distinct()
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size, "Named parameters must remain distinct in DuckDB")
        for (param in params) {
            param.set("this", indexes.getValue(param.name).toString())
        }
        return DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.prepareStatement(Dialects.forName("duckdb").generate(tree)).use { statement ->
                bindings.values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val result = rows.getInt(1)
                    assertTrue(!rows.next(), "Expected one result row")
                    result
                }
            }
        }
    }
}
