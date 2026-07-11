package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.PipeCall
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.MappingSchema
import dev.brikk.house.sql.optimizer.Node
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.optimizer.lineage
import dev.brikk.house.sql.optimizer.lineageAll
import dev.brikk.house.sql.optimizer.nestedSet
import dev.brikk.house.sql.optimizer.qualify
import kotlinx.serialization.Serializable

/**
 * BRIKK-NATIVE (no sqlglot counterpart): a single SQL statement as a composable value —
 * the "(input shape, body, output shape)" fragment from the North-star plan
 * (docs/parsing-research-and-plan.md). Wraps one parsed statement (pipe or standard
 * syntax) and exposes its contract surface: scalar parameters, table-valued slots,
 * source tables, output [Shape], column lineage, and a serializable description for the
 * compiler-plugin payload.
 *
 * Slot convention (from the plan doc): a table-valued input is written as a plain
 * TVF-style call in table position — `FROM source(args) |> ...` — i.e. an *unresolved*
 * function (parsed as [Anonymous]) whose name is NOT in the dialect's function registry.
 * Callers bind slots via [ShapeCatalog.slots]; conflict with real engine TVFs is the
 * caller's responsibility (names that collide with registry functions are not offered
 * as slots).
 */
class SqlFragment(val sql: String, val dialect: String = "") {

    private val dialectObj: Dialect by lazy { Dialects.forName(dialect) }

    private val statements: List<Expression> by lazy {
        dialectObj.parse(sql).filterNotNull()
    }

    /**
     * The parsed statement. Throws [ShapeError] unless the fragment holds exactly one
     * statement — fragments are single composable values by design.
     */
    val ast: Expression by lazy {
        when (statements.size) {
            1 -> statements[0]
            0 -> throw ShapeError("SqlFragment contains no statement: '$sql'")
            else -> throw ShapeError(
                "SqlFragment must contain exactly one statement, found " +
                    "${statements.size}. Split multi-statement SQL into one fragment per " +
                    "statement."
            )
        }
    }

    /** Whether the fragment is written in pipe (`|>`) syntax at the top level. */
    val isPipe: Boolean by lazy { ast is PipeQuery }

    /** The ordered pipe stage nodes (Pipe* classes); empty when [isPipe] is false. */
    val stages: List<Expression> by lazy {
        (ast as? PipeQuery)?.expressionsArg?.filterIsInstance<Expression>() ?: emptyList()
    }

    /**
     * Scalar parameters, grouped by (style, name) in first-occurrence order. Positions
     * are 0-based ordinals over ALL scalar parameter occurrences in the statement
     * (DFS preorder = textual order), so positional `?` binds line up with named ones.
     */
    val scalarParams: List<ScalarParam> by lazy {
        data class Key(val style: ParamStyle, val name: String?)

        val occurrences = LinkedHashMap<Key, MutableList<Int>>()
        var ordinal = 0
        for (node in ast.walk(bfs = false)) {
            val key = when {
                node is Parameter -> Key(ParamStyle.NAMED_AT, node.name.ifEmpty { null })
                node is Placeholder && node.text("this").isNotEmpty() ->
                    Key(ParamStyle.NAMED_COLON, node.text("this"))
                node is Placeholder -> Key(ParamStyle.POSITIONAL, null)
                else -> continue
            }
            occurrences.getOrPut(key) { mutableListOf() }.add(ordinal)
            ordinal++
        }
        occurrences.map { (key, positions) ->
            ScalarParam(
                name = key.name,
                style = key.style,
                count = positions.size,
                positions = positions,
            )
        }
    }

    /**
     * Function names in the dialect's registries (function builders + special
     * function-call and no-paren parsers); a TVF call in table position whose name is
     * here is a real engine function, not a slot.
     */
    private val knownFunctionNames: Set<String> by lazy {
        val p = dialectObj.parser()
        val fromParser = p.functions.keys + p.functionParsers.keys + p.noParenFunctionParsers.keys
        // When the dialect ships a full engine catalog (e.g. Doris: 800+ registered
        // functions vs the handful in the translation registries), slot detection becomes
        // engine-exact: any registered function name is a real function, not a slot.
        val catalog = dialectObj.functionCatalog ?: return@lazy fromParser
        // Catalog names are engine-native case (Doris uppercase, DuckDB/Trino lowercase);
        // the membership check uppercases, so normalize here.
        fromParser + catalog.functions.flatMap { def -> listOf(def.name) + def.aliases }
            .map { it.uppercase() }
    }

