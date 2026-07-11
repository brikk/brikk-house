package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.DerivedTable
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.In
import dev.brikk.house.sql.ast.Pivot
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Selectable
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.UDTF
import dev.brikk.house.sql.ast.aliasColumnNames
import dev.brikk.house.sql.ast.column
import dev.brikk.house.sql.ast.fields
import dev.brikk.house.sql.ast.isStar
import dev.brikk.house.sql.ast.namedSelects
import dev.brikk.house.sql.ast.outputColumns
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.ast.subquery
import dev.brikk.house.sql.ast.unpivot
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError

/**
 * Port of sqlglot/lineage.py — column-level lineage.
 *
 * API mapping (Python -> Kotlin):
 *  - `lineage(column, sql, ...)` (single column)  -> [lineage]
 *  - `lineage(None, sql, ...)` (all-columns dict) -> [lineageAll]
 *  - `to_node(...)`                               -> [toNode]
 *  - `Node` dataclass                             -> [Node] (payload + on_node hook kept)
 *  - `Node.to_html` / `GraphHTML`                 -> NOT ported (visualization is out of scope)
 *  - `exp.expand` (only what lineage needs: sources splicing with `source: <name>`
 *    comment tagging)                             -> [expand]
 *  - Python's `**kwargs` passthrough to qualify is not ported; callers needing custom
 *    qualification can pre-qualify and pass a [Scope] via the `scope` parameter.
 */

// sqlglot: errors.SqlglotError (lineage raises this; parent of the sqlglot error family)
class SqlglotError(message: String) : RuntimeException(message)

// sqlglot: lineage.Node
class Node(
    val name: String,
    val expression: Expression,
    val source: Expression,
    val downstream: MutableList<Node> = mutableListOf(),
    val sourceName: String = "",
    val referenceNodeName: String = "",
    // Caller-injected per-node data, populated via the `onNode` hook on lineage()
    val payload: MutableMap<String, Any?> = mutableMapOf(),
) {
    // sqlglot: Node.walk (DFS preorder, identity-deduped)
    fun walk(): Sequence<Node> = sequence {
        val visited = HashSet<IdentityKey>()
        val queue = ArrayDeque<Node>()
        queue.addLast(this@Node)
        while (queue.isNotEmpty()) {
            val node = queue.removeLast()
            val key = IdentityKey(node)
            if (key in visited) continue
            visited.add(key)
            yield(node)
            for (d in node.downstream.asReversed()) queue.addLast(d)
        }
    }
}

// sqlglot: lineage.to_node cache key (column, id(scope), scope_name, source_name,
// reference_node_name)
private data class LineageCacheKey(
    val column: Any,
    val scope: IdentityKey,
    val scopeName: String?,
    val sourceName: String?,
    val referenceNodeName: String?,
)

/**
 * sqlglot: lineage.lineage (single-column overload) — build the lineage graph for one
 * output column of a SQL query.
 *
 * @param column the output column name.
 * @param sql the SQL string or [Expression].
 * @param schema the schema of tables (nested map or [Schema]).
 * @param sources a mapping of table name to query (String or [Expression]) which will
 *   be spliced in (as subqueries tagged with a `source: <name>` comment) to continue
 *   building lineage upstream.
 * @param dialect the dialect of the input SQL.
 * @param scope a pre-created scope to use instead of qualifying + building one.
 * @param trimSelects whether to clean up selects by trimming to only relevant columns.
 * @param copy whether to copy Expression arguments.
 * @param onNode optional callback invoked for every [Node] created during the walk,
 *   after the node's downstream is populated.
 */
fun lineage(
    column: String,
    sql: Any,
    schema: Any? = null,
    sources: Map<String, Any>? = null,
    dialect: Dialect? = null,
    scope: Scope? = null,
    trimSelects: Boolean = true,
    copy: Boolean = true,
    onNode: ((Node) -> Unit)? = null,
): Node {
    val prep = prepareLineage(sql, schema, sources, dialect, scope, copy)

    val cache = HashMap<LineageCacheKey, Node>()
    val scopeMeta = HashMap<IdentityKey, Pair<Boolean, Map<String, Expression>>>()

    val columnName = normalizeIdentifiers(column, dialect = dialect).name
    if (prep.selectable.selects.none { it.aliasOrName == columnName }) {
        throw SqlglotError("Cannot find column '$columnName' in query.")
    }

    return toNode(
        columnName,
        prep.scope,
        prep.dialect,
        trimSelects = trimSelects,
        schema = prep.schema,
        cache = cache,
        scopeMeta = scopeMeta,
        onNode = onNode,
    )
}

