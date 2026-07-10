package dev.brikk.house.sql.ast

// Explicit imports shield the kotlin builtins from same-package expression classes of
// the same name (generated nodes include Array, List, Map, Set, String).
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.reflect.KClass

/**
 * Child-arg storage type. Legal value types inside the map are:
 * [Expression], MutableList (of Expression or scalars), String, kotlin.Boolean,
 * Int, Long, Double, [DType] (for DataType's "this" arg) and null.
 */
typealias Args = Map<String, kotlin.Any?>

/** Convenience builder preserving insertion order (mirrors Python kwargs order). */
fun args(vararg pairs: Pair<String, kotlin.Any?>): Args = linkedMapOf(*pairs)

/** Convenience builder for argTypes tables. */
internal fun argTypesOf(vararg pairs: Pair<String, kotlin.Boolean>): Map<String, kotlin.Boolean> =
    linkedMapOf(*pairs)

internal val DEFAULT_ARG_TYPES: Map<String, kotlin.Boolean> = argTypesOf("this" to true)

/**
 * The base class for all expressions in a syntax tree.
 *
 * Port of sqlglot's `Expression` (reference/sqlglot/sqlglot/expressions/core.py). We keep
 * sqlglot's dynamic-args model: children live in [args], and [argTypes] describes the legal
 * arg keys (key -> required?).
 *
 * Python->Kotlin naming for the arg getters (Kotlin keywords force a rename):
 *  - `Expression.this`        -> [thisArg]
 *  - `Expression.expression`  -> [expressionArg]
 *  - `Expression.expressions` -> [expressionsArg]
 */
abstract class Expression(initArgs: Args = emptyMap()) {

    // sqlglot: Expression.args
    val args: MutableMap<String, kotlin.Any?> = LinkedHashMap<String, kotlin.Any?>(initArgs.size).also { m ->
        // Lists are canonicalized to mutable lists because set/append mutate them in
        // place, exactly like Python lists.
        for ((k, v) in initArgs) m[k] = if (v is List<*>) v.toMutableList() else v
    }

    // sqlglot: Expression.arg_types
    open val argTypes: Map<String, kotlin.Boolean> get() = DEFAULT_ARG_TYPES

    // sqlglot: Expression.parent / arg_key / index
    var parent: Expression? = null
    var argKey: String? = null
    var index: Int? = null

    // sqlglot: Expression.comments
    var comments: MutableList<String>? = null

    // sqlglot: Expression._meta (lazily created via the `meta` property)
    internal var metaOrNull: MutableMap<String, kotlin.Any?>? = null

    // sqlglot: Expression.meta
    val meta: MutableMap<String, kotlin.Any?>
        get() = metaOrNull ?: LinkedHashMap<String, kotlin.Any?>().also { metaOrNull = it }

    /**
     * Minimal type slot (sqlglot: Expression._type). Full DataType typing (optimizer's
     * annotate_types) is deferred; this exists so serde can round-trip the "t" payload.
     */
    var typeSlot: Expression? = null

    // sqlglot: Expression._hash_raw_args
    open val hashRawArgs: kotlin.Boolean get() = false

    // sqlglot: Expression.is_primitive (Literal/Identifier/Boolean/Var and string-literal kin)
    open val isPrimitive: kotlin.Boolean get() = false

    // sqlglot: Expression.is_cast / is_data_type / is_subquery
    open val isCast: kotlin.Boolean get() = false
    open val isDataType: kotlin.Boolean get() = false
    open val isSubquery: kotlin.Boolean get() = false

    // sqlglot: Expression.key (lowercase class name)
    val key: String get() = this::class.simpleName!!.lowercase()

    init {
        // sqlglot: Expression.__init__ (parent wiring of constructor args)
        for ((k, v) in args) setParent(k, v)
    }

    // sqlglot: Expression.this
    val thisArg: kotlin.Any? get() = args["this"]

    // sqlglot: Expression.expression
    val expressionArg: kotlin.Any? get() = args["expression"]

