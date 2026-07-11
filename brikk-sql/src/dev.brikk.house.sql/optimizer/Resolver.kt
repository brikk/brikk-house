package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.QueryTransform
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Selectable
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.ast.aliasColumnNames
import dev.brikk.house.sql.ast.namedSelects
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.ast.toIdentifier
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

/**
 * Port of sqlglot/optimizer/resolver.py — helper for resolving columns.
 *
 * This is a class so we can lazily load some things and easily share them across
 * functions.
 */
// sqlglot: resolver.Resolver
class Resolver(
    val scope: Scope,
    val schema: Schema,
    private val inferSchema: Boolean = true,
) {
    val dialect: Dialect = schema.dialect ?: Dialects.BASE

    private var sourceColumnsAll: MutableMap<String, List<String>>? = null
    private var unambiguousColumns: Map<String, String>? = null
    private var allColumnsCache: Set<String>? = null
    private val getSourceColumnsCache = HashMap<Pair<String, Boolean>, List<String>>()
    private val columnTypeFromScopeCache = HashMap<Pair<Long, String>, DataType?>()

    /**
     * sqlglot: Resolver.get_table — get the table for a column name (or Column node).
     * Returns the table name Identifier if it can be found/inferred.
     */
    fun getTable(column: Any): Identifier? {
        val columnName = if (column is String) column else (column as Column).name

        var tableName: String? = getTableNameFromSources(columnName)

        if (tableName.isNullOrEmpty() && column is Column) {
            // Fall-back case: If we couldn't find the `table_name` from ALL of the
            // sources, attempt to disambiguate the column based on other characteristics,
            // e.g. if this column is in a join condition, we may be able to disambiguate
            // based on the source order.
            val joinContext = getColumnJoinContext(column)
            if (joinContext != null) {
                // Catch OptimizeError if the column is still ambiguous and try to resolve
                // with schema inference below
                try {
                    tableName = getTableNameFromSources(
                        columnName,
                        getAvailableSourceColumns(joinContext),
                    )
                } catch (e: OptimizeError) {
                    // pass
                }
            }
        }

        if (tableName.isNullOrEmpty() && inferSchema) {
            val sourcesWithoutSchema = getAllSourceColumns()
                .filterValues { it.isEmpty() || "*" in it }
                .keys
                .toList()
            if (sourcesWithoutSchema.size == 1) {
                tableName = sourcesWithoutSchema[0]
            }
        }

        if (tableName == null || tableName !in scope.selectedSources) {
            return toIdentifier(tableName)
        }

        var node: Expression = scope.selectedSources.getValue(tableName).first

        if (node is Query) {
            while (node.alias != tableName && node.parent != null) {
                node = node.parent!!
            }
        }

        val nodeAlias = node.args["alias"] as? Expression
        if (nodeAlias != null) {
            return toIdentifier(nodeAlias.thisArg)
        }

        return toIdentifier(tableName)
    }

    // sqlglot: Resolver.all_columns — all available columns of all sources in this scope
    val allColumns: Set<String>
        get() {
            var cached = allColumnsCache
            if (cached == null) {
                cached = getAllSourceColumns().values.flatten().toSet()
                allColumnsCache = cached
            }
            return cached
        }

    // sqlglot: Resolver.get_source_columns_from_set_op
    fun getSourceColumnsFromSetOp(expression: Expression): List<String> {
        if (expression is Select) {
            return expression.namedSelects
        }
        if (expression is Subquery && expression.thisArg is SetOperation) {
            // Different types of SET modifiers can be chained together if they're
            // explicitly grouped by nesting
            return getSourceColumnsFromSetOp(expression.thisArg as Expression)
        }
        if (expression !is SetOperation) {
            throw OptimizeError("Unknown set operation: $expression")
        }

        val setOp = expression

        // BigQuery specific set operations modifiers, e.g INNER UNION ALL BY NAME
        val onColumnList = setOp.args["on"] as? List<*>

        if (!onColumnList.isNullOrEmpty()) {
            // The resulting columns are the columns in the ON clause:
            // {INNER | LEFT | FULL} UNION ALL BY NAME ON (col1, col2, ...)
            return onColumnList.filterIsInstance<Expression>().map { it.name }
        }

        val side = setOp.text("side")
        val kind = setOp.text("kind")
        if (side.isNotEmpty() || kind.isNotEmpty()) {
            // Visit the children UNIONs (if any) in a post-order traversal
            val left = getSourceColumnsFromSetOp(setOp.left)
            val right = getSourceColumnsFromSetOp(setOp.right)

            return when {
                side == "LEFT" -> left
                side == "FULL" -> (left + right).distinct()
                // Python computes `dict.fromkeys(left).keys() & dict.fromkeys(right).keys()`
                // (an unordered set); we keep left order deterministically.
                kind == "INNER" -> left.distinct().filter { it in right.toSet() }
                else -> emptyList()
            }
        }

        return setOp.namedSelects
    }

    // sqlglot: Resolver.get_source_columns — resolve the source columns for a given source name.
    fun getSourceColumns(name: String, onlyVisible: Boolean = false): List<String> {
        val cacheKey = name to onlyVisible
        return getSourceColumnsCache.getOrPut(cacheKey) {
            if (name !in scope.sources) {
                throw OptimizeError("Unknown table: $name")
            }

            var source: Any = scope.sources.getValue(name)

            // A pivoted CTE reference is stored as an exp.Table in the scope sources (see
            // _traverse_tables in scope.py), but the underlying CTE Scope still holds the
            // column information we need to resolve pre-pivot columns.
            if (source is Table && source.db.isEmpty() &&
                !(source.args["pivots"] as? List<*>).isNullOrEmpty() &&
                source.name in scope.cteSources
            ) {
                source = scope.cteSources.getValue(source.name)
            }

            var columns: List<String>
            if (source is Table) {
                columns = schema.columnNames(source, onlyVisible)
            } else if (source is Scope &&
                (source.expression is Values || source.expression is Unnest ||
                    source.expression is Lateral)
            ) {
                val sourceExpr = source.expression
                columns = sourceExpr.namedSelects.toMutableList()

                // In bigquery, unnest structs are automatically scoped as tables, so you
                // can directly select a struct field in a query. This handles the case
                // where the unnest is statically defined.
                if (dialect.unnestColumnOnly && sourceExpr is Unnest) {
                    if (sourceExpr.typeSlot == null ||
                        sourceExpr.isType(DType.UNKNOWN)
                    ) {
                        val unnestExpr = sourceExpr.expressionsArg.getOrNull(0)
                        if (unnestExpr is Column && unnestExpr::class == Column::class &&
                            scope.parent != null
                        ) {
                            val colType = getUnnestColumnType(unnestExpr, scope.parent!!)
                            if (colType != null && colType.isType(DType.ARRAY)) {
                                val elementTypes = colType.expressionsArg
                                if (elementTypes.isNotEmpty()) {
                                    sourceExpr.typeSlot =
                                        (elementTypes[0] as Expression).copy()
                                }
                            } else if (colType != null) {
                                sourceExpr.typeSlot = colType.copy()
                            }
                        }
                    }

                    columns = columns + structFieldNames(sourceExpr.typeSlot as? DataType)
                } else if (sourceExpr is Lateral && sourceExpr.thisArg is Explode) {
                    val explodeCol = (sourceExpr.thisArg as Expression).thisArg

                    // If the column is unqualified at this point, it couldn't be resolved
                    // when this scope's children were qualified; disambiguating it here
                    // would require enumerating this very source's columns, i.e recurse
                    // without bound
                    if (explodeCol is Column && explodeCol.table.isNotEmpty() &&
                        source.parent != null
                    ) {
                        val colType = getUnnestColumnType(explodeCol, source.parent!!)
                        columns = columns + structFieldNames(colType)
                    }
                } else if (sourceExpr is Lateral && sourceExpr.thisArg is Query) {
                    columns = (sourceExpr.thisArg as Expression).namedSelects
                }
            } else if (source is Scope && source.expression is SetOperation) {
                columns = getSourceColumnsFromSetOp(source.expression)
            } else {
                val sourceScope = source as Scope
                val selectable = sourceScope.expression
                check(selectable is Selectable) { "Expected Selectable, got: $selectable" }
                val select = selectable.selects.getOrNull(0)

                columns = if (select is QueryTransform) {
                    // https://spark.apache.org/docs/3.5.1/sql-ref-syntax-qry-select-transform.html
                    val transformSchema = select.args["schema"] as? Expression
                    transformSchema?.expressionsArg
                        ?.filterIsInstance<Expression>()
                        ?.map { it.name }
                        ?: listOf("key", "value")
                } else {
                    selectable.namedSelects
                }
            }

            val node = scope.selectedSources[name]?.first
            val columnAliases = node?.aliasColumnNames ?: emptyList()

            if (columnAliases.isNotEmpty()) {
                // If the source's columns are aliased, their aliases shadow the
                // corresponding column names (itertools.zip_longest + `alias or name`).
                val size = maxOf(columns.size, columnAliases.size)
                columns = (0 until size).map { i ->
                    columnAliases.getOrNull(i)?.takeIf { it.isNotEmpty() }
                        ?: columns.getOrNull(i)
                        ?: ""
                }
            }

            columns
        }
    }

    // sqlglot: Resolver._get_all_source_columns
    private fun getAllSourceColumns(): Map<String, List<String>> {
        var cached = sourceColumnsAll
        if (cached == null) {
            cached = LinkedHashMap()
            for (sourceName in scope.selectedSources.keys + scope.lateralSources.keys) {
                cached[sourceName] = getSourceColumns(sourceName)
            }
            sourceColumnsAll = cached
        }
        return cached
    }

    // sqlglot: Resolver._get_table_name_from_sources
    private fun getTableNameFromSources(
        columnName: String,
        sourceColumns: Map<String, List<String>>? = null,
    ): String? {
        val unambiguous: Map<String, String>
        if (sourceColumns.isNullOrEmpty()) {
            // If not supplied, get all sources to calculate unambiguous columns
            var cached = unambiguousColumns
            if (cached == null) {
                cached = getUnambiguousColumns(getAllSourceColumns())
                unambiguousColumns = cached
            }
            unambiguous = cached
        } else {
            unambiguous = getUnambiguousColumns(sourceColumns)
        }

        return unambiguous[columnName]
    }

    /**
     * sqlglot: Resolver._get_column_join_context — check if a column participating in
     * a join can be qualified based on the source order.
     */
    private fun getColumnJoinContext(column: Column): Join? {
        val args = scope.expression.args
        val joins = args["joins"] as? List<*>

        if (joins.isNullOrEmpty() || !(args["laterals"] as? List<*>).isNullOrEmpty() ||
            !(args["pivots"] as? List<*>).isNullOrEmpty()
        ) {
            // Feature gap: We currently don't try to disambiguate columns if other
            // sources (e.g laterals, pivots) exist alongside joins
            return null
        }

        val joinAncestor = column.findAncestor(Join::class, Select::class)

        if (joinAncestor is Join && joinAncestor.aliasOrName in scope.selectedSources) {
            // Ensure that the found ancestor is a join that contains an actual source,
            // e.g in Clickhouse `b` is an array expression in `a ARRAY JOIN b`
            return joinAncestor
        }

        return null
    }

    /**
     * sqlglot: Resolver._get_available_source_columns — the source columns available at
     * the point where a column is referenced: the FROM table plus the joined tables up
     * to (and including) the current join.
     */
    private fun getAvailableSourceColumns(joinAncestor: Join): Map<String, List<String>> {
        val args = scope.expression.args

        val fromName = (args["from_"] as Expression).aliasOrName
        val availableSources = LinkedHashMap<String, List<String>>()
        availableSources[fromName] = getSourceColumns(fromName)

        @Suppress("UNCHECKED_CAST")
        val joins = args["joins"] as List<Expression>
        for (join in joins.subList(0, (joinAncestor.index ?: 0) + 1)) {
            availableSources[join.aliasOrName] = getSourceColumns(join.aliasOrName)
        }

        return availableSources
    }

    /**
     * sqlglot: Resolver._get_unambiguous_columns — find all the unambiguous columns in
     * sources, as a mapping of column name to source name.
     */
    private fun getUnambiguousColumns(
        sourceColumns: Map<String, List<String>>,
    ): Map<String, String> {
        if (sourceColumns.isEmpty()) {
            return emptyMap()
        }

        val pairs = sourceColumns.entries.toList()
        val firstTable = pairs[0].key
        val firstColumns = pairs[0].value

        if (pairs.size == 1) {
            // sqlglot: SingleValuedMapping (perf shortcut; a plain map here)
            return firstColumns.associateWith { firstTable }
        }

        // For BigQuery UNNEST_COLUMN_ONLY, build a mapping of original UNNEST aliases
        // from alias.columns[0] to their source names. This is used to resolve shadowing
        // where an UNNEST alias shadows a column name from another table.
        val unnestOriginalAliases = mutableMapOf<String, String>()
        if (dialect.unnestColumnOnly) {
            for ((sourceName, source) in scope.sources) {
                val sourceExpr = when (source) {
                    is Scope -> source.expression
                    is Expression -> source.args["expression"] as? Expression
                    else -> null
                }
                if (sourceExpr is Unnest) {
                    val aliasArg = sourceExpr.args["alias"] as? Expression
                    val aliasColumns = aliasArg?.args?.get("columns") as? List<*>
                    if (!aliasColumns.isNullOrEmpty()) {
                        unnestOriginalAliases[(aliasColumns[0] as Expression).name] = sourceName
                    }
                }
            }
        }

        val unambiguousColumns = LinkedHashMap<String, String>()
        for (col in firstColumns) unambiguousColumns[col] = firstTable
        val allColumns = unambiguousColumns.keys.toMutableSet()

        for ((table, columns) in pairs.drop(1)) {
            val unique = columns.toSet()
            val ambiguous = allColumns.intersect(unique)
            allColumns.addAll(columns)

            for (column in ambiguous) {
                val original = unnestOriginalAliases[column]
                if (original != null) {
                    unambiguousColumns[column] = original
                    continue
                }

                unambiguousColumns.remove(column)
            }
            for (column in unique - ambiguous) {
                unambiguousColumns[column] = table
            }
        }

        return unambiguousColumns
    }

    // sqlglot: Resolver._struct_field_names
    private fun structFieldNames(colType: DataType?): List<String> {
        var type = colType
        if (type != null && type.isType(DType.ARRAY)) {
            type = type.expressionsArg.getOrNull(0) as? DataType
        }

        return if (type != null && type.isType(DType.STRUCT)) {
            type.expressionsArg.filterIsInstance<Expression>().map { it.name }
        } else {
            emptyList()
        }
    }

    /**
     * sqlglot: Resolver._get_unnest_column_type — the type of a column being
     * unnested/exploded, tracing through CTEs/subqueries to find the base table.
     */
    private fun getUnnestColumnType(column: Column, scope: Scope): DataType? {
        // If column is qualified, use that table, otherwise disambiguate using the resolver
        val tableName: String
        if (column.table.isNotEmpty()) {
            tableName = column.table
        } else {
            // Use the parent scope's resolver to disambiguate the column
            val parentResolver = Resolver(scope, schema, inferSchema)
            val tableIdentifier = parentResolver.getTable(column) ?: return null
            tableName = tableIdentifier.name
        }

        val source = scope.sources[tableName] ?: return null
        return getColumnTypeFromScope(source, column)
    }

    /**
     * sqlglot: Resolver._get_column_type_from_scope — a column's type found by tracing
     * through scopes/tables to the base table, memoized per (source identity, name).
     */
    private fun getColumnTypeFromScope(source: Any, column: Column): DataType? {
        val sourceId = when (source) {
            is Expression -> source.objectId
            is Scope -> source.expression.objectId
            else -> 0L
        }
        val cacheKey = sourceId to column.name
        if (cacheKey in columnTypeFromScopeCache) {
            return columnTypeFromScopeCache[cacheKey]
        }

        // None is a valid result if DataType could not be determined!
        var result: DataType? = null
        if (source is Table) {
            // Base table - get the column type from schema
            val colType = schema.getColumnType(source, column)
            if (!colType.isType(DType.UNKNOWN)) {
                result = colType
            }
        } else if (source is Scope) {
            // Iterate over all sources in the scope
            for (nestedSource in source.sources.values) {
                val nestedType = getColumnTypeFromScope(nestedSource, column)
                if (nestedType != null && !nestedType.isType(DType.UNKNOWN)) {
                    result = nestedType
                    break
                }
            }
        }

        columnTypeFromScopeCache[cacheKey] = result
        return result
    }
}
