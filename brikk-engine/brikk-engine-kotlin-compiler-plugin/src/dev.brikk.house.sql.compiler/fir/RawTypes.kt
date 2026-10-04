package dev.brikk.house.sql.compiler.fir

import dev.brikk.house.sql.compiler.analysis.rethrowIfCancellation
import dev.brikk.house.sql.compiler.analysis.PluginGuard
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.FirUserTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/** Import-aware names usable before body/type resolution, including aliases and nested classes. */
internal class RawTypes(
    private val session: FirSession,
    file: FirFile?,
    private val packageName: FqName,
    private val known: Set<ClassId> = session.brikkSql.knownShapeIds,
    source: KtSourceElement? = null,
) {
    private val imports = sourceImports(file, source)

    fun name(ref: FirTypeRef, typeParameters: Set<String> = emptySet()): String? {
        if (ref is FirResolvedTypeRef) {
            val type = ref.coneType
            if (type is ConeTypeParameterType) return type.lookupTag.name.asString()
            return type.classId?.asSingleFqName()?.asString()
        }
        val text = (ref as? FirUserTypeRef)?.qualifier?.joinToString(".") { it.name.asString() } ?: return null
        if (text in typeParameters) return text
        return resolve(text)?.asSingleFqName()?.asString() ?: text
    }

    fun argument(ref: FirTypeRef, typeParameters: Set<String>, visiting: Set<ClassId> = emptySet()): String? = when (ref) {
        is FirUserTypeRef -> {
            val arguments = ref.qualifier.lastOrNull()?.typeArgumentList?.typeArguments.orEmpty()
                .map { (it as? FirTypeProjectionWithVariance)?.typeRef?.let { arg -> name(arg, typeParameters) } }
            val text = ref.qualifier.joinToString(".") { it.name.asString() }
            val id = resolve(text, expandAlias = false)
            val alias = id?.takeUnless { it in known }?.let { session.symbolProvider.getClassLikeSymbolByClassId(it) as? FirTypeAliasSymbol }
            if (alias == null || id == null) arguments.firstOrNull() else {
                check(id !in visiting) { "Cyclic SQL shape type alias '$id'" }
                val formals = alias.fir.typeParameters.map { it.symbol.name.asString() }
                val expansion = RawTypes(session, session.firProvider.getFirClassifierContainerFileIfAny(alias), id.packageFqName, known, alias.fir.source)
                    .argument(alias.fir.expandedTypeRef, typeParameters + formals, visiting + id)
                val index = formals.indexOf(expansion)
                if (index < 0) expansion else arguments.getOrNull(index)
            }
        }
        is FirResolvedTypeRef -> ref.coneType.typeArguments.firstOrNull()?.type?.let {
            if (it is ConeTypeParameterType) it.lookupTag.name.asString() else it.classId?.asSingleFqName()?.asString()
        }
        else -> null
    }

    fun resolve(text: String, visiting: Set<ClassId> = emptySet(), expandAlias: Boolean = true): ClassId? {
        val first = text.substringBefore('.')
        val suffix = text.removePrefix(first)
        val explicit = imports.filter { !it.isAllUnder &&
            (it.alias ?: it.fqName.shortName().asString()) == first }
            .mapNotNull { find(it.fqName.asString() + suffix) }.distinct()
        val local = find(if (packageName.isRoot) text else "$packageName.$text")
        val stars = imports.filter { it.isAllUnder }.mapNotNull { find("${it.fqName}.$text") }.distinct()
        val id = when {
            explicit.size > 1 -> error("Ambiguous imported SQL shape type '$text'")
            explicit.isNotEmpty() -> explicit.single()
            local != null -> local
            text.contains('.') -> find(text)
            stars.size > 1 -> error("Ambiguous star-imported SQL shape type '$text'")
            stars.isNotEmpty() -> stars.single()
            else -> find("kotlin.$text") ?: find("java.lang.$text")
        } ?: return null
        if (!expandAlias) return id
        if (id in known) return id // Do not recursively request a generated output being built.
        val alias = session.symbolProvider.getClassLikeSymbolByClassId(id) as? FirTypeAliasSymbol ?: return id
        check(id !in visiting) { "Cyclic SQL shape type alias '$id'" }
        val aliasFile = session.firProvider.getFirClassifierContainerFileIfAny(alias)
        val resolver = RawTypes(session, aliasFile, id.packageFqName, known, alias.fir.source)
        val ref = alias.fir.expandedTypeRef
        if (ref is FirResolvedTypeRef) return ref.coneType.classId
        val expanded = (ref as? FirUserTypeRef)?.qualifier?.joinToString(".") { it.name.asString() } ?: return null
        return resolver.resolve(expanded, visiting + id)
    }

    private fun find(fq: String): ClassId? {
        val matches = known.filter { it.asSingleFqName().asString() == fq }
        check(matches.size <= 1) { "Ambiguous package/nested SQL shape identity '$fq'" }
        matches.singleOrNull()?.let { return it }
        val parts = fq.split('.')
        for (split in parts.size - 1 downTo 0) {
            val id = ClassId(FqName(parts.take(split).joinToString(".")), FqName(parts.drop(split).joinToString(".")), false)
            try {
                if (session.symbolProvider.getClassLikeSymbolByClassId(id) != null) return id
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                PluginGuard.note("raw type lookup failed for '$id'") { e.toString() }
            }
        }
        return null
    }
}
