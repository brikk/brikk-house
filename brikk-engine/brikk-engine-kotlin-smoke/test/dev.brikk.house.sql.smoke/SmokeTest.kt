package dev.brikk.house.sql.smoke

import dev.brikk.house.sql.runtime.Rel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeTest {
    // Structural assertions only: demo.kt picks whichever dialect has the best IDE support for
    // pipe syntax at the moment (Doris today), and the spelling of JSON extraction, quoting and
    // bind parameters differs per dialect.
    @Test
    fun pipelineRendersAndIsTypedByGeneratedShapes() {
        val report: Rel<LoginDailyOut> = report(Instant.EPOCH, Instant.now())
        val sql = report.render()
        // Three stages -> CTE chain s0 (catalog source), s1 (extract), s2 (aggregate).
        assertTrue(sql.startsWith("WITH s0 AS (SELECT * FROM "), sql)
        assertContains(sql, "s1 AS (")
        assertContains(sql, "s2 AS (")
        assertContains(sql, "FROM s0")
        assertContains(sql, "FROM s1")
        assertTrue(sql.endsWith(" SELECT * FROM s2"), sql)
        // Slot calls are bound to the CTEs, never rendered.
        assertTrue(!sql.contains("src()") && !sql.contains("logins()"), sql)
        // Stage content, dialect-neutral parts.
        assertContains(sql, "AS user_id")
        assertContains(sql, "WHERE action = 'login'")
        assertContains(sql, "AS logins")
        assertEquals(setOf("start", "end"), report.bindings().keys)
    }
}
