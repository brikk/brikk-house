package dev.brikk.house.sql.compiler.analysis

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** One column of a shape as the plugin reasons about it: SQL type string + mapped Kotlin type. */
data class ShapeColumn(val name: String, val sqlType: String, val type: KType) {
    companion object {
        fun from(c: ColumnShape): ShapeColumn = ShapeColumn(c.name, c.type, TypeMap.sqlToKotlin(c.type, c.nullable))
    }
}

fun List<ShapeColumn>.toShape(): Shape = Shape(map { ColumnShape(it.name, it.sqlType) })
fun Shape.toColumns(): List<ShapeColumn> = columns.map { ShapeColumn.from(it) }

/** A `@BrikkTrait` interface: its ClassId and the columns it requires (own + inherited). */
data class TraitInfo(val classId: ClassId, val columns: List<ShapeColumn>)

/** A `Rel<...>`-typed parameter: Kotlin name, slot it feeds, and the raw type-argument name. */
data class RelParam(val name: String, val slot: String, val typeArgName: String)

/**
 * Everything the plugin derives from one `@BrikkSql` function declaration, using only
 * syntactic information (raw FIR) plus the schema catalog and the trait set.
 */
data class FunctionAnalysis(
    val packageFqName: FqName,
    val functionName: Name,
    val outClassId: ClassId,
    val dialect: String,
    /** SQL as written by the user (after trimIndent/trimMargin). */
    val sqlText: String,
    /** SQL as analyzed: headless pipes get the `FROM __src() ` prefix. */
    val fullSql: String,
    val headless: Boolean,
    val relParams: List<RelParam>,
    val scalarParams: List<String>,
    /** Type parameter name -> bound short names (raw). */
    val typeParamBounds: Map<String, List<String>>,
    /** Declared input columns per slot, as resolvable from the signature. */
    val inputs: Map<String, List<ShapeColumn>>,
    /** Output columns under the declared inputs. */
    val output: List<ShapeColumn>,
    /** True = closed full shape (`Shape`); false = minimum guarantee (`Partial`). */
    val isShape: Boolean,
    /** Traits structurally satisfied by [output]. */
    val satisfiedTraits: List<ClassId>,
    /** Non-null when analysis failed (parse error, unknown table, ...). Checker reports it. */
    val error: String? = null,
) {
    val isGeneric: Boolean get() = typeParamBounds.isNotEmpty()
    val fragment: SqlFragment get() = SqlFragment(fullSql, dialect)
}

/**
 * Syntactic view of a `@BrikkSql` function, produced by the FIR layer from raw FIR and handed
 * to [SqlAnalyzer]. Keeps the analyzer free of compiler types.
 */
data class RawFunction(
    val packageFqName: FqName,
    val name: Name,
    /** Callee short name of the `Sql.<dialect>` call, e.g. "postgres". */
    val dialect: String?,
    val sqlText: String?,
    /** (param name, raw type short name, raw type-argument short name if any). */
    val params: List<RawParam>,
    val typeParamBounds: Map<String, List<String>>,
)

data class RawParam(val name: String, val typeShortName: String, val typeArgShortName: String?)

