package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.AggFunc
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Binary
import dev.brikk.house.sql.ast.Case
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.Coalesce
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Connector
import dev.brikk.house.sql.ast.DPipe
import dev.brikk.house.sql.ast.Exists
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.Is
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TryCast
import dev.brikk.house.sql.ast.Unary
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.ast.sqlName
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.NullPropagation

/**
 * BRIKK-NATIVE (no sqlglot counterpart): a conservative, tri-state nullability inference
 * pass. sqlglot performs NO nullability inference of its own — its `nonnull` type meta
 * covers only a handful of parity cases; this pass is entirely ours.
 *
 * It runs AFTER `qualify` + `annotateTypes` (so columns carry a resolved source table /
 * slot name and function nodes are typed), and derives, per expression, whether the value
 * it produces can be NULL:
 *
 *  - `true`   — provably nullable;
 *  - `false`  — provably not-null;
 *  - absent   — unknown (no evidence). We never guess: when a rule cannot conclude, the
 *    node simply has no entry.
 *
 * STORAGE — SIDECAR, NOT NODE META (gate-forced):
 *   AnnotateTypesCorpusTest compares our `Serde.dump` of the annotated tree against
 *   Python's dumps with EXACT equality (`expected != actual`), stripping only "o"
 *   comments and the parser POSITION keys (line/col/start/end) from each node's "m" map —
 *   every OTHER meta key is kept and compared. Writing a nullability marker into node meta
 *   would therefore surface as an extra "m" key on our side and fail the gate for every
 *   affected case. So results live in an [IdentityHashMap] keyed by node identity, wrapped
 *   in [NullabilityResult], and never touch the AST.
 */

/** Result of [annotateNullability]: a sidecar mapping from AST node -> tri-state nullability. */
class NullabilityResult internal constructor(
    private val map: java.util.IdentityHashMap<Expression, Boolean>,
) {
    /** Tri-state: true (nullable) / false (not null) / null (unknown). */
    fun nullableOf(expression: Expression): Boolean? = map[expression]
}

/**
 * Computes tri-state nullability for every node in [expression], returning a sidecar.
 *
 * [inputs] supplies declared column nullability (via each [ShapeCatalog] table/slot
 * [dev.brikk.house.sql.shape.ColumnShape.nullable]); [dialect] supplies the function
 * catalog (semantic profiles + function kinds) and identifier normalization. Both are
 * optional — with neither, only literals and structural operators can be concluded.
 *
 * SCOPE-AWARE (mirrors annotate_types' scope walk): scopes are processed in dependency
 * order (leaf scopes first, [traverseScope]). Within a scope, nodes are evaluated
 * bottom-up; a column ref resolves through [Scope.sources] — a physical [Table] source
 * yields its declared [ShapeCatalog] nullability, while a nested [Scope] source (CTE /
 * derived table / the pipe-desugar CTE chain) yields the previously-computed nullability
 * of that scope's matching output projection. This is why declared nullability survives a
 * pipe fragment's `WITH __tmpN AS (...)` rewrite.
 */
fun annotateNullability(
    expression: Expression,
    inputs: ShapeCatalog = ShapeCatalog.EMPTY,
    dialect: Dialect = Dialects.BASE,
): NullabilityResult {
    val pass = NullabilityAnnotator(inputs, dialect)
    val scopes = try {
        traverseScope(expression)
    } catch (_: Exception) {
        emptyList()
    }
    if (scopes.isEmpty()) {
        // Non-traversable expression (a bare scalar, etc.): evaluate it directly.
        pass.visitTree(expression, scope = null)
    } else {
        for (scope in scopes) pass.visitScope(scope)
    }
    return NullabilityResult(pass.map)
}

