package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Count
import dev.brikk.house.sql.ast.DDL
import dev.brikk.house.sql.ast.DML
import dev.brikk.house.sql.ast.DerivedTable
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Final
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Having
import dev.brikk.house.sql.ast.Hint
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.JoinHint
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TableColumn
import dev.brikk.house.sql.ast.UDTF
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.WithinGroup
import dev.brikk.house.sql.ast.aliasColumnNames
import dev.brikk.house.sql.ast.isStar
import dev.brikk.house.sql.ast.namedSelects
import kotlin.reflect.KClass

/**
 * Port of sqlglot/optimizer/scope.py — selection-scope analysis over a parsed AST.
 *
 * A scope's `sources` map name -> either a Table expression or another Scope; Python
 * types this as `exp.Table | Scope`, which Kotlin models as `Any` values (insertion
 * order preserved via LinkedHashMap, mirroring Python dict ordering).
 */

// sqlglot: errors.OptimizeError
class OptimizeError(message: String) : RuntimeException(message)

// sqlglot: scope.ScopeType
enum class ScopeType { ROOT, SUBQUERY, DERIVED_TABLE, CTE, UNION, UDTF }

// sqlglot: scope.TRAVERSABLES = (exp.Query, exp.DDL, exp.DML)
private fun isTraversable(expression: Expression): Boolean =
    expression is Query || expression is DDL || expression is DML

// sqlglot: scope.COLLECTIBLE_TYPES (Column, Dot, Table, Query, UDTF, CTE, Star,
// TableColumn, JoinHint)
private fun isCollectible(node: Expression): Boolean =
    node is Column || node is Dot || node is Table || node is Query || node is UDTF ||
        node is CTE || node is Star || node is TableColumn || node is JoinHint

/** Identity-keyed wrapper (Python uses `id(obj)`; Expression equality is structural). */
class IdentityKey(val value: Any) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value
    override fun hashCode(): Int = value.hashCode()
}

