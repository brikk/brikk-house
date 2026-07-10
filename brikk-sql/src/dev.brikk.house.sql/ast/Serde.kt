package dev.brikk.house.sql.ast

import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Name -> (Python module, factory) registry for the node subset. The Python module is
 * needed because sqlglot's serde qualifies class names with their defining module
 * (serde.py: `if node.__class__.__module__ != exp.__name__`), and since sqlglot split
 * expressions.py into submodules EVERY class gets fully qualified, e.g.
 * "sqlglot.expressions.core.Column".
 */
object ExpressionRegistry {

    class Entry(val module: String, val factory: () -> Expression)

    private const val CORE = "sqlglot.expressions.core"
    private const val QUERY = "sqlglot.expressions.query"
    private const val DATATYPES = "sqlglot.expressions.datatypes"
    private const val FUNCTIONS = "sqlglot.expressions.functions"
    private const val AGGREGATE = "sqlglot.expressions.aggregate"
    private const val DDL = "sqlglot.expressions.ddl"

    // Handwritten node registrations; the generated catalog is merged in below
    // (GeneratedRegistry.kt registers every class not listed here).
    private val handwritten: Map<String, Entry> = mapOf(
        "Identifier" to Entry(CORE) { Identifier() },
        "Column" to Entry(CORE) { Column() },
        "Dot" to Entry(CORE) { Dot() },
        "Star" to Entry(CORE) { Star() },
        "Literal" to Entry(CORE) { Literal() },
        "Boolean" to Entry(CORE) { Boolean() },
        "Null" to Entry(CORE) { Null() },
        "Alias" to Entry(CORE) { Alias() },
        "Parameter" to Entry(CORE) { Parameter() },
        "Placeholder" to Entry(CORE) { Placeholder() },
        "Not" to Entry(CORE) { Not() },
        "Paren" to Entry(CORE) { Paren() },
        "Neg" to Entry(CORE) { Neg() },
        "And" to Entry(CORE) { And() },
        "Or" to Entry(CORE) { Or() },
        "Xor" to Entry(CORE) { Xor() },
        "EQ" to Entry(CORE) { EQ() },
        "NEQ" to Entry(CORE) { NEQ() },
        "NullSafeEQ" to Entry(CORE) { NullSafeEQ() },
        "GT" to Entry(CORE) { GT() },
        "GTE" to Entry(CORE) { GTE() },
        "LT" to Entry(CORE) { LT() },
        "LTE" to Entry(CORE) { LTE() },
        "Is" to Entry(CORE) { Is() },
        "Like" to Entry(CORE) { Like() },
        "ILike" to Entry(CORE) { ILike() },
        "In" to Entry(CORE) { In() },
        "Between" to Entry(CORE) { Between() },
        "Add" to Entry(CORE) { Add() },
        "Sub" to Entry(CORE) { Sub() },
        "Mul" to Entry(CORE) { Mul() },
        "Div" to Entry(CORE) { Div() },
        "Mod" to Entry(CORE) { Mod() },
        "Pow" to Entry(CORE) { Pow() },
        "IntDiv" to Entry(CORE) { IntDiv() },
        "PropertyEQ" to Entry(CORE) { PropertyEQ() },
        "NullSafeNEQ" to Entry(CORE) { NullSafeNEQ() },
        "BitwiseAnd" to Entry(CORE) { BitwiseAnd() },
        "BitwiseOr" to Entry(CORE) { BitwiseOr() },
        "BitwiseXor" to Entry(CORE) { BitwiseXor() },
        "BitwiseNot" to Entry(CORE) { BitwiseNot() },
        "DPipe" to Entry(CORE) { DPipe() },
        "Escape" to Entry(CORE) { Escape() },
        "Aliases" to Entry(CORE) { Aliases() },
        "Any" to Entry(CORE) { Any() },
        "All" to Entry(CORE) { All() },
        "Var" to Entry(CORE) { Var() },
        "Ordered" to Entry(CORE) { Ordered() },
        "Distinct" to Entry(CORE) { Distinct() },
        "Anonymous" to Entry(CORE) { Anonymous() },
        "TableAlias" to Entry(QUERY) { TableAlias() },
        "Table" to Entry(QUERY) { Table() },
        "Select" to Entry(QUERY) { Select() },
        "From" to Entry(QUERY) { From() },
        "Where" to Entry(QUERY) { Where() },
        "Having" to Entry(QUERY) { Having() },
        "Group" to Entry(QUERY) { Group() },
        "Order" to Entry(QUERY) { Order() },
        "Limit" to Entry(QUERY) { Limit() },
        "Offset" to Entry(QUERY) { Offset() },
        "Join" to Entry(QUERY) { Join() },
        "Subquery" to Entry(QUERY) { Subquery() },
        "CTE" to Entry(QUERY) { CTE() },
        "With" to Entry(QUERY) { With() },
        "Union" to Entry(QUERY) { Union() },
        "Except" to Entry(QUERY) { Except() },
        "Intersect" to Entry(QUERY) { Intersect() },
        "Tuple" to Entry(QUERY) { Tuple() },
        "Window" to Entry(QUERY) { Window() },
        "Semicolon" to Entry(QUERY) { Semicolon() },
        "DataType" to Entry(DATATYPES) { DataType() },
        "DataTypeParam" to Entry(DATATYPES) { DataTypeParam() },
        "Cast" to Entry(FUNCTIONS) { Cast() },
        "Case" to Entry(FUNCTIONS) { Case() },
        "If" to Entry(FUNCTIONS) { If() },
        "Exists" to Entry(FUNCTIONS) { Exists() },
        "Collate" to Entry(FUNCTIONS) { Collate() },
        "ConnectByRoot" to Entry(FUNCTIONS) { ConnectByRoot() },
        "Count" to Entry(AGGREGATE) { Count() },
        "Sum" to Entry(AGGREGATE) { Sum() },
        "Min" to Entry(AGGREGATE) { Min() },
        "Max" to Entry(AGGREGATE) { Max() },
        "Avg" to Entry(AGGREGATE) { Avg() },
        "RowNumber" to Entry(AGGREGATE) { RowNumber() },
        "Command" to Entry(DDL) { Command() },
    )

