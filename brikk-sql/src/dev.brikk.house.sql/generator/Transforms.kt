package dev.brikk.house.sql.generator

// Port of sqlglot's generator-time transforms (reference/sqlglot/sqlglot/transforms.py).
// Only the transforms needed so far are ported; the rest of the exp.Select preprocess
// pipelines remain NOT PORTED (see the dialect generator class docs).

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.And
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Exists
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Inline
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Lambda
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.ILike
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Posexplode
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.RowNumber
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.Any as AnyNode
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.aliasExpression
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.column
import dev.brikk.house.sql.ast.isStar
import dev.brikk.house.sql.ast.namedSelects
import dev.brikk.house.sql.ast.outputName
import dev.brikk.house.sql.ast.subquery
import dev.brikk.house.sql.ast.toIdentifier
import dev.brikk.house.sql.optimizer.findAllInScope
import dev.brikk.house.sql.optimizer.findNewName

/**
 * sqlglot: transforms.eliminate_qualify — converts SELECT statements that contain the
 * QUALIFY clause into subqueries, filtered equivalently.
 *
 * Window functions referenced by QUALIFY are added as aliased projections of the
 * subquery so the outer WHERE can refer to them; columns referenced by QUALIFY but not
 * selected are added too; newly aliased projections referenced from QUALIFY are inlined
 * to avoid invalid column references.
 */
fun eliminateQualify(expression: Expression): Expression {
    if (expression !is Select || expression.args["qualify"] == null) return expression

    val taken = expression.namedSelects.toMutableSet()
    for (select in expression.selects) {
        if (select !is Expression) continue
        if (select.aliasOrName.isEmpty()) {
            val alias = findNewName(taken, "_c")
            select.replace(aliasExpression(select, alias))
            taken.add(alias)
        }
    }

    fun selectAliasOrName(select: Expression): Expression {
        val aliasOrName = select.aliasOrName
        val identifier = select.args["alias"] ?: select.thisArg
        return if (identifier is Identifier) {
            column(aliasOrName, quoted = identifier.args["quoted"] as? Boolean)
        } else {
            // sqlglot returns the raw name here and exp.select() re-parses it; the only
            // non-column shape that reaches this branch is the star projection.
            if (aliasOrName == "*") Star() else column(aliasOrName)
        }
    }

    // brikk extension (NOT sqlglot parity — see docs/brikk-extensions.md #6): when the
    // original projection contains a star, sqlglot emits the star PLUS an explicit column
    // per remaining projection (`SELECT *, rn FROM (...)`). The outer star already
    // re-exports every inner projection, so the explicit columns duplicate output columns
    // and change the result shape vs the original query (verified against DuckDB/Doris/
    // Trino by customer agents). We collapse the outer projection to the bare star.
    // The star-only Case B leak (QUALIFY-only window alias exported through `SELECT *`)
    // is upstream behavior we deliberately keep for parity — see registry entry.
    val outerProjection: List<Expression> =
        if (expression.selects.any { it is Star }) {
            listOf(Star())
        } else {
            expression.selects.map { selectAliasOrName(it as Expression) }
        }
    val outerSelects = Select(args("expressions" to outerProjection))
    var qualifyFilters = (expression.args["qualify"] as Expression).pop().thisArg as Expression
    val expressionByAlias: Map<String, Expression> = expression.selects
        .filterIsInstance<Alias>()
        .associate { it.alias to it.thisArg as Expression }

    val selectCandidates =
        if (expression.isStar) arrayOf(Window::class) else arrayOf(Window::class, Column::class)
    for (candidate in qualifyFilters.findAll(*selectCandidates).toList()) {
        if (candidate is Window) {
            if (expressionByAlias.isNotEmpty()) {
                for (col in candidate.findAll(Column::class).toList()) {
                    val expr = expressionByAlias[col.name]
                    if (expr != null) col.replace(expr)
                }
            }

            val alias = findNewName(expression.namedSelects, "_w")
            expression.append("expressions", aliasExpression(candidate, alias))
            val col = column(alias)

            if (candidate.parent is Qualify) {
                qualifyFilters = col
            } else {
                candidate.replace(col)
            }
        } else if (candidate.name !in expression.namedSelects) {
            expression.append("expressions", candidate.copy())
        }
    }

    outerSelects.set("from_", From(args("this" to subquery(expression, alias = "_t"))))
    outerSelects.set("where", Where(args("this" to qualifyFilters)))
    return outerSelects
}