// sqlglot: scope.Scope
class Scope(
    var expression: Expression,
    sources: MutableMap<String, Any>? = null,
    outerColumns: List<String>? = null,
    var parent: Scope? = null,
    var scopeType: ScopeType = ScopeType.ROOT,
    lateralSources: MutableMap<String, Any>? = null,
    cteSources: MutableMap<String, Any>? = null,
    canBeCorrelated: Boolean? = null,
) {
    val sources: MutableMap<String, Any> = sources ?: LinkedHashMap()
    val lateralSources: MutableMap<String, Any> = lateralSources ?: LinkedHashMap()
    val cteSources: MutableMap<String, Any> = cteSources ?: LinkedHashMap()
    val outerColumns: List<String> = outerColumns ?: emptyList()

    val subqueryScopes: MutableList<Scope> = mutableListOf()
    val derivedTableScopes: MutableList<Scope> = mutableListOf()
    val tableScopes: MutableList<Scope> = mutableListOf()
    val cteScopes: MutableList<Scope> = mutableListOf()
    var unionScopes: MutableList<Scope> = mutableListOf()
    val udtfScopes: MutableList<Scope> = mutableListOf()
    val canBeCorrelated: Boolean = canBeCorrelated ?: false

    private var collected = false
    private var scansAllSubscopeColumnsFlag = false
    private var rawColumnsList: MutableList<Column> = mutableListOf()
    private var tableColumnsList: MutableList<TableColumn> = mutableListOf()
    private var starsList: MutableList<Expression> = mutableListOf()
    private var derivedTablesList: MutableList<Expression> = mutableListOf()
    private var udtfsList: MutableList<Expression> = mutableListOf()
    private var tablesList: MutableList<Table> = mutableListOf()
    private var ctesList: MutableList<CTE> = mutableListOf()
    private var subqueriesList: MutableList<Expression> = mutableListOf()
    private var joinHintsList: MutableList<JoinHint> = mutableListOf()
    private var semiAntiJoinTables: MutableSet<String> = mutableSetOf()
    private var columnIndexList: MutableList<Column> = mutableListOf()
    private var selectedSourcesCache: LinkedHashMap<String, Pair<Expression, Any>>? = null
    private var columnsCache: List<Column>? = null
    private var externalColumnsCache: List<Column>? = null
    private var localColumnsCache: List<Column>? = null
    private var pivotsCache: List<Expression>? = null
    private var referencesCache: MutableList<Pair<String, Expression>>? = null

    init {
        this.sources.putAll(this.lateralSources)
        this.sources.putAll(this.cteSources)
    }

    // sqlglot: Scope.clear_cache
    fun clearCache() {
        collected = false
        scansAllSubscopeColumnsFlag = false
        rawColumnsList = mutableListOf()
        tableColumnsList = mutableListOf()
        starsList = mutableListOf()
        derivedTablesList = mutableListOf()
        udtfsList = mutableListOf()
        tablesList = mutableListOf()
        ctesList = mutableListOf()
        subqueriesList = mutableListOf()
        joinHintsList = mutableListOf()
        semiAntiJoinTables = mutableSetOf()
        columnIndexList = mutableListOf()
        selectedSourcesCache = null
        columnsCache = null
        externalColumnsCache = null
        localColumnsCache = null
        pivotsCache = null
        referencesCache = null
    }

    // sqlglot: Scope.branch
    fun branch(
        expression: Expression,
        scopeType: ScopeType,
        sources: MutableMap<String, Any>? = null,
        cteSources: Map<String, Any>? = null,
        lateralSources: MutableMap<String, Any>? = null,
        outerColumns: List<String>? = null,
    ): Scope =
        Scope(
            expression = expression.unnest(),
            sources = sources?.let { LinkedHashMap(it) },
            parent = this,
            scopeType = scopeType,
            cteSources = LinkedHashMap(this.cteSources).apply { cteSources?.let { putAll(it) } },
            lateralSources = lateralSources?.let { LinkedHashMap(it) },
            canBeCorrelated = this.canBeCorrelated ||
                scopeType == ScopeType.SUBQUERY || scopeType == ScopeType.UDTF,
            outerColumns = outerColumns,
        )

    // sqlglot: Scope._collect (classification per COLLECTIBLE_TYPES)
    private fun collect() {
        tablesList = mutableListOf()
        ctesList = mutableListOf()
        subqueriesList = mutableListOf()
        derivedTablesList = mutableListOf()
        udtfsList = mutableListOf()
        rawColumnsList = mutableListOf()
        tableColumnsList = mutableListOf()
        starsList = mutableListOf()
        joinHintsList = mutableListOf()
        semiAntiJoinTables = mutableSetOf()
        columnIndexList = mutableListOf()

        for (node in walk()) {
            // sqlglot: COLLECTIBLE_TYPES gate (Column, Dot, Table, Query, UDTF, CTE, Star,
            // TableColumn, JoinHint)
            if (node === expression || !isCollectible(node)) continue

            when {
                node is Dot && node.isStar -> starsList.add(node)
                node::class == Column::class -> {
                    // sqlglot: type(node) is exp.Column (excludes subclasses)
                    val column = node as Column
                    columnIndexList.add(column)
                    if (column.thisArg is Star) starsList.add(column) else rawColumnsList.add(column)
                }
                node is Table && node.parent !is JoinHint -> {
                    val parent = node.parent
                    if (parent is Join && parent.isSemiOrAntiJoin) {
                        semiAntiJoinTables.add(node.aliasOrName)
                    }
                    tablesList.add(node)
                }
                node is JoinHint -> joinHintsList.add(node)
                node::class == Lateral::class ||
                    (node is UDTF && (node.parent is From || node.parent is Join)) ->
                    udtfsList.add(node)
                node is CTE -> ctesList.add(node)
                isDerivedTableNode(node) && isFromOrJoin(node) -> derivedTablesList.add(node)
                // sqlglot: exp.UNWRAPPED_QUERIES = (Select, SetOperation)
                (node is Select || node is SetOperation) && !isFromOrJoin(node) ->
                    subqueriesList.add(node)
                node is TableColumn -> tableColumnsList.add(node)
                // sqlglot: ROW_LEVEL_AGG_FUNCS = (exp.Count,)
                node is Star &&
                    (truthy(node.args["except_"]) || node.parent !is Count) ->
                    scansAllSubscopeColumnsFlag = true
            }
        }

        collected = true
    }

    private fun ensureCollected() {
        if (!collected) collect()
    }

    // sqlglot: Scope.walk
    fun walk(prune: ((Expression) -> Boolean)? = null): Sequence<Expression> =
        walkInScope(expression, prune)

    // sqlglot: Scope.find
    fun find(vararg types: KClass<out Expression>): Expression? = findInScope(expression, *types)

    // sqlglot: Scope.find_all
    fun findAll(vararg types: KClass<out Expression>): Sequence<Expression> =
        findAllInScope(expression, *types)

    // sqlglot: Scope.replace
    fun replace(old: Expression, new: Expression) {
        old.replace(new)
        clearCache()
    }

    // sqlglot: Scope.tables
    val tables: List<Table> get() { ensureCollected(); return tablesList }

    // sqlglot: Scope.ctes
    val ctes: List<CTE> get() { ensureCollected(); return ctesList }

    // sqlglot: Scope.derived_tables
    val derivedTables: List<Expression> get() { ensureCollected(); return derivedTablesList }

    // sqlglot: Scope.udtfs
    val udtfs: List<Expression> get() { ensureCollected(); return udtfsList }

    // sqlglot: Scope.subqueries (Select | SetOperation nodes)
    val subqueries: List<Expression> get() { ensureCollected(); return subqueriesList }

    // sqlglot: Scope.scans_all_subscope_columns
    val scansAllSubscopeColumns: Boolean get() { ensureCollected(); return scansAllSubscopeColumnsFlag }

    // sqlglot: Scope.stars
    val stars: List<Expression> get() { ensureCollected(); return starsList }

    // sqlglot: Scope.column_index (Python keeps a set of `id(node)`; we keep the nodes and
    // expose an identity membership check)
    val columnIndex: List<Column> get() { ensureCollected(); return columnIndexList }

    fun inColumnIndex(node: Expression): Boolean = columnIndex.any { it === node }

    // sqlglot: Scope.columns
    val columns: List<Column>
        get() {
            var cached = columnsCache
            if (cached == null) {
                ensureCollected()
                val columns = rawColumnsList

                val externalColumns = mutableListOf<Column>()
                for (scope in subqueryScopes.asSequence() +
                    udtfScopes.asSequence() +
                    derivedTableScopes.asSequence().filter { it.canBeCorrelated }) {
                    externalColumns.addAll(scope.externalColumns)
                }

                val expr = expression
                val namedSelects: Set<String> =
                    if (expr is Query) expr.namedSelects.toSet() else emptySet()

                val result = mutableListOf<Column>()
                for (column in columns + externalColumns) {
                    val ancestor = column.findAncestor(
                        Select::class,
                        Qualify::class,
                        Order::class,
                        Having::class,
                        Hint::class,
                        Table::class,
                        Star::class,
                        Distinct::class,
                    )
                    if (
                        ancestor == null ||
                        column.text("table").isNotEmpty() ||
                        ancestor is Select ||
                        (ancestor is Table && ancestor.thisArg !is dev.brikk.house.sql.ast.Func) ||
                        (
                            (ancestor is Order || ancestor is Distinct) &&
                                (
                                    ancestor.parent is Window || ancestor.parent is WithinGroup ||
                                        ancestor.parent !is Select ||
                                        column.name !in namedSelects
                                    )
                            ) ||
                        (ancestor is Star && column.argKey != "except_")
                    ) {
                        result.add(column)
                    }
                }
                cached = result
                columnsCache = cached
            }
            return cached
        }

    // sqlglot: Scope.table_columns
    val tableColumns: List<TableColumn> get() { ensureCollected(); return tableColumnsList }

    // sqlglot: Scope.selected_sources
    val selectedSources: Map<String, Pair<Expression, Any>>
        get() {
            var cached = selectedSourcesCache
            if (cached == null) {
                ensureCollected()
                val result = LinkedHashMap<String, Pair<Expression, Any>>()

                for ((name, node) in references) {
                    if (name in semiAntiJoinTables) {
                        // The RHS table of SEMI/ANTI joins shouldn't be collected as a
                        // selected source
                        continue
                    }
                    if (name in result) throw OptimizeError("Alias already used: $name")
                    val source = sources[name]
                    if (source != null) result[name] = node to source
                }

                cached = result
                selectedSourcesCache = cached
            }
            return cached
        }

    // sqlglot: Scope.references
    val references: List<Pair<String, Expression>>
        get() {
            var cached = referencesCache
            if (cached == null) {
                cached = mutableListOf()

                for (table in tables) {
                    cached.add(table.aliasOrName to table)
                }
                for (expr in derivedTables + udtfs) {
                    val pivots = expr.args["pivots"]
                    cached.add(
                        getSourceAlias(expr) to
                            (if (truthy(pivots)) expr else expr.unnest())
                    )
                }

                referencesCache = cached
            }
            return cached
        }

    // sqlglot: Scope.external_columns
    val externalColumns: List<Column>
        get() {
            var cached = externalColumnsCache
            if (cached == null) {
                cached = if (expression is SetOperation) {
                    val left = unionScopes[0]
                    val right = unionScopes[1]
                    left.externalColumns + right.externalColumns
                } else {
                    val localSourceNames = references.map { it.first }.toSet()
                    columns.filter {
                        it.text("table") !in localSourceNames &&
                            it.text("table") !in semiOrAntiJoinTables
                    }
                }
                externalColumnsCache = cached
            }
            return cached
        }

    // sqlglot: Scope.local_columns (identity comparison, mirroring Python's id() set)
    val localColumns: List<Column>
        get() {
            var cached = localColumnsCache
            if (cached == null) {
                val external = externalColumns
                cached = columns.filter { c -> external.none { it === c } }
                localColumnsCache = cached
            }
            return cached
        }

    // sqlglot: Scope.unqualified_columns
    val unqualifiedColumns: List<Column> get() = columns.filter { it.text("table").isEmpty() }

    // sqlglot: Scope.join_hints
    val joinHints: List<JoinHint> get() { ensureCollected(); return joinHintsList }

    // sqlglot: Scope.pivots
    val pivots: List<Expression>
        get() {
            var cached = pivotsCache
            if (cached == null) {
                cached = references.flatMap { (_, node) ->
                    (node.args["pivots"] as? List<*>)?.filterIsInstance<Expression>()
                        ?: emptyList()
                }
                pivotsCache = cached
            }
            return cached
        }

    // sqlglot: Scope.semi_or_anti_join_tables
    val semiOrAntiJoinTables: Set<String> get() { ensureCollected(); return semiAntiJoinTables }

    // sqlglot: Scope.source_columns
    fun sourceColumns(sourceName: String): List<Column> =
        columns.filter { it.text("table") == sourceName }

    // sqlglot: Scope.is_subquery / is_derived_table / is_union / is_cte / is_root / is_udtf
    val isSubquery: Boolean get() = scopeType == ScopeType.SUBQUERY
    val isDerivedTable: Boolean get() = scopeType == ScopeType.DERIVED_TABLE
    val isUnion: Boolean get() = scopeType == ScopeType.UNION
    val isCte: Boolean get() = scopeType == ScopeType.CTE
    val isRoot: Boolean get() = scopeType == ScopeType.ROOT
    val isUdtf: Boolean get() = scopeType == ScopeType.UDTF

    // sqlglot: Scope.is_correlated_subquery
    val isCorrelatedSubquery: Boolean get() = canBeCorrelated && externalColumns.isNotEmpty()

    // sqlglot: Scope.rename_source
    fun renameSource(oldName: String?, newName: String) {
        val old = oldName ?: ""
        if (old in sources) {
            sources[newName] = sources.remove(old)!!
        }
    }

    // sqlglot: Scope.add_source
    fun addSource(name: String, source: Any) {
        sources[name] = source
        clearCache()
    }

    // sqlglot: Scope.remove_source
    fun removeSource(name: String) {
        sources.remove(name)
        clearCache()
    }

    // sqlglot: Scope.traverse (DFS post-order)
    fun traverse(): Sequence<Scope> {
        val stack = mutableListOf(this)
        val result = mutableListOf<Scope>()
        while (stack.isNotEmpty()) {
            val scope = stack.removeLast()
            result.add(scope)
            stack.addAll(scope.cteScopes)
            stack.addAll(scope.unionScopes)
            stack.addAll(scope.tableScopes)
            stack.addAll(scope.subqueryScopes)
        }
        return result.asReversed().asSequence()
    }

    // sqlglot: Scope.ref_count (Python maps id(source) -> count; keys here are identity
    // wrappers over the source objects)
    fun refCount(): Map<IdentityKey, Int> {
        val scopeRefCount = LinkedHashMap<IdentityKey, Int>()

        for (scope in traverse()) {
            for ((_, source) in scope.selectedSources.values) {
                val key = IdentityKey(source)
                scopeRefCount[key] = (scopeRefCount[key] ?: 0) + 1
            }
            for (name in scope.semiOrAntiJoinTables) {
                // semi/anti join sources are not actually selected but we still need to
                // increment their ref count to avoid them being optimized away
                val source = scope.sources[name] ?: continue
                val key = IdentityKey(source)
                scopeRefCount[key] = (scopeRefCount[key] ?: 0) + 1
            }
        }

        return scopeRefCount
    }

    override fun toString(): String = "Scope<$expression>"
}

