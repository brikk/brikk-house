package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-engine function-semantics fixes, evidence-gated by
 * brikk-sql/testResources/semantics/trino-duckdb-hazards.json (live-probe-verified
 * verdicts) and brikk-sql/testResources/semantics/gap-report.json (catalog gaps).
 *
 * Tier 1 — the two hazard contradictions (GREATEST/LEAST NULL algebra and
 * REGEXP_REPLACE replace-first vs replace-all), both directions.
 * Tier 2 — the hard UnsupportedError paths stay flagged (or are now clean where a
 * real equivalent exists).
 * Tier 3 — catalog-backed renames for renders that previously emitted names absent
 * from the target catalog (docs/brikk-extensions.md entries 11-15;
 * docs/research/function-gap-report.md "Triage status").
 *
 * Grammar acceptance of every new rendering is pinned against the real engine
 * parsers in brikk-sql-verify (SqlVerifierTest.*FunctionSemantics*).
 */
class FunctionSemanticsTest {

    private fun messagesOf(sql: String, read: String, write: String): List<String> =
        SqlFragment(sql, read).transpileTo(write).unsupportedMessages

    // ------------------------------------------------------------------
    // Tier 1a: GREATEST/LEAST — Trino NULL-propagates, DuckDB skips NULLs
    // (hazards verdict: divergent). Port of sqlglot's duckdb _greatest_least_sql.
    // ------------------------------------------------------------------

    @Test
    fun trinoGreatestToDuckdbPropagatesNulls() {
        assertEquals(
            "SELECT CASE WHEN a IS NULL OR b IS NULL OR c IS NULL THEN NULL ELSE GREATEST(a, b, c) END",
            transpile("SELECT GREATEST(a, b, c)", read = "trino", write = "duckdb"),
        )
        assertEquals(
            "SELECT CASE WHEN a IS NULL OR b IS NULL THEN NULL ELSE LEAST(a, b) END",
            transpile("SELECT LEAST(a, b)", read = "trino", write = "duckdb"),
        )
    }

    @Test
    fun mysqlFamilyGreatestAlsoPropagatesNulls() {
        // MySQL.LEAST_GREATEST_IGNORES_NULLS = False covers MySQL and Doris.
        assertEquals(
            "SELECT CASE WHEN a IS NULL THEN NULL ELSE LEAST(a) END",
            transpile("SELECT LEAST(a)", read = "doris", write = "duckdb"),
        )
        assertEquals(
            "SELECT CASE WHEN a IS NULL OR b IS NULL THEN NULL ELSE GREATEST(a, b) END",
            transpile("SELECT GREATEST(a, b)", read = "mysql", write = "duckdb"),
        )
    }

    @Test
    fun nullSkippingGreatestKeepsNativeDuckdbCall() {
        // duckdb/postgres GREATEST already skips NULLs — no wrap.
        assertEquals(
            "SELECT GREATEST(1.0, 2.5, NULL, 3.7)",
            transpile("SELECT GREATEST(1.0, 2.5, NULL, 3.7)", read = "duckdb", write = "duckdb"),
        )
        assertEquals(
            "SELECT GREATEST(a, b)",
            transpile("SELECT GREATEST(a, b)", read = "postgres", write = "duckdb"),
        )
    }

    @Test
    fun duckdbGreatestToTrinoIsFlaggedNotSilent() {
        // brikk extension 11: reverse direction has no verified rewrite — the bare
        // (Python-oracle) rendering is kept but flagged.
        val result = SqlFragment("SELECT GREATEST(a, b)", "duckdb").transpileTo("trino")
        assertEquals("SELECT GREATEST(a, b)", result.sql)
        assertTrue(result.unsupportedMessages.any { "NULL-skipping" in it }, "expected flag: ${result.unsupportedMessages}")

        // Single-argument form has no NULL-algebra divergence — clean.
        val single = SqlFragment("SELECT GREATEST(a)", "duckdb").transpileTo("trino")
        assertEquals(emptyList(), single.unsupportedMessages)

        // Trino-parsed GREATEST (ignore_nulls=false) round-trips clean.
        val trino = SqlFragment("SELECT GREATEST(a, b)", "trino").transpileTo("trino")
        assertEquals("SELECT GREATEST(a, b)", trino.sql)
        assertEquals(emptyList(), trino.unsupportedMessages)
    }

    // ------------------------------------------------------------------
    // Tier 1b: REGEXP_REPLACE — Trino replaces ALL, DuckDB replaces FIRST unless 'g'
    // (hazards verdict: divergent). Port of sqlglot's duckdb regexpreplace_sql.
    // ------------------------------------------------------------------

    @Test
    fun trinoRegexpReplaceToDuckdbForcesGlobalFlag() {
        assertEquals(
            "SELECT REGEXP_REPLACE(x, 'a', 'b', 'g')",
            transpile("SELECT REGEXP_REPLACE(x, 'a', 'b')", read = "trino", write = "duckdb"),
        )
        // Trino 2-arg form removes all matches.
        assertEquals(
            "SELECT REGEXP_REPLACE(x, 'a', '', 'g')",
            transpile("SELECT REGEXP_REPLACE(x, 'a')", read = "trino", write = "duckdb"),
        )
    }

