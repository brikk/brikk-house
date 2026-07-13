package dev.brikk.house.sql.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * STABLE ACCESSOR CONTRACT
 *
 * `brikk-sql-metadata` is the featherweight, dependency-light module external consumers
 * (e.g. the doris-intellij plugin) embed to get per-dialect function catalogs WITHOUT the
 * transpiler. Its only dependency is kotlinx-serialization-json. Keep the API surface
 * small and deliberate:
 *
 *  - [FunctionKind], [FunctionOverload], [FunctionDef], [FunctionCatalog]
 *  - the generated per-dialect catalog vals (DORIS_FUNCTION_CATALOG, ...)
 *
 * Changes here are API changes for the plugin — additive only, and prefer defaulted
 * fields (serialization stays backward-compatible for persisted JSON).
 */

/**
 * A dialect's built-in function catalog: the engine's real registered functions, as opposed
 * to sqlglot's translation registry (which only models functions that need cross-dialect
 * rewriting — ~7 Doris-specific entries vs the 800+ Doris actually registers).
 *
 * Consumers:
 *  - brikk-sql: engine-exact slot detection (a known function in table position is NOT a
 *    table-valued fragment slot), future UDF/builtin return-type inference and validation.
 *  - External tooling (e.g. the doris-intellij plugin): completion/validation source via
 *    the Kotlin API or [FunctionCatalog.toJson].
 *
 * [FunctionDef.overloads] is populated for all three catalogs: DuckDB/Trino from the
 * engine's own registry dumps (duckdb_functions(), SHOW FUNCTIONS), Doris statically
 * extracted from each function class's SIGNATURES field
 * (tools/extract_doris_signatures.py -> vendor/data/doris-signatures.json). Doris
 * functions whose class computes signatures dynamically (all table-valued functions,
 * rank-like window functions, ...) keep empty overloads.
 */
enum class FunctionKind { SCALAR, AGGREGATE, WINDOW, TABLE_VALUED, TABLE_GENERATING }

/**
 * How a function's result nullability relates to its arguments — a per-(function, engine)
 * semantic fact carried by the catalog (NOT by AST nodes; see the design note in brikk-sql).
 *
 * Engine-construct mapping (the source of each verdict is static engine metadata, never a
 * guess — anything not directly extractable stays [UNKNOWN]):
 *
 *  - Doris (Nereids ComputeNullable marker interfaces, walked over each function class's
 *    implements clause + superclass chain by tools/extract_doris_signatures.py):
 *      - `PropagateNullable`    -> [STRICT] ("nullable if any child is nullable" — Doris's
 *        planning-level form of NULL-in/NULL-out propagation)
 *      - `AlwaysNullable`       -> [ALWAYS_NULLABLE]
 *      - `AlwaysNotNullable`    -> [NEVER_NULL]
 *      - a custom `nullable()` override (Coalesce, Lag, If, ...) -> [UNKNOWN] with
 *        [SemanticProfile.notes] = "doris: custom nullable() override" (the engine computes
 *        it from argument shapes at plan time; not represented by a marker)
 *      - any other `*Nullable*` marker interface (e.g. `PropagateNullableOnDateLikeV2Args`,
 *        which exists in some Doris versions but NOT at the pinned checkout) -> [UNKNOWN]
 *        with the verbatim interface name in notes. It means "nullable if any DATE-like arg
 *        is nullable" — argument-subset propagation, which [STRICT] would over-promise for
 *        the non-date args, so no dedicated member is added until the pin ships it.
 *      - table-valued / table-generating functions: no mode (row-set producers; Doris's
 *        TableValuedFunction.nullable() throws — scalar nullability is not applicable).
 *  - DuckDB: duckdb_functions() exposes no null-handling column (checked v1.5.4: only
 *    has_side_effects/stability, neither is null propagation) -> all [UNKNOWN].
 *  - Trino: SHOW FUNCTIONS exposes nothing -> all [UNKNOWN].
 *
 * [SKIPS_NULLS] (aggregate-style "NULL inputs are ignored, result non-null-from-nulls") is
 * declared for evidence-based population (e.g. the semantic hazard DB) — no engine-side
 * static source maps onto it yet.
 */
enum class NullPropagation {
    /** NULL in any argument makes the result NULL (equivalently: result nullable iff some argument is). */
    STRICT,

    /** NULL inputs are skipped rather than propagated (typical aggregate behavior). */
    SKIPS_NULLS,

    /** Result is nullable regardless of argument nullability (e.g. parse-style functions). */
    ALWAYS_NULLABLE,

    /** Result is never NULL (e.g. count). */
    NEVER_NULL,

    /** No engine-side evidence — the honest default. */
    UNKNOWN,
}

