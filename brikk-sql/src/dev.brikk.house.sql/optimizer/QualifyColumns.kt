package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.AggFunc
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Aliases
import dev.brikk.house.sql.ast.Binary
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Condition
import dev.brikk.house.sql.ast.Connector
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Group
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Is
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Mul
import dev.brikk.house.sql.ast.Neg
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Pivot
import dev.brikk.house.sql.ast.Predicate
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.Pseudocolumn
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.QueryTransform
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Selectable
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.Sub
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.SubqueryPredicate
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TableColumn
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.aliasColumnNames
import dev.brikk.house.sql.ast.aliasExpression
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.coalesce
import dev.brikk.house.sql.ast.column
import dev.brikk.house.sql.ast.combineAnd
import dev.brikk.house.sql.ast.isInt
import dev.brikk.house.sql.ast.isStar
import dev.brikk.house.sql.ast.outputColumns
import dev.brikk.house.sql.ast.outputName
import dev.brikk.house.sql.ast.paren
import dev.brikk.house.sql.ast.parts
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.ast.toIdentifier
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

/**
 * Port of sqlglot/optimizer/qualify_columns.py — rewrite the AST to have fully
 * qualified columns: USING expansion, alias-reference expansion, table.column
 * rewriting, star expansion (incl. EXCEPT/REPLACE/RENAME/ILIKE), output aliasing,
 * GROUP BY/ORDER BY/DISTINCT ON positional and alias expansion, validation and
 * identifier quoting.
 */
// sqlglot: qualify_columns.qualify_columns
fun <E : Expression> qualifyColumns(
    expression: E,
    schema: Any?,
    expandAliasRefs: Boolean = true,
    expandStars: Boolean = true,
    inferSchema: Boolean? = null,
    allowPartialQualification: Boolean = false,
    dialect: Dialect? = null,
): E {
    val resolvedSchema = ensureSchema(schema, dialect = dialect)
    val annotator = TypeAnnotator(resolvedSchema)
    val resolvedInferSchema = inferSchema ?: resolvedSchema.empty
    val resolvedDialect = resolvedSchema.dialect ?: Dialects.BASE
    val pseudocolumns = resolvedDialect.pseudocolumns

    for (scope in traverseScope(expression)) {
        if (resolvedDialect.preferCteAliasColumn) {
            pushdownCteAliasColumns(scope)
        }

        val scopeExpression = scope.expression
        val isSelect = scopeExpression is Select

        separatePseudocolumns(scope, pseudocolumns)

        val resolver = Resolver(scope, resolvedSchema, inferSchema = resolvedInferSchema)
        popTableColumnAliases(scope.ctes)
        popTableColumnAliases(scope.derivedTables)
        val usingColumnTables = expandUsing(scope, resolver)

        if ((resolvedSchema.empty || resolvedDialect.forceEarlyAliasRefExpansion) &&
            expandAliasRefs
        ) {
            expandAliasRefsInScope(
                scope,
                resolver,
                resolvedDialect,
                expandOnlyGroupby = resolvedDialect.expandOnlyGroupAliasRef,
            )
        }

        convertColumnsToDots(scope, resolver)
        qualifyColumnsInScope(
            scope,
            resolver,
            allowPartialQualification = allowPartialQualification,
        )

        if (!resolvedSchema.empty && expandAliasRefs) {
            expandAliasRefsInScope(scope, resolver, resolvedDialect)
        }

        if (isSelect) {
            if (expandStars) {
                expandStarsInScope(
                    scope,
                    resolver,
                    usingColumnTables,
                    pseudocolumns,
                    annotator,
                )
            }
            qualifyOutputs(scope, dialect = resolvedDialect)
        }

        expandGroupBy(scope, resolvedDialect)

        // DISTINCT ON and ORDER BY follow the same rules (tested in DuckDB, Postgres,
        // ClickHouse): https://www.postgresql.org/docs/current/sql-select.html#SQL-DISTINCT
        expandOrderByAndDistinctOn(scope, resolver)

        if (resolvedDialect.annotateAllScopes) {
            annotator.annotateScope(scope)
        }
    }

    return expression
}

// sqlglot: qualify_columns.validate_qualify_columns — raise if any columns aren't qualified
fun <E : Expression> validateQualifyColumns(expression: E, sql: String? = null): E {
    val allUnqualifiedColumns = mutableListOf<Column>()
    for (scope in traverseScope(expression)) {
        if (scope.expression is Select) {
            val unqualifiedColumns = scope.unqualifiedColumns

            if (scope.externalColumns.isNotEmpty() && !scope.isCorrelatedSubquery &&
                scope.pivots.isEmpty()
            ) {
                val column = scope.externalColumns[0]
                val forTable =
                    if (column.table.isNotEmpty()) " for table: '${column.table}'" else ""

                // Python appends " Line: {line}, Col: {col}" and a highlighted SQL
                // excerpt when the AST carries position metadata; ours does not.
                throw OptimizeError(
                    "Column '${column.name}' could not be resolved$forTable."
                )
            }

            allUnqualifiedColumns.addAll(unqualifiedColumns)
        }
    }

    if (allUnqualifiedColumns.isNotEmpty()) {
        val firstColumn = allUnqualifiedColumns[0]
        throw OptimizeError("Ambiguous column '${firstColumn.name}'")
    }

    return expression
}

