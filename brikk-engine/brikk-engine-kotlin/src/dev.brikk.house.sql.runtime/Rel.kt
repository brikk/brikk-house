package dev.brikk.house.sql.runtime

import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.shape.PreservationDiagnostic
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable

/** Executable SQL plus per-stage reports of portions that needed regeneration. */
data class RelRenderResult(val sql: String, val stages: List<RelRenderStage>)

/**
 * A report for one upstream stage. [name] is its composed CTE name (null for a standalone
 * stage). Diagnostic ranges index [sourceSql], after Engine's slot/binding-name edits,
 * not the final composed SQL. No approximate map is presented as an exact output map.
 */
data class RelRenderStage(
    val name: String?,
    val sourceSql: String,
    val sourceDialect: String,
    val targetDialect: String,
    val diagnostics: List<PreservationDiagnostic>,
    val unsupportedMessages: List<String>,
)

/**
 * A relation-valued pipeline node: one SQL fragment plus its table inputs and scalar
 * bindings. `T` is the compile-time shape of the rows this relation produces — a plugin-
 * generated [Shape], or a [Partial] for the declared type of a generic pipe. `T` is erased
 * at runtime: this class is the same object regardless of the static shape.
 *
 * Instances are built by the compiler plugin's rewrite of `Sql.<dialect>(...)`; the fluent
 * [input]/[bind] calls exist so that rewrite is a plain chain of calls.
 *
 * Nothing runs on construction. [render] composes the whole upstream graph into one
 * statement (CTE chain) in the target dialect; [bindings] collects the scalar parameters
 * it references.
 */
class Rel<out T : Partial>(
    /**
     * The fragment text as written. `Rel` inputs appear in it as table-valued slot calls named
     * after the parameter (`FROM src() |> ...`, `JOIN other() ON ...`); [input] binds them.
     */
    val sql: String,
    val dialect: String,
) {
    private val inputSlots = LinkedHashMap<String, Rel<*>>()
    private val scalarBindings = LinkedHashMap<String, Any?>()

    /** Table-valued input: the fragment's `slot()` call in table position is fed by [rel]. */
    fun input(slot: String, rel: Rel<*>): Rel<T> = apply { inputSlots[slot] = rel }

    /** Scalar parameter: the fragment's `:name` placeholder takes [value]. */
    fun bind(name: String, value: Any?): Rel<T> = apply { scalarBindings[name] = value }

    val inputs: Map<String, Rel<*>> get() = inputSlots

    /** Bindings matching [render]; colliding names from different nodes are namespaced. */
    fun bindings(): Map<String, Any?> {
        val order = topologicalOrder()
        val names = bindingNames(order)
        val out = LinkedHashMap<String, Any?>()
        for (node in order) {
            for ((name, value) in node.scalarBindings) out[names.getValue(node).getValue(name)] = value
        }
        return out
    }

    private fun bindingNames(order: List<Rel<*>>): Map<Rel<*>, Map<String, String>> {
        val byNode = order.associateWith { node ->
            node.scalarBindings.keys + SqlFragment(node.sql, node.dialect).scalarParams.mapNotNull { it.name }
        }
        // Include unbound names so one node cannot accidentally supply another's missing value.
        // Reserve case-insensitively because some targets fold named parameters.
        val counts = byNode.values.flatten().groupingBy { it.lowercase() }.eachCount()
        val reserved = counts.keys.toMutableSet()
        return order.mapIndexed { index, node ->
            var next = 0
            node to byNode.getValue(node).associateWith { name ->
                if (counts.getValue(name.lowercase()) == 1) name else {
                    var generated: String
                    do { generated = "__brikk_bind_${index}_${next++}" } while (!reserved.add(generated))
                    generated
                }
            }
        }.toMap()
    }

    /**
     * Renders the pipeline as a single standard-SQL statement:
     * `WITH s0 AS (...), s1 AS (...) SELECT * FROM sN`, where each stage's slot references
     * are rewired to the CTE of the input feeding them. Native stages already in the
     * target dialect preserve their text; only slot names, colliding binding names and
     * embedded statement terminators are edited. Pipes and FROM-first normalization
     * use SQL-library source-preserving lowering; explicit translation regenerates
     * only the stages that need a dialect change. Use [renderWithDiagnostics] to inspect
     * which portions needed structural regeneration and any unsupported warnings.
     * Driver-specific placeholder adaptation is separate from this SQL representation.
     */
    fun render(target: String = dialect): String = renderWithDiagnostics(target).sql

    /** [render] with the SQL library's regeneration/unsupported reports for every stage. */
    fun renderWithDiagnostics(target: String = dialect): RelRenderResult {
        val order = topologicalOrder()
        val bindings = bindingNames(order)
        val fragments = order.associateWith { SqlFragment(it.sql, it.dialect) }
        val targetDialect = Dialects.forName(target).name
        val reports = mutableListOf<RelRenderStage>()
        fun renderStage(node: Rel<*>, name: String?, slots: Map<String, String>): String {
            val source = fragments.getValue(node).rewriteInputs(slots, bindings.getValue(node), target)
            val result = SqlFragment(source, node.dialect).toSourcePreservingExecutable(target)
            reports += RelRenderStage(
                name, source, Dialects.forName(node.dialect).name, targetDialect,
                result.diagnostics, result.unsupportedMessages,
            )
            return if (name == null) result.sql else embedQuery(result.sql, target)
        }

        if (order.size == 1) {
            return RelRenderResult(renderStage(this, null, emptyMap()), reports.toList())
        }
        val reserved = fragments.values.flatMap {
            it.ast.findAll(Identifier::class).map { id -> id.name.lowercase() }
        }.toMutableSet()
        val names = HashMap<Rel<*>, String>()
        var next = 0
        for (node in order) {
            var name: String
            do { name = "s${next++}" } while (!reserved.add(name))
            names[node] = name
        }

        val ctes = order.map { node ->
            val slotToCte = node.inputSlots.mapValues { (_, rel) -> names.getValue(rel) }
            val rendered = renderStage(node, names.getValue(node), slotToCte)
            "${names.getValue(node)} AS ($rendered)"
        }
        return RelRenderResult(
            "WITH ${ctes.joinToString(", ")} SELECT * FROM ${names.getValue(order.last())}", reports.toList(),
        )
    }

    private fun topologicalOrder(): List<Rel<*>> {
        val seen = LinkedHashSet<Rel<*>>()
        val visiting = HashSet<Rel<*>>()
        fun visit(node: Rel<*>) {
            if (node in seen) return
            require(visiting.add(node)) { "Cyclic Rel inputs" }
            node.inputSlots.values.forEach { visit(it) }
            visiting.remove(node)
            seen.add(node)
        }
        visit(this)
        return seen.toList()
    }

    override fun toString(): String = "Rel($dialect: $sql; inputs=${inputSlots.keys}; bindings=${scalarBindings.keys})"
}