// sqlglot: scope.traverse_scope
fun traverseScope(expression: Expression): List<Scope> {
    if (isTraversable(expression)) {
        val acc = mutableListOf<Scope>()
        traverseScopeInner(Scope(expression), acc)
        return acc
    }
    return emptyList()
}

// sqlglot: scope.build_scope
fun buildScope(expression: Expression): Scope? = traverseScope(expression).lastOrNull()

// sqlglot: scope._traverse_scope
private fun traverseScopeInner(scope: Scope, acc: MutableList<Scope>) {
    val expression = scope.expression

    when {
        expression is Select -> traverseSelect(scope, acc)
        expression is SetOperation -> {
            traverseCtes(scope, acc)
            traverseUnion(scope, acc)
            return
        }
        expression is Subquery -> {
            if (scope.isRoot) traverseSelect(scope, acc) else traverseSubqueries(scope, acc)
        }
        expression is Table -> traverseTables(scope, acc)
        expression is UDTF -> traverseUdtfs(scope, acc)
        expression is DDL -> {
            val ddlExpression = expression.args["expression"]
            if (ddlExpression is Expression && ddlExpression is Query) {
                traverseCtes(scope, acc)
                // Python passes scope.cte_sources by reference (not a copy)
                traverseScopeInner(Scope(ddlExpression, cteSources = scope.cteSources), acc)
            }
            return
        }
        expression is DML -> {
            traverseCtes(scope, acc)
            for (query in findAllInScope(expression)) {
                if (query !is Query) continue
                // This check ensures we don't yield the CTE/nested queries twice
                if (query.parent !is CTE && query.parent !is Subquery) {
                    traverseScopeInner(Scope(query, cteSources = scope.cteSources), acc)
                }
            }
            return
        }
        else -> return // sqlglot: logger.warning("Cannot traverse scope ...")
    }

    acc.add(scope)
}