// ---------------------------------------------------------------------------
// Small builder helpers used by the ported transforms (slices of the fluent
// exp.Select builder API / operator helpers that sqlglot relies on).
// ---------------------------------------------------------------------------

// sqlglot: exp.select(...).from_(source) — builds `SELECT <projections> FROM <source>`.
private fun buildSelectFrom(projections: List<Expression>, source: Expression): Select {
    val select = Select(args("expressions" to projections.toMutableList<kotlin.Any?>()))
    select.set("from_", From(args("this" to source)))
    return select
}

// sqlglot: Query.where(condition, copy=False) — ANDs [condition] into the WHERE clause.
private fun addWhere(select: Select, condition: Expression) {
    val existing = select.args["where"] as? Where
    val newCondition = if (existing?.thisArg is Expression) {
        And(args("this" to existing.thisArg, "expression" to condition))
    } else {
        condition
    }
    select.set("where", Where(args("this" to newCondition)))
}

// sqlglot: Query.join(join_expr, join_type=..., copy=False) — appends a Join.
private fun addJoin(select: Select, joinThis: Expression, kind: String? = null, side: String? = null) {
    select.append(
        "joins",
        Join(
            args(
                "this" to joinThis,
                "kind" to kind,
                "side" to side,
            )
        ),
    )
}

/**
 * sqlglot: transforms.eliminate_semi_and_anti_joins — convert SEMI and ANTI joins into
 * equivalent forms that use EXISTS instead.
 */
fun eliminateSemiAndAntiJoins(expression: Expression): Expression {
    if (expression is Select) {
        val joins = (expression.args["joins"] as? List<*>)?.filterIsInstance<Join>() ?: emptyList()
        for (join in joins.toList()) {
            val on = join.args["on"] as? Expression
            if (on != null && (join.kind == "SEMI" || join.kind == "ANTI")) {
                val subq = buildSelectFrom(
                    listOf(Literal.number("1")),
                    join.thisArg as Expression,
                )
                subq.set("where", Where(args("this" to on)))
                var exists: Expression = Exists(args("this" to subq))
                if (join.kind == "ANTI") {
                    exists = Not(args("this" to exists))
                }

                join.pop()
                addWhere(expression, exists)
            }
        }
    }

    return expression
}

/**
 * sqlglot: transforms.unqualify_columns — pops off the table/db/catalog parts of every
 * column, leaving only the leaf name.
 */
fun unqualifyColumns(expression: Expression): Expression {
    for (column in expression.findAll(Column::class).toList()) {
        // We only wanna pop off the table, db, catalog args
        for (part in (column as Column).parts.dropLast(1)) {
            part.pop()
        }
    }

    return expression
}

/**
 * sqlglot: transforms.unqualify_unnest — remove references to unnest table aliases, added
 * by the optimizer's qualify_columns step.
 */
fun unqualifyUnnest(expression: Expression): Expression {
    if (expression is Select) {
        val unnestAliases = findAllInScope(expression, Unnest::class)
            .filter { it.parent is From || it.parent is Join }
            .map { it.alias }
            .filter { it.isNotEmpty() }
            .toSet()
        if (unnestAliases.isNotEmpty()) {
            for (column in expression.findAll(Column::class).toList()) {
                val leftmost = (column as Column).parts.first()
                if (leftmost.argKey != "this" && leftmost.name in unnestAliases) {
                    leftmost.pop()
                }
            }
        }
    }

    return expression
}