// sqlglot: qualify_columns._separate_pseudocolumns
private fun separatePseudocolumns(scope: Scope, pseudocolumns: Set<String>) {
    if (pseudocolumns.isEmpty()) {
        return
    }

    var hasPseudocolumns = false
    val scopeExpression = scope.expression

    for (column in scope.columns) {
        val name = column.name.uppercase()
        if (name !in pseudocolumns) {
            continue
        }

        if (name != "LEVEL" ||
            (scopeExpression is Select && scopeExpression.args["connect"] != null)
        ) {
            column.replace(Pseudocolumn(LinkedHashMap(column.args)))
            hasPseudocolumns = true
        }
    }

    if (hasPseudocolumns) {
        scope.clearCache()
    }
}

/**
 * sqlglot: qualify_columns._pop_table_column_aliases — remove table column aliases:
 * `col1` and `col2` are dropped in SELECT ... FROM (SELECT ...) AS foo(col1, col2)
 */
private fun popTableColumnAliases(derivedTables: Iterable<Expression>) {
    for (derivedTable in derivedTables) {
        val parent = derivedTable.parent
        if (parent is With && parent.recursive) {
            continue
        }
        val tableAlias = derivedTable.args["alias"] as? Expression
        tableAlias?.set("columns", null)
    }
}

// sqlglot: qualify_columns._expand_using
private fun expandUsing(scope: Scope, resolver: Resolver): Map<String, Map<String, Any?>> {
    val columns = LinkedHashMap<String, String>()

    fun updateSourceColumns(sourceName: String) {
        for (columnName in resolver.getSourceColumns(sourceName)) {
            if (columnName !in columns) {
                columns[columnName] = sourceName
            }
        }
    }

    val joins = scope.findAll(Join::class).filterIsInstance<Join>().toList()
    if (joins.isEmpty()) {
        return emptyMap()
    }

    val names = joins.map { it.aliasOrName }.toSet()
    val ordered = scope.selectedSources.keys.filter { it !in names }.toMutableList()

    if (names.isNotEmpty() && ordered.isEmpty()) {
        throw OptimizeError("Joins $names missing source table ${scope.expression}")
    }

    // Mapping of automatically joined column names to an ordered set of source names.
    val columnTables = LinkedHashMap<String, LinkedHashMap<String, Any?>>()

    if (joins.none { !(it.args["using"] as? List<*>).isNullOrEmpty() }) {
        return columnTables
    }

    for (sourceName in ordered.toList()) {
        updateSourceColumns(sourceName)
    }

    for ((i, join) in joins.withIndex()) {
        val sourceTable = ordered.last()
        if (sourceTable.isNotEmpty()) {
            updateSourceColumns(sourceTable)
        }

        val joinTable = join.aliasOrName
        ordered.add(joinTable)

        val using = (join.args["using"] as? List<*>)?.filterIsInstance<Expression>()
        if (using.isNullOrEmpty()) {
            continue
        }

        val joinColumns = resolver.getSourceColumns(joinTable)
        val conditions = mutableListOf<Expression>()
        val usingIdentifierCount = using.size
        val isSemiOrAntiJoin = join.isSemiOrAntiJoin

        for (identifierExpr in using) {
            val identifier = identifierExpr.name
            var table = columns[identifier]

            if (table == null || identifier !in joinColumns) {
                if (columns.isNotEmpty() && "*" !in columns && joinColumns.isNotEmpty()) {
                    throw OptimizeError("Cannot automatically join: $identifier")
                }
            }

            table = table ?: sourceTable

            val lhs: Expression = if (i == 0 || usingIdentifierCount == 1) {
                column(identifier, table = table)
            } else {
                val coalesceColumns = ordered.dropLast(1)
                    .filter { identifier in resolver.getSourceColumns(it) }
                    .map { column(identifier, table = it) }
                if (coalesceColumns.size > 1) {
                    coalesce(coalesceColumns)
                } else {
                    column(identifier, table = table)
                }
            }

            conditions.add(
                dev.brikk.house.sql.ast.EQ(
                    args("this" to lhs, "expression" to column(identifier, table = joinTable))
                )
            )

            // We only care about the key ordering
            val tables = columnTables.getOrPut(identifier) { LinkedHashMap() }

            // Do not update the dict if this was a SEMI/ANTI join in
            // order to avoid generating COALESCE columns for this join pair
            if (!isSemiOrAntiJoin) {
                if (table !in tables) {
                    tables[table] = null
                }
                if (joinTable !in tables) {
                    tables[joinTable] = null
                }
            }
        }

        join.set("using", null)
        join.set("on", combineAnd(conditions))
    }

    if (columnTables.isNotEmpty()) {
        for (column in scope.columns) {
            if (column.table.isEmpty() && column.name in columnTables) {
                val tables = columnTables.getValue(column.name)
                val coalesceArgs = tables.keys.map { column(column.name, table = it) }
                var replacement: Expression = coalesce(coalesceArgs)

                if (column.parent is Select) {
                    // Ensure the USING column keeps its name if it's projected
                    replacement =
                        aliasExpression(replacement, alias = column.name, copy = false)
                } else if (column.parent is Struct) {
                    // Ensure the USING column keeps its name if it's an anonymous
                    // STRUCT field
                    replacement = PropertyEQ(
                        args(
                            "this" to toIdentifier(column.name),
                            "expression" to replacement,
                        )
                    )
                }

                scope.replace(column, replacement)
            }
        }
    }

    return columnTables
}