/**
 * sqlglot: lineage.lineage with column=None (dict mode) — returns a map of every
 * top-level output column name to its lineage [Node], with a shared cache so
 * cross-column work is deduplicated.
 */
fun lineageAll(
    sql: Any,
    schema: Any? = null,
    sources: Map<String, Any>? = null,
    dialect: Dialect? = null,
    scope: Scope? = null,
    trimSelects: Boolean = true,
    copy: Boolean = true,
    onNode: ((Node) -> Unit)? = null,
): Map<String, Node> {
    val prep = prepareLineage(sql, schema, sources, dialect, scope, copy)

    val cache = HashMap<LineageCacheKey, Node>()
    val scopeMeta = HashMap<IdentityKey, Pair<Boolean, Map<String, Expression>>>()

    val result = LinkedHashMap<String, Node>()
    for (sel in prep.selectable.selects) {
        val name = sel.aliasOrName
        if (name.isEmpty()) {
            throw SqlglotError(
                "Cannot fetch lineage for unnamed projection: ${prep.dialect.generate(sel)}."
            )
        }
        result[name] = toNode(
            name,
            prep.scope,
            prep.dialect,
            trimSelects = trimSelects,
            schema = prep.schema,
            cache = cache,
            scopeMeta = scopeMeta,
            onNode = onNode,
        )
    }
    return result
}

private class PreparedLineage(
    val scope: Scope,
    val selectable: Expression,
    val dialect: Dialect,
    val schema: Schema,
)

// sqlglot: lineage.lineage lines 124-156 (maybe_parse + expand + ensure_schema +
// qualify + build_scope, shared by both modes)
private fun prepareLineage(
    sql: Any,
    schema: Any?,
    sources: Map<String, Any>?,
    dialect: Dialect?,
    scope: Scope?,
    copy: Boolean,
): PreparedLineage {
    val resolvedDialect = dialect ?: Dialects.BASE
    var expression = maybeParseSql(sql, resolvedDialect, copy)

    if (!sources.isNullOrEmpty()) {
        expression = expand(
            expression,
            sources.mapValues { (_, v) -> maybeParseSql(v, resolvedDialect, copy) },
            dialect = dialect,
            copy = copy,
        )
    }

    val ensuredSchema = ensureSchema(schema, dialect = dialect)

    var builtScope = scope
    if (builtScope == null) {
        expression = qualify(
            expression,
            dialect = dialect,
            schema = ensuredSchema,
            validateQualifyColumns = false,
            identify = false,
        )
        builtScope = buildScope(expression)
    }

    if (builtScope == null) {
        throw SqlglotError("Cannot build lineage, sql must be SELECT")
    }

    val selectable = builtScope.expression
    if (selectable !is Selectable) {
        throw SqlglotError("Cannot build lineage, sql must be a query")
    }

    return PreparedLineage(builtScope, selectable, resolvedDialect, ensuredSchema)
}

// sqlglot: exp.maybe_parse (the slice lineage needs: String parses, Expression is
// copied when copy=true)
private fun maybeParseSql(sql: Any, dialect: Dialect, copy: Boolean): Expression = when (sql) {
    is Expression -> if (copy) sql.copy() else sql
    is String -> dialect.parseOne(sql)
    else -> throw IllegalArgumentException("Invalid sql: $sql")
}

/**
 * sqlglot: exp.expand — transforms an expression by expanding all referenced sources
 * into subqueries, tagging each spliced subquery with a `source: <name>` comment.
 * Lineage-scoped port: the callable-source variant is not supported.
 */
fun expand(
    expression: Expression,
    sources: Map<String, Expression>,
    dialect: Dialect? = null,
    copy: Boolean = true,
): Expression {
    val normalizedSources = LinkedHashMap<String, Expression>()
    for ((k, v) in sources) normalizedSources[normalizeTableName(k, dialect = dialect)] = v

    fun expandFn(node: Expression): Expression {
        if (node is Table) {
            val name = normalizeTableName(node, dialect = dialect)
            val source = normalizedSources[name]

            if (source != null) {
                // Create a subquery with the same alias (or table name if no alias)
                val sub: Subquery = subquery(source.copy(), node.alias.ifEmpty { name })
                sub.comments = mutableListOf("source: $name")

                // Continue expanding within the subquery
                return sub.transform(copy = false) { expandFn(it) }
            }
        }
        return node
    }

    return expression.transform(copy = copy) { expandFn(it) }
}

