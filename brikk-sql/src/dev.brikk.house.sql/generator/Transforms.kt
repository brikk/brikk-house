package dev.brikk.house.sql.generator

// Port of sqlglot's generator-time transforms (reference/sqlglot/sqlglot/transforms.py).
// Only the transforms needed so far are ported; the rest of the exp.Select preprocess
// pipelines remain NOT PORTED (see the dialect generator class docs).

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.aliasExpression
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.column
import dev.brikk.house.sql.ast.isStar
import dev.brikk.house.sql.ast.namedSelects
import dev.brikk.house.sql.ast.subquery
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
