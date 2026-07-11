package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError

/**
 * Port of sqlglot/schema.py — database schema abstractions used by the optimizer
 * (and, next, annotate_types).
 *
 * Python's nested `dict[str, t.Any]` schema mappings map to `Map<String, Any?>` here:
 * inner nodes are nested maps, leaves are column types (String or [DataType]).
 * `exp.Table | str` / `exp.Column | str` parameters map to `Any`.
 */

// sqlglot: errors.SchemaError
class SchemaError(message: String) : RuntimeException(message)

// sqlglot: exp.TABLE_PARTS
val TABLE_PARTS: List<String> = listOf("this", "db", "catalog")

// sqlglot: core.SAFE_IDENTIFIER_RE
private val SAFE_IDENTIFIER_RE = Regex("^[_a-zA-Z][a-zA-Z0-9_]*$")

// sqlglot: schema.Schema (abstract base class for database schemas)
interface Schema {
    // sqlglot: Schema.dialect
    val dialect: Dialect? get() = null

    // sqlglot: Schema.add_table
    fun addTable(
        table: Any,
        columnMapping: Any? = null,
        dialect: Dialect? = null,
        normalize: Boolean? = null,
        matchDepth: Boolean = true,
    )

    // sqlglot: Schema.column_names
    fun columnNames(
        table: Any,
        onlyVisible: Boolean = false,
        dialect: Dialect? = null,
        normalize: Boolean? = null,
    ): List<String>

    // sqlglot: Schema.get_column_type
    fun getColumnType(
        table: Any,
        column: Any,
        dialect: Dialect? = null,
        normalize: Boolean? = null,
    ): DataType

    // sqlglot: Schema.has_column
    fun hasColumn(
        table: Any,
        column: Any,
        dialect: Dialect? = null,
        normalize: Boolean? = null,
    ): Boolean {
        val name = if (column is String) column else (column as Expression).name
        return name in columnNames(table, dialect = dialect, normalize = normalize)
    }

    // sqlglot: Schema.get_udf_type (returns UNKNOWN by default)
    fun getUdfType(udf: Any, dialect: Dialect? = null, normalize: Boolean? = null): DataType =
        DataType(args("this" to DType.UNKNOWN))

    // sqlglot: Schema.supported_table_args
    val supportedTableArgs: List<String>

    // sqlglot: Schema.empty
    val empty: Boolean get() = true
}

