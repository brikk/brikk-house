package dev.brikk.house.sql.ast

// Explicit imports shield the kotlin builtins from same-package expression classes.
import kotlin.String
import kotlin.collections.List
import kotlin.collections.MutableList

/**
 * Explicit pipe-syntax desugaring: rewrites every [PipeQuery] in the tree into the
 * `WITH __tmpN AS (...)` CTE chain sqlglot's parser would have produced directly.
 *
 * This is a direct port of the desugar semantics in reference/sqlglot/sqlglot/parser.py
 * (`_build_pipe_cte` and the `_parse_pipe_syntax_*` methods, ~9869-10061), operating on
 * brikk's first-class stage nodes post-parse instead of on the token stream:
 *
 *  - stages that mutate the current Select in place: WHERE (AND-merge), ORDER BY
 *    (replace, not append), LIMIT (keep-min) / OFFSET (sum), DISTINCT, TABLESAMPLE, JOIN;
 *  - stages that wrap into CTEs: SELECT, AGGREGATE, EXTEND, AS (user alias), set
 *    operations, PIVOT/UNPIVOT;
 *  - head normalization: Subquery / FROM-less heads become `SELECT * FROM (...)`.
 *
 * The `__tmp{N}` counter is per top-level statement and follows sqlglot's parse-order
 * numbering: a stage's nested subpipelines are desugared before the stage itself builds
 * its CTEs, and the pipeline head before any stage.
 */
fun desugarPipes(expression: Expression, copy: kotlin.Boolean = true): Expression {
    val root = if (copy) expression.copy() else expression
    // sqlglot: Parser._pipe_cte_counter (per-statement)
    val counter = PipeCteCounter()
    return desugarNode(root, counter)
}

private class PipeCteCounter {
    var n: Int = 0
}

private fun desugarNode(node: Expression, counter: PipeCteCounter): Expression {
    if (node is PipeQuery) return desugarPipeQuery(node, counter)
    desugarChildren(node, counter)
    return node
}

/** Desugars nested PipeQueries in arg-insertion order (approximates parse order). */
private fun desugarChildren(node: Expression, counter: PipeCteCounter) {
    for ((key, value) in node.args.entries.toList()) {
        if (value is Expression) {
            val desugared = desugarNode(value, counter)
            if (desugared !== value) node.set(key, desugared)
        } else if (value is List<*>) {
            for ((i, item) in value.withIndex()) {
                if (item is Expression) {
                    val desugared = desugarNode(item, counter)
                    if (desugared !== item) node.set(key, desugared, index = i)
                }
            }
        }
    }
}

// sqlglot: Parser._parse_pipe_syntax_query (head normalization + stage loop)
private fun desugarPipeQuery(pipeQuery: PipeQuery, counter: PipeCteCounter): Expression {
    var query = desugarNode(pipeQuery.thisArg as Expression, counter)

    if (query is Subquery) {
        query = selectStarFrom(query)
    }

    if (query.args["from_"] == null) {
        query = selectStarFrom(Subquery(args("this" to query)))
    }

    for (stage in pipeQuery.expressionsArg) {
        stage as Expression
        // A stage's nested subpipelines were parsed (and therefore numbered) before the
        // stage's own CTEs — desugar them first to reproduce sqlglot's __tmpN order.
        desugarChildren(stage, counter)
        query = applyPipeStage(query, stage, counter)
    }

    return query
}

