package dev.brikk.house.sql.ast

/**
 * Hand-written port of the node subset from reference/sqlglot/sqlglot/expressions/.
 * Each class mirrors its Python twin's base classes and arg_types EXACTLY (see the
 * provenance comment on each class). This is the pattern-settling spike; the full
 * ~1000-class catalog is expected to be generated later.
 *
 * NOTE: `Boolean` below shadows kotlin.Boolean inside this package, which is why
 * argTypes signatures spell out kotlin.Boolean.
 */

// sqlglot: core.QUERY_MODIFIERS
internal val QUERY_MODIFIERS: Map<String, kotlin.Boolean> = argTypesOf(
    "match" to false,
    "laterals" to false,
    "joins" to false,
    "connect" to false,
    "pivots" to false,
    "prewhere" to false,
    "where" to false,
    "group" to false,
    "having" to false,
    "qualify" to false,
    "windows" to false,
    "distribute" to false,
    "sort" to false,
    "cluster" to false,
    "order" to false,
    "limit" to false,
    "offset" to false,
    "locks" to false,
    "sample" to false,
    "settings" to false,
    "format" to false,
    "options" to false,
    "for_" to false,
)

// ---------------------------------------------------------------------------
// Leaf-ish nodes (sqlglot.expressions.core)
// ---------------------------------------------------------------------------

// sqlglot: core.Identifier(Expression) — is_primitive, _hash_raw_args
class Identifier(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES
    override val hashRawArgs get() = true
    override val isPrimitive get() = true

    // sqlglot: Identifier.quoted
    val quoted: kotlin.Boolean get() = args["quoted"] == true

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "quoted" to false, "global_" to false, "temporary" to false,
        )
    }
}

// sqlglot: core.Column(Expression, Condition)
class Column(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = ARG_TYPES

    // sqlglot: Column.table / db / catalog
    val table: String get() = text("table")
    val db: String get() = text("db")
    val catalog: String get() = text("catalog")

    // sqlglot: Column.parts ("catalog", "db", "table", "this" in order, non-null only)
    val parts: List<Expression>
        get() = listOf("catalog", "db", "table", "this").mapNotNull { args[it] as? Expression }

    // sqlglot: Column.to_dot
    fun toDot(includeDots: kotlin.Boolean = true): Expression {
        val parts = parts.toMutableList()
        var parent = this.parent
        if (includeDots) {
            while (parent is Dot) {
                parts.add(parent.expressionArg as Expression)
                parent = parent.parent
            }
        }
        return if (parts.size > 1) Dot.build(parts.map { it.copy() }) else parts[0]
    }

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "table" to false, "db" to false, "catalog" to false, "join_mark" to false,
        )
    }
}

// sqlglot: core.Dot(Expression, Binary)
class Dot(initArgs: Args = emptyMap()) : Binary(initArgs) {
    override val name: String get() = (expressionArg as Expression).name

    companion object {
        // sqlglot: Dot.build
        fun build(expressions: List<Expression>): Dot {
            require(expressions.size >= 2) { "Dot requires >= 2 expressions." }
            return expressions.reduce { x, y -> Dot(args("this" to x, "expression" to y)) } as Dot
        }
    }
}

// sqlglot: core.Star(Expression)
class Star(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES
    override val name: String get() = "*"

    companion object {
        private val ARG_TYPES = argTypesOf(
            "except_" to false, "replace" to false, "rename" to false, "ilike" to false,
        )
    }
}

// sqlglot: core.Literal(Expression, Condition) — is_primitive, _hash_raw_args
class Literal(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = ARG_TYPES
    override val hashRawArgs get() = true
    override val isPrimitive get() = true

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "is_string" to true)

        // sqlglot: Literal.number (string form, e.g. Literal.number(f"0.{...}") in the parser)
        fun number(number: String): Literal =
            Literal(args("this" to number, "is_string" to false))

        // sqlglot: Literal.number (negative numbers come back wrapped in Neg)
        fun number(number: Number): Expression {
            val d = number.toDouble()
            return if (d < 0) {
                Neg(args("this" to Literal(args("this" to formatNumber(number, negate = true), "is_string" to false))))
            } else {
                Literal(args("this" to formatNumber(number, negate = false), "is_string" to false))
            }
        }

        // sqlglot: Literal.string
        fun string(string: String): Literal =
            Literal(args("this" to string, "is_string" to true))

        private fun formatNumber(number: Number, negate: kotlin.Boolean): String {
            val s = when (number) {
                is Int, is Long -> (if (negate) -number.toLong() else number.toLong()).toString()
                else -> (if (negate) -number.toDouble() else number.toDouble()).toString()
            }
            return s
        }
    }
}

