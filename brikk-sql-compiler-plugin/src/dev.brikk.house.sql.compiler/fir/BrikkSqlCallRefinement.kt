package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.analysis.FunctionAnalysis
import dev.brikk.house.sql.compiler.analysis.KType
import dev.brikk.house.sql.compiler.analysis.ShapeColumn
import dev.brikk.house.sql.compiler.analysis.rethrowIfCancellation
import dev.brikk.house.sql.compiler.analysis.TypeMap
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceElementOffsetStrategy
import org.jetbrains.kotlin.fakeElement
import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirFunctionTarget
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.EmptyDeprecationsProvider
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.InlineStatus
import org.jetbrains.kotlin.fir.declarations.builder.buildAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.buildResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.builder.buildAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildBlock
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.expressions.builder.buildReturnExpression
import org.jetbrains.kotlin.fir.extensions.FirExtensionApiInternals
import org.jetbrains.kotlin.fir.extensions.FirFunctionCallRefinementExtension
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.references.resolved
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.calls.candidate.CallInfo
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagWithFixedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.builder.buildTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirTransformer
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * Option-C typing: changes the type of two kinds of calls.
 *
 * 1. `Sql.<dialect>("...")` inside a `@BrikkSql` function -> `Rel<<Fn>Out>`, where `<Fn>Out`
 *    is the non-local interface produced by [ShapeDeclarationGenerator]. With the return type
 *    omitted, the function's inferred type follows.
 * 2. A call to a *generic* `@BrikkSql` pipe (`extractEvent(rel)`) -> `Rel<Local>` where
 *    `Local` is a call-site local `Shape` whose columns are the argument's real columns run
 *    through the pipe, with every structurally satisfied `@BrikkTrait` as a supertype.
 *
 * Mechanics follow the compiler's plugin-sandbox `DataFrameLikeCallsRefinementExtension`;
 * see docs/RESEARCH-fir-refinement-and-generation.md.
 */
@OptIn(FirExtensionApiInternals::class)
class BrikkSqlCallRefinement(session: FirSession) : FirFunctionCallRefinementExtension(session) {

    private val brikk get() = session.brikkSql

    /** Local classes created in [intercept], keyed by the copied callee symbol, consumed in [transform]. */
    private val pending = HashMap<FirNamedFunctionSymbol, FirRegularClass>()

    /** Local classes by name, for [restoreSymbol]. */
    private val localsByName = HashMap<Name, FirRegularClassSymbol>()