/**
 * Per-(function, engine) semantic facts beyond the type signature. Kept deliberately
 * minimal and extensible: every field has a default so persisted JSON stays
 * backward-compatible as facts are added (collation/timezone sensitivity flags are NOT
 * modeled yet — they need per-function evidence no engine exposes statically).
 *
 * Argument names are NOT here: DuckDB (the only engine exposing them) names parameters
 * per overload, and 122 of its 286 multi-overload functions use different names across
 * overloads (round(x), round(x, precision), ...) — so names live on
 * [FunctionOverload.argNames], the one home that can represent both engines' shapes.
 *
 * [notes] preserves extractor oddities verbatim (e.g. Doris custom `nullable()`
 * overrides, unmapped marker interfaces) — human-readable provenance, not machine API.
 */
@Serializable
data class SemanticProfile(
    @SerialName("null_propagation") val nullPropagation: NullPropagation = NullPropagation.UNKNOWN,
    val notes: String? = null,
)

/**
 * [argNames] are the engine-declared parameter names for THIS overload, in [argTypes]
 * order; null when the engine does not expose names (Doris SIGNATURES, Trino SHOW
 * FUNCTIONS, and some DuckDB rows). Names are verbatim from the engine — DuckDB includes
 * generic ones (col0, col1) and lambda shapes (`lambda(x)`).
 */
@Serializable
data class FunctionOverload(
    @SerialName("arg_types") val argTypes: List<String>,
    @SerialName("return_type") val returnType: String,
    val variadic: Boolean = false,
    @SerialName("arg_names") val argNames: List<String>? = null,
)

/**
 * One catalog entry (a function and all its overloads).
 *
 * [kind] is normalized to the 5-value [FunctionKind] enum; [nativeKind] preserves the
 * engine-native type string when it doesn't map 1:1 (e.g. DuckDB "macro"/"table_macro"
 * normalize to SCALAR/TABLE_VALUED but keep their native kind here). Null when the
 * engine kind maps directly.
 *
 * [sinceVersion] is intended as "the first engine version that ships the function" (for
 * version-gated completion in external tooling), but for Doris it is honestly only "the
 * first apache/doris-website version tier that DOCUMENTS the function" — no engine or
 * docs source carries a true introduced-in fact (see
 * tools/extract_doris_since_versions.py and vendor/README.md for the extraction method
 * and its limitations). Null when doris-website has no matching doc at the pinned clone
 * (undocumented/legacy function) or when its only doc lives in the unreleased/dev tree
 * (not yet in a shipped version). Always null for DuckDB/Trino: neither exposes any
 * version-introduced metadata the generators can read.
 *
 * [profile] carries per-engine semantic facts (see [SemanticProfile]); null when the
 * engine exposes none for this function (all Trino/DuckDB defs currently; Doris defs
 * whose class carries no ComputeNullable marker). Null and an all-defaults profile mean
 * the same thing semantically — generators emit null to keep the catalogs lean.
 */
@Serializable
data class FunctionDef(
    val name: String,
    val kind: FunctionKind,
    val aliases: List<String> = emptyList(),
    val overloads: List<FunctionOverload> = emptyList(),
    @SerialName("native_kind") val nativeKind: String? = null,
    @SerialName("since_version") val sinceVersion: String? = null,
    val profile: SemanticProfile? = null,
)

/**
 * [grammarBuiltins] are function-shaped names the engine parses at the GRAMMAR (or
 * parser-special-form) level rather than registering in its function registry — e.g.
 * Trino's COALESCE/CAST/EXTRACT (absent from `SHOW FUNCTIONS`; dedicated SqlBase.g4
 * alternatives or AstBuilder special forms). They are engine-grammar knowledge, not
 * registry entries: uppercase names, NOT part of [functions] and NOT serialized by
 * [toJson] (persisted catalogs stay pure registry dumps). Use [isKnown] when asking
 * "would this name reach the engine unrecognized?" — [contains]/[get] stay
 * registry-only.
 */
class FunctionCatalog(
    val functions: List<FunctionDef>,
    val grammarBuiltins: Set<String> = emptySet(),
) {

    private val byName: Map<String, FunctionDef> = buildMap {
        for (def in functions) {
            put(def.name.uppercase(), def)
            for (alias in def.aliases) put(alias.uppercase(), def)
        }
    }

    val size: Int get() = functions.size

    /** Case-insensitive lookup by primary name or alias. */
    operator fun get(name: String): FunctionDef? = byName[name.uppercase()]

    operator fun contains(name: String): Boolean = byName.containsKey(name.uppercase())

    /**
     * True when the engine recognizes [name] at all: registered in the function
     * registry ([contains], aliases included) OR parsed at the grammar level
     * ([grammarBuiltins]). Case-insensitive.
     */
    fun isKnown(name: String): Boolean =
        contains(name) || name.uppercase() in grammarBuiltins

    /** True when [name] is a registered table-valued / table-generating function. */
    fun isTableFunction(name: String): Boolean =
        get(name)?.kind in TABLE_KINDS

    fun toJson(pretty: Boolean = false): String =
        (if (pretty) PRETTY else PLAIN).encodeToString(ListSerializer(FunctionDef.serializer()), functions)

    private companion object {
        val TABLE_KINDS = setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING)
        val PLAIN = Json
        val PRETTY = Json { prettyPrint = true }
    }
}
