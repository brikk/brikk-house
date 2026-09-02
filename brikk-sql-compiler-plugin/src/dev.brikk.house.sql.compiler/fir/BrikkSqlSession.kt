package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.BrikkSqlOptions
import dev.brikk.house.sql.compiler.analysis.FunctionAnalysis
import dev.brikk.house.sql.compiler.analysis.KType
import dev.brikk.house.sql.compiler.analysis.RawFunction
import dev.brikk.house.sql.compiler.analysis.PluginGuard
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
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.extensions.FirExtensionSessionComponent
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.name.Name
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

    // ---------------------------------------------------------------- schema catalog
    //
    // Loading must never throw: the IDE re-runs FIR resolution on every keystroke from several
    // highlighting passes at once, and an exception here surfaces as a resolve failure of the
    // declaration under analysis (not as a plugin diagnostic). A load failure is therefore held
    // as [LoadedCatalog.error] and attached to every @BrikkSql function's analysis, which the
    // checker reports through SQL_ANALYSIS_FAILED and the refinement treats as "no typing".

    private class LoadedCatalog(val catalog: ShapeCatalog, val error: String?)

    private var loadedCatalog: LoadedCatalog? = null

    /**
     * Resolves and loads the schema file. `anchorFilePath` is the source file of whichever
     * declaration first needs the catalog; a relative `schema=` path that does not exist relative
     * to the working directory (the IDE's cwd is not the project root, the CLI's is) is retried
     * against that file's ancestors, which finds `<project>/<relative path>` in both.
     */
    private fun loadCatalog(anchorFilePath: String?): LoadedCatalog {
        loadedCatalog?.let { return it }
        val path = options.schemaPath
        val result = if (path == null) {
            LoadedCatalog(ShapeCatalog.EMPTY, null)
        } else {
            val file = resolveSchemaFile(path, anchorFilePath)
            if (file == null) {
                LoadedCatalog(
                    ShapeCatalog.EMPTY,
                    "schema file not found: '$path' (looked relative to the working directory " +
                        "'${File("").absolutePath}'" +
                        (anchorFilePath?.let { " and the ancestors of '$it'" } ?: "") + ")",
                )
            } else {
                try {
                    LoadedCatalog(DdlCatalog.fromDdl(file.readText(), options.schemaDialect, options.defaultSchema), null)
                } catch (e: Exception) {
                    LoadedCatalog(ShapeCatalog.EMPTY, "schema file '${file.path}' could not be loaded: ${e.message ?: e}")
                }
            }
        }
        // A failure without an anchor may still succeed once a caller can supply one; do not pin it.
        if (result.error == null || anchorFilePath != null) loadedCatalog = result
        return result
    }

    private fun resolveSchemaFile(path: String, anchorFilePath: String?): File? {
        val direct = File(path)
        if (direct.isAbsolute) return direct.takeIf { it.isFile }
        if (direct.isFile) return direct
        // The IDE creates many short-lived sessions for one project (dangling/in-memory file
        // sessions have no source path at all); once any session has located the file, reuse it.
        RESOLVED_SCHEMAS[path]?.takeIf { it.isFile }?.let { return it }
        val anchors = buildList {
            anchorFilePath?.let { add(it) }
            addAll(knownSourcePaths())
        }
        for (anchor in anchors) {
            var dir: File? = File(anchor).absoluteFile.parentFile
            while (dir != null) {
                val candidate = File(dir, path)
                if (candidate.isFile) {
                    RESOLVED_SCHEMAS[path] = candidate
                    return candidate
                }
                dir = dir.parentFile
            }
        }
        return null
    }

    /** Source paths of the module's files that the provider can enumerate (anchors for the schema search). */
    private fun knownSourcePaths(): List<String> = try {
        val packages = functionsByOutClassId.values.mapTo(HashSet()) { it.callableId.packageName }
        packages.flatMap { pkg -> session.firProvider.getFirFilesByPackage(pkg) }.mapNotNull { it.sourceFile?.path }
    } catch (e: Exception) {
        emptyList()
    }

    /** The FIR file a symbol is declared in, if the provider knows it (source path = schema anchor; imports = const lookup). */
    private fun containerFileOf(symbol: FirNamedFunctionSymbol): FirFile? = try {
        session.firProvider.getFirCallableContainerFile(symbol)
    } catch (e: Exception) {
        PluginGuard.note("container file lookup failed for '${symbol.name}'") { e.toString() }
        null
    }

    private fun noteNoAnchor(symbol: FirNamedFunctionSymbol) =
        PluginGuard.note("no source file for '${symbol.name}'") { "schema path resolves against cwd only" }

    /** The schema catalog (empty if none configured or it failed to load; see [analyzer]). */
    val catalog: ShapeCatalog get() = loadCatalog(null).catalog

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

    private var analyzerCache: SqlAnalyzer? = null

    private fun analyzerFor(anchorFilePath: String?): SqlAnalyzer {
        analyzerCache?.let { return it }
        val loaded = loadCatalog(anchorFilePath)
        val analyzer = SqlAnalyzer(loaded.catalog, traitsByShortName, loaded.error) { outShortName ->
            functionsByOutShortName[outShortName]?.let { analysisOf(it) }
        }
        if (loaded.error == null || anchorFilePath != null) analyzerCache = analyzer
        return analyzer
    }

    val analyzer: SqlAnalyzer get() = analyzerFor(null)

    private val analyses = HashMap<FirNamedFunctionSymbol, FunctionAnalysis>()
    private val analyzing = HashSet<FirNamedFunctionSymbol>()

    fun analysisOf(symbol: FirNamedFunctionSymbol): FunctionAnalysis? {
        analyses[symbol]?.let { return it }
        if (!analyzing.add(symbol)) return null // cycle: Rel<AOut> param inside A's own chain
        try {
            val containerFile = containerFileOf(symbol)
            val analyzer = analyzerFor(containerFile?.sourceFile?.path.also { if (it == null) noteNoAnchor(symbol) })
            val analysis = try {
                analyzer.analyze(RawFir.rawFunction(symbol.fir as FirNamedFunction, session, containerFile))
            } catch (e: Throwable) {
                // Reading the raw declaration failed (IDE: partially built FIR). Report, don't throw.
                PluginGuard.recoverable(e, "analysisOf(${symbol.name})")
                val stub = RawFunction(symbol.callableId.packageName, symbol.name, null, null, emptyList(), emptyMap())
                analyzer.failed(stub, "internal error reading '${symbol.name}': $e")
            }
            // Do not pin an analysis that failed only because the catalog could not be located
            // yet (analyzer not pinned either); a later caller may supply a usable anchor file.
            if (analysis.error == null || analyzerCache != null) analyses[symbol] = analysis
            return analysis
        } finally {
            analyzing.remove(symbol)
        }
    }

    fun analysisOf(outClassId: ClassId): FunctionAnalysis? =
        (functionsByOutClassId[outClassId] ?: functionByOutClassIdFallback(outClassId))?.let { analysisOf(it) }

    /**
     * `XyzOut` -> the `@BrikkSql fun xyz` in the same package, by name through the symbol
     * provider. Used when the predicate index does not list it: some IDE sessions (dangling /
     * in-memory files) have an empty predicate-based provider although the declarations resolve.
     */
    private fun functionByOutClassIdFallback(outClassId: ClassId): FirNamedFunctionSymbol? {
        val short = outClassId.shortClassName.asString()
        if (!short.endsWith("Out") || short.length <= 3) return null
        val fnName = Name.identifier(short.removeSuffix("Out").replaceFirstChar { it.lowercase() })
        return try {
            session.symbolProvider.getTopLevelCallableSymbols(outClassId.packageFqName, fnName)
                .filterIsInstance<FirNamedFunctionSymbol>()
                .firstOrNull { it.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, session) }
        } catch (e: Exception) {
            null
        }
    }

    /** Function symbol -> analysis, for the enclosing-function lookup in refinement/checkers. */
    fun analysisOfFunction(symbol: FirNamedFunctionSymbol): FunctionAnalysis? =
        if (functionsByOutClassId.containsValue(symbol) || symbol.isBrikkSqlFunction()) analysisOf(symbol) else null

    /**
     * `@BrikkSql` on the symbol, resolved or not: some IDE sessions hand us declarations whose
     * annotations are still raw (`FirUserTypeRef`), where `hasAnnotation(classId)` is false.
     */
    private fun FirNamedFunctionSymbol.isBrikkSqlFunction(): Boolean {
        if (hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, session)) return true
        val short = BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID.shortClassName.asString()
        return fir.annotations.any { (it.annotationTypeRef as? FirUserTypeRef)?.qualifier?.lastOrNull()?.name?.asString() == short }
    }

    // ---------------------------------------------------------------- local shape classes

    /** Columns of a class we generated (non-local or call-site local), if any. */
    fun columnsOf(classSymbol: FirRegularClassSymbol): List<ShapeColumn>? {
        classSymbol.fir.shapeColumns?.let { return it }
        return analysisOf(classSymbol.classId)?.output
    }

    private var localCounter = 0
    fun nextLocalIndex(): Int = ++localCounter

    companion object {
        /** Relative `schema=` option -> file located by some session of this process; see [resolveSchemaFile]. */
        private val RESOLVED_SCHEMAS = java.util.concurrent.ConcurrentHashMap<String, File>()

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
