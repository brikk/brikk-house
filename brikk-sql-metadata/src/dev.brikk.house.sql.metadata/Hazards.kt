package dev.brikk.house.sql.metadata

import kotlinx.serialization.Serializable

/*
 * SEMANTIC HAZARD REGISTRY
 *
 * Live-probe-verified cross-engine semantics verdicts for function pairs, generated from
 * brikk-sql/testResources/semantics/<a>-<b>-hazards.json (one file per dialect pair; the
 * source of truth) by tools/generate_hazards_registry.py into GeneratedABHazards.kt per pair.
 *
 * Same stable-accessor posture as FunctionCatalog.kt: this module is the featherweight
 * metadata artifact external consumers embed — keep the surface small, additive-only,
 * defaulted fields.
 */

/**
 * The probe verdict for a (source function, target function) pair between two engines.
 * Mirrors the `verdict` field of the hazards JSON (live-probe-verified evidence, never a
 * guess — see the `provenance` pointers into the research reports).
 */
enum class HazardVerdict {
    /** Probe-verified result-identical (including NULL algebra / edge inputs). */
    IDENTICAL,

    /** Probe-verified different results for the same inputs — never silently map. */
    DIVERGENT,

    /**
     * Result-identical under common conditions, divergent in documented corners
     * (specific argument shapes, extensions required, precision limits, ...).
     */
    CONDITIONALLY_EQUIVALENT,

    /** The other engine has no equivalent construct. */
    NO_EQUIVALENT,

    /** Evidence is incomplete — treat as unsafe until probed. */
    UNCLEAR,
}

/**
 * One hazard verdict as shipped data. [hazard] is the human-readable probe finding
 * (null for verdicts that need no explanation), [areas] the semantic areas involved
 * (null-handling, unicode, timezone, ...), [provenance] the pointer into the research
 * report that pinned the verdict.
 *
 * [sourceName] / [targetName] carry the two dialect-side function names from the hazards
 * JSON, oriented to the lookup direction: for a `lookup(source, target, ...)` hit,
 * [sourceName] is the name under the source dialect and [targetName] the name under the
 * target dialect (e.g. trino->doris `contains` -> `array_contains`). Both default to null
 * and are additive — existing call-sites and serialized data are unaffected.
 */
@Serializable
data class FunctionHazard(
    val verdict: HazardVerdict,
    val hazard: String? = null,
    val areas: List<String> = emptyList(),
    val provenance: String,
    val sourceName: String? = null,
    val targetName: String? = null,
)

/**
 * Directional lookup over the shipped hazard pair data. Each JSON pair entry is keyed
 * BOTH ways: a→b consults the entry's a-side name, b→a the b-side name (the name a
 * fragment parsed under that source dialect would carry).
 *
 * Keying (see tools/generate_hazards_registry.py, mirrored here for consumers):
 *  - the raw side-name string, uppercased (constructs like "CAST (primitive)" or
 *    "IS [NOT] DISTINCT FROM" stay retrievable by their verbatim string — they simply
 *    never match a parsed function name, which is fine);
 *  - each bare-identifier alternative from `a / b / c` lists (an optional trailing
 *    `()` is stripped: `today()` keys as TODAY);
 *  - when several entries claim the same key (e.g. Trino `concat` has both a divergent
 *    argument-coercion entry and an identical `||`-mapping entry), the WORST verdict
 *    wins (DIVERGENT > UNCLEAR > CONDITIONALLY_EQUIVALENT > NO_EQUIVALENT > IDENTICAL;
 *    ties: first entry in the JSON) — a hazard registry must be conservative.
 *
 * Wired pairs today: trino↔duckdb, duckdb↔doris, trino↔doris, duckdb↔clickhouse,
 * trino↔clickhouse, doris↔clickhouse and starrocks↔doris (all live-probe-filled).
 * Lookups for unknown pairs simply return null (see SemanticCoverage for the explicit
 * pair/scope coverage metadata that certification consults so an unresearched pair is
 * conservatively refused rather than silently treated as safe).
 */
object HazardRegistry {

