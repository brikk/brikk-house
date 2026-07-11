package dev.brikk.house.sql.dialects

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
 * [FunctionDef.overloads] is deliberately present-but-empty for now: signatures require a
 * built engine (Doris: per-class static SIGNATURES; see vendor/README.md).
 */
enum class FunctionKind { SCALAR, AGGREGATE, WINDOW, TABLE_VALUED, TABLE_GENERATING }

@Serializable
data class FunctionOverload(
    @SerialName("arg_types") val argTypes: List<String>,
    @SerialName("return_type") val returnType: String,
    val variadic: Boolean = false,
)

@Serializable
data class FunctionDef(
    val name: String,
    val kind: FunctionKind,
    val aliases: List<String> = emptyList(),
    val overloads: List<FunctionOverload> = emptyList(),
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
