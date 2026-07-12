package dev.brikk.house.intellij

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrikkDialectsTest {

    @Test
    fun `every offered dialect is supported by brikk-sql`() {
        for (name in BrikkDialects.names) {
            assertTrue(BrikkDialects.isSupported(name), "brikk-sql does not know '$name'")
        }
    }

    @Test
    fun `maps IDE language ids to brikk dialects`() {
        assertEquals("mysql", BrikkDialects.fromLanguageId("MySQL"))
        assertEquals("mysql", BrikkDialects.fromLanguageId("MariaDB"))
        assertEquals("postgres", BrikkDialects.fromLanguageId("PostgreSQL"))
        assertEquals("clickhouse", BrikkDialects.fromLanguageId("ClickHouse"))
        assertEquals("doris", BrikkDialects.fromLanguageId("DorisSQL"))
        assertEquals("duckdb", BrikkDialects.fromLanguageId("DuckDB"))
    }

    @Test
    fun `generic and unrelated ids map to null`() {
        assertNull(BrikkDialects.fromLanguageId("SQL"))
        assertNull(BrikkDialects.fromLanguageId("SQL92"))
        assertNull(BrikkDialects.fromLanguageId("Oracle"))
        assertNull(BrikkDialects.fromLanguageId("TSQL"))
    }
}