    // sqlglot: Expression.expressions
    @Suppress("UNCHECKED_CAST")
    val expressionsArg: List<kotlin.Any?> get() = (args["expressions"] as? List<kotlin.Any?>) ?: emptyList()

    // sqlglot: Expression.text
    fun text(key: String): String {
        val field = args[key]
        return when {
            field is String -> field
            field is Identifier || field is Literal || field is Var -> field.thisArg as? String ?: ""
            field is Star || field is Null -> field.name
            else -> ""
        }
    }

    // sqlglot: Expression.name
    open val name: String get() = text("this")

    // sqlglot: Expression.alias
    val alias: String
        get() {
            val alias = args["alias"]
            return if (alias is Expression) alias.name else text("alias")
        }

    // sqlglot: Expression.alias_or_name
    val aliasOrName: String get() = alias.ifEmpty { name }

    // sqlglot: Expression.type (getter). For casts the target type doubles as the node type.
    val type: Expression?
        get() = when {
            isDataType -> this
            isCast -> typeSlot ?: args["to"] as? Expression
            else -> typeSlot
        }

    // sqlglot: Expression.is_string
    val isString: kotlin.Boolean
        get() = this is Literal && args["is_string"] == true

    // sqlglot: Expression.is_number
    val isNumber: kotlin.Boolean
        get() = (this is Literal && args["is_string"] != true) ||
            (this is Neg && (thisArg as? Expression)?.isNumber == true)

    // sqlglot: Expression.append
    fun append(argKey: String, value: kotlin.Any?) {
        if (args[argKey] !is MutableList<*>) {
            args[argKey] = mutableListOf<kotlin.Any?>()
        }
        setParent(argKey, value)
        @Suppress("UNCHECKED_CAST")
        val values = args[argKey] as MutableList<kotlin.Any?>
        if (value is Expression) value.index = values.size
        values.add(value)
    }

    // sqlglot: Expression.set
    fun set(argKey: String, value: kotlin.Any?, index: Int? = null, overwrite: kotlin.Boolean = true) {
        var v: kotlin.Any? = if (value is List<*> && value !is Expression) value.toMutableList() else value

        if (index != null) {
            @Suppress("UNCHECKED_CAST")
            val expressions = (args[argKey] as? MutableList<kotlin.Any?>) ?: mutableListOf()

            if (expressions.getOrNull(index) == null) return

            if (v == null) {
                expressions.removeAt(index)
                for (rest in expressions.subList(index, expressions.size)) {
                    if (rest is Expression) rest.index = rest.index!! - 1
                }
                return
            }

            if (v is MutableList<*>) {
                expressions.removeAt(index)
                expressions.addAll(index, v)
            } else if (overwrite) {
                expressions[index] = v
            } else {
                expressions.add(index, v)
            }

            v = expressions
        } else if (v == null) {
            args.remove(argKey)
            return
        }

        args[argKey] = v
        setParent(argKey, v, index)
    }

    // sqlglot: Expression._set_parent
    private fun setParent(argKey: String, value: kotlin.Any?, index: Int? = null) {
        if (value is Expression) {
            value.parent = this
            value.argKey = argKey
            value.index = index
        } else if (value is List<*>) {
            for ((i, v) in value.withIndex()) {
                if (v is Expression) {
                    v.parent = this
                    v.argKey = argKey
                    v.index = i
                }
            }
        }
    }

    // sqlglot: Expression.add_comments
    fun addComments(newComments: List<String>?, prepend: kotlin.Boolean = false) {
        if (newComments.isNullOrEmpty()) return
        val existing = comments
        if (existing == null) {
            comments = newComments.toMutableList()
        } else if (prepend) {
            existing.addAll(0, newComments)
        } else {
            existing.addAll(newComments)
        }
    }

    // sqlglot: Expression.pop_comments
    fun popComments(): MutableList<String> {
        val popped = comments ?: mutableListOf()
        comments = null
        return popped
    }

