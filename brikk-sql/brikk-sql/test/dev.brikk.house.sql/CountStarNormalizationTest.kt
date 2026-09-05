package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Zero-arg count() normalization. ClickHouse allows a bare count() (count all rows), but
 * MySQL/Doris and Trino/Presto REJECT COUNT() and require COUNT(*). Transpiling ClickHouse
 * count() to those engines must emit COUNT(*). ClickHouse (native count()) and DuckDB (which
 * accepts COUNT()) keep their form; any argument / *  / DISTINCT is untouched.
 *
 * Reported by the Doris IntelliJ plugin agent: ClickHouse count() -> Doris COUNT() is invalid.
 */
class CountStarNormalizationTest {

    private fun w(target: String): String = transpile("SELECT count()", read = "clickhouse", write = target)

    @Test
    fun zeroArgCount_normalizedToCountStar() {
        assertEquals("SELECT COUNT(*)", w("doris"))
        assertEquals("SELECT COUNT(*)", w("trino"))
        assertEquals("SELECT COUNT(*)", w("mysql"))
        assertEquals("SELECT COUNT(*)", w("presto"))
    }

    @Test
    fun nativeBareForm_preserved() {
        // ClickHouse count() is valid and idiomatic; DuckDB accepts COUNT(). Keep faithful.
        assertEquals("SELECT count()", transpile("SELECT count()", read = "clickhouse", write = "clickhouse"))
        assertEquals("SELECT COUNT()", transpile("SELECT count()", read = "clickhouse", write = "duckdb"))
    }

    @Test
    fun argumentedCount_unchanged() {
        for (target in listOf("doris", "trino")) {
            assertEquals("SELECT COUNT(*)", transpile("SELECT count(*)", read = "clickhouse", write = target))
            assertEquals("SELECT COUNT(x)", transpile("SELECT count(x)", read = "clickhouse", write = target))
            assertEquals(
                "SELECT COUNT(DISTINCT x)",
                transpile("SELECT count(DISTINCT x)", read = "clickhouse", write = target),
            )
        }
    }

    @Test
    fun dorisAndTrino_ownZeroArgCount_normalized() {
        // Also normalize within the dialect (e.g. a Doris fragment that reached us with count()).
        assertEquals("SELECT COUNT(*)", transpile("SELECT count()", read = "doris", write = "doris"))
        assertEquals("SELECT COUNT(*)", transpile("SELECT count()", read = "trino", write = "trino"))
    }
}
