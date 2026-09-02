package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.BrikkSqlOptions
import dev.brikk.house.sql.compiler.analysis.FunctionAnalysis
import dev.brikk.house.sql.compiler.analysis.KType
import dev.brikk.house.sql.compiler.analysis.ShapeColumn
import dev.brikk.house.sql.compiler.analysis.SqlAnalyzer
import dev.brikk.house.sql.compiler.analysis.TraitInfo
import dev.brikk.house.sql.compiler.analysis.TypeMap
import dev.brikk.house.sql.shape.DdlCatalog
import dev.brikk.house.sql.shape.ShapeCatalog
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.ClassId
import java.io.File

/** Origin key for every declaration this plugin generates. */
data object BrikkSqlGeneratedKey : GeneratedDeclarationKey()

/**
 * Per-session state shared by the FIR extensions: options, schema catalog, the trait set,
 * the analyses of all `@BrikkSql` functions (keyed by their generated output ClassId), and
 * the registry of call-site local shape classes created by the refinement extension.
 *
 * Trait and function discovery uses the predicate-based provider, whose index exists from
 * `ANNOTATIONS_FOR_PLUGINS` on — i.e. before any generation callback. Reading the
 * declarations themselves goes through [RawFir] (raw trees only).
 */
class BrikkSqlSession(session: FirSession, val options: BrikkSqlOptions) : FirExtensionSessionComponent(session) {

    val catalog: ShapeCatalog by lazy {
        val path = options.schemaPath ?: return@lazy ShapeCatalog.EMPTY
        val ddl = File(path).readText()
        DdlCatalog.fromDdl(ddl, options.schemaDialect, options.defaultSchema)
    }

    /** All `@BrikkTrait` interfaces in the module, by short name. */
    private var traitsCache: Map<String, TraitInfo>? = null
    val traitsByShortName: Map<String, TraitInfo>
        get() = traitsCache ?: computeTraits().also { if (it.isNotEmpty()) traitsCache = it }

    private fun computeTraits(): Map<String, TraitInfo> {
        val classes = session.predicateBasedProvider.getSymbolsByPredicate(TRAIT_PREDICATE)
            .filterIsInstance<FirRegularClassSymbol>()
            .associate { it.classId.shortClassName.asString() to it.fir }
        val resolved = HashMap<String, TraitInfo>()
        fun build(name: String, visiting: Set<String>): TraitInfo? {
            resolved[name]?.let { return it }
            val klass = classes[name] ?: return null
            if (name in visiting) return null
            val cols = LinkedHashMap<String, ShapeColumn>()
            for (superName in RawFir.superTypeShortNames(klass)) {
                build(superName, visiting + name)?.columns?.forEach { cols.putIfAbsent(it.name.lowercase(), it) }
            }
            for ((pName, typeShort, nullable) in RawFir.traitProperties(klass)) {
                val sql = TypeMap.kotlinShortNameToSql(typeShort) ?: "UNKNOWN"
                val classId = TypeMap.kotlinShortNameToClassId(typeShort) ?: org.jetbrains.kotlin.name.StandardClassIds.Any
                cols[pName.lowercase()] = ShapeColumn(pName, sql, KType(classId, nullable))
            }
            return TraitInfo(klass.symbol.classId, cols.values.toList()).also { resolved[name] = it }
        }
        classes.keys.forEach { build(it, emptySet()) }
        return resolved
    }

    /** `@BrikkSql` functions by their generated output ClassId. */
    // Not `lazy`: the predicate index only exists from ANNOTATIONS_FOR_PLUGINS on, and an
    // early caller (IMPORTS-phase `hasPackage`) must not pin an empty result.
    private var functionsCache: Map<ClassId, FirNamedFunctionSymbol>? = null
    val functionsByOutClassId: Map<ClassId, FirNamedFunctionSymbol>
        get() = functionsCache ?: session.predicateBasedProvider.getSymbolsByPredicate(SQL_PREDICATE)
            .filterIsInstance<FirNamedFunctionSymbol>()
            .associateBy { ClassId(it.callableId.packageName, BrikkSqlNames.outputClassName(it.name)) }
            .also { if (it.isNotEmpty()) functionsCache = it }

    private val functionsByOutShortName: Map<String, FirNamedFunctionSymbol>
        get() = functionsByOutClassId.entries.associate { it.key.shortClassName.asString() to it.value }

    val analyzer: SqlAnalyzer by lazy {
        SqlAnalyzer(catalog, traitsByShortName) { outShortName ->
            functionsByOutShortName[outShortName]?.let { analysisOf(it) }
        }
    }

    private val analyses = HashMap<FirNamedFunctionSymbol, FunctionAnalysis>()
    private val analyzing = HashSet<FirNamedFunctionSymbol>()

    fun analysisOf(symbol: FirNamedFunctionSymbol): FunctionAnalysis? {
        analyses[symbol]?.let { return it }
        if (!analyzing.add(symbol)) return null // cycle: Rel<AOut> param inside A's own chain
        try {
            val raw = RawFir.rawFunction(symbol.fir as FirNamedFunction)
            return analyzer.analyze(raw).also { analyses[symbol] = it }
        } finally {
            analyzing.remove(symbol)
        }
    }

    fun analysisOf(outClassId: ClassId): FunctionAnalysis? = functionsByOutClassId[outClassId]?.let { analysisOf(it) }

    /** Function symbol -> analysis, for the enclosing-function lookup in refinement/checkers. */
    fun analysisOfFunction(symbol: FirNamedFunctionSymbol): FunctionAnalysis? =
        if (functionsByOutClassId.containsValue(symbol)) analysisOf(symbol) else null

    // ---------------------------------------------------------------- local shape classes

    /** Columns of a class we generated (non-local or call-site local), if any. */
    fun columnsOf(classSymbol: FirRegularClassSymbol): List<ShapeColumn>? {
        classSymbol.fir.shapeColumns?.let { return it }
        return analysisOf(classSymbol.classId)?.output
    }

    private var localCounter = 0
    fun nextLocalIndex(): Int = ++localCounter

    companion object {
        val SQL_PREDICATE: LookupPredicate = LookupPredicate.create { annotated(BrikkSqlNames.BRIKK_SQL_ANNOTATION) }
        val TRAIT_PREDICATE: LookupPredicate = LookupPredicate.create { annotated(BrikkSqlNames.BRIKK_TRAIT_ANNOTATION) }
    }
}

val FirSession.brikkSql: BrikkSqlSession by FirSession.sessionComponentAccessor()

// Attributes carried on plugin-generated (local) shape classes, sandbox-style.
private object ShapeColumnsKey : FirDeclarationDataKey()
private object ShapeAnchorKey : FirDeclarationDataKey()

var FirClass.shapeColumns: List<ShapeColumn>? by FirDeclarationDataRegistry.data(ShapeColumnsKey)
var FirClass.shapeAnchor: KtSourceElement? by FirDeclarationDataRegistry.data(ShapeAnchorKey)
val FirRegularClassSymbol.shapeAnchor: KtSourceElement? by FirDeclarationDataRegistry.symbolAccessor(ShapeAnchorKey)