    // sqlglot: Expression.depth
    val depth: Int get() = parent?.let { it.depth + 1 } ?: 0

    // sqlglot: Expression.iter_expressions
    fun iterExpressions(reverse: kotlin.Boolean = false): Sequence<Expression> = sequence {
        val values = if (reverse) args.values.reversed() else args.values.toList()
        for (vs in values) {
            if (vs is List<*>) {
                for (v in if (reverse) vs.reversed() else vs) {
                    if (v is Expression) yield(v)
                }
            } else if (vs is Expression) {
                yield(vs)
            }
        }
    }

    // sqlglot: Expression.walk
    fun walk(
        bfs: kotlin.Boolean = true,
        prune: ((Expression) -> kotlin.Boolean)? = null,
    ): Sequence<Expression> = if (bfs) bfs(prune) else dfs(prune)

    // sqlglot: Expression.dfs
    fun dfs(prune: ((Expression) -> kotlin.Boolean)? = null): Sequence<Expression> = sequence {
        val stack = ArrayDeque<Expression>()
        stack.addLast(this@Expression)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            yield(node)
            if (prune != null && prune(node)) continue
            for (v in node.iterExpressions(reverse = true)) stack.addLast(v)
        }
    }

    // sqlglot: Expression.bfs
    fun bfs(prune: ((Expression) -> kotlin.Boolean)? = null): Sequence<Expression> = sequence {
        val queue = ArrayDeque<Expression>()
        queue.addLast(this@Expression)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            yield(node)
            if (prune != null && prune(node)) continue
            for (v in node.iterExpressions()) queue.addLast(v)
        }
    }

    // sqlglot: Expression.find_all (single reified type; use the KClass overload for unions)
    inline fun <reified E : Expression> findAll(bfs: kotlin.Boolean = true): Sequence<E> =
        walk(bfs = bfs).filterIsInstance<E>()

    // sqlglot: Expression.find
    inline fun <reified E : Expression> find(bfs: kotlin.Boolean = true): E? =
        findAll<E>(bfs = bfs).firstOrNull()

    // sqlglot: Expression.find_all (multi-type variant)
    fun findAll(vararg types: KClass<out Expression>, bfs: kotlin.Boolean = true): Sequence<Expression> =
        walk(bfs = bfs).filter { node -> types.any { it.isInstance(node) } }

    // sqlglot: Expression.find (multi-type variant)
    fun find(vararg types: KClass<out Expression>, bfs: kotlin.Boolean = true): Expression? =
        findAll(*types, bfs = bfs).firstOrNull()

    // sqlglot: Expression.find_ancestor
    inline fun <reified E : Expression> findAncestor(): E? {
        var ancestor = parent
        while (ancestor != null && ancestor !is E) ancestor = ancestor.parent
        return ancestor
    }

    // sqlglot: Expression.find_ancestor (multi-type variant)
    fun findAncestor(vararg types: KClass<out Expression>): Expression? {
        var ancestor = parent
        while (ancestor != null && types.none { it.isInstance(ancestor) }) ancestor = ancestor.parent
        return ancestor
    }

    // sqlglot: Expression.same_parent
    val sameParent: kotlin.Boolean get() = parent?.let { it::class == this::class } ?: false

    // sqlglot: Expression.root
    fun root(): Expression {
        var expression: Expression = this
        while (true) expression = expression.parent ?: return expression
    }

    // sqlglot: Expression.unnest
    fun unnest(): Expression {
        var expression: Expression = this
        while (expression is Paren) expression = expression.thisArg as Expression
        return expression
    }

    // sqlglot: Expression.unalias
    fun unalias(): Expression = if (this is Alias) thisArg as Expression else this

    // sqlglot: Expression.__deepcopy__ / copy (deep copy detached from any parent)
    fun copy(): Expression {
        val root = newInstance()
        val stack = ArrayDeque<Pair<Expression, Expression>>()
        stack.addLast(this to root)

        while (stack.isNotEmpty()) {
            val (node, copy) = stack.removeLast()

            node.comments?.let { copy.comments = it.toMutableList() }
            node.typeSlot?.let { copy.typeSlot = it.copy() }
            node.metaOrNull?.let { copy.metaOrNull = LinkedHashMap(it) }

            for ((k, vs) in node.args) {
                if (vs is Expression) {
                    val child = vs.newInstance()
                    stack.addLast(vs to child)
                    copy.set(k, child)
                } else if (vs is List<*>) {
                    copy.args[k] = mutableListOf<kotlin.Any?>()
                    for (v in vs) {
                        if (v is Expression) {
                            val child = v.newInstance()
                            stack.addLast(v to child)
                            copy.append(k, child)
                        } else {
                            copy.append(k, v)
                        }
                    }
                } else {
                    copy.args[k] = vs
                }
            }
        }

        return root
    }

    private fun newInstance(): Expression =
        ExpressionRegistry.newInstance(this::class.simpleName!!)

    // sqlglot: Expression.transform. fn returning null removes the node (root removal is an error).
    fun transform(copy: kotlin.Boolean = true, fn: (Expression) -> Expression?): Expression {
        var root: Expression? = null
        var newNode: Expression? = null

        val start = if (copy) this.copy() else this
        for (node in start.dfs(prune = { it !== newNode })) {
            val parent = node.parent
            val argKey = node.argKey
            val index = node.index
            newNode = fn(node)

            if (root == null) {
                root = newNode
            } else if (parent != null && argKey != null && newNode !== node) {
                parent.set(argKey, newNode, index)
            }
        }

        return checkNotNull(root) { "transform removed the root node" }
    }

    // sqlglot: Expression.replace. `expression` may be an Expression, a List (splice), or null.
    fun replace(expression: kotlin.Any?): kotlin.Any? {
        val parent = this.parent

        if (parent == null || parent === expression) return expression

        val key = argKey
        if (key != null) {
            val value = parent.args[key]

            if (expression is List<*> && value is Expression) {
                // We are trying to replace an Expression with a list, so it's assumed that
                // the intention was to really replace the parent of this expression.
                value.parent?.replace(expression)
            } else {
                parent.set(key, expression, index)
            }
        }

        if (expression !== this) {
            this.parent = null
            this.argKey = null
            this.index = null
        }

        return expression
    }

    // sqlglot: Expression.pop
    fun pop(): Expression {
        replace(null)
        return this
    }

    /**
     * Structural equality mirroring sqlglot's hashing semantics (core.py Expression.__hash__ /
     * __eq__): same class + normalized args. parent/comments/meta/type are ignored.
     *
     * Normalization rules (from Python's __hash__):
     *  - regular nodes: args whose value is null or false compare equal to absent args;
     *    empty lists compare equal to absent args; null/false items inside lists are
     *    "present but valueless"; strings are compared case-insensitively.
     *  - hashRawArgs nodes (Literal, Identifier): falsy args (null, false, "", 0) compare
     *    equal to absent args; strings are compared case-sensitively.
     *
     * Deviation from Python: sqlglot's __eq__ is `type(a) is type(b) and hash(a) == hash(b)`
     * (hash-collision based). We implement true structural equality under the same
     * normalization, which is strictly more accurate.
     */
    final override fun equals(other: kotlin.Any?): kotlin.Boolean {
        if (this === other) return true
        if (other !is Expression || other::class != this::class) return false
        return canonicalArgs() == other.canonicalArgs()
    }

    final override fun hashCode(): Int = key.hashCode() * 31 + canonicalArgs().hashCode()

    internal fun canonicalArgs(): Map<String, kotlin.Any?> {
        val out = HashMap<String, kotlin.Any?>(args.size)
        if (hashRawArgs) {
            for ((k, v) in args) {
                if (isTruthy(v)) out[k] = canonicalNumber(v)
            }
        } else {
            for ((k, v) in args) {
                if (v is List<*>) {
                    if (v.isNotEmpty()) {
                        out[k] = v.map { if (it == null || it == false) AbsentValue else canonicalValue(it) }
                    }
                } else if (v != null && v != false) {
                    out[k] = canonicalValue(v)
                }
            }
        }
        return out
    }

    private fun canonicalValue(v: kotlin.Any?): kotlin.Any? = when (v) {
        is String -> v.lowercase()
        else -> canonicalNumber(v)
    }

    private fun canonicalNumber(v: kotlin.Any?): kotlin.Any? = if (v is Int) v.toLong() else v

    private fun isTruthy(v: kotlin.Any?): kotlin.Boolean = when (v) {
        null, false, "", 0, 0L, 0.0 -> false
        is List<*> -> v.isNotEmpty()
        else -> true
    }

    /** Marker for null/false items inside list args ("present but valueless" in the hash). */
    private object AbsentValue

    override fun toString(): String = buildString { toS(this@Expression, this) }

    private fun toS(node: kotlin.Any?, sb: StringBuilder) {
        when (node) {
            is Expression -> {
                sb.append(node::class.simpleName).append('(')
                var first = true
                for ((k, v) in node.args) {
                    if (v == null || (v is List<*> && v.isEmpty())) continue
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(k).append('=')
                    toS(v, sb)
                }
                sb.append(')')
            }
            is List<*> -> {
                sb.append('[')
                for ((i, v) in node.withIndex()) {
                    if (i > 0) sb.append(", ")
                    toS(v, sb)
                }
                sb.append(']')
            }
            else -> sb.append(node)
        }
    }
}