    /**
     * Table-valued slot names: unresolved ([Anonymous]) function calls in table
     * position (FROM/JOIN/pipe-head sources) whose names are not known functions of
     * the dialect. Names are reported as written; slot binding matches them
     * case-insensitively (SQL function-name semantics).
     *
     * `|> CALL tvf(...)` stages count too: on desugar the TVF lands in table position
     * with the pipe input as its first argument (see ast/PipeDesugar.kt), i.e. exactly
     * the slot-candidate shape — so an unknown CALLed function is offered as a slot
     * pre-desugar as well.
     */
    val tableSlots: List<String> by lazy {
        val out = LinkedHashSet<String>()
        for (node in ast.walk(bfs = false)) {
            val fn = when (node) {
                is Table -> node.thisArg as? Anonymous
                is PipeCall -> node.thisArg as? Anonymous
                else -> null
            } ?: continue
            val name = fn.name
            if (name.isNotEmpty() && name.uppercase() !in knownFunctionNames) out.add(name)
        }
        out.toList()
    }

    /**
     * Plain table references (fully-qualified, parts joined with '.') that the caller
     * may treat as inputs. CTE self-references defined inside the fragment are
     * excluded; slots are reported separately via [tableSlots].
     */
    val sourceTables: List<String> by lazy {
        val cteNames = ast.findAll(CTE::class).map { it.alias }.toSet()
        val out = LinkedHashSet<String>()
        // DFS arg order: deterministic, though the WITH clause is attached after the
        // Select body parses, so outer sources precede CTE-body sources.
        for (table in ast.findAll(Table::class, bfs = false)) {
            if (table.thisArg is Anonymous) continue
            val parts = (table as Table).parts.map { it.name }
            if (parts.isEmpty()) continue
            if (parts.size == 1 && parts[0] in cteNames) continue
            out.add(parts.joinToString("."))
        }
        out.toList()
    }

    /**
     * Resolves the fragment's output shape against [inputs]:
     *
     *  1. copy the AST; desugar pipe stages;
     *  2. rewrite bound slots (`FROM source(...)`) into plain table references named
     *     after the slot, so the resolver sees them (the slot's shape joins the schema
     *     under that name); an existing alias on the call is preserved;
     *  3. build a [MappingSchema] from the catalog (dialect-aware normalization);
     *  4. `qualify(validateQualifyColumns = false)` — unknown tables/columns do not
     *     explode, they simply stay unresolved (their types end up UNKNOWN and
     *     unexpandable stars survive as a single "*" column);
     *  5. `annotateTypes` under the same schema;
     *  6. read the outermost SELECT: names from the qualified output aliases, types
     *     from the annotated nodes rendered as base-dialect SQL ("UNKNOWN" when
     *     unresolved). For set operations the left-most branch names the output.
     *
     * [ColumnShape.nullable] is left null for now — the annotator's nonnull metadata
     * exists but is not surfaced yet (future work).
     */
    fun outputShape(inputs: ShapeCatalog = ShapeCatalog.EMPTY): Shape {
        val prepared = prepareTree(inputs)
        val schema = buildSchema(inputs)
        val qualified = qualify(
            prepared,
            dialect = dialectObj,
            schema = schema,
            validateQualifyColumns = false,
        )
        val annotated = annotateTypes(qualified, schema = schema, dialect = dialectObj)
        val selects = outermostSelect(annotated).selects.filterIsInstance<Expression>()
        return Shape(
            selects.map { sel ->
                ColumnShape(name = sel.aliasOrName, type = renderType(sel.type))
            }
        )
    }

    /**
     * Column-level dependencies — a thin wrapper over lineage()/lineageAll(): output
     * column name -> set of leaf source names. Leaves that resolve to physical tables
     * (or bound slots — rewritten to tables named after the slot) contribute their
     * fully-qualified table name; unresolvable leaves (lineage Placeholder dead-ends)
     * contribute the leaf's own name.
     *
     * Pass [column] to restrict the walk to a single output column. All output
     * projections must be named (lineage constraint) — bind a catalog to expand stars.
     */
    fun columnDependencies(
        column: String? = null,
        inputs: ShapeCatalog = ShapeCatalog.EMPTY,
    ): Map<String, Set<String>> {
        val prepared = prepareTree(inputs)
        val schema = buildSchema(inputs)
        val nodes: Map<String, Node> = if (column == null) {
            lineageAll(prepared, schema = schema, dialect = dialectObj)
        } else {
            mapOf(column to lineage(column, prepared, schema = schema, dialect = dialectObj))
        }
        return nodes.mapValues { (_, node) ->
            node.walk()
                .filter { it.downstream.isEmpty() }
                .map { leaf -> leafName(leaf) }
                .toSet()
        }
    }

