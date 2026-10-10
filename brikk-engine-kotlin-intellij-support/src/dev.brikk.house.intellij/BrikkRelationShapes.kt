package dev.brikk.house.intellij

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.pom.Navigatable
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParameter

/** Reads effective type scopes, not generated class names, runtime relations or compiler-private FIR. */
public object BrikkRelationShapes {
    public fun ofParameter(parameter: KtParameter): ShapeResult {
        if (DumbService.isDumb(parameter.project)) return ShapeResult.Unavailable("Kotlin indexing is in progress")
        return BrikkKotlinContext.analysis(parameter) { parameterInSession(this, parameter) }
    }

    public fun ofExpression(expression: KtExpression): ShapeResult {
        if (DumbService.isDumb(expression.project)) return ShapeResult.Unavailable("Kotlin indexing is in progress")
        return BrikkKotlinContext.analysis(expression) {
            val type = expression.expressionType ?: return@analysis ShapeResult.Unavailable("Expression type is not resolved")
            read(this, type, expression)
        }
    }

    internal fun parameterInSession(session: KaSession, parameter: KtParameter): ShapeResult = with(session) {
        read(session, parameter.symbol.returnType, parameter)
    }

    private fun read(session: KaSession, relation: KaType, anchor: PsiElement): ShapeResult = with(session) {
        val expanded = relation.fullyExpandedType
        if (expanded is KaErrorType) return ShapeResult.Unavailable("Relation type is unresolved")
        val classType = expanded as? KaClassType ?: return ShapeResult.NotRelation
        if (classType.classId.asSingleFqName().asString() != "${BrikkKotlinContext.RUNTIME}.Rel") return ShapeResult.NotRelation
        val fallback = navigation(anchor, NavigationKind.RELATION_FALLBACK)
        val row = (classType.typeArguments.singleOrNull() as? KaTypeArgumentWithVariance)?.type?.fullyExpandedType
            ?: return ShapeResult.Available(RelationShape(ShapeKnowledge.PARTIAL, emptyList(), ShapeOrigin.UNKNOWN, fallback))
        if (row is KaErrorType) return ShapeResult.Unavailable("Row type is unresolved")
        val markerNames = setOf("${BrikkKotlinContext.RUNTIME}.Partial", "${BrikkKotlinContext.RUNTIME}.Shape")
        val rowClass = row as? KaClassType
        if (rowClass?.classId?.asSingleFqName()?.asString() in markerNames) {
            return ShapeResult.Available(RelationShape(ShapeKnowledge.PARTIAL, emptyList(), ShapeOrigin.UNKNOWN, fallback))
        }
        val bound = row is KaTypeParameterType
        val roots = if (row is KaTypeParameterType) row.symbol.upperBounds else listOf(row)
        val properties = linkedMapOf<String, ColumnContract>()
        var hasPartial = false
        var full = !bound
        for (root in roots) {
            val types = listOf(root) + root.allSupertypes.toList()
            val ids = types.mapNotNull { (it.fullyExpandedType as? KaClassType)?.classId?.asSingleFqName()?.asString() }.toSet()
            hasPartial = hasPartial || "${BrikkKotlinContext.RUNTIME}.Partial" in ids
            full = full && "${BrikkKotlinContext.RUNTIME}.Shape" in ids
            val scope = root.scope ?: return ShapeResult.Unavailable("Row member scope is unavailable")
            for (signature in scope.getCallableSignatures { true }) {
                val property = signature.symbol as? KaPropertySymbol ?: continue
                if (!property.isVal || property.isStatic || property.isExtension) continue
                val type = signature.returnType.fullyExpandedType
                if (type is KaErrorType) return ShapeResult.Unavailable("Column '${property.name}' type is unresolved")
                val id = (type as? KaClassType)?.classId?.asSingleFqName()?.asString()
                val nullability = when (type.nullability) {
                    KaTypeNullability.NON_NULLABLE -> ColumnNullability.NON_NULL
                    KaTypeNullability.NULLABLE -> ColumnNullability.NULLABLE
                    else -> ColumnNullability.UNKNOWN
                }
                val column = ColumnContract(property.name.asString(), id, nullability, BrikkTypeFamilies.forClassId(id),
                    property.psi?.let { navigation(it, NavigationKind.PROPERTY) } ?: fallback)
                val key = column.name.lowercase(java.util.Locale.ROOT)
                val previous = properties[key]
                if (previous != null && previous.kotlinClassId != column.kotlinClassId) {
                    return ShapeResult.Unsupported("Conflicting column contracts for '${column.name}'")
                }
                if (previous == null || previous.nullability != ColumnNullability.NON_NULL) properties[key] = column
            }
        }
        if (!hasPartial) return ShapeResult.Unsupported("Row type does not expose an Engine Partial contract")
        val generated = rowClass?.symbol?.origin in setOf(KaSymbolOrigin.PLUGIN, KaSymbolOrigin.SOURCE_MEMBER_GENERATED)
        if (generated && properties.isEmpty()) return ShapeResult.Unavailable("Generated row members are not available yet")
        val origin = when {
            bound -> ShapeOrigin.TYPE_BOUND
            generated -> ShapeOrigin.GENERATED
            rowClass?.symbol?.origin in setOf(KaSymbolOrigin.SOURCE, KaSymbolOrigin.JAVA_SOURCE) -> ShapeOrigin.DECLARED
            else -> ShapeOrigin.UNKNOWN
        }
        ShapeResult.Available(RelationShape(if (full) ShapeKnowledge.FULL else ShapeKnowledge.PARTIAL, properties.values.toList(), origin, fallback))
    }

    private fun navigation(element: PsiElement, kind: NavigationKind): NavigationTarget =
        PsiNavigation(SmartPointerManager.getInstance(element.project).createSmartPsiElementPointer(element), kind)

    private class PsiNavigation(private val pointer: SmartPsiElementPointer<PsiElement>, override val kind: NavigationKind) : NavigationTarget {
        override val location: SourceLocation? get() {
            val element = pointer.element ?: return null
            val url = element.containingFile?.virtualFile?.url ?: return null
            return SourceLocation(url, element.textRange.startOffset, element.textRange.endOffset)
        }
        override fun navigate(requestFocus: Boolean) { (pointer.element as? Navigatable)?.navigate(requestFocus) }
    }
}
