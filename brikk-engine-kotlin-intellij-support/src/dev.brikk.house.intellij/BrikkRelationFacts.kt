package dev.brikk.house.intellij

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.ColumnOrigin
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.Nullability
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** What [BrikkRelationFacts] knows about one SQL file's relations. */
public sealed interface BrikkRelationFactsResult {
    /** Copied [facts]; [revision] is equal exactly when the facts are. */
    public data class Available(public val facts: RelationFacts, public val revision: Long) : BrikkRelationFactsResult

    /** Not known yet: the host's Kotlin analysis or one of its contracts is unavailable. Retry; cache nothing. */
    public data class Pending(public val reason: String) : BrikkRelationFactsResult
}

/**
 * How Brikk columns are labeled, as plain data: "[declared] (<rel>)" for a column with a Kotlin
 * property, "[contract] (<rel>)" for one known only through its relation. [brikkColumnStyle]
 * asks for the consumer's Brikk column style (the bricks icon), not its generic column style.
 * Computed and physical columns keep the consumer's own labels.
 */
public data class BrikkColumnLabels(
    public val declared: String = ColumnContract.COLUMN_LABEL,
    public val contract: String = ColumnContract.RELATION_CONTRACT_LABEL,
    public val brikkColumnStyle: Boolean = true,
)

/**
 * Relation facts of Brikk host strings, as brikk-sql core data: each `Rel` parameter of the
 * host's function is a slot (`src()`), its contract the slot's columns. Facts are pending while
 * the host's Kotlin analysis, or any of its contracts, is unavailable; a non-Brikk host has none.
 *
 * A column's SQL type is set only when its contract knows it (Kotlin types never prove one); its
 * Kotlin type is the display type. Provenance names the Kotlin property, or for a column known
 * only through its relation (a generated one) and for the relation itself, the `Rel` parameter.
 *
 * Depends on core only, not on brikk-sql support: a plugin adapts [facts], [navigationTarget]
 * and [LABELS] to its support provider in a few lines (see the README). [dialect] restricts the
 * facts to hosts of one dialect; null answers for any Brikk host. Call only from an optional
 * Kotlin integration.
 */
public class BrikkRelationFacts(private val dialect: BrikkDialect? = null) {
    /** The facts for [file], an injected fragment of a Kotlin host string; null when it is none of this provider's. */
    public fun facts(file: PsiFile): BrikkRelationFactsResult? {
        val host = host(file) ?: return null
        return when (val computed = CachedValuesManager.getManager(host.project).getCachedValue(host, CACHE, { compute(host) }, false)) {
            is Computed.Facts -> if (dialect == null || computed.dialect == dialect) BrikkRelationFactsResult.Available(computed.facts, computed.revision) else null
            is Computed.Pending -> if (dialect == null || computed.dialect == null || computed.dialect == dialect) BrikkRelationFactsResult.Pending(computed.reason) else null
            Computed.None -> null
        }
    }

    /** The Kotlin element a fact's [provenance] names in [file]'s current facts; null when there is none. */
    public fun navigationTarget(file: PsiFile, provenance: String): PsiElement? {
        val host = host(file) ?: return null
        val targets = host.getUserData(TARGETS) ?: run { facts(file); host.getUserData(TARGETS) } ?: return null
        return (targets[provenance] as? BrikkRelationShapes.PsiNavigation)?.element
    }

