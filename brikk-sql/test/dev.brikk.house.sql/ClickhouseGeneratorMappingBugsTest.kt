package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression pins for the ClickHouse generator function-mapping fixes filed in
 * docs/research/BUGS-clickhouse-generator-mappings-2026-07-13.md (live-probe-verified
 * against ClickHouse 26.5.1.1 via chdb). Same shape as DorisGeneratorMappingBugsTest:
 * each row asserts the corrected transpile-string rendering, and the reconciled cases
 * also assert the hazard verdict flipped divergent -> identical (the generator now emits
 * the result-identical ClickHouse function).
 *
 * There is no ClickhouseVerifier wired in brikk-sql-verify (the probe agent used chdb
 * externally), so semantic authority here is the BUGS file + the live registry dump
 * vendor/data/clickhouse-functions-26.5.1.1.tsv for name existence.
 */
class ClickhouseGeneratorMappingBugsTest {

    private fun ch(sql: String, read: String): String =
        transpile(sql, read = read, write = "clickhouse")

    // -- P1 ships-wrong: fixed at the generator source ------------------------------

    @Test
    fun p1_lowerUpper_utf8() {
        // ClickHouse LOWER/UPPER are ASCII-only; lowerUTF8/upperUTF8 fold multibyte.
        assertEquals("SELECT lowerUTF8(x)", ch("SELECT lower(x)", "duckdb"))
        assertEquals("SELECT upperUTF8(x)", ch("SELECT upper(x)", "trino"))
        // Residual İ/ß full-case-folding edge divergence remains -> hazard stays divergent.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "lower")?.verdict)
    }

    @Test
    fun p1_log_base10_notNaturalLog() {
        // DuckDB single-arg log(x) is base-10; ClickHouse LOG(x) is natural log. log10 fixes it.
        assertEquals("SELECT log10(x)", ch("SELECT log(x)", "duckdb"))
        // Reconciled: duckdb log -> clickhouse log10 is now identical.
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "log")?.verdict)
    }

    @Test
    fun p1_regexpReplace_firstVsAll() {
        // DuckDB regexp_replace (no 'g') replaces only the FIRST match -> replaceRegexpOne.
        assertEquals("SELECT replaceRegexpOne(s, p, r)", ch("SELECT regexp_replace(s, p, r)", "duckdb"))
        // Trino regexp_replace replaces ALL -> replaceRegexpAll (matches ClickHouse alias).
        assertEquals("SELECT replaceRegexpAll(s, p, r)", ch("SELECT regexp_replace(s, p, r)", "trino"))
        // Reconciled to CONDITIONALLY_EQUIVALENT (not identical): the common first-match case
        // is now correct, but a residual empty-pattern divergence (CH no-op vs DuckDB inserts
        // at every position) must stay surfaced (policy #2 -> WARNING), not silently cleared.
        assertEquals(
            HazardVerdict.CONDITIONALLY_EQUIVALENT,
            HazardRegistry.lookup("duckdb", "clickhouse", "regexp_replace")?.verdict,
        )
    }

    @Test
    fun p1_week_isoWeek() {
        // DuckDB / Trino week are ISO-8601; ClickHouse WEEK/toWeek default is Sunday-based.
        assertEquals("SELECT toISOWeek(d)", ch("SELECT week(d)", "duckdb"))
        assertEquals("SELECT toISOWeek(d)", ch("SELECT week(d)", "trino"))
        // Reconciled both directions: -> toISOWeek is now identical.
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "week")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "week")?.verdict)
    }

    // -- P2 invalid-name: emitted name did not exist in ClickHouse ------------------

    @Test
    fun p2_dayofweek_validName() {
        // The emitted DAY_OF_WEEK does not exist in ClickHouse; toDayOfWeek is valid.
        assertEquals("SELECT toDayOfWeek(d)", ch("SELECT dayofweek(d)", "duckdb"))
        // Name fixed, but a numbering divergence (DuckDB Sunday=0 vs ClickHouse ISO
        // Monday=1..Sunday=7) remains -> hazard stays divergent.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "dayofweek")?.verdict)
    }

    @Test
    fun p2_log10_validSingleArgName() {
        // duckdb log10(x) used to emit the invalid 2-arg LOG(10, x); now log10(x).
        assertEquals("SELECT log10(x)", ch("SELECT log10(x)", "duckdb"))
        assertEquals("SELECT log2(x)", ch("SELECT log2(x)", "duckdb"))
    }

    @Test
    fun p2_toUnixtime_validName() {
        // Trino to_unixtime leaked the internal node name TIME_TO_UNIX; toUnixTimestamp is real.
        assertEquals("SELECT toUnixTimestamp(t)", ch("SELECT to_unixtime(t)", "trino"))
        // A fractional-seconds divergence remains -> hazard stays conditionally-equivalent.
        assertEquals(
            HazardVerdict.CONDITIONALLY_EQUIVALENT,
            HazardRegistry.lookup("trino", "clickhouse", "to_unixtime")?.verdict,
        )
    }

    // -- P3 shape/operator: emitted SQL was rejected by ClickHouse -------------------

    @Test
    fun p3_xor_bitXor() {
        // DuckDB xor is bitwise; the base emitted `a ^ b` (no ClickHouse operator).
        assertEquals("SELECT bitXor(a, b)", ch("SELECT xor(a, b)", "duckdb"))
        // Reconciled: duckdb xor -> bitXor is now identical.
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "xor")?.verdict)
    }

    @Test
    fun p1_millisecond_compound() {
        // DuckDB/Trino millisecond(t) = seconds-within-minute*1000 + ms; ClickHouse
        // toMillisecond is the sub-second component only. Emit the compound. Live-
        // differential-verified vs ClickHouse 26.5.1.1 + DuckDB 1.5.4.
        assertEquals("SELECT (toSecond(t) * 1000 + toMillisecond(t))", ch("SELECT millisecond(t)", "duckdb"))
        // Reconciled -> identical (verified). `millisecond` is not a ClickHouse function
        // name, so the rewrite never fires on ClickHouse->ClickHouse.
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "millisecond")?.verdict)
    }

    // -- Deferred (documented in the BUGS file) -------------------------------------

    @Test
    fun deferred_stayGuarded() {
        // round / bin (P1): verified half-away / strip-leading-zeros shims EXIST (recorded
        // in the BUGS file), but `round` and `bin` are ClickHouse-NATIVE names, so a
        // source-unaware generator rewrite would regress ClickHouse->ClickHouse numeric/
        // binary results -- deferred pending that policy call. age (return-type mismatch:
        // DuckDB interval vs ClickHouse scalar) and to_days (parser-level source ambiguity)
        // are genuinely unmappable here. All stay divergent-and-guarded.
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "round")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "bin")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "age")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "to_days")?.verdict)
    }
}
