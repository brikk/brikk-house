package dev.brikk.house.sql.smoke

import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.shape.SqlFragment
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
        val fragment = SqlFragment(sql, report.dialect)
        val with = fragment.ast.args["with_"] as With
        assertEquals(listOf("s0", "s1", "s2"), with.expressionsArg.filterIsInstance<CTE>().map { it.alias })
        assertContains(sql, "SELECT * FROM ")
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
