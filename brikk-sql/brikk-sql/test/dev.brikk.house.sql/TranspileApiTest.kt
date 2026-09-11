package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.dialects.transpileAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TranspileApiTest {
    @Test
    fun transpileAllPreservesEveryStatement() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            transpileAll("SELECT 1; SELECT 2"),
        )
        assertEquals(listOf("SELECT 1"), transpileAll("SELECT 1;"))
        assertEquals(listOf("SELECT 1", "SELECT 2"), transpileAll("SELECT 1; ; SELECT 2"))
    }

    @Test
    fun transpileRejectsScriptsInsteadOfDroppingStatements() {
        val error = assertFailsWith<IllegalArgumentException> {
            transpile("SELECT 1; SELECT 2")
        }
        assertContains(error.message.orEmpty(), "found 2")
        assertContains(error.message.orEmpty(), "transpileAll")
        assertEquals("SELECT 1", transpile("SELECT 1;"))
    }

    @Test
    fun transpileAllAppliesOptionsToEveryStatement() {
        assertEquals(
            listOf("SELECT CAST(x AS STRING)", "SELECT CAST(y AS STRING)"),
            transpileAll(
                "SELECT CAST(x AS TEXT); SELECT CAST(y AS TEXT)",
                write = "doris",
            ),
        )
    }
}