/**
 * sqlglot: qualify_columns._expand_alias_refs — expand references to aliases, e.g.
 * SELECT y.foo AS bar, bar * 2 AS baz FROM y
 * => SELECT y.foo AS bar, y.foo * 2 AS baz FROM y
 */
private fun expandAliasRefsInScope(
    scope: Scope,
    resolver: Resolver,
    dialect: Dialect,
    expandOnlyGroupby: Boolean = false,
) {
    val expression = scope.expression

    if (expression !is Select || dialect.disablesAliasRefExpansion) {
        return
    }

    val aliasToExpression = mutableMapOf<String, Pair<Expression, Int>>()
    val projections = expression.selects.filterIsInstance<Expression>()
        .map { it.aliasOrName }
        .toSet()
    var replaced = false

    fun replaceColumns(
        node: Expression?,
        resolveTable: Boolean = false,
        literalIndex: Boolean = false,
    ) {
        val isGroupBy = node is Group
        val isHaving = node is dev.brikk.house.sql.ast.Having
        val isQualify = node is Qualify
        if (node == null || (expandOnlyGroupby && !isGroupBy)) {
            return
        }

        for (column in walkInScope(node, prune = { it.isStar })) {
            if (column !is Column) {
                continue
            }

            // BigQuery's GROUP BY allows alias expansion only for standalone names, e.g:
            //   SELECT FUNC(col) AS col FROM t GROUP BY col --> Can be expanded
            //   SELECT FUNC(col) AS col FROM t GROUP BY FUNC(col) --> Shouldn't be
            // expanded, will result to FUNC(FUNC(col))
            // This not required for the HAVING clause as it can evaluate expressions
            // using both the alias & the table columns
            if (expandOnlyGroupby && isGroupBy && column.parent !== node) {
                continue
            }

            var skipReplace = false
            val table =
                if (resolveTable && column.table.isEmpty()) resolver.getTable(column.name)
                else null
            val (aliasExpr, i) = aliasToExpression[column.name] ?: (null to 1)

            if (aliasExpr != null) {
                skipReplace = aliasExpr.walk().any { it is AggFunc } &&
                    findAncestor(column) { it is AggFunc } != null &&
                    findAncestor(column) { it is Window || it is Select } !is Window

                // BigQuery's having clause gets confused if an alias matches a source.
                // SELECT x.a, max(x.b) as x FROM x GROUP BY 1 HAVING x > 1;
                // If "HAVING x" is expanded to "HAVING max(x.b)", BQ would blindly
                // replace the "x" reference with the projection MAX(x.b), i.e
                // HAVING MAX(MAX(x.b).b), resulting in the error:
                // "Aggregations of aggregations are not allowed"
                if ((isHaving || isQualify) && dialect.projectionAliasesShadowSourceNames) {
                    skipReplace = skipReplace || aliasExpr.findAll(Column::class).any {
                        (it as Column).parts[0].name in projections
                    }
                }
            } else if (dialect.projectionAliasesShadowSourceNames &&
                (isGroupBy || isHaving || isQualify)
            ) {
                val columnTable = table?.name ?: column.table
                if (columnTable in projections) {
                    // BigQuery's GROUP BY and HAVING clauses get confused if the column
                    // name matches a source name and a projection; do not qualify.
                    column.replace(toIdentifier(column.name))
                    replaced = true
                    return
                }
            }

            if (table != null && (aliasExpr == null || skipReplace)) {
                column.set("table", table)
            } else if (column.table.isEmpty() && aliasExpr != null && !skipReplace) {
                if ((aliasExpr is Literal || aliasExpr.isNumber) &&
                    (literalIndex || resolveTable)
                ) {
                    if (literalIndex) {
                        column.replace(Literal.number(i.toString()))
                        replaced = true
                    }
                } else {
                    replaced = true
                    val newColumn = column.replace(paren(aliasExpr)) as Expression
                    val simplified = simplifyParens(newColumn, dialect)
                    if (simplified !== newColumn) {
                        newColumn.replace(simplified)
                    }
                }
            }
        }
    }

    for ((i, projection) in expression.selects.withIndex()) {
        if (projection !is Expression) continue
        replaceColumns(projection)
        if (projection is Alias) {
            aliasToExpression[projection.alias] = (projection.thisArg as Expression) to (i + 1)
        }
    }

    var parentScope: Scope? = scope
    var onRightSubTree = false
    while (parentScope != null && !parentScope.isCte) {
        parentScope = parentScope.parent
        if (parentScope != null) {
            val parentExpr = parentScope.expression
            if (parentExpr is Union) {
                // NOTE: ported verbatim from Python, where this comparison also never
                // holds (the Union's right child is compared against the Union itself)
                onRightSubTree = parentExpr.right === parentExpr
            }
        }
    }

    // We shouldn't expand aliases if they match the recursive CTE's columns
    // and we are in the recursive part (right sub tree) of the CTE
    if (parentScope != null && onRightSubTree) {
        val cte = parentScope.expression.parent
        if (cte != null) {
            val with_ = cte.findAncestor(With::class) as? With
            if (with_ != null && with_.recursive) {
                val aliasColumns =
                    ((cte.args["alias"] as? Expression)?.args?.get("columns") as? List<*>)
                        ?.filterIsInstance<Expression>()
                val recursiveCteColumns =
                    if (!aliasColumns.isNullOrEmpty()) aliasColumns
                    else (cte.thisArg as Expression).selects
                for (recursiveCteColumn in recursiveCteColumns) {
                    aliasToExpression.remove(recursiveCteColumn.outputName)
                }
            }
        }
    }

    replaceColumns(expression.args["where"] as? Expression)
    replaceColumns(expression.args["group"] as? Expression, literalIndex = true)
    replaceColumns(expression.args["having"] as? Expression, resolveTable = true)
    replaceColumns(expression.args["qualify"] as? Expression, resolveTable = true)

    if (dialect.supportsAliasRefsInJoinConditions) {
        for (join in (expression.args["joins"] as? List<*>) ?: emptyList<Any?>()) {
            replaceColumns(join as? Expression)
        }
    }

    if (replaced) {
        scope.clearCache()
    }
}