    @Test
    fun duckdbRegexpReplaceRoundTripsWithoutForcedFlag() {
        // duckdb-parsed calls carry single_replace=true: replace-first is preserved
        // byte-identically, and explicit modifiers survive.
        assertEquals(
            "SELECT REGEXP_REPLACE(x, 'a', 'b')",
            transpile("SELECT REGEXP_REPLACE(x, 'a', 'b')", read = "duckdb", write = "duckdb"),
        )
        assertEquals(
            "SELECT REGEXP_REPLACE(x, 'a', 'b', 'ims')",
            transpile("SELECT REGEXP_REPLACE(x, 'a', 'b', 'ims')", read = "duckdb", write = "duckdb"),
        )
    }

    @Test
    fun duckdbReplaceFirstToTrinoIsFlaggedNotSilent() {
        // brikk extension 12: no replace-first form exists in Trino.
        val result = SqlFragment("SELECT REGEXP_REPLACE(x, 'a', 'b')", "duckdb").transpileTo("trino")
        assertEquals("SELECT REGEXP_REPLACE(x, 'a', 'b')", result.sql)
        assertTrue(result.unsupportedMessages.any { "first" in it }, "expected flag: ${result.unsupportedMessages}")

        // 'g' == Trino's default: clean 3-arg form, modifier dropped.
        val global = SqlFragment("SELECT REGEXP_REPLACE(x, 'a', 'b', 'g')", "duckdb").transpileTo("trino")
        assertEquals("SELECT REGEXP_REPLACE(x, 'a', 'b')", global.sql)
        assertEquals(emptyList(), global.unsupportedMessages)

        // Other modifiers have no Trino equivalent: grammar-legal output + flag.
        val flags = SqlFragment("SELECT REGEXP_REPLACE(x, 'a', 'b', 'gi')", "duckdb").transpileTo("trino")
        assertEquals("SELECT REGEXP_REPLACE(x, 'a', 'b')", flags.sql)
        assertTrue(flags.unsupportedMessages.any { "'i'" in it }, "expected flag: ${flags.unsupportedMessages}")
    }

    @Test
    fun trinoRegexpReplaceIdentityStaysClean() {
        val result = SqlFragment("SELECT REGEXP_REPLACE(x, 'a', 'b')", "trino").transpileTo("trino")
        assertEquals("SELECT REGEXP_REPLACE(x, 'a', 'b')", result.sql)
        assertEquals(emptyList(), result.unsupportedMessages)
    }

    // ------------------------------------------------------------------
    // Tier 2: the hard UnsupportedError paths — fixed where a real equivalent
    // exists, otherwise still flagged with the accurate message.
    // ------------------------------------------------------------------

    @Test
    fun duckdbListValueToDorisRendersFirstClassArray() {
        // gap-report duckdb->doris render error resolved by extension 7 (first-class
        // Doris arrays): LIST_VALUE routes into the unflagged array rendering.
        val result = SqlFragment("SELECT LIST_VALUE(1, 2, 3)", "duckdb").transpileTo("doris")
        assertEquals("SELECT ARRAY(1, 2, 3)", result.sql)
        assertEquals(emptyList(), result.unsupportedMessages)
    }

    @Test
    fun unfixableFunctionsStayAccuratelyFlagged() {
        // doris->duckdb parse_url: DuckDB core has no URL accessors (the netquack
        // extension was probe-rejected for NULL-vs-'' divergence — hazards doc).
        assertTrue(
            messagesOf("SELECT PARSE_URL(u, 'HOST')", "doris", "duckdb")
                .any { "PARSE_URL" in it },
        )
        // doris->duckdb compress: no DuckDB equivalent.
        assertTrue(
            messagesOf("SELECT COMPRESS(x)", "doris", "duckdb").any { "COMPRESS" in it },
        )
        // trino->duckdb sha512: DuckDB core stops at SHA-256; the community crypto
        // extension was probe-rejected (hazards: availability + hex-VARCHAR shape).
        assertTrue(
            messagesOf("SELECT SHA512(x)", "trino", "duckdb").any { "SHA256" in it },
        )
        // trino/doris->duckdb soundex: only via the splink_udfs community extension
        // (hazards: conditionally-equivalent, not core).
        assertTrue(
            messagesOf("SELECT SOUNDEX(x)", "trino", "duckdb").any { "SOUNDEX" in it },
        )
        assertTrue(
            messagesOf("SELECT SOUNDEX(x)", "doris", "duckdb").any { "SOUNDEX" in it },
        )
    }

    // ------------------------------------------------------------------
    // Tier 3: catalog-backed fixes for absent-name renders (extension 14).
    // ------------------------------------------------------------------

