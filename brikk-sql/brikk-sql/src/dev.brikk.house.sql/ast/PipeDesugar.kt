package dev.brikk.house.sql.ast

import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.optimizer.buildScope
import dev.brikk.house.sql.optimizer.walkInScope

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
 * brikk's first-class stage nodes post-parse instead of on the token stream, with
 * SELECT/DISTINCT input boundaries where merging would change rows or bindings:
 *
 *  - stages that mutate the current Select in place: WHERE (AND-merge), ORDER BY
 *    (replace, not append), LIMIT (keep-min) / OFFSET (sum), DISTINCT, TABLESAMPLE, JOIN;
 *  - stages that wrap into CTEs: SELECT, AGGREGATE, EXTEND, AS (user alias), set
 *    operations, PIVOT/UNPIVOT;
 *  - head normalization: Subquery / FROM-less heads become `SELECT * FROM (...)`.
 *
 * The extended GoogleSQL operators (NO sqlglot counterpart; hand-derived from
 * reference/googlesql/docs/pipe-syntax.md) desugar as:
 *
 *  - SET (~664)    -> CTE + `SELECT * REPLACE (expr AS col, ...)`;
 *  - DROP (~713)   -> CTE + `SELECT * EXCEPT (col, ...)`;
 *  - RENAME (~763) -> CTE + `SELECT * RENAME (old AS new, ...)`;
 *  - CALL (~1323)  -> the input query becomes the TVF's first table argument:
 *                     `SELECT * FROM f((<input>), args...)`;
 *  - WINDOW (~2361)-> EXTEND-like CTE + `SELECT *, w AS a, ...`.
 *
 * The star EXCEPT/REPLACE/RENAME modifiers are BigQuery-isms: for engines without
 * them, qualify()'s star expansion resolves them to explicit column lists when a
 * schema is available (shape/SqlFragment.toStandardSql(expandStars=true) and
 * shape/expandStarModifiers).
 *
 * The `__tmp{N}` counter is per top-level statement and follows sqlglot's parse-order
 * numbering: a stage's nested subpipelines are desugared before the stage itself builds
 * its CTEs, and the pipeline head before any stage.
 */
fun desugarPipes(expression: Expression, copy: kotlin.Boolean = true): Expression {
    val root = if (copy) expression.copy() else expression
    // sqlglot: Parser._pipe_cte_counter (per-statement)
    val counter = PipeCteCounter(root.findAll(Table::class, TableAlias::class)
        .flatMap { listOf(it.name.lowercase(), it.alias.lowercase()) }.toSet())
    return desugarNode(root, counter)
}