// sqlglot: core.Boolean(Expression, Condition) — is_primitive
class Boolean(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val isPrimitive get() = true
}

// sqlglot: core.Var(Expression) — is_primitive
class Var(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val isPrimitive get() = true
}

// sqlglot: core.Null(Expression, Condition) — arg_types = {}
class Null(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = ARG_TYPES
    override val name: String get() = "NULL"

    companion object {
        private val ARG_TYPES = emptyMap<String, kotlin.Boolean>()
    }
}

// sqlglot: core.Alias(Expression)
class Alias(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "alias" to false)
    }
}

// sqlglot: core.Parameter(Expression, Condition)
class Parameter(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to false)
    }
}

// sqlglot: core.Placeholder(Expression, Condition)
class Placeholder(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = ARG_TYPES
    override val name: String get() = text("this").ifEmpty { "?" }

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "kind" to false, "widget" to false, "jdbc" to false,
        )
    }
}

// ---------------------------------------------------------------------------
// Unary / binary operators and predicates (sqlglot.expressions.core)
// ---------------------------------------------------------------------------

// sqlglot: core.Not(Unary)
class Not(initArgs: Args = emptyMap()) : Unary(initArgs)

// sqlglot: core.Paren(Unary)
class Paren(initArgs: Args = emptyMap()) : Unary(initArgs)

// sqlglot: core.Neg(Unary)
class Neg(initArgs: Args = emptyMap()) : Unary(initArgs)

// sqlglot: core.And(Expression, Connector, Func)
class And(initArgs: Args = emptyMap()) : Connector(initArgs), Func

// sqlglot: core.Or(Expression, Connector, Func)
class Or(initArgs: Args = emptyMap()) : Connector(initArgs), Func

// sqlglot: core.Xor(Expression, Connector, Func)
class Xor(initArgs: Args = emptyMap()) : Connector(initArgs), Func {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expression" to true, "round_input" to false,
        )
    }
}

// sqlglot: core.EQ(Expression, Binary, Predicate)
class EQ(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.NEQ(Expression, Binary, Predicate)
class NEQ(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.NullSafeEQ(Expression, Binary, Predicate)
class NullSafeEQ(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.GT(Expression, Binary, Predicate)
class GT(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.GTE(Expression, Binary, Predicate)
class GTE(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.LT(Expression, Binary, Predicate)
class LT(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.LTE(Expression, Binary, Predicate)
class LTE(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.Is(Expression, Binary, Predicate)
class Is(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.Like(Expression, Binary, Predicate)
class Like(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to true, "negate" to false)
    }
}

// sqlglot: core.ILike(Expression, Binary, Predicate)
class ILike(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to true, "negate" to false)
    }
}

// sqlglot: core.In(Expression, Predicate)
class In(initArgs: Args = emptyMap()) : Expression(initArgs), Predicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expressions" to false, "query" to false,
            "unnest" to false, "field" to false, "is_global" to false,
        )
    }
}

// sqlglot: core.Between(Expression, Predicate)
class Between(initArgs: Args = emptyMap()) : Expression(initArgs), Predicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "low" to true, "high" to true, "symmetric" to false,
        )
    }
}

