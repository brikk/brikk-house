package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.analysis.RawFunction
import dev.brikk.house.sql.compiler.analysis.RawParam
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
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

    /** The body's single `Sql.<dialect>(<literal>)` call, if the function has that form. */
    fun sqlCall(function: FirNamedFunction): FirFunctionCall? {
        val body = function.body ?: return null
        val expr = body.singleResultExpression() ?: return null
        return expr as? FirFunctionCall
    }

    /** `Sql.<dialect>(...)` -> "<dialect>" if the receiver is the `Sql` object (by name). */
    fun dialectOf(call: FirFunctionCall): String? {
        val receiver = call.explicitReceiver ?: return null
        val receiverName = when (receiver) {
            is FirResolvedQualifier -> receiver.classId?.shortClassName?.asString()
            is FirQualifiedAccessExpression -> (receiver.calleeReference as? FirNamedReference)?.name?.asString()
            else -> null
        }
        if (receiverName != BrikkSqlNames.SQL_OBJECT_CLASS_ID.shortClassName.asString()) return null
        return call.calleeReference.name.asString()
    }

    fun rawFunction(function: FirNamedFunction): RawFunction {
        val call = sqlCall(function)
        val sqlArg = call?.arguments?.firstOrNull()
        return RawFunction(
            packageFqName = function.symbol.callableId.packageName,
            name = function.name,
            dialect = call?.let { dialectOf(it) },
            sqlText = sqlArg?.constSqlStringOrNull(),
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

    private fun FirBlock.singleResultExpression(): FirExpression? {
        val stmt = statements.singleOrNull() ?: return null
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

/**
 * Evaluates the SQL argument to a constant String if possible.
 * Accepts a plain literal (raw or escaped, no interpolation) optionally wrapped in
 * `.trimIndent()` / `.trimMargin()`, which are applied at compile time.
 */
internal fun FirExpression.constSqlStringOrNull(): String? = when (this) {
    is FirLiteralExpression ->
        if (kind == ConstantValueKind.String) value as? String else null

    is FirFunctionCall -> {
        val name = calleeReference.name.asString()
        val receiver = explicitReceiver
        when {
            receiver == null -> null
            name == "trimIndent" && arguments.isEmpty() -> receiver.constSqlStringOrNull()?.trimIndent()
            name == "trimMargin" && arguments.isEmpty() -> receiver.constSqlStringOrNull()?.trimMargin()
            else -> null
        }
    }

    else -> null
}
