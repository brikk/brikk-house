package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.BrikkSqlNames
import dev.brikk.house.sql.compiler.analysis.SqlPiece
import dev.brikk.house.sql.compiler.analysis.SqlTemplate
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isLocal
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLazyBlock
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

/** Result of reading the SQL argument as a template; see [SqlTemplate] for the rules. */
sealed interface TemplateOutcome {
    class Ok(val template: SqlTemplate) : TemplateOutcome

    /** A `${...}` entry that is not a plain reference to something we can substitute. */
    class Rejected(val entry: FirExpression, val reason: String) : TemplateOutcome

    /** Not a string literal/template we understand at all (e.g. a call, a variable). */
    data object NotSql : TemplateOutcome
}

/**
 * Names visible from a `@BrikkSql` function's SQL template and how each classifies. Built
 * from raw FIR (declaration generation, before any resolution) or from resolved references
 * (checkers); [SqlTemplateFir.read] uses the resolved symbol when a reference has one and
 * falls back to these name sets otherwise.
 */
class TemplateScope(
    val relParams: Set<String>,
    val otherParams: Set<String>,
    val locals: Set<String>,
    /** `const val` by simple name -> its value as SQL text; `null` when not a const. */
    val constByName: (String) -> String?,
) {
    fun classify(name: String): SqlPiece = when (name) {
        in relParams -> SqlPiece.Slot(name)
        in otherParams, in locals -> SqlPiece.Bind(name)
        else -> constByName(name)?.let { SqlPiece.Const(it) } ?: SqlPiece.Bind(name)
    }
}

object SqlTemplateFir {

    /**
     * Reads `expr` (a literal, a template, or either wrapped in `.trimIndent()`/`.trimMargin()`)
     * into a [SqlTemplate], classifying every `$name` entry.
     */
    fun read(expr: FirExpression, scope: TemplateScope): TemplateOutcome = when (expr) {
        is FirLiteralExpression ->
            if (expr.kind == ConstantValueKind.String) TemplateOutcome.Ok(SqlTemplate.text(expr.value as String))
            else TemplateOutcome.NotSql

        is FirStringConcatenationCall -> {
            val pieces = ArrayList<SqlPiece>()
            for (entry in expr.arguments) {
                pieces += classifyEntry(entry, scope) ?: return TemplateOutcome.Rejected(
                    entry,
                    "only a parameter, a local val, a property or a const val can be interpolated here; " +
                        "extract this expression to a val",
                )
            }
            TemplateOutcome.Ok(SqlTemplate(pieces))
        }

        is FirFunctionCall -> {
            val name = expr.calleeReference.name.asString()
            val receiver = expr.explicitReceiver
            when {
                receiver == null || expr.arguments.isNotEmpty() -> TemplateOutcome.NotSql
                name == "trimIndent" -> read(receiver, scope).trimmedBy { it.trimIndent() }
                name == "trimMargin" -> read(receiver, scope).trimmedBy { it.trimMargin() }
                else -> TemplateOutcome.NotSql
            }
        }

        else -> TemplateOutcome.NotSql
    }

    private fun TemplateOutcome.trimmedBy(f: (String) -> String): TemplateOutcome =
        if (this is TemplateOutcome.Ok) TemplateOutcome.Ok(template.trimmed(f)) else this

    private fun classifyEntry(entry: FirExpression, scope: TemplateScope): SqlPiece? {
        if (entry is FirLiteralExpression) {
            return if (entry.kind == ConstantValueKind.String) SqlPiece.Text(entry.value as String) else SqlPiece.Const(entry.value.toString())
        }
        val access = entry as? FirPropertyAccessExpression ?: return null
        if (access.explicitReceiver != null) return null
        val reference = access.calleeReference as? FirNamedReference ?: return null
        val name = reference.name.asString()

        // Resolved (checker phase): the symbol is authoritative.
        if (reference is FirResolvedNamedReference) {
            when (val symbol = reference.resolvedSymbol) {
                is FirValueParameterSymbol -> {
                    val isRel = symbol.resolvedReturnTypeRef.coneType.classId == BrikkSqlNames.REL_CLASS_ID
                    return if (isRel) SqlPiece.Slot(name) else SqlPiece.Bind(name)
                }
                is FirPropertySymbol -> {
                    if (symbol.isConst) constText(symbol)?.let { return SqlPiece.Const(it) }
                    return SqlPiece.Bind(name)
                }
                else -> return null
            }
        }
        // Raw (generation phase): classify by name.
        return scope.classify(name)
    }

    /** Value of a `const val` as SQL text (strings unquoted: the author writes the quotes). */
    fun constText(symbol: FirPropertySymbol): String? =
        (symbol.fir.initializer as? FirLiteralExpression)?.value?.toString()

    // ---------------------------------------------------------------- scope construction

    /** Scope for [function] from raw FIR: parameters by declared type, local vals of the body, consts by lookup. */
    fun scopeOf(function: FirNamedFunction, session: FirSession, containerFile: FirFile?): TemplateScope {
        val rel = HashSet<String>()
        val other = HashSet<String>()
        for (p in function.valueParameters) {
            with(RawFir) { if (p.returnTypeRef.shortName() == "Rel") rel += p.name.asString() else other += p.name.asString() }
        }
        val body = function.body
        val locals = if (body is FirBlock && body !is FirLazyBlock) {
            body.statements.filterIsInstance<FirProperty>().filter { it.isLocal }.mapTo(HashSet()) { it.name.asString() }
        } else {
            emptySet()
        }
        val packageFqName = function.symbol.callableId.packageName
        return TemplateScope(rel, other, locals) { name -> lookupConst(session, packageFqName, containerFile, name) }
    }

    /**
     * `const val` lookup by simple name: same package, then the file's explicit and star
     * imports. Companion/object constants are out of scope (bind instead).
     */
    private fun lookupConst(session: FirSession, packageFqName: FqName, file: FirFile?, name: String): String? {
        val id = Name.identifier(name)
        val candidates = ArrayList<FqName>()
        candidates += packageFqName
        file?.imports?.forEach { imp ->
            val fq = imp.importedFqName ?: return@forEach
            if (imp.isAllUnder) candidates += fq else if (fq.shortName() == id) candidates += fq.parent()
        }
        for (pkg in candidates) {
            val prop = session.symbolProvider.getTopLevelCallableSymbols(pkg, id)
                .filterIsInstance<FirPropertySymbol>().firstOrNull() ?: continue
            return if (prop.isConst) constText(prop) else null
        }
        return null
    }
}
