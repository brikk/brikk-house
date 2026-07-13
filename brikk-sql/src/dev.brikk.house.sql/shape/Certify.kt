package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.AnonymousAggFunc
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.sqlName
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.metadata.FunctionHazard
import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict

/*
 * BRIKK-NATIVE: "verified-correct transpilation" consumption modes.
 *
 * transpileTo() is best-effort by design (sqlglot semantics: warn and continue). The
 * certification layer turns its diagnostic channels — plus the shipped semantic-hazard
 * registry (brikk-sql-metadata, live-probe-verified trino<->duckdb verdicts) — into a
 * single [TranspileReport] that two kinds of consumers read differently:
 *
 *  - machine mode ("I'm transpiling a view — it must work or error"): [transpileStrict]
 *    / [TranspileReport.orThrow] — any REFUSAL finding throws.
 *  - human mode ("warn me, I'll hand-edit"): call [certify], render [TranspileReport.findings],
 *    ship the SQL anyway.
 *
 * The report itself is mode-agnostic — STRICT vs PERMISSIVE ([TranspileMode]) is purely
 * a consumer decision over the same findings, not a generation switch.
 *
 * Belt-and-braces composition: certification checks capability and semantics, not
 * grammar. Module layering (brikk-sql-verify depends on brikk-sql) prevents calling the
 * real-engine parsers from here — compose at the call site:
 * ```
 * val result = fragment.transpileStrict("duckdb")                          // this module
 * check(SqlVerifiers.forEngine("duckdb")!!.verify(result.sql).accepted)    // brikk-sql-verify
 * ```
 */

/** How a consumer reads a [TranspileReport]; see the file header. Docs-only enum. */
enum class TranspileMode {
    /** Accept the SQL regardless of findings; render them for a human. */
    PERMISSIVE,

    /** Any REFUSAL finding aborts ([TranspileReport.orThrow]). */
    STRICT,
}

/** Severity of a certification [Finding]. */
enum class Severity {
    /** Strict consumers must not run the emitted SQL. */
    REFUSAL,

    /** Emitted SQL is equivalent under common conditions — review [Finding.detail]. */
    WARNING,
}

/** What a certification [Finding] is about. */
enum class FindingKind {
    /** A function name would reach the target engine unregistered (Class-3 hole). */
    UNMAPPABLE_FUNCTION,

    /** The generator flagged a construct it emitted best-effort (unsupportedMessages). */
    UNSUPPORTED_TRANSLATION,

    /** Command/Pragma root: verbatim text with no cross-dialect semantics. */
    RAW_PASSTHROUGH_STATEMENT,

    /** A probe-verified semantic divergence with no gate-verified mitigation. */
    SEMANTIC_HAZARD,

    /** The target dialect ships no function catalog — capability cannot be certified. */
    NO_TARGET_CATALOG,
}

/** One certification finding: [subject] is the function name / construct, [detail] the
 * human-readable evidence, [provenance] the research-report pointer when the finding
 * comes from the hazard registry. */
data class Finding(
    val severity: Severity,
    val kind: FindingKind,
    val subject: String,
    val detail: String,
    val provenance: String? = null,
)

/** Thrown by [TranspileReport.orThrow] when the report carries REFUSAL findings. */
class TranspileCertificationError(message: String) : RuntimeException(message)

/**
 * The certified transpilation: the best-effort [result] plus every [Finding] derived
 * from it. [ok] means "no refusals" — WARNINGs (conditionally-equivalent hazards) do
 * not clear it to false; strict consumers should still read [findings].
 */
data class TranspileReport(
    val result: TranspileResult,
    val findings: List<Finding>,
) {
    val ok: Boolean get() = findings.none { it.severity == Severity.REFUSAL }

    /** [result] if [ok]; throws [TranspileCertificationError] listing every refusal otherwise. */
    fun orThrow(): TranspileResult {
        val refusals = findings.filter { it.severity == Severity.REFUSAL }
        if (refusals.isEmpty()) return result
        throw TranspileCertificationError(
            "Transpilation refused (${refusals.size} finding(s)):\n" +
                refusals.joinToString("\n") { " - [${it.kind}] ${it.subject}: ${it.detail}" }
        )
    }
}

