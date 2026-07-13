package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.AnonymousAggFunc
import dev.brikk.house.sql.ast.Binary
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Sub
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
 * comes from the hazard registry.
 *
 * [areas] carries the hazard's semantic-area tags (from [FunctionHazard.areas], e.g.
 * "unicode", "datetime", "null") for [FindingKind.SEMANTIC_HAZARD] findings and is empty
 * otherwise. It exists so a consumer can make context-dependent risk decisions over
 * honest verdicts (see [TranspileReport.okAccepting]) — the JSON stays faithful; the
 * consumer owns the acceptance. Trailing default keeps the constructor wire-compatible. */
data class Finding(
    val severity: Severity,
    val kind: FindingKind,
    val subject: String,
    val detail: String,
    val provenance: String? = null,
    val areas: List<String> = emptyList(),
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

    /**
     * Consumer-controlled acceptance: [ok] under a risk policy that the *consumer*, not
     * the registry, owns. Returns true when every REFUSAL finding is accepted by
     * [accept] (i.e. no unaccepted refusal remains); WARNINGs never block, exactly as
     * with [ok]. The shipped verdicts stay faithful to the live-probe research — this is
     * NOT a downgrade of any hazard; it lets a consumer opt into a divergence that its
     * own data makes irrelevant.
     *
     * Canonical use case — an ASCII-only corpus for which the unicode case-folding
     * hazard (lower()/upper(), verdict-divergent, no SQL fix) simply cannot manifest:
     * ```
     * report.okAccepting { "unicode" in it.areas }
     * ```
     * accepts precisely those refusals whose [Finding.areas] mark them unicode-scoped,
     * while any OTHER refusal (an unmappable function, an unrelated hazard) still blocks.
     */
    fun okAccepting(accept: (Finding) -> Boolean): Boolean =
        findings.none { it.severity == Severity.REFUSAL && !accept(it) }

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
                areas = hazard.areas,
            )
        )
    }

    // 5. Construct-level semantic hazards (BRIKK-NATIVE).
    //
    // The step-4 scan keys on FUNCTION names (Func/Anonymous), so operator/construct
    // divergences can never fire there. This step walks the SOURCE ast for the one
    // construct we have live-probe evidence for: DATE + INTERVAL / DATE - INTERVAL type
    // promotion between trino and duckdb (either direction).
    //
    // WHY A HAND-WRITTEN CHECK, NOT A REGISTRY LOOKUP: `date_col + INTERVAL 1 DAY`
    // parses to an Add (not DateAdd) whose `expression` operand is an Interval — an
    // OPERATOR, carrying no function name to look up. The faithful registry entry
    // exists for provenance/documentation; detection is the shape of the AST node.
    //
    // Evidence: DuckDB promotes DATE + INTERVAL to TIMESTAMP, Trino keeps DATE — a
    // transpiled expression round-trips value-equal but returns a different TYPE, which
    // diverges downstream (casts, comparisons, string rendering). Live corpus:
    // trino agent add_files_hive_partition_cast.test:51.
    findConstructHazards(target, findings)

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

/*
 * BRIKK-NATIVE construct-level hazard: DATE + INTERVAL type promotion (trino<->duckdb).
 *
 * DuckDB promotes DATE + INTERVAL (and DATE - INTERVAL) to TIMESTAMP; Trino keeps DATE.
 * The value round-trips but the result TYPE diverges, so this fires for the trino<->duckdb
 * pair in EITHER direction. No other pair carries this evidence — leave them untouched.
 */
private val PROMOTION_PAIR = setOf("trino", "duckdb")

/** Detail + provenance for the construct hazard, mirroring the faithful registry entry. */
private const val PROMOTION_DETAIL =
    "DATE + INTERVAL type promotion: DuckDB promotes DATE +/- INTERVAL to TIMESTAMP " +
        "(e.g. '2024-01-03 00:00:00'), Trino keeps DATE ('2024-01-03') — value round-trips " +
        "but the result type diverges (downstream casts/comparisons/rendering differ)"
private const val PROMOTION_PROVENANCE =
    "live corpus evidence: trino agent add_files_hive_partition_cast.test:51"

/**
 * Walks the SOURCE ast for Add/Sub nodes with an [Interval] operand and flags the
 * promotion-divergent shape. Only the trino<->duckdb pair is affected.
 *
 * TIERED SEVERITY (rationale): the divergence is a DATE-vs-TIMESTAMP promotion, so it
 * only bites when the non-interval operand is a DATE.
 *   - non-interval operand is *provably DATE-typed syntactically* (DATE '...' literal or
 *     CAST/TRY_CAST to DATE — both parse to a [Cast] whose target [DType] is DATE) ->
 *     REFUSAL: we can prove the promotion applies.
 *   - operand type unknown (a bare column, an expression we can't type from syntax
 *     alone) -> WARNING: we can neither prove the DATE promotion (so not a hard refusal)
 *     nor rule it out (the column may well be a DATE), so it must still surface.
 *   - operand is *provably TIMESTAMP-typed* (TIMESTAMP '...' literal / CAST to a
 *     timestamp type) -> nothing: promotion does not apply to an already-TIMESTAMP
 *     operand. (The registry's date_trunc/extract notes confirm the divergence is
 *     specifically DATE-input promotion; a TIMESTAMP operand stays TIMESTAMP in both.)
 */
private fun SqlFragment.findConstructHazards(target: String, findings: MutableSet<Finding>) {
    if (setOf(dialect, target) != PROMOTION_PAIR) return

    for (node in ast.walk(bfs = false)) {
        if (node !is Add && node !is Sub) continue
        val binary = node as Binary
        val left = binary.left
        val right = binary.right

        // The Interval must be one operand; the "base" operand is the other.
        val base = when {
            right is Interval -> left
            left is Interval -> right
            else -> continue
        }
        // Two intervals (interval arithmetic) is not a date-promotion shape.
        if (base is Interval) continue

        val severity = when (dateOperandTyping(base)) {
            OperandType.DATE -> Severity.REFUSAL      // provably DATE: promotion applies
            OperandType.TIMESTAMP -> continue         // provably TIMESTAMP: no promotion
            OperandType.UNKNOWN -> Severity.WARNING   // can't prove, can't ignore
        }
        findings.add(
            Finding(
                severity, FindingKind.SEMANTIC_HAZARD,
                if (node is Add) "DATE + INTERVAL" else "DATE - INTERVAL",
                "divergent: $PROMOTION_DETAIL",
                provenance = PROMOTION_PROVENANCE,
                areas = listOf("datetime"),
            )
        )
    }
}

private enum class OperandType { DATE, TIMESTAMP, UNKNOWN }

/**
 * Best-effort SYNTACTIC typing of the non-interval operand. Only recognizes what a
 * DATE '...' literal or a CAST/TRY_CAST spells out; anything else (bare column,
 * arbitrary expression) is UNKNOWN. TryCast is a Cast subclass, so `is Cast` covers both.
 */
private fun dateOperandTyping(operand: Expression): OperandType {
    if (operand !is Cast) return OperandType.UNKNOWN
    val to = operand.to
    return when {
        to.isType(DType.DATE) -> OperandType.DATE
        to.isType(
            DType.TIMESTAMP, DType.TIMESTAMPNTZ, DType.TIMESTAMPLTZ, DType.TIMESTAMPTZ,
            DType.TIMESTAMP_S, DType.TIMESTAMP_MS, DType.TIMESTAMP_NS,
            DType.DATETIME, DType.DATETIME2, DType.DATETIME64,
        ) -> OperandType.TIMESTAMP
        else -> OperandType.UNKNOWN
    }
}
