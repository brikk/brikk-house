package dev.brikk.house.sql.ast

// Explicit imports shield the kotlin builtins from same-package expression classes.
import kotlin.String
import kotlin.collections.Map

/**
 * Brikk-native pipe-syntax AST nodes (NO sqlglot counterpart).
 *
 * sqlglot desugars pipe syntax (`FROM t |> WHERE x |> ...`) at parse time into
 * `WITH __tmpN AS (...)` CTE chains (parser.py `_parse_pipe_syntax_query`), destroying the
 * stage structure. Brikk keeps pipe stages FIRST-CLASS in the AST: the parser produces a
 * [PipeQuery] holding the pipeline head plus an ordered list of stage nodes, and
 * desugaring is an explicit transform ([desugarPipes] in PipeDesugar.kt).
 *
 * Stage nodes wrap the same inner nodes the standard clauses use (Where, Order, Limit,
 * Join, TableSample, Pivot, ...), so they walk/copy/compare like any other Expression.
 *
 * These classes are registered in the serde registry under the NATIVE module
 * "brikk.pipes" (see [registerNativePipeNodes]); tools/gen_ast_nodes.py scrapes this file
 * into its handwritten skip-list.
 */

/**
 * A pipe-syntax query: `this` is the pipeline head (whatever the parser produced for it —
 * a Select, a FROM-only `SELECT * FROM t` shape, a Subquery or a set operation), and
 * `expressions` is the ordered list of Pipe* stage nodes.
 */
class PipeQuery(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "expressions" to true)
    }
}

/** `|> SELECT <projections>` — expressions holds the projection list. */
class PipeSelect(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}

/** `|> EXTEND <projections>` — expressions holds the added projections. */
class PipeExtend(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}

/** `|> AS <alias>` — alias is a [TableAlias]. */
class PipeAs(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("alias" to true)
    }
}

/** `|> WHERE <condition>` — this is a [Where] node. */
class PipeWhere(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/**
 * `|> AGGREGATE <aggregates> [GROUP BY <fields> | GROUP AND ORDER BY <fields>]`.
 *
 * `expressions` holds the aggregate projections and `group` the group-by fields, both
 * exactly as parsed by sqlglot's `_parse_pipe_syntax_aggregate_fields`: elements may be
 * Alias-wrapped (`x AS y`) and/or Ordered-wrapped (trailing ASC/DESC), carrying exactly
 * what desugaring needs. `group_and_order` is true for the GROUP AND ORDER BY variant.
 */
class PipeAggregate(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "expressions" to true, "group" to false, "group_and_order" to false,
        )
    }
}

/** `|> DISTINCT` — no args. */
class PipeDistinct(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = emptyMap<String, kotlin.Boolean>()
    }
}

/** `|> ORDER BY <ordered...>` — this is an [Order] node. */
class PipeOrderBy(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/** `|> LIMIT n [OFFSET m]` — this is a [Limit] node, offset an optional [Offset]. */
class PipeLimit(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "offset" to false)
    }
}

/** `|> OFFSET m` — this is an [Offset] node (GoogleSQL extended form). */
class PipeOffset(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/** `|> TABLESAMPLE ...` — this is a [TableSample] node. */
class PipeTableSample(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/** `|> PIVOT(...)` — expressions holds the parsed [Pivot] list. */
class PipePivot(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}

/** `|> UNPIVOT(...)` — expressions holds the parsed [Pivot] list (unpivot=true). */
class PipeUnpivot(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}

/** `|> [CROSS|LEFT|...] JOIN t ON/USING ...` — this is a [Join] node. */
class PipeJoin(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/**
 * `|> [side] UNION|INTERSECT|EXCEPT [ALL|DISTINCT] [BY NAME] (q1)[, (q2), ...]`.
 *
 * `this` is the operator name ("UNION" | "EXCEPT" | "INTERSECT"), `expressions` the
 * unwrapped right-hand-side queries; distinct/by_name/side/kind/on mirror
 * sqlglot's SetOperation args as captured by `parse_set_operation`.
 */
class PipeSetOperation(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expressions" to true, "distinct" to false,
            "by_name" to false, "side" to false, "kind" to false, "on" to false,
        )
    }
}

/** Serde registrations for the NATIVE pipe nodes (module "brikk.pipes"). */
internal fun registerNativePipeNodes(m: kotlin.collections.MutableMap<String, ExpressionRegistry.Entry>) {
    val module = "brikk.pipes"
    m["PipeQuery"] = ExpressionRegistry.Entry(module) { PipeQuery() }
    m["PipeSelect"] = ExpressionRegistry.Entry(module) { PipeSelect() }
    m["PipeExtend"] = ExpressionRegistry.Entry(module) { PipeExtend() }
    m["PipeAs"] = ExpressionRegistry.Entry(module) { PipeAs() }
    m["PipeWhere"] = ExpressionRegistry.Entry(module) { PipeWhere() }
    m["PipeAggregate"] = ExpressionRegistry.Entry(module) { PipeAggregate() }
    m["PipeDistinct"] = ExpressionRegistry.Entry(module) { PipeDistinct() }
    m["PipeOrderBy"] = ExpressionRegistry.Entry(module) { PipeOrderBy() }
    m["PipeLimit"] = ExpressionRegistry.Entry(module) { PipeLimit() }
    m["PipeOffset"] = ExpressionRegistry.Entry(module) { PipeOffset() }
    m["PipeTableSample"] = ExpressionRegistry.Entry(module) { PipeTableSample() }
    m["PipePivot"] = ExpressionRegistry.Entry(module) { PipePivot() }
    m["PipeUnpivot"] = ExpressionRegistry.Entry(module) { PipeUnpivot() }
    m["PipeJoin"] = ExpressionRegistry.Entry(module) { PipeJoin() }
    m["PipeSetOperation"] = ExpressionRegistry.Entry(module) { PipeSetOperation() }
}

/** Simple names of all NATIVE (brikk-original) expression classes. */
val NATIVE_EXPRESSION_CLASSES: kotlin.collections.Set<String> = setOf(
    "PipeQuery", "PipeSelect", "PipeExtend", "PipeAs", "PipeWhere", "PipeAggregate",
    "PipeDistinct", "PipeOrderBy", "PipeLimit", "PipeOffset", "PipeTableSample",
    "PipePivot", "PipeUnpivot", "PipeJoin", "PipeSetOperation",
)
