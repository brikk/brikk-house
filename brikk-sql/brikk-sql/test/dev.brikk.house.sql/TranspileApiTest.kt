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

    @Test
    fun transpileAllPreservesSourceDialectSemanticsForEveryStatement() {
        val source = "SELECT CURRENT_DATE(); SELECT CURRENT_DATE()"
        assertEquals(
            List(2) { "SELECT CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS DATE)" },
            transpileAll(source, read = "bigquery", write = "duckdb"),
        )
        assertEquals(
            List(2) { "SELECT CURRENT_DATE" },
            transpileAll(source, read = "duckdb", write = "duckdb"),
        )
    }
}