// sqlglot: qualify_columns._expand_group_by
private fun expandGroupBy(scope: Scope, dialect: Dialect) {
    val expression = scope.expression
    val group = expression.args["group"] as? Group ?: return

    group.set(
        "expressions",
        expandPositionalReferences(
            scope,
            group.expressionsArg.filterIsInstance<Expression>(),
            dialect,
        ),
    )
    expression.set("group", group)
}

// sqlglot: qualify_columns._expand_order_by_and_distinct_on
private fun expandOrderByAndDistinctOn(scope: Scope, resolver: Resolver) {
    val expression = scope.expression

    if (expression !is Selectable) {
        return
    }

    for (modifierKey in listOf("order", "distinct")) {
        var modifier = expression.args[modifierKey] as? Expression
        if (modifier is Distinct) {
            modifier = modifier.args["on"] as? Expression
        }

        if (modifier == null) {
            continue
        }

        var modifierExpressions = modifier.expressionsArg.filterIsInstance<Expression>()
        if (modifierKey == "order") {
            modifierExpressions = modifierExpressions.map { it.thisArg as Expression }
        }

        val expanded = expandPositionalReferences(
            scope,
            modifierExpressions,
            resolver.dialect,
            alias = true,
        )
        for ((original, expandedNode) in modifierExpressions.zip(expanded)) {
            for (agg in original.walk().filter { it is AggFunc }) {
                for (col in agg.findAll(Column::class)) {
                    if ((col as Column).table.isEmpty()) {
                        col.set("table", resolver.getTable(col.name))
                    }
                }
            }

            original.replace(expandedNode)
        }

        if (expression.args["group"] != null) {
            val selectsMap = LinkedHashMap<Expression, Expression>()
            for (s in expression.selects) {
                selectsMap[s.thisArg as? Expression ?: s] = column(s.aliasOrName)
            }

            for (node in modifierExpressions) {
                node.replace(
                    if (node.isInt) {
                        toIdentifier(selectByPos(expression, node as Literal).alias)!!
                    } else {
                        selectsMap[node] ?: node
                    }
                )
            }
        }
    }
}

// sqlglot: qualify_columns._expand_positional_references
private fun expandPositionalReferences(
    scope: Scope,
    expressions: Iterable<Expression>,
    dialect: Dialect,
    alias: Boolean = false,
): List<Expression> {
    val newNodes = mutableListOf<Expression>()
    var ambiguousProjections: Set<String>? = null

    val expression = scope.expression

    if (expression !is Selectable) {
        return newNodes
    }

    for (node in expressions) {
        if (node.isInt && node is Literal) {
            val select = selectByPos(expression, node)

            if (alias) {
                newNodes.add(column((select.args["alias"] as Expression).copy()))
            } else {
                val selectExpr = select.thisArg as Expression

                val ambiguous: Boolean
                if (dialect.projectionAliasesShadowSourceNames) {
                    if (ambiguousProjections == null) {
                        // When a projection name is also a source name and it is
                        // referenced in the GROUP BY clause, BQ can't understand what
                        // the identifier corresponds to
                        ambiguousProjections = expression.selects
                            .map { it.aliasOrName }
                            .filter { it in scope.selectedSources }
                            .toSet()
                    }

                    ambiguous = selectExpr.findAll(Column::class).any {
                        (it as Column).parts[0].name in ambiguousProjections!!
                    }
                } else {
                    ambiguous = false
                }

                if (isConstant(selectExpr) ||
                    selectExpr.isNumber ||
                    selectExpr.find(Explode::class, Unnest::class) != null ||
                    ambiguous
                ) {
                    newNodes.add(node)
                } else {
                    newNodes.add(selectExpr.copy())
                }
            }
        } else {
            newNodes.add(node)
        }
    }

    return newNodes
}

// sqlglot: Expression.find_ancestor over trait interfaces (AggFunc/Window are not
// Expression subclasses in Kotlin, so KClass-based findAncestor can't dispatch them)
private inline fun findAncestor(node: Expression, predicate: (Expression) -> Boolean): Expression? {
    var ancestor = node.parent
    while (ancestor != null && !predicate(ancestor)) {
        ancestor = ancestor.parent
    }
    return ancestor
}

// sqlglot: exp.CONSTANTS = (Literal, Boolean, Null)
private fun isConstant(expression: Expression): Boolean =
    expression is Literal || expression is dev.brikk.house.sql.ast.Boolean || expression is Null

// sqlglot: qualify_columns._select_by_pos
private fun selectByPos(expression: Expression, node: Literal): Alias {
    val select = expression.selects.getOrNull((node.thisArg as String).toInt() - 1)
        ?: throw OptimizeError("Unknown output column: ${node.name}")
    check(select is Alias) { "Expected Alias, got: $select" }
    return select
}

