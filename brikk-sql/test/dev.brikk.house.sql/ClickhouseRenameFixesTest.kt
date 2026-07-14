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

    // -- Group 3: math nodes + snake/camel + ip/url/hash ---------------------------

    @Test
    fun math_lowercaseNames() {
        // Base emitted ACOSH/CBRT/... (uppercase); ClickHouse is case-sensitive.
        for ((src) in listOf("duckdb", "doris", "trino").map { it to it }) {
            assertEquals("SELECT acosh(x)", ch("SELECT acosh(x)", src))
            assertEquals("SELECT cbrt(x)", ch("SELECT cbrt(x)", src))
            assertEquals("SELECT sinh(x)", ch("SELECT sinh(x)", src))
            assertEquals("SELECT cosh(x)", ch("SELECT cosh(x)", src))
        }
        assertEquals("SELECT asinh(x)", ch("SELECT asinh(x)", "duckdb"))
    }

    @Test
    fun duckdb_mathMisc() {
        assertEquals("SELECT bitCount(x)", ch("SELECT bit_count(x)", "duckdb"))
        assertEquals("SELECT tgamma(x)", ch("SELECT gamma(x)", "duckdb"))
        assertEquals("SELECT gcd(a, b)", ch("SELECT greatest_common_divisor(a, b)", "duckdb"))
        assertEquals("SELECT isFinite(x)", ch("SELECT isfinite(x)", "duckdb"))
        assertEquals("SELECT jaroSimilarity(a, b)", ch("SELECT jaro_similarity(a, b)", "duckdb"))
        assertEquals("SELECT lcm(a, b)", ch("SELECT least_common_multiple(a, b)", "duckdb"))
    }

    @Test
    fun doris_snakeCamelIpUrlHash() {
        // bit ops (BitwiseCount node + Anonymous)
        assertEquals("SELECT bitCount(x)", ch("SELECT bit_count(x)", "doris"))
        assertEquals("SELECT bitShiftLeft(a, b)", ch("SELECT bit_shift_left(a, b)", "doris"))
        assertEquals("SELECT bitShiftRight(a, b)", ch("SELECT bit_shift_right(a, b)", "doris"))
        assertEquals("SELECT bitTest(a, b)", ch("SELECT bit_test(a, b)", "doris"))
        // string / url
        assertEquals("SELECT countSubstrings(s, p)", ch("SELECT count_substrings(s, p)", "doris"))
        assertEquals("SELECT splitByRegexp(s, p)", ch("SELECT split_by_regexp(s, p)", "doris"))
        assertEquals("SELECT cutToFirstSignificantSubdomain(s)", ch("SELECT cut_to_first_significant_subdomain(s)", "doris"))
        assertEquals("SELECT domainWithoutWWW(s)", ch("SELECT domain_without_www(s)", "doris"))
        assertEquals("SELECT extractURLParameter(s, p)", ch("SELECT extract_url_parameter(s, p)", "doris"))
        assertEquals("SELECT firstSignificantSubdomain(s)", ch("SELECT first_significant_subdomain(s)", "doris"))
        assertEquals("SELECT topLevelDomain(s)", ch("SELECT top_level_domain(s)", "doris"))
        // ip
        assertEquals("SELECT IPv4NumToString(x)", ch("SELECT ipv4_num_to_string(x)", "doris"))
        assertEquals("SELECT IPv4StringToNumOrDefault(x)", ch("SELECT ipv4_string_to_num_or_default(x)", "doris"))
        assertEquals("SELECT IPv6StringToNumOrDefault(x)", ch("SELECT ipv6_string_to_num_or_default(x)", "doris"))
        assertEquals("SELECT isIPv4String(x)", ch("SELECT is_ipv4_string(x)", "doris"))
        assertEquals("SELECT isIPv6String(x)", ch("SELECT is_ipv6_string(x)", "doris"))
        assertEquals("SELECT toIPv4OrDefault(x)", ch("SELECT to_ipv4_or_default(x)", "doris"))
        assertEquals("SELECT toIPv6OrDefault(x)", ch("SELECT to_ipv6_or_default(x)", "doris"))
        // distance / hash / rounding
        assertEquals("SELECT L1Distance(a, b)", ch("SELECT l1_distance(a, b)", "doris"))
        assertEquals("SELECT roundBankers(x)", ch("SELECT round_bankers(x)", "doris"))
        assertEquals("SELECT xxHash32(x)", ch("SELECT xxhash_32(x)", "doris"))
        assertEquals("SELECT xxHash64(x)", ch("SELECT xxhash_64(x)", "doris"))
    }

    // -- Group 4a: trino -> clickhouse forward completion --------------------------

    @Test
    fun trino_forwardRenames() {
        assertEquals("SELECT bitShiftLeft(a, b)", ch("SELECT bitwise_left_shift(a, b)", "trino"))
        assertEquals("SELECT bitShiftRight(a, b)", ch("SELECT bitwise_right_shift(a, b)", "trino"))
        assertEquals("SELECT toDayOfMonth(d)", ch("SELECT day_of_month(d)", "trino"))
        assertEquals("SELECT toDayOfWeek(d)", ch("SELECT day_of_week(d)", "trino"))
        assertEquals("SELECT toDayOfYear(d)", ch("SELECT day_of_year(d)", "trino"))
        assertEquals("SELECT dotProduct(a, b)", ch("SELECT dot_product(a, b)", "trino"))
        assertEquals("SELECT isFinite(x)", ch("SELECT is_finite(x)", "trino"))
        assertEquals("SELECT isInfinite(x)", ch("SELECT is_infinite(x)", "trino"))
        assertEquals("SELECT xxHash64(x)", ch("SELECT xxhash64(x)", "trino"))
        // math shared nodes also cover trino
        assertEquals("SELECT cbrt(x)", ch("SELECT cbrt(x)", "trino"))
        assertEquals("SELECT sinh(x)", ch("SELECT sinh(x)", "trino"))
    }

    // -- Deferred (documented): source-aware / arg-order / wrong-type blocked ------

    @Test
    fun deferred_notRewritten() {
        // translate reaches the shared Translate node which ClickHouse itself parses to,
        // so a node rewrite would corrupt native ClickHouse translate — see the hazard notes.
        // (No safe generator rename this pass.)
        assertEquals("SELECT translateUTF8(x, f, t)",
            transpile("SELECT translateUTF8(x, f, t)", read = "clickhouse", write = "clickhouse"))
    }

    // -- Round-trip safety: ClickHouse native names are unchanged ------------------

    @Test
    fun clickhouse_roundTripUnchanged() {
        for (call in listOf(
            "arraySort(a)", "arrayReverseSort(a)", "arrayIntersect(a, b)", "arrayUniq(a)",
            "arrayCompact(a)", "arrayExcept(a, b)", "hasAll(a, b)", "hasAny(a, b)",
            "arrayElement(a, i)", "arrayDotProduct(a, b)",
            "toDayOfMonth(d)", "toDayOfYear(d)", "toDayOfWeek(d)",
            "acosh(x)", "cbrt(x)", "sinh(x)", "cosh(x)", "asinh(x)", "bitCount(x)",
            "tgamma(x)", "gcd(a, b)", "lcm(a, b)", "isFinite(x)", "L1Distance(a, b)",
            "roundBankers(x)", "xxHash64(x)", "IPv4NumToString(x)", "translate(s, f, t)",
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
        // chunk 3 identical renames stay identical
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "bit_count")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "gamma")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "l1_distance")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "bit_shift_left")?.verdict)
        // trino
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "bitwise_left_shift")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "day_of_week")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "dot_product")?.verdict)
    }
}
