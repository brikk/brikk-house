package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.aliasExpression
import dev.brikk.house.sql.ast.selectStarFrom
import dev.brikk.house.sql.ast.subquery
import dev.brikk.house.sql.dialects.Dialect

/**
 * Port of sqlglot/optimizer/isolate_table_selects.py — wraps schema-backed tables that
 * share a scope with other sources into `(SELECT * FROM table AS table) AS alias`.
 */
// sqlglot: isolate_table_selects.isolate_table_selects
fun <E : Expression> isolateTableSelects(
    expression: E,
    schema: Any? = null,
    dialect: Dialect? = null,
): E {
    val resolvedSchema = ensureSchema(schema, dialect = dialect)

    for (scope in traverseScope(expression)) {
        if (scope.selectedSources.size == 1) {
            continue
        }

        for ((_, source) in scope.selectedSources.values) {
            if (source !is Table ||
                resolvedSchema.columnNames(source).isEmpty() ||
                source.parent is Subquery ||
                source.parent?.parent is Table
            ) {
                continue
            }

            if (source.alias.isEmpty()) {
                throw OptimizeError(
                    "Tables require an alias. Run qualify_tables optimization."
                )
            }

            source.replace(
                subquery(
                    selectStarFrom(
                        aliasExpression(
                            source,
                            source.aliasOrName,
                            table = true,
                            copy = true,
                        )
                    ),
                    alias = source.alias,
                )
            )
        }
    }

    return expression
}