// sqlglot: schema.AbstractMappingSchema (nested mapping + trie over reversed table parts)
open class AbstractMappingSchema(
    mapping: MutableMap<String, Any?>? = null,
    udfMapping: MutableMap<String, Any?>? = null,
) {
    var mapping: MutableMap<String, Any?> = LinkedHashMap()
        protected set
    var mappingTrie: MutableMap<Any, Any?> = LinkedHashMap()
        protected set

    // TODO(udf): the UDF mapping is stored (and trie-indexed) for parity with Python's
    // AbstractMappingSchema, but find_udf/get_udf_type consumers haven't been ported yet
    // (nothing consumes them until annotate_types lands UDF support).
    var udfMapping: MutableMap<String, Any?> = LinkedHashMap()
        protected set
    var udfTrie: MutableMap<Any, Any?> = LinkedHashMap()
        protected set

    private var supportedTableArgsCache: List<String> = emptyList()

    init {
        setMappings(mapping ?: LinkedHashMap(), udfMapping ?: LinkedHashMap())
    }

    protected fun setMappings(
        mapping: MutableMap<String, Any?>,
        udfMapping: MutableMap<String, Any?>,
    ) {
        this.mapping = mapping
        this.mappingTrie = newTrie(flattenSchema(mapping, depth = depth()).map { it.reversed() })
        this.udfMapping = udfMapping
        this.udfTrie = newTrie(flattenSchema(udfMapping, depth = udfDepth()).map { it.reversed() })
        supportedTableArgsCache = emptyList()
    }

    // sqlglot: AbstractMappingSchema.empty
    open val empty: Boolean get() = mapping.isEmpty()

    // sqlglot: AbstractMappingSchema.depth
    open fun depth(): Int = dictDepth(mapping)

    // sqlglot: AbstractMappingSchema.udf_depth
    fun udfDepth(): Int = dictDepth(udfMapping)

    // sqlglot: AbstractMappingSchema.supported_table_args
    open val supportedTableArgs: List<String>
        get() {
            if (supportedTableArgsCache.isEmpty() && mapping.isNotEmpty()) {
                val depth = depth()

                supportedTableArgsCache = when {
                    depth == 0 -> emptyList()
                    depth in 1..3 -> TABLE_PARTS.take(depth)
                    else -> throw SchemaError("Invalid mapping shape. Depth: $depth")
                }
            }
            return supportedTableArgsCache
        }

    // sqlglot: AbstractMappingSchema.table_parts
    fun tableParts(table: Table): List<String> = table.parts.asReversed().map { it.name }

    // sqlglot: AbstractMappingSchema._find_in_trie
    protected fun findInTrie(
        parts: MutableList<String>,
        trie: Map<Any, Any?>,
        raiseOnMissing: Boolean,
    ): List<String>? {
        val (value, subtrie) = inTrie(trie, parts)

        if (value == TrieResult.FAILED) return null

        if (value == TrieResult.PREFIX) {
            val possibilities = flattenSchema(subtrie)

            if (possibilities.size == 1) {
                parts.addAll(possibilities[0])
            } else {
                if (raiseOnMissing) {
                    val joinedParts = parts.joinToString(".")
                    val message = possibilities.joinToString(", ") { it.joinToString(".") }
                    throw SchemaError("Ambiguous mapping for $joinedParts: $message.")
                }
                return null
            }
        }

        return parts
    }

    // sqlglot: AbstractMappingSchema.find
    open fun find(
        table: Table,
        raiseOnMissing: Boolean = true,
        ensureDataTypes: Boolean = false,
    ): Any? {
        val parts = tableParts(table).take(supportedTableArgs.size).toMutableList()
        val resolvedParts = findInTrie(parts, mappingTrie, raiseOnMissing) ?: return null
        return nestedGetParts(resolvedParts, raiseOnMissing = raiseOnMissing)
    }

    // sqlglot: AbstractMappingSchema.nested_get
    fun nestedGetParts(
        parts: List<String>,
        d: Map<String, Any?>? = null,
        raiseOnMissing: Boolean = true,
    ): Any? =
        nestedGet(
            d ?: mapping,
            supportedTableArgs.zip(parts.asReversed()),
            raiseOnMissing = raiseOnMissing,
        )
}

/**
 * sqlglot: schema.MappingSchema — schema based on a nested mapping:
 *   1. {table: {col: type}}
 *   2. {db: {table: {col: type}}}
 *   3. {catalog: {db: {table: {col: type}}}}
 *
 * Types are `String` (parsed lazily via the dialect) or [DataType].
 */