// sqlglot: scope._traverse_select
private fun traverseSelect(scope: Scope, acc: MutableList<Scope>) {
    traverseCtes(scope, acc)
    traverseTables(scope, acc)
    traverseSubqueries(scope, acc)
}

// sqlglot: scope._traverse_union
private fun traverseUnion(scope: Scope, acc: MutableList<Scope>) {
    var prevScope: Scope? = null
    val unionScopeStack = mutableListOf(scope)

    val setOp = scope.expression as SetOperation
    val expressionStack = mutableListOf(setOp.right, setOp.left)

    // Mirrors Python's `scope` loop-variable reuse (falls back to the argument when the
    // inner traversal yields nothing).
    var currentScope: Scope = scope

    while (expressionStack.isNotEmpty()) {
        val expression = expressionStack.removeLast()
        val unionScope = unionScopeStack.last()

        val newScope = unionScope.branch(
            expression,
            outerColumns = unionScope.outerColumns,
            scopeType = ScopeType.UNION,
        )

        if (expression is SetOperation) {
            traverseCtes(newScope, acc)

            unionScopeStack.add(newScope)
            expressionStack.add(expression.right)
            expressionStack.add(expression.left)
            continue
        }

        val yielded = mutableListOf<Scope>()
        traverseScopeInner(newScope, yielded)
        for (s in yielded) {
            acc.add(s)
            currentScope = s
        }

        if (prevScope != null) {
            unionScopeStack.removeLast()
            unionScope.unionScopes = mutableListOf(prevScope, currentScope)
            prevScope = unionScope

            acc.add(unionScope)
        } else {
            prevScope = currentScope
        }
    }
}

