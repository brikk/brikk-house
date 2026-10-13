package dev.brikk.house.intellij

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Brikk evidence from syntax and imports alone, for when Kotlin cannot resolve (indexing, an
 * unresolved callee). Never proof of Brikk; only the absence of a reason to rule it out.
 */
internal object BrikkSyntax {
    private const val RUNTIME = BrikkKotlinContext.RUNTIME

    /**
     * The `Sql` member [call] names when its callee can only be the runtime's entry: `Sql.x(...)`
     * with the runtime's `Sql` imported (or the runtime package star-imported and no other `Sql`
     * in sight), fully qualified, or an import (alias) of `Sql.x`. Null otherwise.
     */
    fun entryName(call: KtCallExpression): String? {
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val file = call.containingKtFile
        val qualified = (call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === call }
        if (qualified != null) {
            val receiver = qualified.receiverExpression.text
            return callee.takeIf { receiver == "$RUNTIME.Sql" || runtimeName(file, receiver, "Sql") }
        }
        if (declares(file, callee)) return null
        for (directive in file.importDirectives) {
            val imported = directive.importedFqName?.asString() ?: continue
            if (directive.isAllUnder) { if (imported == "$RUNTIME.Sql") return callee else continue }
            if (!imported.startsWith("$RUNTIME.Sql.")) continue
            val member = imported.removePrefix("$RUNTIME.Sql.")
            if ((directive.aliasName ?: member) == callee) return member
        }
        return null
    }

    /** Whether [function] carries an annotation that can only be the runtime's `@BrikkSql`. */
    fun annotated(function: KtNamedFunction): Boolean = function.annotationEntries.any { entry ->
        val written = entry.typeReference?.text ?: return@any false
        written == "$RUNTIME.BrikkSql" || runtimeName(function.containingKtFile, written, "BrikkSql")
    }

    /**
     * The syntax that decides ownership while Kotlin cannot resolve: annotations, the callee and
     * its receiver, trims, imports and top-level names. A decision made under one fingerprint does
     * not carry over to another.
     */
    fun fingerprint(syntax: BrikkKotlinContext.SyntaxContext): String = buildString {
        syntax.function.annotationEntries.forEach { append(it.text).append('\n') }
        append((syntax.call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === syntax.call }?.receiverExpression?.text)
        append('.').append(syntax.call.calleeExpression?.text).append('\n')
        syntax.trimCalls.forEach { append(it.calleeExpression?.text).append('\n') }
        val file = syntax.function.containingKtFile
        append(file.packageFqName.asString()).append('\n')
        file.importDirectives.forEach { append(it.text).append('\n') }
        file.declarations.mapNotNull { it.name }.sorted().forEach { append(it).append(',') }
    }

    /** Whether [written] in [file] names the runtime's top-level [name], by explicit import, alias, star import or package. */
    private fun runtimeName(file: KtFile, written: String, name: String): Boolean {
        if (declares(file, written)) return false
        val directives = file.importDirectives
        if (directives.any { !it.isAllUnder && it.importedFqName?.asString() == "$RUNTIME.$name" && (it.aliasName ?: name) == written }) return true
        if (written != name) return false
        // Another explicit import of the name shadows the star import.
        if (directives.any { !it.isAllUnder && it.aliasName == null && it.importedFqName?.shortName()?.asString() == name }) return false
        return file.packageFqName.asString() == RUNTIME || directives.any { it.isAllUnder && it.importedFqName?.asString() == RUNTIME }
    }

    private fun declares(file: KtFile, name: String): Boolean = file.declarations.any { it.name == name }
}
