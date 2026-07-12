package dev.brikk.house.intellij

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TranspileApprovalCacheTest {

    private val cache = TranspileApprovalCache()

    @Test
    fun `approval allows re-execute of identical transpilation`() {
        val key = cache.key("SELECT 1", "clickhouse", "mysql")
        assertFalse(cache.isApproved(key, "SELECT 1"))
        cache.approve(key, "SELECT 1")
        assertTrue(cache.isApproved(key, "SELECT 1"))
    }

    @Test
    fun `changed source SQL requires review again`() {
        val key1 = cache.key("SELECT 1", "clickhouse", "mysql")
        cache.approve(key1, "SELECT 1")
        val key2 = cache.key("SELECT 2", "clickhouse", "mysql")
        assertNotEquals(key1, key2)
        assertFalse(cache.isApproved(key2, "SELECT 2"))
    }

    @Test
    fun `changed generated output requires review again`() {
        val key = cache.key("SELECT 1", "clickhouse", "mysql")
        cache.approve(key, "SELECT 1")
        assertFalse(cache.isApproved(key, "SELECT 1 /* new generator output */"))
    }

    @Test
    fun `dialect pair is part of the key`() {
        assertNotEquals(
            cache.key("SELECT 1", "clickhouse", "mysql"),
            cache.key("SELECT 1", "mysql", "clickhouse"),
        )
    }

    @Test
    fun `key is deterministic`() {
        assertEquals(
            cache.key("SELECT 1", "doris", "duckdb"),
            cache.key("SELECT 1", "doris", "duckdb"),
        )
    }
}