// sqlglot: scope._traverse_ctes
private fun traverseCtes(scope: Scope, acc: MutableList<Scope>) {
    val sources = LinkedHashMap<String, Any>()

    for (cte in scope.ctes) {
        val cteName = cte.alias

        // if the scope is a recursive cte, it must be in the form of base_case UNION
        // recursive. thus the recursive scope is the first section of the union.
        val with_ = scope.expression.args["with_"]
        if (with_ is With && with_.recursive) {
            val union = cte.thisArg
            if (union is SetOperation) {
                sources[cteName] = scope.branch(union.left, scopeType = ScopeType.CTE)
            }
        }

        var childScope: Scope? = null

        val yielded = mutableListOf<Scope>()
        traverseScopeInner(
            scope.branch(
                cte.thisArg as Expression,
                cteSources = sources,
                outerColumns = cte.aliasColumnNames,
                scopeType = ScopeType.CTE,
            ),
            yielded,
        )
        for (s in yielded) {
            acc.add(s)
            childScope = s
        }

        // append the final child_scope yielded
        if (childScope != null) {
            sources[cteName] = childScope
            scope.cteScopes.add(childScope)
        }
    }

    scope.sources.putAll(sources)
    scope.cteSources.putAll(sources)
}

/**
 * sqlglot: scope._is_derived_table — (tbl1 JOIN tbl2) parses as a Subquery, but it's not
 * really a "derived table" unless it's aliased or wraps an unwrapped query.
 */
