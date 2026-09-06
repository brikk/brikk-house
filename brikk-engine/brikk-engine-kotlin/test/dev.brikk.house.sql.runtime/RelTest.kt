package dev.brikk.house.sql.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelTest {

    @Test
    fun singleStageRenderingPreservesSourceDialectContext() {
        assertEquals("SELECT lowerUTF8(x) AS x FROM t",
            Rel<Partial>("SELECT LOWER(x) AS x FROM t", "duckdb").render("clickhouse"))
        assertEquals("SELECT lower(x) AS x FROM t",
            Rel<Partial>("SELECT LOWER(x) AS x FROM t", "clickhouse").render())
    }

    @Test
    fun mixedChainsUseEachNodesDialectRatherThanTheRootDialect() {
        for ((sourceDialect, rootDialect, first, second) in listOf(
            listOf("duckdb", "clickhouse", "lowerUTF8", "lower"),
            listOf("clickhouse", "duckdb", "lower", "lowerUTF8"),
        )) {
            val source = Rel<Partial>("SELECT LOWER(x) AS x FROM t", sourceDialect)
            val root = Rel<Partial>("SELECT LOWER(x) AS x FROM src()", rootDialect).input("src", source)
            assertEquals("WITH s0 AS (SELECT $first(x) AS x FROM t), " +
                "s1 AS (SELECT $second(x) AS x FROM s0) SELECT * FROM s1", root.render("clickhouse"))
        }
    }

    @Test
    fun runtimeAlsoAppliesSourceSpecificWeekAndRoundingRules() {
        assertEquals("SELECT toISOWeek(d) AS w FROM t",
            Rel<Partial>("SELECT WEEK(d) AS w FROM t", "duckdb").render("clickhouse"))
        assertEquals("SELECT week(d) AS w FROM t",
            Rel<Partial>("SELECT WEEK(d) AS w FROM t", "clickhouse").render())
        assertEquals("SELECT sign(x) * floor(abs(x) * pow(10, 0) + 0.5) / pow(10, 0) AS n FROM t",
            Rel<Partial>("SELECT ROUND(x) AS n FROM t", "duckdb").render("clickhouse"))
        assertEquals("SELECT ROUND(x) AS n FROM t",
            Rel<Partial>("SELECT ROUND(x) AS n FROM t", "clickhouse").render())
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
}