class MappingSchema(
    schema: Map<String, Any?>? = null,
    visible: MutableMap<String, Any?>? = null,
    dialect: Dialect? = null,
    val normalize: Boolean = true,
    udfMapping: Map<String, Any?>? = null,
) : AbstractMappingSchema(), Schema {

    val visible: MutableMap<String, Any?> = visible ?: LinkedHashMap()

    // sqlglot: Dialect.get_or_raise(dialect)
    private val schemaDialect: Dialect = dialect ?: Dialects.BASE

    // sqlglot: MappingSchema.dialect
    override val dialect: Dialect get() = schemaDialect

    // sqlglot: MappingSchema._type_mapping_cache (the Python normalized-table/name/find
    // caches are pure perf and are not ported)
    private val typeMappingCache = HashMap<String, DataType>()

    // sqlglot: MappingSchema._normalized_name_cache. This is not just perf: the cache
    // hit skips normalize_name's `meta["is_table"]` side effect on the AST node, so
    // only the FIRST occurrence of a given name gets the meta key — visible in
    // annotated serde dumps and therefore ported.
    private val normalizedNameCache = HashMap<List<Any?>, String>()

    private var cachedDepth = 0

    init {
        val rawSchema = deepMutable(schema ?: emptyMap())
        val rawUdfs = deepMutable(udfMapping ?: emptyMap())
        setMappings(
            if (normalize) normalizeMapping(rawSchema) else rawSchema,
            // TODO(udf): Python normalizes the UDF mapping (_normalize_udfs); ours is
            // stored raw until a consumer (annotate_types UDF typing) is ported.
            rawUdfs,
        )
    }

    override val empty: Boolean get() = super<AbstractMappingSchema>.empty

    override val supportedTableArgs: List<String>
        get() = super<AbstractMappingSchema>.supportedTableArgs

    // sqlglot: MappingSchema.from_mapping_schema
    companion object {
        fun fromMappingSchema(mappingSchema: MappingSchema): MappingSchema =
            MappingSchema(
                schema = mappingSchema.mapping,
                visible = mappingSchema.visible,
                dialect = mappingSchema.dialect,
                normalize = mappingSchema.normalize,
                udfMapping = mappingSchema.udfMapping,
            )
    }

    // sqlglot: MappingSchema.find
    override fun find(table: Table, raiseOnMissing: Boolean, ensureDataTypes: Boolean): Any? {
        var schema = super.find(table, raiseOnMissing, false)
        if (ensureDataTypes && schema is Map<*, *>) {
            val converted = LinkedHashMap<Any?, Any?>()
            for ((col, dtype) in schema) {
                converted[col] = if (dtype is String) toDataType(dtype) else dtype
            }
            schema = converted
        }
        return schema
    }

    // sqlglot: MappingSchema.copy
    fun copy(
        schema: Map<String, Any?>? = null,
        visible: MutableMap<String, Any?>? = null,
        dialect: Dialect? = null,
        normalize: Boolean? = null,
        udfMapping: Map<String, Any?>? = null,
    ): MappingSchema =
        MappingSchema(
            schema = schema ?: deepMutable(mapping),
            visible = visible ?: LinkedHashMap(this.visible),
            dialect = dialect ?: this.dialect,
            normalize = normalize ?: this.normalize,
            udfMapping = udfMapping ?: deepMutable(this.udfMapping),
        )

    // sqlglot: MappingSchema.add_table
    override fun addTable(
        table: Any,
        columnMapping: Any?,
        dialect: Dialect?,
        normalize: Boolean?,
        matchDepth: Boolean,
    ) {
        val normalizedTable = normalizeTable(table, dialect = dialect, normalize = normalize)

        if (matchDepth && !empty && normalizedTable.parts.size != depth()) {
            throw SchemaError(
                "Table ${this.dialect.generate(normalizedTable)} must match the " +
                    "schema's nesting level: ${depth()}."
            )
        }

        val normalizedColumnMapping = LinkedHashMap<String, Any?>()
        for ((key, value) in ensureColumnMapping(columnMapping)) {
            normalizedColumnMapping[
                normalizeNamePart(key, dialect = dialect, normalize = normalize)
            ] = value
        }

        val schema = find(normalizedTable, raiseOnMissing = false)
        if (schema is Map<*, *> && schema.isNotEmpty() && normalizedColumnMapping.isEmpty()) {
            return
        }

        val parts = tableParts(normalizedTable)

        nestedSet(mapping, parts.asReversed(), normalizedColumnMapping)
        newTrie(listOf(parts), mappingTrie)
    }

    // sqlglot: MappingSchema.column_names
    override fun columnNames(
        table: Any,
        onlyVisible: Boolean,
        dialect: Dialect?,
        normalize: Boolean?,
    ): List<String> {
        val normalizedTable = normalizeTable(table, dialect = dialect, normalize = normalize)

        val schema = find(normalizedTable) as? Map<*, *> ?: return emptyList()

        if (!onlyVisible || visible.isEmpty()) {
            return schema.keys.map { it as String }
        }

        val visibleCols = nestedGetParts(tableParts(normalizedTable), visible)
        val visibleSet: Set<*> = when (visibleCols) {
            is Set<*> -> visibleCols
            is Collection<*> -> visibleCols.toSet()
            else -> emptySet<Any?>()
        }
        return schema.keys.filter { it in visibleSet }.map { it as String }
    }

    // sqlglot: MappingSchema.get_column_type
    override fun getColumnType(
        table: Any,
        column: Any,
        dialect: Dialect?,
        normalize: Boolean?,
    ): DataType {
        val normalizedTable = normalizeTable(table, dialect = dialect, normalize = normalize)

        val normalizedColumnName = normalizeNamePart(
            if (column is String) column else (column as Expression).thisArg!!,
            dialect = dialect,
            normalize = normalize,
        )

        val tableSchema = find(normalizedTable, raiseOnMissing = false) as? Map<*, *>
        if (!tableSchema.isNullOrEmpty()) {
            val columnType = tableSchema[normalizedColumnName]

            if (columnType is DataType) {
                return columnType
            } else if (columnType is String) {
                return toDataType(columnType, dialect = dialect)
            }
        }

        return DataType(args("this" to DType.UNKNOWN))
    }

    // sqlglot: MappingSchema.get_udf_type
    // TODO(udf): stubbed to UNKNOWN — Python resolves via udf_trie; port together with
    // the annotate_types UDF support.
    override fun getUdfType(udf: Any, dialect: Dialect?, normalize: Boolean?): DataType =
        DataType(args("this" to DType.UNKNOWN))

    // sqlglot: MappingSchema.has_column
    override fun hasColumn(
        table: Any,
        column: Any,
        dialect: Dialect?,
        normalize: Boolean?,
    ): Boolean {
        val normalizedTable = normalizeTable(table, dialect = dialect, normalize = normalize)

        val normalizedColumnName = normalizeNamePart(
            if (column is String) column else (column as Expression).thisArg!!,
            dialect = dialect,
            normalize = normalize,
        )

        val tableSchema = find(normalizedTable, raiseOnMissing = false) as? Map<*, *>
        return if (!tableSchema.isNullOrEmpty()) normalizedColumnName in tableSchema else false
    }

    // sqlglot: MappingSchema._normalize
    private fun normalizeMapping(schema: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val normalizedMapping = LinkedHashMap<String, Any?>()
        val flattenedSchema = flattenSchema(schema)

        for (keys in flattenedSchema) {
            val columns = nestedGet(schema, keys.zip(keys))

            if (columns !is Map<*, *>) {
                throw SchemaError(
                    "Table ${keys.dropLast(1).joinToString(".")} must match the schema's " +
                        "nesting level: ${flattenedSchema[0].size}."
                )
            }
            if (columns.isEmpty()) {
                throw SchemaError(
                    "Table ${keys.dropLast(1).joinToString(".")} must have at least one column"
                )
            }
            if (columns.values.first() is Map<*, *>) {
                throw SchemaError(
                    "Table ${
                        (keys + flattenSchema(columns)[0]).joinToString(".")
                    } must match the schema's nesting level: ${flattenedSchema[0].size}."
                )
            }

            val normalizedKeys = keys.map { normalizeNamePart(it, isTable = true) }
            for ((columnName, columnType) in columns) {
                nestedSet(
                    normalizedMapping,
                    normalizedKeys + normalizeNamePart(columnName as Any),
                    columnType,
                )
            }
        }

        return normalizedMapping
    }

    // sqlglot: MappingSchema._normalize_table
    private fun normalizeTable(
        table: Any,
        dialect: Dialect? = null,
        normalize: Boolean? = null,
    ): Table {
        val d = dialect ?: this.dialect
        val n = normalize ?: this.normalize

        // sqlglot: exp.maybe_parse(table, into=exp.Table, dialect=dialect, copy=normalize)
        val normalizedTable: Table = when (table) {
            is Table -> if (n) table.copy() as Table else table
            is String ->
                d.parser().parseIntoTable(d.tokenize(table), table) as? Table
                    ?: throw ParseError("Failed to parse '$table' into Table")
            else -> throw IllegalArgumentException("Invalid table: $table")
        }

        if (n) {
            for (part in normalizedTable.parts) {
                if (part is Identifier) {
                    part.replace(
                        normalizeName(part, dialect = d, isTable = true, normalize = n)
                    )
                }
            }
        }

        return normalizedTable
    }

    // sqlglot: MappingSchema._normalize_name (name: String | Identifier)
    private fun normalizeNamePart(
        name: Any,
        dialect: Dialect? = null,
        isTable: Boolean = false,
        normalize: Boolean? = null,
    ): String {
        val n = normalize ?: this.normalize
        val d = dialect ?: this.dialect
        val nameStr = if (name is String) name else (name as Expression).name
        val cacheKey = listOf<Any?>(nameStr, d, isTable, n)
        normalizedNameCache[cacheKey]?.let { return it }

        val result = normalizeName(name, dialect = d, isTable = isTable, normalize = n).name
        normalizedNameCache[cacheKey] = result
        return result
    }

    // sqlglot: MappingSchema.depth
    override fun depth(): Int {
        if (!empty && cachedDepth == 0) {
            // The columns themselves are a mapping, but we don't want to include those
            cachedDepth = super.depth() - 1
        }
        return cachedDepth
    }

    // sqlglot: MappingSchema._to_data_type
    private fun toDataType(schemaType: String, dialect: Dialect? = null): DataType =
        typeMappingCache.getOrPut(schemaType) {
            val d = dialect ?: this.dialect
            val udt = d.parser().supportsUserDefinedTypes

            val expression = try {
                dataTypeFromStr(schemaType, dialect = d, udt = udt)
            } catch (e: Exception) {
                throw SchemaError("Failed to build type '$schemaType' in dialect ${d.name}.")
            }
            expression.transform(copy = false) { d.normalizeIdentifier(it) }
            expression
        }
}

