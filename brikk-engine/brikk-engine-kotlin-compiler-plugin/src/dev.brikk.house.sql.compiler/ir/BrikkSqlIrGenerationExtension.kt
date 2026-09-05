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
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.declarations.IrProperty
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
 *     Sql.postgres("FROM src() |> WHERE event_at >= :start")
 *
 * becomes
 *
 *     Rel<Out>("FROM src() |> WHERE event_at >= :start", "postgres").input("src", src).bind("start", start)
 *
 * The frontend has already typed the call as `Rel<Out>`; the constructor call reuses that
 * type. Parameter roles mirror the frontend analysis: every `Rel` parameter is a slot named
 * after itself (the SQL references it as `FROM name()`), everything else is a scalar binding
 * by name. The SQL text is passed through unchanged.
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
        val template = sqlExpression.sqlTemplate() ?: return expression
        val sql = template.sql.trim()
        val dialect = callee.name.asString()

        intercepted++
        if (options.debug) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "brikk-sql[debug]: intercepted ${callee.name} in ${enclosing.name} with SQL:\n$sql",
            )
        }

        val builder = DeclarationIrBuilder(pluginContext, enclosing.symbol, expression.startOffset, expression.endOffset)
        val relType = expression.type
        val shapeType: IrType = (relType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
            ?: pluginContext.irBuiltIns.nothingType

        var result: IrExpression = builder.irCallConstructor(relConstructor, listOf(shapeType)).apply {
            type = relType
            arguments[0] = builder.irString(sql)
            arguments[1] = builder.irString(dialect)
        }

        // Every Rel parameter is a slot; every scalar parameter is bound by name (it may be
        // referenced as `:name` text or as `$name`); `$name` entries that are locals or
        // properties are bound with the entry's own expression.
        val bound = HashSet<String>()
        for (param in enclosing.parameters.filter { it.kind == IrParameterKind.Regular }) {
            val name = param.name.asString()
            result = if (param.isRel()) {
                builder.irCall(relInput).apply {
                    type = relType
                    arguments[0] = result
                    arguments[1] = builder.irString(name)
                    arguments[2] = builder.irGet(param)
                }
            } else {
                bound += name
                builder.irCall(relBind).apply {
                    type = relType
                    arguments[0] = result
                    arguments[1] = builder.irString(name)
                    arguments[2] = builder.irGet(param)
                }
            }
        }
        for ((name, value) in template.binds) {
            if (!bound.add(name)) continue
            result = builder.irCall(relBind).apply {
                type = relType
                arguments[0] = result
                arguments[1] = builder.irString(name)
                arguments[2] = value
            }
        }
        return result
    }

    private fun IrValueParameter.isRel(): Boolean = type.classOrNull == relClass

    /** The SQL text after substitution plus the `$name` binds (name -> value expression) it introduced. */
    private class IrSqlTemplate(val sql: String, val binds: List<Pair<String, IrExpression>>)

    /**
     * IR mirror of `SqlTemplateFir.read`: literal or string template, optionally wrapped in
     * trimIndent/trimMargin. Entries: constants -> text; a `Rel` parameter -> its name (slot);
     * any other parameter/local -> `:name`; a `const val` -> its value; other properties -> `:name`
     * bound to the getter call. The frontend has already rejected anything else.
     */
    private fun IrExpression.sqlTemplate(): IrSqlTemplate? = when (this) {
        is IrConst -> if (kind == IrConstKind.String) IrSqlTemplate(value as String, emptyList()) else null

        is IrStringConcatenation -> {
            val sql = StringBuilder()
            val binds = ArrayList<Pair<String, IrExpression>>()
            for (entry in arguments) {
                when {
                    entry is IrConst -> sql.append(entry.value.toString())
                    entry is IrGetValue -> {
                        val owner = entry.symbol.owner
                        val name = owner.name.asString()
                        if (owner is IrValueParameter && owner.isRel()) sql.append(name)
                        else { sql.append(':').append(name); binds += name to entry }
                    }
                    else -> {
                        val property = entry.propertyOrNull() ?: return null
                        val constValue = (property.backingField?.initializer?.expression as? IrConst)?.takeIf { property.isConst }
                        if (constValue != null) {
                            sql.append(constValue.value.toString())
                        } else {
                            val name = property.name.asString()
                            sql.append(':').append(name); binds += name to entry
                        }
                    }
                }
            }
            IrSqlTemplate(sql.toString(), binds)
        }

        is IrCall -> {
            val callee = symbol.owner
            val receiverParam = callee.parameters.firstOrNull {
                it.kind == IrParameterKind.ExtensionReceiver || it.kind == IrParameterKind.DispatchReceiver
            }
            val receiver = receiverParam?.let { arguments[it.indexInParameters] }
            when {
                receiver == null -> null
                callee.name.asString() == "trimIndent" -> receiver.sqlTemplate()?.let { IrSqlTemplate(it.sql.trimIndent(), it.binds) }
                callee.name.asString() == "trimMargin" -> receiver.sqlTemplate()?.let { IrSqlTemplate(it.sql.trimMargin(), it.binds) }
                else -> null
            }
        }

        else -> null
    }

    /** The property behind a getter call or a field read, if any. */
    private fun IrExpression.propertyOrNull(): IrProperty? = when (this) {
        is IrCall -> symbol.owner.correspondingPropertySymbol?.owner
        is IrGetField -> symbol.owner.correspondingPropertySymbol?.owner
        else -> null
    }
}