private fun isDerivedTableNode(expression: Expression): Boolean =
    expression is Subquery &&
        (
            expression.alias.isNotEmpty() ||
                expression.thisArg is Select || expression.thisArg is SetOperation
            )

// sqlglot: scope._is_from_or_join
private fun isFromOrJoin(expression: Expression): Boolean {
    var parent = expression.parent

    // Subqueries can be arbitrarily nested
    while (parent != null && parent::class == Subquery::class) {
        parent = parent.parent
    }

    return parent != null && (parent::class == From::class || parent::class == Join::class)
}

// sqlglot: scope._traverse_tables
private fun traverseTables(scope: Scope, acc: MutableList<Scope>) {
    val sources = LinkedHashMap<String, Any>()

    // Traverse FROMs, JOINs, and LATERALs in the order they are defined
    val expressions = mutableListOf<Expression>()
    val from_ = scope.expression.args["from_"]
    if (from_ is From) {
        expressions.add(from_.thisArg as Expression)
    }

    for (join in (scope.expression.args["joins"] as? List<*>).orEmpty()) {
        if (join is Expression) expressions.add(join.thisArg as Expression)
    }

    if (scope.expression is Table) {
        expressions.add(scope.expression)
    }

    for (lateral in (scope.expression.args["laterals"] as? List<*>).orEmpty()) {
        if (lateral is Expression) expressions.add(lateral)
    }

    // Python iterates the list while extending it; use an index-based loop.
    var i = 0
    while (i < expressions.size) {
        var expression = expressions[i]
        i++

        if (expression is Final) {
            expression = expression.thisArg as Expression
        }
        if (expression is Table) {
            val tableName = expression.name
            val sourceName = expression.aliasOrName

            if (tableName in scope.sources && expression.db.isEmpty()) {
                // This is a reference to a parent source (e.g. a CTE), not an actual
                // table, unless it is pivoted, because then we get back a new table and
                // hence a new source.
                val pivots = expression.args["pivots"] as? List<*>
                if (!pivots.isNullOrEmpty()) {
                    sources[(pivots[0] as Expression).alias] = expression
                } else {
                    sources[sourceName] = scope.sources.getValue(tableName)
                }
            } else if (sourceName in sources) {
                sources[findNewName(sources.keys, tableName)] = expression
            } else {
                sources[sourceName] = expression
            }

            // Make sure to not include the joins twice
            if (expression !== scope.expression) {
                for (join in (expression.args["joins"] as? List<*>).orEmpty()) {
                    if (join is Expression) expressions.add(join.thisArg as Expression)
                }
            }

            continue
        }

        if (expression !is DerivedTable) continue

        val node: Expression = expression

        val lateralSources: MutableMap<String, Any>?
        val scopeType: ScopeType
        val scopes: MutableList<Scope>
        if (expression is UDTF) {
            lateralSources = sources
            scopeType = ScopeType.UDTF
            scopes = scope.udtfScopes
        } else if (isDerivedTableNode(expression)) {
            lateralSources = null
            scopeType = ScopeType.DERIVED_TABLE
            scopes = scope.derivedTableScopes
            for (join in (node.args["joins"] as? List<*>).orEmpty()) {
                if (join is Expression) expressions.add(join.thisArg as Expression)
            }
        } else {
            // Makes sure we check for possible sources in nested table constructs
            expressions.add(node.thisArg as Expression)
            for (join in (node.args["joins"] as? List<*>).orEmpty()) {
                if (join is Expression) expressions.add(join.thisArg as Expression)
            }
            continue
        }

        var childScope: Scope? = null

        val yielded = mutableListOf<Scope>()
        traverseScopeInner(
            scope.branch(
                node,
                lateralSources = lateralSources,
                outerColumns = node.aliasColumnNames,
                scopeType = scopeType,
            ),
            yielded,
        )
        for (s in yielded) {
            acc.add(s)
            childScope = s

            // Tables without aliases will be set as ""
            // This shouldn't be a problem once qualify_columns runs, as it adds aliases on
            // everything. Until then, this means that only a single, unaliased derived
            // table is allowed (rather, the latest one wins).
            sources[getSourceAlias(node)] = s
        }

        // append the final child_scope yielded
        if (childScope != null) {
            scopes.add(childScope)
            scope.tableScopes.add(childScope)
        }
    }

    scope.sources.putAll(sources)
}

