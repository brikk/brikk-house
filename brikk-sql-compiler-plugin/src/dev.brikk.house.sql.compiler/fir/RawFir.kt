package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.analysis.RawFunction
import dev.brikk.house.sql.compiler.analysis.RawParam
import dev.brikk.house.sql.compiler.analysis.SqlLiteralText
import dev.brikk.house.sql.compiler.analysis.SqlTemplate
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLazyBlock
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Syntactic readers over FIR that work on **raw** (unresolved) trees as well as resolved
 * ones. Declaration generation runs before body resolution, so everything the plugin needs
 * from a `@BrikkSql` function must be recoverable from the tree as parsed:
 * `FirUserTypeRef` qualifiers, `FirLiteralExpression`s, callee short names.
 */
object RawFir {

    /**
     * The body's single `Sql.<dialect>(<literal>)` call, if the function has that form.
     * `null` for a lazy (not yet built) body, which the IDE hands us for declarations other
     * than the one under resolution; see [sqlLiteralOf] for the fallback.
     */
    fun sqlCall(function: FirNamedFunction): FirFunctionCall? {
        val body = function.body ?: return null
        if (body is FirLazyBlock) return null
        val expr = body.singleResultExpression() ?: return null
        return expr as? FirFunctionCall
    }

    /**
     * (dialect, SQL template) of the body's `Sql.<dialect>(<literal or template>)` call.
     *
     * In the CLI every body is a real [FirBlock]. In the IDE, FIR is built lazily per
     * declaration: a callee in another file has a [FirLazyBlock] whose statements throw when
     * touched, and forcing its resolution from inside another declaration's resolution is not
     * allowed. The declaration's *source text* is available either way, so lazy bodies are read
     * textually. (PSI is deliberately not used: the plugin is compiled against the embeddable
     * compiler, where `com.intellij` types are relocated, so PSI signatures do not link in the IDE.)
     *
     * A rejected `${'$'}{...}` entry yields a null template here; the call checker reports it
     * precisely, this path only needs "no analyzable SQL".
     */
    fun sqlTemplateOf(function: FirNamedFunction, session: FirSession, containerFile: FirFile?): Pair<String?, SqlTemplate?> {
        val scope = SqlTemplateFir.scopeOf(function, session, containerFile)
        val body = function.body
        if (body is FirLazyBlock) {
            val text = function.source?.text ?: return null to null
            return SqlLiteralText.parse(text, scope::classify) ?: (null to null)
        }
        val call = sqlCall(function) ?: return null to null
        val arg = call.arguments.firstOrNull() ?: return dialectOf(call) to null
        val template = (SqlTemplateFir.read(arg, scope) as? TemplateOutcome.Ok)?.template
        return dialectOf(call) to template
    }

    /** `Sql.<dialect>(...)` -> "<dialect>" if the receiver is the `Sql` object (by name). */
    fun dialectOf(call: FirFunctionCall): String? {
        val receiver = call.explicitReceiver ?: return null
        val receiverName = when (receiver) {
            // Not `classId`: removed from FirResolvedQualifier in Kotlin 2.4.20 (the IDE's compiler
            // under KEFS); packageFqName/relativeClassFqName exist in both.
            is FirResolvedQualifier -> receiver.relativeClassFqName?.takeIf { !it.isRoot }?.shortName()?.asString()
            is FirQualifiedAccessExpression -> (receiver.calleeReference as? FirNamedReference)?.name?.asString()
            else -> null
        }
        if (receiverName != BrikkSqlNames.SQL_OBJECT_CLASS_ID.shortClassName.asString()) return null
        return call.calleeReference.name.asString()
    }

    fun rawFunction(function: FirNamedFunction, session: FirSession, containerFile: FirFile?): RawFunction {
        val (dialect, template) = sqlTemplateOf(function, session, containerFile)
        return RawFunction(
            packageFqName = function.symbol.callableId.packageName,
            name = function.name,
            dialect = dialect,
            sqlText = template?.sql,
            binds = template?.binds.orEmpty(),
            params = function.valueParameters.map { p ->
                RawParam(
                    name = p.name.asString(),
                    typeShortName = p.returnTypeRef.shortName() ?: "?",
                    typeArgShortName = p.returnTypeRef.firstTypeArgumentShortName(),
                )
            },
            typeParamBounds = function.typeParameters.associate { tp ->
                tp.symbol.name.asString() to tp.symbol.resolvedBoundsSafe().mapNotNull { it.shortName() }
            },
        )
    }

    /** Own `val` declarations of a trait interface: (name, type short name, nullable). */
    fun traitProperties(klass: FirRegularClass): List<Triple<String, String, Boolean>> =
        klass.declarations.filterIsInstance<FirProperty>().mapNotNull { p ->
            val short = p.returnTypeRef.shortName() ?: return@mapNotNull null
            Triple(p.name.asString(), short, p.returnTypeRef.isNullableRaw())
        }

    /** Raw supertype short names of a class (for trait inheritance). */
    fun superTypeShortNames(klass: FirRegularClass): List<String> =
        klass.superTypeRefs.mapNotNull { it.shortName() }

    // ---------------------------------------------------------------- helpers

    /**
     * The body's result: its last statement (a `return`, or the expression of an expression
     * body). Preceding statements are allowed so `val`s can be declared for `$name` binds.
     */
    private fun FirBlock.singleResultExpression(): FirExpression? {
        val stmt = statements.lastOrNull() ?: return null
        return when (stmt) {
            is FirReturnExpression -> stmt.result
            is FirExpression -> stmt
            else -> null
        }
    }

    private fun org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol.resolvedBoundsSafe(): List<FirTypeRef> =
        try {
            fir.bounds
        } catch (e: Throwable) {
            emptyList()
        }

    /** Short name of a type ref, raw or resolved; type parameters yield their own name. */
    fun FirTypeRef.shortName(): String? = when (this) {
        is FirUserTypeRef -> qualifier.lastOrNull()?.name?.asString()
        is FirResolvedTypeRef -> coneType.shortName()
        else -> null
    }

    private fun ConeKotlinType.shortName(): String? = when (this) {
        is ConeTypeParameterType -> lookupTag.name.asString()
        else -> classId?.shortClassName?.asString()
    }

    /** Short name of the first type argument (`Rel<X>` -> "X"), raw or resolved. */
    fun FirTypeRef.firstTypeArgumentShortName(): String? = when (this) {
        is FirUserTypeRef ->
            (qualifier.lastOrNull()?.typeArgumentList?.typeArguments?.firstOrNull() as? FirTypeProjectionWithVariance)
                ?.typeRef?.shortName()
        is FirResolvedTypeRef -> coneType.typeArguments.firstOrNull()?.type?.shortName()
        else -> null
    }

    private fun FirTypeRef.isNullableRaw(): Boolean = when (this) {
        is FirUserTypeRef -> isMarkedNullable
        is FirResolvedTypeRef -> coneType.isMarkedNullable
        else -> false
    }
}
