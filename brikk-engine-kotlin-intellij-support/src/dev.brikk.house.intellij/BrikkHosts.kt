package dev.brikk.house.intellij

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
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
    /** A recognized context and the ownership syntax it was recognized under. */
    private class Remembered(val context: BrikkContext, val fingerprint: String?)

    private val lastRecognized: Key<Remembered> = Key.create("dev.brikk.house.intellij.lastRecognizedContext")
    private val validatedNegative: Key<String> = Key.create("dev.brikk.house.intellij.validatedNotBrikk")

    /**
     * Decides what [element] is now, erring toward not claiming. When its Kotlin analysis is
     * unavailable: a host recognized before, under the same deciding syntax, is pending with a
     * projection; one with strong evidence (a callee that can only be a runtime `Sql` entry by
     * explicit import or alias, unshadowed, in a function whose annotation can only be the
     * runtime's `@BrikkSql`) is pending without one; anything else is not Brikk, meaning no
     * decision: native injection stays as the platform makes it. So is a host Kotlin ruled out
     * before, while its deciding syntax is unchanged. Execution guards use [BrikkInspectionView],
     * which errs toward Brikk instead.
     */
    public fun state(element: PsiElement, includeShapes: Boolean = false): BrikkHostState = decide(element, includeShapes).state

    /** A decision and whether Kotlin itself made it (rather than syntax while Kotlin could not resolve). */
    internal class Decision(val state: BrikkHostState, val byKotlin: Boolean)

    internal fun decide(element: PsiElement, includeShapes: Boolean = false): Decision {
        val host = element as? KtStringTemplateExpression ?: return Decision(BrikkHostState.NotBrikk, true)
        return when (val result = BrikkKotlinContext.inspect(host, includeShapes)) {
            is ContextResult.Available -> {
                val fingerprint = BrikkKotlinContext.syntaxContext(host)?.let(BrikkSyntax::fingerprint)
                host.putUserData(lastRecognized, Remembered(result.context.copy(relations = emptyMap()), fingerprint))
                host.putUserData(validatedNegative, null)
                Decision(BrikkHostState.Recognized(result.context), true)
            }
            ContextResult.NotBrikk -> {
                forget(host)
                // Remember a negative Kotlin validated, not one syntax alone decided while indexing.
                if (!DumbService.isDumb(host.project)) {
                    host.putUserData(validatedNegative, BrikkKotlinContext.syntaxContext(host)?.let(BrikkSyntax::fingerprint))
                }
                Decision(BrikkHostState.NotBrikk, true)
            }
            is ContextResult.Unavailable -> Decision(unavailable(host, result), false)
        }
    }

    private fun unavailable(host: KtStringTemplateExpression, result: ContextResult.Unavailable): BrikkHostState {
                val syntax = BrikkKotlinContext.syntaxContext(host) ?: return BrikkHostState.NotBrikk
                val fingerprint = BrikkSyntax.fingerprint(syntax)
                if (host.getUserData(validatedNegative) == fingerprint) return BrikkHostState.NotBrikk
                // An edit to the deciding syntax (annotation removed, Sql.trino -> Sql.duckdb) withdraws
                // the remembered ownership; an edit to the SQL text keeps it.
                val previous = host.getUserData(lastRecognized)?.takeIf { it.fingerprint == fingerprint }?.context
                val entry = BrikkSyntax.entryName(syntax.call)
                return when {
                    previous != null -> BrikkHostState.Pending(result.reason, project(host, previous, syntax.trimCalls.map { it.calleeExpression!!.text }, result.reason), null)
                    entry != null && BrikkSyntax.annotated(syntax.function) ->
                        BrikkHostState.Pending(result.reason, null, BrikkDialect.entries.firstOrNull { it.sqlName == entry })
                    else -> BrikkHostState.NotBrikk
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
}
