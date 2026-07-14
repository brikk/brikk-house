package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Source-aware generator transforms (SPIKE-source-aware-generator-transforms, Option A:
 * Generator.sourceDialect / isCrossDialectFrom). A semantic-changing rewrite that is correct
 * cross-dialect but would corrupt a NATIVE ClickHouse function must fire ONLY when the source
 * is a known non-ClickHouse dialect. These pins lock both directions and — critically — the
 * pipe-desugaring path (`toStandardSql`, same-dialect) that the corruption bug rode in on.
 *
 * All target values live-verified vs ClickHouse 26.5.1.1 (chdb) + DuckDB 1.5.4:
 * duckdb week 52 == toISOWeek 52 (faithful CH week = 1); duckdb translate('café','é','e') ==
 * translateUTF8 == 'cafe'; lower('İSTANBUL') differs (İ edge) so lower stays divergent.
 */
class ClickhouseSourceAwareTransformsTest {

    private fun toCh(sql: String, read: String): String = transpile(sql, read = read, write = "clickhouse")

    // -- cross-dialect: the rewrite fires -----------------------------------------

    @Test
    fun crossDialect_rewritesFire() {
        assertEquals("SELECT lowerUTF8(x)", toCh("SELECT lower(x)", "duckdb"))
        assertEquals("SELECT upperUTF8(x)", toCh("SELECT upper(x)", "trino"))
        assertEquals("SELECT lowerUTF8(x)", toCh("SELECT lower(x)", "doris"))
        assertEquals("SELECT toISOWeek(d)", toCh("SELECT week(d)", "duckdb"))
        assertEquals("SELECT toISOWeek(d)", toCh("SELECT week(d)", "trino"))
        assertEquals("SELECT translateUTF8(s, f, t)", toCh("SELECT translate(s, f, t)", "duckdb"))
        assertEquals("SELECT translateUTF8(s, f, t)", toCh("SELECT translate(s, f, t)", "doris"))
        assertEquals("SELECT translateUTF8(s, f, t)", toCh("SELECT translate(s, f, t)", "trino"))
    }

    // -- source-specific: Doris week is mode-0 (NOT ISO) -> faithful week ----------

    @Test
    fun dorisWeek_isNotIso_staysFaithful() {
        // Doris week() defaults to mode 0 (Sunday-based) == ClickHouse week default; emitting
        // toISOWeek here would be WRONG. Only ISO-week sources (DuckDB) get toISOWeek. (The
        // Doris parser casts the arg; the point is the faithful `week`, not `toISOWeek`.)
        assertEquals("SELECT week(CAST(d AS Nullable(DATE)))", toCh("SELECT week(d)", "doris"))
    }

    // -- same-dialect: faithful, native functions preserved ------------------------

    @Test
    fun sameDialect_faithful() {
        assertEquals("SELECT lower(x)", toCh("SELECT lower(x)", "clickhouse"))
        assertEquals("SELECT upper(x)", toCh("SELECT upper(x)", "clickhouse"))
        assertEquals("SELECT week(d)", toCh("SELECT week(d)", "clickhouse"))
        assertEquals("SELECT translate(s, f, t)", toCh("SELECT translate(s, f, t)", "clickhouse"))
    }

    // -- source-unknown (direct Expression.sql / transpile with no read) -> faithful

    @Test
    fun sourceUnknown_faithful() {
        // read="" means source unknown; must NOT rewrite (preserves direct-generation behavior).
        assertEquals("SELECT lower(x)", transpile("SELECT lower(x)", write = "clickhouse"))
        assertEquals("SELECT week(d)", transpile("SELECT week(d)", write = "clickhouse"))
    }

    // -- pipe desugaring (toStandardSql, same-dialect) must NOT corrupt -------------