// sqlglot: exp.normalize_table_name — case-normalized dotted table name without quotes
fun normalizeTableName(table: Any, dialect: Dialect? = null, copy: Boolean = true): String {
    val d = dialect ?: Dialects.BASE
    val tbl: Table = when (table) {
        is Table -> if (copy) table.copy() as Table else table
        is String ->
            d.parser().parseIntoTable(d.tokenize(table), table) as? Table
                ?: throw ParseError("Failed to parse '$table' into Table")
        else -> throw IllegalArgumentException("Invalid table: $table")
    }
    return normalizeIdentifiers(tbl, dialect = d).parts.joinToString(".") { it.name }
}

/**
 * sqlglot: lineage.to_node — the recursive step that builds the lineage [Node] for
 * `column` (an output column name, or an ordinal when descending through set
 * operations) within `scope`.
 */
fun toNode(
    column: Any, // String | Int (sqlglot: str | int)
    scope: Scope,
    dialect: Dialect,
    scopeName: String? = null,
    upstream: Node? = null,
    sourceName: String? = null,
    referenceNodeName: String? = null,
    trimSelects: Boolean = true,
    schema: Schema? = null,
    cache: MutableMap<*, *>? = null,
    scopeMeta: MutableMap<IdentityKey, Pair<Boolean, Map<String, Expression>>>? = null,
    onNode: ((Node) -> Unit)? = null,
): Node {
    @Suppress("UNCHECKED_CAST")
    val nodeCache = cache as? MutableMap<LineageCacheKey, Node>
    val cacheKey = LineageCacheKey(column, IdentityKey(scope), scopeName, sourceName, referenceNodeName)

    val cached = nodeCache?.get(cacheKey)
    if (cached != null) {
        upstream?.downstream?.add(cached)
        return cached
    }

    // Find the specific select clause that is the source of the column we want.
    // This can either be a specific, named select or a generic `*` clause.
    val selectable = scope.expression
    val select: Expression
    if (column is Int) {
        val selects = selectable.selects
        if (column >= selects.size) {
            throw SqlglotError(
                "Cannot find column's source with index $column in query: " +
                    dialect.generate(selectable)
            )
        }
        select = selects[column]
    } else {
        column as String
        select = if (scopeMeta == null) {
            selectable.selects.firstOrNull { it.aliasOrName == column }
                ?: (if (selectable.isStar) Star() else scope.expression)
        } else {
            // Resolving a column to its select scans selectable.selects on every call;
            // memoize a per-scope {name: select} map and is_star bit instead.
            val meta = scopeMeta.getOrPut(IdentityKey(scope)) {
                val selectByName = LinkedHashMap<String, Expression>()
                for (sel in selectable.selects) {
                    if (sel.aliasOrName !in selectByName) selectByName[sel.aliasOrName] = sel
                }
                selectable.isStar to selectByName
            }
            meta.second[column] ?: (if (meta.first) Star() else scope.expression)
        }
    }

    if (scope.expression is Subquery) {
        for (innerScope in scope.subqueryScopes) {
            val result = toNode(
                column,
                scope = innerScope,
                dialect = dialect,
                upstream = upstream,
                sourceName = sourceName,
                referenceNodeName = referenceNodeName,
                trimSelects = trimSelects,
                schema = schema,
                cache = nodeCache,
                scopeMeta = scopeMeta,
                onNode = onNode,
            )
            // Skip caching a passed-in upstream returned by an inner SetOp:
            // a sibling call at the same key with that node as its upstream
            // would otherwise self-loop on the cache hit.
            if (nodeCache != null && result !== upstream) {
                nodeCache[cacheKey] = result
            }
            return result
        }
    }
    if (scope.expression is SetOperation) {
        val name = scope.expression::class.simpleName!!.uppercase()
        val createdSetop = upstream == null
        val setopNode = upstream ?: Node(name = name, source = scope.expression, expression = select)

        val index = if (column is Int) {
            column
        } else {
            selectable.selects.indexOfFirst { it.aliasOrName == column || it.isStar }
        }

        if (index == -1) {
            throw IllegalArgumentException("Could not find $column in ${scope.expression}")
        }

        for (s in scope.unionScopes) {
            toNode(
                index,
                scope = s,
                dialect = dialect,
                upstream = setopNode,
                sourceName = sourceName,
                referenceNodeName = referenceNodeName,
                trimSelects = trimSelects,
                schema = schema,
                cache = nodeCache,
                scopeMeta = scopeMeta,
                onNode = onNode,
            )
        }

        if (nodeCache != null && createdSetop) {
            nodeCache[cacheKey] = setopNode
        }
        if (createdSetop) onNode?.invoke(setopNode)
        return setopNode
    }

    val source: Expression
    if (trimSelects && scope.expression is Select) {
        // For better ergonomics in our node labels, replace the full select with
        // a version that has only the column we care about.
        //   "x", SELECT x, y FROM foo
        //     => "x", SELECT x FROM foo
        // sqlglot: scope.expression.select(select, append=False) — the trimmed copy
        // adopts the original select instance, exactly like Python's list builder.
        val trimmed = scope.expression.copy()
        trimmed.set("expressions", mutableListOf<Any?>(select))
        source = trimmed
    } else {
        source = scope.expression
    }

    // Create the node for this step in the lineage chain, and attach it to the previous one.
    val node = Node(
        name = if (scopeName != null) "$scopeName.$column" else column.toString(),
        source = source,
        expression = select,
        sourceName = sourceName ?: "",
        referenceNodeName = referenceNodeName ?: "",
    )

    upstream?.downstream?.add(node)

    val subqueryScopes = HashMap<IdentityKey, Scope>()
    for (subqueryScope in scope.subqueryScopes) {
        subqueryScopes[IdentityKey(subqueryScope.expression)] = subqueryScope
    }

    // sqlglot: find_all_in_scope(select, *exp.UNWRAPPED_QUERIES)
    for (subquery in findAllInScope(select, Select::class, SetOperation::class)) {
        val subqueryScope = subqueryScopes[IdentityKey(subquery)]
            ?: continue // Python logs "Unknown subquery scope" and continues

        for (name in subquery.namedSelects) {
            toNode(
                name,
                scope = subqueryScope,
                dialect = dialect,
                upstream = node,
                trimSelects = trimSelects,
                schema = schema,
                cache = nodeCache,
                scopeMeta = scopeMeta,
                onNode = onNode,
            )
        }
    }

    // if the select is a star add all scope sources as downstreams
    if (select is Star) {
        for (src in scope.sources.values) {
            val srcExpr = if (src is Scope) src.expression else src as Expression
            val starNode = Node(
                name = sqlWithoutComments(select, dialect),
                source = srcExpr,
                expression = srcExpr,
            )
            node.downstream.add(starNode)
            onNode?.invoke(starNode)
        }
    }

    // Find all columns that went into creating this one to list their lineage nodes.
    // Python builds a set() here (structural equality dedupe); we keep first-seen
    // order which is deterministic (Python's is hash-order).
    val sourceColumns = LinkedHashSet<Column>()
    for (c in findAllInScope(select, Column::class)) sourceColumns.add(c as Column)

    // If the source is a UDTF find columns used in the UDTF to generate the table
    val derivedTables: List<Expression>
    if (source is UDTF) {
        for (c in source.findAll<Column>()) sourceColumns.add(c)
        derivedTables = scope.sources.values.mapNotNull { src ->
            if (src is Scope && src.isDerivedTable) src.expression.parent else null
        }
    } else {
        derivedTables = scope.derivedTables
    }

    val sourceNames = LinkedHashMap<String, String>()
    for (dt in derivedTables) {
        val comments = dt.comments
        if (!comments.isNullOrEmpty() && comments[0].startsWith("source: ")) {
            sourceNames[dt.alias] = comments[0].trim().split(WHITESPACE_RE)[1]
        }
    }

    val pivots = scope.pivots
    val pivot = if (pivots.size == 1) pivots[0] as? Pivot else null
    var pivotRenames: Map<String, String> = emptyMap()
    var pivotColumnMapping: Map<String, List<Column>> = emptyMap()

    if (pivot != null) {
        pivotRenames = pivotOutputRenames(pivot, scope, schema)
        pivotColumnMapping = pivotColumnMappingOf(pivot)
        if (pivotRenames.isNotEmpty()) {
            val remapped = LinkedHashMap<String, List<Column>>()
            for ((post, pre) in pivotRenames) {
                pivotColumnMapping[pre]?.let { remapped[post] = it }
            }
            pivotColumnMapping = remapped
        }
    }

    // Python rebinds the `reference_node_name` parameter inside this loop; the pivot
    // branch then reads whatever the last Scope-branch iteration left behind. Keep
    // that leak to stay 1:1.
    var refNodeName = referenceNodeName
    for (c in sourceColumns) {
        var table = c.table
        var colSource: Any? = scope.sources[table]

        if (colSource is Scope) {
            refNodeName = null
            if (colSource.scopeType == ScopeType.DERIVED_TABLE && table !in sourceNames) {
                refNodeName = table
            } else if (colSource.scopeType == ScopeType.CTE) {
                val selectedNode = scope.selectedSources[table]?.first
                refNodeName = selectedNode?.name
            }

            // The table itself came from a more specific scope. Recurse into that one
            // using the unaliased column name.
            toNode(
                c.name,
                scope = colSource,
                dialect = dialect,
                scopeName = table,
                upstream = node,
                sourceName = sourceNames[table].takeUnless { it.isNullOrEmpty() } ?: sourceName,
                referenceNodeName = refNodeName,
                trimSelects = trimSelects,
                schema = schema,
                cache = nodeCache,
                scopeMeta = scopeMeta,
                onNode = onNode,
            )
        } else if (pivot != null && pivot.aliasOrName == c.table) {
            val downstreamColumns = mutableListOf<Column>()

            val columnName = c.name
            val mapped = pivotColumnMapping[columnName]
            if (mapped != null) {
                downstreamColumns.addAll(mapped)
            } else {
                // The column is not in the pivot, so it must be an implicit column of the
                // pivoted source -- adapt column to be from the implicit pivoted source.
                val pivotParent = pivot.parent
                downstreamColumns.add(
                    column(
                        pivotRenames[c.name] ?: c.thisArg as Expression,
                        table = pivotParent?.aliasOrName,
                    ) as Column
                )
            }

            for (dc in downstreamColumns) {
                var downstreamColumn = dc
                if (downstreamColumn.table.isEmpty()) {
                    // Some dialects (e.g. bigquery) don't qualify the IN-list columns,
                    // but they can only come from the pivoted source
                    val pivotParent = pivot.parent
                    downstreamColumn = column(
                        downstreamColumn.thisArg as Expression,
                        table = pivotParent?.aliasOrName,
                    ) as Column
                }

                table = downstreamColumn.table
                colSource = scope.sources[table]
                if (colSource is Table && colSource.db.isEmpty()) {
                    // A pivoted CTE reference maps to the raw table in `scope.sources`,
                    // so recover the CTE's scope to keep tracing through it
                    colSource = scope.cteSources[colSource.name] ?: colSource
                }
                if (colSource is Scope) {
                    toNode(
                        downstreamColumn.name,
                        scope = colSource,
                        scopeName = table,
                        dialect = dialect,
                        upstream = node,
                        sourceName = sourceNames[table].takeUnless { it.isNullOrEmpty() }
                            ?: sourceName,
                        referenceNodeName = refNodeName,
                        trimSelects = trimSelects,
                        schema = schema,
                        cache = nodeCache,
                        scopeMeta = scopeMeta,
                        onNode = onNode,
                    )
                } else {
                    val colExpr = (colSource as? Expression) ?: Placeholder()
                    val pivotLeaf = Node(
                        name = sqlWithoutComments(downstreamColumn, dialect),
                        source = colExpr,
                        expression = colExpr,
                    )
                    node.downstream.add(pivotLeaf)
                    onNode?.invoke(pivotLeaf)
                }
            }
        } else {
            // The source is not a scope and the column is not in any pivot - we've reached
            // the end of the line. At this point, if a source is not found it means this
            // column's lineage is unknown. This can happen if the definition of a source
            // used in a query is not passed into the `sources` map.
            val colExpr = (colSource as? Expression) ?: Placeholder()
            val leaf = Node(name = sqlWithoutComments(c, dialect), source = colExpr, expression = colExpr)
            node.downstream.add(leaf)
            onNode?.invoke(leaf)
        }
    }

    nodeCache?.put(cacheKey, node)

    onNode?.invoke(node)

    return node
}