// sqlglot: core.Add(Expression, Binary)
class Add(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.Sub(Expression, Binary)
class Sub(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.Mul(Expression, Binary)
class Mul(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.Div(Expression, Binary)
class Div(initArgs: Args = emptyMap()) : Binary(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expression" to true, "typed" to false, "safe" to false,
        )
    }
}

// sqlglot: core.Mod(Expression, Binary)
class Mod(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.IntDiv(Expression, Binary)
class IntDiv(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.PropertyEQ(Expression, Binary)
class PropertyEQ(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.NullSafeNEQ(Expression, Binary, Predicate)
class NullSafeNEQ(initArgs: Args = emptyMap()) : Binary(initArgs), Predicate

// sqlglot: core.BitwiseAnd(Expression, Binary)
class BitwiseAnd(initArgs: Args = emptyMap()) : Binary(initArgs) {
    override val argTypes get() = BITWISE_BINARY_ARG_TYPES
}

// sqlglot: core.BitwiseOr(Expression, Binary)
class BitwiseOr(initArgs: Args = emptyMap()) : Binary(initArgs) {
    override val argTypes get() = BITWISE_BINARY_ARG_TYPES
}

// sqlglot: core.BitwiseXor(Expression, Binary)
class BitwiseXor(initArgs: Args = emptyMap()) : Binary(initArgs) {
    override val argTypes get() = BITWISE_BINARY_ARG_TYPES
}

// sqlglot: shared arg_types of core.BitwiseAnd/BitwiseOr/BitwiseXor
internal val BITWISE_BINARY_ARG_TYPES = argTypesOf(
    "this" to true, "expression" to true, "padside" to false,
)

// sqlglot: core.BitwiseNot(Unary)
class BitwiseNot(initArgs: Args = emptyMap()) : Unary(initArgs)

// sqlglot: core.DPipe(Expression, Binary)
class DPipe(initArgs: Args = emptyMap()) : Binary(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to true, "safe" to false)
    }
}

// sqlglot: core.Escape(Expression, Binary)
class Escape(initArgs: Args = emptyMap()) : Binary(initArgs)

// sqlglot: core.Aliases(Expression)
class Aliases(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expressions" to true)
    }
}

// sqlglot: core.SubqueryPredicate(Predicate)
interface SubqueryPredicate : Predicate

// sqlglot: core.Any(Expression, SubqueryPredicate)
class Any(initArgs: Args = emptyMap()) : Expression(initArgs), SubqueryPredicate

// sqlglot: core.All(Expression, SubqueryPredicate)
class All(initArgs: Args = emptyMap()) : Expression(initArgs), SubqueryPredicate

// sqlglot: core.Pow(Expression, Binary, Func) — _sql_names = ["POWER", "POW"]
class Pow(initArgs: Args = emptyMap()) : Binary(initArgs), Func {
    override val sqlNamesOverride get() = listOf("POWER", "POW")
}

// ---------------------------------------------------------------------------
// Query clause nodes (sqlglot.expressions.query unless noted)
// ---------------------------------------------------------------------------

// sqlglot: query.TableAlias(Expression)
class TableAlias(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    // sqlglot: TableAlias.columns
    @Suppress("UNCHECKED_CAST")
    val columns: List<kotlin.Any?> get() = (args["columns"] as? List<kotlin.Any?>) ?: emptyList()

    companion object {
        private val ARG_TYPES = argTypesOf("this" to false, "columns" to false)
    }
}

// sqlglot: query.Table(Expression, Selectable)
class Table(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    // sqlglot: Table.db / catalog
    val db: String get() = text("db")
    val catalog: String get() = text("catalog")

    // sqlglot: Table.parts ("catalog", "db", "this" in order, Dots flattened)
    val parts: List<Expression>
        get() {
            val out = mutableListOf<Expression>()
            for (key in listOf("catalog", "db", "this")) {
                when (val part = args[key]) {
                    is Dot -> {
                        // sqlglot: Binary.flatten over the Dot chain
                        var node: Expression = part
                        val stack = ArrayDeque<Expression>()
                        while (node is Dot) {
                            stack.addFirst(node.right)
                            node = node.left
                        }
                        out.add(node)
                        out.addAll(stack)
                    }
                    is Expression -> out.add(part)
                    else -> {}
                }
            }
            return out
        }

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "alias" to false, "db" to false, "catalog" to false,
            "laterals" to false, "joins" to false, "pivots" to false, "hints" to false,
            "system_time" to false, "version" to false, "format" to false, "pattern" to false,
            "ordinality" to false, "when" to false, "only" to false, "partition" to false,
            "changes" to false, "rows_from" to false, "sample" to false, "indexed" to false,
        )
    }
}