// sqlglot: Parser._build_pipe_cte
private fun buildPipeCte(
    query: Expression,
    expressions: List<Expression>,
    counter: PipeCteCounter,
    aliasCte: TableAlias? = null,
): Select {
    val newCte: TableAlias = aliasCte ?: run {
        counter.n += 1
        TableAlias(args("this" to Identifier(args("this" to "__tmp${counter.n}", "quoted" to false))))
    }

    val ctes = (query.args["with_"] as? With)?.also { it.pop() }

    // sqlglot: exp.select(*expressions, copy=False).from_(new_cte, copy=False). A string
    // cte name parses to a Table; a TableAlias instance is used verbatim (From(this=alias)).
    val fromThis: Expression = if (aliasCte != null) {
        aliasCte
    } else {
        Table(args("this" to (newCte.thisArg as Expression)))
    }
    val newSelect = Select(
        args(
            "expressions" to expressions.toMutableList(),
            "from_" to From(args("this" to fromThis)),
        )
    )
    if (ctes != null) newSelect.set("with_", ctes)

    // sqlglot: new_select.with_(new_cte, as_=query, copy=False)
    val cte = CTE(args("this" to query, "alias" to newCte))
    val with_ = newSelect.args["with_"] as? With
    if (with_ == null) {
        newSelect.set("with_", With(args("expressions" to mutableListOf<Expression>(cte))))
    } else {
        with_.append("expressions", cte)
    }

    return newSelect
}

private fun applyPipeStage(query: Expression, stage: Expression, counter: PipeCteCounter): Expression =
    when (stage) {
        // sqlglot: Parser._parse_pipe_syntax_select
        is PipeSelect -> {
            query.set("expressions", stage.args["expressions"])
            buildPipeCte(query, listOf(Star()), counter)
        }

        // sqlglot: Parser._parse_pipe_syntax_extend
        is PipeExtend -> {
            val exprs = mutableListOf<Expression>(Star())
            for (e in stage.expressionsArg) exprs.add(e as Expression)
            query.set("expressions", exprs)
            buildPipeCte(query, listOf(Star()), counter)
        }

        // sqlglot: PIPE_SYNTAX_TRANSFORM_PARSERS["AS"]
        is PipeAs -> buildPipeCte(query, listOf(Star()), counter, aliasCte = stage.args["alias"] as TableAlias)

        // sqlglot: PIPE_SYNTAX_TRANSFORM_PARSERS["WHERE"] — query.where(...) AND-merges
        is PipeWhere -> {
            val where = stage.thisArg as Where
            val existing = query.args["where"] as? Where
            if (existing == null) {
                query.set("where", where)
            } else {
                existing.set(
                    "this",
                    and_(existing.thisArg as Expression, where.thisArg as Expression),
                )
            }
            query
        }

        // sqlglot: PIPE_SYNTAX_TRANSFORM_PARSERS["ORDER BY"] — replace, not append
        is PipeOrderBy -> {
            query.set("order", stage.thisArg)
            query
        }

        // sqlglot: Parser._parse_pipe_syntax_limit — keep-min limit, summed offset
        is PipeLimit -> {
            val limit = stage.thisArg as? Limit
            if (limit != null) {
                val currLimit = query.args["limit"] as? Limit ?: limit
                if (literalLong(currLimit.expressionArg) >= literalLong(limit.expressionArg)) {
                    query.set("limit", limit)
                }
            }
            applyPipeOffset(query, stage.args["offset"] as? Offset)
            query
        }

        is PipeOffset -> {
            applyPipeOffset(query, stage.thisArg as? Offset)
            query
        }

        // sqlglot: PIPE_SYNTAX_TRANSFORM_PARSERS["DISTINCT"] — query.distinct(copy=False)
        is PipeDistinct -> {
            query.set("distinct", Distinct())
            query
        }

        // sqlglot: Parser._parse_pipe_syntax_tablesample
        is PipeTableSample -> {
            val sample = stage.thisArg
            val with_ = query.args["with_"] as? With
            if (with_ != null) {
                val lastCte = with_.expressionsArg.last() as CTE
                (lastCte.thisArg as Expression).set("sample", sample)
            } else {
                query.set("sample", sample)
            }
            query
        }

        // sqlglot: Parser._parse_pipe_syntax_pivot
        is PipePivot, is PipeUnpivot -> {
            val from = query.args["from_"] as? From
            if (from != null) {
                (from.thisArg as Expression).set("pivots", stage.args["expressions"])
            }
            buildPipeCte(query, listOf(Star()), counter)
        }

        // sqlglot: Parser._parse_pipe_syntax_join
        is PipeJoin -> {
            if (query is Select) query.append("joins", stage.thisArg)
            query
        }

        // sqlglot: Parser._parse_pipe_syntax_aggregate
        is PipeAggregate -> {
            var q: Expression = query
            q = applyAggregateGroupOrderBy(q, stage.expressionsArg, groupByExists = false)
            val group = stage.args["group"] as? List<*>
            if (group != null) {
                q = applyAggregateGroupOrderBy(q, group, groupByExists = true)
            }
            buildPipeCte(q, listOf(Star()), counter)
        }

        // sqlglot: Parser._parse_pipe_syntax_set_operator
        is PipeSetOperation -> {
            var q: Expression = buildPipeCte(query, listOf(Star()), counter)
            val ctes = (q.args["with_"] as? With)?.also { it.pop() }

            for (setop in stage.expressionsArg) {
                q = makeSetOperation(stage, q, setop as Expression)
            }
            if (ctes != null) q.set("with_", ctes)

            buildPipeCte(q, listOf(Star()), counter)
        }

        else -> error("Unknown pipe stage node: ${stage::class.simpleName}")
    }

