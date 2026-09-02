package dev.brikk.house.sql.smoke

import dev.brikk.house.sql.runtime.Rel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun pipelineRendersAndIsTypedByGeneratedShapes() {
        val report: Rel<LoginDailyOut> = report(Instant.EPOCH, Instant.now())
        val sql = report.render()
        assertTrue(sql.startsWith("WITH s0 AS (SELECT * FROM public.events WHERE event_at >= %(start)s AND event_at < %(end)s), s1 AS ("), sql)
        assertContains(sql, "payload ->> 'user_id' AS user_id")
        assertContains(sql, "WHERE action = 'login'")
        assertTrue(sql.endsWith(" SELECT * FROM s2"), sql)
        assertEquals(setOf("start", "end"), report.bindings().keys)
    }
}
