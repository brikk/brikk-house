package dev.brikk.house.sql.compiler.ir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.BrikkSqlOptions
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Backend rewrite of `@BrikkSql`-annotated calls.
 *
 * Scaffold behavior (proves the mechanics only): each intercepted `String`-returning call
 * is replaced with a constant `BRIKK[<sql>]` where `<sql>` is the compile-time-normalized
 * SQL text (trimIndent/trimMargin already applied).
 *
 * The real transform will: merge composed TVF ASTs, desugar pipe syntax, render final SQL
 * for the target dialect, and rewrite the call into a statement + parameter-bindings object
 * (terpal/ExoQuery-style parts/params model).
 */
class BrikkSqlIrGenerationExtension(
    private val messageCollector: MessageCollector,
    private val options: BrikkSqlOptions,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val transformer = SqlCallTransformer(messageCollector, options)
        moduleFragment.transformChildrenVoid(transformer)
        val message = "brikk-sql: intercepted ${transformer.intercepted} SQL call(s) in ${moduleFragment.name}"
        messageCollector.report(
            if (options.debug) CompilerMessageSeverity.WARNING else CompilerMessageSeverity.LOGGING,
            message,
        )
    }
}

private class SqlCallTransformer(
    private val messageCollector: MessageCollector,
    private val options: BrikkSqlOptions,
) : IrElementTransformerVoid() {
    var intercepted: Int = 0
        private set

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        val callee = expression.symbol.owner
        if (!callee.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION)) return expression

        val sqlParam = callee.parameters.firstOrNull { it.kind == IrParameterKind.Regular }
            ?: return expression
        val sqlExpression = expression.arguments[sqlParam.indexInParameters] ?: return expression
        val sql = sqlExpression.constSqlStringOrNull() ?: return expression

        intercepted++
        if (options.debug) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "brikk-sql[debug]: intercepted ${callee.name} with SQL:\n$sql",
            )
        }

        // Scaffold-only rewrite: replace String-returning calls with a marker constant so
        // tests can observe that the backend transform actually ran.
        return if (expression.type.isString()) {
            IrConstImpl.string(expression.startOffset, expression.endOffset, expression.type, "BRIKK[$sql]")
        } else {
            expression
        }
    }
}

/** IR mirror of the FIR-side constant evaluation: literal, optionally trimIndent/trimMargin. */
private fun IrExpression.constSqlStringOrNull(): String? = when (this) {
    is IrConst ->
        if (kind == IrConstKind.String) value as? String else null

    is IrCall -> {
        val callee = symbol.owner
        val receiverParam = callee.parameters.firstOrNull {
            it.kind == IrParameterKind.ExtensionReceiver || it.kind == IrParameterKind.DispatchReceiver
        }
        val receiver = receiverParam?.let { arguments[it.indexInParameters] }
        when {
            receiver == null -> null
            callee.name.asString() == "trimIndent" -> receiver.constSqlStringOrNull()?.trimIndent()
            callee.name.asString() == "trimMargin" -> receiver.constSqlStringOrNull()?.trimMargin()
            else -> null
        }
    }

    else -> null
}
