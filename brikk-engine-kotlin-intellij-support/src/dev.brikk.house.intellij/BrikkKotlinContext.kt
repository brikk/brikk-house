package dev.brikk.house.intellij

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/** No extension registrations or SQL-plugin dependency. Call only from an optional Kotlin integration. */
public object BrikkKotlinContext {
    internal const val RUNTIME: String = "dev.brikk.house.sql.runtime"

    /** Full host recognition and template classification in one bounded analysis session. */
    public fun inspect(element: PsiElement, includeShapes: Boolean = true): ContextResult {
        val host = element as? KtStringTemplateExpression ?: return ContextResult.NotBrikk
        val callContext = syntaxContext(host) ?: return ContextResult.NotBrikk
        if (DumbService.isDumb(host.project)) return ContextResult.Unavailable("Kotlin indexing is in progress")
        return analysis(host) {
            // Smart mode: the annotation must resolve to the runtime's, and an unresolved callee is
            // pending only when its syntax can name the runtime's entry, never for any call.
            if (callContext.function.symbol.annotations.none { it.classId?.asSingleFqName()?.asString() == "$RUNTIME.BrikkSql" }) {
                return@analysis ContextResult.NotBrikk
            }
            val target = callContext.call.resolveSymbol() as? KaCallableSymbol
                ?: return@analysis if (BrikkSyntax.entryName(callContext.call) != null || runtimeReceiver(callContext.call)) {
                    ContextResult.Unavailable("SQL entry point is not resolved")
                } else ContextResult.NotBrikk
            val dialect = dialect(target) ?: return@analysis ContextResult.NotBrikk
            for (trimCall in callContext.trimCalls) {
                val trim = trimCall.resolveSymbol() as? KaCallableSymbol
                    ?: return@analysis ContextResult.Unavailable("Trim call is not resolved")
                if (trim.callableId?.asSingleFqName()?.asString() != "kotlin.text.${trimCall.calleeExpression?.text}") {
                    return@analysis ContextResult.NotBrikk
                }
            }
            val entries = host.entries.mapIndexed { index, entry -> classify(host, entry, index) }
            val shapes = if (includeShapes) callContext.function.valueParameters.mapNotNull { parameter ->
                val result = BrikkRelationShapes.parameterInSession(this, parameter)
                if (result == ShapeResult.NotRelation) null else parameter.name?.let { it to result }
            }.toMap() else emptyMap()
            ContextResult.Available(BrikkContext(dialect, callContext.trimCalls.map { it.calleeExpression!!.text }, entries, shapes, callContext.trimSteps))
        }
    }

    /**
     * A runtime `Sql` member is an entry point when it declares `@BrikkSqlDialect`, or, for runtimes
     * before that annotation, when its name is a registered dialect.
     */
    private fun dialect(target: KaCallableSymbol): BrikkDialect? {
        val id = target.callableId ?: return null
        if (id.packageName.asString() != RUNTIME || id.className?.asString() != "Sql") return null
        val declared = target.annotations.firstOrNull { it.classId?.asSingleFqName()?.asString() == "$RUNTIME.BrikkSqlDialect" }
            ?.let { stringArgument(it, "dialect") }
        val name = declared ?: id.callableName.asString().takeIf { name -> BrikkDialect.entries.any { it.sqlName == name } } ?: return null
        val language = (target as? KaFunctionSymbol)?.valueParameters?.singleOrNull()?.annotations
            ?.firstOrNull { it.classId?.asSingleFqName()?.asString() == "org.intellij.lang.annotations.Language" }
            ?.let { stringArgument(it, "value") }
        return BrikkDialect.of(name, language)
    }

    /**
     * Whether Kotlin proves [call]'s receiver is the runtime's `Sql` even though the member is
     * unresolved (an older runtime): directly, or through a typealias chain Kotlin expands (a
     * cyclic one does not expand).
     */
    private fun KaSession.runtimeReceiver(call: KtCallExpression): Boolean {
        val receiver = ((call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === call }?.receiverExpression as? KtNameReferenceExpression)
            ?: return false
        val symbol = receiver.references.filterIsInstance<org.jetbrains.kotlin.idea.references.KtReference>().firstOrNull()?.resolveToSymbol()
        val classId = when (symbol) {
            is KaTypeAliasSymbol -> (symbol.expandedType.fullyExpandedType as? KaClassType)?.classId
            is KaClassSymbol -> symbol.classId
            else -> null
        }
        return classId?.asSingleFqName()?.asString() == "$RUNTIME.Sql"
    }

    private fun stringArgument(annotation: KaAnnotation, name: String): String? =
        ((annotation.arguments.firstOrNull { it.name.asString() == name }?.expression as? KaAnnotationValue.ConstantValue)
            ?.value as? KaConstantValue.StringValue)?.value

    internal data class SyntaxContext(val call: KtCallExpression, val function: KtNamedFunction, val trimCalls: List<KtCallExpression>) {
        val trimSteps: List<BrikkTrim> get() = trimCalls.map { BrikkTrim.of(it.calleeExpression!!.text, marginPrefix(it)) }
    }

    /** The literal prefix argument of a `trimMargin` call, without interpolation; null when there is none. */
    internal fun marginPrefix(trim: KtCallExpression): String? {
        val argument = trim.valueArguments.singleOrNull()?.getArgumentExpression() as? KtStringTemplateExpression ?: return null
        if (argument.hasInterpolation()) return null
        return buildString { argument.entries.forEach { append((it as? KtEscapeStringTemplateEntry)?.unescapedValue ?: it.text) } }
    }