    val entries: Map<String, Entry> = LinkedHashMap<String, Entry>(1100).also { m ->
        m.putAll(handwritten)
        registerAllGenerated(m)
        // NATIVE section: brikk-original nodes with no Python counterpart (module
        // "brikk.pipes"); see PipeNodes.kt.
        registerNativePipeNodes(m)
    }

    fun newInstance(simpleName: String): Expression =
        (entries[simpleName] ?: error("Unregistered expression class: $simpleName")).factory()

    fun qualifiedName(simpleName: String): String =
        (entries[simpleName] ?: error("Unregistered expression class: $simpleName")).module + "." + simpleName
}

/**
 * JSON dump/load matching reference/sqlglot/sqlglot/serde.py payload-for-payload.
 *
 * Format rules (all from serde.py):
 *  - dump(expr) is a flat JSON array of payload objects in DFS preorder; children are
 *    visited in args-insertion order, list items in list order.
 *  - payload keys: "i" parent payload index, "k" arg key, "a" true for list items,
 *    "c" class name, "t" nested dump of the node's type, "o" comments, "m" meta,
 *    "v" scalar value.
 *  - Expression payloads carry "c" = fully qualified Python class name; "t" only when
 *    node.type is set and is not the node itself (this makes Cast dump its `to` target
 *    twice: once under "t" and once as the "to" child); "o" only when comments is
 *    non-empty; "m" whenever meta was materialized (even if empty).
 *  - DType enum values become {"c": "DataType.Type", "v": <value>}.
 *  - everything else is a scalar: {"i", "k", ["a"], "v": string|bool|number|null}.
 *  - null args and empty list args are skipped entirely; null items INSIDE lists are
 *    kept (dumped as {"v": null}); false scalars are kept ({"v": false}).
 */
object Serde {

    private const val INDEX = "i"
    private const val ARG_KEY = "k"
    private const val IS_ARRAY = "a"
    private const val CLASS = "c"
    private const val TYPE = "t"
    private const val COMMENTS = "o"
    private const val META = "m"
    private const val VALUE = "v"
    private const val DATA_TYPE = "DataType.Type"

    private class StackVal(val node: kotlin.Any?, val index: Int?, val argKey: String?, val isArray: kotlin.Boolean)

