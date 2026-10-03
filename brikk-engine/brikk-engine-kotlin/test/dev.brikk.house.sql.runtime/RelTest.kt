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
    fun singleStageRenderingPreservesSourceDialectContext() {
        assertEquals("SELECT lowerUTF8(x) AS x FROM t",
            Rel<Partial>("SELECT LOWER(x) AS x FROM t", "duckdb").render("clickhouse"))
        assertEquals("WITH __tmp1 AS (SELECT LOWER(x) AS x FROM t ) SELECT * FROM __tmp1",
            Rel<Partial>("FROM t |> SELECT LOWER(x) AS x", "clickhouse").render())
    }

    @Test
    fun mixedChainsUseEachNodesDialectRatherThanTheRootDialect() {
        for ((sourceDialect, rootDialect, first, second) in listOf(
            listOf("duckdb", "clickhouse", "lowerUTF8", "LOWER"),
            listOf("clickhouse", "duckdb", "LOWER", "lowerUTF8"),
        )) {
            val source = Rel<Partial>("SELECT LOWER(x) AS x FROM t", sourceDialect)
            val root = Rel<Partial>("SELECT LOWER(x) AS x FROM src()", rootDialect).input("src", source)
            assertEquals("WITH s0 AS (SELECT $first(x) AS x FROM t), " +
                "s1 AS (SELECT $second(x) AS x FROM s0 AS src) SELECT * FROM s1", root.render("clickhouse"))
        }
    }

    @Test
    fun runtimeAlsoAppliesSourceSpecificWeekAndRoundingRules() {
        assertEquals("SELECT toISOWeek(d) AS w FROM t",
            Rel<Partial>("SELECT WEEK(d) AS w FROM t", "duckdb").render("clickhouse"))
        assertEquals("WITH __tmp1 AS (SELECT WEEK(d) AS w FROM t ) SELECT * FROM __tmp1",
            Rel<Partial>("FROM t |> SELECT WEEK(d) AS w", "clickhouse").render())
        assertEquals("SELECT sign(x) * floor(abs(x) * pow(10, 0) + 0.5) / pow(10, 0) AS n FROM t",
            Rel<Partial>("SELECT ROUND(x) AS n FROM t", "duckdb").render("clickhouse"))
        assertEquals("WITH __tmp1 AS (SELECT ROUND(x) AS n FROM t ) SELECT * FROM __tmp1",
            Rel<Partial>("FROM t |> SELECT ROUND(x) AS n", "clickhouse").render())
    }

    @Test
    fun singleStageRendersDirectly() {
        val src = Rel<Partial>("FROM public.events |> WHERE event_at >= :start", "postgres").bind("start", 1)
        val sql = src.render()
        assertEquals("SELECT * FROM public.events  WHERE event_at >= :start", sql)
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
        assertTrue(sql.startsWith("WITH s0 AS (SELECT * FROM public.events  WHERE event_at >= :start), s1 AS ("), sql)
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

    @Test
    fun nativeCompositionEditsOnlySlotsAndRequiredTerminators() {
        val sourceSql = "\n-- head\nselect /* source */ 5::integer as id; -- source end"
        val rootSql = "\tselect /* root */ src.id::integer as id from src(/* inside */); -- root end"
        val source = Rel<Partial>(sourceSql, "postgres")
        val root = Rel<Partial>(rootSql, "postgres").input("src", source)
        assertEquals(
            "WITH s0 AS (\n-- head\nselect /* source */ 5::integer as id -- source end\n), " +
                "s1 AS (\tselect /* root */ src.id::integer as id from s0/* inside */ AS src -- root end\n) SELECT * FROM s1",
            root.render(),
        )
        assertEquals(5, scalar(root))
    }

    @Test
    fun nativeBindingCollisionsRetainMarkerStyleCastsAndCommentText() {
        val source = Rel<Partial>("select :n::int as x /* :n */;", "postgres").bind("n", 2)
        val root = Rel<Partial>(
            "select src.x + %(n)s::int as y, ':n; %(n)s src() 😀' as note from src() -- %(n)s", "postgres",
        ).input("src", source).bind("n", 3)
        assertEquals(
            "WITH s0 AS (select :__brikk_bind_0_0::int as x /* :n */), " +
                "s1 AS (select src.x + %(__brikk_bind_1_0)s::int as y, ':n; %(n)s src() 😀' as note " +
                "from s0 AS src -- %(n)s\n) SELECT * FROM s1",
            root.render(),
        )
        assertEquals(5, scalar(root))
        assertEquals(setOf(2, 3), root.bindings().values.toSet())
    }

    @Test
    fun singleNativeQueryCanRenameBindingsWithoutRegeneration() {
        val root = Rel<Partial>(" \nselect :n::int + :N::int as y /* :N */;\n", "postgres")
            .bind("n", 2).bind("N", 3)
        assertEquals(
            " \nselect :__brikk_bind_0_0::int + :__brikk_bind_0_1::int as y /* :N */;\n",
            root.render(),
        )
        assertEquals(5, scalar(root))
    }

    @Test
    fun sourceCoordinatesAreUtf16AndDoNotRewriteScalarCalls() {
        val source = Rel<Partial>("select 5 as id", "postgres")
        val root = Rel<Partial>(
            "select '😀; src()' as note, src.id as id from src ( ) as src /* src() */", "postgres",
        ).input("src", source)
        assertContains(root.render(), "select '😀; src()' as note, src.id as id from s0   as src /* src() */")
        // Only Table(this=Anonymous) nodes are slots, never same-named scalar functions.
        val scalarCall = Rel<Partial>("select src(), src.id from src()", "postgres").input("src", source)
        assertContains(scalarCall.render(), "select src(), src.id from s0 AS src")
    }

    @Test
    fun existingCtesQuotedAliasesAndRecursiveQueriesKeepTheirText() {
        val source = Rel<Partial>(
            "with recursive r(n) as (select 1 union all select n + 1 from r where n < 3) select max(n) as id from r;",
            "postgres",
        )
        val rootSql = "with s0 as (select 10 as id) select \"Mixed\".id from \"Mixed\"()"
        val root = Rel<Partial>(rootSql, "postgres").input("Mixed", source)
        assertEquals(
            "WITH s1 AS (${source.sql.dropLast(1)}), s2 AS (with s0 as (select 10 as id) " +
                "select \"Mixed\".id from s1 AS \"Mixed\") SELECT * FROM s2",
            root.render(),
        )
        assertEquals(3, scalar(root))
    }

    @Test
    fun nativeDorisAndClickhouseHintsAndSettingsSurviveComposition() {
        for ((dialect, sourceSql, rootSql) in listOf(
            Triple("doris", "select /*+ SET_VAR(exec_mem_limit=1234) */ 1 as id;",
                "select /*+ SET_VAR(query_timeout=10) */ q.id from src() as q;"),
            Triple("clickhouse", "select lower('AbC') as id SETTINGS max_threads = 1;",
                "select q.id from src() as q SETTINGS max_threads = 2;"),
        )) {
            val root = Rel<Partial>(rootSql, dialect).input("src", Rel<Partial>(sourceSql, dialect))
            assertEquals(
                "WITH s0 AS (${sourceSql.dropLast(1)}), s1 AS (${rootSql.dropLast(1).replace("src()", "s0")}) SELECT * FROM s1",
                root.render(), dialect,
            )
        }
    }

    @Test
    fun nativeStagesArePreservedEvenWhenAnotherStageNeedsPipeLowering() {
        val sql = "select /* native island */ 2::integer as id; -- not pipe |>"
        val root = Rel<Partial>("FROM src() |> WHERE id = 2 |> SELECT id", "postgres")
            .input("src", Rel<Partial>(sql, "postgres"))
        val rendered = root.render()
        assertContains(rendered, "s0 AS (select /* native island */ 2::integer as id -- not pipe |>\n)")
        assertTrue(!rendered.substringAfter("), s1 AS (").contains("|>"), rendered)
        assertEquals(2, scalar(root))
    }

    @Test
    fun parameterizedSlotCallsAreRejectedRatherThanDroppingArguments() {
        val root = Rel<Partial>("select * from src(5)", "postgres")
            .input("src", Rel<Partial>("select 1 as id", "postgres"))
        val error = assertFailsWith<dev.brikk.house.sql.generator.UnsupportedError> { root.render() }
        assertContains(error.message!!, "zero-argument")
        assertFailsWith<dev.brikk.house.sql.generator.UnsupportedError> { root.render("duckdb") }
    }

    @Test
    fun nativeTypedAndAtOrDollarBindingsKeepTheirDialectSyntax() {
        for ((dialect, marker) in listOf("clickhouse" to "{n: UInt32}", "doris" to "@n", "duckdb" to "\$n")) {
            val source = Rel<Partial>("select $marker as id;", dialect).bind("n", 2)
            val root = Rel<Partial>("select src.id + $marker as id from src();", dialect)
                .input("src", source).bind("n", 3)
            val first = marker.replaceFirst("n", "__brikk_bind_0_0")
            val second = marker.replaceFirst("n", "__brikk_bind_1_0")
            assertEquals(
                "WITH s0 AS (select $first as id), s1 AS (select src.id + $second as id from s0 AS src) SELECT * FROM s1",
                root.render(), dialect,
            )
            assertEquals(5, scalar(root), dialect)
        }
    }

    @Test
    fun exactParameterRangesLeaveStructPunctuationAndColumnsUnchanged() {
        val source = Rel<Partial>("select :n as n", "sqlglot").bind("n", 2)
        val root = Rel<Partial>("select {'key': n} as data, :n as value from src()", "sqlglot")
            .input("src", source).bind("n", 3)
        assertContains(root.render(), "{'key': n} as data, :__brikk_bind_1_0 as value from s0 AS src")
        assertEquals(setOf(2, 3), root.bindings().values.toSet())
    }

    @Test
    fun sourcePreservedDuckdbCompositionExecutesWithoutRegeneratingTheTestInput() {
        val sourceSql = "\n-- native source\nselect /* keep */ 2::integer as id; -- end"
        val source = Rel<Partial>(sourceSql, "duckdb")
        val root = Rel<Partial>("select src.id + 3 as id from src(/* slot trivia */);", "duckdb")
            .input("src", source)
        val rendered = root.render()
        assertEquals(
            "WITH s0 AS (\n-- native source\nselect /* keep */ 2::integer as id -- end\n), " +
                "s1 AS (select src.id + 3 as id from s0/* slot trivia */ AS src) SELECT * FROM s1",
            rendered,
        )
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                // No parse/generate adapter here: execute exactly what render() returned.
                statement.executeQuery(rendered).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(5, rows.getInt(1))
                    assertTrue(!rows.next())
                }
            }
        }
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