/**
 * sqlglot: qualify_columns._convert_columns_to_dots — converts Column instances that
 * represent STRUCT or JSON field lookups into chained Dots, so they can be qualified.
 */
private fun convertColumnsToDots(scope: Scope, resolver: Resolver) {
    var converted = false
    for (column in scope.columns + scope.stars) {
        if (column is Dot) {
            continue
        }

        val columnTable: String = (column as Column).table
        val dotParts = column.metaOrNull?.remove("dot_parts") as? List<*> ?: emptyList<Any?>()
        if (columnTable.isNotEmpty() &&
            columnTable !in scope.selectedSources &&
            (scope.parent == null ||
                columnTable !in scope.parent!!.sources ||
                !scope.isCorrelatedSubquery)
        ) {
            val allParts = column.parts
            var root = allParts[0]
            var parts = allParts.drop(1)

            val newColumnTable: Any?
            val wasQualified: Boolean
            if (root is Identifier && root.name in scope.selectedSources) {
                // The struct is already qualified, but we still need to change the AST
                newColumnTable = root
                root = parts[0]
                parts = parts.drop(1)
                wasQualified = true
            } else {
                newColumnTable = resolver.getTable(root.name)
                wasQualified = false
            }

            if (newColumnTable != null) {
                converted = true
                val newColumn = column(root, table = newColumnTable)

                if (dotParts.isNotEmpty()) {
                    // Remove the actual column parts from the rest of dot parts
                    newColumn.meta["dot_parts"] =
                        dotParts.drop(if (wasQualified) 2 else 1)
                }

                column.replace(Dot.build(listOf(newColumn) + parts))
            }
        }
    }

    if (converted) {
        // We want to re-aggregate the converted columns, otherwise they'd be skipped in
        // a `for column in scope.columns` iteration, even though they shouldn't be
        scope.clearCache()
    }
}

// sqlglot: qualify_columns._qualify_columns — disambiguate columns, ensuring each
// column specifies a source
private fun qualifyColumnsInScope(
    scope: Scope,
    resolver: Resolver,
    allowPartialQualification: Boolean,
) {
    for (column in scope.columns) {
        val columnTable = column.table
        val columnName = column.name

        if (columnTable.isNotEmpty() && columnTable in scope.sources) {
            val columnSource = scope.sources[columnTable]
            var sourceColumns: Collection<String> = resolver.getSourceColumns(columnTable)
            // For pivoted sources, source_columns are pre-pivot; validate against the
            // post-pivot set.
            val pivots = (columnSource as? Table)?.args?.get("pivots") as? List<*>
            if (!pivots.isNullOrEmpty()) {
                sourceColumns = (pivots[0] as Pivot).outputColumns(sourceColumns).keys
            }
            if (!allowPartialQualification &&
                sourceColumns.isNotEmpty() &&
                columnName !in sourceColumns &&
                "*" !in sourceColumns
            ) {
                throw OptimizeError("Unknown column: $columnName")
            }
        }

        if (columnTable.isEmpty()) {
            if (scope.pivots.isNotEmpty() && column.findAncestor(Pivot::class) == null) {
                // If the column is under the Pivot expression, we need to qualify it
                // using the name of the pivoted source instead of the pivot's alias
                column.set("table", toIdentifier(scope.pivots[0].alias))
                continue
            }

            // column_table can be a '' because bigquery unnest has no table alias
            val table = resolver.getTable(column)

            if (table != null) {
                val source = scope.sources[table.name]
                if (source is Scope && source.inColumnIndex(column)) {
                    continue
                }
            }

            if (table != null) {
                column.set("table", table)
            } else if (resolver.dialect.tablesReferenceableAsColumns &&
                column.parts.size == 1 &&
                columnName in scope.selectedSources
            ) {
                // BigQuery and Postgres allow tables to be referenced as columns,
                // treating them as structs/records
                scope.replace(column, TableColumn(args("this" to column.thisArg)))
            }
        }
    }

    for (pivot in scope.pivots) {
        for (column in pivot.findAll(Column::class)) {
            if ((column as Column).table.isEmpty() && column.name in resolver.allColumns) {
                val table = resolver.getTable(column.name)
                if (table != null) {
                    column.set("table", table)
                }
            }
        }
    }
}

// sqlglot: qualify_columns._expand_struct_stars_no_parens
// [BigQuery] Expand/Flatten foo.bar.* where bar is a struct column
private fun expandStructStarsNoParens(expression: Dot): List<Alias> {
    val dotColumn = expression.find(Column::class)
    if (dotColumn !is Column || !dotColumn.isType(DType.STRUCT)) {
        return emptyList()
    }

    // All nested struct values are ColumnDefs, so normalize the first exp.Column in one
    val dotColumnCopy = dotColumn.copy() as Column
    var startingStruct: Expression = ColumnDef(
        args("this" to dotColumnCopy.thisArg, "kind" to dotColumnCopy.type)
    )

    // First part is the table name and last part is the star so they can be dropped
    val dotParts = expression.parts.drop(1).dropLast(1)

    // If we're expanding a nested struct eg. t.c.f1.f2.* find the last struct (f2 here)
    for (part in dotParts.drop(1)) {
        var found = false
        for (field in (startingStruct.args["kind"] as DataType).expressionsArg) {
            val fieldExpr = field as Expression
            // Unable to expand star unless all fields are named
            if (fieldExpr.thisArg !is Identifier) {
                return emptyList()
            }

            if (fieldExpr.name == part.name &&
                (fieldExpr.args["kind"] as? DataType)?.isType(DType.STRUCT) == true
            ) {
                startingStruct = fieldExpr
                found = true
                break
            }
        }
        if (!found) {
            // There is no matching field in the struct
            return emptyList()
        }
    }

    val takenNames = mutableSetOf<String>()
    val newSelections = mutableListOf<Alias>()

    for (field in (startingStruct.args["kind"] as DataType).expressionsArg) {
        val fieldExpr = field as Expression
        val name = fieldExpr.name

        // Ambiguous or anonymous fields can't be expanded
        if (name in takenNames || fieldExpr.thisArg !is Identifier) {
            return emptyList()
        }

        takenNames.add(name)

        val fieldIdentifier = (fieldExpr.thisArg as Identifier).copy() as Identifier
        val chained = (dotParts + fieldIdentifier).map { it.copy() }
        val root = chained[0]
        val rest = chained.drop(1)
        val newColumn = column(
            root as Identifier,
            table = dotColumnCopy.args["table"],
            fields = rest,
        )
        newSelections.add(
            aliasExpression(newColumn, fieldIdentifier, copy = false) as Alias
        )
    }

    return newSelections
}