    // sqlglot: serde.dump
    fun dump(expression: Expression): JsonArray {
        var i = 0
        val payloads = mutableListOf<JsonObject>()
        val stack = ArrayDeque<StackVal>()
        stack.addLast(StackVal(expression, null, null, false))

        while (stack.isNotEmpty()) {
            val sv = stack.removeLast()
            val node = sv.node

            val payload = LinkedHashMap<String, JsonElement>()
            if (sv.index != null) payload[INDEX] = JsonPrimitive(sv.index)
            if (sv.argKey != null) payload[ARG_KEY] = JsonPrimitive(sv.argKey)
            if (sv.isArray) payload[IS_ARRAY] = JsonPrimitive(true)

            when {
                node is Expression -> {
                    payload[CLASS] = JsonPrimitive(ExpressionRegistry.qualifiedName(node::class.simpleName!!))

                    val type = node.type
                    if (type != null && type !== node) payload[TYPE] = dump(type)
                    val comments = node.comments
                    if (!comments.isNullOrEmpty()) payload[COMMENTS] = JsonArray(comments.map { JsonPrimitive(it) })
                    val meta = node.metaOrNull
                    if (meta != null) payload[META] = JsonObject(meta.mapValues { (_, v) -> scalarToJson(v) })

                    for ((k, vs) in node.args.entries.reversed()) {
                        if (vs is List<*>) {
                            for (v in vs.reversed()) stack.addLast(StackVal(v, i, k, true))
                        } else if (vs != null) {
                            stack.addLast(StackVal(vs, i, k, false))
                        }
                    }
                }
                node is DType -> {
                    payload[CLASS] = JsonPrimitive(DATA_TYPE)
                    payload[VALUE] = JsonPrimitive(node.value)
                }
                else -> payload[VALUE] = scalarToJson(node)
            }

            payloads.add(JsonObject(payload))
            i += 1
        }

        return JsonArray(payloads)
    }

    // sqlglot: serde.load
    fun load(payloads: JsonArray?): kotlin.Any? {
        if (payloads == null || payloads.isEmpty()) return null

        val root = loadPayload(payloads[0].jsonObject)
        val nodes = mutableListOf<kotlin.Any?>(root)

        for (element in payloads.drop(1)) {
            val payload = element.jsonObject
            val node: kotlin.Any? = if (CLASS in payload) loadPayload(payload) else jsonToScalar(payload.getValue(VALUE))

            nodes.add(node)
            val parent = nodes[payload.getValue(INDEX).jsonPrimitive.int] as Expression
            val argKey = payload.getValue(ARG_KEY).jsonPrimitive.content

            if (payload[IS_ARRAY]?.jsonPrimitive?.booleanOrNull == true) {
                parent.append(argKey, node)
            } else {
                parent.set(argKey, node)
            }
        }

        return root
    }

    /** Typed convenience for the common case of loading a dumped Expression tree. */
    fun loadExpression(payloads: JsonArray): Expression = load(payloads) as Expression

    // sqlglot: serde._load
    private fun loadPayload(payload: JsonObject): kotlin.Any {
        val className = payload.getValue(CLASS).jsonPrimitive.content

        if (className == DATA_TYPE) {
            return DType.fromValue(payload.getValue(VALUE).jsonPrimitive.content)
        }

        val expression = ExpressionRegistry.newInstance(className.substringAfterLast('.'))
        expression.typeSlot = payload[TYPE]?.let { load(it.jsonArray) as Expression? }
        expression.comments = (payload[COMMENTS] as? JsonArray)
            ?.map { it.jsonPrimitive.content }
            ?.toMutableList()
        expression.metaOrNull = (payload[META] as? JsonObject)
            ?.entries
            ?.associateTo(LinkedHashMap()) { (k, v) -> k to jsonToScalar(v) }
        return expression
    }

    /**
     * Comparison mode: strips "o" (comments) and "m" (meta) from every payload,
     * recursing into nested "t" dumps. Position parity comes with the parser work,
     * so oracle comparisons run over stripped dumps on both sides.
     */
    fun stripMetaAndComments(payloads: JsonArray): JsonArray = JsonArray(
        payloads.map { element ->
            val payload = element.jsonObject
            JsonObject(
                payload.entries
                    .filter { it.key != COMMENTS && it.key != META }
                    .associate { (k, v) -> k to if (k == TYPE) stripMetaAndComments(v.jsonArray) else v }
            )
        }
    )

    private fun scalarToJson(v: kotlin.Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is kotlin.Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        else -> error("Unsupported scalar in expression args/meta: ${v::class} ($v)")
    }

    /**
     * JSON scalar -> Kotlin. Integral numbers become Long (canonical integer type in
     * args; Expression equality normalizes Int to Long so round-trips stay equal).
     */
    private fun jsonToScalar(element: JsonElement): kotlin.Any? {
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        if (primitive.isString) return primitive.content
        primitive.booleanOrNull?.let { return it }
        primitive.longOrNull?.let { return it }
        primitive.doubleOrNull?.let { return it }
        return primitive.content
    }
}
