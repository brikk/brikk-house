package dev.brikk.house.sql.shape

import dev.brikk.house.sql.metadata.HazardRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The "verified-correct transpilation" consumption modes: [SqlFragment.certify] rolls
 * every diagnostic channel (capability holes, generator flags, raw passthrough,
 * probe-verified semantic hazards) into one [TranspileReport]; machine mode reads it
 * via [SqlFragment.transpileStrict]/orThrow, human mode renders the findings.
 *
 * Every case here was verified empirically against the real channel behavior — the
 * RULE (finding derivation + mitigation skip) is what's under test.
 */
class CertifyTest {

    private fun report(sql: String, read: String, write: String): TranspileReport =
        SqlFragment(sql, read).certify(write)

    private fun refusals(r: TranspileReport) = r.findings.filter { it.severity == Severity.REFUSAL }

    // ------------------------------------------------------------ refusal channels

    @Test
    fun unmappableFunctionIsRefused() {
        val r = report("SELECT * FROM read_parquet('f.parquet')", "duckdb", "doris")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.UNMAPPABLE_FUNCTION, f.kind)
        assertEquals("READ_PARQUET", f.subject)
    }

    @Test
    fun unsupportedTranslationIsRefused() {
        val r = report("SELECT unnest([1, 2, 3])", "duckdb", "doris")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.UNSUPPORTED_TRANSLATION, f.kind)
        assertTrue("EXPLODE is only valid in LATERAL VIEW" in f.detail, f.detail)
    }

    @Test
    fun rawPassthroughStatementIsRefused() {
        val r = report("PRAGMA database_size", "duckdb", "trino")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.RAW_PASSTHROUGH_STATEMENT, f.kind)
        assertEquals("Pragma", f.subject)
    }

    @Test
    fun catalogLessTargetIsRefusedNotThrown() {
        // unmappableFunctions throws ShapeError for mysql; certify converts that hole
        // into a single NO_TARGET_CATALOG refusal ("cannot certify capability").
        val r = report("SELECT a FROM t", "duckdb", "mysql")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.NO_TARGET_CATALOG, f.kind)
        assertEquals("mysql", f.subject)
    }

    // ------------------------------------------------------------ semantic hazards

    @Test
    fun divergentPassthroughFunctionIsRefusedWithProvenance() {
        // 'lower' is fallback-rendered for trino (no dedicated renderer) and the
        // hazard DB pins it divergent (Turkish İ full case folding probe).
        val r = report("SELECT lower(x) FROM t", "duckdb", "trino")
        assertEquals("SELECT LOWER(x) FROM t", r.result.sql)
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.SEMANTIC_HAZARD, f.kind)
        assertEquals("LOWER", f.subject)
        assertTrue("İ" in f.detail, f.detail)
        assertNotNull(f.provenance)
        assertTrue("REPORT-string-unicode-audit" in f.provenance!!)
    }

    @Test
    fun dedicatedRendererDivergentIsWarningNotRefusal() {
        // BEHAVIOR CHANGE (certify policy #2, 2026-07-13): a dedicated renderer no longer
        // blanket-CLEARS a hazard, but it does downgrade a DIVERGENT/UNCLEAR verdict from
        // REFUSAL to a non-blocking WARNING. 'concat' is probe-verified DIVERGENT (NULL
        // algebra); trino->duckdb rewrites to `a || b` via the dedicated Concat renderer,
        // so it is a TRANSLATED function -> WARNING, ok stays true (the divergence is
        // surfaced, not silent; the consumer owns the risk). The multi-key lookup still
        // hits the source-surface name CONCAT. (The || mapping also has a SEPARATE probe-
        // verified IDENTICAL entry `concat -> || operator`, so this is very likely a stale
        // finding for this direction; it stays a visible WARNING pending owner review —
        // see docs/research/REPORT-certify-hazard-hole-closed-2026-07-13.md.)
        val fwd = report("SELECT concat(a, b) FROM t", "trino", "duckdb")
        assertEquals("SELECT a || b FROM t", fwd.result.sql)
        assertTrue(fwd.ok)
        val f = fwd.findings.single()
        assertEquals(FindingKind.SEMANTIC_HAZARD, f.kind)
        assertEquals(Severity.WARNING, f.severity)
        assertEquals("CONCAT", f.subject)
        assertTrue(f.detail.startsWith("divergent:"), f.detail)
    }

    @Test
    fun divergentTranslatedHazardWarnsButGeneratorFlagStillRefuses() {
        // duckdb->trino GREATEST: probe-verified DIVERGENT (Trino returns NULL if any
        // arg is NULL; DuckDB skips NULLs). Trino HAS a dedicated renderer for the node
        // (the CASE-wrap translation), so under certify policy #2 the SEMANTIC_HAZARD is
        // a non-blocking WARNING (translated function, consumer owns the residual risk).
        // The report is still NOT ok, but for an INDEPENDENT reason: the renderer itself
        // emits an UNSUPPORTED_TRANSLATION flag (channel 2, always a REFUSAL) for the
        // NULL-skipping case it cannot fully translate. Two findings, one WARNING + one
        // REFUSAL; ok is false because of the latter.
        val r = report("SELECT greatest(a, b) FROM t", "duckdb", "trino")
        assertTrue(!r.ok)
        val hazard = r.findings.single { it.kind == FindingKind.SEMANTIC_HAZARD }
        assertEquals(Severity.WARNING, hazard.severity)
        assertTrue("NULL" in hazard.detail, hazard.detail)
        val flagged = r.findings.single { it.kind == FindingKind.UNSUPPORTED_TRANSLATION }
        assertEquals(Severity.REFUSAL, flagged.severity)
        assertTrue("NULL-skipping" in flagged.detail, flagged.detail)
    }

    @Test
    fun conditionallyEquivalentIsWarningAndOk() {
        // duckdb->trino 'to_base': fallback-rendered, hazard verdict
        // conditionally-equivalent (padding-arg divergence corner) — WARNING, ok stays
        // true (permissive consumers proceed; strict consumers read findings).
        val r = report("SELECT to_base(n, 16) FROM t", "duckdb", "trino")
        assertTrue(r.ok)
        val f = r.findings.single()
        assertEquals(Severity.WARNING, f.severity)
        assertEquals(FindingKind.SEMANTIC_HAZARD, f.kind)
        assertEquals("TO_BASE", f.subject)
        assertTrue(f.detail.startsWith("conditionally-equivalent:"), f.detail)
        assertNotNull(f.provenance)
    }

    @Test
    fun worstVerdictCollisionSurfacesAsWarning() {
        // trino->duckdb 'hour': identical-extract entry collides with the
        // conditionally-equivalent session-timezone entry — the registry keeps the
        // conservative verdict, so the fallback-rendered call warns.
        val r = report("SELECT hour(x) FROM t", "trino", "duckdb")
        assertTrue(r.ok)
        val f = r.findings.single()
        assertEquals(Severity.WARNING, f.severity)
        assertEquals("HOUR", f.subject)
        assertTrue("TimeZone" in f.detail, f.detail)
    }

    @Test
    fun grammarBuiltinIsNotACapabilityHole() {
        // COALESCE is absent from Trino's SHOW FUNCTIONS (parser special form) — the
        // catalog's grammarBuiltins set clears it, so a direct call certifies clean
        // (its hazard verdict is probe-verified IDENTICAL).
        val r = report("SELECT COALESCE(1, 2)", "duckdb", "trino")
        assertEquals("SELECT COALESCE(1, 2)", r.result.sql)
        assertEquals(emptyList(), r.findings)
        assertTrue(r.ok)
    }

    // ------------------------------------------------------------ pipe desugaring

    @Test
    fun certifyDesugarsPipesForRealEngines() {
        // Doris doesn't speak |>; desugarPipes=true certifies the standard-syntax
        // rendering (WITH __tmp form) — clean end to end for a plain pipe fragment.
        val fragment = SqlFragment("FROM sales |> WHERE qty > 0 |> SELECT item, qty")
        val r = fragment.certify("doris", desugarPipes = true)
        assertTrue(r.ok, "${r.findings}")
        assertEquals(emptyList(), r.findings)
        assertTrue("|>" !in r.result.sql, r.result.sql)
        assertTrue("__tmp" in r.result.sql, r.result.sql)
        // Default keeps pipe rendering — same fragment, pipe operator preserved.
        assertTrue("|>" in fragment.certify("doris").result.sql)
    }

    // ------------------------------------------------------------ clean + strict mode

    @Test
    fun cleanQueryCertifiesWithEmptyFindings() {
        val r = report("SELECT o_orderkey FROM orders", "duckdb", "trino")
        assertTrue(r.ok)
        assertEquals(emptyList(), r.findings)
        assertEquals("SELECT o_orderkey FROM orders", r.result.sql)
    }

    @Test
    fun orThrowReturnsResultWhenOk() {
        val result = SqlFragment("SELECT abs(x) FROM t", "duckdb").transpileStrict("trino")
        assertEquals("SELECT ABS(x) FROM t", result.sql)
    }

    @Test
    fun orThrowSucceedsOnWarningsOnly() {
        // Warnings do not break machine mode — conditional equivalence is accepted.
        val result = SqlFragment("SELECT to_base(n, 16) FROM t", "duckdb").transpileStrict("trino")
        assertEquals("SELECT TO_BASE(n, 16) FROM t", result.sql)
    }

    @Test
    fun orThrowListsEveryRefusal() {
        val report = report("SELECT lower(x), upper(x) FROM t", "duckdb", "trino")
        assertEquals(2, refusals(report).size)
        try {
            report.orThrow()
            fail("expected TranspileCertificationError")
        } catch (e: TranspileCertificationError) {
            assertTrue("LOWER" in e.message!!, e.message)
            assertTrue("UPPER" in e.message!!, e.message)
            assertTrue("SEMANTIC_HAZARD" in e.message!!, e.message)
        }
    }

    @Test
    fun repeatedCallsProduceOneFindingPerSubject() {
        val r = report("SELECT lower(a), lower(b) FROM t", "duckdb", "trino")
        assertEquals(1, r.findings.size)
    }

    // ---------------------------------------- construct hazard: DATE + INTERVAL promotion

    @Test
    fun dateInterval_provablyDate_isRefused() {
        // CAST(x AS DATE) + INTERVAL '1' DAY: the base operand is provably DATE-typed
        // (Cast to DATE), so DuckDB->TIMESTAMP promotion certainly applies -> REFUSAL.
        val r = report("SELECT CAST(x AS DATE) + INTERVAL '1' DAY FROM t", "duckdb", "trino")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.SEMANTIC_HAZARD, f.kind)
        assertEquals("DATE + INTERVAL", f.subject)
        assertTrue("promotion" in f.detail, f.detail)
        assertTrue("datetime" in f.areas, f.areas.toString())
        assertNotNull(f.provenance)
        assertTrue("add_files_hive_partition_cast.test:51" in f.provenance!!, f.provenance!!)
    }

    @Test
    fun dateLiteralInterval_isRefused() {
        // DATE '...' literal parses to a Cast-to-DATE too -> provably DATE -> REFUSAL.
        val r = report("SELECT DATE '2024-01-02' + INTERVAL '1' DAY", "duckdb", "trino")
        val f = refusals(r).single()
        assertEquals("DATE + INTERVAL", f.subject)
    }

    @Test
    fun dateInterval_bareColumn_isWarningNotRefusal() {
        // Bare column: operand type unknown from syntax. Can't prove the DATE promotion
        // (not a hard refusal) but can't rule it out either -> WARNING, ok stays true.
        val r = report("SELECT date_col + INTERVAL 1 DAY FROM t", "duckdb", "trino")
        assertTrue(r.ok)
        val f = r.findings.single()
        assertEquals(Severity.WARNING, f.severity)
        assertEquals(FindingKind.SEMANTIC_HAZARD, f.kind)
        assertEquals("DATE + INTERVAL", f.subject)
    }

    @Test
    fun dateInterval_subtraction_isDetected() {
        // Sub is the same promotion shape.
        val r = report("SELECT CAST(x AS DATE) - INTERVAL '1' DAY FROM t", "duckdb", "trino")
        val f = refusals(r).single()
        assertEquals("DATE - INTERVAL", f.subject)
    }

    @Test
    fun dateInterval_firesInBothDirections() {
        // trino->duckdb is equally affected (either direction).
        val r = report("SELECT date_col + INTERVAL 1 DAY FROM t", "trino", "duckdb")
        assertEquals(Severity.WARNING, r.findings.single().severity)
    }

    @Test
    fun timestampInterval_provablyTimestamp_noFinding() {
        // TIMESTAMP '...' + INTERVAL: base is provably TIMESTAMP, so the DATE-promotion
        // divergence does not apply -> no construct finding at all.
        val r = report("SELECT TIMESTAMP '2024-01-02' + INTERVAL '1' DAY", "duckdb", "trino")
        assertTrue(r.findings.none { it.subject == "DATE + INTERVAL" }, r.findings.toString())
    }

    @Test
    fun dateInterval_nonAffectedPair_noFinding() {
        // duckdb->doris has no such live evidence; do not invent it for other pairs.
        // (doris ships no catalog here, so the only finding is NO_TARGET_CATALOG — never
        // the DATE+INTERVAL construct hazard.)
        val r = report("SELECT date_col + INTERVAL 1 DAY FROM t", "duckdb", "doris")
        assertTrue(r.findings.none { it.subject.contains("INTERVAL") }, r.findings.toString())
    }

    // -------------------------------------------------- consumer severity policy (areas)

    @Test
    fun okAccepting_unicodeArea_clearsUnicodeRefusal() {
        // lower() duckdb->trino stays REFUSAL by default (verdict honest, not downgraded).
        val r = report("SELECT lower(x) FROM t", "duckdb", "trino")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertTrue("unicode" in f.areas, f.areas.toString())
        // An ASCII-only consumer accepts unicode-scoped refusals -> okAccepting is true.
        assertTrue(r.okAccepting { "unicode" in it.areas })
    }

    @Test
    fun okAccepting_doesNotClearNonUnicodeRefusal() {
        // An UNMAPPABLE_FUNCTION refusal has empty areas; a unicode-only accept predicate
        // is false for it -> okAccepting stays false. The consumer only waived unicode.
        val r = report("SELECT * FROM read_parquet('f.parquet')", "duckdb", "doris")
        assertTrue(!r.ok)
        val f = refusals(r).single()
        assertEquals(FindingKind.UNMAPPABLE_FUNCTION, f.kind)
        assertEquals(emptyList(), f.areas)
        assertTrue(!r.okAccepting { "unicode" in it.areas })
    }

    @Test
    fun semanticHazardFindingCarriesAreas() {
        // SEMANTIC_HAZARD findings surface FunctionHazard.areas; others stay empty.
        val hazard = report("SELECT lower(x) FROM t", "duckdb", "trino").findings.single()
        assertTrue(hazard.areas.isNotEmpty())
        assertTrue("string" in hazard.areas && "unicode" in hazard.areas, hazard.areas.toString())
    }

    // --------------------------------------- multi-key lookup + verdict-driven certify

    @Test
    fun multiKeyLookupHitsSourceSurfaceName() {
        // duckdb `list_min` parses to the canonical ArrayMin node (sqlName ARRAY_MIN),
        // NOT the surface name LIST_MIN. The duckdb->trino divergent entry
        // (`list_min/list_max` -> `array_min/array_max`, NULL/type divergence) is keyed
        // under the DUCKDB-side surface name LIST_MIN, so a parsed-sqlName-only lookup
        // (ARRAY_MIN) MISSES it. The multi-key set recovers LIST_MIN by rendering the
        // node under the SOURCE dialect -> the divergent hazard now fires.
        val r = report("SELECT list_min(xs) FROM t", "duckdb", "trino")
        assertEquals("SELECT ARRAY_MIN(xs) FROM t", r.result.sql)
        assertTrue(!r.ok)
        val f = r.findings.single { it.kind == FindingKind.SEMANTIC_HAZARD }
        assertEquals(Severity.REFUSAL, f.severity)
        // Parsed sqlName alone would have missed: ARRAY_MIN is not a key for this pair.
        assertEquals(null, HazardRegistry.lookup("duckdb", "trino", "ARRAY_MIN"))
        assertNotNull(HazardRegistry.lookup("duckdb", "trino", "LIST_MIN"))
    }

    @Test
    fun identicalMappingStaysOk() {
        // duckdb `list_has_any` -> Doris ARRAYS_OVERLAP is a probe-verified IDENTICAL
        // mapping (the generator fix). An IDENTICAL entry produces NO SEMANTIC_HAZARD
        // finding even though the source surface name resolves and a dedicated renderer
        // exists — trust lives in the DATA.
        val r = report("SELECT list_has_any(a, b) FROM t", "duckdb", "doris")
        assertEquals("SELECT ARRAYS_OVERLAP(a, b) FROM t", r.result.sql)
        assertTrue(r.findings.none { it.kind == FindingKind.SEMANTIC_HAZARD }, r.findings.toString())
    }

    @Test
    fun conditionallyEquivalentStaysWarningUnderMultiKey() {
        // A conditionally-equivalent verdict remains a WARNING (ok stays true) under the
        // verdict-driven rule — only DIVERGENT/UNCLEAR refuse.
        val r = report("SELECT to_base(n, 16) FROM t", "duckdb", "trino")
        assertTrue(r.ok)
        val f = r.findings.single { it.kind == FindingKind.SEMANTIC_HAZARD }
        assertEquals(Severity.WARNING, f.severity)
    }

    @Test
    fun okAcceptingStillWorksOverNewFindings() {
        // okAccepting composes over the (now more complete) refusal set: waiving the
        // hazard's areas clears the refusal, exactly as with the old findings.
        val r = report("SELECT list_min(xs) FROM t", "duckdb", "trino")
        assertTrue(!r.ok)
        val f = r.findings.single { it.kind == FindingKind.SEMANTIC_HAZARD }
        assertTrue(r.okAccepting { it.areas.any { a -> a in f.areas } })
    }
}