    private fun host(file: PsiFile): KtStringTemplateExpression? =
        InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) as? KtStringTemplateExpression

    private sealed interface Computed {
        class Facts(val dialect: BrikkDialect, val facts: RelationFacts, val revision: Long) : Computed
        class Pending(val reason: String, val dialect: BrikkDialect?) : Computed
        data object None : Computed
    }

    public companion object {
        /** Kotlin-origin columns are `brikk column (<rel>)` with their Kotlin type; relation-level ones `relation contract (<rel>)`. */
        @JvmField public val LABELS: BrikkColumnLabels = BrikkColumnLabels()

        private val CACHE: Key<CachedValue<Computed>> = Key.create("dev.brikk.house.intellij.relationFacts")
        private val TARGETS: Key<Map<String, NavigationTarget>> = Key.create("dev.brikk.house.intellij.relationTargets")

        /** Slot facts of a host function's relation contracts; null when any contract is unavailable (the host is pending). */
        @JvmStatic public fun factsOf(relations: Map<String, ShapeResult>): RelationFacts? = mapped(relations)?.first

        private fun relationId(name: String) = "brikk:relation:$name"
        private fun columnId(relation: String, column: String) = "brikk:column:$relation:$column"

        private fun mapped(relations: Map<String, ShapeResult>): Pair<RelationFacts, Map<String, NavigationTarget>>? {
            val targets = HashMap<String, NavigationTarget>()
            val slots = LinkedHashMap<String, RelationFact>()
            for ((name, result) in relations) {
                val relation = relationId(name)
                slots[name] = when (result) {
                    is ShapeResult.Available -> {
                        result.shape.navigation?.let { targets[relation] = it }
                        val columns = result.shape.columns.map { column ->
                            val declared = column.navigation?.kind == NavigationKind.PROPERTY
                            val provenance = if (declared) columnId(name, column.name) else relation
                            if (declared) targets[provenance] = column.navigation!!
                            ColumnFact(column.name, column.exactSqlType, column.kotlinTypeName, when (column.nullability) {
                                ColumnNullability.NON_NULL -> Nullability.NON_NULL
                                ColumnNullability.NULLABLE -> Nullability.NULLABLE
                                ColumnNullability.UNKNOWN -> Nullability.UNKNOWN
                            }, if (declared) ColumnOrigin.DECLARED else ColumnOrigin.RELATION_CONTRACT, provenance)
                        }
                        RelationFact(columns, if (result.shape.knowledge == ShapeKnowledge.FULL) Knowledge.FULL else Knowledge.PARTIAL, relation)
                    }
                    is ShapeResult.Unsupported -> RelationFact(emptyList(), Knowledge.UNKNOWN, relation)
                    is ShapeResult.Unavailable -> return null
                    ShapeResult.NotRelation -> continue
                }
            }
            return RelationFacts(slots = slots) to targets
        }

        private fun compute(host: KtStringTemplateExpression): CachedValueProvider.Result<Computed> {
            val pending = { value: Computed -> CachedValueProvider.Result.create(value, ModificationTracker.EVER_CHANGED) }
            return when (val state = BrikkHosts.state(host, includeShapes = true)) {
                is BrikkHostState.Recognized -> {
                    val mapped = mapped(state.context.relations)
                    if (mapped == null) {
                        val (name, result) = state.context.relations.entries.first { it.value is ShapeResult.Unavailable }
                        pending(Computed.Pending("Relation '$name': ${(result as ShapeResult.Unavailable).reason}", state.dialect))
                    } else {
                        host.putUserData(TARGETS, mapped.second)
                        CachedValueProvider.Result.create(Computed.Facts(state.dialect, mapped.first, revision(mapped.first)),
                            PsiModificationTracker.getInstance(host.project), ProjectRootModificationTracker.getInstance(host.project))
                    }
                }
                is BrikkHostState.Pending -> pending(Computed.Pending(state.reason, state.dialect))
                BrikkHostState.NotBrikk -> {
                    host.putUserData(TARGETS, null)
                    CachedValueProvider.Result.create(Computed.None, PsiModificationTracker.getInstance(host.project),
                        DumbService.getInstance(host.project).modificationTracker)
                }
            }
        }

        /** Equal facts share a revision across reparsed hosts and unrelated edits; changed facts get another. */
        private fun revision(facts: RelationFacts): Long {
            var hash = -0x340d631b7bdddcdbL
            for (char in facts.toString()) hash = (hash xor char.code.toLong()) * 0x100000001b3L
            return hash
        }
    }
}