    private val pairs: Map<Pair<String, String>, Map<String, FunctionHazard>> = mapOf(
        ("trino" to "duckdb") to TRINO_TO_DUCKDB_HAZARDS,
        ("duckdb" to "trino") to DUCKDB_TO_TRINO_HAZARDS,
        ("duckdb" to "doris") to DUCKDB_TO_DORIS_HAZARDS,
        ("doris" to "duckdb") to DORIS_TO_DUCKDB_HAZARDS,
        ("trino" to "doris") to TRINO_TO_DORIS_HAZARDS,
        ("doris" to "trino") to DORIS_TO_TRINO_HAZARDS,
        ("duckdb" to "clickhouse") to DUCKDB_TO_CLICKHOUSE_HAZARDS,
        ("clickhouse" to "duckdb") to CLICKHOUSE_TO_DUCKDB_HAZARDS,
        ("trino" to "clickhouse") to TRINO_TO_CLICKHOUSE_HAZARDS,
        ("clickhouse" to "trino") to CLICKHOUSE_TO_TRINO_HAZARDS,
        ("doris" to "clickhouse") to DORIS_TO_CLICKHOUSE_HAZARDS,
        ("clickhouse" to "doris") to CLICKHOUSE_TO_DORIS_HAZARDS,
        // StarRocks<->Doris (common MySQL lineage; live behavior-matrix probe of
        // StarRocks 4.1.4 vs Doris 4.1.3 — see doris-starrocks-hazards.json).
        ("starrocks" to "doris") to STARROCKS_TO_DORIS_HAZARDS,
        ("doris" to "starrocks") to DORIS_TO_STARROCKS_HAZARDS,
    )

    /**
     * The hazard verdict for [functionName] (as parsed under [sourceDialect]) when
     * transpiling [sourceDialect] → [targetDialect]; null when the pair or the name is
     * unknown. Dialect names and function names are case-insensitive.
     */
    fun lookup(sourceDialect: String, targetDialect: String, functionName: String): FunctionHazard? =
        pairs[sourceDialect.lowercase() to targetDialect.lowercase()]
            ?.get(functionName.trim().uppercase())

    /**
     * Resolve a semantic concept from every name a parsed function can carry. Unlike
     * [lookup], which intentionally remains source-side directional, this also matches
     * the evidence entry's target-side name so a typed rewrite such as DuckDB
     * `list_has_any` -> Doris `arrays_overlap` receives its recorded verdict when the
     * canonical/source rendering is operator-shaped. Collisions choose the worst verdict.
     */
    fun lookupConcept(
        sourceDialect: String,
        targetDialect: String,
        functionKeys: Iterable<String>,
    ): FunctionHazard? {
        val pair = pairs[sourceDialect.lowercase() to targetDialect.lowercase()] ?: return null
        val keys = functionKeys.map { it.trim().uppercase() }.toSet()
        if (keys.isEmpty()) return null

        val candidates = LinkedHashSet<FunctionHazard>()
        for (key in keys) pair[key]?.let { candidates.add(it) }
        for (hazard in pair.values) {
            if (hazard.targetName?.let(::nameKeys)?.any { it in keys } == true) {
                candidates.add(hazard)
            }
        }
        return candidates.minByOrNull { verdictRank(it.verdict) }
    }

    private fun nameKeys(name: String): Set<String> = buildSet {
        add(name.trim().uppercase())
        for (raw in name.split('/')) {
            var piece = raw.trim()
            if (piece.endsWith("()")) piece = piece.dropLast(2)
            if (piece.isNotEmpty() && piece.all { it == '_' || it.isLetterOrDigit() }) {
                add(piece.uppercase())
            }
        }
    }

    private fun verdictRank(verdict: HazardVerdict): Int = when (verdict) {
        HazardVerdict.DIVERGENT -> 0
        HazardVerdict.UNCLEAR -> 1
        HazardVerdict.CONDITIONALLY_EQUIVALENT -> 2
        HazardVerdict.NO_EQUIVALENT -> 3
        HazardVerdict.IDENTICAL -> 4
    }
}
