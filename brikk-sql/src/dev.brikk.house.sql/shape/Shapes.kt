package dev.brikk.house.sql.shape

// Explicit kotlin imports: this package sits next to `dev.brikk.house.sql.ast` whose
// node classes shadow builtins when star-imported; we import only what we use.
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.dataTypeFromStr
import dev.brikk.house.sql.optimizer.normalizeName
import kotlinx.serialization.Serializable

/**
 * BRIKK-NATIVE (no sqlglot counterpart): the shape/contract model consumed by pipeline
 * construction, the future compiler plugin, and IDE tooling. A [Shape] is the ordered
 * column signature of a table, fragment output, or table-valued slot — the "(input
 * shape, body, output shape)" contract from docs/parsing-research-and-plan.md
 * ("North star: composable, parameterized pipe fragments").
 */

/** Error type for the shape layer (multi-statement fragments, bad slot bindings, ...). */
class ShapeError(message: String) : RuntimeException(message)

/**
 * One output/input column.
 *
 * [type] is the column's [DataType] rendered as base-dialect SQL (e.g. "DECIMAL(10, 2)",
 * "ARRAY<INT>", "UNKNOWN") — reconstructable by parsing the string back into a DataType
 * (see [dataTypeFromStr]). "UNKNOWN" means undeclared/unresolvable ("declared-any").
 *
 * [nullable] is the BRIKK-NATIVE tri-state nullability verdict surfaced by
 * outputShape(): true (provably nullable) / false (provably not-null) / null (unknown —
 * never guessed). Callers MAY also declare it on input shapes to feed column-nullability
 * inference. See optimizer/AnnotateNullability.kt.
 */
@Serializable
data class ColumnShape(
    val name: String,
    val type: String,
    val nullable: Boolean? = null,
)

/** Ordered column signature. */
@Serializable
data class Shape(val columns: List<ColumnShape>) {

    /** Column names, in declaration order. */
    fun names(): List<String> = columns.map { it.name }

    /**
     * Finds a column by name using [dialect]'s identifier normalization on both sides
     * (e.g. unquoted identifiers are case-insensitive in most dialects).
     */
    fun byName(name: String, dialect: String = ""): ColumnShape? {
        val d = Dialects.forName(dialect)
        val wanted = normalizeName(name, dialect = d).name
        return columns.firstOrNull { normalizeName(it.name, dialect = d).name == wanted }
    }

    /**
     * The `{column: type}` mapping used to feed a table entry of
     * `optimizer.MappingSchema` (see [ShapeCatalog]).
     */
    fun toSchemaMapping(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (c in columns) out[c.name] = c.type
        return out
    }

    /**
     * Compares `this` (the EXPECTED/declared contract) against [actual]:
     *
     *  - name matching uses [dialect] identifier normalization;
     *  - type comparison is exact on the normalized [DataType] (both type strings are
     *    parsed and compared structurally via `isType`, so "decimal(10,2)" ==
     *    "DECIMAL(10, 2)" but ARRAY<INT> != ARRAY<FLOAT>);
     *  - UNKNOWN is asymmetric: an UNKNOWN *expected* type matches anything
     *    (declared-any), but an UNKNOWN *actual* against a concrete expected type is a
     *    mismatch — the actual side cannot prove it satisfies the contract.
     *
     * BRIKK-NATIVE nullability participates ONLY when BOTH sides declare it
     * ([ColumnShape.nullable] non-null): expected=false (contract demands not-null) with
     * actual=true (provably nullable) is a mismatch, reported in
     * [ShapeComparison.nullabilityMismatches] as (expected, actual). An expected=true
     * contract accepts anything; an unknown (null) verdict on EITHER side is not compared
     * (we never penalize absent evidence). Nullability mismatches count toward
     * [ShapeVerdict.HAS_LESS] exactly like type mismatches.
     */
    fun compare(actual: Shape, dialect: String = ""): ShapeComparison {
        val d = Dialects.forName(dialect)
        val actualByName = LinkedHashMap<String, ColumnShape>()
        for (c in actual.columns) actualByName[normalizeName(c.name, dialect = d).name] = c

        val missing = mutableListOf<ColumnShape>()
        val typeMismatches = mutableListOf<Pair<ColumnShape, ColumnShape>>()
        val nullabilityMismatches = mutableListOf<Pair<ColumnShape, ColumnShape>>()
        val matchedKeys = HashSet<String>()

        for (expected in columns) {
            val key = normalizeName(expected.name, dialect = d).name
            val found = actualByName[key]
            if (found == null) {
                missing.add(expected)
                continue
            }
            matchedKeys.add(key)
            if (!typesMatch(expected.type, found.type)) {
                typeMismatches.add(expected to found)
            }
            // Nullability: only when both sides declare it. A not-null contract
            // (expected=false) is violated by a provably-nullable actual (true).
            if (expected.nullable == false && found.nullable == true) {
                nullabilityMismatches.add(expected to found)
            }
        }

        val additional = actual.columns.filter {
            normalizeName(it.name, dialect = d).name !in matchedKeys
        }

        return ShapeComparison(
            missing = missing,
            additional = additional,
            typeMismatches = typeMismatches,
            nullabilityMismatches = nullabilityMismatches,
        )
    }

