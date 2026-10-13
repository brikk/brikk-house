package dev.brikk.house.intellij

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** What one host string is to Brikk now. Decided by [BrikkHosts.state]. */
public sealed interface BrikkHostState {
    /** The host's dialect when recognized or remembered; for a never-recognized pending host, a hint from its callee. */
    public val dialect: BrikkDialect?

    /** A resolved Brikk host: inject [context]'s entries. */
    public data class Recognized(public val context: BrikkContext) : BrikkHostState {
        override val dialect: BrikkDialect get() = context.dialect
    }

    /**
     * A Brikk host whose Kotlin analysis is unavailable (indexing, unresolved symbols). Claim it and
     * retry when analysis is ready; it is Brikk and inspection-only meanwhile, never executable.
     * [projection] exists when this host was recognized before: its dialect and trims with every
     * interpolation [EntryRole.PENDING], so the SQL keeps its injection without a guessed role.
     * Without one, claim the host without injecting.
     */
    public data class Pending(
        public val reason: String,
        public val projection: BrikkContext?,
        private val dialectHint: BrikkDialect?,
    ) : BrikkHostState {
        override val dialect: BrikkDialect? get() = projection?.dialect ?: dialectHint
    }

    /** Not a Brikk host: withdraw this host's Brikk state and leave native injection alone. */
    public data object NotBrikk : BrikkHostState {
        override val dialect: BrikkDialect? get() = null
    }
}

/**
 * The recognized / pending / not-Brikk decision for host strings, remembering each host's last
 * recognized context. Call only from an optional Kotlin integration.
 */
public object BrikkHosts {
    private val lastRecognized: Key<BrikkContext> = Key.create("dev.brikk.house.intellij.lastRecognizedContext")

    /**
     * Decides what [element] is now. A host recognized before but unavailable now is pending with
     * a projection; an unavailable host whose function is annotated `@BrikkSql` (by name or import
     * alias) is pending without one; any other unavailable host is not Brikk.
     */
    public fun state(element: PsiElement, includeShapes: Boolean = false): BrikkHostState {
        val host = element as? KtStringTemplateExpression ?: return BrikkHostState.NotBrikk
        return when (val result = BrikkKotlinContext.inspect(host, includeShapes)) {
            is ContextResult.Available -> {
                host.putUserData(lastRecognized, result.context.copy(relations = emptyMap()))
                BrikkHostState.Recognized(result.context)
            }
            ContextResult.NotBrikk -> {
                forget(host)
                BrikkHostState.NotBrikk
            }
            is ContextResult.Unavailable -> {
                val syntax = BrikkKotlinContext.syntaxContext(host) ?: return BrikkHostState.NotBrikk
                val previous = host.getUserData(lastRecognized)
                when {
                    previous != null -> BrikkHostState.Pending(result.reason, project(host, previous, syntax.trimCalls.map { it.calleeExpression!!.text }, result.reason), null)
                    annotated(syntax.function) -> BrikkHostState.Pending(result.reason, null, hint(syntax.call))
                    else -> BrikkHostState.NotBrikk
                }
            }
        }
    }

    /** Drops [element]'s remembered context, for a host this plugin withdraws. */
    public fun forget(element: PsiElement) {
        element.putUserData(lastRecognized, null)
    }

    /** The current host's entries, every interpolation pending: no remembered range or role is reused. */
    private fun project(host: KtStringTemplateExpression, previous: BrikkContext, trims: List<String>, reason: String): BrikkContext =
        previous.copy(trims = trims, relations = emptyMap(), entries = host.entries.mapIndexed { index, entry ->
            val range = HostRange(entry.startOffsetInParent, entry.startOffsetInParent + entry.textLength)
            if (entry.expression == null) TemplateEntry(range, EntryRole.LITERAL)
            else TemplateEntry(range, EntryRole.PENDING, replacement = ":__brikk_pending_$index", problem = reason)
        })

    private fun annotated(function: KtNamedFunction): Boolean {
        val qualified = "${BrikkKotlinContext.RUNTIME}.BrikkSql"
        val aliases = function.containingKtFile.importDirectives.filter { it.importedFqName?.asString() == qualified }.mapNotNull { it.aliasName }
        return function.annotationEntries.any { it.shortName?.asString().let { name -> name == "BrikkSql" || name in aliases } }
    }

    private fun hint(call: KtCallExpression): BrikkDialect? {
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val entry = "${BrikkKotlinContext.RUNTIME}.Sql."
        val imported = call.containingKtFile.importDirectives.firstOrNull { it.aliasName == callee }?.importedFqName?.asString()
        val name = if (imported == null) callee else imported.takeIf { it.startsWith(entry) }?.removePrefix(entry) ?: return null
        return BrikkDialect.entries.firstOrNull { it.sqlName == name }
    }
}