private val WHITESPACE_RE = Regex("\\s+")

// sqlglot: expression.sql(comments=False) — strip comments on a copy, then generate
private fun sqlWithoutComments(expression: Expression, dialect: Dialect): String {
    val copy = expression.copy()
    for (n in copy.walk()) n.comments = null
    return dialect.generate(copy, copy = false)
}

/**
 * sqlglot: lineage._pivot_output_renames — map each (UN)PIVOT output column name to its
 * pre-rename name, when an alias column list (`... AS t(c1, c2, ...)`) renames the
 * outputs. The renames are positional over the operator's full output, so they can
 * only be aligned when the pre-pivot columns are known: from the projections of a
 * derived table or CTE source, or from the schema for a physical table.
 */
private fun pivotOutputRenames(pivot: Pivot, scope: Scope, schema: Schema?): Map<String, String> {
    if (pivot.aliasColumnNames.isEmpty()) return emptyMap()

    val parent = pivot.parent
    var prePivotColumns: List<String> = emptyList()
    if (parent is DerivedTable && parent.thisArg is Query) {
        prePivotColumns = (parent.thisArg as Expression).namedSelects
    } else if (parent is Table) {
        val cteSource = if (parent.db.isEmpty()) scope.cteSources[parent.name] else null
        if (cteSource is Scope && cteSource.expression is Query) {
            prePivotColumns = cteSource.expression.namedSelects
        } else if (schema != null) {
            prePivotColumns = schema.columnNames(parent, onlyVisible = true)
        }
    }

    // The alignment is also unknowable when the source's projections aren't fully
    // expanded (e.g. an unresolved star), since the renames would silently shift
    if (prePivotColumns.isEmpty() || "*" in prePivotColumns) return emptyMap()

    return pivot.outputColumns(prePivotColumns)
}