// sqlglot: qualify_columns._expand_struct_stars_with_parens
// [RisingWave] Expand/Flatten (<exp>.bar).*, where bar is a struct column
private fun expandStructStarsWithParens(expression: Dot): List<Alias> {
    // it is not (<sub_exp>).* pattern, which means we can't expand
    if (expression.thisArg !is Paren) {
        return emptyList()
    }

    // find column definition to get data-type
    val dotColumn = expression.find(Column::class)
    if (dotColumn !is Column || !dotColumn.isType(DType.STRUCT)) {
        return emptyList()
    }

    var parent: Expression? = dotColumn.parent
    var startingStruct: DataType? = dotColumn.type as? DataType

    // walk up AST and down into struct definition in sync
    while (parent != null) {
        if (parent is Paren) {
            parent = parent.parent
            continue
        }

        // if parent is not a dot, then something is wrong
        if (parent !is Dot) {
            return emptyList()
        }

        // if the rhs of the dot is star we are done
        val rhs = parent.right
        if (rhs is Star) {
            break
        }

        // if it is not identifier, then something is wrong
        if (rhs !is Identifier) {
            return emptyList()
        }

        // Check if current rhs identifier is in struct
        var matched = false
        for (structFieldDef in (startingStruct?.expressionsArg ?: emptyList())) {
            val fieldDef = structFieldDef as Expression
            if (fieldDef.name == rhs.name) {
                matched = true
                startingStruct = fieldDef.args["kind"] as? DataType // update struct
                break
            }
        }

        if (!matched) {
            return emptyList()
        }

        parent = parent.parent
    }

    // build new aliases to expand star
    val newSelections = mutableListOf<Alias>()

    // fetch the outermost parentheses for new aliases
    val outerParen = expression.thisArg as Expression

    for (structFieldDef in (startingStruct?.expressionsArg ?: emptyList())) {
        val fieldDef = structFieldDef as Expression
        val newIdentifier = (fieldDef.thisArg as Expression).copy()
        val newDot = Dot.build(listOf(outerParen.copy(), newIdentifier))
        val newAlias = aliasExpression(newDot, newIdentifier, copy = false) as Alias
        newSelections.add(newAlias)
    }

    return newSelections
}