    internal fun syntaxContext(host: KtStringTemplateExpression): SyntaxContext? {
        var expression: KtExpression = host
        val trims = mutableListOf<KtCallExpression>()
        // Parentheses change nothing Brikk-relevant: `Sql.duckdb(("..."))` is still the Brikk host,
        // and so inspection-only, even where the compiler rejects it.
        fun unwrap() { while (expression.parent is KtParenthesizedExpression) expression = expression.parent as KtExpression }
        while (true) {
            unwrap()
            val qualified = expression.parent as? KtDotQualifiedExpression ?: break
            if (qualified.receiverExpression !== expression) return null
            val trim = qualified.selectorExpression as? KtCallExpression ?: return null
            val name = trim.calleeExpression?.text
            val arguments = trim.valueArguments
            // trimMargin may name its prefix with a literal string; anything else is not a supported trim.
            if (name == "trimIndent" && arguments.isNotEmpty() || name !in setOf("trimIndent", "trimMargin")) return null
            if (arguments.size > 1 || arguments.singleOrNull()?.let { marginPrefix(trim) == null } == true) return null
            trims += trim
            expression = qualified
        }
        val argument = expression.parent as? KtValueArgument ?: return null
        val call = argument.parent?.parent as? KtCallExpression ?: return null
        if (call.valueArguments.size != 1 || call.valueArguments.single() !== argument) return null
        val function = PsiTreeUtil.getParentOfType(call, KtNamedFunction::class.java) ?: return null
        return SyntaxContext(call, function, trims)
    }

    private fun KaSession.classify(host: KtStringTemplateExpression, entry: KtStringTemplateEntry, index: Int): TemplateEntry {
        val range = HostRange(entry.startOffsetInParent, entry.startOffsetInParent + entry.textLength)
        val expression = entry.expression ?: return TemplateEntry(range, EntryRole.LITERAL)
        fun pending() = TemplateEntry(range, EntryRole.PENDING, replacement = ":__brikk_pending_$index",
            problem = "Kotlin symbol or type resolution is not yet available")
        fun unsupported(message: String) = TemplateEntry(range, EntryRole.UNSUPPORTED, replacement = ":__brikk_unsupported_$index", problem = message)
        literal(expression)?.let { return TemplateEntry(range, EntryRole.LITERAL, replacement = it) }
        val reference = expression as? KtNameReferenceExpression
            ?: return unsupported("Only simple Kotlin references can be interpolated in Brikk SQL; extract this expression to a val")
        val name = reference.getReferencedName()
        val symbol = reference.references.filterIsInstance<org.jetbrains.kotlin.idea.references.KtReference>()
            .firstOrNull()?.resolveToSymbol()
        return when (symbol) {
            is KaValueParameterSymbol -> {
                val type = symbol.returnType.fullyExpandedType
                if (type is KaErrorType) pending()
                else if ((type as? KaClassType)?.classId?.asSingleFqName()?.asString() == "$RUNTIME.Rel") {
                    val following = host.text.substring(range.end.coerceAtMost(host.text.length))
                    TemplateEntry(range, EntryRole.RELATION_SLOT, name, name,
                        if (following.trimStart().startsWith("(")) null else "Rel parameter '$name' must be used as a relation slot: \$$name()")
                } else TemplateEntry(range, EntryRole.BIND, name, ":$name")
            }
            is KaLocalVariableSymbol -> TemplateEntry(range, EntryRole.BIND, name, ":$name")
            is KaKotlinPropertySymbol -> {
                if (!symbol.isConst) TemplateEntry(range, EntryRole.BIND, name, ":$name")
                else {
                    // Deserialized constants are literal values to FIR. Source constants still
                    // require a literal initializer, not arbitrary constant-expression folding.
                    val initializer = (symbol.psi as? KtProperty)?.initializer
                    val value = if (symbol.origin == KaSymbolOrigin.LIBRARY) {
                        reference.evaluate()?.takeUnless { it is KaConstantValue.ErrorValue }?.let { it.value.toString() }
                    } else initializer?.let { literal(it) }
                    if (value != null) TemplateEntry(range, EntryRole.SQL_CONSTANT, name, value)
                    else unsupported("Computed or unavailable const val '$name' cannot be interpolated as SQL; use a literal initializer")
                }
            }
            null -> pending()
            else -> unsupported("This Kotlin declaration cannot be interpolated in Brikk SQL")
        }
    }

    private fun KaSession.literal(expression: KtExpression): String? = when (expression) {
        is KtStringTemplateExpression -> if (expression.hasInterpolation()) null else buildString {
            expression.entries.forEach { append((it as? KtEscapeStringTemplateEntry)?.unescapedValue ?: it.text) }
        }
        is KtConstantExpression -> expression.evaluate()?.takeUnless { it is KaConstantValue.ErrorValue }?.let { it.value.toString() }
        is KtPrefixExpression -> if (expression.operationToken in setOf(KtTokens.MINUS, KtTokens.PLUS) && expression.baseExpression is KtConstantExpression) {
            expression.evaluate()?.takeUnless { it is KaConstantValue.ErrorValue }?.let { it.value.toString() }
        } else null
        else -> null
    }

    @OptIn(KaAllowAnalysisOnEdt::class)
    internal fun <T> analysis(element: KtElement, action: KaSession.() -> T): T =
        allowAnalysisOnEdt { analyze(element) { action() } }
}