/**
 * Transpiles to [target] and derives every certification [Finding]:
 *
 *  1. [SqlFragment.unmappableFunctions] -> one REFUSAL/[FindingKind.UNMAPPABLE_FUNCTION]
 *     each; when [target] ships no function catalog, a single
 *     REFUSAL/[FindingKind.NO_TARGET_CATALOG] instead (capability cannot be certified).
 *  2. [TranspileResult.unsupportedMessages] -> REFUSAL/[FindingKind.UNSUPPORTED_TRANSLATION] each.
 *  3. Command/Pragma root -> REFUSAL/[FindingKind.RAW_PASSTHROUGH_STATEMENT].
 *  4. Semantic hazards: every function call in the SOURCE ast is looked up in
 *     [HazardRegistry] under (source dialect, target). DIVERGENT and UNCLEAR verdicts
 *     are REFUSALs, CONDITIONALLY_EQUIVALENT is a WARNING (result-identical under
 *     common conditions — permissive consumers proceed, strict consumers read the
 *     findings); IDENTICAL / NO_EQUIVALENT produce nothing here (NO_EQUIVALENT
 *     surfaces through 1/2 when it matters).
 *
 *     MITIGATION RULE: a hazard is SKIPPED when the target generator has a dedicated
 *     renderer for the node's class ([dev.brikk.house.sql.generator.Generator.hasDedicatedRenderer])
 *     — dedicated renderers are the gate-verified translations that exist precisely to
 *     fix these divergences (e.g. trino->duckdb GREATEST/LEAST CASE-wrap, REGEXP_REPLACE
 *     'g' forcing), and the ones that can't fix a case flag it through
 *     unsupportedMessages (channel 2). Unresolved ([Anonymous]) calls are always
 *     checked: their "renderer" is the verbatim passthrough, never a mitigation.
 *
 * [desugarPipes] is threaded to [SqlFragment.transpileTo] (and the capability check):
 * real engines don't speak `|>`, so when certifying a pipe-syntax fragment for a real
 * engine pass true (or pre-desugar manually via [SqlFragment.toStandardSql]).
 */
fun SqlFragment.certify(
    target: String,
    pretty: Boolean = false,
    trackSourceMap: Boolean = false,
    desugarPipes: Boolean = false,
): TranspileReport {
    val result = transpileTo(
        target,
        pretty = pretty,
        trackSourceMap = trackSourceMap,
        desugarPipes = desugarPipes,
    )
    val findings = LinkedHashSet<Finding>()

    // 1. Capability: unmappable functions, or no catalog to check against.
    if (Dialects.forName(target).functionCatalog == null) {
        findings.add(
            Finding(
                Severity.REFUSAL, FindingKind.NO_TARGET_CATALOG, target,
                "target dialect '$target' ships no function catalog — capability cannot be certified",
            )
        )
    } else {
        for (name in unmappableFunctions(target, desugarPipes = desugarPipes)) {
            findings.add(
                Finding(
                    Severity.REFUSAL, FindingKind.UNMAPPABLE_FUNCTION, name,
                    "'$name' is not registered by the '$target' engine and would reach it verbatim",
                )
            )
        }
    }

    // 2. Generator-flagged best-effort translations.
    for (message in result.unsupportedMessages) {
        findings.add(
            Finding(Severity.REFUSAL, FindingKind.UNSUPPORTED_TRANSLATION, rootKind, message)
        )
    }

    // 3. Statement-shaped passthrough.
    if (isRawPassthroughStatement) {
        findings.add(
            Finding(
                Severity.REFUSAL, FindingKind.RAW_PASSTHROUGH_STATEMENT, rootKind,
                "$rootKind statements transpile as verbatim text with no cross-dialect semantics",
            )
        )
    }

    // 4. Probe-verified semantic hazards (source-side names, mitigation-aware).
    val targetGenerator = Dialects.forName(target).generator()
    for (node in ast.walk(bfs = false)) {
        if (node !is Func) continue
        val name = when (node) {
            is Anonymous -> node.name
            is AnonymousAggFunc -> node.name
            else -> node.sqlName()
        }
        if (name.isEmpty()) continue
        val hazard = HazardRegistry.lookup(dialect, target, name) ?: continue
        val mitigated = node !is Anonymous && node !is AnonymousAggFunc &&
            targetGenerator.hasDedicatedRenderer(node::class)
        if (mitigated) continue
        val severity = when (hazard.verdict) {
            HazardVerdict.DIVERGENT, HazardVerdict.UNCLEAR -> Severity.REFUSAL
            HazardVerdict.CONDITIONALLY_EQUIVALENT -> Severity.WARNING
            HazardVerdict.IDENTICAL, HazardVerdict.NO_EQUIVALENT -> continue
        }
        findings.add(
            Finding(
                severity, FindingKind.SEMANTIC_HAZARD, name.uppercase(),
                hazardDetail(hazard),
                provenance = hazard.provenance,
            )
        )
    }

    return TranspileReport(result, findings.toList())
}

/**
 * Machine-mode convenience ([TranspileMode.STRICT]): certified transpile that throws
 * [TranspileCertificationError] on any REFUSAL finding. [desugarPipes] as in [certify]
 * — pass true when the fragment is pipe syntax and [target] is a real engine.
 */
fun SqlFragment.transpileStrict(target: String, desugarPipes: Boolean = false): TranspileResult =
    certify(target, desugarPipes = desugarPipes).orThrow()

private fun hazardDetail(hazard: FunctionHazard): String {
    val verdict = hazard.verdict.name.lowercase().replace('_', '-')
    val text = hazard.hazard ?: "probe-verified '$verdict' verdict"
    return "$verdict: $text"
}