// sqlglot: query.Select(Expression, Query)
class Select(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    // sqlglot: Select.selects
    val selects: List<kotlin.Any?> get() = expressionsArg

    companion object {
        private val ARG_TYPES: Map<String, kotlin.Boolean> = LinkedHashMap(
            argTypesOf(
                "with_" to false, "kind" to false, "expressions" to false, "hint" to false,
                "distinct" to false, "into" to false, "from_" to false,
                "operation_modifiers" to false, "exclude" to false,
            )
        ).apply { putAll(QUERY_MODIFIERS) }
    }
}

// sqlglot: query.From(Expression)
class From(initArgs: Args = emptyMap()) : Expression(initArgs)

// sqlglot: query.Where(Expression)
class Where(initArgs: Args = emptyMap()) : Expression(initArgs)

// sqlglot: query.Having(Expression)
class Having(initArgs: Args = emptyMap()) : Expression(initArgs)

// sqlglot: query.Group(Expression)
class Group(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "expressions" to false, "grouping_sets" to false, "cube" to false,
            "rollup" to false, "totals" to false, "all" to false,
        )
    }
}

// sqlglot: query.Order(Expression)
class Order(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "expressions" to true, "siblings" to false,
        )
    }
}

// sqlglot: core.Ordered(Expression)
class Ordered(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "desc" to false, "nulls_first" to true, "with_fill" to false,
        )
    }
}

// sqlglot: query.Limit(Expression)
class Limit(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "expression" to true, "offset" to false,
            "limit_options" to false, "expressions" to false,
        )
    }
}

// sqlglot: query.Offset(Expression)
class Offset(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "expression" to true, "expressions" to false,
        )
    }
}

// sqlglot: core.Distinct(Expression)
class Distinct(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to false, "on" to false)
    }
}

// sqlglot: query.Join(Expression)
class Join(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    // sqlglot: Join.method / kind / side / hint
    val method: String get() = text("method").uppercase()
    val kind: String get() = text("kind").uppercase()
    val side: String get() = text("side").uppercase()
    val hint: String get() = text("hint").uppercase()

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "on" to false, "side" to false, "kind" to false,
            "using" to false, "method" to false, "global_" to false, "hint" to false,
            "match_condition" to false, "directed" to false, "expressions" to false,
            "pivots" to false,
        )
    }
}

// sqlglot: query.Subquery(Expression, DerivedTable, Query) — is_subquery
class Subquery(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES
    override val isSubquery get() = true

    // sqlglot: Subquery.is_wrapper
    val isWrapper: kotlin.Boolean
        get() = args.all { (k, v) -> k == "this" || v == null }

    companion object {
        private val ARG_TYPES: Map<String, kotlin.Boolean> = LinkedHashMap(
            argTypesOf("this" to true, "alias" to false, "with_" to false)
        ).apply { putAll(QUERY_MODIFIERS) }
    }
}

// sqlglot: query.CTE(Expression, DerivedTable)
class CTE(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "alias" to true, "scalar" to false,
            "materialized" to false, "key_expressions" to false,
        )
    }
}

// sqlglot: query.With(Expression)
class With(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    // sqlglot: With.recursive
    val recursive: kotlin.Boolean get() = args["recursive"] == true

    companion object {
        private val ARG_TYPES = argTypesOf(
            "expressions" to true, "recursive" to false, "search" to false,
        )
    }
}

// sqlglot: query.SetOperation(Expression, Query)
abstract class SetOperation(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = SET_OPERATION_ARG_TYPES

    // sqlglot: SetOperation.left / right
    val left: Expression get() = args["this"] as Expression
    val right: Expression get() = args["expression"] as Expression

    companion object {
        internal val SET_OPERATION_ARG_TYPES: Map<String, kotlin.Boolean> = LinkedHashMap(
            argTypesOf(
                "with_" to false, "this" to true, "expression" to true, "distinct" to false,
                "by_name" to false, "side" to false, "kind" to false, "on" to false,
            )
        ).apply { putAll(QUERY_MODIFIERS) }
    }
}

// sqlglot: query.Tuple(Expression)
class Tuple(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to false)
    }
}

