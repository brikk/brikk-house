package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.certify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression pins for the generator function-mapping fixes filed in
 * docs/research/BUGS-doris-generator-mappings-2026-07-13.md. Each case asserts both the
 * corrected transpile-string rendering AND the post-fix certify behavior (the confident-
 * but-wrong P1 rows used to ship `ok=true` with WRONG SQL because the divergent hazards
 * were masked by the dedicated-renderer mitigation rule — after the fix the SQL is
 * correct and certify still returns ok=true, which is now honest).
 *
 * Grammar acceptance of these renderings against the real Doris FE parser is pinned in
 * brikk-sql-verify SqlVerifierTest (dorisAcceptsBrikkGeneratorMappingFixes).
 */
class DorisGeneratorMappingBugsTest {

    private fun doris(sql: String, read: String): String = transpile(sql, read = read, write = "doris")

    private fun certifyOk(sql: String, read: String): Boolean =
        SqlFragment(sql, read).certify("doris").ok

    // -- P1: confident-but-wrong (used to ship wrong SQL with ok=true) ---------------

    @Test
    fun p1_listHasAny_arraysOverlap() {
        assertEquals("SELECT ARRAYS_OVERLAP(a, b)", doris("SELECT list_has_any(a, b)", "duckdb"))
        assertTrue(certifyOk("SELECT list_has_any(a, b)", "duckdb"))
    }

    @Test
    fun p1_epochMs_fromMillisecond() {
        assertEquals("SELECT FROM_MILLISECOND(ms)", doris("SELECT epoch_ms(ms)", "duckdb"))
        assertTrue(certifyOk("SELECT epoch_ms(ms)", "duckdb"))
    }

    @Test
    fun p1_stringSplitRegex_splitByRegexp() {
        assertEquals("SELECT SPLIT_BY_REGEXP(s, p)", doris("SELECT string_split_regex(s, p)", "duckdb"))
        assertTrue(certifyOk("SELECT string_split_regex(s, p)", "duckdb"))
        // literal split must stay SPLIT_BY_STRING (not regex).
        assertEquals("SELECT SPLIT_BY_STRING(s, p)", doris("SELECT string_split(s, p)", "duckdb"))
    }

    @Test
    fun p1_regexpSplit_trino_splitByRegexp() {
        assertEquals("SELECT SPLIT_BY_REGEXP(s, p)", doris("SELECT regexp_split(s, p)", "trino"))
        assertTrue(certifyOk("SELECT regexp_split(s, p)", "trino"))
    }

    @Test
    fun p1_structPack_namedStruct() {
        assertEquals(
            "SELECT NAMED_STRUCT('a', 1, 'b', 'x')",
            doris("SELECT struct_pack(a := 1, b := 'x')", "duckdb"),
        )
        assertTrue(certifyOk("SELECT struct_pack(a := 1, b := 'x')", "duckdb"))
    }

    @Test
    fun p1_jsonArrayContains_jsonContains() {
        assertEquals("SELECT JSON_CONTAINS(j, v)", doris("SELECT json_array_contains(j, v)", "trino"))
        assertTrue(certifyOk("SELECT json_array_contains(j, v)", "trino"))
    }

    @Test
    fun p1_jsonExtractScalar_jsonUnquote() {
        assertEquals(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(j, p))",
            doris("SELECT json_extract_scalar(j, p)", "trino"),
        )
        assertTrue(certifyOk("SELECT json_extract_scalar(j, p)", "trino"))
    }

    // -- P2: catalog staleness (name not on live FE) ---------------------------------

    @Test
    fun p2_arrayLength_arraySize() {
        assertEquals("SELECT ARRAY_SIZE(a)", doris("SELECT array_length(a)", "duckdb"))
        assertTrue(certifyOk("SELECT array_length(a)", "duckdb"))
    }

    // -- P3: fail-loud (were refusing; now render correctly) -------------------------

    @Test
    fun p3_strftime_plainCast_noInternalNode() {
        val out = doris("SELECT strftime(ts, '%Y')", "duckdb")
        assertEquals("SELECT DATE_FORMAT(ts, '%Y')", out)
        assertTrue("TS_OR_DS_TO_TIMESTAMP" !in out)
        // The false UNMAPPABLE refusal from the round-trip TsOrDsToTimestamp is gone.
        assertTrue(certifyOk("SELECT strftime(ts, '%Y')", "duckdb"))
    }

    @Test
    fun p3_fromIso8601TimestampNanos_datetime6() {
        // Lossy: Doris DATETIME tops out at microseconds; nanoseconds are unrepresentable.
        assertEquals(
            "SELECT CAST(s AS DATETIME(6))",
            doris("SELECT from_iso8601_timestamp_nanos(s)", "trino"),
        )
        assertTrue(certifyOk("SELECT from_iso8601_timestamp_nanos(s)", "trino"))
    }

    @Test
    fun p3_mapExtract_stillRefuses_documentedDeferral() {
        // DEFERRED: duckdb map_extract returns a LIST [v]; Doris element_at returns a
        // scalar v — the shape differs, and map_extract reaches us as an unresolved
        // Anonymous (no canonical node), so the safe refusal is kept.
        assertTrue(!certifyOk("SELECT map_extract(m, k)", "duckdb"))
    }

    // -- Enhancements: previously-refused, now mapped --------------------------------

    @Test
    fun enh_trivialRenames() {
        assertEquals("SELECT GCD(a, b)", doris("SELECT greatest_common_divisor(a, b)", "duckdb"))
        assertEquals("SELECT LCM(a, b)", doris("SELECT least_common_multiple(a, b)", "duckdb"))
        assertEquals("SELECT ARRAY_POSITION(a, b)", doris("SELECT list_position(a, b)", "duckdb"))
        assertEquals("SELECT ENDS_WITH(s, x)", doris("SELECT suffix(s, x)", "duckdb"))
        assertEquals("SELECT NOW()", doris("SELECT get_current_timestamp()", "duckdb"))
        assertEquals("SELECT ST_ASBINARY(g)", doris("SELECT st_aswkb(g)", "duckdb"))
        assertEquals("SELECT ISNAN(x)", doris("SELECT is_nan(x)", "trino"))
        for (case in listOf(
            "SELECT greatest_common_divisor(a, b)" to "duckdb",
            "SELECT least_common_multiple(a, b)" to "duckdb",
            "SELECT list_position(a, b)" to "duckdb",
            "SELECT suffix(s, x)" to "duckdb",
            "SELECT get_current_timestamp()" to "duckdb",
            "SELECT is_nan(x)" to "trino",
        )) {
            assertTrue(certifyOk(case.first, case.second), "certify not ok: ${case.first}")
        }
    }

    @Test
    fun enh_listSlice_endToLengthConversion() {
        // list_slice(a, 1, 3) -> array_slice(a, 1, length=3) (end 3 - begin 1 + 1 = 3).
        assertEquals("SELECT ARRAY_SLICE(a, 1, 3)", doris("SELECT list_slice(a, 1, 3)", "duckdb"))
        // list_slice(a, 2, 4) -> array_slice(a, 2, length=3).
        assertEquals("SELECT ARRAY_SLICE(a, 2, 3)", doris("SELECT list_slice(a, 2, 4)", "duckdb"))
        // begin only -> offset-to-tail form.
        assertEquals("SELECT ARRAY_SLICE(a, 2)", doris("SELECT list_slice(a, 2)", "duckdb"))
        assertTrue(certifyOk("SELECT list_slice(a, 1, 3)", "duckdb"))
    }
}