/**
 * sqlglot: transforms.any_to_exists — transform the ANY operator into Spark/Hive EXISTS.
 *
 *   Postgres: SELECT * FROM tbl WHERE 5 > ANY(tbl.col)
 *   Spark:    SELECT * FROM tbl WHERE EXISTS(tbl.col, x -> x < 5)
 *
 * Only array expressions are supported (queries are left untouched).
 */
fun anyToExists(expression: Expression): Expression {
    if (expression is Select) {
        for (anyExpr in expression.findAll(AnyNode::class).toList()) {
            val this_ = anyExpr.thisArg as Expression
            if (this_ is Query || anyExpr.parent is Like || anyExpr.parent is ILike) {
                continue
            }

            val binop = anyExpr.parent
            if (binop is dev.brikk.house.sql.ast.Binary) {
                val lambdaArg = toIdentifier("x")!!
                anyExpr.replace(lambdaArg)
                val lambdaExpr = Lambda(
                    args("this" to binop.copy(), "expressions" to mutableListOf<kotlin.Any?>(lambdaArg))
                )
                binop.replace(
                    Exists(args("this" to this_.unnest(), "expression" to lambdaExpr))
                )
            }
        }
    }

    return expression
}

/**
 * sqlglot: transforms.move_ctes_to_top_level — some dialects (Hive, T-SQL, Spark < 3)
 * only allow CTEs at the top level, so nested WITH clauses are hoisted up.
 */
fun moveCtesToTopLevel(expression: Expression): Expression {
    var topLevelWith = expression.args["with_"] as? With
    for (innerWithExpr in expression.findAll(With::class).toList()) {
        val innerWith = innerWithExpr as With
        if (innerWith.parent === expression) continue

        if (topLevelWith == null) {
            topLevelWith = innerWith.pop() as With
            expression.set("with_", topLevelWith)
        } else {
            if (innerWith.recursive) {
                topLevelWith.set("recursive", true)
            }

            val parentCte = innerWith.findAncestor(CTE::class)
            innerWith.pop()

            val topExprs = (topLevelWith.args["expressions"] as? List<*>)
                ?.filterIsInstance<Expression>()?.toMutableList() ?: mutableListOf()
            val innerExprs = (innerWith.args["expressions"] as? List<*>)
                ?.filterIsInstance<Expression>() ?: emptyList()

            if (parentCte != null) {
                val i = topExprs.indexOf(parentCte)
                if (i >= 0) {
                    topExprs.addAll(i, innerExprs)
                } else {
                    topExprs.addAll(innerExprs)
                }
                topLevelWith.set("expressions", topExprs)
            } else {
                topLevelWith.set("expressions", (topExprs + innerExprs).toMutableList())
            }
        }
    }

    return expression
}

/**
 * sqlglot: transforms.eliminate_distinct_on — convert SELECT DISTINCT ON statements to a
 * subquery with a ROW_NUMBER window function (for dialects lacking DISTINCT ON).
 */