    /**
     * Renders the fragment as standard (non-pipe) SQL in the [target] dialect. When
     * [expandStars] is true and [inputs] is provided, the tree is qualified against the
     * catalog first (bound slots included) so `*` becomes explicit columns — for
     * engines without star/star-except support. Qualification keeps identifiers
     * unquoted for readability.
     */
    fun toStandardSql(
        target: String = dialect,
        inputs: ShapeCatalog? = null,
        expandStars: Boolean = false,
    ): String {
        var tree = desugarPipes(ast, copy = true)
        if (expandStars && inputs != null) {
            tree = bindSlots(tree, inputs)
            tree = expandStarModifiers(tree, buildSchema(inputs), dialectObj)
        }
        return Dialects.forName(target).generate(tree)
    }

    /**
     * Serializable roll-up of the fragment's statically-known surface — the seed of
     * the compiler-plugin payload. [FragmentDescription.stageOperators] derives from
     * the stage node classes ("Pipe" prefix stripped, uppercased: WHERE, AGGREGATE,
     * ORDERBY, SETOPERATION, ...).
     */
    fun describe(): FragmentDescription = FragmentDescription(
        dialect = dialect,
        isPipe = isPipe,
        stageOperators = stages.map { it::class.simpleName!!.removePrefix("Pipe").uppercase() },
        scalarParams = scalarParams,
        tableSlots = tableSlots,
        sourceTables = sourceTables,
    )

    /**
     * The fragment's full contract under [inputs]: inputs used (source tables then
     * slots), resolved output shape, and column dependencies (empty when lineage
     * cannot run, e.g. unexpandable star projections).
     */
    fun contract(inputs: ShapeCatalog = ShapeCatalog.EMPTY): FragmentContract =
        FragmentContract(
            inputsUsed = sourceTables + tableSlots,
            output = outputShape(inputs),
            dependencies = try {
                columnDependencies(inputs = inputs)
            } catch (e: Exception) {
                emptyMap()
            },
        )

    // ------------------------------------------------------------------ internals

    /** Copy + desugar pipes + rewrite bound slots into plain table references. */
    private fun prepareTree(inputs: ShapeCatalog): Expression {
        var tree = ast.copy()
        if (isPipe) tree = desugarPipes(tree, copy = false)
        return bindSlots(tree, inputs)
    }

    /**
     * Rewrites every slot call bound in [inputs] — `Table(this=Anonymous(name))` —
     * into `Table(this=Identifier(slotName))`, preserving any explicit alias (an
     * unaliased slot is referenced by the slot name itself, exactly like a plain
     * table). Unknown slot bindings raise [ShapeError].
     */
    private fun bindSlots(tree: Expression, inputs: ShapeCatalog): Expression {
        if (inputs.slots.isEmpty()) return tree

        val available = tableSlots.associateBy { it.uppercase() }
        for (slotName in inputs.slots.keys) {
            if (slotName.uppercase() !in available) {
                throw ShapeError(
                    "Unknown slot binding '$slotName': fragment declares " +
                        (if (tableSlots.isEmpty()) "no slots" else "slots $tableSlots") + "."
                )
            }
        }

        val slotsByUpper = inputs.slots.keys.associateBy { it.uppercase() }
        tree.transform(copy = false) { node ->
            if (node is Table) {
                val fn = node.thisArg as? Anonymous
                val slotKey = fn?.name?.uppercase()?.let { slotsByUpper[it] }
                if (slotKey != null) {
                    node.set("this", Identifier(args("this" to slotKey, "quoted" to false)))
                }
            }
            node
        }
        return tree
    }

    /**
     * Builds a dialect-aware [MappingSchema] from the catalog. Dotted table names nest
     * (db.table / catalog.db.table); all entries — slots included — must share one
     * nesting depth (MappingSchema constraint). Slot shapes join under the slot name.
     */
    private fun buildSchema(inputs: ShapeCatalog): MappingSchema {
        val mapping = LinkedHashMap<String, Any?>()
        for ((tableName, shape) in inputs.tables) {
            nestedSet(mapping, tableName.split("."), shape.toSchemaMapping())
        }
        for ((slotName, shape) in inputs.slots) {
            nestedSet(mapping, listOf(slotName), shape.toSchemaMapping())
        }
        return MappingSchema(schema = mapping, dialect = dialectObj)
    }

    /** The SELECT whose projections name the output (left-most branch for set ops). */
    private fun outermostSelect(tree: Expression): Select {
        var node: Expression = tree
        while (true) {
            node = when (node) {
                is Select -> return node
                is SetOperation -> node.thisArg as Expression
                is Subquery -> node.thisArg as Expression
                else -> throw ShapeError(
                    "Cannot derive an output shape from a '${node.key}' node."
                )
            }
        }
    }