// sqlglot: scope._traverse_subqueries
private fun traverseSubqueries(scope: Scope, acc: MutableList<Scope>) {
    for (subquery in scope.subqueries) {
        var top: Scope? = null
        val yielded = mutableListOf<Scope>()
        traverseScopeInner(scope.branch(subquery, scopeType = ScopeType.SUBQUERY), yielded)
        for (s in yielded) {
            acc.add(s)
            top = s
        }
        if (top != null) scope.subqueryScopes.add(top)
    }
}

// sqlglot: scope._traverse_udtfs
private fun traverseUdtfs(scope: Scope, acc: MutableList<Scope>) {
    val expr = scope.expression
    val udtfExpressions: List<Any?> = when (expr) {
        is Unnest -> expr.expressionsArg
        is Lateral -> listOf(expr.thisArg)
        else -> emptyList()
    }

    val sources = LinkedHashMap<String, Any>()
    for (expression in udtfExpressions) {
        if (expression is Subquery) {
            var top: Scope? = null
            val yielded = mutableListOf<Scope>()
            traverseScopeInner(
                scope.branch(
                    expression,
                    scopeType = ScopeType.SUBQUERY,
                    outerColumns = expression.aliasColumnNames,
                ),
                yielded,
            )
            for (s in yielded) {
                acc.add(s)
                top = s
                sources[getSourceAlias(expression)] = s
            }

            if (top != null) scope.subqueryScopes.add(top)
        }
    }

    scope.sources.putAll(sources)
}

