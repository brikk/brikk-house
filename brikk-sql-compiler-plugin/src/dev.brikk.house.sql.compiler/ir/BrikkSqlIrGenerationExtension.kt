package dev.brikk.house.sql.compiler.ir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.BrikkSqlOptions
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irString
import dev.brikk.house.sql.compiler.fir.BrikkSqlGeneratedKey
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Backend rewrite: inside a `@BrikkSql` function `f(src: Rel<..>, start: Instant)`,
 *
 *     Sql.postgres("|> WHERE event_at >= :start")
 *
 * becomes
 *
 *     Rel<Out>("FROM __src() |> WHERE event_at >= :start", "postgres").input("__src", src).bind("start", start)
 *
 * The frontend has already typed the call as `Rel<Out>`; the constructor call reuses that
 * type. Parameter roles mirror the frontend analysis: the first `Rel` parameter is the pipe
 * source slot `__src`, further `Rel` parameters are slots named after themselves, everything
 * else is a scalar binding by name.
 */
class BrikkSqlIrGenerationExtension(
    private val messageCollector: MessageCollector,
    private val options: BrikkSqlOptions,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val transformer = SqlCallTransformer(pluginContext, messageCollector, options)
        moduleFragment.transformChildrenVoid(transformer)
        val message = "brikk-sql: intercepted ${transformer.intercepted} SQL call(s) in ${moduleFragment.name}"
        messageCollector.report(
            if (options.debug) CompilerMessageSeverity.WARNING else CompilerMessageSeverity.LOGGING,
            message,
        )
    }
}

private class SqlCallTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector,
    private val options: BrikkSqlOptions,
) : IrElementTransformerVoid() {
    var intercepted: Int = 0
        private set

    private val functionStack = ArrayDeque<IrFunction>()

    private val relClass by lazy { pluginContext.referenceClass(BrikkSqlNames.REL_CLASS_ID) }
    private val relConstructor by lazy { pluginContext.referenceConstructors(BrikkSqlNames.REL_CLASS_ID).single() }
    private val relInput by lazy { pluginContext.referenceFunctions(BrikkSqlNames.REL_INPUT).single() }
    private val relBind by lazy { pluginContext.referenceFunctions(BrikkSqlNames.REL_BIND).single() }

    /**
     * Call-site local shape classes come out of FIR as abstract classes with a plugin-generated
     * primary constructor that has no body. Give it the standard `super()` + instance
     * initializer so the lowerings accept it.
     */
    override fun visitConstructor(declaration: IrConstructor): IrStatement {
        val origin = declaration.origin
        if (declaration.body == null && origin is IrDeclarationOrigin.GeneratedByPlugin && origin.pluginKey == BrikkSqlGeneratedKey) {
            val klass = declaration.parentAsClass
            val anyConstructor = pluginContext.irBuiltIns.anyClass.owner.constructors.single()
            val builder = DeclarationIrBuilder(pluginContext, declaration.symbol, declaration.startOffset, declaration.endOffset)
            declaration.body = builder.irBlockBody {
                +irDelegatingConstructorCall(anyConstructor)
                +IrInstanceInitializerCallImpl(startOffset, endOffset, klass.symbol, pluginContext.irBuiltIns.unitType)
            }
        }
        return super.visitConstructor(declaration)
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        functionStack.addLast(declaration)
        try {
            return super.visitFunction(declaration)
        } finally {
            functionStack.removeLast()
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        val callee = expression.symbol.owner
        if (!callee.hasAnnotation(BrikkSqlNames.BRIKK_SQL_DIALECT_ANNOTATION)) return expression
        val enclosing = functionStack.lastOrNull { it.hasAnnotation(BrikkSqlNames.BRIKK_SQL_ANNOTATION) } ?: return expression

        val sqlParam = callee.parameters.firstOrNull { it.kind == IrParameterKind.Regular } ?: return expression
        val sqlExpression = expression.arguments[sqlParam.indexInParameters] ?: return expression
        val sql = sqlExpression.constSqlStringOrNull()?.trim() ?: return expression
        val dialect = callee.name.asString()
        val fullSql = if (sql.startsWith("|>")) BrikkSqlNames.SOURCE_PREFIX + sql else sql

        intercepted++
        if (options.debug) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "brikk-sql[debug]: intercepted ${callee.name} in ${enclosing.name} with SQL:\n$fullSql",
            )
        }

        val builder = DeclarationIrBuilder(pluginContext, enclosing.symbol, expression.startOffset, expression.endOffset)
        val relType = expression.type
        val shapeType: IrType = (relType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
            ?: pluginContext.irBuiltIns.nothingType

        var result: IrExpression = builder.irCallConstructor(relConstructor, listOf(shapeType)).apply {
            type = relType
            arguments[0] = builder.irString(fullSql)
            arguments[1] = builder.irString(dialect)
        }

        var firstRel = true
        for (param in enclosing.parameters.filter { it.kind == IrParameterKind.Regular }) {
            result = if (param.isRel()) {
                val slot = if (firstRel) BrikkSqlNames.SOURCE_SLOT else param.name.asString()
                firstRel = false
                builder.irCall(relInput).apply {
                    type = relType
                    arguments[0] = result
                    arguments[1] = builder.irString(slot)
                    arguments[2] = builder.irGet(param)
                }
            } else {
                builder.irCall(relBind).apply {
                    type = relType
                    arguments[0] = result
                    arguments[1] = builder.irString(param.name.asString())
                    arguments[2] = builder.irGet(param)
                }
            }
        }
        return result
    }

    private fun IrValueParameter.isRel(): Boolean = type.classOrNull == relClass
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
