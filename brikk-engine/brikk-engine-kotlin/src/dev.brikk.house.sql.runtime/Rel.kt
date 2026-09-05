package dev.brikk.house.sql.runtime

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.ast.toIdentifier
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
     * are rewired to the CTE of the input feeding them. A single stage without inputs
     * renders directly.
     */
    fun render(target: String = dialect): String {
        val order = topologicalOrder()
        val bindings = bindingNames(order)
        val trees = order.associateWith { desugarPipes(SqlFragment(it.sql, it.dialect).ast, copy = true) }
        val reserved = trees.values.flatMap { it.findAll(Identifier::class).map { id -> id.name.lowercase() } }.toMutableSet()
        val names = HashMap<Rel<*>, String>()
        var next = 0
        for (node in order) {
            var name: String
            do { name = "s${next++}" } while (!reserved.add(name))
            names[node] = name
        }

        val gen = Dialects.forName(target)
        if (order.size == 1) {
            return gen.generate(standardTree(trees.getValue(this), emptyMap(), bindings.getValue(this)), sourceDialect = dialect)
        }

        val ctes = order.map { node ->
            val slotToCte = node.inputSlots.mapValues { (_, rel) -> names.getValue(rel) }
            val tree = node.standardTree(trees.getValue(node), slotToCte, bindings.getValue(node))
            "${names.getValue(node)} AS (${gen.generate(tree, sourceDialect = node.dialect)})"
        }
        return "WITH ${ctes.joinToString(", ")} SELECT * FROM ${names.getValue(order.last())}"
    }

    /** Desugared (non-pipe) AST with slot calls replaced by plain table references. */
    private fun standardTree(tree: Expression, slotToCte: Map<String, String>, bindNames: Map<String, String>): Expression {
        val byUpper = slotToCte.mapKeys { it.key.uppercase() }
        tree.transform(copy = false) { node ->
            if (node is Table) {
                val fn = node.thisArg as? Anonymous
                val cte = fn?.name?.uppercase()?.let { byUpper[it] }
                if (cte != null) {
                    if (node.args["alias"] == null) {
                        val alias = (fn.thisArg as? Identifier)?.copy()
                            ?: Identifier(args("this" to fn.name, "quoted" to false))
                        node.set("alias", TableAlias(args("this" to alias)))
                    }
                    node.set("this", Identifier(args("this" to cte, "quoted" to false)))
                }
            } else if (node is Placeholder || node is Parameter) {
                val name = bindNames[node.name]
                if (name != null && name != node.name) {
                    node.set("this", if (node is Parameter) toIdentifier(name) else name)
                }
            }
            node
        }
        return tree
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