// sqlglot: exp.DataType.from_str
fun dataTypeFromStr(dtype: String, dialect: Dialect? = null, udt: Boolean = false): DataType {
    if (dtype.uppercase() == "UNKNOWN") return DataType(args("this" to DType.UNKNOWN))

    val d = dialect ?: Dialects.BASE
    val parsed = try {
        d.parser(errorLevel = ErrorLevel.IGNORE).parseIntoDataType(d.tokenize(dtype), dtype)
    } catch (e: Exception) {
        null
    }

    if (parsed is DataType) return parsed
    if (udt) return DataType(args("this" to DType.USERDEFINED, "kind" to dtype))
    throw ParseError("Failed to parse '$dtype' into DataType")
}

// sqlglot: schema.normalize_name — the Python signature says str | Identifier, but
// callers also pass e.g. Star nodes (Column(this=Star) in get_column_type); those
// flow through unchanged apart from the is_table meta side effect, like in Python.
fun normalizeName(
    identifier: Any,
    dialect: Dialect? = null,
    isTable: Boolean = false,
    normalize: Boolean? = true,
): Expression {
    val ident: Expression = when (identifier) {
        is String -> parseIdentifier(identifier, dialect)
        is Expression -> identifier
        else -> throw IllegalArgumentException("Invalid identifier: $identifier")
    }

    if (normalize != true) return ident

    // this is used for normalize_identifier, bigquery has special rules pertaining tables
    ident.meta["is_table"] = isTable
    return (dialect ?: Dialects.BASE).normalizeIdentifier(ident)
}

