package dev.brikk.house.sql.shape

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
    fun dedicatedRendererMitigatesTheHazard() {
        // 'concat' is hazard-flagged divergent (NULL/coercion algebra) BUT every target
        // generator has a dedicated Concat renderer — trino->duckdb rewrites to the
        // gate-verified `||` + coalesce path, so the hazard is mitigated and the report
        // is fully clean.
        val fwd = report("SELECT concat(a, b) FROM t", "trino", "duckdb")
        assertEquals("SELECT a || b FROM t", fwd.result.sql)
        assertEquals(emptyList(), fwd.findings)
        assertTrue(fwd.ok)

        // Reverse direction: equally clean. The COALESCE the renderer emits is a Trino
        // grammar-level builtin (absent from SHOW FUNCTIONS, parsed by SqlBase.g4 /
        // AstBuilder) — cleared by the catalog's grammarBuiltins set.
        val rev = report("SELECT concat(a, b) FROM t", "duckdb", "trino")
        assertEquals(emptyList(), rev.findings)
        assertTrue(rev.ok)
    }

    @Test
    fun mitigatedHazardStillRefusedWhenGeneratorFlagsIt() {
        // duckdb->trino GREATEST: the dedicated renderer skips the hazard channel, but
        // it is exactly the renderer that flags the unverified NULL-algebra reversal
        // (brikk extension 11) — the refusal arrives via UNSUPPORTED_TRANSLATION.
        val r = report("SELECT greatest(a, b) FROM t", "duckdb", "trino")
        assertTrue(!r.ok)
        assertTrue(r.findings.none { it.kind == FindingKind.SEMANTIC_HAZARD })
        val f = refusals(r).single()
        assertEquals(FindingKind.UNSUPPORTED_TRANSLATION, f.kind)
        assertTrue("NULL-skipping" in f.detail, f.detail)
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
}
