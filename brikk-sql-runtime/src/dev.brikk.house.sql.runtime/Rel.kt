package dev.brikk.house.sql.runtime

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.shape.SqlFragment

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
    /** The fragment text as analyzed by the plugin (headless pipes carry the `FROM __src()` prefix). */
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

    /** Scalar bindings of this node and every upstream node, in dependency order. */
    fun bindings(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (node in topologicalOrder()) out.putAll(node.scalarBindings)
        return out
    }

    /**
     * Renders the pipeline as a single standard-SQL statement:
     * `WITH s0 AS (...), s1 AS (...) SELECT * FROM sN`, where each stage's slot references
     * are rewired to the CTE of the input feeding them. A single stage without inputs
     * renders directly.
     */
    fun render(target: String = dialect): String {
        val order = topologicalOrder()
        val names = HashMap<Rel<*>, String>()
        order.forEachIndexed { i, node -> names[node] = "s$i" }

        val gen = Dialects.forName(target)
        if (order.size == 1) return gen.generate(order[0].standardTree(emptyMap()))

        val ctes = order.map { node ->
            val slotToCte = node.inputSlots.mapValues { (_, rel) -> names.getValue(rel) }
            "${names.getValue(node)} AS (${gen.generate(node.standardTree(slotToCte))})"
        }
        return "WITH ${ctes.joinToString(", ")} SELECT * FROM ${names.getValue(order.last())}"
    }

    /** Desugared (non-pipe) AST with slot calls replaced by plain table references. */
    private fun standardTree(slotToCte: Map<String, String>): Expression {
        val fragment = SqlFragment(sql, dialect)
        val tree = desugarPipes(fragment.ast, copy = true)
        if (slotToCte.isEmpty()) return tree
        val byUpper = slotToCte.mapKeys { it.key.uppercase() }
        tree.transform(copy = false) { node ->
            if (node is Table) {
                val fn = node.thisArg as? Anonymous
                val cte = fn?.name?.uppercase()?.let { byUpper[it] }
                if (cte != null) node.set("this", Identifier(args("this" to cte, "quoted" to false)))
            }
            node
        }
        return tree
    }

    private fun topologicalOrder(): List<Rel<*>> {
        val seen = LinkedHashSet<Rel<*>>()
        fun visit(node: Rel<*>) {
            if (node in seen) return
            node.inputSlots.values.forEach { visit(it) }
            seen.add(node)
        }
        visit(this)
        return seen.toList()
    }

    override fun toString(): String = "Rel($dialect: $sql; inputs=${inputSlots.keys}; bindings=${scalarBindings.keys})"

    companion object {
        /** Slot name the plugin assigns to the first `Rel` parameter of a headless (`|> ...`) pipe. */
        const val SOURCE_SLOT: String = "__src"

        /** Prefix the plugin prepends to headless pipe text so it parses as a full pipe query. */
        const val SOURCE_PREFIX: String = "FROM $SOURCE_SLOT() "
    }
}