// sqlglot: builders.parse_identifier
fun parseIdentifier(name: String, dialect: Dialect? = null): Identifier {
    // Simple names parse to a single unquoted identifier in all dialects, so we can
    // avoid the tokenizer/parser round-trip for them.
    if (SAFE_IDENTIFIER_RE.matches(name)) {
        return Identifier(args("this" to name, "quoted" to false))
    }

    return try {
        val d = dialect ?: Dialects.BASE
        d.parser().parseIntoIdentifier(d.tokenize(name), name) as? Identifier
            ?: toIdentifier(name)
    } catch (e: Exception) {
        toIdentifier(name)
    }
}

// sqlglot: builders.to_identifier (string branch)
private fun toIdentifier(name: String): Identifier =
    Identifier(args("this" to name, "quoted" to !SAFE_IDENTIFIER_RE.matches(name)))

// sqlglot: schema.ensure_schema
fun ensureSchema(
    schema: Any?,
    dialect: Dialect? = null,
    normalize: Boolean = true,
): Schema {
    if (schema is Schema) return schema

    @Suppress("UNCHECKED_CAST")
    return MappingSchema(
        schema = schema as? Map<String, Any?>,
        dialect = dialect,
        normalize = normalize,
    )
}

// sqlglot: schema.ensure_column_mapping
fun ensureColumnMapping(mapping: Any?): MutableMap<String, Any?> = when (mapping) {
    null -> LinkedHashMap()
    is Map<*, *> -> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in mapping) out[k as String] = v
        out
    }
    is String -> {
        val out = LinkedHashMap<String, Any?>()
        for (nameTypeStr in mapping.split(",").map { it.trim() }) {
            out[nameTypeStr.split(":")[0].trim()] = nameTypeStr.split(":")[1].trim()
        }
        out
    }
    is List<*> -> {
        val out = LinkedHashMap<String, Any?>()
        for (x in mapping) out[(x as String).trim()] = null
        out
    }
    else -> throw IllegalArgumentException("Invalid mapping provided: $mapping")
}

