package dev.brikk.house.sql.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlTest {
    @Test
    fun `Trino entry point requires compiler rewriting`() {
        val error = assertFailsWith<IllegalStateException> { Sql.trino("SELECT 1 AS id") }
        assertEquals("Sql.trino(...) reached at runtime: the brikk-sql compiler plugin was not applied", error.message)
    }
}