// sqlglot: Parser._parse_pipe_syntax_limit (offset part)
private fun applyPipeOffset(query: Expression, offset: Offset?) {
    if (offset == null) return
    val currOffset = (query.args["offset"] as? Offset)?.let { literalLong(it.expressionArg) } ?: 0L
    query.set(
        "offset",
        Offset(args("expression" to Literal.number(currOffset + literalLong(offset.expressionArg)))),
    )
}

// sqlglot: Parser._parse_pipe_syntax_aggregate_group_order_by
private fun applyAggregateGroupOrderBy(
    query: Expression,
    elements: List<kotlin.Any?>,
    groupByExists: kotlin.Boolean,
): Expression {
    val aggregatesOrGroups = mutableListOf<Expression>()
    val orders = mutableListOf<Expression>()

    for (element in elements) {
        element as Expression
        val this_: Expression
        if (element is Ordered) {
            val inner = element.thisArg as Expression
            this_ = inner
            if (inner is Alias) {
                element.set("this", inner.args["alias"])
            }
            orders.add(element)
        } else {
            this_ = element
        }
        aggregatesOrGroups.add(this_)
    }

    if (groupByExists) {
        val newExprs = mutableListOf<Expression>()
        newExprs.addAll(aggregatesOrGroups)
        for (e in query.expressionsArg) newExprs.add(e as Expression)
        query.set("expressions", newExprs)

        // sqlglot: .group_by(*[projection.args.get("alias", projection) ...], copy=False)
        val groupExprs = aggregatesOrGroups.map { (it.args["alias"] ?: it) as Expression }
        query.set("group", Group(args("expressions" to groupExprs)))
    } else {
        query.set("expressions", aggregatesOrGroups)
    }

    if (orders.isNotEmpty()) {
        query.set("order", Order(args("expressions" to orders)))
    }

    return query
}

// sqlglot: Query.union/except_/intersect with **first_setop.args
private fun makeSetOperation(stage: PipeSetOperation, left: Expression, right: Expression): SetOperation {
    val operationArgs = args(
        "this" to left,
        "expression" to right,
        "distinct" to stage.args["distinct"],
        "by_name" to stage.args["by_name"],
        "side" to stage.args["side"],
        "kind" to stage.args["kind"],
        "on" to (stage.args["on"] as? List<*>)?.toMutableList(),
    )
    return when (stage.thisArg as String) {
        "UNION" -> Union(operationArgs)
        "EXCEPT" -> Except(operationArgs)
        else -> Intersect(operationArgs)
    }
}

/** sqlglot: exp.and_(a, b) — Connector operands get wrapped in Paren. */
private fun and_(left: Expression, right: Expression): And =
    And(args("this" to wrapConnector(left), "expression" to wrapConnector(right)))

private fun wrapConnector(expression: Expression): Expression =
    if (expression is Connector) Paren(args("this" to expression)) else expression

/** sqlglot: Literal.to_py() for integer limit/offset literals. */
private fun literalLong(value: kotlin.Any?): Long {
    val literal = value as? Literal
        ?: error("Expected a number literal in pipe LIMIT/OFFSET, got: $value")
    return (literal.thisArg as String).toLong()
}