// sqlglot: schema.flatten_schema
fun flattenSchema(
    schema: Map<*, *>,
    depth: Int? = null,
    keys: List<String>? = null,
): List<List<String>> {
    val tables = mutableListOf<List<String>>()
    val ks = keys ?: emptyList()
    val d = depth ?: (dictDepth(schema) - 1)

    for ((k, v) in schema) {
        if (d == 1 || v !is Map<*, *>) {
            tables.add(ks + k.toString())
        } else if (d >= 2) {
            tables.addAll(flattenSchema(v, d - 1, ks + k.toString()))
        }
    }

    return tables
}

// sqlglot: schema.nested_get (path items are (name, key) pairs)
fun nestedGet(
    d: Map<*, *>,
    path: List<Pair<String, String>>,
    raiseOnMissing: Boolean = true,
): Any? {
    var result: Any? = d
    for ((name, key) in path) {
        result = (result as? Map<*, *>)?.get(key)
        if (result == null) {
            if (raiseOnMissing) {
                val label = if (name == "this") "table" else name
                throw IllegalArgumentException("Unknown $label: $key")
            }
            return null
        }
    }
    return result
}

// sqlglot: schema.nested_set (in-place set for a nested mapping)
fun nestedSet(
    d: MutableMap<String, Any?>,
    keys: List<String>,
    value: Any?,
): MutableMap<String, Any?> {
    if (keys.isEmpty()) return d

    if (keys.size == 1) {
        d[keys[0]] = value
        return d
    }

    var subd = d
    for (key in keys.dropLast(1)) {
        @Suppress("UNCHECKED_CAST")
        subd = subd.getOrPut(key) { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
    }

    subd[keys.last()] = value
    return d
}

// sqlglot: helper.dict_depth
fun dictDepth(d: Any?): Int = when {
    d !is Map<*, *> -> 0 // AttributeError branch
    d.isEmpty() -> 1 // StopIteration branch
    else -> 1 + dictDepth(d.values.first())
}

/** Deep-copies nested maps into LinkedHashMaps so nested_set can mutate them. */
private fun deepMutable(schema: Map<String, Any?>): MutableMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in schema) {
        @Suppress("UNCHECKED_CAST")
        out[k] = if (v is Map<*, *>) deepMutable(v as Map<String, Any?>) else v
    }
    return out
}

// ---------------------------------------------------------------------------
// String-part trie (sqlglot/trie.py). The parser's Trie.kt is char-keyed for keyword
// scanning; the schema needs part-keyed tries with PREFIX/EXISTS results, so this is a
// separate faithful port of the nested-dict shape (0 marks a terminal).
// ---------------------------------------------------------------------------

// sqlglot: trie.TrieResult
enum class TrieResult { FAILED, PREFIX, EXISTS }

// sqlglot: trie.new_trie
fun newTrie(
    keywords: Iterable<List<String>>,
    trie: MutableMap<Any, Any?>? = null,
): MutableMap<Any, Any?> {
    val root: MutableMap<Any, Any?> = trie ?: LinkedHashMap()

    for (key in keywords) {
        var current = root
        for (part in key) {
            @Suppress("UNCHECKED_CAST")
            current = current.getOrPut(part) { LinkedHashMap<Any, Any?>() }
                as MutableMap<Any, Any?>
        }
        current[0] = true
    }

    return root
}

// sqlglot: trie.in_trie
fun inTrie(trie: Map<Any, Any?>, key: List<String>): Pair<TrieResult, Map<Any, Any?>> {
    if (key.isEmpty()) return TrieResult.FAILED to trie

    var current = trie
    for (part in key) {
        val next = current[part] ?: return TrieResult.FAILED to current
        @Suppress("UNCHECKED_CAST")
        current = next as Map<Any, Any?>
    }

    return if (current.containsKey(0)) {
        TrieResult.EXISTS to current
    } else {
        TrieResult.PREFIX to current
    }
}