/** Pure analysis over the catalog + traits + other functions' outputs. */
class SqlAnalyzer(
    private val catalog: ShapeCatalog,
    /** Trait short name -> info. */
    private val traitsByShortName: Map<String, TraitInfo>,
    /** Generated output class short name (e.g. "EventsInRangeOut") -> the function it belongs to. */
    private val functionsByOutName: (String) -> FunctionAnalysis?,
) {
    val traits: Collection<TraitInfo> get() = traitsByShortName.values

    fun analyze(raw: RawFunction): FunctionAnalysis {
        val outClassId = ClassId(raw.packageFqName, BrikkSqlNames.outputClassName(raw.name))
        val relParams = ArrayList<RelParam>()
        val scalarParams = ArrayList<String>()
        for (p in raw.params) {
            if (p.typeShortName == "Rel") {
                val slot = if (relParams.isEmpty()) BrikkSqlNames.SOURCE_SLOT else p.name
                relParams.add(RelParam(p.name, slot, p.typeArgShortName ?: "Partial"))
            } else {
                scalarParams.add(p.name)
            }
        }
        val sqlText = raw.sqlText?.trim().orEmpty()
        val headless = sqlText.startsWith("|>")
        val fullSql = if (headless) BrikkSqlNames.SOURCE_PREFIX + sqlText else sqlText
        val dialect = raw.dialect ?: "postgres"

        fun failed(msg: String) = FunctionAnalysis(
            raw.packageFqName, raw.name, outClassId, dialect, sqlText, fullSql, headless, relParams, scalarParams,
            raw.typeParamBounds, emptyMap(), emptyList(), isShape = false, satisfiedTraits = emptyList(), error = msg,
        )

        if (raw.dialect == null || raw.sqlText == null) {
            return failed("body must be a single Sql.<dialect>(\"...\") call with a constant string literal")
        }
        if (headless && relParams.isEmpty()) {
            return failed("headless pipe (starts with '|>') needs a Rel<...> parameter as its source")
        }

        // Declared inputs from the signature.
        val inputs = LinkedHashMap<String, List<ShapeColumn>>()
        for (rp in relParams) {
            val cols = resolveDeclaredInput(rp.typeArgName, raw.typeParamBounds)
                ?: return failed("cannot resolve input shape of parameter '${rp.name}': Rel<${rp.typeArgName}>")
            inputs[rp.slot] = cols
        }

        val output = try {
            computeOutput(fullSql, dialect, inputs)
        } catch (e: Exception) {
            return failed(e.message ?: e.toString())
        }

        val isGeneric = raw.typeParamBounds.isNotEmpty()
        val closed = !headless || closesColumnSet(fullSql, dialect)
        val isShape = !isGeneric && closed
        return FunctionAnalysis(
            raw.packageFqName, raw.name, outClassId, dialect, sqlText, fullSql, headless, relParams, scalarParams,
            raw.typeParamBounds, inputs, output, isShape, satisfiedTraits(output),
        )
    }

    /** Output columns of [fullSql] when its slots are fed by [inputs]. */
    fun computeOutput(fullSql: String, dialect: String, inputs: Map<String, List<ShapeColumn>>): List<ShapeColumn> {
        val fragment = SqlFragment(fullSql, dialect)
        val cat = ShapeCatalog(tables = catalog.tables, slots = inputs.mapValues { it.value.toShape() })
        return fragment.outputShape(cat).toColumns()
    }

    /** Re-applies [fn] to concrete call-site inputs (generic pipes). */
    fun applyTo(fn: FunctionAnalysis, inputs: Map<String, List<ShapeColumn>>): List<ShapeColumn> =
        computeOutput(fn.fullSql, fn.dialect, inputs)

    fun satisfiedTraits(output: List<ShapeColumn>): List<ClassId> =
        traitsByShortName.values.filter { satisfies(output, it) }.map { it.classId }

    fun satisfies(output: List<ShapeColumn>, trait: TraitInfo): Boolean =
        trait.columns.all { req ->
            val actual = output.firstOrNull { it.name.equals(req.name, ignoreCase = true) } ?: return@all false
            TypeMap.satisfies(actual.type, req.type)
        }

    /** Whether the pipe contains a stage that replaces the column set (SELECT / AGGREGATE). */
    private fun closesColumnSet(fullSql: String, dialect: String): Boolean {
        val ops = SqlFragment(fullSql, dialect).describe().stageOperators
        return ops.any { it == "SELECT" || it == "AGGREGATE" }
    }

    /**
     * `Rel<X>` where X is: a type parameter (-> union of its trait bounds' columns), a trait
     * short name, or another function's generated output class short name.
     */
    private fun resolveDeclaredInput(typeArgName: String, bounds: Map<String, List<String>>): List<ShapeColumn>? {
        bounds[typeArgName]?.let { boundNames ->
            val cols = LinkedHashMap<String, ShapeColumn>()
            for (b in boundNames) {
                val t = traitsByShortName[b] ?: return null
                for (c in t.columns) cols.putIfAbsent(c.name.lowercase(), c)
            }
            return cols.values.toList()
        }
        traitsByShortName[typeArgName]?.let { return it.columns }
        functionsByOutName(typeArgName)?.let { return it.output }
        if (typeArgName == "Partial" || typeArgName == "Shape") return emptyList()
        return null
    }
}