// sqlglot: qualify_columns._expand_stars — expand stars to lists of column selections
private fun expandStarsInScope(
    scope: Scope,
    resolver: Resolver,
    usingColumnTables: Map<String, Map<String, Any?>>,
    pseudocolumns: Set<String>,
    annotator: TypeAnnotator,
) {
    val newSelections = mutableListOf<Expression>()
    val exceptColumns = HashMap<Any, Set<String>>()
    val replaceColumns = HashMap<Any, Map<String, Alias>>()
    val renameColumns = HashMap<Any, Map<String, String>>()
    var ilikePattern: String? = null

    val coalescedColumns = mutableSetOf<String>()
    val dialect = resolver.dialect

    val pivot = scope.pivots.getOrNull(0) as? Pivot

    if (dialect.supportsStructStarExpansion && scope.stars.any { it is Dot }) {
        // Found struct expansion, annotate scope ahead of time
        annotator.annotateScope(scope)
    }

    val scopeExpression = scope.expression

    if (scopeExpression !is Selectable) {
        return
    }

    for (expression in scopeExpression.selects) {
        // Pairs of (table name, identity key mirroring Python's id(table) semantics):
        // bare `*` shares the selected_sources key across selections; a qualified
        // `t.*` gets a per-occurrence key (its identifier's string object in Python).
        val tables = mutableListOf<Pair<String, Any>>()
        if (expression is Star) {
            for (name in scope.selectedSources.keys) {
                tables.add(name to name)
            }
            val tableKeys = tables.map { it.second }
            addExceptColumns(expression, tableKeys, exceptColumns)
            addReplaceColumns(expression, tableKeys, replaceColumns)
            addRenameColumns(expression, tableKeys, renameColumns)
            ilikePattern = addIlikeColumns(expression)
        } else if (expression.isStar) {
            if (expression is Column) {
                tables.add(expression.table to Any())
                val tableKeys = tables.map { it.second }
                val star = expression.thisArg as Expression
                addExceptColumns(star, tableKeys, exceptColumns)
                addReplaceColumns(star, tableKeys, replaceColumns)
                addRenameColumns(star, tableKeys, renameColumns)
                ilikePattern = addIlikeColumns(star)
            } else if (expression is Dot) {
                if (dialect.supportsStructStarExpansion &&
                    !dialect.requiresParenthesizedStructAccess
                ) {
                    val structFields = expandStructStarsNoParens(expression)
                    if (structFields.isNotEmpty()) {
                        newSelections.addAll(structFields)
                        continue
                    }
                } else if (dialect.requiresParenthesizedStructAccess) {
                    val structFields = expandStructStarsWithParens(expression)
                    if (structFields.isNotEmpty()) {
                        newSelections.addAll(structFields)
                        continue
                    }
                }
            }
        }

        if (tables.isEmpty()) {
            newSelections.add(expression)
            continue
        }

        for ((table, tableId) in tables) {
            val source = scope.sources[table]
                ?: throw OptimizeError("Unknown table: $table")

            var columns = resolver.getSourceColumns(table, onlyVisible = true)
            if (columns.isEmpty()) {
                columns = scope.outerColumns
            }

            if (pseudocolumns.isNotEmpty() && dialect.excludesPseudocolumnsFromStar) {
                columns = columns.filter { it.uppercase() !in pseudocolumns }
            }

            if (columns.isEmpty() || "*" in columns) {
                return
            }

            val columnsToExclude = exceptColumns[tableId] ?: emptySet()
            val renamedColumns = renameColumns[tableId] ?: emptyMap()
            val replacedColumns = replaceColumns[tableId] ?: emptyMap()

            // Preserve case-sensitivity of quoted source columns when expanding stars,
            // so the generated alias isn't folded by dialect normalization
            val sourceExpression = (source as? Scope)?.expression
            val quotedColumns: Set<String> =
                if (sourceExpression is Query) {
                    sourceExpression.selects
                        .filter { outputIdentifierQuoted(it) }
                        .map { it.outputName }
                        .toSet()
                } else {
                    emptySet()
                }

            if (pivot != null) {
                var pivotColumns: Collection<String> = pivot.outputColumns(columns).keys
                if (pivotColumns.isEmpty()) {
                    pivotColumns = pivot.aliasColumnNames
                }

                if (pivotColumns.isNotEmpty()) {
                    newSelections.addAll(
                        pivotColumns
                            .filter { it !in columnsToExclude }
                            .map { name ->
                                aliasExpression(
                                    column(
                                        name,
                                        table = pivot.alias.takeIf { it.isNotEmpty() },
                                    ),
                                    name,
                                    copy = false,
                                )
                            }
                    )
                    continue
                }
            }

            for (name in columns) {
                if (name in columnsToExclude || name in coalescedColumns) {
                    continue
                }
                if (ilikePattern != null &&
                    !Regex(ilikePattern, RegexOption.IGNORE_CASE).matches(name)
                ) {
                    continue
                }
                val usingTables = usingColumnTables[name]
                if (usingTables != null && table in usingTables) {
                    coalescedColumns.add(name)
                    val coalesceArgs = usingTables.keys.map { column(name, table = it) }

                    newSelections.add(
                        aliasExpression(coalesce(coalesceArgs), alias = name, copy = false)
                    )
                } else {
                    val alias_ = renamedColumns[name] ?: name
                    val quoted = name in quotedColumns ||
                        // if it has characters that the dialect would have changed,
                        // infer that it was quoted.
                        (source is Table && dialect.caseSensitive(name))
                    val selectionExpr: Expression = replacedColumns[name]
                        ?: column(name, table = table, quoted = quoted)
                    newSelections.add(
                        if (alias_ != name) {
                            aliasExpression(selectionExpr, alias_, copy = false)
                        } else {
                            selectionExpr
                        }
                    )
                }
            }
        }
    }

    // Ensures we don't overwrite the initial selections with an empty list
    if (newSelections.isNotEmpty() && scopeExpression is Select) {
        scopeExpression.set("expressions", newSelections)
    }
}

// sqlglot: qualify_columns._output_identifier_quoted — whether a projection's output
// column name is a quoted (case-sensitive) identifier.
private fun outputIdentifierQuoted(selection: Expression): Boolean {
    val identifier = when (selection) {
        is Alias -> selection.args["alias"]
        is Column -> selection.thisArg
        else -> null
    }

    return identifier is Identifier && identifier.quoted
}

// sqlglot: qualify_columns._add_ilike_columns
private fun addIlikeColumns(expression: Expression): String? {
    val ilike = expression.args["ilike"] as? Expression ?: return null

    return ilike.name.toCharArray().joinToString("") { c ->
        when (c) {
            '%' -> ".*"
            '_' -> "."
            else -> Regex.escape(c.toString())
        }
    }
}

// sqlglot: qualify_columns._add_except_columns
private fun addExceptColumns(
    expression: Expression,
    tables: List<Any>,
    exceptColumns: MutableMap<Any, Set<String>>,
) {
    val except = (expression.args["except_"] as? List<*>)?.filterIsInstance<Expression>()

    if (except.isNullOrEmpty()) {
        return
    }

    val columns = except.map { it.name }.toSet()

    for (table in tables) {
        exceptColumns[table] = columns
    }
}

// sqlglot: qualify_columns._add_rename_columns
private fun addRenameColumns(
    expression: Expression,
    tables: List<Any>,
    renameColumns: MutableMap<Any, Map<String, String>>,
) {
    val rename = (expression.args["rename"] as? List<*>)?.filterIsInstance<Expression>()

    if (rename.isNullOrEmpty()) {
        return
    }

    val columns = rename.associate { (it.thisArg as Expression).name to it.alias }

    for (table in tables) {
        renameColumns[table] = columns
    }
}

