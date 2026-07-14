package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression pins for cross-engine function-RENAME fixes: brikk now EMITS the correct
 * ClickHouse spelling for functions that reach the ClickHouse generator from another
 * dialect (DuckDB / Doris / Trino). Before these fixes each call rendered either the
 * uppercase/base node name (e.g. `ARRAY_SORT`, invalid in case-sensitive ClickHouse) or an
 * unmapped Anonymous passthrough (`array_avg`), neither of which ClickHouse accepts.
 *
 * All target names + values are live-probe-verified against ClickHouse 26.5.1.1 (chdb) and
 * DuckDB 1.5.4; see docs/research/CLICKHOUSE-rename-map.md and the per-pair generator-gap
 * reports. The fixes are round-trip-safe: ClickHouse parses its own names to Anonymous /
 * different nodes, so a ClickHouse->ClickHouse render is unchanged (asserted below).
 *
 * Group 1 (this pass): the array family (array_ and list_ prefixes -> ClickHouse array*).
 */
class ClickhouseRenameFixesTest {

    private fun ch(sql: String, read: String): String =
        transpile(sql, read = read, write = "clickhouse")

    // -- DuckDB array/list family --------------------------------------------------

    @Test
    fun duckdb_arrayFamily() {
        assertEquals("SELECT arraySort(a)", ch("SELECT array_sort(a)", "duckdb"))
        assertEquals("SELECT arraySort(a)", ch("SELECT list_sort(a)", "duckdb"))
        assertEquals("SELECT arrayReverseSort(a)", ch("SELECT array_reverse_sort(a)", "duckdb"))
        assertEquals("SELECT arrayReverseSort(a)", ch("SELECT list_reverse_sort(a)", "duckdb"))
        assertEquals("SELECT arrayIntersect(a, b)", ch("SELECT array_intersect(a, b)", "duckdb"))
        assertEquals("SELECT arrayIntersect(a, b)", ch("SELECT list_intersect(a, b)", "duckdb"))
        assertEquals("SELECT arrayUniq(a)", ch("SELECT list_unique(a)", "duckdb"))
        assertEquals("SELECT arrayDotProduct(a, b)", ch("SELECT list_dot_product(a, b)", "duckdb"))
        assertEquals("SELECT hasAll(a, b)", ch("SELECT list_has_all(a, b)", "duckdb"))
        assertEquals("SELECT hasAny(a, b)", ch("SELECT list_has_any(a, b)", "duckdb"))
        // divergent (indexing edges) — name is correct, hazard stays divergent
        assertEquals("SELECT arrayElement(a, i)", ch("SELECT list_element(a, i)", "duckdb"))
        assertEquals("SELECT arrayElement(a, i)", ch("SELECT list_extract(a, i)", "duckdb"))
    }

    // -- Doris array family --------------------------------------------------------

    @Test
    fun doris_arrayFamily() {
        assertEquals("SELECT arraySort(a)", ch("SELECT array_sort(a)", "doris"))
        assertEquals("SELECT arrayCompact(a)", ch("SELECT array_compact(a)", "doris"))
        assertEquals("SELECT arrayExcept(a, b)", ch("SELECT array_except(a, b)", "doris"))
        assertEquals("SELECT arrayIntersect(a, b)", ch("SELECT array_intersect(a, b)", "doris"))
        assertEquals("SELECT arrayAvg(a)", ch("SELECT array_avg(a)", "doris"))
        assertEquals("SELECT arrayCount(a)", ch("SELECT array_count(a)", "doris"))
        assertEquals("SELECT arrayCumSum(a)", ch("SELECT array_cum_sum(a)", "doris"))
        assertEquals("SELECT arrayDifference(a)", ch("SELECT array_difference(a)", "doris"))
        assertEquals("SELECT arrayEnumerate(a)", ch("SELECT array_enumerate(a)", "doris"))
        assertEquals("SELECT arrayEnumerateUniq(a)", ch("SELECT array_enumerate_uniq(a)", "doris"))
        assertEquals("SELECT arrayPopBack(a)", ch("SELECT array_popback(a)", "doris"))
        assertEquals("SELECT arrayPopFront(a)", ch("SELECT array_popfront(a)", "doris"))
        assertEquals("SELECT arrayProduct(a)", ch("SELECT array_product(a)", "doris"))
        assertEquals("SELECT arrayReverseSort(a)", ch("SELECT array_reverse_sort(a)", "doris"))
        // divergent renames — name valid, hazard stays divergent
        assertEquals("SELECT arrayExists(a)", ch("SELECT array_exists(a)", "doris"))
        assertEquals("SELECT arrayShuffle(a)", ch("SELECT array_shuffle(a)", "doris"))
        assertEquals("SELECT arrayUnion(a, b)", ch("SELECT array_union(a, b)", "doris"))
    }

    // -- Group 2: temporal to<Part> (DuckDB + Doris) -------------------------------

    @Test
    fun temporal_toPart() {
        // Base emitted DAY_OF_MONTH / DAY_OF_YEAR (underscore forms ClickHouse rejects).
        assertEquals("SELECT toDayOfMonth(d)", ch("SELECT dayofmonth(d)", "duckdb"))
        assertEquals("SELECT toDayOfYear(d)", ch("SELECT dayofyear(d)", "duckdb"))
        assertEquals("SELECT toMonday(d)", ch("SELECT to_monday(d)", "doris"))
        // weekday: valid name emitted, numbering divergence (Sun=0/6 vs ISO) stays a hazard.
        assertEquals("SELECT toDayOfWeek(d)", ch("SELECT weekday(d)", "duckdb"))
        assertEquals("SELECT toDayOfWeek(d)", ch("SELECT weekday(d)", "doris"))
    }

    // -- Round-trip safety: ClickHouse native names are unchanged ------------------

    @Test
    fun clickhouse_roundTripUnchanged() {
        for (call in listOf(
            "arraySort(a)", "arrayReverseSort(a)", "arrayIntersect(a, b)", "arrayUniq(a)",
            "arrayCompact(a)", "arrayExcept(a, b)", "hasAll(a, b)", "hasAny(a, b)",
            "arrayElement(a, i)", "arrayDotProduct(a, b)",
            "toDayOfMonth(d)", "toDayOfYear(d)", "toDayOfWeek(d)",
        )) {
            assertEquals("SELECT $call", transpile("SELECT $call", read = "clickhouse", write = "clickhouse"))
        }
    }

    // -- Hazard reconciliation: the identical renames stay identical ---------------

    @Test
    fun hazards_identicalRenamesPreserved() {
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "list_sort")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "array_sort")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "array_sort")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "array_avg")?.verdict)
        // divergent renames keep guarding despite the valid emitted name
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "list_element")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("doris", "clickhouse", "array_union")?.verdict)
        // temporal
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "dayofmonth")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "to_monday")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "weekday")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("doris", "clickhouse", "weekday")?.verdict)
    }
}