// sqlglot: lineage._pivot_column_mapping — map each (UN)PIVOT output column name to the
// source columns it's derived from.
private fun pivotColumnMappingOf(pivot: Pivot): Map<String, List<Column>> {
    val mapping = LinkedHashMap<String, MutableList<Column>>()

    if (pivot.unpivot) {
        // UNPIVOT((v1, v2) FOR name IN ((a1, a2), (b1, b2))): each value column is derived
        // positionally from the IN-list entries, and the name column from all of them
        val valueColumns = pivot.expressionsArg
            .filterIsInstance<Expression>()
            .flatMap { it.findAll<Identifier>().toList() }
        for (valueColumn in valueColumns) {
            mapping[valueColumn.name] = mutableListOf()
        }

        for (field in pivot.fields) {
            if (field !is In) continue

            val nameColumns = mapping.getOrPut((field.thisArg as? Expression)?.name ?: "") {
                mutableListOf()
            }
            for (entry in field.expressionsArg) {
                if (entry !is Expression) continue
                val entryColumns = entry.findAll<Column>().toList()
                nameColumns.addAll(entryColumns)

                if (entryColumns.size == valueColumns.size) {
                    for ((valueColumn, col) in valueColumns.zip(entryColumns)) {
                        mapping.getValue(valueColumn.name).add(col)
                    }
                } else {
                    for (valueColumn in valueColumns) {
                        mapping.getValue(valueColumn.name).addAll(entryColumns)
                    }
                }
            }
        }

        return mapping
    }

    // For each aggregation function, the pivot creates a new column for each field in
    // category combined with the aggfunc; only the columns used in the aggregations are
    // of interest in the lineage. See lineage.py:566-574 for the full walkthrough.
    //
    // Example: PIVOT (SUM(value) AS value_sum, MAX(price)) FOR category IN ('a' AS cat_a, 'b')
    val pivotColumns = (pivot.args["columns"] as? List<*>)?.filterIsInstance<Expression>()
        ?: emptyList()
    val pivotAggsCount = pivot.expressionsArg.size

    val result = LinkedHashMap<String, MutableList<Column>>()
    for ((i, agg) in pivot.expressionsArg.withIndex()) {
        if (agg !is Expression) continue
        val aggCols = agg.findAll<Column>().toMutableList()
        var colIndex = i
        while (colIndex < pivotColumns.size) {
            result[pivotColumns[colIndex].name] = aggCols
            colIndex += pivotAggsCount
        }
    }
    return result
}