    private fun typesMatch(expected: String, actual: String): Boolean {
        val expectedType = parseType(expected)
        // UNKNOWN expected = declared-any: matches whatever the actual side has.
        if (expectedType.thisArg == dev.brikk.house.sql.ast.DType.UNKNOWN) return true
        val actualType = parseType(actual)
        // UNKNOWN actual vs concrete expected falls through to a structural compare,
        // which fails — the asymmetry documented on compare().
        return actualType.isType(expectedType)
    }

    private fun parseType(type: String): DataType = try {
        dataTypeFromStr(type)
    } catch (e: Exception) {
        throw ShapeError("Cannot parse column type '$type': ${e.message}")
    }

    companion object {
        val EMPTY = Shape(emptyList())

        /** Builder convenience: `Shape.of("a" to "INT", "b" to "TEXT")`. */
        fun of(vararg columns: Pair<String, String>): Shape =
            Shape(columns.map { (n, t) -> ColumnShape(n, t) })
    }
}

/** Three-way verdict for [Shape.compare] (see the North-star plan doc). */
enum class ShapeVerdict {
    /** Actual provides every expected column with a matching type, nothing else. */
    SATISFIES,

    /** All expected columns are satisfied, but actual carries extra columns. */
    HAS_ADDITIONAL,

    /** Actual is insufficient: expected columns are missing or their types mismatch. */
    HAS_LESS,
}

/**
 * Result of [Shape.compare]. [typeMismatches] and [nullabilityMismatches] pairs are
 * (expected, actual).
 *
 * [nullabilityMismatches] is a dedicated list (not folded into [typeMismatches]) so the
 * two failure kinds stay distinguishable; it defaults to empty for @Serializable wire
 * compatibility with persisted comparisons that predate nullability.
 *
 * Verdict semantics: missing columns OR type mismatches OR nullability mismatches ->
 * [ShapeVerdict.HAS_LESS] (a not-null contract broken by a nullable actual cannot satisfy
 * the contract any more than a wrong type or an absent column can); otherwise extra
 * columns -> [ShapeVerdict.HAS_ADDITIONAL]; otherwise [ShapeVerdict.SATISFIES].
 */
@Serializable
data class ShapeComparison(
    val missing: List<ColumnShape>,
    val additional: List<ColumnShape>,
    val typeMismatches: List<Pair<ColumnShape, ColumnShape>>,
    val nullabilityMismatches: List<Pair<ColumnShape, ColumnShape>> = emptyList(),
) {
    val verdict: ShapeVerdict
        get() = when {
            missing.isNotEmpty() || typeMismatches.isNotEmpty() ||
                nullabilityMismatches.isNotEmpty() -> ShapeVerdict.HAS_LESS
            additional.isNotEmpty() -> ShapeVerdict.HAS_ADDITIONAL
            else -> ShapeVerdict.SATISFIES
        }
}

/**
 * The shapes a fragment resolves against:
 *
 *  - [tables]: physical table name -> shape. Dotted names ("db.t", "cat.db.t") nest
 *    into the schema mapping; all entries must share one nesting depth
 *    (MappingSchema constraint).
 *  - [slots]: table-valued slot name -> shape, binding the fragment's `FROM slot(...)`
 *    TVF-style inputs (see [SqlFragment.tableSlots]).
 */
@Serializable
data class ShapeCatalog(
    val tables: Map<String, Shape>,
    val slots: Map<String, Shape> = emptyMap(),
) {
    companion object {
        val EMPTY = ShapeCatalog(emptyMap())
    }
}