private class PipeCteCounter(val takenNames: kotlin.collections.Set<String>) {
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
        do { counter.n += 1 } while ("__tmp${counter.n}" in counter.takenNames)
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

private fun applyPipeStage(input: Expression, stage: Expression, counter: PipeCteCounter): Expression {
    var query = input
    val directProjection = query.expressionsArg.singleOrNull() is Star || (
        query.expressionsArg.all { it is Column && !it.isStar } &&
            stage.expressionsArg.all { it is Column && it in query.expressionsArg }
        )
    val positionalOrder = (query.args["order"] as? Order)?.expressionsArg.orEmpty().any {
        ((it as? Ordered)?.thisArg as? Expression)?.unnest()?.isInt == true
    }
    // brikk extension: projection/deduplication consumes the previous stage's rows,
    // not its FROM clause. Keep prior row selection and projected aliases intact.
    if ((stage is PipeSelect || stage is PipeDistinct) && (
            listOf("limit", "offset", "distinct", "group", "having", "qualify").any { query.args[it] != null } ||
                (stage is PipeSelect && (!directProjection || positionalOrder))
            )) {
        query = pipeInputCte(query, stage, counter)
    }
    return when (stage) {
        // sqlglot: Parser._parse_pipe_syntax_select
        is PipeSelect -> {
            if (stage.args["distinct"] != null) {
                query.set("distinct", stage.args["distinct"])
                // Explicit DISTINCT replaces any preserved SELECT ALL on the head.
                val modifiers = query.args["operation_modifiers"] as? List<*>
                if (modifiers != null) {
                    query.set("operation_modifiers", modifiers.filterNot { it is Var && it.name == "ALL" })
                }
            }
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
            val modifiers = query.args["operation_modifiers"] as? List<*>
            if (modifiers != null) query.set("operation_modifiers", modifiers.filterNot { it is Var && it.name == "ALL" })
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

        // googlesql: pipe SET (docs/pipe-syntax.md ~664) — NO sqlglot counterpart.
        // `|> SET col = expr, ...` replaces the column's value keeping its position,
        // exactly Star.replace semantics: CTE the input and select
        // `SELECT * REPLACE (expr AS col, ...)` from it.
        is PipeSet -> {
            val replaceList = stage.expressionsArg.map { assignment ->
                assignment as EQ
                Alias(
                    args(
                        "this" to assignment.expressionArg,
                        "alias" to toIdentifier((assignment.thisArg as Expression).name),
                    )
                ) as Expression
            }
            buildPipeCte(
                query,
                listOf(Star(args("replace" to replaceList.toMutableList()))),
                counter,
            )
        }

        // googlesql: pipe DROP (docs/pipe-syntax.md ~713) — NO sqlglot counterpart.
        // `|> DROP col, ...` ≡ `SELECT * EXCEPT (col, ...)` from the CTE'd input.
        is PipeDrop -> buildPipeCte(
            query,
            listOf(Star(args("except_" to stage.expressionsArg.toMutableList()))),
            counter,
        )

        // googlesql: pipe RENAME (docs/pipe-syntax.md ~763) — NO sqlglot counterpart.
        // `|> RENAME old AS new, ...` ≡ `SELECT * RENAME (old AS new, ...)` from the
        // CTE'd input (the stage's Alias elements are exactly Star.rename's shape).
        is PipeRename -> buildPipeCte(
            query,
            listOf(Star(args("rename" to stage.expressionsArg.toMutableList()))),
            counter,
        )

        // googlesql: pipe CALL (docs/pipe-syntax.md ~1323) — NO sqlglot counterpart.
        // The pipe input becomes the TVF's FIRST TABLE ARGUMENT (spec: "The first table
        // argument comes from the input table and must be omitted in the arguments"):
        // `x |> CALL f(a, b) AS t` ≡ `SELECT * FROM f((<x>), a, b) AS t`.
        //
        // The call lands in table position as an unresolved function (Anonymous) — the
        // same shape SqlFragment's slot mechanism keys on, so a CALLed function that is
        // unknown to the dialect becomes a tableSlots candidate. That is desirable:
        // callers can bind the TVF's output shape like any other table-valued input.
        is PipeCall -> {
            // Keep the CTE chain flat, like buildPipeCte: hoist the input's WITH onto
            // the new enclosing select.
            val ctes = (query.args["with_"] as? With)?.also { it.pop() }

            val call = stage.thisArg as Expression
            val callArgs = mutableListOf<Expression>(Subquery(args("this" to query)))
            for (arg in call.expressionsArg) callArgs.add(arg as Expression)
            call.set("expressions", callArgs)

            val table = Table(args("this" to call, "alias" to stage.args["alias"]))
            val newSelect = Select(
                args(
                    "expressions" to mutableListOf<Expression>(Star()),
                    "from_" to From(args("this" to table)),
                )
            )
            if (ctes != null) newSelect.set("with_", ctes)
            newSelect
        }

        // googlesql: pipe WINDOW (docs/pipe-syntax.md ~2361, deprecated alias of EXTEND
        // for window functions) — NO sqlglot counterpart. EXTEND-like: append the window
        // projections after `*`, then CTE.
        is PipeWindow -> {
            val exprs = mutableListOf<Expression>(Star())
            for (e in stage.expressionsArg) exprs.add(e as Expression)
            query.set("expressions", exprs)
            buildPipeCte(query, listOf(Star()), counter)
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
}

private fun pipeInputCte(query: Expression, stage: Expression, counter: PipeCteCounter): Select {
    val projections = query.expressionsArg.filterIsInstance<Expression>()
    val hasStar = projections.any { (it is Star || it is Column || it is Dot) && it.isStar }
    val hasJoins = walkInScope(query).any { it is Join }
    if (hasJoins && hasStar) {
        throw UnsupportedError("Cannot preserve a pipe SELECT/DISTINCT input boundary over joined stars without explicit, uniquely named input columns")
    }
    if (!hasStar && (projections.any { it.outputName.isEmpty() } ||
            projections.map { it.outputName.lowercase() }.toSet().size != projections.size)) {
        throw UnsupportedError("Pipe SELECT/DISTINCT input boundaries require uniquely named input columns; add explicit aliases")
    }

    val source = (query.args["from_"] as? From)?.thisArg as? Expression
    val sourceAlias = (source?.args?.get("alias") as? TableAlias)?.thisArg as? Identifier
        ?: if (source is Table || source is TableAlias) source.thisArg as? Identifier else null
    // Scope.columns includes child-query correlations but excludes their local bindings.
    val stageScope = buildScope(Select(args("expressions" to listOf(stage.copy()))))!!
    val stageColumns = stageScope.columns + stageScope.stars.filterIsInstance<Column>()
    if (stageColumns.any { it.db.isNotEmpty() || it.catalog.isNotEmpty() }) {
        throw UnsupportedError("Pipe SELECT/DISTINCT input boundaries require table aliases rather than database-qualified column references")
    }
    // Scope's table-name lookup cannot distinguish db1.t from db2.t. Check full
    // names in child queries before considering a multipart reference locally bound.
    for (col in stage.findAll<Column>().filter { it.db.isNotEmpty() || it.catalog.isNotEmpty() }) {
        var ancestor = col.parent
        var local = false
        while (ancestor != null && ancestor !== stage) {
            if (ancestor is Select && walkInScope(ancestor).filterIsInstance<Table>().any {
                    it.alias.isEmpty() && it.name == col.table && it.db == col.db && it.catalog == col.catalog
                }) {
                local = true
                break
            }
            ancestor = ancestor.parent
        }
        if (!local) throw UnsupportedError("Cannot preserve a correlated database-qualified reference across a pipe input boundary; use distinct table aliases")
    }
    if (hasJoins && stageColumns.any { it.table.isNotEmpty() }) {
        throw UnsupportedError("Pipe SELECT/DISTINCT input boundaries over joins require references to the input's explicit output names")
    }
    if (stageColumns.any { it.isStar && it.table.isNotEmpty() } &&
        !(projections.size == 1 && projections.single().isStar)) {
        throw UnsupportedError("Cannot preserve a qualified star across a pipe input boundary with additional or renamed projections; use explicit columns")
    }

    val rankingOrder = if ((stage.args["distinct"] as? Distinct)?.args?.get("on") is Tuple) {
        (query.args["order"] as? Order)?.copy()?.also { order ->
            for (key in order.expressionsArg.filterIsInstance<Expression>()) {
                val value = if (key is Ordered) key.thisArg as Expression else key
                if (value.unnest().isInt) {
                    throw UnsupportedError("Pipe DISTINCT ON input boundaries require named ORDER BY expressions, not positional references")
                }
                val projected = if (value is Column && value.table.isEmpty()) {
                    projections.firstOrNull { !it.isStar && it.outputName == value.name }
                        ?: projections.firstOrNull { it.unalias() == value }
                } else projections.firstOrNull { it.unalias() == value }
                val identifier = if (!hasStar && projected != null) {
                    (projected.args["alias"] as? Identifier ?: projected.thisArg as? Identifier)?.copy()
                } else if (projections.size == 1 && hasStar && value is Column &&
                    value.db.isEmpty() && value.catalog.isEmpty() &&
                    (value.table.isEmpty() || value.table == sourceAlias?.name)) {
                    (value.thisArg as? Identifier)?.copy()
                } else null
                if (identifier == null) {
                    throw UnsupportedError("Pipe DISTINCT ON cannot preserve an ORDER BY expression across its input boundary; expose the computed sort key with a unique alias")
                }
                identifier.updatePositions(if (value is Column) value.thisArg as Expression else value)
                val bound = Column(args("this" to identifier))
                bound.updatePositions(value)
                value.replace(bound)
            }
        }
    } else null
    val outer = buildPipeCte(query, listOf(Star()), counter)
    // Retain the single input namespace, including references inside correlated
    // stage expressions, without stripping qualifiers or changing source positions.
    if (!hasJoins && sourceAlias != null) {
        ((outer.args["from_"] as From).thisArg as Table).set("alias", TableAlias(args("this" to sourceAlias.copy())))
    }
    // Ranking still needs the input order; the original stays with its restriction.
    if (rankingOrder != null) outer.set("order", rankingOrder)
    return outer
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
