package dev.brikk.house.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class BrikkSqlTest {
    @Test
    fun moduleIsWired() {
        assertEquals("brikk-sql", BrikkSql.NAME)
        assertEquals("v30.17.0-93-gdcc36544a", BrikkSql.SQLGLOT_PIN)
    }
}
