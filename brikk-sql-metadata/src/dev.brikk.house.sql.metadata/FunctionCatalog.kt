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
 * [FunctionDef.overloads] is empty for catalogs whose signatures require a built engine
 * (Doris: per-class static SIGNATURES; see vendor/README.md) and populated where the
 * engine exposes them directly (DuckDB's duckdb_functions(), Trino's SHOW FUNCTIONS).
 */
enum class FunctionKind { SCALAR, AGGREGATE, WINDOW, TABLE_VALUED, TABLE_GENERATING }

@Serializable
data class FunctionOverload(
    @SerialName("arg_types") val argTypes: List<String>,
    @SerialName("return_type") val returnType: String,
    val variadic: Boolean = false,
)

/**
 * One catalog entry (a function and all its overloads).
 *
 * [kind] is normalized to the 5-value [FunctionKind] enum; [nativeKind] preserves the
 * engine-native type string when it doesn't map 1:1 (e.g. DuckDB "macro"/"table_macro"
 * normalize to SCALAR/TABLE_VALUED but keep their native kind here). Null when the
 * engine kind maps directly.
 */
@Serializable
data class FunctionDef(
    val name: String,
    val kind: FunctionKind,
    val aliases: List<String> = emptyList(),
    val overloads: List<FunctionOverload> = emptyList(),
    @SerialName("native_kind") val nativeKind: String? = null,
)

class FunctionCatalog(val functions: List<FunctionDef>) {

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