    // Boundary: a refinement that throws poisons resolution of the whole enclosing declaration
    // (and in the IDE, every highlighting pass that touches it). `null` = "no refinement", so the
    // call keeps its declared type and the checkers get to report whatever went wrong.
    override fun intercept(callInfo: CallInfo, symbol: FirNamedFunctionSymbol): CallReturnType? = try {
        when {
            symbol.hasAnnotation(BrikkSqlNames.BRIKK_SQL_DIALECT_ANNOTATION_CLASS_ID, session) ->
                interceptSqlLiteral(callInfo)
            symbol.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, session) && symbol.typeParameterSymbols.isNotEmpty() ->
                interceptGenericPipe(callInfo, symbol)
            else -> null
        }
    } catch (e: Exception) {
        rethrowIfCancellation(e)
        null
    }

    private fun interceptSqlLiteral(callInfo: CallInfo): CallReturnType? {
        val enclosing = callInfo.containingDeclarations.filterIsInstance<FirNamedFunction>()
            .lastOrNull { it.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, session) } ?: return null
        val analysis = brikk.analysisOfFunction(enclosing.symbol) ?: return null
        if (analysis.error != null) return null
        val outType = analysis.outClassId.constructClassLikeType(emptyArray(), isMarkedNullable = false)
        return CallReturnType(buildResolvedTypeRef { coneType = relOf(outType) })
    }

    private fun interceptGenericPipe(callInfo: CallInfo, symbol: FirNamedFunctionSymbol): CallReturnType? {
        val analysis = brikk.analysisOfFunction(symbol) ?: return null
        if (analysis.error != null) return null

        // Concrete input columns from the argument types, positionally matched to Rel parameters.
        val paramNames = symbol.valueParameterSymbols.map { it.name.asString() }
        val inputs = LinkedHashMap<String, List<ShapeColumn>>()
        for (rp in analysis.relParams) {
            val index = paramNames.indexOf(rp.name)
            val arg = callInfo.arguments.getOrNull(index) ?: return null
            val argType = arg.resolvedType as? ConeClassLikeType ?: return null
            if (argType.classId != BrikkSqlNames.REL_CLASS_ID) return null
            val shapeType = argType.typeArguments.firstOrNull()?.type as? ConeClassLikeType ?: return null
            val shapeSymbol = shapeType.toRegularClassSymbol(session) ?: return null
            inputs[rp.slot] = columnsOfShapeClass(shapeSymbol) ?: return null
        }

        val output = try {
            brikk.analyzer.applyTo(analysis, inputs)
        } catch (e: Exception) {
            return null
        }
        val local = buildLocalShapeClass(output, analysis, callInfo.callSite.source)
        val localType = ConeClassLikeTypeImpl(
            ConeClassLikeLookupTagWithFixedSymbol(local.symbol.classId, local.symbol), emptyArray<ConeTypeProjection>(), isMarkedNullable = false,
        )
        return CallReturnType(buildResolvedTypeRef { coneType = relOf(localType) }) { copied ->
            pending[copied] = local
        }
    }

    /** Columns of any class usable as a `Rel` type argument: plugin-generated, or a user interface. */
    private fun columnsOfShapeClass(symbol: FirRegularClassSymbol): List<ShapeColumn>? {
        brikk.columnsOf(symbol)?.let { return it }
        // User-declared interface (a trait or hand-written shape): resolved property types.
        val cols = LinkedHashMap<String, ShapeColumn>()
        for (superRef in symbol.fir.superTypeRefs) {
            val superSym = superRef.coneType.toRegularClassSymbol(session) ?: continue
            if (superSym.classId == BrikkSqlNames.SHAPE_CLASS_ID || superSym.classId == BrikkSqlNames.PARTIAL_CLASS_ID) continue
            columnsOfShapeClass(superSym)?.forEach { cols.putIfAbsent(it.name.lowercase(), it) }
        }
        for (p in symbol.fir.declarations.filterIsInstance<FirProperty>()) {
            val type = p.returnTypeRef.coneType
            val classId = type.classId ?: continue
            val sql = TypeMap.kotlinClassIdToSql(classId) ?: "UNKNOWN"
            cols[p.name.asString().lowercase()] = ShapeColumn(p.name.asString(), sql, KType(classId, type.isMarkedNullable))
        }
        return cols.values.toList()
    }

    private fun buildLocalShapeClass(columns: List<ShapeColumn>, analysis: FunctionAnalysis, callSource: KtSourceElement?): FirRegularClass {
        val name = Name.identifier("${analysis.outClassId.shortClassName.asString()}\$${brikk.nextLocalIndex()}")
        val classId = ClassId(CallableId.PACKAGE_FQ_NAME_FOR_LOCAL, FqName.topLevel(name), isLocal = true)
        val symbol = FirRegularClassSymbol(classId)
        val traits = brikk.analyzer.satisfiedTraits(columns)
        val klass = buildRegularClass {
            // Declaration checkers require a source; make it a distinct zero-width fake range
            // at the call so it never collides with the wrapping lambda's source.
            source = callSource?.fakeElement(
                CompilerCompat.pluginGenerated,
                KtSourceElementOffsetStrategy.Custom.Initialized(callSource.startOffset, callSource.startOffset),
            )
            resolvePhase = FirResolvePhase.BODY_RESOLVE
            moduleData = session.moduleData
            origin = FirDeclarationOrigin.Plugin(BrikkSqlGeneratedKey)
            status = FirResolvedDeclarationStatusImpl(Visibilities.Local, Modality.ABSTRACT, EffectiveVisibility.Local)
            deprecationsProvider = EmptyDeprecationsProvider
            // Local interfaces are illegal in Kotlin; an abstract class can be local and can
            // still implement Shape + the traits.
            classKind = ClassKind.CLASS
            scopeProvider = FirKotlinScopeProvider()
            this.name = name
            this.symbol = symbol
            superTypeRefs += buildResolvedTypeRef {
                coneType = BrikkSqlNames.SHAPE_CLASS_ID.constructClassLikeType(emptyArray(), isMarkedNullable = false)
            }
            for (trait in traits) {
                superTypeRefs += buildResolvedTypeRef { coneType = trait.constructClassLikeType(emptyArray(), isMarkedNullable = false) }
            }
        }
        klass.shapeColumns = columns
        localsByName[name] = symbol
        return klass
    }

    override fun transform(call: FirFunctionCall, originalSymbol: FirNamedFunctionSymbol): FirFunctionCall {
        val copied = call.calleeReference.resolved?.toResolvedNamedFunctionSymbol()
        val local = copied?.let { pending.remove(it) }

        // The copied function does not exist in FIR: point the callee back at the real one.
        call.transformCalleeReference(object : FirTransformer<Nothing?>() {
            override fun <E : FirElement> transformElement(element: E, data: Nothing?): E {
                @Suppress("UNCHECKED_CAST")
                return if (element is FirResolvedNamedReference) {
                    buildResolvedNamedReference {
                        source = element.source
                        name = element.name
                        resolvedSymbol = originalSymbol
                    } as E
                } else element
            }
        }, null)

        if (local == null) return call
        local.shapeAnchor = call.source
        return wrapInRun(call, local)
    }

    /** `call` => `run { <local class>; call }` so the local class lives in the FIR tree. */
    private fun wrapInRun(call: FirFunctionCall, local: FirRegularClass): FirFunctionCall {
        val runSymbol = findRun()
        val blockParam = runSymbol.valueParameterSymbols[0]
        val returnType = call.resolvedType
        val originalSource = call.calleeReference.source

        val lambda = buildAnonymousFunctionExpression {
            source = call.source?.fakeElement(CompilerCompat.pluginGenerated)
            val fSymbol = FirAnonymousFunctionSymbol()
            val target = FirFunctionTarget(null, isLambda = true)
            isTrailingLambda = true
            anonymousFunction = buildAnonymousFunction {
                source = call.source?.fakeElement(CompilerCompat.pluginGenerated)
                resolvePhase = FirResolvePhase.BODY_RESOLVE
                moduleData = session.moduleData
                origin = FirDeclarationOrigin.Plugin(BrikkSqlGeneratedKey)
                status = FirResolvedDeclarationStatusImpl(Visibilities.Local, Modality.FINAL, EffectiveVisibility.Local)
                deprecationsProvider = EmptyDeprecationsProvider
                returnTypeRef = buildResolvedTypeRef { coneType = returnType }
                body = buildBlock {
                    coneTypeOrNull = returnType
                    statements += local
                    statements += buildReturnExpression {
                        result = call
                        this.target = target
                    }
                }
                this.symbol = fSymbol
                isLambda = true
                hasExplicitParameterList = false
                typeRef = buildResolvedTypeRef {
                    coneType = ConeClassLikeTypeImpl(
                        ConeClassLikeLookupTagImpl(ClassId(FqName("kotlin"), Name.identifier("Function0"))),
                        typeArguments = arrayOf(returnType),
                        isMarkedNullable = false,
                    )
                }
                invocationKind = EventOccurrencesRange.EXACTLY_ONCE
                inlineStatus = InlineStatus.Inline
            }.also { target.bind(it) }
        }

        return buildFunctionCall {
            coneTypeOrNull = returnType
            typeArguments += buildTypeProjectionWithVariance {
                typeRef = buildResolvedTypeRef { coneType = returnType }
                variance = Variance.INVARIANT
            }
            argumentList = buildResolvedArgumentList(original = null, linkedMapOf(lambda to blockParam.fir))
            calleeReference = buildResolvedNamedReference {
                source = originalSource
                name = Name.identifier("run")
                resolvedSymbol = runSymbol
            }
        }
    }

    private fun findRun(): FirFunctionSymbol<*> =
        session.symbolProvider.getTopLevelFunctionSymbols(FqName("kotlin"), Name.identifier("run"))
            .first { it.fir.receiverParameter == null }

    override fun ownsSymbol(symbol: FirRegularClassSymbol): Boolean = symbol.shapeAnchor != null

    override fun anchorElement(symbol: FirRegularClassSymbol): KtSourceElement = symbol.shapeAnchor!!

    override fun restoreSymbol(call: FirFunctionCall, name: Name): FirRegularClassSymbol? = localsByName[name]

    private fun relOf(shape: ConeKotlinType): ConeKotlinType =
        BrikkSqlNames.REL_CLASS_ID.constructClassLikeType(arrayOf(shape), isMarkedNullable = false)
}