fun eliminateDistinctOn(expression: Expression): Expression {
    if (expression !is Select) return expression
    val distinct = expression.args["distinct"] as? Distinct ?: return expression
    val on = distinct.args["on"]
    if (on !is Tuple) return expression

    val rowNumberWindowAlias = findNewName(expression.namedSelects, "_row_number")

    val distinctCols = ((distinct.pop() as Distinct).args["on"] as Tuple)
        .expressionsArg.filterIsInstance<Expression>()
    val window = Window(
        args("this" to RowNumber(), "partition_by" to distinctCols.toMutableList())
    )

    val order = expression.args["order"] as? Order
    if (order != null) {
        window.set("order", order.pop())
    } else {
        window.set(
            "order",
            Order(args("expressions" to distinctCols.map { it.copy() }.toMutableList()))
        )
    }

    expression.append("expressions", aliasExpression(window, rowNumberWindowAlias))

    // Add aliases to projections so they can be referenced safely in the outer query.
    var newSelects = mutableListOf<Expression>()
    val takenNames = mutableSetOf(rowNumberWindowAlias)
    val selectsBeforeWindow = expression.selects.filterIsInstance<Expression>().dropLast(1)
    for (select in selectsBeforeWindow) {
        if (select.isStar) {
            newSelects = mutableListOf(Star())
            break
        }

        var sel = select
        if (sel !is Alias) {
            val alias = findNewName(takenNames, sel.outputName.ifEmpty { "_col" })
            val quoted = if (sel is Column) sel.thisArg.let { (it as? Identifier)?.args?.get("quoted") as? Boolean } else null
            sel = sel.replace(aliasExpression(sel, alias, quoted = quoted)) as Expression
        }

        takenNames.add(sel.outputName)
        newSelects.add(sel.args["alias"] as Expression)
    }

    val outer = Select(args("expressions" to newSelects.toMutableList<kotlin.Any?>()))
    outer.set("from_", From(args("this" to subquery(expression, alias = "_t"))))
    outer.set(
        "where",
        Where(args("this" to EQ(args("this" to column(rowNumberWindowAlias), "expression" to Literal.number("1"))))),
    )
    return outer
}

// Python truthiness for raw arg values (matches sqlglot's `if x:` gating).
private fun truthy(value: kotlin.Any?): kotlin.Boolean = when (value) {
    null, false, "", 0, 0L, 0.0 -> false
    is List<*> -> value.isNotEmpty()
    else -> true
}

// sqlglot: transforms.unnest_to_explode._udtf_type
private fun udtfType(u: Unnest, hasMultiExpr: kotlin.Boolean): (Expression) -> Expression {
    if (truthy(u.args["offset"])) {
        return { this_ -> Posexplode(args("this" to this_)) }
    }
    return if (hasMultiExpr) {
        { this_ -> Inline(args("this" to this_)) }
    } else {
        { this_ -> Explode(args("this" to this_)) }
    }
}

/**
 * sqlglot: transforms.unnest_to_explode — convert cross join UNNEST into
 * LATERAL VIEW EXPLODE (Spark/Hive family).
 */
