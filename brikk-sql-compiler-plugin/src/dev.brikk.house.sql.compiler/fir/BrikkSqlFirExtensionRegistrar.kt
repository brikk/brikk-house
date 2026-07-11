package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.types.ConstantValueKind

class BrikkSqlFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::BrikkSqlAdditionalCheckers
        registerDiagnosticContainers(BrikkSqlDiagnostics)
    }
}

class BrikkSqlAdditionalCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker>
            get() = setOf(SqlCallChecker)
    }
}

/**
 * Frontend checker for calls to `@BrikkSql`-annotated functions.
 *
 * Scaffold behavior: require the SQL argument to be a compile-time constant string.
 * Later this is where the brikk-sql parser runs: parse the dialect SQL, resolve
 * `:param` bindings against enclosing function parameters, infer output shape, etc.
 */
object SqlCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        if (!callee.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION_CLASS_ID, context.session)) return

        val calleeName = callee.callableId?.asSingleFqName()?.asString() ?: callee.name.asString()
        val sqlArg = expression.arguments.firstOrNull() ?: return

        val sql = sqlArg.constSqlStringOrNull()
        if (sql == null) {
            reporter.reportOn(sqlArg.source, BrikkSqlDiagnostics.SQL_NOT_CONSTANT, calleeName)
            return
        }
        if (sql.isBlank()) {
            reporter.reportOn(sqlArg.source, BrikkSqlDiagnostics.SQL_EMPTY, calleeName)
        }
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
