package dev.brikk.house.sql.shape

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate API requested by the Doris corpus agent: throw-vs-passthrough detection for
 * Class 2 (semantic) / Class 3 (capability) cases. Cases are the agent's own examples;
 * transpile outputs verified against the Python oracle where translation occurs.
 */
class TranspileGateApiTest {

    // --- Class 3: no target function -> unmappableFunctions reports them ---

    @Test
    fun class3FunctionHolesAreReported() {
        assertEquals(
            listOf("READ_PARQUET"),
            SqlFragment("SELECT * FROM read_parquet('f.parquet')", "duckdb")
                .unmappableFunctions("doris"),
        )
        assertEquals(
            listOf("DUCKDB_TABLES"),
            SqlFragment("SELECT * FROM duckdb_tables()", "duckdb").unmappableFunctions("doris"),
        )
        assertEquals(
            listOf("DUCKLAKE_SNAPSHOTS"),
            SqlFragment("SELECT * FROM ducklake_snapshots('lake')", "duckdb")
                .unmappableFunctions("doris"),
        )
    }

    @Test
    fun translatedFunctionsAreNotHoles() {
        // list(x ORDER BY x) -> COLLECT_LIST(...) which Doris registers.
        assertEquals(
            emptyList(),
            SqlFragment("SELECT list(o_orderkey ORDER BY o_orderkey) FROM orders", "duckdb")
                .unmappableFunctions("doris"),
        )
        assertEquals(
            emptyList(),
            SqlFragment("SELECT array_agg(o_orderkey) FROM orders", "duckdb")
                .unmappableFunctions("doris"),
        )
    }

    // --- Class 2 flagged-but-emitted: unsupportedMessages channel ---

    @Test
    fun unsupportedTranslationsAreFlaggedNotSilent() {
        // brikk extension (registry entry 7): arrays are first-class for Doris, so the flag
        // is now the accurate scalar-UNNEST one, not MySQL's blanket array rejection.
        val result = SqlFragment("SELECT unnest([1, 2, 3])", "duckdb").transpileTo("doris")
        assertTrue(result.unsupportedMessages.isNotEmpty(), "expected unsupported flags")
        assertTrue(result.unsupportedMessages.any { "EXPLODE is only valid in LATERAL VIEW" in it })
    }

    @Test
    fun cleanTranspileHasNoMessages() {
        val result = SqlFragment(
            "SELECT list(o_orderkey ORDER BY o_orderkey) FROM orders",
            "duckdb",
        ).transpileTo("doris")
        assertEquals(emptyList(), result.unsupportedMessages)
        // Oracle-verified output (ordered agg carries through + null-ordering compensation)
        assertEquals(
            "SELECT COLLECT_LIST(o_orderkey ORDER BY CASE WHEN o_orderkey IS NULL THEN 1 ELSE 0 END, o_orderkey) FROM orders",
            result.sql,
        )
    }

    // --- Statement-shaped holes: root kind ---

    @Test
    fun pragmaIsRawPassthroughStatement() {
        val frag = SqlFragment("PRAGMA database_size", "duckdb")
        assertEquals("Pragma", frag.rootKind)
        assertTrue(frag.isRawPassthroughStatement)
        assertTrue(frag.transpileTo("doris").isRawPassthroughStatement)
    }

    @Test
    fun selectIsNotRawPassthrough() {
        val frag = SqlFragment("SELECT * FROM information_schema.columns", "duckdb")
        assertEquals("Select", frag.rootKind)
        assertTrue(!frag.isRawPassthroughStatement)
        // The information_schema case is gate-clean by every signal — it's exactly the
        // "explicit skip-list" residue: content divergence is not detectable from SQL.
        assertEquals(emptyList(), frag.unmappableFunctions("doris"))
        assertEquals(emptyList(), frag.transpileTo("doris").unsupportedMessages)
    }

    @Test
    fun unmappableRequiresCatalog() {
        val frag = SqlFragment("SELECT * FROM read_parquet('f.parquet')", "duckdb")
        val err = runCatching { frag.unmappableFunctions("mysql") }.exceptionOrNull()
        assertTrue(err is ShapeError, "expected ShapeError for catalog-less target")
    }
}