fun unnestToExplode(expression: Expression, unnestUsingArraysZip: kotlin.Boolean = true): Expression {
    // sqlglot: nested _unnest_zip_exprs
    fun unnestZipExprs(
        u: Unnest,
        unnestExprs: kotlin.collections.List<Expression>,
        hasMultiExpr: kotlin.Boolean,
    ): kotlin.collections.List<Expression> {
        if (hasMultiExpr) {
            if (!unnestUsingArraysZip) {
                throw UnsupportedError("Cannot transpile UNNEST with multiple input arrays")
            }
            val zipExprs: kotlin.collections.List<Expression> =
                listOf(Anonymous(args("this" to "ARRAYS_ZIP", "expressions" to unnestExprs.toMutableList())))
            u.set("expressions", zipExprs.toMutableList())
            return zipExprs
        }
        return unnestExprs
    }

    if (expression is Select) {
        val from = expression.args["from_"] as? From

        if (from != null && from.thisArg is Unnest) {
            val unnest = from.thisArg as Unnest
            val alias = unnest.args["alias"] as? TableAlias
            val exprs = unnest.expressionsArg.filterIsInstance<Expression>()
            val hasMultiExpr = exprs.size > 1
            val this_ = unnestZipExprs(unnest, exprs, hasMultiExpr).first()

            val columns = (alias?.columns?.filterIsInstance<Expression>() ?: emptyList()).toMutableList()
            val offset = unnest.args["offset"]
            if (truthy(offset)) {
                columns.add(0, if (offset is Identifier) offset else toIdentifier("pos")!!)
            }

            unnest.replace(
                Table(
                    args(
                        "this" to udtfType(unnest, hasMultiExpr)(this_),
                        "alias" to if (alias != null) {
                            TableAlias(args("this" to alias.thisArg, "columns" to columns))
                        } else {
                            null
                        },
                    )
                )
            )
        }

        val joins = (expression.args["joins"] as? List<*>)?.filterIsInstance<Join>() ?: emptyList()
        for (join in joins.toList()) {
            val joinExpr = join.thisArg as? Expression
            val isLateral = joinExpr is Lateral
            val unnestCandidate = if (isLateral) (joinExpr as Lateral).thisArg else joinExpr

            if (unnestCandidate is Unnest) {
                val alias: TableAlias? = if (isLateral) {
                    (joinExpr as Lateral).args["alias"] as? TableAlias
                } else {
                    unnestCandidate.args["alias"] as? TableAlias
                }

                if (alias == null) {
                    throw UnsupportedError(
                        "CROSS JOIN UNNEST to LATERAL VIEW EXPLODE transformation requires an alias"
                    )
                }

                var exprs = unnestCandidate.expressionsArg.filterIsInstance<Expression>()
                val hasMultiExpr = exprs.size > 1
                exprs = unnestZipExprs(unnestCandidate, exprs, hasMultiExpr)

                val currentJoins = (expression.args["joins"] as? List<*>)
                    ?.filterIsInstance<Join>()?.toMutableList() ?: mutableListOf()
                currentJoins.remove(join)
                expression.set("joins", currentJoins.ifEmpty { null })

                val aliasCols = (alias.columns.filterIsInstance<Expression>()).toMutableList()

                if (!hasMultiExpr && aliasCols.size !in setOf(1, 2)) {
                    throw UnsupportedError(
                        "CROSS JOIN UNNEST to LATERAL VIEW EXPLODE transformation requires explicit column aliases"
                    )
                }

                val offset = unnestCandidate.args["offset"]
                if (truthy(offset)) {
                    aliasCols.add(0, if (offset is Identifier) offset else toIdentifier("pos")!!)
                }

                for ((idx, e) in exprs.withIndex()) {
                    if (idx >= aliasCols.size) break
                    expression.append(
                        "laterals",
                        Lateral(
                            args(
                                "this" to udtfType(unnestCandidate, hasMultiExpr)(e),
                                "view" to true,
                                "alias" to TableAlias(args("this" to alias.thisArg, "columns" to aliasCols)),
                            )
                        ),
                    )
                }
            }
        }
    }

    return expression
}

/**
 * sqlglot: transforms.inherit_struct_field_names — inherit field names from the first
 * struct in an array (BigQuery implicit inheritance), making them explicit on all
 * structs so they can be transpiled to other dialects.
 */
fun inheritStructFieldNames(expression: Expression): Expression {
    if (expression !is ArrayNode) return expression
    if (expression.args["struct_name_inheritance"] != true) return expression

    val exprs = expression.expressionsArg.filterIsInstance<Expression>()
    val firstItem = exprs.firstOrNull()
    if (firstItem !is Struct) return expression
    val firstFields = firstItem.expressionsArg.filterIsInstance<Expression>()
    if (firstFields.isEmpty() || !firstFields.all { it is PropertyEQ }) return expression

    val fieldNames = firstFields.map { (it as PropertyEQ).thisArg as Expression }

    for (struct in exprs.drop(1)) {
        if (struct !is Struct) continue
        val structExprs = struct.expressionsArg.filterIsInstance<Expression>()
        if (structExprs.size != fieldNames.size) continue

        val newExpressions = mutableListOf<Expression>()
        for ((i, expr) in structExprs.withIndex()) {
            if (expr !is PropertyEQ) {
                val propertyEq = PropertyEQ(
                    args("this" to fieldNames[i].copy(), "expression" to expr)
                )
                propertyEq.typeSlot = expr.typeSlot
                newExpressions.add(propertyEq)
            } else {
                newExpressions.add(expr)
            }
        }

        struct.set("expressions", newExpressions)
    }

    return expression
}