// ---------------------------------------------------------------------------
// Trait hierarchy. Python models Condition/Predicate/Binary/Unary/Connector/Func
// as mixin "traits" with multiple inheritance (e.g. `class EQ(Expression, Binary,
// Predicate)`). Kotlin has no multiple class inheritance, so pure markers become
// interfaces while arg-carrying traits (Binary, Unary, Connector) become abstract
// classes; nodes mixing Binary/Unary with Predicate/Func implement the interfaces.
// ---------------------------------------------------------------------------

// sqlglot: core.Condition ("Logical conditions like x AND y, or simply x")
interface Condition

// sqlglot: core.Predicate ("Relationships like x = y, x > 1, x >= y")
interface Predicate : Condition

// sqlglot: core.Func (base class for all function expressions)
interface Func : Condition {
    // sqlglot: Func.is_var_len_args
    val isVarLenArgs: kotlin.Boolean get() = false

    // sqlglot: Func._sql_names (null -> derived from the class name, see sqlNames())
    val sqlNamesOverride: List<String>? get() = null
}

// sqlglot: Func.sql_names
fun Func.sqlNames(): List<String> =
    sqlNamesOverride ?: listOf(camelToSnakeCase((this as Expression)::class.simpleName!!))

// sqlglot: Func.sql_name
fun Func.sqlName(): String = sqlNames().first()

// sqlglot: helper.camel_to_snake_case
internal fun camelToSnakeCase(name: String): String = buildString {
    for ((i, c) in name.withIndex()) {
        if (c.isUpperCase() && i > 0) append('_')
        append(c.uppercaseChar())
    }
}

// sqlglot: core.AggFunc
interface AggFunc : Func

// sqlglot: core.Binary(Condition) with arg_types {"this": True, "expression": True}
abstract class Binary(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes: Map<String, kotlin.Boolean> get() = BINARY_ARG_TYPES

    // sqlglot: Binary.left / Binary.right
    val left: Expression get() = args["this"] as Expression
    val right: Expression get() = args["expression"] as Expression

    companion object {
        internal val BINARY_ARG_TYPES = argTypesOf("this" to true, "expression" to true)
    }
}

// sqlglot: core.Connector(Binary)
abstract class Connector(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.Unary(Expression, Condition). Open + concrete like Python's Unary
// (instantiable, though never instantiated directly by the parser).
open class Unary(initArgs: Args = emptyMap()) : Expression(initArgs), Condition