    @Test
    fun pipeDesugar_clickhouseNativeSurvives() {
        // The original bug: FROM t |> SELECT lower(x) silently became lowerUTF8(x). With
        // source-aware generation the structural desugar keeps the native function intact.
        fun std(call: String) = SqlFragment("FROM t |> SELECT $call", "clickhouse").toStandardSql()
        assertEquals("WITH __tmp1 AS (SELECT lower(x) FROM t) SELECT * FROM __tmp1", std("lower(x)"))
        assertEquals("WITH __tmp1 AS (SELECT week(d) FROM t) SELECT * FROM __tmp1", std("week(d)"))
        assertEquals(
            "WITH __tmp1 AS (SELECT translate(s, f, t) FROM t) SELECT * FROM __tmp1",
            std("translate(s, f, t)"),
        )
    }

    // -- source-aware shims: round (half-away) + bin (strip zero-pad) --------------

    @Test
    fun roundBin_shims_duckdbOnly() {
        // DuckDB round is half-away-from-zero, bin has no leading zeros; emit shims (live-
        // verified equal to DuckDB). ClickHouse stays faithful native round/bin. MySQL/Trino
        // round is half-up (differs on negatives) so it is NOT shimmed — faithful passthrough.
        assertEquals(
            "SELECT sign(x) * floor(abs(x) * pow(10, 0) + 0.5) / pow(10, 0)",
            toCh("SELECT round(x)", "duckdb"),
        )
        assertEquals(
            "SELECT sign(x) * floor(abs(x) * pow(10, 2) + 0.5) / pow(10, 2)",
            toCh("SELECT round(x, 2)", "duckdb"),
        )
        assertEquals(
            "SELECT if(x = 0, '0', substring(bin(x), position(bin(x), '1')))",
            toCh("SELECT bin(x)", "duckdb"),
        )
        // faithful CH + non-half-away sources
        assertEquals("SELECT round(x)", toCh("SELECT round(x)", "clickhouse"))
        assertEquals("SELECT bin(x)", toCh("SELECT bin(x)", "clickhouse"))
    }

    // -- GOLDEN: X->X round-trip must be faithful for semantic-sensitive functions --
    // (The spike's stated gap: no X->X identity tests, which let the pipe-desugar bug in.
    //  A same-dialect round-trip must NEVER apply a semantic-changing rename.)

    @Test
    fun golden_sameDialectRoundTripFaithful() {
        val checks = mapOf(
            "clickhouse" to mapOf(
                "SELECT lower(x)" to "SELECT lower(x)",
                "SELECT upper(x)" to "SELECT upper(x)",
                "SELECT length(x)" to "SELECT LENGTH(x)",
                "SELECT char_length(x)" to "SELECT CHAR_LENGTH(x)",
                "SELECT round(x, 2)" to "SELECT round(x, 2)",
                "SELECT bin(x)" to "SELECT bin(x)",
                "SELECT week(d)" to "SELECT week(d)",
                "SELECT translate(a, b, c)" to "SELECT translate(a, b, c)",
            ),
            "duckdb" to mapOf(
                "SELECT round(x, 2)" to "SELECT ROUND(x, 2)",
                "SELECT bin(x)" to "SELECT BIN(x)",
                "SELECT lower(x)" to "SELECT LOWER(x)",
                "SELECT week(d)" to "SELECT WEEK(d)",
                "SELECT translate(a, b, c)" to "SELECT TRANSLATE(a, b, c)",
            ),
        )
        for ((dialect, cases) in checks) {
            for ((input, expected) in cases) {
                assertEquals(expected, transpile(input, read = dialect, write = dialect),
                    "same-dialect round-trip changed semantics for $dialect: $input")
            }
        }
    }

    // -- hazard reconciliation ------------------------------------------------------

    @Test
    fun hazards_reconciled() {
        // now-correct cross-dialect rewrites -> identical
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "week")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("duckdb", "clickhouse", "translate")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("doris", "clickhouse", "translate")?.verdict)
        assertEquals(HazardVerdict.IDENTICAL, HazardRegistry.lookup("trino", "clickhouse", "translate")?.verdict)
        // lower/upper keep the İ/ß full-case-folding divergence (renderer present -> WARNING)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("duckdb", "clickhouse", "lower")?.verdict)
        assertEquals(HazardVerdict.DIVERGENT, HazardRegistry.lookup("trino", "clickhouse", "upper")?.verdict)
    }
}
