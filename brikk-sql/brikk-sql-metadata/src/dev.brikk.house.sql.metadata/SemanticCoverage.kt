package dev.brikk.house.sql.metadata

/** How a registered dialect participates in semantic certification. */
enum class EngineClass {
    REAL_ENGINE,
    TRANSLATION_ONLY,
    BRIKK_NATIVE,
}

/** Closed-world status of an ordered source-to-target semantic scope. */
enum class PairCoverage {
    /** Every applicable catalog relationship is evidence-classified and tested. */
    COMPLETE,

    /** Some concepts have evidence; every other encountered function must be refused. */
    PARTIAL,

    /** Two real engines with no semantic evidence for the pair. */
    UNRESEARCHED,

    /** At least one endpoint is not a real engine oracle. */
    NOT_APPLICABLE,
}

/**
 * Evidence scope for one ordered engine pair. [coveredConcepts] contains source-side
 * lookup keys, including evidence-backed aliases. It is a closed set: under [PARTIAL],
 * a function whose parsed/source-rendered/target-rendered keys do not intersect this set
 * is explicitly uncovered and certification must refuse it.
 */
data class SemanticPairScope(
    val source: String,
    val target: String,
    val coverage: PairCoverage,
    val sourceCatalog: String,
    val targetCatalog: String,
    val scope: String,
    val evidence: String,
    val coveredConcepts: Set<String>,
)

/** Explicit pair/scope metadata generated from the live-probe hazard sources. */
object SemanticCoverage {

    val engineClass: Map<String, EngineClass> = mapOf(
        "" to EngineClass.TRANSLATION_ONLY,
        "sqlglot" to EngineClass.TRANSLATION_ONLY,
        "mysql" to EngineClass.REAL_ENGINE,
        "doris" to EngineClass.REAL_ENGINE,
        "starrocks" to EngineClass.REAL_ENGINE,
        "presto" to EngineClass.REAL_ENGINE,
        "trino" to EngineClass.REAL_ENGINE,
        "duckdb" to EngineClass.REAL_ENGINE,
        "postgres" to EngineClass.REAL_ENGINE,
        "clickhouse" to EngineClass.REAL_ENGINE,
        "hive" to EngineClass.REAL_ENGINE,
        "spark2" to EngineClass.REAL_ENGINE,
        "spark" to EngineClass.REAL_ENGINE,
        "bigquery" to EngineClass.REAL_ENGINE,
        "datafusion" to EngineClass.BRIKK_NATIVE,
    )

    val pairScopes: Map<Pair<String, String>, SemanticPairScope>
        get() = GENERATED_SEMANTIC_PAIR_SCOPES

    private fun classOf(dialect: String): EngineClass =
        engineClass[dialect.lowercase().trim()] ?: EngineClass.REAL_ENGINE

    fun scope(source: String, target: String): SemanticPairScope {
        val s = source.lowercase().trim()
        val t = target.lowercase().trim()
        if (s == t) {
            return SemanticPairScope(
                s, t, PairCoverage.COMPLETE, "same engine", "same engine",
                "identity", "same-dialect identity", emptySet(),
            )
        }
        if (classOf(s) != EngineClass.REAL_ENGINE || classOf(t) != EngineClass.REAL_ENGINE) {
            return SemanticPairScope(
                s, t, PairCoverage.NOT_APPLICABLE, "not applicable", "not applicable",
                "non-engine endpoint", "engineClass metadata", emptySet(),
            )
        }
        return pairScopes[s to t] ?: SemanticPairScope(
            s, t, PairCoverage.UNRESEARCHED, "unknown", "unknown",
            "no semantic behavior evidence", "no hazards source", emptySet(),
        )
    }

    fun coverage(source: String, target: String): PairCoverage = scope(source, target).coverage

    /** True when at least one canonical/surface/rendered key resolves inside this scope. */
    fun coversConcept(source: String, target: String, keys: Iterable<String>): Boolean {
        val scope = scope(source, target)
        if (scope.coverage == PairCoverage.COMPLETE) return true
        if (scope.coverage != PairCoverage.PARTIAL) return false
        return keys.any { it.trim().uppercase() in scope.coveredConcepts }
    }
}
