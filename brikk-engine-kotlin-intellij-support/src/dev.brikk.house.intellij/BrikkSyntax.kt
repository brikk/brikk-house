package dev.brikk.house.intellij

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Brikk evidence from syntax and imports alone, for when Kotlin cannot resolve (indexing, an
 * unresolved callee). Strong evidence decides injection claims and errs toward not claiming;
 * weak evidence decides the execution guard and errs toward Brikk.
 */
internal object BrikkSyntax {
    private const val RUNTIME = BrikkKotlinContext.RUNTIME

    /**
     * Strong: the `Sql` member [call] names when its callee can only be the runtime's entry:
     * `Sql.x(...)` with the runtime's `Sql` explicitly imported (or aliased), fully qualified, or
     * an explicit import (alias) of `Sql.x`; and no lexical binding of that name in scope. Star
     * imports never count: a same-package or other star-imported `Sql` could win. Null otherwise.
     */
    fun entryName(call: KtCallExpression): String? {
        val callee = calleeName(call) ?: return null
        val receiver = receiver(call)
        if (receiver != null) return callee.takeIf { receiver == "$RUNTIME.Sql" || explicitRuntime(call, receiver, "$RUNTIME.Sql") }
        if (bound(call, callee)) return null
        val imports = call.containingKtFile.importDirectives.filter { !it.isAllUnder && effectiveName(it) == callee }
        val member = imports.singleOrNull()?.importedFqName?.asString()?.takeIf { it.startsWith("$RUNTIME.Sql.") } ?: return null
        return member.removePrefix("$RUNTIME.Sql.")
    }

    /** Strong: [function] carries an annotation that can only be the runtime's `@BrikkSql`. */
    fun annotated(function: KtNamedFunction): Boolean = function.annotationEntries.any { entry ->
        val written = entry.typeReference?.text ?: return@any false
        written == "$RUNTIME.BrikkSql" || explicitRuntime(function, written, "$RUNTIME.BrikkSql")
    }

    /**
     * Weak: [syntax] could be a Brikk host, ignoring shadowing. Its callee is named `Sql.x`, or
     * reached through an import, alias or in-file typealias chain of an `Sql`, `Sql.x` or any
     * explicitly imported name (which may be a typealias elsewhere), and its function has an
     * annotation named `BrikkSql` or reached the same way. Star imports count.
     */
    fun weak(syntax: BrikkKotlinContext.SyntaxContext): Boolean = weakEntry(syntax.call) && weakAnnotation(syntax.function)

    private fun weakEntry(call: KtCallExpression): Boolean {
        val callee = calleeName(call) ?: return false
        val file = call.containingKtFile
        val receiver = receiver(call)
        if (receiver != null) return names(file, receiver).any { name ->
            lastSegment(name) == "Sql" || file.importDirectives.any { !it.isAllUnder && effectiveName(it) == name }
        }
        return file.importDirectives.any { directive ->
            val imported = directive.importedFqName?.asString() ?: return@any false
            if (directive.isAllUnder) lastSegment(imported) == "Sql"
            else effectiveName(directive) == callee && imported.substringBeforeLast('.', "").let { lastSegment(it) == "Sql" }
        }
    }

    private fun weakAnnotation(function: KtNamedFunction): Boolean = function.annotationEntries.any { entry ->
        val written = entry.typeReference?.text ?: return@any false
        val file = function.containingKtFile
        names(file, written).any { name -> lastSegment(name) == "BrikkSql" || file.importDirectives.any { !it.isAllUnder && effectiveName(it) == name } }
    }

    /** [written] and what it names through the file's typealias chain; a cycle ends the chain with nothing. */
    private fun names(file: KtFile, written: String): List<String> {
        val aliases = file.declarations.filterIsInstance<KtTypeAlias>().associateBy { it.name }
        val chain = mutableListOf(written)
        while (true) {
            val alias = aliases[chain.last()] ?: return chain
            val target = alias.getTypeReference()?.text?.substringBefore('<')?.trim() ?: return chain
            if (target in chain) return listOf(written)
            chain += target
        }
    }

    /**
     * The syntax that decides ownership while Kotlin cannot resolve: annotations, the callee and
     * its receiver, trims, package and imports, top-level names, every typealias with its target,
     * and the lexical bindings in scope (parameters, locals, members, declarations) named like the
     * receiver, callee, annotations or the runtime's `Sql`/`BrikkSql`. A remembered projection or
     * negative made under one fingerprint does not carry over to another.
     */
    fun fingerprint(syntax: BrikkKotlinContext.SyntaxContext): String = buildString {
        syntax.function.annotationEntries.forEach { append(it.text).append('\n') }
        val receiver = receiver(syntax.call)
        append(receiver).append('.').append(syntax.call.calleeExpression?.text).append('\n')
        syntax.trimCalls.forEach { append(it.text).append('\n') }
        val file = syntax.function.containingKtFile
        append(file.packageFqName.asString()).append('\n')
        file.importDirectives.forEach { append(it.text).append('\n') }
        file.declarations.mapNotNull { it.name }.sorted().forEach { append(it).append(',') }
        append('\n')
        file.declarations.filterIsInstance<KtTypeAlias>().forEach { append(it.text).append('\n') }
        val relevant = buildSet {
            add("Sql"); add("BrikkSql")
            receiver?.let { add(it.substringBefore('.')) }
            calleeName(syntax.call)?.let(::add)
            syntax.function.annotationEntries.mapNotNullTo(this) { it.typeReference?.text?.substringBefore('.') }
        }
        for (scope in listOf<PsiElement>(syntax.call, syntax.function)) {
            bindings(scope).filter { it.name in relevant }.forEach { append(it.javaClass.simpleName).append(':').append(it.name).append(',') }
            append('\n')
        }
    }

    /** Whether [written] at [context] names [fqName] by one explicit import (or alias), unshadowed, or by its own package. */
    private fun explicitRuntime(context: PsiElement, written: String, fqName: String): Boolean {
        if ('.' in written || bound(context, written)) return false
        val file = context.containingFile as? KtFile ?: return false
        val imports = file.importDirectives.filter { !it.isAllUnder && effectiveName(it) == written }
        if (imports.isNotEmpty()) return imports.all { it.importedFqName?.asString() == fqName }
        return file.packageFqName.asString() == RUNTIME && lastSegment(fqName) == written
    }

    /** Whether [name] is declared in a lexical scope enclosing [element]: parameters, locals, members, file declarations. */
    private fun bound(element: PsiElement, name: String): Boolean = bindings(element).any { it.name == name }

    private fun bindings(element: PsiElement): Sequence<KtNamedDeclaration> = generateSequence(element.parent) { it.parent }.flatMap { scope ->
        when (scope) {
            is KtBlockExpression -> scope.statements.asSequence().filterIsInstance<KtNamedDeclaration>()
            is KtFunction -> scope.valueParameters.asSequence()
            is KtClassOrObject -> scope.declarations.asSequence().filterIsInstance<KtNamedDeclaration>() + scope.primaryConstructorParameters.asSequence()
            is KtFile -> scope.declarations.asSequence().filterIsInstance<KtNamedDeclaration>()
            else -> emptySequence()
        }
    }

    private fun calleeName(call: KtCallExpression): String? = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

    private fun receiver(call: KtCallExpression): String? =
        (call.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression === call }?.receiverExpression?.text

    private fun effectiveName(directive: org.jetbrains.kotlin.psi.KtImportDirective): String? =
        directive.aliasName ?: directive.importedFqName?.shortName()?.asString()

    private fun lastSegment(name: String): String = name.substringAfterLast('.')
}
