package dev.brikk.house.sql.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelTest {

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
            Rel.SOURCE_PREFIX + "|> EXTEND payload->>'user_id' AS user_id, payload->>'action' AS action",
            "postgres",
        ).input(Rel.SOURCE_SLOT, src)
        val agg = Rel<Partial>(
            Rel.SOURCE_PREFIX + "|> WHERE action = 'login' |> AGGREGATE count(*) AS logins GROUP BY user_id",
            "postgres",
        ).input(Rel.SOURCE_SLOT, ext)

        val sql = agg.render()
        assertTrue(sql.startsWith("WITH s0 AS (SELECT * FROM public.events WHERE event_at >= %(start)s), s1 AS ("), sql)
        assertTrue(sql.contains("FROM s0"), sql)
        assertTrue(sql.contains("FROM s1"), sql)
        assertTrue(sql.endsWith(" SELECT * FROM s2"), sql)
        assertTrue(!sql.contains("__src", ignoreCase = true), sql)
        assertEquals(mapOf("start" to "2026-01-01"), agg.bindings())
    }
}