    @Test
    fun trinoShaDigestsToDorisUseSha2WithUnhexWrap() {
        // Doris SHA2 returns hex VARCHAR; Trino sha256/sha512 return VARBINARY — the
        // UNHEX wrap mirrors the hazard-pinned duckdb treatment (UNHEX(SHA256(x))).
        assertEquals(
            "SELECT UNHEX(SHA2(x, 256))",
            transpile("SELECT SHA256(x)", read = "trino", write = "doris"),
        )
        assertEquals(
            "SELECT UNHEX(SHA2(x, 512))",
            transpile("SELECT SHA512(x)", read = "trino", write = "doris"),
        )
        assertEquals(
            "SELECT UNHEX(MD5(x))",
            transpile("SELECT MD5(x)", read = "trino", write = "doris"),
        )
    }

    @Test
    fun dorisAbsentNameRendersFixedForTrino() {
        assertEquals(
            "SELECT IS_INFINITE(x)",
            transpile("SELECT ISINF(x)", read = "duckdb", write = "trino"),
        )
        assertEquals(
            "SELECT CURRENT_SCHEMA",
            transpile("SELECT DATABASE()", read = "doris", write = "trino"),
        )
        assertEquals(
            "SELECT DATE_ADD('MONTH', 2, d)",
            transpile("SELECT MONTHS_ADD(d, 2)", read = "doris", write = "trino"),
        )
        assertEquals(
            "SELECT SPLIT(x, ',')",
            transpile("SELECT SPLIT_BY_STRING(x, ',')", read = "doris", write = "trino"),
        )
    }

    @Test
    fun duckdbAbsentNameRendersFixedForDoris() {
        assertEquals(
            "SELECT GROUP_BIT_AND(x)",
            transpile("SELECT BIT_AND(x)", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT GROUP_BIT_OR(x)",
            transpile("SELECT BIT_OR(x)", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT GROUP_BIT_XOR(x)",
            transpile("SELECT BIT_XOR(x)", read = "duckdb", write = "doris"),
        )
        // ISO day-of-week (Mon=1..Sun=7): Doris WEEKDAY is Mon=0..Sun=6.
        assertEquals(
            "SELECT (WEEKDAY(x) + 1)",
            transpile("SELECT ISODOW(x)", read = "duckdb", write = "doris"),
        )
        // Doris lambda functions take the lambda FIRST (FE ArrayFilter/ArrayMap).
        assertEquals(
            "SELECT ARRAY_FILTER(x -> x > 1, arr)",
            transpile("SELECT LIST_FILTER(arr, x -> x > 1)", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT ARRAY_MAP(x -> x + 1, arr)",
            transpile("SELECT LIST_TRANSFORM(arr, x -> x + 1)", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT ARRAY_PUSHFRONT(arr, e)",
            transpile("SELECT LIST_PREPEND(e, arr)", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT ARRAY_SORT(arr)",
            transpile("SELECT LIST_SORT(arr)", read = "duckdb", write = "doris"),
        )
        assertEquals(
            "SELECT ARRAY_REVERSE_SORT(arr)",
            transpile("SELECT LIST_REVERSE_SORT(arr)", read = "duckdb", write = "doris"),
        )
    }

    @Test
    fun trinoApproxPercentileToDorisPercentileApprox() {
        assertEquals(
            "SELECT PERCENTILE_APPROX(x, 0.5)",
            transpile("SELECT APPROX_PERCENTILE(x, 0.5)", read = "trino", write = "doris"),
        )
    }

    @Test
    fun rangeAndSequenceToDorisArrayRange() {
        // duckdb range is end-exclusive — exactly Doris ARRAY_RANGE.
        assertEquals(
            "SELECT ARRAY_RANGE(0, 5)",
            transpile("SELECT RANGE(5)", read = "duckdb", write = "doris"),
        )
        // trino sequence is INCLUSIVE — stop bound shifts one step.
        assertEquals(
            "SELECT ARRAY_RANGE(1, 5 + 1)",
            transpile("SELECT SEQUENCE(1, 5)", read = "trino", write = "doris"),
        )
    }

    @Test
    fun dorisTimeToDuckdbStaysOnWallClockTier() {
        // brikk extension 15: the Python oracle emits `... AT TIME ZONE  AS TIME` (empty
        // zone operand — grammar-invalid) and would detour through the session-zone-
        // dependent TIMESTAMPTZ tier; zone-less TIME(x) extracts wall-clock time.
        assertEquals(
            "SELECT CAST(CAST(x AS TIMESTAMP) AS TIME)",
            transpile("SELECT TIME(x)", read = "doris", write = "duckdb"),
        )
    }

    @Test
    fun duckdbScalarUnnestToTrinoIsFlaggedNotSilent() {
        // brikk extension 13: explode_projection_to_unnest is not ported, and Trino has
        // no EXPLODE function — flag instead of silently emitting an unresolvable call.
        val result = SqlFragment("SELECT UNNEST([1, 2, 3]) + 1", "duckdb").transpileTo("trino")
        assertTrue(
            result.unsupportedMessages.any { "EXPLODE" in it },
            "expected flag: ${result.unsupportedMessages}",
        )
    }
}