// sqlglot: qualify_columns._add_replace_columns
private fun addReplaceColumns(
    expression: Expression,
    tables: List<Any>,
    replaceColumns: MutableMap<Any, Map<String, Alias>>,
) {
    val replace = (expression.args["replace"] as? List<*>)?.filterIsInstance<Alias>()

    if (replace.isNullOrEmpty()) {
        return
    }

    val columns = replace.associateBy { it.alias }

    for (table in tables) {
        replaceColumns[table] = columns
    }
}

// sqlglot: qualify_columns.qualify_outputs — ensure all output columns are aliased
fun qualifyOutputs(scopeOrExpression: Any, dialect: Dialect) {
    val scope: Scope = when (scopeOrExpression) {
        is Expression -> buildScope(scopeOrExpression) ?: return
        is Scope -> scopeOrExpression
        else -> return
    }

    val expression = scope.expression

    if (expression !is Selectable) {
        return
    }

    val newSelections = mutableListOf<Expression>()

    val selects = expression.selects
    val outerColumns = scope.outerColumns
    val size = maxOf(selects.size, outerColumns.size)

    for (i in 0 until size) {
        var selection = selects.getOrNull(i)
        val aliasedColumn = outerColumns.getOrNull(i)

        if (selection == null || selection is QueryTransform) {
            break
        }

        if (selection is Subquery) {
            if (selection.outputName.isEmpty()) {
                val aliasIdentifier = toIdentifier("_col_$i")!!
                dialect.normalizeIdentifier(aliasIdentifier)
                selection.set("alias", TableAlias(args("this" to aliasIdentifier)))
            }
        } else if (selection !is Alias && selection !is Aliases && !selection.isStar) {
            val sourceQuoted =
                selection is Column && (selection.thisArg as? Identifier)?.quoted == true
            selection = aliasExpression(
                selection,
                alias = selection.outputName.ifEmpty { "_col_$i" },
                copy = false,
            )
            if (sourceQuoted) {
                (selection.args["alias"] as Expression).set("quoted", true)
            }
            dialect.normalizeIdentifier(selection.args["alias"] as Expression)
        }
        if (!aliasedColumn.isNullOrEmpty()) {
            selection.set("alias", toIdentifier(aliasedColumn))
        }

        newSelections.add(selection)
    }

    if (newSelections.isNotEmpty() && expression is Select) {
        expression.set("expressions", newSelections)
    }
}

// sqlglot: qualify_columns.quote_identifiers — makes sure all identifiers that need
// to be quoted are quoted.
fun <E : Expression> quoteIdentifiers(
    expression: E,
    dialect: Dialect? = null,
    identify: Boolean = true,
): E {
    val resolvedDialect = dialect ?: Dialects.BASE

    // `quote_identifier` only mutates identifiers in place, so we avoid `transform`
    // here because its node replacement machinery is wasteful for this case.
    for (node in expression.walk()) {
        if (node is Identifier) {
            resolvedDialect.quoteIdentifier(node, identify = identify)
        }
    }

    return expression
}

/**
 * sqlglot: qualify_columns.pushdown_cte_alias_columns — pushes down the CTE alias
 * columns into the projection. Useful in Snowflake where the CTE alias columns can be
 * referenced in the HAVING.
 */
fun pushdownCteAliasColumns(scope: Scope) {
    for (cte in scope.ctes) {
        val aliasColumnNames = cte.aliasColumnNames
        val cteThis = cte.thisArg
        if (aliasColumnNames.isNotEmpty() && cteThis is Select) {
            val newExpressions = mutableListOf<Expression>()
            val projections = cteThis.expressionsArg.filterIsInstance<Expression>()
            for ((aliasName, projection) in aliasColumnNames.zip(projections)) {
                var newProjection = projection
                if (newProjection is Alias) {
                    newProjection.set("alias", toIdentifier(aliasName))
                } else {
                    newProjection = aliasExpression(newProjection, alias = aliasName)
                }
                newExpressions.add(newProjection)
            }
            cteThis.set("expressions", newExpressions)
        }
    }
}

// sqlglot: simplify.simplify_parens (the slice reached from _expand_alias_refs)
internal fun simplifyParens(expression: Expression, dialect: Dialect): Expression {
    if (expression !is Paren) {
        return expression
    }

    val this_ = expression.thisArg as Expression
    val parent = expression.parent
    val parentIsPredicate = parent is Predicate

    if (this_ is Select) {
        return expression
    }

    if (parent is SubqueryPredicate || parent is Bracket) {
        return expression
    }

    if (dialect.requiresParenthesizedStructAccess &&
        parent is Dot &&
        (parent.right is Identifier || parent.right is Star)
    ) {
        return expression
    }

    if (this_ is Predicate &&
        !(parentIsPredicate || parent is Neg || (parent is Binary && parent !is Connector))
    ) {
        return this_
    }

    if (!(parent is Condition || parent is Binary) ||
        parent is Paren ||
        (this_ !is Binary && !((this_ is Not || this_ is Is) && parentIsPredicate)) ||
        (this_ is Add && parent is Add) ||
        (this_ is Mul && parent is Mul) ||
        (this_ is Mul && (parent is Add || parent is Sub))
    ) {
        return this_
    }

    return expression
}
