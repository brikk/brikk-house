package dev.brikk.house.sql.ast

// Explicit imports shield the kotlin builtins from same-package expression classes of
// the same name (generated nodes include Array, List, Map, Set, String).
import kotlin.String
import kotlin.collections.List

/**
 * Python-side Expression members that sqlglot defines as per-class property overrides
 * (`output_name`, `is_star`, `selects`, `named_selects`, `alias_column_names`).
 *
 * They are ported here as centralized dispatch extensions instead of per-class overrides
 * so the generated node files stay untouched. When EXPRESSION_METADATA codegen learns to
 * emit member properties, these dispatchers can be replaced by real overrides.
 */

// sqlglot: Expression.output_name and its overrides. The complete Python override set is
// Alias, Bracket, Cast, Column, Dot, Identifier, JSONExtract, JSONExtractScalar,
// JSONPath, Literal, Paren, Star, Subquery, TableColumn (default: "").
val Expression.outputName: String
    get() = when (this) {
        is Column -> name
        is Identifier -> name
        is Literal -> name
        is Star -> name
        is Alias -> alias // covers PivotAlias
        is Dot -> name
        is Paren -> (thisArg as Expression).name
        is Cast -> name // covers TryCast/JSONCast
        is Subquery -> alias
        is TableColumn -> name
        is Bracket ->
            if (expressionsArg.size == 1) (expressionsArg[0] as Expression).outputName else ""
        is JSONExtract ->
            if (expressionsArg.isEmpty()) (expressionArg as Expression).outputName else ""
        is JSONExtractScalar -> (expressionArg as Expression).outputName
        is JSONPath -> {
            val lastSegment = (expressionsArg.lastOrNull() as? Expression)?.thisArg
            if (lastSegment is String) lastSegment else ""
        }
        else -> ""
    }

// sqlglot: Expression.is_star and its overrides (Dot, Select, SetOperation, Subquery).
val Expression.isStar: kotlin.Boolean
    get() = when (this) {
        is Dot -> (expressionArg as Expression).isStar
        is Select -> expressionsArg.any { it is Expression && it.isStar }
        is SetOperation -> left.isStar || right.isStar
        is Subquery -> (thisArg as Expression).isStar
        else -> this is Star || (this is Column && thisArg is Star)
    }

// sqlglot: Expression.alias_column_names
val Expression.aliasColumnNames: List<String>
    get() {
        val tableAlias = args["alias"] as? Expression ?: return emptyList()
        val columns = tableAlias.args["columns"] as? List<*> ?: return emptyList()
        return columns.mapNotNull { (it as? Expression)?.name }
    }

// sqlglot: Selectable.selects and its overrides (Select, SetOperation, Table, Unnest,
// plus the DerivedTable/UDTF/DDL trait implementations).
val Expression.selects: List<Expression>
    get() = when (this) {
        // sqlglot: Select.selects
        is Select -> expressionsArg.filterIsInstance<Expression>()
        // sqlglot: SetOperation.selects (left-most non-set-op branch)
        is SetOperation -> {
            var expr: Expression = this
            while (expr is SetOperation) expr = expr.left.unnest()
            expr.selects // getattr(expr, "selects", []) — the else branch yields []
        }
        // sqlglot: array.Unnest.selects (UDTF selects + optional offset column)
        is Unnest -> {
            val columns = udtfSelects(this).toMutableList()
            val offset = args["offset"]
            if (offset == true) {
                columns.add(Identifier(args("this" to "offset", "quoted" to false)))
            } else if (offset is Expression) {
                columns.add(offset)
            }
            columns
        }
        // sqlglot: UDTF.selects (alias columns)
        is UDTF -> udtfSelects(this)
        // sqlglot: DerivedTable.selects (covers Subquery and CTE)
        is DerivedTable -> {
            val inner = thisArg
            if (inner is Expression && inner is Query) inner.selects else emptyList()
        }
        // sqlglot: DDL.selects
        is DDL -> {
            val inner = args["expression"]
            if (inner is Expression && inner is Query) inner.selects else emptyList()
        }
        // sqlglot: Table.selects = [] (and the non-Selectable default)
        else -> emptyList()
    }

private fun udtfSelects(node: Expression): List<Expression> {
    val alias = node.args["alias"] as? Expression ?: return emptyList()
    return (alias.args["columns"] as? List<*>)?.filterIsInstance<Expression>() ?: emptyList()
}

// sqlglot: Selectable.named_selects / _named_selects and the Select/SetOperation/Table/DDL
// overrides.
val Expression.namedSelects: List<String>
    get() = when (this) {
        // sqlglot: Select.named_selects
        is Select -> {
            val selected = mutableListOf<String>()
            for (e in expressionsArg) {
                if (e !is Expression) continue
                if (e.aliasOrName.isNotEmpty()) {
                    selected.add(e.outputName)
                } else if (e is Aliases) {
                    // sqlglot: Aliases.aliases
                    for (a in e.expressionsArg) if (a is Expression) selected.add(a.name)
                }
            }
            selected
        }
        // sqlglot: SetOperation.named_selects (maps _named_selects over the left-most branch)
        is SetOperation -> {
            var expr: Expression = this
            while (expr is SetOperation) expr = expr.left.unnest()
            expr.selects.map { it.outputName }
        }
        // sqlglot: Table.named_selects = []
        is Table -> emptyList()
        // sqlglot: DDL.named_selects
        is DDL -> {
            val inner = args["expression"]
            if (inner is Expression && inner is Query) inner.namedSelects else emptyList()
        }
        // sqlglot: Selectable._named_selects (covers Subquery/CTE/UDTF via their selects)
        else -> selects.map { it.outputName }
    }