// sqlglot: query.Window(Expression, Condition)
class Window(initArgs: Args = emptyMap()) : Expression(initArgs), Condition {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "partition_by" to false, "order" to false, "spec" to false,
            "alias" to false, "over" to false, "first" to false,
        )
    }
}

// sqlglot: query.Semicolon(Expression) — arg_types = {}
class Semicolon(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = emptyMap<String, kotlin.Boolean>()
    }
}

// sqlglot: query.Union(SetOperation)
class Union(initArgs: Args = emptyMap()) : SetOperation(initArgs)

// sqlglot: query.Except(SetOperation)
class Except(initArgs: Args = emptyMap()) : SetOperation(initArgs)

// sqlglot: query.Intersect(SetOperation)
class Intersect(initArgs: Args = emptyMap()) : SetOperation(initArgs)

// ---------------------------------------------------------------------------
// Functions (sqlglot.expressions.functions / aggregate / core)
// ---------------------------------------------------------------------------

// sqlglot: functions.Cast(Expression, Func) — is_cast
class Cast(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = ARG_TYPES
    override val isCast get() = true

    // sqlglot: Cast.to
    val to: DataType get() = args["to"] as DataType

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "to" to true, "format" to false,
            "safe" to false, "action" to false, "default" to false,
        )
    }
}

// sqlglot: functions.Case(Expression, Func)
class Case(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to false, "ifs" to true, "default" to false)
    }
}

// sqlglot: functions.If(Expression, Func) — _sql_names = ["IF", "IIF"]
class If(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = ARG_TYPES
    override val sqlNamesOverride get() = listOf("IF", "IIF")

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "true" to true, "false" to false)
    }
}

// sqlglot: functions.Exists(Expression, SubqueryPredicate)
class Exists(initArgs: Args = emptyMap()) : Expression(initArgs), SubqueryPredicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to false)
    }
}

// sqlglot: functions.Collate(Expression, Binary, Func)
class Collate(initArgs: Args = emptyMap()) : Binary(initArgs), Func

// sqlglot: functions.ConnectByRoot(Expression, Func)
class ConnectByRoot(initArgs: Args = emptyMap()) : Expression(initArgs), Func

// sqlglot: core.Anonymous(Expression, Func) — is_var_len_args
class Anonymous(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = ARG_TYPES
    override val isVarLenArgs get() = true
    override val name: String get() = thisArg as? String ?: (thisArg as Expression).name

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expressions" to false)
    }
}

// sqlglot: aggregate.Count(Expression, AggFunc) — is_var_len_args
class Count(initArgs: Args = emptyMap()) : Expression(initArgs), AggFunc {
    override val argTypes get() = ARG_TYPES
    override val isVarLenArgs get() = true

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "expressions" to false, "big_int" to false,
        )
    }
}

// sqlglot: aggregate.Sum(Expression, AggFunc)
class Sum(initArgs: Args = emptyMap()) : Expression(initArgs), AggFunc

// sqlglot: aggregate.Min(Expression, AggFunc) — is_var_len_args
class Min(initArgs: Args = emptyMap()) : Expression(initArgs), AggFunc {
    override val argTypes get() = ARG_TYPES
    override val isVarLenArgs get() = true

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expressions" to false)
    }
}

// sqlglot: aggregate.Max(Expression, AggFunc) — is_var_len_args
class Max(initArgs: Args = emptyMap()) : Expression(initArgs), AggFunc {
    override val argTypes get() = ARG_TYPES
    override val isVarLenArgs get() = true

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expressions" to false)
    }
}

// sqlglot: aggregate.Avg(Expression, AggFunc)
class Avg(initArgs: Args = emptyMap()) : Expression(initArgs), AggFunc

// sqlglot: aggregate.RowNumber(Expression, Func)
class RowNumber(initArgs: Args = emptyMap()) : Expression(initArgs), Func {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to false)
    }
}

// ---------------------------------------------------------------------------
// Misc (sqlglot.expressions.ddl)
// ---------------------------------------------------------------------------

// sqlglot: ddl.Command(Expression) — the parser's fallback node
class Command(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expression" to false)
    }
}