/**
 * sqlglot: scope.walk_in_scope — visits all nodes in the tree, stopping at nodes that
 * start child scopes.
 */
fun walkInScope(
    expression: Expression,
    prune: ((Expression) -> Boolean)? = null,
): Sequence<Expression> = sequence {
    val stack = ArrayDeque<Expression>()
    stack.addLast(expression)

    while (stack.isNotEmpty()) {
        val node = stack.removeLast()

        yield(node)

        // Only CTEs and Queries can start child scopes; checking that first lets all
        // other nodes (the vast majority) skip the rest of the boundary checks.
        if (
            node !== expression &&
            (node is CTE || node is Query) &&
            (
                node is CTE ||
                    ((node.parent is From || node.parent is Join) && isDerivedTableNode(node)) ||
                    node.parent is UDTF ||
                    node is Select || node is SetOperation
                )
        ) {
            if (node is Subquery || node is UDTF) {
                for (key in listOf("joins", "laterals", "pivots")) {
                    for (arg in (node.args[key] as? List<*>).orEmpty()) {
                        if (arg is Expression) yieldAll(walkInScope(arg))
                    }
                }
            }
            continue
        }

        if (prune != null && prune(node)) continue

        for (vs in node.args.values.reversed()) {
            if (vs is List<*>) {
                for (v in vs.asReversed()) {
                    if (v is Expression) stack.addLast(v)
                }
            } else if (vs is Expression) {
                stack.addLast(vs)
            }
        }
    }
}

// sqlglot: scope.find_all_in_scope
fun findAllInScope(
    expression: Expression,
    vararg expressionTypes: KClass<out Expression>,
): Sequence<Expression> =
    walkInScope(expression).filter { node ->
        expressionTypes.isEmpty() || expressionTypes.any { it.isInstance(node) }
    }

// sqlglot: scope.find_in_scope
fun findInScope(
    expression: Expression,
    vararg expressionTypes: KClass<out Expression>,
): Expression? = findAllInScope(expression, *expressionTypes).firstOrNull()

// sqlglot: helper.find_new_name
internal fun findNewName(taken: Collection<String>, base: String): String {
    if (base !in taken) return base

    var i = 2
    var new = "${base}_$i"
    while (new in taken) {
        i += 1
        new = "${base}_$i"
    }
    return new
}

// sqlglot: scope._get_source_alias
private fun getSourceAlias(expression: Expression): String {
    val aliasArg = expression.args["alias"]
    var aliasName = expression.alias

    if (aliasName.isEmpty() && aliasArg is TableAlias && aliasArg.columns.size == 1) {
        aliasName = (aliasArg.columns[0] as Expression).name
    }

    return aliasName
}

/** Python truthiness for arg values (None/False/""/empty list are falsy). */
private fun truthy(value: Any?): Boolean = when (value) {
    null, false, "" -> false
    is List<*> -> value.isNotEmpty()
    else -> true
}
