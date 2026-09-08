package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.generator.UnsupportedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Customer demo: DuckDB-authored test SQL converted to Doris and Trino.
 * Unchanged conversions retain Python-oracle expectations; Doris corrections and
 * binding refusals are explicit rather than treating parity as a semantic proof.
 */
class DuckdbMigrationExamplesTest {

    private val cases = listOf(
        Triple(
            "SELECT o_orderkey::BIGINT, o_comment::VARCHAR FROM orders",
            "SELECT CAST(o_orderkey AS BIGINT), CAST(o_comment AS STRING) FROM orders",
            "SELECT CAST(o_orderkey AS BIGINT), CAST(o_comment AS VARCHAR) FROM orders",
        ),
        Triple(
            "SELECT * FROM t WHERE ts::DATE = DATE '2024-01-01'",
            "SELECT * FROM t WHERE CAST(ts AS DATE) = CAST('2024-01-01' AS DATE)",
            "SELECT * FROM t WHERE CAST(ts AS DATE) = CAST('2024-01-01' AS DATE)",
        ),
        Triple(
            "SELECT COUNT(*) FILTER (WHERE id % 2 = 0), SUM(x) FILTER (WHERE y > 0) FROM t",
            // brikk extension (NOT sqlglot parity — see docs/brikk-extensions.md): sqlglot
            // passes FILTER through for Doris, which is invalid Doris SQL (no FILTER clause).
            // brikk rewrites to the result-identical CASE form.
            "SELECT COUNT(CASE WHEN id % 2 = 0 THEN 1 END), SUM(CASE WHEN y > 0 THEN x END) FROM t",
            "SELECT COUNT(*) FILTER(WHERE id % 2 = 0), SUM(x) FILTER(WHERE y > 0) FROM t",
        ),
        Triple(
            "SELECT date_trunc('month', o_orderdate) FROM orders",
            "SELECT DATE_TRUNC(o_orderdate, 'MONTH') FROM orders",
            "SELECT DATE_TRUNC('MONTH', o_orderdate) FROM orders",
        ),
    )

    @Test
    fun duckdbToDorisAndTrino() {
        for ((duckdb, doris, trino) in cases) {
            val ourDoris = transpile(duckdb, read = "duckdb", write = "doris")
            val ourTrino = transpile(duckdb, read = "duckdb", write = "trino")
            println("-- duckdb: $duckdb")
            println("   doris : $ourDoris")
            println("   trino : $ourTrino")
            println()
            assertEquals(doris, ourDoris, "doris mismatch for: $duckdb")
            assertEquals(trino, ourTrino, "trino mismatch for: $duckdb")
        }
    }

    @Test
    fun anUnknownInputColumnMustNotBeAssumedToNameTheWindowAlias() {
        val source = "SELECT *, row_number() OVER (PARTITION BY a ORDER BY b) rn FROM t QUALIFY rn = 1"
        // If t already has rn, DuckDB filters that input column. Doris requires
        // a window-dependent predicate, so this cross-dialect binding needs schema.
        assertFailsWith<UnsupportedError> { transpile(source, read = "duckdb", write = "doris") }
        assertEquals(
            "SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY a ORDER BY b) AS rn FROM t) AS _t WHERE rn = 1",
            transpile(source, read = "duckdb", write = "trino"),
        )
    }
}