private class NullabilityAnnotator(
    private val inputs: ShapeCatalog,
    private val dialect: Dialect,
) {
    val map = java.util.IdentityHashMap<Expression, Boolean>()

    // Per-scope output-column nullability: scope identity -> {aliasOrName -> verdict}.
    // Populated as each scope is finished so downstream scopes can resolve column refs
    // into upstream (CTE/derived-table) projections.
    private val scopeOutputs =
        java.util.IdentityHashMap<Scope, Map<String, Boolean>>()

    private var currentScope: Scope? = null

    private fun get(e: Expression?): Boolean? = e?.let { map[it] }
    private fun put(e: Expression, v: Boolean?) {
        if (v != null) map[e] = v
    }

    /** Evaluates one scope's own expressions, then records its output-column verdicts. */
    fun visitScope(scope: Scope) {
        currentScope = scope
        // scope.walk() stays within this scope (prunes at subscope boundaries), so
        // nested-scope nodes were already handled when their own scope was processed.
        val nodes = scope.walk().toList()
        for (node in nodes.asReversed()) visit(node)

        val outputs = LinkedHashMap<String, Boolean>()
        for (sel in scope.expression.selects) {
            get(sel)?.let { outputs[sel.aliasOrName] = it }
        }
        scopeOutputs[scope] = outputs
        currentScope = null
    }

    /** Evaluates a standalone expression tree with no scope context. */
    fun visitTree(expression: Expression, scope: Scope?) {
        currentScope = scope
        for (node in expression.walk(bfs = false).toList().asReversed()) visit(node)
        currentScope = null
    }

    fun visit(node: Expression) {
        val verdict: Boolean? = when (node) {
            // --- literals ---
            is Null -> true
            is Literal -> false

            // --- alias / paren are pass-through wrappers ---
            is Alias -> get(node.thisArg as? Expression)
            is Paren -> get(node.thisArg as? Expression)

            // --- IS NULL / IS NOT NULL / EXISTS: boolean predicate, never NULL ---
            is Is -> false
            is Exists -> false

            // --- column references ---
            is Column -> columnNullability(node)

            // --- CAST / TRY_CAST ---
            // TryCast must precede Cast (TryCast is a subclass of Cast).
            is TryCast -> true // TRY_CAST yields NULL on conversion failure.
            is Cast -> get(node.thisArg as? Expression)

            // --- COALESCE / IFNULL / NVL ---
            is Coalesce -> coalesceNullability(node)

            // --- CASE ---
            is Case -> caseNullability(node)

            // --- string concat (`||`) is null-propagating like other binaries ---
            is DPipe -> operatorRule(operands(node))

            // --- AND / OR: three-valued logic (see connectorRule) ---
            is Connector -> connectorRule(node)

            // --- function calls (catalog profile + aggregate override) ---
            is Func -> functionNullability(node)

            // --- generic null-propagating operators (arithmetic, comparison, unary) ---
            is Binary -> operatorRule(operands(node))
            is Unary -> operatorRule(operands(node))

            // --- anything else: unknown ---
            else -> null
        }
        put(node, verdict)
    }

    /** Immediate operand expressions of a node, in arg order. */
    private fun operands(node: Expression): List<Expression> =
        node.iterExpressions().toList()

    /**
     * Null-propagating operator rule: any operand nullable -> nullable; all operands
     * not-null -> not-null; otherwise unknown.
     */
    private fun operatorRule(args: List<Expression>): Boolean? {
        if (args.isEmpty()) return null
        val verdicts = args.map { get(it) }
        return when {
            verdicts.any { it == true } -> true
            verdicts.all { it == false } -> false
            else -> null
        }
    }

    /**
     * AND / OR three-valued approximation: SQL boolean connectors can produce NULL, but
     * `FALSE AND NULL` = FALSE and `TRUE OR NULL` = TRUE regardless of the NULL operand.
     * We approximate conservatively: not-null only when BOTH operands are provably
     * not-null (their nulls, if any, cannot leak through). This under-promises for the
     * short-circuit cases (e.g. we do not prove `FALSE AND x` is not-null since we do not
     * track boolean values), which is the safe direction. Otherwise unknown.
     */
    private fun connectorRule(node: Connector): Boolean? {
        val l = get(node.left)
        val r = get(node.right)
        return if (l == false && r == false) false else null
    }

    /** COALESCE: not-null if ANY arg not-null; nullable if ALL args nullable; else unknown. */
    private fun coalesceNullability(node: Coalesce): Boolean? {
        val args = operands(node)
        if (args.isEmpty()) return null
        val verdicts = args.map { get(it) }
        return when {
            verdicts.any { it == false } -> false
            verdicts.all { it == true } -> true
            else -> null
        }
    }

    /**
     * CASE: OR over branch results (each `WHEN ... THEN result` + the ELSE). A missing
     * ELSE contributes an implicit NULL branch, so a CASE without ELSE is nullable.
     */
    private fun caseNullability(node: Case): Boolean? {
        val results = mutableListOf<Expression?>()
        val ifs = (node.args["ifs"] as? List<*>)?.filterIsInstance<Expression>() ?: emptyList()
        for (branch in ifs) results.add(branch.args["true"] as? Expression)
        val default = node.args["default"] as? Expression
        if (default == null) return true // missing ELSE -> implicit NULL branch.
        results.add(default)

        val verdicts = results.map { it?.let { e -> get(e) } }
        return when {
            verdicts.any { it == true } -> true
            verdicts.all { it == false } -> false
            else -> null
        }
    }

    /**
     * Column ref: resolve its source through the current [Scope].
     *
     *  - source is a physical [Table]: its declared [ShapeCatalog] nullability;
     *  - source is a nested [Scope] (CTE / derived table / pipe-desugar CTE): the
     *    previously-computed verdict of that scope's matching output projection;
     *  - otherwise (no scope context, or unresolved source): fall back to matching the
     *    column's textual `table` against the catalog directly.
     */
    private fun columnNullability(column: Column): Boolean? {
        val table = column.text("table")
        if (table.isEmpty()) return null

        val scope = currentScope
        if (scope != null) {
            var s: Scope? = scope
            while (s != null) {
                val source = s.sources[table]
                when (source) {
                    is Table -> return declaredNullability(table, column.name)
                    is Scope -> {
                        val d = Dialects.forName(dialect.name)
                        val wanted = normalizeName(column.name, dialect = d).name
                        val outputs = scopeOutputs[source] ?: return null
                        for ((colName, verdict) in outputs) {
                            if (normalizeName(colName, dialect = d).name == wanted) return verdict
                        }
                        return null
                    }
                    else -> s = s.parent
                }
            }
        }

        // No scope resolution — match the textual table name against the catalog.
        return declaredNullability(table, column.name)
    }

    /**
     * Declared nullability of [columnName] from the input source named [sourceName] (a
     * table or slot name — qualify reduces a dotted table like `db.t` to its final part
     * `t`). Tables match on their final dotted segment; slots match by name. Matching
     * uses the dialect's identifier normalization; the verdict is present only when the
     * caller declared [dev.brikk.house.sql.shape.ColumnShape.nullable].
     */
    private fun declaredNullability(sourceName: String, columnName: String): Boolean? {
        val d = Dialects.forName(dialect.name)
        val wanted = normalizeName(sourceName, dialect = d).name
        for ((tableName, shape) in inputs.tables) {
            val lastPart = tableName.substringAfterLast(".")
            if (normalizeName(lastPart, dialect = d).name == wanted) {
                return shape.byName(columnName, dialect = dialect.name)?.nullable
            }
        }
        for ((slotName, shape) in inputs.slots) {
            if (normalizeName(slotName, dialect = d).name == wanted) {
                return shape.byName(columnName, dialect = dialect.name)?.nullable
            }
        }
        return null
    }

    /**
     * Function-call nullability via the dialect's function catalog:
     *
     *  - AGGREGATE override (checked FIRST): an aggregate returns NULL over an empty group,
     *    so nullable = true — EXCEPT COUNT, which returns 0 (not-null). Aggregate detection
     *    prefers the catalog's [FunctionKind.AGGREGATE]; when no catalog entry exists it
     *    falls back to our [AggFunc] AST marker.
     *  - otherwise, the semantic profile's [NullPropagation]:
     *      STRICT           -> operator rule over the function's argument expressions;
     *      ALWAYS_NULLABLE  -> true;
     *      NEVER_NULL       -> false;
     *      UNKNOWN / none / no catalog -> unknown.
     */
    private fun functionNullability(node: Func): Boolean? {
        val name = node.sqlName()
        val catalog = dialect.functionCatalog
        val def = catalog?.get(name)

        // Aggregate override. Prefer the catalog kind; fall back to the AST marker.
        val isAggregate = def?.let { it.kind == FunctionKind.AGGREGATE }
            ?: (node is AggFunc)
        if (isAggregate) {
            // COUNT never returns NULL (0 over an empty group); every other aggregate can.
            return if (name.equals("COUNT", ignoreCase = true)) false else true
        }

        val propagation = def?.profile?.nullPropagation ?: return null
        return when (propagation) {
            NullPropagation.STRICT -> operatorRule(operands(node as Expression))
            NullPropagation.ALWAYS_NULLABLE -> true
            NullPropagation.NEVER_NULL -> false
            NullPropagation.SKIPS_NULLS -> null
            NullPropagation.UNKNOWN -> null
        }
    }
}