    private fun renderType(type: Expression?): String {
        val dataType = type as? DataType ?: return "UNKNOWN"
        return Dialects.BASE.generate(dataType)
    }

    private fun leafName(leaf: Node): String {
        val source = leaf.source
        return if (source is Table) {
            source.parts.joinToString(".") { it.name }
        } else {
            leaf.name
        }
    }
}

/** How a scalar parameter is written in the SQL text. */
@Serializable
enum class ParamStyle {
    /** `:name` — [Placeholder] with a name. */
    NAMED_COLON,

    /** `@name` — [Parameter]. */
    NAMED_AT,

    /** `?` — anonymous [Placeholder]. */
    POSITIONAL,
}

/**
 * One scalar parameter of a fragment. [positions] are 0-based ordinals over all scalar
 * parameter occurrences in the statement (walk order); [count] == positions.size.
 */
@Serializable
data class ScalarParam(
    val name: String? = null,
    val style: ParamStyle,
    val count: Int,
    val positions: List<Int>,
)

/** Serializable, statically-derivable summary of a fragment (compiler-plugin seed). */
@Serializable
data class FragmentDescription(
    val dialect: String,
    val isPipe: Boolean,
    val stageOperators: List<String>,
    val scalarParams: List<ScalarParam>,
    val tableSlots: List<String>,
    val sourceTables: List<String>,
)

/** A fragment's resolved contract under a specific [ShapeCatalog]. */
@Serializable
data class FragmentContract(
    val inputsUsed: List<String>,
    val output: Shape,
    val dependencies: Map<String, Set<String>>,
)

/**
 * Any-engine post-pass for the star EXCEPT/REPLACE/RENAME modifiers (BigQuery-isms) the
 * pipe SET/DROP/RENAME desugars produce (see ast/PipeDesugar.kt): qualifies [tree]
 * against [schema] so qualify()'s star expansion resolves every star — modifiers
 * included — to an explicit column list, valid on engines with no star-modifier
 * support (e.g. MySQL). [tree] is qualified in place; pass a disposable copy.
 *
 * Validation (googlesql spec: "Each referenced column must exist exactly once in the
 * input table", docs/pipe-syntax.md ~664 SET / ~763 RENAME): once a star with
 * REPLACE/RENAME modifiers has been expanded, the target column must have resolved —
 * a RENAME of an unknown column (no `old AS new` projection materialized) or a
 * SET/REPLACE of an unknown column throws [ShapeError]. Modifiers on stars whose
 * source has no schema entry survive unexpanded and are not validated (same lenient
 * posture as `validateQualifyColumns = false`).
 */
fun expandStarModifiers(
    tree: Expression,
    schema: Any?,
    dialect: Dialect = Dialects.BASE,
): Expression {
    fun renamePairs(root: Expression): Set<Pair<String, String>> =
        root.findAll(Star::class)
            .flatMap { star ->
                ((star.args["rename"] as? List<*>) ?: emptyList<Any?>())
                    .filterIsInstance<Alias>()
                    .map { (it.thisArg as Expression).name.lowercase() to it.alias.lowercase() }
            }
            .toSet()

    fun replaceTargets(root: Expression): Set<String> =
        root.findAll(Star::class)
            .flatMap { star ->
                ((star.args["replace"] as? List<*>) ?: emptyList<Any?>())
                    .filterIsInstance<Alias>()
                    .map { it.alias.lowercase() }
            }
            .toSet()

    val requestedRenames = renamePairs(tree)
    val requestedReplaces = replaceTargets(tree)

    val qualified = qualify(
        tree,
        dialect = dialect,
        schema = schema,
        validateQualifyColumns = false,
        quoteIdentifiers = false,
    )

    // Modifiers still attached to surviving stars could not be resolved (no schema for
    // their source) — exempt from validation.
    val unresolvedRenames = renamePairs(qualified)
    val unresolvedReplaces = replaceTargets(qualified)

    val outputAliases =
        qualified.findAll(Alias::class).map { it.alias.lowercase() }.toSet()

    for ((old, new) in requestedRenames - unresolvedRenames) {
        if (new !in outputAliases) {
            throw ShapeError(
                "Cannot RENAME unknown column '$old': it does not exist in the " +
                    "resolved input columns."
            )
        }
    }
    for (target in requestedReplaces - unresolvedReplaces) {
        if (target !in outputAliases) {
            throw ShapeError(
                "Cannot SET/REPLACE unknown column '$target': it does not exist in " +
                    "the resolved input columns."
            )
        }
    }

    return qualified
}
