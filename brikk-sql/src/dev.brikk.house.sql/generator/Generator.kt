package dev.brikk.house.sql.generator

// Explicit kotlin imports shield builtins from same-named ast classes (Array, List,
// Map, Set, String, Boolean) pulled in by the star import.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Any as AnyNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Set as SetNode
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.Set
import kotlin.reflect.KClass

/** Dispatch entry type: a generator method rendering one expression class. */
typealias GenMethod = Generator.(Expression) -> String

// sqlglot: helper.csv
internal fun csv(vararg parts: String, sep: String = ", "): String =
    parts.filter { it.isNotEmpty() }.joinToString(sep)

/**
 * Port of sqlglot's Generator (reference/sqlglot/sqlglot/generator.py) for the base
 * "sqlglot" dialect. Methods appear in the SAME ORDER as generator.py.
 *
 * Table of contents:
 *  1. Options / state          — constructor options, dialect flags, derived quoting state
 *  2. Core dispatch + helpers  — generate, sql, sep/seg/indent, wrap, maybeComment,
 *                                 expressions, func/formatArgs, binary/connector helpers
 *  3. Identifier / literal     — identifierSql, literalSql, escapeStr, string kinds
 *  4. Clause methods           — select/from/where/join/order/limit/set-ops/CTE/DDL...
 *  5. Expression methods       — operators, predicates, casts, windows, intervals...
 *  6. Function fallbacks       — functionFallbackSql + specific Func overrides
 *
 * Dispatch happens through an explicit KClass -> method map ([GeneratorTables.DISPATCH]
 * merged with the [overrides] constructor arg) instead of Python's name-based
 * auto-discovery; unknown Funcs/Properties fall back like Python's `sql()`.
 */
open class Generator(
    // sqlglot: Generator.__init__ (defaults match Python)
    val pretty: Boolean = false,
    // false | true | "safe" | "unsafe" (sqlglot: identify)
    identify: kotlin.Any = false,
    val normalize: Boolean = false,
    val pad: Int = 2,
    indent: Int = 2,
    // "upper" | "lower" | true | false (sqlglot: normalize_functions; base dialect default "upper")
    val normalizeFunctions: kotlin.Any = "upper",
    val leadingComma: Boolean = false,
    val maxTextWidth: Int = 80,
    val comments: Boolean = true,
    val tokenizerConfig: TokenizerConfig = TokenizerConfig.BASE,
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
) {
    // ------------------------------------------------------------------
    // 1. Options / state
    // ------------------------------------------------------------------

    var identify: kotlin.Any = identify
        protected set

    private val indentWidth: Int = indent

    // sqlglot: Generator.unsupported_messages (unsupported_level defaults to WARN: collect, don't raise)
    val unsupportedMessages: MutableList<String> = mutableListOf()

    protected val dispatch: Map<KClass<out Expression>, GenMethod> =
        if (overrides.isEmpty()) GeneratorTables.DISPATCH
        else GeneratorTables.DISPATCH + overrides

    /**
     * Whether this generator has a dedicated renderer for [kclass] (dispatch entry /
     * dialect transform), as opposed to the generic function-fallback that emits
     * `NAME(args)` verbatim. Capability checks use this to separate grammar-shaped
     * functions (CAST, EXTRACT, ...) from names that pass through to the engine.
     */
    fun hasDedicatedRenderer(kclass: KClass<out Expression>): Boolean =
        dispatch.containsKey(kclass)

    // sqlglot: dialect QUOTE_START/QUOTE_END/IDENTIFIER_START/IDENTIFIER_END (from tokenizer tables)
    protected val quoteStart: String = tokenizerConfig.quotes.keys.first()
    protected val quoteEnd: String = tokenizerConfig.quotes.values.first()
    protected val identifierStart: String = tokenizerConfig.identifiers.keys.first().toString()
    protected val identifierEnd: String = tokenizerConfig.identifiers.values.first()

    // sqlglot: Generator._escaped_quote_end / _escaped_identifier_end
    // (STRING_ESCAPES[0] + QUOTE_END; open because the Kotlin tokenizer configs keep
    // escapes as sets, losing Python's list order)
    protected open val escapedQuoteEnd: String get() = tokenizerConfig.stringEscapes.first() + quoteEnd
    protected val escapedIdentifierEnd: String = identifierEnd + identifierEnd

    // sqlglot: Generator._next_name (name_sequence("_t"))
    private var nextNameCounter = -1
    protected fun nextName(): String { nextNameCounter += 1; return "_t$nextNameCounter" }

    // --- brikk-native: emit-span tracking (see SourceMap.kt) ------------------
    //
    // Design: generation is bottom-up string concatenation (each dispatch handler
    // returns a fragment its parent embeds), so a fragment's absolute offset is
    // unknown at dispatch time. When [trackSpans] is on, the central [sql] dispatch
    // records, per rendered node, the exact returned fragment plus the fragments of
    // its (recursively recorded) children — an offset TREE keyed by fragment
    // strings. After generation, [buildSourceMap] resolves absolute offsets top-down:
    // the root fragment is the whole raw output; each child fragment is located
    // inside its parent's window via a cursor-advancing verbatim search (with a
    // from-the-start retry for handlers that reorder args). Children whose fragment
    // was transformed after rendering (pretty-mode re-indentation of multi-line
    // blocks) get no span, but their descendants are re-resolved against the parent
    // window, so leaf/inline nodes keep exact spans even in pretty output. Final
    // offsets are then remapped through generate()'s post-passes (trim + pretty
    // SENTINEL_LINE_BREAK replacement). Overhead when disabled: one boolean check
    // per sql() call, no allocations.

    /** brikk-native: enable emit-span recording for subsequent [generate] calls. */
    var trackSpans: Boolean = false

    /** brikk-native: the [SourceMap] of the last [generate] call (when [trackSpans]). */
    var lastSourceMap: SourceMap? = null
        protected set

    private class SpanRecord(val node: Expression, val fragment: String, val children: List<SpanRecord>)

    private val spanStack = ArrayDeque<MutableList<SpanRecord>>()

    // --- Generator class-level flags (base dialect values; open for dialect subclasses) ---
    // sqlglot: NULL_ORDERING_SUPPORTED and friends (only flags used by ported methods)
    open val nullOrderingSupported: Boolean? get() = true
    open val ignoreNullsInFunc: Boolean get() = false
    open val lockingReadsSupported: Boolean get() = false
    open val exceptIntersectSupportAllClause: Boolean get() = true
    open val wrapDerivedValues: Boolean get() = true
    open val createFunctionReturnAs: Boolean get() = true
    open val singleStringInterval: Boolean get() = false
    open val intervalAllowsPluralForm: Boolean get() = true
    open val limitFetch: String get() = "ALL"
    // sqlglot: Generator.LIMIT_ONLY_LITERALS
    open val limitOnlyLiterals: Boolean get() = false
    // sqlglot: Generator.UNPIVOT_ALIASES_ARE_IDENTIFIERS
    open val unpivotAliasesAreIdentifiers: Boolean get() = true
    open val renameTableWithDb: Boolean get() = true
    open val groupingsSep: String get() = ","
    open val indexOn: String get() = "ON"
    open val joinHints: Boolean get() = true
    open val directedJoins: Boolean get() = false
    open val tableHints: Boolean get() = true
    open val queryHints: Boolean get() = true
    open val queryHintSep: String get() = ", "
    open val isBoolAllowed: Boolean get() = true
    open val duplicateKeyUpdateWithSet: Boolean get() = true
    open val limitIsTop: Boolean get() = false
    open val returningEnd: Boolean get() = true
    open val extractAllowsQuotes: Boolean get() = true
    open val tzToWithTimeZone: Boolean get() = false
    open val selectKinds: Set<String> get() = setOf("STRUCT", "VALUE")
    open val valuesAsTable: Boolean get() = true
    open val alterTableIncludeColumnKeyword: Boolean get() = true
    open val unnestWithOrdinality: Boolean get() = true
    open val aggregateFilterSupported: Boolean get() = true
    open val semiAntiJoinWithSide: Boolean get() = true
    open val computedColumnWithType: Boolean get() = true
    open val supportsTableCopy: Boolean get() = true
    open val tablesampleRequiresParens: Boolean get() = true
    open val tablesampleSizeIsRows: Boolean get() = true
    open val tablesampleKeywords: String get() = "TABLESAMPLE"
    open val tablesampleWithMethod: Boolean get() = true
    open val tablesampleSeedKeyword: String get() = "SEED"
    open val collateIsFunc: Boolean get() = false
    open val dataTypeSpecifiersAllowed: Boolean get() = false
    open val cteRecursiveKeywordRequired: Boolean get() = true
    open val supportsSingleArgConcat: Boolean get() = true
    open val supportsTableAliasColumns: Boolean get() = true
    open val supportsNamedCteColumns: Boolean get() = true
    open val jsonKeyValuePairSep: String get() = ":"
    open val insertOverwrite: String get() = " OVERWRITE TABLE"
    open val supportsSelectInto: Boolean get() = false
    open val supportsUnloggedTables: Boolean get() = false
    open val supportsCreateTableLike: Boolean get() = true
    open val likePropertyInsideSchema: Boolean get() = false
    open val multiArgDistinct: Boolean get() = true
    open val jsonPathBracketedKeySupported: Boolean get() = true
    open val jsonPathSingleQuoteEscape: Boolean get() = false
    open val jsonPathKeyQuotedForcesBrackets: Boolean get() = false
    open val supportsToNumber: Boolean get() = true
    open val supportsWindowExclude: Boolean get() = false
    open val setOpModifiers: Boolean get() = true
    open val trySupported: Boolean get() = true
    open val starExcept: String get() = "EXCEPT"
    open val withPropertiesPrefix: String get() = "WITH"
    open val quoteJsonPath: Boolean get() = true
    open val supportsMedian: Boolean get() = true
    open val alterSetWrapped: Boolean get() = false
    open val parseJsonName: String? get() = "PARSE_JSON"
    open val alterSetType: String get() = "SET DATA TYPE"
    open val supportsBetweenFlags: Boolean get() = false
    open val supportsLikeQuantifiers: Boolean get() = true
    open val setAssignmentRequiresVariableKeyword: Boolean get() = false
    open val updateStatementSupportsFrom: Boolean get() = true
    open val starExcludeRequiresDerivedTable: Boolean get() = true
    open val supportsDropAlterIcebergProperty: Boolean get() = true
    open val structDelimiter: Pair<String, String> get() = "<" to ">"
    open val parameterToken: String get() = "@"
    open val namedPlaceholderToken: String get() = ":"
    open val padFillPatternIsRequired: Boolean get() = false
    open val matchedBySource: Boolean get() = true
    open val supportsMergeWhere: Boolean get() = false
    open val nvl2Supported: Boolean get() = true
    open val reservedKeywords: Set<String> get() = emptySet()
    // sqlglot: Generator.TYPE_MAPPING (base: empty)
    open val typeMapping: Map<DType, String> get() = emptyMap()
    // sqlglot: Dialect.INVERSE_TIME_MAPPING (base TIME_MAPPING is empty)
    open val inverseTimeMapping: Map<String, String> get() = emptyMap()
    // sqlglot: Dialect.ESCAPED_SEQUENCES (base: empty; populated when the dialect's
    // tokenizer supports backslash escape sequences)
    open val escapedSequences: Map<String, String> get() = emptyMap()
    // sqlglot: Dialect.HEX_START / HEX_END (base tokenizer has no hex strings)
    open val hexStart: String? get() = null
    open val hexEnd: String? get() = null
    // sqlglot: Dialect.HEX_STRING_IS_INTEGER_TYPE
    open val hexStringIsIntegerType: Boolean get() = false
    // sqlglot: Dialect.TIME_FORMAT / DATE_FORMAT
    open val dialectTimeFormat: String get() = "'%Y-%m-%d %H:%M:%S'"
    open val dialectDateFormat: String get() = "'%Y-%m-%d'"
    // sqlglot: Generator.PROPERTIES_LOCATION (open so dialects can relocate properties)
    open val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = GeneratorTables.PROPERTIES_LOCATION
    open val expressionPrecedesPropertiesCreatables: Set<String> get() = emptySet()
    open val inoutSeparator: String get() = " "
    open val supportsUescape: Boolean get() = true

    // --- Dialect-level flags (base dialect values) ---
    // sqlglot: Generator.dialect (the umbrella Dialect object; used by annotate_types-
    // driven generation paths like convert_concat_args and Presto's struct_sql)
    open val dialect: dev.brikk.house.sql.dialects.Dialect
        get() = dev.brikk.house.sql.dialects.Dialects.BASE

    // sqlglot: Dialect.* defaults used by the base generator
    open val dialectSupportsColumnJoinMarks: Boolean get() = false
    open val dialectAliasPostTablesample: Boolean get() = false
    open val dialectAliasPostVersion: Boolean get() = true
    open val dialectUnnestColumnOnly: Boolean get() = false
    open val dialectNullOrdering: String get() = "nulls_are_small"
    open val dialectIndexOffset: Int get() = 0
    open val dialectPreserveOriginalNames: Boolean get() = false
    open val dialectAlterTableAddRequiredForEachColumn: Boolean get() = true
    open val dialectAlterTableSupportsCascade: Boolean get() = false
    open val dialectConcatCoalesce: Boolean get() = false
    open val dialectConcatWsCoalesce: Boolean get() = false
    open val dialectStrictStringConcat: Boolean get() = false
    open val dialectSafeDivision: Boolean get() = false
    open val dialectTypedDivision: Boolean get() = false
    open val dialectLogBaseFirst: Boolean? get() = true
    open val dialectTablesampleSizeIsPercent: Boolean get() = false
    open val dialectStringsSupportEscapedSequences: Boolean get() = false
    // sqlglot: Dialect.BYTE_STRINGS_SUPPORT_ESCAPED_SEQUENCES
    open val dialectByteStringsSupportEscapedSequences: Boolean get() = false
    // sqlglot: Dialect.BYTE_START / BYTE_END (from tokenizer BYTE_STRINGS)
    open val byteStart: String? get() = null
    open val byteEnd: String? get() = null
    // sqlglot: Dialect.BYTE_STRING_IS_BYTES_TYPE
    open val dialectByteStringIsBytesType: Boolean get() = false
    open val dialectArrayAggIncludesNulls: Boolean get() = true
    open val dialectIdentifiersCanStartWithDigit: Boolean get() = false
    open val dialectIdentifiersCanStartWithDollar: Boolean get() = false
    open val inverseCreatableKindMapping: Map<String, String> get() = emptyMap()

    companion object {
        // sqlglot: Generator.SENTINEL_LINE_BREAK
        const val SENTINEL_LINE_BREAK = "__SQLGLOT__LB__"

        // sqlglot: expressions.core.SAFE_IDENTIFIER_RE
        internal val SAFE_IDENTIFIER_RE = Regex("^[_a-zA-Z]\\w*$")

        // sqlglot: expressions.datatypes.DataType.INTEGER_TYPES
        val INTEGER_TYPES: Set<DType> = setOf(
            DType.BIGINT, DType.INT, DType.INT128, DType.INT256, DType.MEDIUMINT,
            DType.SMALLINT, DType.TINYINT, DType.UBIGINT, DType.UINT, DType.UINT128,
            DType.UINT256, DType.UMEDIUMINT, DType.USMALLINT, DType.UTINYINT, DType.BIT,
        )

        // sqlglot: expressions.datatypes.DataType.REAL_TYPES
        val REAL_TYPES: Set<DType> = setOf(
            DType.DOUBLE, DType.FLOAT, DType.BIGDECIMAL, DType.DECIMAL, DType.DECIMAL32,
            DType.DECIMAL64, DType.DECIMAL128, DType.DECIMAL256, DType.DECFLOAT,
            DType.MONEY, DType.SMALLMONEY, DType.UDECIMAL, DType.UDOUBLE,
        )

        // sqlglot: expressions.datatypes.DataType.TEXT_TYPES
        val TEXT_TYPES: Set<DType> = setOf(
            DType.CHAR, DType.NCHAR, DType.NVARCHAR, DType.TEXT, DType.VARCHAR, DType.NAME,
        )
    }

    // ------------------------------------------------------------------
    // 2. Core dispatch + helpers
    // ------------------------------------------------------------------

    // sqlglot: Generator.generate
    open fun generate(expression: Expression, copy: Boolean = true): String {
        val expr = if (copy) expression.copy() else expression
        unsupportedMessages.clear()
        // brikk-native: span tracking bookkeeping (no-op unless trackSpans)
        lastSourceMap = null
        if (trackSpans) {
            spanStack.clear()
            spanStack.addLast(mutableListOf())
        }
        val raw = sql(preprocess(expr))
        var sql = raw.trim()
        if (pretty) sql = sql.replace(SENTINEL_LINE_BREAK, "\n")
        if (trackSpans) {
            lastSourceMap = buildSourceMap(raw, sql)
            spanStack.clear()
        }
        return sql
    }

    /**
     * brikk-native: resolves the recorded span tree into absolute offsets over the
     * final output. [raw] is the pre-trim generator output; [final] the returned SQL
     * (raw trimmed, and with SENTINEL_LINE_BREAK replaced by \n in pretty mode).
     */
    private fun buildSourceMap(raw: String, final: String): SourceMap {
        val entries = mutableListOf<SourceMap.Entry>()

        // Offset remap raw -> final: subtract the leading trim cut, then account for
        // each SENTINEL_LINE_BREAK (len N) collapsing to "\n" (len 1) in pretty mode.
        val leadCut = raw.length - raw.trimStart().length
        val trimmed = raw.trim()
        val sentinelPositions: List<Int> =
            if (pretty && trimmed.contains(SENTINEL_LINE_BREAK)) {
                val positions = mutableListOf<Int>()
                var i = trimmed.indexOf(SENTINEL_LINE_BREAK)
                while (i >= 0) {
                    positions.add(i)
                    i = trimmed.indexOf(SENTINEL_LINE_BREAK, i + SENTINEL_LINE_BREAK.length)
                }
                positions
            } else emptyList()
        val sentinelLen = SENTINEL_LINE_BREAK.length

        fun remap(rawOffset: Int): Int {
            var o = rawOffset - leadCut
            if (o < 0) o = 0
            if (o > trimmed.length) o = trimmed.length
            var shift = 0
            for (p in sentinelPositions) {
                when {
                    p + sentinelLen <= o -> shift += sentinelLen - 1
                    p < o -> return (p - shift + 1).coerceIn(0, final.length) // mid-sentinel: clamp past the "\n"
                    else -> {}
                }
                if (p >= o) break
            }
            return (o - shift).coerceIn(0, final.length)
        }

        fun addEntry(node: Expression, rawStart: Int, rawEnd: Int) {
            val s = remap(rawStart)
            val e = remap(rawEnd)
            if (e > s) entries.add(SourceMap.Entry(s, e, node))
        }

        // Locates each child fragment inside its parent's window (cursor scan with a
        // from-the-start retry for arg-reordering handlers); unresolved children are
        // skipped but their descendants are re-resolved against the same window.
        fun resolve(children: List<SpanRecord>, windowStart: Int, window: String) {
            var cursor = 0
            for (child in children) {
                val frag = child.fragment
                if (frag.isEmpty()) {
                    resolve(child.children, windowStart, window)
                    continue
                }
                var idx = window.indexOf(frag, cursor)
                if (idx < 0) idx = window.indexOf(frag)
                if (idx < 0) {
                    resolve(child.children, windowStart, window)
                    continue
                }
                addEntry(child.node, windowStart + idx, windowStart + idx + frag.length)
                resolve(child.children, windowStart + idx, frag)
                cursor = idx + frag.length
            }
        }

        val roots = spanStack.firstOrNull() ?: mutableListOf()
        resolve(roots, 0, raw)
        return SourceMap(final, entries)
    }

    // sqlglot: Generator.EXPRESSIONS_WITHOUT_NESTED_CTES (base: empty set). Dialects that
    // only allow top-level CTEs (Hive/Spark<3, T-SQL) override this.
    open val expressionsWithoutNestedCtes: Set<kotlin.reflect.KClass<out Expression>> get() = emptySet()

    // sqlglot: Generator.preprocess -> Generator._move_ctes_to_top_level. ENSURE_BOOLS is
    // false for every dialect we currently model, so only the nested-CTE hoist runs.
    open fun preprocess(expression: Expression): Expression = moveCtesToTopLevelIfNeeded(expression)

    // sqlglot: Generator._move_ctes_to_top_level
    protected fun moveCtesToTopLevelIfNeeded(expression: Expression): Expression {
        if (expression.parent == null &&
            expression::class in expressionsWithoutNestedCtes &&
            expression.findAll(dev.brikk.house.sql.ast.With::class).any { it.parent !== expression }
        ) {
            return moveCtesToTopLevel(expression)
        }
        return expression
    }

    // sqlglot: Generator.unsupported (unsupported_level WARN: collect only)
    open fun unsupported(message: String) {
        unsupportedMessages.add(message)
    }

    // sqlglot: Generator.sep
    fun sep(sep: String = " "): String = if (pretty) "${sep.trim()}\n" else sep

    // sqlglot: Generator.seg
    fun seg(sql: String, sep: String = " "): String = "${sep(sep)}$sql"

    // sqlglot: Generator.sanitize_comment
    open fun sanitizeComment(comment: String): String {
        var c = comment
        if (c.first().toString().isNotBlank()) c = " $c"
        if (c.last().toString().isNotBlank()) c += " "
        return c.replace("*/", "* /").replace("/*", "/ *")
    }

    // sqlglot: Generator.maybe_comment
    open fun maybeComment(
        sql: String,
        expression: Expression? = null,
        comments: List<String>? = null,
        separated: Boolean = false,
    ): String {
        val commentList: List<String>? =
            if (this.comments) comments ?: expression?.comments else null

        if (commentList.isNullOrEmpty() || isExcludeComments(expression)) return sql

        val rendered = commentList
            .filter { it.isNotEmpty() }
            .map { "/*${replaceLineBreaks(sanitizeComment(it))}*/" }

        if (rendered.isEmpty()) return sql

        if (separated || isWithSeparatedComments(expression)) {
            val commentsSql = rendered.joinToString(sep())
            return if (sql.isEmpty() || sql.first().isWhitespace()) {
                "${sep()}$commentsSql$sql"
            } else {
                "$commentsSql${sep()}$sql"
            }
        }

        return "$sql ${rendered.joinToString(" ")}"
    }

    // sqlglot: Generator.EXCLUDE_COMMENTS (Binary, SetOperation)
    protected open fun isExcludeComments(expression: Expression?): Boolean =
        expression is Binary || expression is SetOperation

    // sqlglot: Generator.WITH_SEPARATED_COMMENTS
    protected open fun isWithSeparatedComments(expression: Expression?): Boolean = when (expression) {
        is Command, is Create, is Describe, is Delete, is Drop, is From, is Insert, is Join,
        is Order, is Group, is Having, is Select, is SetOperation, is Update, is Where, is With,
        -> true
        else -> false
    }

    // sqlglot: Generator.wrap
    open fun wrap(expression: kotlin.Any?): String {
        val thisSqlRaw = when (expression) {
            is Select, is SetOperation -> sql(expression)
            is String -> expression
            else -> sql(expression, "this")
        }
        if (thisSqlRaw.isEmpty()) return "()"
        val thisSql = indent(thisSqlRaw, level = 1, pad = 0)
        return "(${sep("")}$thisSql${seg(")", sep = "")}"
    }

    // sqlglot: Generator.no_identify
    fun <T> noIdentify(block: () -> T): T {
        val original = identify
        identify = false
        try {
            return block()
        } finally {
            identify = original
        }
    }

    // sqlglot: Generator.normalize_func
    open fun normalizeFunc(name: String): String = when (normalizeFunctions) {
        "upper", true -> name.uppercase()
        "lower" -> name.lowercase()
        else -> name
    }

    // sqlglot: Generator.indent
    fun indent(
        sql: String,
        level: Int = 0,
        pad: Int? = null,
        skipFirst: Boolean = false,
        skipLast: Boolean = false,
    ): String {
        if (!pretty || sql.isEmpty()) return sql
        val padAmount = pad ?: this.pad
        val lines = sql.split("\n")
        return lines.mapIndexed { i, line ->
            if ((skipFirst && i == 0) || (skipLast && i == lines.size - 1)) line
            else " ".repeat(level * indentWidth + padAmount) + line
        }.joinToString("\n")
    }

    // sqlglot: Generator.sql
    open fun sql(expression: kotlin.Any?, key: String? = null, comment: Boolean = true): String {
        if (expression == null || expression == false || expression == "") return ""
        if (expression is String) return expression
        if (expression !is Expression) return ""

        if (key != null) {
            val value = expression.args[key]
            return if (isTruthyArg(value)) sql(value) else ""
        }

        // brikk-native: span recording — push a child collector for this node's frame
        val collecting = trackSpans && spanStack.isNotEmpty()
        if (collecting) spanStack.addLast(mutableListOf())

        val handler = dispatch[expression::class]
        val sql = try {
            when {
                handler != null -> handler(this, expression)
                expression is Func -> functionFallbackSql(expression)
                expression is Property -> propertySql(expression)
                else -> throw UnsupportedError(
                    "Unsupported expression type ${expression::class.simpleName}"
                )
            }
        } catch (t: Throwable) {
            if (collecting) spanStack.removeLast()
            throw t
        }

        val out = if (comments && comment) maybeComment(sql, expression) else sql
        if (collecting) {
            val children = spanStack.removeLast()
            spanStack.last().add(SpanRecord(expression, out, children))
        }
        return out
    }

    // Python truthiness for arg values in `sql(expression, key)`.
    private fun isTruthyArg(value: kotlin.Any?): Boolean = when (value) {
        null, false, "", 0, 0L, 0.0 -> false
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    // sqlglot: Generator.prepend_ctes
    open fun prependCtes(expression: Expression, sql: String): String {
        val with_ = sql(expression, "with_")
        return if (with_.isNotEmpty()) "$with_${sep()}$sql" else sql
    }

    // sqlglot: Generator.expressions
    open fun expressions(
        expression: Expression? = null,
        key: String? = null,
        sqls: List<kotlin.Any?>? = null,
        flat: Boolean = false,
        indent: Boolean = true,
        skipFirst: Boolean = false,
        skipLast: Boolean = false,
        sep: String = ", ",
        prefix: String = "",
        dynamic: Boolean = false,
        newLine: Boolean = false,
    ): String {
        val raw = if (expression != null) expression.args[key ?: "expressions"] else sqls
        val items: List<kotlin.Any?> = when (raw) {
            null -> return ""
            is List<*> -> raw
            else -> listOf(raw)
        }
        if (items.isEmpty()) return ""

        if (flat) {
            return items.asSequence().map { sql(it) }.filter { it.isNotEmpty() }.joinToString(sep)
        }

        val numSqls = items.size
        val resultSqls = mutableListOf<String>()

        for ((i, e) in items.withIndex()) {
            val itemSql = sql(e, comment = false)
            if (itemSql.isEmpty()) continue

            val itemComments = if (e is Expression) maybeComment("", e) else ""

            if (pretty) {
                if (leadingComma) {
                    resultSqls.add("${if (i > 0) sep else ""}$prefix$itemSql$itemComments")
                } else {
                    val tail = if (i + 1 < numSqls) (if (itemComments.isNotEmpty()) sep.trimEnd() else sep) else ""
                    resultSqls.add("$prefix$itemSql$tail$itemComments")
                }
            } else {
                resultSqls.add("$prefix$itemSql$itemComments${if (i + 1 < numSqls) sep else ""}")
            }
        }

        val resultSql = if (pretty && (!dynamic || tooWide(resultSqls))) {
            val lines = resultSqls.toMutableList()
            if (newLine) {
                lines.add(0, "")
                lines.add("")
            }
            lines.joinToString("\n") { it.trimEnd() }
        } else {
            resultSqls.joinToString("")
        }

        return if (indent) indent(resultSql, skipFirst = skipFirst, skipLast = skipLast) else resultSql
    }

    // sqlglot: Generator.op_expressions
    open fun opExpressions(op: String, expression: Expression, flat: Boolean = false): String {
        val isFlat = flat || expression.parent is Properties
        val expressionsSql = expressions(expression, flat = isFlat)
        if (isFlat) return "$op $expressionsSql"
        return "${seg(op)}${if (expressionsSql.isNotEmpty()) sep() else ""}$expressionsSql"
    }

    // sqlglot: Generator.naked_property
    open fun nakedProperty(expression: Property): String {
        val propertyName = GeneratorTables.PROPERTY_TO_NAME[expression::class]
        if (propertyName == null) {
            unsupported("Unsupported property ${expression::class.simpleName}")
        }
        return "$propertyName ${sql(expression, "this")}"
    }

    // sqlglot: Generator.func
    open fun func(
        name: String,
        vararg args: kotlin.Any?,
        prefix: String = "(",
        suffix: String = ")",
        normalize: Boolean = true,
    ): String {
        val funcName = if (normalize) normalizeFunc(name) else name
        return "$funcName$prefix${formatArgs(*args)}$suffix"
    }

    // sqlglot: Generator.format_args
    open fun formatArgs(vararg args: kotlin.Any?, sep: String = ", "): String {
        val argSqls = args.filter { it != null && it !is Boolean }.map { sql(it) }
        if (pretty && tooWide(argSqls)) {
            return indent(
                "\n" + argSqls.joinToString("${sep.trim()}\n") + "\n",
                skipFirst = true,
                skipLast = true,
            )
        }
        return argSqls.joinToString(sep)
    }

    // sqlglot: Generator.too_wide
    fun tooWide(args: Iterable<String>): Boolean = args.sumOf { it.length } > maxTextWidth

    // sqlglot: Generator.format_time — renders args["format"] and converts python
    // strftime specifiers back into this dialect's (INVERSE_TIME_MAPPING).
    open fun formatTime(expression: Expression): String? =
        formatTimeString(sql(expression, "format"), inverseTimeMapping)

    // sqlglot: Generator.binary
    open fun binary(expression: Binary, op: String): String {
        var opText = op
        val sqls = mutableListOf<String>()
        val stack = ArrayDeque<kotlin.Any?>()
        stack.addLast(expression)
        val binaryClass: KClass<out Expression> = expression::class

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node != null && node is Expression && node::class == binaryClass) {
                val opFunc = node.args["operator"]
                if (opFunc != null) opText = "OPERATOR(${sql(opFunc)})"
                stack.addLast(node.args["expression"])
                stack.addLast(" ${maybeComment(opText, comments = node.comments)} ")
                stack.addLast(node.args["this"])
            } else {
                sqls.add(sql(node))
            }
        }

        return sqls.joinToString("")
    }

    // sqlglot: Generator.connector_sql
    open fun connectorSql(expression: Connector, op: String, stack: MutableList<kotlin.Any?>? = null): String {
        if (stack != null) {
            if (expression.expressionsArg.isNotEmpty()) {
                stack.add(expressions(expression, sep = " $op "))
            } else {
                var opText = op
                stack.add(expression.right)
                if (expression.comments != null && comments) {
                    opText = maybeComment(opText, comments = expression.comments)
                }
                stack.add(opText)
                stack.add(expression.left)
                return opText
            }
            return op
        }

        val workStack = mutableListOf<kotlin.Any?>(expression)
        val sqls = mutableListOf<String>()
        val ops = mutableSetOf<String>()

        while (workStack.isNotEmpty()) {
            val node = workStack.removeLast()
            if (node is Connector) {
                ops.add(connectorOpSql(node, workStack))
            } else {
                val nodeSql = sql(node)
                if (sqls.isNotEmpty() && sqls.last() in ops) {
                    sqls[sqls.size - 1] = sqls.last() + " $nodeSql"
                } else {
                    sqls.add(nodeSql)
                }
            }
        }

        val joiner = if (pretty && tooWide(sqls)) "\n" else " "
        return sqls.joinToString(joiner)
    }

    // sqlglot: connector_sql's `getattr(self, f"{node.key}_sql")(node, stack)` dispatch
    protected open fun connectorOpSql(node: Connector, stack: MutableList<kotlin.Any?>): String = when (node) {
        is And -> connectorSql(node, "AND", stack)
        is Or -> connectorSql(node, "OR", stack)
        is Xor -> connectorSql(node, "XOR", stack)
        else -> connectorSql(node, node.key.uppercase(), stack)
    }

    // sqlglot: Generator._replace_line_breaks
    protected fun replaceLineBreaks(string: String): String =
        if (pretty) string.replace("\n", SENTINEL_LINE_BREAK) else string

    // ------------------------------------------------------------------
    // 3. Identifier / literal handling
    // ------------------------------------------------------------------

    // sqlglot: Dialect.can_quote
    protected open fun canQuote(identifier: Identifier, identify: kotlin.Any): Boolean {
        if (identifier.quoted) return true
        if (identify == false || identify == "") return false
        if (identifier.parent is Func) return false
        if (identify == true) return true

        // Base dialect normalization strategy is CASE_INSENSITIVE(lowercase): a name is
        // case-sensitive if it contains any uppercase char (sqlglot: Dialect.case_sensitive).
        val text = identifier.thisArg as? String ?: ""
        val isSafe = text.none { it.isUpperCase() } && SAFE_IDENTIFIER_RE.matches(text)

        return when (identify) {
            "safe" -> isSafe
            "unsafe" -> !isSafe
            else -> throw IllegalArgumentException("Unexpected argument for identify: '$identify'")
        }
    }

    // sqlglot: Generator.identifier_sql
    open fun identifierSql(expression: Identifier): String {
        var text = expression.name
        val lower = text.lowercase()
        val quoted = expression.quoted
        if (normalize && !quoted) text = lower
        text = text.replace(identifierEnd, escapedIdentifierEnd)
        if (
            quoted ||
            canQuote(expression, identify) ||
            lower in reservedKeywords ||
            (!dialectIdentifiersCanStartWithDigit && text.firstOrNull()?.isDigit() == true)
        ) {
            text = "$identifierStart${replaceLineBreaks(text)}$identifierEnd"
        }
        return text
    }

    // sqlglot: Generator.national_sql
    open fun nationalSql(expression: National, prefix: String = "N"): String {
        val string = sql(Literal.string(expression.name))
        return "$prefix$string"
    }

    // sqlglot: Generator.literal_sql
    open fun literalSql(expression: Literal): String {
        val text = when (val t = expression.thisArg) {
            null -> ""
            is String -> t
            else -> t.toString()
        }
        return if (expression.isString) "$quoteStart${escapeStr(text)}$quoteEnd" else text
    }

    // sqlglot: Generator.escape_str (base: STRINGS_SUPPORT_ESCAPED_SEQUENCES=false)
    open fun escapeStr(
        text: String,
        escapeBackslash: Boolean = true,
        delimiter: String? = null,
        escapedDelimiter: String? = null,
        isByteString: Boolean = false,
    ): String {
        var out = text
        val supportsEscapes = if (isByteString) {
            dialectByteStringsSupportEscapedSequences
        } else {
            dialectStringsSupportEscapedSequences
        }
        if (supportsEscapes && escapedSequences.isNotEmpty()) {
            out = buildString {
                for (ch in out) {
                    val escaped = escapedSequences[ch.toString()]
                    if (escaped != null && (escapeBackslash || ch != '\\')) append(escaped)
                    else append(ch)
                }
            }
        }
        val delim = delimiter ?: quoteEnd
        val escapedDelim = escapedDelimiter ?: escapedQuoteEnd
        return replaceLineBreaks(out).replace(delim, escapedDelim)
    }

    // sqlglot: Generator.null_sql
    open fun nullSql(expression: Null): String = "NULL"

    // sqlglot: Generator.boolean_sql
    open fun booleanSql(expression: BooleanNode): String =
        if (expression.thisArg == true) "TRUE" else "FALSE"

    // ------------------------------------------------------------------
    // 4. Clause methods (same order as generator.py)
    // ------------------------------------------------------------------

    // sqlglot: Generator.uncache_sql
    open fun uncacheSql(expression: Uncache): String {
        val table = sql(expression, "this")
        val existsSql = if (expression.args["exists"] == true) " IF EXISTS" else ""
        return "UNCACHE TABLE$existsSql $table"
    }

    // sqlglot: Generator.cache_sql
    open fun cacheSql(expression: Cache): String {
        val lazy = if (expression.args["lazy"] == true) " LAZY" else ""
        val table = sql(expression, "this")
        val optionsList = expression.args["options"] as? List<*>
        val options = if (!optionsList.isNullOrEmpty()) {
            " OPTIONS(${sql(optionsList[0])} = ${sql(optionsList[1])})"
        } else ""
        var exprSql = sql(expression, "expression")
        if (exprSql.isNotEmpty()) exprSql = " AS${sep()}$exprSql"
        return prependCtes(expression, "CACHE$lazy TABLE $table$options$exprSql")
    }

    // sqlglot: Generator.characterset_sql
    open fun charactersetSql(expression: CharacterSet): String {
        val default = if (expression.args["default"] == true) "DEFAULT " else ""
        return "${default}CHARACTER SET=${sql(expression, "this")}"
    }

    // sqlglot: Generator.column_parts
    open fun columnParts(expression: Column): String =
        listOf("catalog", "db", "table", "this")
            .mapNotNull { expression.args[it] }
            .joinToString(".") { sql(it) }

    // sqlglot: Generator.column_sql (base: SUPPORTS_COLUMN_JOIN_MARKS=false)
    open fun columnSql(expression: Column): String {
        var joinMark = if (expression.args["join_mark"] == true) " (+)" else ""
        if (joinMark.isNotEmpty() && !dialectSupportsColumnJoinMarks) {
            joinMark = ""
            unsupported("Outer join syntax using the (+) operator is not supported.")
        }
        return "${columnParts(expression)}$joinMark"
    }

    // sqlglot: Generator.columnposition_sql
    open fun columnpositionSql(expression: ColumnPosition): String {
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        val position = sql(expression, "position")
        return "$position$thisSql"
    }

    // sqlglot: Generator.columndef_sql
    open fun columndefSql(expression: ColumnDef, sep: String = " "): String {
        val column = sql(expression, "this")
        var kind = sql(expression, "kind")
        var constraints = expressions(expression, key = "constraints", sep = " ", flat = true)
        val exists = if (expression.args["exists"] == true) "IF NOT EXISTS " else ""
        kind = if (kind.isNotEmpty()) "$sep$kind" else ""
        constraints = if (constraints.isNotEmpty()) " $constraints" else ""
        var position = sql(expression, "position")
        if (position.isNotEmpty()) position = " $position"

        if (expression.find<ComputedColumnConstraint>() != null && !computedColumnWithType) {
            kind = ""
        }

        return "$exists$column$kind$constraints$position"
    }

    // sqlglot: Generator.columnconstraint_sql
    open fun columnconstraintSql(expression: ColumnConstraint): String {
        val thisSql = sql(expression, "this")
        val kindSql = sql(expression, "kind").trim()
        return if (thisSql.isNotEmpty()) "CONSTRAINT $thisSql $kindSql" else kindSql
    }

    // sqlglot: Generator.computedcolumnconstraint_sql
    open fun computedcolumnconstraintSql(expression: ComputedColumnConstraint): String {
        val thisSql = sql(expression, "this")
        val persisted = when {
            expression.args["not_null"] == true -> " PERSISTED NOT NULL"
            expression.args["persisted"] == true -> " PERSISTED"
            else -> ""
        }
        return "AS $thisSql$persisted"
    }

    // sqlglot: Generator.autoincrementcolumnconstraint_sql (token_sql(AUTO_INCREMENT))
    open fun autoincrementcolumnconstraintSql(expression: AutoIncrementColumnConstraint): String =
        "AUTO_INCREMENT"

    // sqlglot: Generator.compresscolumnconstraint_sql
    open fun compresscolumnconstraintSql(expression: CompressColumnConstraint): String {
        val thisSql = if (expression.thisArg is List<*>) {
            wrap(expressions(expression, key = "this", flat = true))
        } else {
            sql(expression, "this")
        }
        return "COMPRESS $thisSql"
    }

    // sqlglot: Generator.generatedasidentitycolumnconstraint_sql
    open fun generatedasidentitycolumnconstraintSql(
        expression: GeneratedAsIdentityColumnConstraint,
    ): String {
        var thisSql = ""
        val thisVal = expression.args["this"]
        if (thisVal != null) {
            val onNull = if (expression.args["on_null"] == true) " ON NULL" else ""
            thisSql = if (thisVal == true) " ALWAYS" else " BY DEFAULT$onNull"
        }

        var start = expression.args["start"]?.let { sql(it) } ?: ""
        if (start.isNotEmpty()) start = "START WITH $start"
        var increment = expression.args["increment"]?.let { sql(it) } ?: ""
        if (increment.isNotEmpty()) increment = " INCREMENT BY $increment"
        var minvalue = expression.args["minvalue"]?.let { sql(it) } ?: ""
        if (minvalue.isNotEmpty()) minvalue = " MINVALUE $minvalue"
        var maxvalue = expression.args["maxvalue"]?.let { sql(it) } ?: ""
        if (maxvalue.isNotEmpty()) maxvalue = " MAXVALUE $maxvalue"
        val cycle = expression.args["cycle"]
        var cycleSql = ""

        if (cycle != null) {
            cycleSql = "${if (cycle == false) " NO" else ""} CYCLE"
            if (start.isEmpty() && increment.isEmpty()) cycleSql = cycleSql.trim()
        }

        var sequenceOpts = ""
        if (start.isNotEmpty() || increment.isNotEmpty() || cycleSql.isNotEmpty()) {
            sequenceOpts = "$start$increment$minvalue$maxvalue$cycleSql"
            sequenceOpts = " (${sequenceOpts.trim()})"
        }

        var expr = sql(expression, "expression")
        expr = if (expr.isNotEmpty()) "($expr)" else "IDENTITY"

        return "GENERATED$thisSql AS $expr$sequenceOpts"
    }

    // sqlglot: Generator.notnullcolumnconstraint_sql
    open fun notnullcolumnconstraintSql(expression: NotNullColumnConstraint): String =
        "${if (expression.args["allow_null"] == true) "" else "NOT "}NULL"

    // sqlglot: Generator.primarykeycolumnconstraint_sql
    open fun primarykeycolumnconstraintSql(expression: PrimaryKeyColumnConstraint): String {
        val desc = expression.args["desc"]
        if (desc != null) {
            return "PRIMARY KEY${if (desc == true) " DESC" else " ASC"}"
        }
        var options = expressions(expression, key = "options", flat = true, sep = " ")
        if (options.isNotEmpty()) options = " $options"
        return "PRIMARY KEY$options"
    }

    // sqlglot: Generator.uniquecolumnconstraint_sql
    open fun uniquecolumnconstraintSql(expression: UniqueColumnConstraint): String {
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        var indexType = expression.args["index_type"]?.let { sql(it) } ?: ""
        if (indexType.isNotEmpty()) indexType = " USING $indexType"
        var onConflict = sql(expression, "on_conflict")
        if (onConflict.isNotEmpty()) onConflict = " $onConflict"
        val nullsSql = if (expression.args["nulls"] == true) " NULLS NOT DISTINCT" else ""
        var options = expressions(expression, key = "options", flat = true, sep = " ")
        if (options.isNotEmpty()) options = " $options"
        return "UNIQUE$nullsSql$thisSql$indexType$onConflict$options"
    }

    // sqlglot: Generator.createable_sql
    open fun createableSql(expression: Create, locations: Map<GeneratorTables.PropLocation, List<Expression>>): String =
        sql(expression, "this")

    // sqlglot: Generator.create_sql
    open fun createSql(expression: Create): String {
        var kind = sql(expression, "kind")
        kind = inverseCreatableKindMapping[kind] ?: kind

        val properties = expression.args["properties"] as? Properties

        if (
            kind == "TRIGGER" &&
            properties != null &&
            (properties.expressionsArg.firstOrNull() as? TriggerProperties)?.args?.get("constraint") == true
        ) {
            kind = "CONSTRAINT $kind"
        }

        val propertiesLocs =
            if (properties != null) locateProperties(properties)
            else emptyMap()

        val thisSql = createableSql(expression, propertiesLocs)

        var propertiesSql = ""
        val postSchema = propertiesLocs[GeneratorTables.PropLocation.POST_SCHEMA].orEmpty()
        val postWith = propertiesLocs[GeneratorTables.PropLocation.POST_WITH].orEmpty()
        if (postSchema.isNotEmpty() || postWith.isNotEmpty()) {
            val propsAst = Properties(args("expressions" to postSchema + postWith))
            propsAst.parent = expression
            propertiesSql = sql(propsAst)

            propertiesSql = if (postSchema.isNotEmpty()) {
                sep() + propertiesSql
            } else if (!pretty) {
                // Standalone POST_WITH properties need a leading whitespace in non-pretty mode
                " $propertiesSql"
            } else propertiesSql
        }

        val begin = if (expression.args["begin"] == true) " BEGIN" else ""

        var expressionSql = sql(expression, "expression")
        if (expressionSql.isNotEmpty()) {
            expressionSql = "$begin${sep()}$expressionSql"

            val innerExpression = expression.args["expression"]
            if (innerExpression !is MacroOverloads &&
                (createFunctionReturnAs || innerExpression !is Return)
            ) {
                var postaliasPropsSql = ""
                val postAlias = propertiesLocs[GeneratorTables.PropLocation.POST_ALIAS].orEmpty()
                if (postAlias.isNotEmpty()) {
                    postaliasPropsSql = properties(
                        Properties(args("expressions" to postAlias)),
                        wrapped = false,
                    )
                }
                if (postaliasPropsSql.isNotEmpty()) postaliasPropsSql = " $postaliasPropsSql"
                expressionSql = " AS$postaliasPropsSql$expressionSql"
            }
        }

        var postindexPropsSql = ""
        val postIndex = propertiesLocs[GeneratorTables.PropLocation.POST_INDEX].orEmpty()
        if (postIndex.isNotEmpty()) {
            postindexPropsSql = properties(
                Properties(args("expressions" to postIndex)),
                wrapped = false,
                prefix = " ",
            )
        }

        var indexes = expressions(expression, key = "indexes", indent = false, sep = " ")
        if (indexes.isNotEmpty()) indexes = " $indexes"
        val indexSql = indexes + postindexPropsSql

        val replace = if (expression.args["replace"] == true) " OR REPLACE" else ""
        val refresh = if (expression.args["refresh"] == true) " OR REFRESH" else ""
        val unique = if (expression.args["unique"] == true) " UNIQUE" else ""

        val clustered = expression.args["clustered"]
        val clusteredSql = when (clustered) {
            null -> ""
            true -> " CLUSTERED COLUMNSTORE"
            else -> " NONCLUSTERED COLUMNSTORE"
        }

        var postcreatePropsSql = ""
        val postCreate = propertiesLocs[GeneratorTables.PropLocation.POST_CREATE].orEmpty()
        if (postCreate.isNotEmpty()) {
            postcreatePropsSql = properties(
                Properties(args("expressions" to postCreate)),
                sep = " ",
                prefix = " ",
                wrapped = false,
            )
        }

        val modifiers = "$clusteredSql$replace$refresh$unique$postcreatePropsSql"

        var postexpressionPropsSql = ""
        val postExpression = propertiesLocs[GeneratorTables.PropLocation.POST_EXPRESSION].orEmpty()
        if (postExpression.isNotEmpty()) {
            postexpressionPropsSql = properties(
                Properties(args("expressions" to postExpression)),
                sep = " ",
                prefix = " ",
                wrapped = false,
            )
        }

        val concurrently = if (expression.args["concurrently"] == true) " CONCURRENTLY" else ""
        val existsSql = if (expression.args["exists"] == true) " IF NOT EXISTS" else ""
        val noSchemaBinding =
            if (expression.args["no_schema_binding"] == true) " WITH NO SCHEMA BINDING" else ""

        var clone = sql(expression, "clone")
        if (clone.isNotEmpty()) clone = " $clone"

        val propertiesExpression = if (kind in expressionPrecedesPropertiesCreatables) {
            "$expressionSql$propertiesSql"
        } else {
            "$propertiesSql$expressionSql"
        }

        val createSql =
            "CREATE$modifiers $kind$concurrently$existsSql $thisSql$propertiesExpression$postexpressionPropsSql$indexSql$noSchemaBinding$clone"
        return prependCtes(expression, createSql)
    }

    // sqlglot: Generator.clone_sql
    open fun cloneSql(expression: Clone): String {
        val thisSql = sql(expression, "this")
        val shallow = if (expression.args["shallow"] == true) "SHALLOW " else ""
        val keyword = if (expression.args["copy"] == true && supportsTableCopy) "COPY" else "CLONE"
        return "$shallow$keyword $thisSql"
    }

    // sqlglot: Generator.describe_sql
    open fun describeSql(expression: Describe): String {
        var style = expression.args["style"]?.let { sql(it) } ?: ""
        if (style.isNotEmpty()) style = " $style"
        var partition = sql(expression, "partition")
        if (partition.isNotEmpty()) partition = " $partition"
        var format = sql(expression, "format")
        if (format.isNotEmpty()) format = " $format"
        val asJson = if (expression.args["as_json"] == true) " AS JSON" else ""
        return "DESCRIBE$style$format ${sql(expression, "this")}$partition$asJson"
    }

    // sqlglot: Generator.heredoc_sql
    open fun heredocSql(expression: Heredoc): String {
        val tag = sql(expression, "tag")
        return "\$$tag\$${sql(expression, "this")}\$$tag\$"
    }

    // sqlglot: Generator.with_sql
    open fun withSql(expression: With): String {
        val sqlText = expressions(expression, flat = true)
        val recursive =
            if (cteRecursiveKeywordRequired && expression.args["recursive"] == true) "RECURSIVE " else ""
        var search = sql(expression, "search")
        if (search.isNotEmpty()) search = " $search"
        return "WITH $recursive$sqlText$search"
    }

    // sqlglot: Generator.cte_sql
    open fun cteSql(expression: CTE): String {
        val alias = expression.args["alias"] as? Expression
        alias?.addComments(expression.popComments())

        val aliasSql = sql(expression, "alias")

        val materializedArg = expression.args["materialized"]
        val materialized = when (materializedArg) {
            false -> "NOT MATERIALIZED "
            null -> ""
            else -> if (isTruthyArg(materializedArg)) "MATERIALIZED " else ""
        }

        var keyExpressions = expressions(expression, key = "key_expressions", flat = true)
        if (keyExpressions.isNotEmpty()) keyExpressions = " USING KEY ($keyExpressions)"

        return "$aliasSql$keyExpressions AS $materialized${wrap(expression)}"
    }

    // sqlglot: Generator.tablealias_sql
    open fun tablealiasSql(expression: TableAlias): String {
        var alias = sql(expression, "this")
        var columns = expressions(expression, key = "columns", flat = true)
        columns = if (columns.isNotEmpty()) "($columns)" else ""

        if (
            columns.isNotEmpty() &&
            !supportsTableAliasColumns &&
            !(supportsNamedCteColumns && expression.parent is CTE)
        ) {
            columns = ""
            unsupported("Named columns are not supported in table alias.")
        }

        if (alias.isEmpty() && !dialectUnnestColumnOnly) {
            alias = nextName()
        }

        return "$alias$columns"
    }

    // sqlglot: Generator.rawstring_sql (base: no backslash escapes)
    open fun rawstringSql(expression: RawString): String {
        val string = escapeStr(expression.thisArg as? String ?: "", escapeBackslash = false)
        return "$quoteStart$string$quoteEnd"
    }

    // sqlglot: Generator.datatypeparam_sql
    open fun datatypeparamSql(expression: DataTypeParam): String {
        val thisSql = sql(expression, "this")
        var specifier = sql(expression, "expression")
        specifier = if (specifier.isNotEmpty() && dataTypeSpecifiersAllowed) " $specifier" else ""
        return "$thisSql$specifier"
    }

    // sqlglot: Generator.datatype_sql (base: TYPE_PARAM_SETTINGS/UNSUPPORTED_TYPES/TYPE_MAPPING empty)
    open fun datatypeSql(expression: DataType): String {
        var nested = ""
        var values = ""

        val exprNested = expression.args["nested"] == true
        val typeValue = expression.thisArg

        val interior = if (exprNested && pretty) {
            expressions(expression, dynamic = true, newLine = true, skipFirst = true, skipLast = true)
        } else {
            expressions(expression, flat = true)
        }

        var typeSql: String
        if (typeValue == DType.USERDEFINED && isTruthyArg(expression.args["kind"])) {
            typeSql = sql(expression, "kind")
        } else if (typeValue == DType.CHARACTER_SET) {
            return "CHAR CHARACTER SET ${sql(expression, "kind")}"
        } else {
            // sqlglot: `self.TYPE_MAPPING.get(type_value, type_value.value)`
            typeSql = if (typeValue is DType) (typeMapping[typeValue] ?: typeValue.value) else sql(typeValue)
        }

        if (interior.isNotEmpty()) {
            if (exprNested) {
                nested = "${structDelimiter.first}$interior${structDelimiter.second}"
                if (expression.args["values"] != null) {
                    val delimiters = if (typeValue == DType.ARRAY) "[" to "]" else "(" to ")"
                    values = expressions(expression, key = "values", flat = true)
                    values = "${delimiters.first}$values${delimiters.second}"
                }
            } else if (typeValue == DType.INTERVAL) {
                nested = " $interior"
            } else {
                nested = "($interior)"
            }
        }

        typeSql = "$typeSql$nested$values"
        if (tzToWithTimeZone && (typeValue == DType.TIMETZ || typeValue == DType.TIMESTAMPTZ)) {
            typeSql = "$typeSql WITH TIME ZONE"
        }

        val collate = sql(expression, "collate")
        if (collate.isNotEmpty()) typeSql = "$typeSql COLLATE $collate"

        return typeSql
    }

    // sqlglot: Generator.directory_sql
    open fun directorySql(expression: Directory): String {
        val local = if (expression.args["local"] == true) "LOCAL " else ""
        var rowFormat = sql(expression, "row_format")
        if (rowFormat.isNotEmpty()) rowFormat = " $rowFormat"
        return "${local}DIRECTORY ${sql(expression, "this")}$rowFormat"
    }

    // sqlglot: Generator.delete_sql
    open fun deleteSql(expression: Delete): String {
        val hint = sql(expression, "hint")
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " FROM $thisSql"
        var using = expressions(expression, key = "using")
        if (using.isNotEmpty()) using = " USING $using"
        var cluster = sql(expression, "cluster")
        if (cluster.isNotEmpty()) cluster = " $cluster"
        val where = sql(expression, "where")
        val returning = sql(expression, "returning")
        val order = sql(expression, "order")
        val limit = sql(expression, "limit")
        var tables = expressions(expression, key = "tables")
        if (tables.isNotEmpty()) tables = " $tables"
        val expressionSql = if (returningEnd) {
            "$thisSql$using$cluster$where$returning$order$limit"
        } else {
            "$returning$thisSql$using$cluster$where$order$limit"
        }
        return prependCtes(expression, "DELETE$hint$tables$expressionSql")
    }

    // sqlglot: Generator.drop_sql
    open fun dropSql(expression: Drop): String {
        val thisSql = sql(expression, "this")
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) exprs = " ($exprs)"
        var kind = expression.args["kind"] as? String ?: ""
        kind = inverseCreatableKindMapping[kind] ?: kind
        val iceberg =
            if (expression.args["iceberg"] == true && supportsDropAlterIcebergProperty) " ICEBERG" else ""
        val existsSql = if (expression.args["exists"] == true) " IF EXISTS " else " "
        val concurrentlySql = if (expression.args["concurrently"] == true) " CONCURRENTLY" else ""
        var onCluster = sql(expression, "cluster")
        if (onCluster.isNotEmpty()) onCluster = " $onCluster"
        val temporary = if (expression.args["temporary"] == true) " TEMPORARY" else ""
        val materialized = if (expression.args["materialized"] == true) " MATERIALIZED" else ""
        val cascade = if (expression.args["cascade"] == true) " CASCADE" else ""
        val restrict = if (expression.args["restrict"] == true) " RESTRICT" else ""
        val constraints = if (expression.args["constraints"] == true) " CONSTRAINTS" else ""
        val purge = if (expression.args["purge"] == true) " PURGE" else ""
        val sync = if (expression.args["sync"] == true) " SYNC" else ""
        return "DROP$temporary$materialized$iceberg $kind$concurrentlySql$existsSql$thisSql$onCluster$exprs$cascade$restrict$constraints$purge$sync"
    }

    // sqlglot: Generator.set_operation
    open fun setOperation(expression: SetOperation): String {
        val opName = expression::class.simpleName!!.uppercase()

        var distinct = expression.args["distinct"] as? Boolean
        if (
            distinct == false &&
            (expression is Except || expression is Intersect) &&
            !exceptIntersectSupportAllClause
        ) {
            unsupported("$opName ALL is not supported")
        }

        // sqlglot: Dialect.SET_OP_DISTINCT_BY_DEFAULT (base: all true)
        val defaultDistinct = true

        if (distinct == null) distinct = defaultDistinct

        val distinctOrAll = if (distinct == defaultDistinct) "" else if (distinct) " DISTINCT" else " ALL"

        var sideKind = listOf(expression.text("side").uppercase(), expression.text("kind").uppercase())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (sideKind.isNotEmpty()) sideKind = "$sideKind "

        val byName = if (expression.args["by_name"] == true) " BY NAME" else ""
        var on = expressions(expression, key = "on", flat = true)
        if (on.isNotEmpty()) on = " ON ($on)"

        return "$sideKind$opName$distinctOrAll$byName$on"
    }

    // sqlglot: Generator.set_operations (base: SET_OP_MODIFIERS=true)
    open fun setOperations(expression: SetOperation): String {
        val sqls = mutableListOf<String>()
        val stack = mutableListOf<kotlin.Any?>(expression)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is SetOperation) {
                stack.add(node.args["expression"])
                stack.add(maybeComment(setOperation(node), comments = node.comments, separated = true))
                stack.add(node.args["this"])
            } else {
                sqls.add(sql(node))
            }
        }

        var thisSql = sqls.joinToString(sep())
        thisSql = queryModifiers(expression, thisSql)
        return prependCtes(expression, thisSql)
    }

    // sqlglot: Generator.fetch_sql
    open fun fetchSql(expression: Fetch): String {
        var direction = expression.args["direction"]?.let { sql(it) } ?: ""
        if (direction.isNotEmpty()) direction = " $direction"
        var count = sql(expression, "count")
        if (count.isNotEmpty()) count = " $count"
        var limitOptions = sql(expression, "limit_options")
        if (limitOptions.isEmpty()) limitOptions = " ROWS ONLY"
        return "${seg("FETCH")}$direction$count$limitOptions"
    }

    // sqlglot: Generator.limitoptions_sql
    open fun limitoptionsSql(expression: LimitOptions): String {
        val percent = if (expression.args["percent"] == true) " PERCENT" else ""
        val rows = if (expression.args["rows"] == true) " ROWS" else ""
        var withTies = if (expression.args["with_ties"] == true) " WITH TIES" else ""
        if (withTies.isEmpty() && rows.isNotEmpty()) withTies = " ONLY"
        return "$percent$rows$withTies"
    }

    // sqlglot: Generator.filter_sql (base: AGGREGATE_FILTER_SUPPORTED=true)
    open fun filterSql(expression: Filter): String {
        val thisSql = sql(expression, "this")
        val where = sql(expression, "expression").trim()
        return "$thisSql FILTER($where)"
    }

    // sqlglot: Generator.hint_sql
    open fun hintSql(expression: Hint): String {
        if (!queryHints) {
            unsupported("Hints are not supported")
            return ""
        }
        return " /*+ ${expressions(expression, sep = queryHintSep).trim()} */"
    }

    // sqlglot: Generator.indexparameters_sql
    open fun indexparametersSql(expression: IndexParameters): String {
        var using = sql(expression, "using")
        if (using.isNotEmpty()) using = " USING $using"
        var columns = expressions(expression, key = "columns", flat = true)
        if (columns.isNotEmpty()) columns = "($columns)"
        var partitionBy = expressions(expression, key = "partition_by", flat = true)
        if (partitionBy.isNotEmpty()) partitionBy = " PARTITION BY $partitionBy"
        val where = sql(expression, "where")
        var include = expressions(expression, key = "include", flat = true)
        if (include.isNotEmpty()) include = " INCLUDE ($include)"
        var withStorage = expressions(expression, key = "with_storage", flat = true)
        if (withStorage.isNotEmpty()) withStorage = " WITH ($withStorage)"
        var tablespace = sql(expression, "tablespace")
        if (tablespace.isNotEmpty()) tablespace = " USING INDEX TABLESPACE $tablespace"
        var on = sql(expression, "on")
        if (on.isNotEmpty()) on = " ON $on"
        return "$using$columns$include$withStorage$tablespace$partitionBy$where$on"
    }

    // sqlglot: Generator.index_sql
    open fun indexSql(expression: Index): String {
        val unique = if (expression.args["unique"] == true) "UNIQUE " else ""
        val primary = if (expression.args["primary"] == true) "PRIMARY " else ""
        val amp = if (expression.args["amp"] == true) "AMP " else ""
        var name = sql(expression, "this")
        if (name.isNotEmpty()) name = "$name "
        var table = sql(expression, "table")
        if (table.isNotEmpty()) table = "$indexOn $table"
        val index = if (table.isEmpty()) "INDEX " else ""
        val params = sql(expression, "params")
        return "$unique$primary$amp$index$name$table$params"
    }

    // sqlglot: Generator.partition_sql
    open fun partitionSql(expression: Partition): String {
        val partitionKeyword = if (expression.args["subpartition"] == true) "SUBPARTITION" else "PARTITION"
        return "$partitionKeyword(${expressions(expression, flat = true)})"
    }

    // sqlglot: Generator.properties_sql
    open fun propertiesSql(expression: Properties): String {
        val rootProperties = mutableListOf<Expression>()
        val withProperties = mutableListOf<Expression>()

        for (p in expression.expressionsArg) {
            if (p !is Expression) continue
            when (propertiesLocation[p::class]) {
                GeneratorTables.PropLocation.POST_WITH -> withProperties.add(p)
                GeneratorTables.PropLocation.POST_SCHEMA -> rootProperties.add(p)
                else -> {}
            }
        }

        val rootPropsAst = Properties(args("expressions" to rootProperties))
        rootPropsAst.parent = expression.parent
        val withPropsAst = Properties(args("expressions" to withProperties))
        withPropsAst.parent = expression.parent

        val rootProps = rootProperties(rootPropsAst)
        var withProps = withProperties(withPropsAst)

        if (rootProps.isNotEmpty() && withProps.isNotEmpty() && !pretty) {
            withProps = " $withProps"
        }

        return rootProps + withProps
    }

    // sqlglot: Generator.root_properties
    open fun rootProperties(properties: Properties): String =
        if (properties.expressionsArg.isNotEmpty()) expressions(properties, indent = false, sep = " ") else ""

    // sqlglot: Generator.properties
    open fun properties(
        properties: Properties,
        prefix: String = "",
        sep: String = ", ",
        suffix: String = "",
        wrapped: Boolean = true,
    ): String {
        if (properties.expressionsArg.isNotEmpty()) {
            var exprs = expressions(properties, sep = sep, indent = false)
            if (exprs.isNotEmpty()) {
                if (wrapped) exprs = wrap(exprs)
                return "$prefix${if (prefix.trim().isNotEmpty()) " " else ""}$exprs$suffix"
            }
        }
        return ""
    }

    // sqlglot: Generator.with_properties
    open fun withProperties(properties: Properties): String =
        properties(properties, prefix = seg(withPropertiesPrefix, sep = ""))

    // sqlglot: Generator.locate_properties
    open fun locateProperties(properties: Properties): Map<GeneratorTables.PropLocation, List<Expression>> {
        val locations = LinkedHashMap<GeneratorTables.PropLocation, MutableList<Expression>>()
        for (p in properties.expressionsArg) {
            if (p !is Expression) continue
            val loc = propertiesLocation[p::class]
            if (loc == null) {
                // Python's PROPERTIES_LOCATION covers every Property; an absent entry here
                // means an unported property class.
                throw UnsupportedError("Property location unknown for ${p::class.simpleName}")
            }
            if (loc != GeneratorTables.PropLocation.UNSUPPORTED) {
                locations.getOrPut(loc) { mutableListOf() }.add(p)
            } else {
                unsupported("Unsupported property ${p.key}")
            }
        }
        return locations
    }

    // sqlglot: Generator.likeproperty_sql (base: SUPPORTS_CREATE_TABLE_LIKE=true)
    open fun likepropertySql(expression: LikeProperty): String {
        var options = expression.expressionsArg
            .filterIsInstance<Expression>()
            .joinToString(" ") { "${it.name} ${sql(it, "value")}" }
        if (options.isNotEmpty()) options = " $options"
        var like = "LIKE ${sql(expression, "this")}$options"
        if (likePropertyInsideSchema && expression.parent !is Schema) like = "($like)"
        return like
    }

    // sqlglot: Generator.lockingproperty_sql
    open fun lockingpropertySql(expression: LockingProperty): String {
        val kind = expression.args["kind"] ?: ""
        val thisSql = if (expression.args["this"] != null) " ${sql(expression, "this")}" else ""
        var forOrIn = expression.args["for_or_in"] as? String ?: ""
        if (forOrIn.isNotEmpty()) forOrIn = " $forOrIn"
        val lockType = expression.args["lock_type"] ?: ""
        val override = if (expression.args["override"] == true) " OVERRIDE" else ""
        return "LOCKING $kind$thisSql$forOrIn $lockType$override"
    }

    // sqlglot: Generator.withdataproperty_sql
    open fun withdatapropertySql(expression: WithDataProperty): String {
        val dataSql = "WITH ${if (expression.args["no"] == true) "NO " else ""}DATA"
        val statistics = expression.args["statistics"]
        var statisticsSql = ""
        if (statistics != null) {
            statisticsSql = " AND ${if (statistics == true) "" else "NO "}STATISTICS"
        }
        return "$dataSql$statisticsSql"
    }

    // sqlglot: Generator.insert_sql
    open fun insertSql(expression: Insert): String {
        val hint = sql(expression, "hint")
        val overwrite = expression.args["overwrite"] == true

        var thisKeyword = if (expression.args["this"] is Directory) {
            if (overwrite) " OVERWRITE" else " INTO"
        } else {
            if (overwrite) insertOverwrite else " INTO"
        }

        var stored = sql(expression, "stored")
        if (stored.isNotEmpty()) stored = " $stored"
        var alternative = expression.args["alternative"] as? String ?: ""
        if (alternative.isNotEmpty()) alternative = " OR $alternative"
        val ignore = if (expression.args["ignore"] == true) " IGNORE" else ""
        if (expression.args["is_function"] == true) thisKeyword = "$thisKeyword FUNCTION"
        val thisSql = "$thisKeyword ${sql(expression, "this")}"

        val exists = if (expression.args["exists"] == true) " IF EXISTS" else ""
        var where = sql(expression, "where")
        if (where.isNotEmpty()) where = "${sep()}REPLACE WHERE $where"
        var expressionSql = "${sep()}${sql(expression, "expression")}"
        var onConflict = sql(expression, "conflict")
        if (onConflict.isNotEmpty()) onConflict = " $onConflict"
        val byName = if (expression.args["by_name"] == true) " BY NAME" else ""
        val defaultValues = if (expression.args["default"] == true) "DEFAULT VALUES" else ""
        val returning = sql(expression, "returning")

        expressionSql = if (returningEnd) {
            "$expressionSql$onConflict$defaultValues$returning"
        } else {
            "$returning$expressionSql$onConflict"
        }

        var partitionBy = sql(expression, "partition")
        if (partitionBy.isNotEmpty()) partitionBy = " $partitionBy"
        var settings = sql(expression, "settings")
        if (settings.isNotEmpty()) settings = " $settings"

        var source = sql(expression, "source")
        if (source.isNotEmpty()) source = "TABLE $source"

        val sqlText =
            "INSERT$hint$alternative$ignore$thisSql$stored$byName$exists$partitionBy$settings$where$expressionSql$source"
        return prependCtes(expression, sqlText)
    }

    // sqlglot: Generator.kill_sql
    open fun killSql(expression: Kill): String {
        var kind = expression.args["kind"]?.let { sql(it) } ?: ""
        if (kind.isNotEmpty()) kind = " $kind"
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        return "KILL$kind$thisSql"
    }

    // sqlglot: Generator.objectidentifier_sql
    open fun objectidentifierSql(expression: ObjectIdentifier): String = expression.name

    // sqlglot: Generator.inoutcolumnconstraint_sql
    open fun inoutcolumnconstraintSql(expression: InOutColumnConstraint): String {
        val input = expression.args["input_"] == true
        val output = expression.args["output"] == true
        val variadic = expression.args["variadic"] == true

        // VARIADIC is mutually exclusive with IN/OUT/INOUT
        if (variadic) return "VARIADIC"

        if (input && output) return "IN${inoutSeparator}OUT"
        if (input) return "IN"
        if (output) return "OUT"

        return ""
    }

    // sqlglot: Generator.truncatetable_sql
    open fun truncatetableSql(expression: TruncateTable): String {
        val target = if (expression.args["is_database"] == true) "DATABASE" else "TABLE"
        val tables = " ${expressions(expression)}"

        val exists = if (expression.args["exists"] == true) " IF EXISTS" else ""

        var onCluster = sql(expression, "cluster")
        if (onCluster.isNotEmpty()) onCluster = " $onCluster"

        var identity = sql(expression, "identity")
        if (identity.isNotEmpty()) identity = " $identity IDENTITY"

        var option = sql(expression, "option")
        if (option.isNotEmpty()) option = " $option"

        var partition = sql(expression, "partition")
        if (partition.isNotEmpty()) partition = " $partition"

        return "TRUNCATE $target$exists$tables$onCluster$identity$option$partition"
    }

    // sqlglot: Generator.triggerproperties_sql
    open fun triggerpropertiesSql(expression: TriggerProperties): String {
        val timing = expression.args["timing"] as? String ?: ""
        val events = (expression.args["events"] as? List<*>).orEmpty()
            .joinToString(" OR ") { sql(it) }
        val timingEvents = if (timing.isNotEmpty() || events.isNotEmpty()) {
            "$timing $events".trim()
        } else ""

        val parts = mutableListOf(timingEvents, "ON", sql(expression, "table"))

        // Python walrus `if arg := ...` checks truthiness — False/null args are skipped
        val referencedTable = expression.args["referenced_table"]
        if (referencedTable != null && referencedTable != false) {
            parts.add("FROM")
            parts.add(sql(referencedTable))
        }

        (expression.args["deferrable"] as? String)?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        (expression.args["initially"] as? String)?.takeIf { it.isNotEmpty() }
            ?.let { parts.add("INITIALLY $it") }
        expression.args["referencing"]?.takeIf { it != false }?.let { parts.add(sql(it)) }
        (expression.args["for_each"] as? String)?.takeIf { it.isNotEmpty() }
            ?.let { parts.add("FOR EACH $it") }
        expression.args["when"]?.takeIf { it != false }?.let { parts.add("WHEN (${sql(it)})") }

        parts.add(sql(expression, "execute"))

        return parts.joinToString(sep())
    }

    // sqlglot: Generator.triggerreferencing_sql
    open fun triggerreferencingSql(expression: TriggerReferencing): String {
        val parts = mutableListOf<String>()

        expression.args["old"]?.let { parts.add("OLD TABLE AS ${sql(it)}") }
        expression.args["new"]?.let { parts.add("NEW TABLE AS ${sql(it)}") }

        return "REFERENCING ${parts.joinToString(" ")}"
    }

    // sqlglot: Generator.triggerevent_sql
    open fun triggereventSql(expression: TriggerEvent): String {
        val columns = expression.args["columns"] as? List<*>
        if (!columns.isNullOrEmpty()) {
            return "${expression.name} OF ${expressions(expression, key = "columns", flat = true)}"
        }

        return sql(expression, "this")
    }

    // sqlglot: Generator.json_sql
    open fun jsonSql(expression: JSON): String {
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"

        val with_ = expression.args["with_"]
        val withSql = when {
            !expression.args.containsKey("with_") || with_ == null -> ""
            with_ == false -> " WITHOUT"
            else -> " WITH"
        }

        val uniqueSql = if (expression.args["unique"] == true) " UNIQUE KEYS" else ""

        return "JSON$thisSql$withSql$uniqueSql"
    }

    // sqlglot: Generator.SUPPORTS_EXPLODING_PROJECTIONS
    open val supportsExplodingProjections: Boolean get() = true

    // sqlglot: Generator.explodinggenerateseries_sql
    open fun explodinggenerateseriesSql(expression: ExplodingGenerateSeries): String {
        val generateSeries = GenerateSeries(
            expression.args.entries.associate { (k, v) -> k to v }
        )

        var parent = expression.parent
        if (parent is Alias || parent is TableAlias) {
            parent = parent.parent
        }

        if (supportsExplodingProjections && parent !is Table && parent !is Unnest) {
            return sql(Unnest(args("expressions" to listOf(generateSeries))))
        }

        if (parent is Select) {
            unsupported("GenerateSeries projection unnesting is not supported.")
        }

        return sql(generateSeries)
    }

    // sqlglot: Generator.partitionboundspec_sql
    open fun partitionboundspecSql(expression: PartitionBoundSpec): String {
        val thisArg = expression.args["this"]
        if (thisArg is List<*>) {
            return "IN (${expressions(expression, key = "this", flat = true)})"
        }
        if (thisArg != null) {
            val modulus = sql(expression, "this")
            val remainder = sql(expression, "expression")
            return "WITH (MODULUS $modulus, REMAINDER $remainder)"
        }

        val fromExpressions = expressions(expression, key = "from_expressions", flat = true)
        val toExpressions = expressions(expression, key = "to_expressions", flat = true)
        return "FROM ($fromExpressions) TO ($toExpressions)"
    }

    // sqlglot: Generator.partitionedofproperty_sql
    open fun partitionedofpropertySql(expression: PartitionedOfProperty): String {
        val thisSql = sql(expression, "this")

        val forValuesOrDefault = expression.args["expression"]
        val suffix = if (forValuesOrDefault is PartitionBoundSpec) {
            " FOR VALUES ${sql(forValuesOrDefault)}"
        } else {
            " DEFAULT"
        }

        return "PARTITION OF $thisSql$suffix"
    }

    // sqlglot: Generator.pseudotype_sql
    open fun pseudotypeSql(expression: PseudoType): String = expression.name

    // sqlglot: Generator.overlay_sql
    open fun overlaySql(expression: Overlay): String {
        val thisSql = sql(expression, "this")
        val expr = sql(expression, "expression")
        val fromSql = sql(expression, "from_")
        var forSql = sql(expression, "for_")
        if (forSql.isNotEmpty()) forSql = " FOR $forSql"

        return "OVERLAY($thisSql PLACING $expr FROM $fromSql$forSql)"
    }

    // sqlglot: Generator.xmlelement_sql
    open fun xmlelementSql(expression: XMLElement): String {
        val prefix = if (expression.args["evalname"] == true) "EVALNAME" else "NAME"
        val name = "$prefix ${sql(expression, "this")}"
        return func("XMLELEMENT", name, *expression.expressionsArg.toTypedArray())
    }

    // sqlglot: Generator.xmltable_sql
    open fun xmltableSql(expression: XMLTable): String {
        val thisSql = sql(expression, "this")
        var namespaces = expressions(expression, key = "namespaces")
        namespaces = if (namespaces.isNotEmpty()) "XMLNAMESPACES($namespaces), " else ""
        var passing = expressions(expression, key = "passing")
        passing = if (passing.isNotEmpty()) "${sep()}PASSING${seg(passing)}" else ""
        var columns = expressions(expression, key = "columns")
        columns = if (columns.isNotEmpty()) "${sep()}COLUMNS${seg(columns)}" else ""
        val byRef = if (expression.args["by_ref"] == true) "${sep()}RETURNING SEQUENCE BY REF" else ""
        return "XMLTABLE(${sep("")}${indent(namespaces + thisSql + passing + byRef + columns)}${seg(")", sep = "")}"
    }

    // sqlglot: Generator.xmlnamespace_sql
    open fun xmlnamespaceSql(expression: XMLNamespace): String {
        val thisSql = sql(expression, "this")
        return if (expression.args["this"] is Alias) thisSql else "DEFAULT $thisSql"
    }

    // sqlglot: Generator.recursivewithsearch_sql
    open fun recursivewithsearchSql(expression: RecursiveWithSearch): String {
        val kind = sql(expression, "kind")
        val thisSql = sql(expression, "this")
        val setSql = sql(expression, "expression")
        var using = sql(expression, "using")
        if (using.isNotEmpty()) using = " USING $using"

        val kindSql = if (kind == "CYCLE") kind else "SEARCH $kind FIRST BY"

        return "$kindSql $thisSql SET $setSql$using"
    }

    // sqlglot: Generator.onconflict_sql
    open fun onconflictSql(expression: OnConflict): String {
        val conflict = if (expression.args["duplicate"] == true) "ON DUPLICATE KEY" else "ON CONFLICT"
        var constraint = sql(expression, "constraint")
        if (constraint.isNotEmpty()) constraint = " ON CONSTRAINT $constraint"
        var conflictKeys = expressions(expression, key = "conflict_keys", flat = true)
        if (conflictKeys.isNotEmpty()) conflictKeys = "($conflictKeys)"
        val indexPredicate = sql(expression, "index_predicate")
        conflictKeys = "$conflictKeys$indexPredicate "
        val action = sql(expression, "action")
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) {
            val setKeyword = if (duplicateKeyUpdateWithSet) "SET " else ""
            exprs = " $setKeyword$exprs"
        }
        val where = sql(expression, "where")
        return "$conflict$constraint$conflictKeys$action$exprs$where"
    }

    // sqlglot: Generator.returning_sql
    open fun returningSql(expression: Returning): String =
        "${seg("RETURNING")} ${expressions(expression, flat = true)}"

    // sqlglot: Generator.rowformatdelimitedproperty_sql
    open fun rowformatdelimitedpropertySql(expression: RowFormatDelimitedProperty): String {
        var fields = sql(expression, "fields")
        if (fields.isNotEmpty()) fields = " FIELDS TERMINATED BY $fields"
        var escaped = sql(expression, "escaped")
        if (escaped.isNotEmpty()) escaped = " ESCAPED BY $escaped"
        var items = sql(expression, "collection_items")
        if (items.isNotEmpty()) items = " COLLECTION ITEMS TERMINATED BY $items"
        var keys = sql(expression, "map_keys")
        if (keys.isNotEmpty()) keys = " MAP KEYS TERMINATED BY $keys"
        var lines = sql(expression, "lines")
        if (lines.isNotEmpty()) lines = " LINES TERMINATED BY $lines"
        var nullSql = sql(expression, "null")
        if (nullSql.isNotEmpty()) nullSql = " NULL DEFINED AS $nullSql"
        return "ROW FORMAT DELIMITED$fields$escaped$items$keys$lines$nullSql"
    }

    // sqlglot: Generator.table_parts
    open fun tableParts(expression: Table): String =
        listOf("catalog", "db", "this")
            .mapNotNull { expression.args[it] }
            .joinToString(".") { sql(it) }

    // sqlglot: Generator.table_sql
    open fun tableSql(expression: Table, sep: String = " AS "): String {
        var table = tableParts(expression)
        val only = if (expression.args["only"] == true) "ONLY " else ""
        var partition = sql(expression, "partition")
        if (partition.isNotEmpty()) partition = " $partition"
        var version = sql(expression, "version")
        if (version.isNotEmpty()) version = " $version"
        var alias = sql(expression, "alias")
        if (alias.isNotEmpty()) alias = "$sep$alias"

        val sample = sql(expression, "sample")
        var postAlias = ""
        var preAlias = ""

        if (dialectAliasPostTablesample) preAlias = sample else postAlias = sample
        if (dialectAliasPostVersion) preAlias += version else postAlias += version

        var hints = expressions(expression, key = "hints", sep = " ")
        hints = if (hints.isNotEmpty() && tableHints) " $hints" else ""
        val pivots = expressions(expression, key = "pivots", sep = "", flat = true)
        val joins = indent(
            expressions(expression, key = "joins", sep = "", flat = true),
            skipFirst = true,
        )
        val laterals = expressions(expression, key = "laterals", sep = "")

        var fileFormat = sql(expression, "format")
        var pattern = sql(expression, "pattern")
        if (fileFormat.isNotEmpty()) {
            pattern = if (pattern.isNotEmpty()) ", PATTERN => $pattern" else ""
            fileFormat = " (FILE_FORMAT => $fileFormat$pattern)"
        } else if (pattern.isNotEmpty()) {
            fileFormat = " (PATTERN => $pattern)"
        }

        var ordinality = ""
        if (expression.args["ordinality"] == true) {
            ordinality = " WITH ORDINALITY$alias"
            alias = ""
        }

        val whenSql = sql(expression, "when")
        if (whenSql.isNotEmpty()) table = "$table $whenSql"

        var changes = sql(expression, "changes")
        if (changes.isNotEmpty()) changes = " $changes"

        val rowsFrom = expressions(expression, key = "rows_from")
        if (rowsFrom.isNotEmpty()) table = "ROWS FROM ${wrap(rowsFrom)}"

        val indexedArg = expression.args["indexed"]
        val indexed = when {
            indexedArg == null -> ""
            isTruthyArg(indexedArg) -> " INDEXED BY ${sql(indexedArg)}"
            else -> " NOT INDEXED"
        }

        return "$only$table$changes$partition$fileFormat$preAlias$alias$indexed$hints$pivots$postAlias$joins$laterals$ordinality"
    }

    // sqlglot: Generator.tablesample_sql
    // sqlglot: Generator.indextablehint_sql
    open fun indextablehintSql(expression: IndexTableHint): String {
        val thisSql = "${sql(expression, "this")} INDEX"
        var target = sql(expression, "target")
        target = if (target.isNotEmpty()) " FOR $target" else ""
        return "$thisSql$target (${expressions(expression, flat = true)})"
    }

    open fun tablesampleSql(expression: TableSample, tablesampleKeyword: String? = null): String {
        var method = sql(expression, "method")
        method = if (method.isNotEmpty() && tablesampleWithMethod) "$method " else ""
        val numerator = sql(expression, "bucket_numerator")
        val denominator = sql(expression, "bucket_denominator")
        var field = sql(expression, "bucket_field")
        if (field.isNotEmpty()) field = " ON $field"
        val bucket = if (numerator.isNotEmpty()) "BUCKET $numerator OUT OF $denominator$field" else ""
        var seed = sql(expression, "seed")
        if (seed.isNotEmpty()) seed = " $tablesampleSeedKeyword ($seed)"

        var size = sql(expression, "size")
        if (size.isNotEmpty() && tablesampleSizeIsRows) size = "$size ROWS"

        var percent = sql(expression, "percent")
        if (percent.isNotEmpty() && !dialectTablesampleSizeIsPercent) percent = "$percent PERCENT"

        var expr = "$bucket$percent$size"
        if (tablesampleRequiresParens) expr = "($expr)"

        return " ${tablesampleKeyword ?: tablesampleKeywords} $method$expr$seed"
    }

    // sqlglot: Generator.pivot_sql (without _pivot_in_value_aliases — base-dialect
    // round trips don't rewrite IN-value aliases)
    open fun pivotSql(expression: Pivot): String {
        val exprs = expressions(expression, flat = true)
        val unpivot = isTruthyArg(expression.args["unpivot"])
        val direction = if (unpivot) "UNPIVOT" else "PIVOT"

        val group = sql(expression, "group")

        if (expression.args["this"] != null) {
            val thisSql = sql(expression, "this")
            val sqlText = if (exprs.isEmpty()) {
                "UNPIVOT $thisSql"
            } else {
                val on = "${seg("ON")} $exprs"
                var into = sql(expression, "into")
                if (into.isNotEmpty()) into = "${seg("INTO")} $into"
                var using = expressions(expression, key = "using", flat = true)
                if (using.isNotEmpty()) using = "${seg("USING")} $using"
                "$direction $thisSql$on$into$using$group"
            }
            return prependCtes(expression, sqlText)
        }

        var alias = sql(expression, "alias")
        if (alias.isNotEmpty()) alias = " AS $alias"

        val fields = expressions(
            expression,
            key = "fields",
            sep = " ",
            dynamic = true,
            newLine = true,
            skipFirst = true,
            skipLast = true,
        )

        val includeNulls = expression.args["include_nulls"]
        val nulls = when (includeNulls) {
            null -> ""
            true -> " INCLUDE NULLS "
            else -> " EXCLUDE NULLS "
        }

        var defaultOnNull = sql(expression, "default_on_null")
        if (defaultOnNull.isNotEmpty()) defaultOnNull = " DEFAULT ON NULL ($defaultOnNull)"
        val sqlText = "${seg(direction)}$nulls($exprs FOR $fields$defaultOnNull$group)$alias"
        return prependCtes(expression, sqlText)
    }

    // sqlglot: Generator.version_sql
    open fun versionSql(expression: Version): String {
        val thisSql = "FOR ${expression.name}"
        val kind = expression.text("kind")
        val expr = sql(expression, "expression")
        return "$thisSql $kind $expr"
    }

    // sqlglot: Generator.tuple_sql
    open fun tupleSql(expression: Tuple): String =
        "(${expressions(expression, dynamic = true, newLine = true, skipFirst = true, skipLast = true)})"

    // sqlglot: Generator.update_sql (base: UPDATE_STATEMENT_SUPPORTS_FROM=true)
    open fun updateSql(expression: Update): String {
        val hint = sql(expression, "hint")
        val thisSql = sql(expression, "this")
        val fromSql = sql(expression, "from_")
        val setSql = expressions(expression, flat = true)
        val whereSql = sql(expression, "where")
        val returning = sql(expression, "returning")
        val order = sql(expression, "order")
        val limit = sql(expression, "limit")
        val expressionSql = if (returningEnd) {
            "$fromSql$whereSql$returning"
        } else {
            "$returning$fromSql$whereSql"
        }
        var options = expressions(expression, key = "options")
        if (options.isNotEmpty()) options = " OPTION($options)"
        val sqlText = "UPDATE$hint $thisSql SET $setSql$expressionSql$order$limit$options"
        return prependCtes(expression, sqlText)
    }

    // sqlglot: Generator.values_sql (base: VALUES_AS_TABLE=true)
    open fun valuesSql(expression: Values, valuesAsTableParam: Boolean = true): String {
        val asTable = valuesAsTableParam && valuesAsTable

        // The VALUES clause is still valid in an `INSERT INTO ..` statement, for example
        if (asTable || expression.findAncestor(From::class, Join::class) == null) {
            val argsSql = expressions(expression)
            val alias = sql(expression, "alias")
            var values = "VALUES${seg("")}$argsSql"
            values = if (
                wrapDerivedValues &&
                (alias.isNotEmpty() || expression.parent is From || expression.parent is Table)
            ) "($values)" else values
            values = queryModifiers(expression, values)
            return if (alias.isNotEmpty()) "$values AS $alias" else values
        }

        // Converts `VALUES...` expression into a series of select unions.
        val aliasNode = expression.args["alias"] as? TableAlias
        val columnNames = aliasNode?.columns.orEmpty()

        val selects = mutableListOf<Expression>()
        for ((i, tup) in expression.expressionsArg.withIndex()) {
            var row = (tup as Tuple).expressionsArg
            if (i == 0 && columnNames.isNotEmpty()) {
                row = row.zip(columnNames) { value, columnName ->
                    Alias(args("this" to value, "alias" to columnName))
                }
            }
            selects.add(Select(args("expressions" to row)))
        }

        val alias = if (aliasNode != null) " AS ${sql(aliasNode, "this")}" else ""
        val unions = selects.joinToString(" UNION ALL ") { sql(it) }
        return "($unions)$alias"
    }

    // sqlglot: Generator.var_sql
    open fun varSql(expression: Var): String = sql(expression, "this")

    // sqlglot: Generator.into_sql
    open fun intoSql(expression: Into): String {
        val temporary = if (expression.args["temporary"] == true) " TEMPORARY" else ""
        val unlogged = if (expression.args["unlogged"] == true) " UNLOGGED" else ""
        return "${seg("INTO")}${temporary.ifEmpty { unlogged }} ${sql(expression, "this")}"
    }

    // sqlglot: Generator.from_sql
    open fun fromSql(expression: From): String =
        "${seg("FROM")} ${sql(expression, "this")}"

    // sqlglot: Generator.groupingsets_sql
    open fun groupingsetsSql(expression: GroupingSets): String =
        "GROUPING SETS ${wrap(expressions(expression, indent = false))}"

    // sqlglot: Generator.rollup_sql
    open fun rollupSql(expression: Rollup): String {
        val exprs = expressions(expression, indent = false)
        return if (exprs.isNotEmpty()) "ROLLUP ${wrap(exprs)}" else "WITH ROLLUP"
    }

    // sqlglot: Generator.rollupproperty_sql
    open fun rolluppropertySql(expression: RollupProperty): String =
        "ROLLUP (${expressions(expression, flat = true)})"

    // sqlglot: Generator.cube_sql
    open fun cubeSql(expression: Cube): String {
        val exprs = expressions(expression, indent = false)
        return if (exprs.isNotEmpty()) "CUBE ${wrap(exprs)}" else "WITH CUBE"
    }

    // sqlglot: Generator.group_sql
    open fun groupSql(expression: Group): String {
        val groupByAll = expression.args["all"]
        val modifier = when (groupByAll) {
            true -> " ALL"
            false -> " DISTINCT"
            else -> ""
        }

        var groupBy = opExpressions("GROUP BY$modifier", expression)

        val groupingSets = expressions(expression, key = "grouping_sets")
        val cube = expressions(expression, key = "cube")
        val rollup = expressions(expression, key = "rollup")

        val groupings = csv(
            if (groupingSets.isNotEmpty()) seg(groupingSets) else "",
            if (cube.isNotEmpty()) seg(cube) else "",
            if (rollup.isNotEmpty()) seg(rollup) else "",
            if (expression.args["totals"] == true) seg("WITH TOTALS") else "",
            sep = groupingsSep,
        )

        if (
            expression.expressionsArg.isNotEmpty() &&
            groupings.isNotEmpty() &&
            groupings.trim() !in setOf("WITH CUBE", "WITH ROLLUP")
        ) {
            groupBy = "$groupBy$groupingsSep"
        }

        return "$groupBy$groupings"
    }

    // sqlglot: Generator.having_sql
    open fun havingSql(expression: Having): String {
        val thisSql = indent(sql(expression, "this"))
        return "${seg("HAVING")}${sep()}$thisSql"
    }

    // sqlglot: Generator.connect_sql
    open fun connectSql(expression: Connect): String {
        var start = sql(expression, "start")
        if (start.isNotEmpty()) start = seg("START WITH $start")
        val nocycle = if (expression.args["nocycle"] == true) " NOCYCLE" else ""
        val connect = seg("CONNECT BY$nocycle ${sql(expression, "connect")}")
        return start + connect
    }

    // sqlglot: Generator.prior_sql
    open fun priorSql(expression: Prior): String = "PRIOR ${sql(expression, "this")}"

    // sqlglot: Generator.join_sql
    open fun joinSql(expression: Join): String {
        val side = if (!semiAntiJoinWithSide && expression.kind in setOf("SEMI", "ANTI")) {
            ""
        } else {
            expression.side
        }

        val opSql = listOf(
            expression.method,
            // sqlglot: `"GLOBAL" if expression.args.get("global_") else None` — the
            // ClickHouse parser stores the GLOBAL token's text here, so truthiness matters
            if (expression.args["global_"] != null && expression.args["global_"] != false) "GLOBAL" else "",
            side,
            expression.kind,
            if (joinHints) expression.hint else "",
            if (expression.args["directed"] == true && directedJoins) "DIRECTED" else "",
        ).filter { it.isNotEmpty() }.joinToString(" ")

        var matchCond = sql(expression, "match_condition")
        if (matchCond.isNotEmpty()) matchCond = " MATCH_CONDITION ($matchCond)"
        var onSql = sql(expression, "on")
        val using = expression.args["using"] as? List<*>

        if (onSql.isEmpty() && !using.isNullOrEmpty()) {
            onSql = using.joinToString(", ") { sql(it) }
        }

        val thisExpr = expression.args["this"]
        var thisSql = sql(thisExpr)

        val exprs = expressions(expression)
        if (exprs.isNotEmpty()) thisSql = "$thisSql,${seg(exprs)}"

        if (onSql.isNotEmpty()) {
            onSql = indent(onSql, skipFirst = true)
            val space = if (pretty) seg(" ".repeat(pad)) else " "
            onSql = if (!using.isNullOrEmpty()) "${space}USING ($onSql)" else "${space}ON $onSql"
        } else if (opSql.isEmpty()) {
            if (thisExpr is Lateral && thisExpr.args.containsKey("cross_apply") &&
                thisExpr.args["cross_apply"] != null
            ) {
                return " $thisSql"
            }
            return ", $thisSql"
        }

        val opJoin = if (opSql != "STRAIGHT_JOIN") {
            if (opSql.isNotEmpty()) "$opSql JOIN" else "JOIN"
        } else opSql

        val pivots = expressions(expression, key = "pivots", sep = "", flat = true)
        return "${seg(opJoin)} $thisSql$matchCond$onSql$pivots"
    }

    // sqlglot: Generator.lambda_sql
    open fun lambdaSql(expression: Lambda, arrowSep: String = "->", wrapArgs: Boolean = true): String {
        var argsSql = expressions(expression, flat = true)
        if (wrapArgs && argsSql.split(",").size > 1) argsSql = "($argsSql)"
        return "$argsSql $arrowSep ${sql(expression, "this")}"
    }

    // sqlglot: Generator.lateral_op
    open fun lateralOp(expression: Lateral): String {
        val op = when (expression.args["cross_apply"]) {
            true -> "INNER JOIN "
            false -> "LEFT JOIN "
            else -> ""
        }
        return "${op}LATERAL"
    }

    // sqlglot: Generator.lateral_sql
    open fun lateralSql(expression: Lateral): String {
        val thisSql = sql(expression, "this")

        if (expression.args["view"] == true) {
            val aliasNode = expression.args["alias"] as? TableAlias
            val columns = if (aliasNode != null) expressions(aliasNode, key = "columns", flat = true) else ""
            val table = if (aliasNode != null && aliasNode.name.isNotEmpty()) " ${aliasNode.name}" else ""
            val columnsSql = if (columns.isNotEmpty()) " AS $columns" else ""
            val opSql = seg("LATERAL VIEW${if (expression.args["outer"] == true) " OUTER" else ""}")
            return "$opSql${sep()}$thisSql$table$columnsSql"
        }

        var alias = sql(expression, "alias")
        if (alias.isNotEmpty()) alias = " AS $alias"

        var ordinality = ""
        if (expression.args["ordinality"] == true) {
            ordinality = " WITH ORDINALITY$alias"
            alias = ""
        }

        return "${lateralOp(expression)} $thisSql$alias$ordinality"
    }

    // sqlglot: Generator.limit_sql (base: LIMIT_ONLY_LITERALS=false)
    open fun limitSql(expression: Limit, top: Boolean = false): String {
        val thisSql = sql(expression, "this")

        val argsList = listOf("offset", "expression")
            .mapNotNull { expression.args[it] as? Expression }
            .map { if (limitOnlyLiterals) simplifyUnlessLiteral(it) else it }

        var argsSql = argsList.joinToString(", ") { sql(it) }
        if (top && argsList.any { !it.isNumber }) argsSql = "($argsSql)"
        var exprs = expressions(expression, flat = true)
        val limitOptions = sql(expression, "limit_options")
        exprs = if (exprs.isNotEmpty()) " BY $exprs" else ""

        return "$thisSql${seg(if (top) "TOP" else "LIMIT")} $argsSql$limitOptions$exprs"
    }

    // sqlglot: Generator.offset_sql
    open fun offsetSql(expression: Offset): String {
        val thisSql = sql(expression, "this")
        var value = expression.args["expression"]
        if (limitOnlyLiterals && value is Expression) value = simplifyUnlessLiteral(value)
        var exprs = expressions(expression, flat = true)
        exprs = if (exprs.isNotEmpty()) " BY $exprs" else ""
        return "$thisSql${seg("OFFSET")} ${sql(value)}$exprs"
    }

    /**
     * sqlglot: Generator._simplify_unless_literal — Python calls optimizer.simplify;
     * we port the slice needed by LIMIT_ONLY_LITERALS: constant folding of integer
     * `+`, `-`, `*` arithmetic (and paren/neg unwrapping). Non-constant expressions
     * pass through unchanged, matching Python's simplify no-op on them.
     */
    protected fun simplifyUnlessLiteral(expression: Expression): Expression {
        if (expression is Literal) return expression
        val folded = foldIntLiteral(expression) ?: return expression
        return intLiteral(folded)
    }

    // sqlglot: Generator.setitem_sql
    open fun setitemSql(expression: SetItem): String {
        var kind = sql(expression, "kind")
        kind = if (!setAssignmentRequiresVariableKeyword && kind == "VARIABLE") {
            ""
        } else if (kind.isNotEmpty()) {
            "$kind "
        } else ""
        val thisSql = sql(expression, "this")
        val exprs = expressions(expression)
        var collate = sql(expression, "collate")
        if (collate.isNotEmpty()) collate = " COLLATE $collate"
        val global = if (expression.args["global_"] == true) "GLOBAL " else ""
        return "$global$kind$thisSql$exprs$collate"
    }

    // sqlglot: Generator.set_sql
    open fun setSql(expression: SetNode): String {
        val exprs = " ${expressions(expression, flat = true)}"
        val tag = if (expression.args["tag"] == true) " TAG" else ""
        return "${if (expression.args["unset"] == true) "UNSET" else "SET"}$tag$exprs"
    }

    // sqlglot: Generator.pragma_sql
    open fun pragmaSql(expression: Pragma): String = "PRAGMA ${sql(expression, "this")}"

    // sqlglot: Generator.lock_sql (base: LOCKING_READS_SUPPORTED=false)
    open fun lockSql(expression: Lock): String {
        if (!lockingReadsSupported) {
            unsupported("Locking reads using 'FOR UPDATE/SHARE' are not supported")
            return ""
        }
        val update = expression.args["update"] == true
        val key = isTruthyArg(expression.args["key"])
        val lockType = if (update) {
            if (key) "FOR NO KEY UPDATE" else "FOR UPDATE"
        } else {
            if (key) "FOR KEY SHARE" else "FOR SHARE"
        }
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) exprs = " OF $exprs"
        val waitArg = expression.args["wait"]
        val wait = when {
            waitArg is Literal -> " WAIT ${sql(waitArg)}"
            waitArg != null -> if (waitArg == true) " NOWAIT" else " SKIP LOCKED"
            else -> ""
        }
        return "$lockType$exprs$wait"
    }

    // sqlglot: Generator.loaddata_sql
    open fun loaddataSql(expression: LoadData): String {
        val isOverwrite = expression.args["overwrite"] == true
        val overwrite = if (isOverwrite) " OVERWRITE" else ""
        var thisSql = sql(expression, "this")

        val files = expression.args["files"] as? Expression
        if (files != null) {
            var filesSql = expressions(files, flat = true)
            filesSql = "FILES${wrap(filesSql)}"
            thisSql = when {
                isOverwrite -> " $thisSql"
                expression.args["temp"] == true -> " INTO TEMP TABLE $thisSql"
                else -> " INTO TABLE $thisSql"
            }
            return "LOAD DATA$overwrite$thisSql FROM $filesSql"
        }

        val local = if (expression.args["local"] == true) " LOCAL" else ""
        val inpath = " INPATH ${sql(expression, "inpath")}"
        thisSql = " INTO TABLE $thisSql"
        var partition = sql(expression, "partition")
        if (partition.isNotEmpty()) partition = " $partition"
        var inputFormat = sql(expression, "input_format")
        if (inputFormat.isNotEmpty()) inputFormat = " INPUTFORMAT $inputFormat"
        var serde = sql(expression, "serde")
        if (serde.isNotEmpty()) serde = " SERDE $serde"
        return "LOAD DATA$local$inpath$overwrite$thisSql$partition$inputFormat$serde"
    }

    // sqlglot: Generator.order_sql
    open fun orderSql(expression: Order, flat: Boolean = false): String {
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = "$thisSql "
        val siblings = if (expression.args["siblings"] == true) "SIBLINGS " else ""
        return opExpressions("${thisSql}ORDER ${siblings}BY", expression, flat = thisSql.isNotEmpty() || flat)
    }

    // sqlglot: Generator.withfill_sql
    open fun withfillSql(expression: WithFill): String {
        var fromSql = sql(expression, "from_")
        if (fromSql.isNotEmpty()) fromSql = " FROM $fromSql"
        var toSql = sql(expression, "to")
        if (toSql.isNotEmpty()) toSql = " TO $toSql"
        var stepSql = sql(expression, "step")
        if (stepSql.isNotEmpty()) stepSql = " STEP $stepSql"
        val interpolatedValues = (expression.args["interpolate"] as? List<*>).orEmpty().map { e ->
            if (e is Alias) "${sql(e, "alias")} AS ${sql(e, "this")}"
            else sql(e as Expression, "this")
        }
        val interpolate = if (interpolatedValues.isNotEmpty()) {
            " INTERPOLATE (${interpolatedValues.joinToString(", ")})"
        } else ""
        return "WITH FILL$fromSql$toSql$stepSql$interpolate"
    }

    // sqlglot: Generator.cluster_sql
    open fun clusterSql(expression: Cluster): String = opExpressions("CLUSTER BY", expression)

    // sqlglot: Generator.clusterproperty_sql
    open fun clusterpropertySql(expression: ClusterProperty): String {
        if (expression.args["this"] != null) {
            unsupported("Unsupported CLUSTER BY ${sql(expression, "this")}")
            return ""
        }
        return "CLUSTER BY (${expressions(expression, flat = true)})"
    }

    // sqlglot: Generator.distribute_sql
    open fun distributeSql(expression: Distribute): String = opExpressions("DISTRIBUTE BY", expression)

    // sqlglot: Generator.sort_sql
    open fun sortSql(expression: Sort): String = opExpressions("SORT BY", expression)

    // sqlglot: Generator.WINDOW_FUNCS_WITH_NULL_ORDERING (base: empty)
    open val windowFuncsWithNullOrdering: Set<KClass<out Expression>> get() = emptySet()

    // sqlglot: Generator._resolve_ordered_for_null_ordering_simulation
    protected open fun resolveOrderedForNullOrderingSimulation(expression: Ordered): Expression? {
        val this_ = expression.args["this"]
        if (this_ !is Column || this_.table.isNotEmpty()) return null

        val ancestor = expression.findAncestor(Select::class, Window::class)
        if (ancestor !is Select) return null

        val columnName = this_.name
        val matched = ancestor.selects.mapNotNull { p ->
            val pe = p as? Expression ?: return@mapNotNull null
            // sqlglot: p.output_name
            val outputName = when (pe) {
                is Alias -> pe.alias
                is Column, is Literal, is Identifier -> pe.name
                else -> ""
            }
            if (outputName == columnName) {
                if (pe is Alias) pe.thisArg as? Expression else pe
            } else null
        }
        val match = if (matched.size == 1) matched[0] else null

        // Skip the substitution when it would be identical to the existing reference
        if (match is Column && match.table.isEmpty() && match.name == columnName) return null

        return match
    }

    // sqlglot: Generator.ordered_sql
    open fun orderedSql(expression: Ordered): String {
        val desc = expression.args["desc"]
        val asc = desc != true

        val nullsFirst = expression.args["nulls_first"] == true
        val nullsLast = !nullsFirst
        val nullsAreLarge = dialectNullOrdering == "nulls_are_large"
        val nullsAreSmall = dialectNullOrdering == "nulls_are_small"
        val nullsAreLast = dialectNullOrdering == "nulls_are_last"

        var thisSql = sql(expression, "this")

        val sortOrder = when (desc) {
            true -> " DESC"
            false -> " ASC"
            else -> ""
        }
        var nullsSortChange = ""
        if (nullsFirst && ((asc && nullsAreLarge) || (!asc && nullsAreSmall) || nullsAreLast)) {
            nullsSortChange = " NULLS FIRST"
        } else if (
            nullsLast && ((asc && nullsAreSmall) || (!asc && nullsAreLarge)) && !nullsAreLast
        ) {
            nullsSortChange = " NULLS LAST"
        }

        // If the NULLS FIRST/LAST clause is unsupported, we add another sort key to simulate it
        if (nullsSortChange.isNotEmpty() && nullOrderingSupported != true) {
            val window = expression.findAncestor(Window::class, Select::class)

            var windowThis: Expression? = null
            var spec: Expression? = null
            if (window is Window) {
                windowThis = window.thisArg as? Expression
                if (windowThis is IgnoreNulls || windowThis is RespectNulls) {
                    windowThis = windowThis.thisArg as? Expression
                }
                spec = window.args["spec"] as? Expression
            }

            // Some window functions (e.g. LAST_VALUE, RANK) support NULLS FIRST/LAST
            // without a spec or with a ROWS spec, but not with RANGE
            val nullOrderingWindowFunc = windowThis != null &&
                windowFuncsWithNullOrdering.any { it.isInstance(windowThis) } &&
                (spec == null || spec.text("kind").uppercase() == "ROWS")

            if (!nullOrderingWindowFunc) {
                if (windowThis != null && spec != null) {
                    val funcName = (windowThis as? Func)?.sqlName()
                        ?: windowThis::class.simpleName
                    unsupported(
                        "'${nullsSortChange.trim()}' translation not supported in window function $funcName"
                    )
                    nullsSortChange = ""
                } else if (nullOrderingSupported == false &&
                    ((asc && nullsSortChange == " NULLS LAST") ||
                        (!asc && nullsSortChange == " NULLS FIRST"))
                ) {
                    // BigQuery does not allow these ordering/nulls combinations when used
                    // under an aggregation func or under a window containing one
                    var ancestor: Expression? = expression.parent
                    while (ancestor != null && ancestor !is AggFunc &&
                        ancestor !is Window && ancestor !is Select
                    ) {
                        ancestor = ancestor.parent
                    }
                    if (ancestor is Window) ancestor = ancestor.thisArg as? Expression
                    if (ancestor is AggFunc) {
                        val funcName = (ancestor as? Func)?.sqlName()
                            ?: ancestor::class.simpleName
                        unsupported(
                            "'${nullsSortChange.trim()}' translation not supported for " +
                                "aggregate function $funcName with $sortOrder sort order"
                        )
                        nullsSortChange = ""
                    }
                } else if (nullOrderingSupported == null) {
                    val orderedThis = expression.args["this"] as? Expression
                    val isInt = orderedThis is Literal && !orderedThis.isString &&
                        (orderedThis.thisArg as? String)?.toLongOrNull() != null
                    if (isInt) {
                        unsupported(
                            "'${nullsSortChange.trim()}' translation not supported with " +
                                "positional ordering"
                        )
                    } else if (orderedThis !is Rand) {
                        val resolved = resolveOrderedForNullOrderingSimulation(expression)
                        val target = if (resolved != null) sql(resolved) else thisSql
                        val nullSortOrder = if (nullsSortChange == " NULLS FIRST") " DESC" else ""
                        thisSql =
                            "CASE WHEN $target IS NULL THEN 1 ELSE 0 END$nullSortOrder, $target"
                    }
                    nullsSortChange = ""
                }
            }
        }

        var withFill = sql(expression, "with_fill")
        if (withFill.isNotEmpty()) withFill = " $withFill"

        return "$thisSql$sortOrder$nullsSortChange$withFill"
    }

    // sqlglot: Generator.matchrecognizemeasure_sql
    open fun matchrecognizemeasureSql(expression: MatchRecognizeMeasure): String {
        var windowFrame = sql(expression, "window_frame")
        if (windowFrame.isNotEmpty()) windowFrame = "$windowFrame "

        return "$windowFrame${sql(expression, "this")}"
    }

    // sqlglot: Generator.matchrecognize_sql
    open fun matchrecognizeSql(expression: MatchRecognize): String {
        val partition = partitionBySql(expression)
        val order = sql(expression, "order")
        var measures = expressions(expression, key = "measures")
        measures = if (measures.isNotEmpty()) seg("MEASURES${seg(measures)}") else ""
        var rows = sql(expression, "rows")
        rows = if (rows.isNotEmpty()) seg(rows) else ""
        var after = sql(expression, "after")
        after = if (after.isNotEmpty()) seg(after) else ""
        var pattern = sql(expression, "pattern")
        pattern = if (pattern.isNotEmpty()) seg("PATTERN ($pattern)") else ""
        val definitionSqls = (expression.args["define"] as? List<*>).orEmpty().map { definition ->
            "${sql(definition as? Expression ?: return@map "", "alias")} AS ${sql(definition, "this")}"
        }
        val definitions = expressions(sqls = definitionSqls)
        val define = if (definitions.isNotEmpty()) seg("DEFINE${seg(definitions)}") else ""
        val body = listOf(partition, order, measures, rows, after, pattern, define).joinToString("")
        var alias = sql(expression, "alias")
        if (alias.isNotEmpty()) alias = " $alias"
        return "${seg("MATCH_RECOGNIZE")} ${wrap(body)}$alias"
    }

    // sqlglot: Generator.refresh_sql
    open fun refreshSql(expression: Refresh): String {
        val thisSql = sql(expression, "this")
        val kind = if (expression.thisArg is Literal) "" else "${expression.text("kind")} "
        return "REFRESH $kind$thisSql"
    }

    // sqlglot: Generator.AFTER_HAVING_MODIFIER_TRANSFORMS (base adds cluster/distribute/sort
    // over the module-level windows/qualify pair; dialects can drop them)
    protected open fun afterHavingModifierSqls(expression: Expression): List<String> = listOf(
        sql(expression, "cluster"),
        sql(expression, "distribute"),
        sql(expression, "sort"),
        windowsModifierSql(expression),
        sql(expression, "qualify"),
    )

    // sqlglot: AFTER_HAVING_MODIFIER_TRANSFORMS["windows"]
    protected fun windowsModifierSql(expression: Expression): String =
        if (isTruthyArg(expression.args["windows"])) {
            seg("WINDOW ") + expressions(expression, key = "windows", flat = true)
        } else ""

    // sqlglot: Generator.query_modifiers (base: LIMIT_FETCH="ALL" -> no fetch/limit conversion)
    open fun queryModifiers(expression: Expression, vararg sqls: String): String {
        var limit = expression.args["limit"] as? Expression

        // sqlglot: LIMIT_FETCH conversion between Fetch and Limit
        if (limitFetch == "LIMIT" && limit is Fetch) {
            val count = limit.args["count"] as? Expression
            limit = Limit(args("expression" to (count?.copy() ?: Literal.number("1"))))
        } else if (limitFetch == "FETCH" && limit is Limit) {
            limit = Fetch(
                args("direction" to "FIRST", "count" to (limit.args["expression"] as? Expression)?.copy())
            )
        }

        val parts = mutableListOf<String>()
        parts.addAll(sqls)
        for (join in (expression.args["joins"] as? List<*>).orEmpty()) parts.add(sql(join))
        parts.add(sql(expression, "match"))
        for (lateral in (expression.args["laterals"] as? List<*>).orEmpty()) parts.add(sql(lateral))
        parts.add(sql(expression, "prewhere"))
        parts.add(sql(expression, "where"))
        parts.add(sql(expression, "connect"))
        parts.add(sql(expression, "group"))
        parts.add(sql(expression, "having"))
        // sqlglot: AFTER_HAVING_MODIFIER_TRANSFORMS (cluster, distribute, sort, windows, qualify)
        parts.addAll(afterHavingModifierSqls(expression))
        parts.add(sql(expression, "order"))
        parts.addAll(offsetLimitModifiers(expression, limit is Fetch, limit))
        parts.addAll(afterLimitModifiers(expression))
        parts.add(optionsModifier(expression))
        parts.add(sql(expression, "for_"))

        return parts.filter { it.isNotEmpty() }.joinToString("")
    }

    // sqlglot: Generator.options_modifier
    open fun optionsModifier(expression: Expression): String {
        val options = expressions(expression, key = "options")
        return if (options.isNotEmpty()) " $options" else ""
    }

    // sqlglot: Generator.queryoption_sql
    open fun queryoptionSql(expression: QueryOption): String {
        unsupported("Unsupported query option.")
        return ""
    }

    // sqlglot: Generator.offset_limit_modifiers
    open fun offsetLimitModifiers(
        expression: Expression,
        fetch: Boolean,
        limit: Expression?,
    ): List<String> = listOf(
        if (fetch) sql(expression, "offset") else sql(limit),
        if (fetch) sql(limit) else sql(expression, "offset"),
    )

    // sqlglot: Generator.after_limit_modifiers
    open fun afterLimitModifiers(expression: Expression): List<String> {
        var locks = expressions(expression, key = "locks", sep = " ")
        if (locks.isNotEmpty()) locks = " $locks"
        return listOf(locks, sql(expression, "sample"))
    }

    // sqlglot: Generator.select_sql (base: SUPPORTS_SELECT_INTO=false, LIMIT_IS_TOP=false,
    // STAR_EXCLUDE_REQUIRES_DERIVED_TABLE=true)
    open fun selectSql(expression: Select): String {
        // brikk pipe: parser-synthesized SELECT * FROM (PipeQuery-with-Subquery-head)
        // regenerates as FROM-first pipe text (which re-parses to the identical shape).
        val pipeFromFirst = pipeSyntheticStarSelectSql(expression)
        if (pipeFromFirst != null) return pipeFromFirst

        val into = expression.args["into"] as? Into
        if (!supportsSelectInto && into != null) into.pop()

        val hint = sql(expression, "hint")
        var distinct = sql(expression, "distinct")
        if (distinct.isNotEmpty()) distinct = " $distinct"
        var kind = sql(expression, "kind")

        val limit = expression.args["limit"]
        var top = ""
        if (limit is Limit && limitIsTop) {
            top = limitSql(limit, top = true)
            limit.pop()
        }

        var exprs = expressions(expression)

        if (kind.isNotEmpty()) {
            if (kind in selectKinds) {
                kind = " AS $kind"
            } else {
                if (kind == "STRUCT") {
                    exprs = expressions(
                        sqls = listOf(
                            sql(
                                Struct(
                                    args(
                                        "expressions" to expression.expressionsArg.map { e ->
                                            if (e is Alias) {
                                                PropertyEQ(
                                                    args(
                                                        "this" to e.args["alias"],
                                                        "expression" to e.args["this"],
                                                    )
                                                )
                                            } else e
                                        }
                                    )
                                )
                            )
                        )
                    )
                }
                kind = ""
            }
        }

        var operationModifiers = expressions(expression, key = "operation_modifiers", sep = " ")
        if (operationModifiers.isNotEmpty()) operationModifiers = "${sep()}$operationModifiers"

        val exclude = expression.args["exclude"] as? List<*>

        if (!starExcludeRequiresDerivedTable && !exclude.isNullOrEmpty()) {
            val excludeSql = expressions(sqls = exclude, flat = true)
            exprs = "$exprs${seg("EXCLUDE")} ($excludeSql)"
        }

        val topDistinct = if (limitIsTop) "$distinct$hint$top" else "$top$hint$distinct"
        if (exprs.isNotEmpty()) exprs = "${sep()}$exprs"
        var sqlText = queryModifiers(
            expression,
            "SELECT$topDistinct$operationModifiers$kind$exprs",
            sql(expression, "into", comment = false),
            sql(expression, "from_", comment = false),
        )

        // If both the CTE and SELECT clauses have comments, generate the latter earlier
        if (expression.args["with_"] != null) {
            sqlText = maybeComment(sqlText, expression)
            expression.popComments()
        }

        sqlText = prependCtes(expression, sqlText)

        if (starExcludeRequiresDerivedTable && !exclude.isNullOrEmpty()) {
            expression.set("exclude", null)
            val subquery = Subquery(args("this" to expression))
            val star = Star(args("except_" to exclude))
            val outer = Select(args("expressions" to listOf(star)))
            outer.set("from_", From(args("this" to subquery)))
            sqlText = sql(outer)
        }

        if (!supportsSelectInto && into != null) {
            val tableKind = when {
                into.args["temporary"] == true -> " TEMPORARY"
                supportsUnloggedTables && into.args["unlogged"] == true -> " UNLOGGED"
                else -> ""
            }
            sqlText = "CREATE$tableKind TABLE ${sql(into.thisArg)} AS $sqlText"
        }

        return sqlText
    }

    // sqlglot: Generator.schema_sql
    open fun schemaSql(expression: Schema): String {
        val thisSql = sql(expression, "this")
        val sqlText = schemaColumnsSql(expression)
        return if (thisSql.isNotEmpty() && sqlText.isNotEmpty()) "$thisSql $sqlText"
        else thisSql.ifEmpty { sqlText }
    }

    // sqlglot: Generator.schema_columns_sql
    open fun schemaColumnsSql(expression: Expression): String =
        if (expression.expressionsArg.isNotEmpty()) {
            "(${sep("")}${expressions(expression)}${seg(")", sep = "")}"
        } else ""

    // sqlglot: Generator.star_sql
    open fun starSql(expression: Star): String {
        var except = expressions(expression, key = "except_", flat = true)
        if (except.isNotEmpty()) except = "${seg(starExcept)} ($except)"
        var replace = expressions(expression, key = "replace", flat = true)
        if (replace.isNotEmpty()) replace = "${seg("REPLACE")} ($replace)"
        var rename = expressions(expression, key = "rename", flat = true)
        if (rename.isNotEmpty()) rename = "${seg("RENAME")} ($rename)"
        var ilike = sql(expression, "ilike")
        if (ilike.isNotEmpty()) ilike = "${seg("ILIKE")} $ilike"
        return "*$ilike$except$replace$rename"
    }

    // sqlglot: Generator.parameter_sql
    open fun parameterSql(expression: Parameter): String =
        "$parameterToken${sql(expression, "this")}"

    // sqlglot: Generator.sessionparameter_sql
    open fun sessionparameterSql(expression: SessionParameter): String {
        val thisSql = sql(expression, "this")
        var kind = expression.text("kind")
        if (kind.isNotEmpty()) kind = "$kind."
        return "@@$kind$thisSql"
    }

    // sqlglot: Generator.placeholder_sql
    open fun placeholderSql(expression: Placeholder): String =
        if (expression.args["this"] != null) "$namedPlaceholderToken${expression.name}" else "?"

    // sqlglot: Generator.subquery_sql
    open fun subquerySql(expression: Subquery, sep: String = " AS "): String {
        // brikk pipe: parsing `(<pipe text>)` in table position wraps twice (table-level
        // subquery + _parse_subquery); collapse the synthesized outer wrapper so pipe
        // generation re-parses to the identical tree.
        val inner = expression.thisArg
        if (expression.isWrapper && inner is Subquery && inner.isWrapper && inner.thisArg is PipeQuery) {
            return subquerySql(inner, sep)
        }

        var alias = sql(expression, "alias")
        if (alias.isNotEmpty()) alias = "$sep$alias"
        val sample = sql(expression, "sample")
        if (dialectAliasPostTablesample && sample.isNotEmpty()) {
            alias = "$sample$alias"
            expression.set("sample", null)
        }
        val pivots = expressions(expression, key = "pivots", sep = "", flat = true)
        val sqlText = queryModifiers(expression, wrap(expression), alias, pivots)
        return prependCtes(expression, sqlText)
    }

    // sqlglot: Generator.qualify_sql
    open fun qualifySql(expression: Qualify): String {
        val thisSql = indent(sql(expression, "this"))
        return "${seg("QUALIFY")}${sep()}$thisSql"
    }

    // sqlglot: Generator.unnest_sql (base: UNNEST_WITH_ORDINALITY=true)
    open fun unnestSql(expression: Unnest): String {
        val argsSql = expressions(expression, flat = true)

        val aliasNode = expression.args["alias"] as? TableAlias
        // NB: like Python, the local `offset` keeps its original value even after the
        // arg is cleared below — it still drives the WITH ORDINALITY suffix.
        val offset = expression.args["offset"]

        if (unnestWithOrdinality) {
            if (aliasNode != null && offset is Expression) {
                aliasNode.append("columns", offset)
                expression.set("offset", null)
            }
        }

        var alias = if (aliasNode != null && dialectUnnestColumnOnly) {
            val columns = aliasNode.columns
            if (columns.isNotEmpty()) sql(columns[0]) else ""
        } else {
            sql(aliasNode)
        }

        if (alias.isNotEmpty()) alias = " AS $alias"
        val suffix = if (unnestWithOrdinality) {
            if (isTruthyArg(offset)) " WITH ORDINALITY$alias" else alias
        } else {
            when {
                offset is Expression -> "$alias WITH OFFSET AS ${sql(offset)}"
                isTruthyArg(offset) -> "$alias WITH OFFSET"
                else -> alias
            }
        }

        return "UNNEST($argsSql)$suffix"
    }

    // sqlglot: Generator.prewhere_sql
    open fun prewhereSql(expression: PreWhere): String = ""

    // sqlglot: Generator.where_sql
    open fun whereSql(expression: Where): String {
        val thisSql = indent(sql(expression, "this"))
        return "${seg("WHERE")}${sep()}$thisSql"
    }

    // sqlglot: Generator.window_sql
    open fun windowSql(expression: Window): String {
        var thisSql = sql(expression, "this")
        val partition = partitionBySql(expression)
        val orderNode = expression.args["order"] as? Order
        val order = if (orderNode != null) orderSql(orderNode, flat = true) else ""
        val spec = sql(expression, "spec")
        val alias = sql(expression, "alias")
        val over = sql(expression, "over").ifEmpty { "OVER" }

        thisSql = "$thisSql ${if (expression.argKey == "windows") "AS" else over}"

        val firstArg = expression.args["first"]
        val first = when (firstArg) {
            null -> ""
            true -> "FIRST"
            else -> "LAST"
        }

        if (partition.isEmpty() && order.isEmpty() && spec.isEmpty() && alias.isNotEmpty()) {
            return "$thisSql $alias"
        }

        val argsSql = formatArgs(
            *listOf(alias, first, partition, order, spec).filter { it.isNotEmpty() }.toTypedArray(),
            sep = " ",
        )
        return "$thisSql ($argsSql)"
    }

    // sqlglot: Generator.partition_by_sql
    open fun partitionBySql(expression: Expression): String {
        val partition = expressions(expression, key = "partition_by", flat = true)
        return if (partition.isNotEmpty()) "PARTITION BY $partition" else ""
    }

    // sqlglot: Generator.windowspec_sql
    open fun windowspecSql(expression: WindowSpec): String {
        val kind = sql(expression, "kind")
        val start = csv(sql(expression, "start"), sql(expression, "start_side"), sep = " ")
        val end = csv(sql(expression, "end"), sql(expression, "end_side"), sep = " ")
            .ifEmpty { "CURRENT ROW" }

        var windowSpec = "$kind BETWEEN $start AND $end"

        val exclude = sql(expression, "exclude")
        if (exclude.isNotEmpty()) {
            if (supportsWindowExclude) {
                windowSpec += " EXCLUDE $exclude"
            } else {
                unsupported("EXCLUDE clause is not supported in the WINDOW clause")
            }
        }

        return windowSpec
    }

    // sqlglot: Generator.withingroup_sql
    open fun withingroupSql(expression: WithinGroup): String {
        val thisSql = sql(expression, "this")
        val expressionSql = sql(expression, "expression").drop(1) // order has a leading space
        return "$thisSql WITHIN GROUP ($expressionSql)"
    }

    // sqlglot: Generator.between_sql (base: SUPPORTS_BETWEEN_FLAGS=false)
    open fun betweenSql(expression: Between): String {
        val thisSql = sql(expression, "this")
        val low = sql(expression, "low")
        val high = sql(expression, "high")
        val symmetric = expression.args["symmetric"]

        if (symmetric == true && !supportsBetweenFlags) {
            return "($thisSql BETWEEN $low AND $high OR $thisSql BETWEEN $high AND $low)"
        }

        val flag = when {
            symmetric == true -> " SYMMETRIC"
            symmetric == false && supportsBetweenFlags -> " ASYMMETRIC"
            else -> "" // silently drop ASYMMETRIC – semantics identical
        }
        return "$thisSql BETWEEN$flag $low AND $high"
    }

    // sqlglot: Generator.bracket_offset_expressions (base: INDEX_OFFSET=0 -> identity)
    open fun bracketOffsetExpressions(expression: Bracket, indexOffset: Int? = null): List<kotlin.Any?> {
        if (expression.args["json_access"] == true) return expression.expressionsArg
        return applyIndexOffset(
            expression.thisArg as? Expression,
            expression.expressionsArg,
            (indexOffset ?: dialectIndexOffset) -
                ((expression.args["offset"] as? Number)?.toInt() ?: 0),
            dialect = dialect,
        )
    }



    // sqlglot: Generator.bracket_sql
    open fun bracketSql(expression: Bracket): String {
        val exprs = bracketOffsetExpressions(expression)
        val expressionsSql = exprs.joinToString(", ") { sql(it) }
        return "${sql(expression, "this")}[$expressionsSql]"
    }

    // sqlglot: Generator.all_sql
    open fun allSql(expression: All): String {
        var thisSql = sql(expression, "this")
        val thisArg = expression.args["this"]
        if (thisArg !is Tuple && thisArg !is Paren) thisSql = wrap(thisSql)
        return "ALL $thisSql"
    }

    // sqlglot: Generator.any_sql
    open fun anySql(expression: AnyNode): String {
        var thisSql = sql(expression, "this")
        val thisArg = expression.args["this"]
        if (thisArg is Select || thisArg is SetOperation || thisArg is Paren) {
            if (thisArg is Select || thisArg is SetOperation) thisSql = wrap(thisSql)
            return "ANY$thisSql"
        }
        return "ANY $thisSql"
    }

    // sqlglot: Generator.exists_sql
    open fun existsSql(expression: Exists): String = "EXISTS${wrap(expression)}"

    // sqlglot: Generator.case_sql
    open fun caseSql(expression: Case): String {
        val thisSql = sql(expression, "this")
        val statements = mutableListOf(if (thisSql.isNotEmpty()) "CASE $thisSql" else "CASE")

        for (e in (expression.args["ifs"] as? List<*>).orEmpty()) {
            val ifExpr = e as Expression
            statements.add("WHEN ${sql(ifExpr, "this")}")
            statements.add("THEN ${sql(ifExpr, "true")}")
        }

        val default = sql(expression, "default")
        if (default.isNotEmpty()) statements.add("ELSE $default")

        statements.add("END")

        if (pretty && tooWide(statements)) {
            return indent(statements.joinToString("\n"), skipFirst = true, skipLast = true)
        }

        return statements.joinToString(" ")
    }

    // sqlglot: Generator.constraint_sql
    open fun constraintSql(expression: Constraint): String {
        val thisSql = sql(expression, "this")
        val exprs = expressions(expression, flat = true)
        return "CONSTRAINT $thisSql $exprs"
    }

    // sqlglot: Generator.nextvaluefor_sql
    open fun nextvalueforSql(expression: NextValueFor): String {
        val orderNode = expression.args["order"] as? Order
        val order = if (orderNode != null) " OVER (${orderSql(orderNode, flat = true)})" else ""
        return "NEXT VALUE FOR ${sql(expression, "this")}$order"
    }

    // sqlglot: Generator.extract_sql (base: NORMALIZE_EXTRACT_DATE_PARTS=false)
    open fun extractSql(expression: Extract): String {
        val thisNode = expression.args["this"] as? Expression
        val thisSql = if (extractAllowsQuotes) sql(thisNode) else thisNode?.name ?: ""
        val expressionSql = sql(expression, "expression")
        return "EXTRACT($thisSql FROM $expressionSql)"
    }

    // sqlglot: Generator.trim_sql
    open fun trimSql(expression: Trim): String {
        val trimType = sql(expression, "position")
        val funcName = when (trimType) {
            "LEADING" -> "LTRIM"
            "TRAILING" -> "RTRIM"
            else -> "TRIM"
        }
        return func(funcName, expression.thisArg, expression.expressionArg)
    }

    // sqlglot: Generator.convert_concat_args
    open fun convertConcatArgs(expression: Func): List<kotlin.Any?> {
        var argsList = (expression as Expression).expressionsArg
        if (expression is ConcatWs) argsList = argsList.drop(1) // Skip the delimiter

        if (dialectStrictStringConcat && expression.args["safe"] == true) {
            argsList = argsList.map { e ->
                Cast(args("this" to e, "to" to DataType(args("this" to DType.TEXT))))
            }
        }

        val concatCoalesce =
            if (expression is ConcatWs) dialectConcatWsCoalesce else dialectConcatCoalesce

        if (!concatCoalesce && expression.args["coalesce"] == true) {
            argsList = argsList.map { item ->
                var e = item as Expression
                if (e.type == null) {
                    e = dev.brikk.house.sql.optimizer.annotateTypes(e, dialect = dialect)
                }

                if (e.isString || e.isType(DType.ARRAY)) {
                    e
                } else {
                    Coalesce(
                        args(
                            "this" to e,
                            "expressions" to mutableListOf<kotlin.Any?>(
                                Literal(args("this" to "", "is_string" to true))
                            ),
                        )
                    )
                }
            }
        }

        return argsList
    }

    // sqlglot: dialect.concat_to_dpipe_sql (reduce over DPipe)
    protected fun concatToDpipeSql(expression: Expression): String {
        val exprs = expression.expressionsArg
        var reduced = exprs[0]
        for (e in exprs.drop(1)) {
            reduced = DPipe(args("this" to reduced, "expression" to e))
        }
        return sql(reduced)
    }

    // sqlglot: Generator.concat_sql (base: SUPPORTS_SINGLE_ARG_CONCAT=true)
    open fun concatSql(expression: Concat): String {
        if (dialectConcatCoalesce && expression.args["coalesce"] != true) {
            // Dialect's CONCAT function coalesces NULLs to empty strings, but the
            // expression does not. Transpile to double pipe operators, which typically
            // return NULL if any args are NULL instead of coalescing them.
            return concatToDpipeSql(expression)
        }

        val exprs = convertConcatArgs(expression)
        if (!supportsSingleArgConcat && exprs.size == 1) return sql(exprs[0])
        return func("CONCAT", *exprs.toTypedArray())
    }

    // sqlglot: Generator.concatws_sql
    open fun concatwsSql(expression: ConcatWs): String {
        if (dialectConcatWsCoalesce && expression.args["coalesce"] != true) {
            // Dialect's CONCAT_WS function skips NULL args, but the expression does
            // not. Wrap the entire call in a CASE expression that returns NULL if any
            // input IS NULL.
            val allArgs = expression.expressionsArg.filterIsInstance<Expression>()
            expression.set("coalesce", true)
            var condition: Expression = Is(
                args("this" to allArgs[0].copy(), "expression" to Null())
            )
            for (arg in allArgs.drop(1)) {
                condition = Or(
                    args(
                        "this" to condition,
                        "expression" to Is(args("this" to arg.copy(), "expression" to Null())),
                    )
                )
            }
            return sql(
                Case(
                    args(
                        "ifs" to mutableListOf<kotlin.Any?>(
                            If(args("this" to condition, "true" to Null()))
                        ),
                        "default" to expression,
                    )
                )
            )
        }

        return func(
            "CONCAT_WS",
            expression.expressionsArg.getOrNull(0),
            *convertConcatArgs(expression).toTypedArray(),
        )
    }

    // sqlglot: Generator.check_sql
    open fun checkSql(expression: Check): String = "CHECK (${sql(expression, "this")})"

    // sqlglot: Generator.foreignkey_sql
    open fun foreignkeySql(expression: ForeignKey): String {
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) exprs = " ($exprs)"
        var reference = sql(expression, "reference")
        if (reference.isNotEmpty()) reference = " $reference"
        var delete = sql(expression, "delete")
        if (delete.isNotEmpty()) delete = " ON DELETE $delete"
        var update = sql(expression, "update")
        if (update.isNotEmpty()) update = " ON UPDATE $update"
        var options = expressions(expression, key = "options", flat = true, sep = " ")
        if (options.isNotEmpty()) options = " $options"
        return "FOREIGN KEY$exprs$reference$delete$update$options"
    }

    // sqlglot: Generator.primarykey_sql
    open fun primarykeySql(expression: PrimaryKey): String {
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        val exprs = expressions(expression, flat = true)
        val include = sql(expression, "include")
        var options = expressions(expression, key = "options", flat = true, sep = " ")
        if (options.isNotEmpty()) options = " $options"
        return "PRIMARY KEY$thisSql ($exprs)$include$options"
    }

    // sqlglot: Generator.if_sql
    open fun ifSql(expression: If): String =
        caseSql(Case(args("ifs" to listOf(expression), "default" to expression.args["false"])))

    // sqlglot: Generator.matchagainst_sql (base: MATCH_AGAINST_TABLE_PREFIX=null)
    open fun matchagainstSql(expression: MatchAgainst): String {
        var modifier = expression.args["modifier"] as? String ?: ""
        if (modifier.isNotEmpty()) modifier = " $modifier"
        return "${func("MATCH", *expression.expressionsArg.toTypedArray())} AGAINST(${sql(expression, "this")}$modifier)"
    }

    // sqlglot: Generator.jsonkeyvalue_sql
    open fun jsonkeyvalueSql(expression: JSONKeyValue): String =
        "${sql(expression, "this")}$jsonKeyValuePairSep ${sql(expression, "expression")}"

    // sqlglot: Generator.jsonpath_sql
    open fun jsonpathSql(expression: JSONPath): String {
        var path = expressions(expression, sep = "", flat = true).trimStart('.')
        if (quoteJsonPath) path = "$quoteStart$path$quoteEnd"
        return path
    }

    // sqlglot: Generator.json_path_part
    open fun jsonPathPart(expression: kotlin.Any?): String {
        if (expression is Expression) {
            val handler = dispatch[expression::class]
            if (handler == null) {
                unsupported("Unsupported JSONPathPart type ${expression::class.simpleName}")
                return ""
            }
            return handler(this, expression)
        }

        if (expression is Int || expression is Long) return expression.toString()

        val text = expression as? String ?: ""
        // base: _quote_json_path_key_using_brackets=true, JSON_PATH_SINGLE_QUOTE_ESCAPE=false
        val escaped = text.replace("\"", "\\\"")
        return "\"$escaped\""
    }

    // sqlglot: Generator._jsonpathkey_sql
    open fun jsonpathkeySql(expression: JSONPathKey): String {
        val thisArg = expression.args["this"]
        if (thisArg is JSONPathWildcard) {
            val thisSql = jsonPathPart(thisArg)
            return if (thisSql.isNotEmpty()) ".$thisSql" else ""
        }

        val quoted = expression.args["quoted"] == true
        if (
            !(quoted && jsonPathKeyQuotedForcesBrackets) &&
            thisArg is String && SAFE_IDENTIFIER_RE.matches(thisArg)
        ) {
            return ".$thisArg"
        }

        var thisSql = jsonPathPart(thisArg)
        if (quoted && quoteJsonPath) thisSql = escapeStr(thisSql)

        return if (jsonPathBracketedKeySupported) "[$thisSql]" else ".$thisSql"
    }

    // sqlglot: Generator._jsonpathsubscript_sql
    open fun jsonpathsubscriptSql(expression: JSONPathSubscript): String {
        val thisSql = jsonPathPart(expression.args["this"])
        return if (thisSql.isNotEmpty()) "[$thisSql]" else ""
    }

    // sqlglot: Generator.formatjson_sql
    open fun formatjsonSql(expression: FormatJson): String = "${sql(expression, "this")} FORMAT JSON"

    // sqlglot: Generator._jsonobject_sql
    open fun jsonobjectSql(expression: Expression, name: String = ""): String {
        var nullHandling = expression.args["null_handling"] as? String ?: ""
        if (nullHandling.isNotEmpty()) nullHandling = " $nullHandling"

        val uniqueKeysArg = expression.args["unique_keys"]
        val uniqueKeys = if (uniqueKeysArg != null) {
            " ${if (uniqueKeysArg == true) "WITH" else "WITHOUT"} UNIQUE KEYS"
        } else ""

        var returnType = sql(expression, "return_type")
        if (returnType.isNotEmpty()) returnType = " RETURNING $returnType"
        var encoding = sql(expression, "encoding")
        if (encoding.isNotEmpty()) encoding = " ENCODING $encoding"

        val funcName = name.ifEmpty { if (expression is JSONObject) "JSON_OBJECT" else "JSON_OBJECTAGG" }

        return func(
            funcName,
            *expression.expressionsArg.toTypedArray(),
            suffix = "$nullHandling$uniqueKeys$returnType$encoding)",
        )
    }

    // sqlglot: Generator.jsoncolumndef_sql
    open fun jsoncolumndefSql(expression: JSONColumnDef): String {
        var path = sql(expression, "path")
        if (path.isNotEmpty()) path = " PATH $path"
        val nestedSchema = sql(expression, "nested_schema")

        if (nestedSchema.isNotEmpty()) return "NESTED$path $nestedSchema"

        val thisSql = sql(expression, "this")
        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = " $kind"
        val formatJson = if (expression.args["format_json"] == true) " FORMAT JSON" else ""
        val ordinality = if (expression.args["ordinality"] == true) " FOR ORDINALITY" else ""
        return "$thisSql$kind$formatJson$path$ordinality"
    }

    // sqlglot: Generator.jsonschema_sql
    open fun jsonschemaSql(expression: JSONSchema): String =
        func("COLUMNS", *expression.expressionsArg.toTypedArray())

    // sqlglot: Generator.jsontable_sql
    open fun jsontableSql(expression: JSONTable): String {
        val thisSql = sql(expression, "this")
        var path = sql(expression, "path")
        if (path.isNotEmpty()) path = ", $path"
        // sqlglot interpolates the raw arg (str, or Expression for DEFAULT ... ON ERROR)
        fun handling(key: String): String = when (val raw = expression.args[key]) {
            is String -> " $raw"
            is Expression -> " ${sql(raw)}"
            else -> ""
        }
        val errorHandling = handling("error_handling")
        val emptyHandling = handling("empty_handling")
        val schema = sql(expression, "schema")
        return func(
            "JSON_TABLE",
            thisSql,
            suffix = "$path$errorHandling$emptyHandling $schema)",
        )
    }

    // sqlglot: Generator.in_sql
    open fun inSql(expression: In): String {
        val query = expression.args["query"]
        val unnest = expression.args["unnest"]
        val field = expression.args["field"]
        val isGlobal = if (expression.args["is_global"] == true) " GLOBAL" else ""

        val inSql = when {
            query != null -> sql(query)
            unnest is Unnest -> inUnnestOp(unnest)
            field != null -> sql(field)
            else -> "(${expressions(expression, dynamic = true, newLine = true, skipFirst = true, skipLast = true)})"
        }

        return "${sql(expression, "this")}$isGlobal IN $inSql"
    }

    // sqlglot: Generator.in_unnest_op
    open fun inUnnestOp(unnest: Unnest): String = "(SELECT ${sql(unnest)})"

    // sqlglot: Generator.interval_sql (base: SINGLE_STRING_INTERVAL=false,
    // INTERVAL_ALLOWS_PLURAL_FORM=true)
    open fun intervalSql(expression: Interval): String {
        val unitExpression = expression.args["unit"]
        var unit = if (unitExpression != null) sql(unitExpression) else ""
        if (!intervalAllowsPluralForm) unit = GeneratorTables.TIME_PART_SINGULARS[unit] ?: unit
        if (unit.isNotEmpty()) unit = " $unit"

        if (singleStringInterval) {
            val thisName = (expression.args["this"] as? Expression)?.name ?: ""
            if (thisName.isNotEmpty()) {
                if (unitExpression is IntervalSpan) return "INTERVAL '$thisName'$unit"
                return "INTERVAL '$thisName$unit'"
            }
            return "INTERVAL$unit"
        }

        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) {
            val unwrapped = isUnwrappedIntervalValue(expression.args["this"])
            thisSql = if (unwrapped) " $thisSql" else " ($thisSql)"
        }

        return "INTERVAL$thisSql$unit"
    }

    // sqlglot: Generator.UNWRAPPED_INTERVAL_VALUES (base: Column, Literal, Neg, Paren)
    open fun isUnwrappedIntervalValue(thisArg: kotlin.Any?): Boolean =
        thisArg is Column || thisArg is Literal || thisArg is Neg || thisArg is Paren

    // sqlglot: Generator.return_sql
    open fun returnSql(expression: Return): String = "RETURN ${sql(expression, "this")}"

    // sqlglot: Generator.reference_sql
    open fun referenceSql(expression: Reference): String {
        val thisSql = sql(expression, "this")
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) exprs = "($exprs)"
        var options = expressions(expression, key = "options", flat = true, sep = " ")
        if (options.isNotEmpty()) options = " $options"
        return "REFERENCES $thisSql$exprs$options"
    }

    // sqlglot: Generator.anonymous_sql
    open fun anonymousSql(expression: Anonymous): String {
        // We don't normalize qualified functions such as a.b.foo(), because they can be case-sensitive
        val parent = expression.parent
        val isQualified = parent is Dot && expression === parent.expressionArg
        return func(
            sql(expression, "this"),
            *expression.expressionsArg.toTypedArray(),
            normalize = !isQualified,
        )
    }

    // sqlglot: Generator.paren_sql
    open fun parenSql(expression: Paren): String {
        val sqlText = seg(indent(sql(expression, "this")), sep = "")
        return "($sqlText${seg(")", sep = "")}"
    }

    // sqlglot: Generator.neg_sql
    open fun negSql(expression: Neg): String {
        // This makes sure we don't convert "- - 5" to "--5", which is a comment
        val thisSql = sql(expression, "this")
        val sep = if (thisSql.startsWith("-")) " " else ""
        return "-$sep$thisSql"
    }

    // sqlglot: Generator.not_sql
    open fun notSql(expression: Not): String = "NOT ${sql(expression, "this")}"

    // sqlglot: Generator.alias_sql
    open fun aliasSql(expression: Alias): String {
        var alias = sql(expression, "alias")
        if (alias.isNotEmpty()) alias = " AS $alias"
        return "${sql(expression, "this")}$alias"
    }

    // sqlglot: Generator.pivotalias_sql
    open fun pivotaliasSql(expression: PivotAlias): String {
        val alias = expression.args["alias"]

        val parent = expression.parent
        val pivot = parent?.parent

        if (pivot is Pivot && pivot.unpivot) {
            val identifierAlias = alias is Identifier
            val literalAlias = alias is Literal

            if (identifierAlias && !unpivotAliasesAreIdentifiers) {
                (alias as Expression).replace(Literal.string(alias.outputName))
            } else if (!identifierAlias && literalAlias && unpivotAliasesAreIdentifiers) {
                (alias as Expression).replace(toIdentifier(alias.outputName))
            }
        }

        return aliasSql(expression)
    }

    // sqlglot: Generator.aliases_sql
    open fun aliasesSql(expression: Aliases): String =
        "${sql(expression, "this")} AS (${expressions(expression, flat = true)})"

    // sqlglot: Generator.atindex_sql
    open fun atindexSql(expression: AtIndex): String =
        "${sql(expression, "this")} AT ${sql(expression, "expression")}"

    // sqlglot: Generator.attimezone_sql
    open fun attimezoneSql(expression: AtTimeZone): String =
        "${sql(expression, "this")} AT TIME ZONE ${sql(expression, "zone")}"

    // sqlglot: Generator.fromtimezone_sql
    open fun fromtimezoneSql(expression: FromTimeZone): String =
        "${sql(expression, "this")} AT TIME ZONE ${sql(expression, "zone")} AT TIME ZONE 'UTC'"

    // ------------------------------------------------------------------
    // 5. Expression methods — operators, casts, DDL actions (generator.py 4019+)
    // ------------------------------------------------------------------

    // sqlglot: Generator.and_sql / or_sql / xor_sql
    open fun andSql(expression: And, stack: MutableList<kotlin.Any?>? = null): String =
        connectorSql(expression, "AND", stack)

    open fun orSql(expression: Or, stack: MutableList<kotlin.Any?>? = null): String =
        connectorSql(expression, "OR", stack)

    open fun xorSql(expression: Xor, stack: MutableList<kotlin.Any?>? = null): String =
        connectorSql(expression, "XOR", stack)

    // sqlglot: Generator.cast_sql
    open fun castSql(expression: Cast, safePrefix: String? = null): String =
        baseCastSql(expression, safePrefix)

    // Base implementation, callable by grandchild dialects that must bypass an
    // intermediate override (sqlglot: `super(HiveGenerator, self).cast_sql(...)`).
    protected fun baseCastSql(expression: Cast, safePrefix: String? = null): String {
        var formatSql = sql(expression, "format")
        if (formatSql.isNotEmpty()) formatSql = " FORMAT $formatSql"
        var toSql = sql(expression, "to")
        if (toSql.isNotEmpty()) toSql = " $toSql"
        var action = sql(expression, "action")
        if (action.isNotEmpty()) action = " $action"
        var default = sql(expression, "default")
        if (default.isNotEmpty()) default = " DEFAULT $default ON CONVERSION ERROR"
        return "${safePrefix ?: ""}CAST(${sql(expression, "this")} AS$toSql$default$formatSql$action)"
    }

    // sqlglot: Generator.currentdate_sql
    open fun currentdateSql(expression: CurrentDate): String {
        val zone = sql(expression, "this")
        return if (zone.isNotEmpty()) "CURRENT_DATE($zone)" else "CURRENT_DATE"
    }

    // sqlglot: Generator.collate_sql (base: COLLATE_IS_FUNC=false)
    open fun collateSql(expression: Collate): String {
        if (collateIsFunc) return functionFallbackSql(expression)
        return binary(expression, "COLLATE")
    }

    // sqlglot: Generator.command_sql
    open fun commandSql(expression: Command): String =
        "${sql(expression, "this")} ${expression.text("expression").trim()}"

    // sqlglot: Generator.comment_sql
    open fun commentSql(expression: Comment): String {
        val thisSql = sql(expression, "this")
        val kind = expression.args["kind"] as? String ?: ""
        val materialized = if (expression.args["materialized"] == true) " MATERIALIZED" else ""
        val existsSql = if (expression.args["exists"] == true) " IF EXISTS " else " "
        val expressionSql = sql(expression, "expression")
        return "COMMENT${existsSql}ON$materialized $kind $thisSql IS $expressionSql"
    }

    // sqlglot: Generator.transaction_sql
    open fun transactionSql(expression: Transaction): String {
        var modes = expressions(expression, key = "modes")
        if (modes.isNotEmpty()) modes = " $modes"
        return "BEGIN$modes"
    }

    // sqlglot: Generator.commit_sql
    open fun commitSql(expression: Commit): String {
        val chainArg = expression.args["chain"]
        val chain = when (chainArg) {
            null -> ""
            true -> " AND CHAIN"
            else -> " AND NO CHAIN"
        }
        return "COMMIT$chain"
    }

    // sqlglot: Generator.rollback_sql
    open fun rollbackSql(expression: Rollback): String {
        var savepoint = expression.args["savepoint"]?.let { sql(it) } ?: ""
        if (savepoint.isNotEmpty()) savepoint = " TO $savepoint"
        return "ROLLBACK$savepoint"
    }

    // sqlglot: Generator.altercolumn_sql
    open fun altercolumnSql(expression: AlterColumn): String = baseAltercolumnSql(expression)

    // Base impl callable by grandchild dialects (sqlglot: super(HiveGenerator, self)).
    protected fun baseAltercolumnSql(expression: AlterColumn): String {
        val thisSql = sql(expression, "this")

        val dtype = sql(expression, "dtype")
        if (dtype.isNotEmpty()) {
            var collate = sql(expression, "collate")
            if (collate.isNotEmpty()) collate = " COLLATE $collate"
            var using = sql(expression, "using")
            if (using.isNotEmpty()) using = " USING $using"
            val alterSetTypeSql = if (alterSetType.isNotEmpty()) "$alterSetType " else ""
            return "ALTER COLUMN $thisSql $alterSetTypeSql$dtype$collate$using"
        }

        val default = sql(expression, "default")
        if (default.isNotEmpty()) return "ALTER COLUMN $thisSql SET DEFAULT $default"

        val comment = sql(expression, "comment")
        if (comment.isNotEmpty()) return "ALTER COLUMN $thisSql COMMENT $comment"

        val visible = expression.args["visible"] as? String
        if (!visible.isNullOrEmpty()) return "ALTER COLUMN $thisSql SET $visible"

        val allowNull = expression.args["allow_null"]
        val drop = expression.args["drop"]

        if (drop != true && allowNull != true) {
            unsupported("Unsupported ALTER COLUMN syntax")
        }

        if (allowNull != null) {
            val keyword = if (drop == true) "DROP" else "SET"
            return "ALTER COLUMN $thisSql $keyword NOT NULL"
        }

        return "ALTER COLUMN $thisSql DROP DEFAULT"
    }

    // sqlglot: Generator.alterrename_sql (base: RENAME_TABLE_WITH_DB=true)
    open fun alterrenameSql(expression: AlterRename, includeTo: Boolean = true): String {
        var expr = expression
        if (!renameTableWithDb) {
            // Remove db from tables (sqlglot: exp.table_(n.this) for Table nodes)
            expr = expr.transform { n ->
                if (n is Table) Table(args("this" to n.thisArg)) else n
            } as AlterRename
        }
        val thisSql = sql(expr, "this")
        val toKw = if (includeTo) " TO" else ""
        return "RENAME$toKw $thisSql"
    }

    // sqlglot: Generator.renamecolumn_sql
    open fun renamecolumnSql(expression: RenameColumn): String = baseRenamecolumnSql(expression)

    // Base impl callable by grandchild dialects (sqlglot: super(HiveGenerator, self)).
    protected fun baseRenamecolumnSql(expression: RenameColumn): String {
        val exists = if (expression.args["exists"] == true) " IF EXISTS" else ""
        val oldColumn = sql(expression, "this")
        val newColumn = sql(expression, "to")
        return "RENAME COLUMN$exists $oldColumn TO $newColumn"
    }

    // sqlglot: Generator.alterset_sql (base: ALTER_SET_WRAPPED=false)
    open fun altersetSql(expression: AlterSet): String {
        var exprs = expressions(expression, flat = true)
        if (alterSetWrapped) exprs = "($exprs)"
        return "SET $exprs"
    }

    // sqlglot: Generator.alter_sql (base: ALTER_TABLE_ADD_REQUIRED_FOR_EACH_COLUMN=true)
    open fun alterSql(expression: Alter): String {
        val actions = (expression.args["actions"] as? List<*>).orEmpty()

        val actionsSql: String
        if (!dialectAlterTableAddRequiredForEachColumn && actions.firstOrNull() is ColumnDef) {
            actionsSql = "ADD ${expressions(expression, key = "actions", flat = true)}"
        } else {
            val actionsList = mutableListOf<String>()
            for (action in actions) {
                val actionExpr = action as Expression
                var actionSql: String
                if (actionExpr is ColumnDef || actionExpr is Schema) {
                    actionSql = addColumnSql(actionExpr)
                } else {
                    actionSql = sql(actionExpr)
                    if (actionExpr is Select || actionExpr is SetOperation) actionSql = "AS $actionSql"
                }
                actionsList.add(actionSql)
            }
            actionsSql = formatArgs(*actionsList.toTypedArray()).trimStart('\n')
        }

        val iceberg =
            if (expression.args["iceberg"] == true && supportsDropAlterIcebergProperty) "ICEBERG " else ""
        val exists = if (expression.args["exists"] == true) " IF EXISTS" else ""
        var onCluster = sql(expression, "cluster")
        if (onCluster.isNotEmpty()) onCluster = " $onCluster"
        val only = if (expression.args["only"] == true) " ONLY" else ""
        var options = expressions(expression, key = "options")
        if (options.isNotEmpty()) options = ", $options"
        val kind = sql(expression, "kind")
        val notValid = if (expression.args["not_valid"] == true) " NOT VALID" else ""
        val check = if (expression.args["check"] == true) " WITH CHECK" else ""
        val cascade =
            if (expression.args["cascade"] == true && dialectAlterTableSupportsCascade) " CASCADE" else ""
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"

        return "ALTER $iceberg$kind$exists$only$thisSql$onCluster$check${sep()}$actionsSql$notValid$options$cascade"
    }

    // sqlglot: Generator.add_column_sql
    open fun addColumnSql(expression: Expression): String {
        val sqlText = sql(expression)
        val columnText = when {
            expression is Schema -> " COLUMNS"
            expression is ColumnDef && alterTableIncludeColumnKeyword -> " COLUMN"
            else -> ""
        }
        return "ADD$columnText $sqlText"
    }

    // sqlglot: Generator.droppartition_sql
    open fun droppartitionSql(expression: DropPartition): String {
        val exprs = expressions(expression)
        val exists = if (expression.args["exists"] == true) " IF EXISTS " else " "
        return "DROP$exists$exprs"
    }

    // sqlglot: Generator.dropprimarykey_sql
    open fun dropprimarykeySql(expression: DropPrimaryKey): String = "DROP PRIMARY KEY"

    // sqlglot: Generator.SUPPORTS_MODIFY_COLUMN / SUPPORTS_CHANGE_COLUMN (base: false)
    open val supportsModifyColumn: Boolean get() = false
    open val supportsChangeColumn: Boolean get() = false

    // sqlglot: Dialect.ON_CONDITION_EMPTY_BEFORE_ERROR (base: true)
    open val onConditionEmptyBeforeError: Boolean get() = true

    // sqlglot: Generator.modifycolumn_sql
    open fun modifycolumnSql(expression: ModifyColumn): String {
        val thisSql = sql(expression, "this")
        val renameFrom = sql(expression, "rename_from")
        if (renameFrom.isNotEmpty()) {
            if (!supportsChangeColumn) unsupported("CHANGE COLUMN is not supported in this dialect")
            return "CHANGE COLUMN $renameFrom $thisSql"
        }
        if (!supportsModifyColumn) unsupported("MODIFY COLUMN is not supported in this dialect")
        return "MODIFY COLUMN $thisSql"
    }

    // sqlglot: Generator.alterindex_sql
    open fun alterindexSql(expression: AlterIndex): String {
        val thisSql = sql(expression, "this")
        val visible = if (expression.args["visible"] == true) "VISIBLE" else "INVISIBLE"
        return "ALTER INDEX $thisSql $visible"
    }

    // sqlglot: Generator.renameindex_sql
    open fun renameindexSql(expression: RenameIndex): String {
        val thisSql = sql(expression, "this")
        val to = sql(expression, "to")
        return "RENAME INDEX $thisSql TO $to"
    }

    // sqlglot: Generator.indexconstraintoption_sql
    open fun indexconstraintoptionSql(expression: IndexConstraintOption): String {
        val keyBlockSize = sql(expression, "key_block_size")
        if (keyBlockSize.isNotEmpty()) return "KEY_BLOCK_SIZE = $keyBlockSize"

        val using = sql(expression, "using")
        if (using.isNotEmpty()) return "USING $using"

        val parser = sql(expression, "parser")
        if (parser.isNotEmpty()) return "WITH PARSER $parser"

        val comment = sql(expression, "comment")
        if (comment.isNotEmpty()) return "COMMENT $comment"

        val visible = expression.args["visible"]
        if (visible != null) return if (visible == true) "VISIBLE" else "INVISIBLE"

        val engineAttr = sql(expression, "engine_attr")
        if (engineAttr.isNotEmpty()) return "ENGINE_ATTRIBUTE = $engineAttr"

        val secondaryEngineAttr = sql(expression, "secondary_engine_attr")
        if (secondaryEngineAttr.isNotEmpty()) return "SECONDARY_ENGINE_ATTRIBUTE = $secondaryEngineAttr"

        unsupported("Unsupported index constraint option.")
        return ""
    }

    // sqlglot: Generator.indexcolumnconstraint_sql
    open fun indexcolumnconstraintSql(expression: IndexColumnConstraint): String {
        var kind = sql(expression, "kind")
        kind = if (kind.isNotEmpty()) "$kind INDEX" else "INDEX"
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        var indexType = sql(expression, "index_type")
        if (indexType.isNotEmpty()) indexType = " USING $indexType"
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) exprs = " ($exprs)"
        var options = expressions(expression, key = "options", sep = " ")
        if (options.isNotEmpty()) options = " $options"
        return "$kind$thisSql$indexType$exprs$options"
    }

    // sqlglot: Generator.jsonvalue_sql
    open fun jsonvalueSql(expression: JSONValue): String {
        val path = sql(expression, "path")
        var returning = sql(expression, "returning")
        if (returning.isNotEmpty()) returning = " RETURNING $returning"

        var onCondition = sql(expression, "on_condition")
        if (onCondition.isNotEmpty()) onCondition = " $onCondition"

        return func("JSON_VALUE", expression.thisArg, "$path$returning$onCondition")
    }

    // sqlglot: Generator.oncondition_sql
    open fun onconditionSql(expression: OnCondition): String {
        // Static options like "NULL ON ERROR" are stored as strings, in contrast to
        // "DEFAULT <expr> ON ERROR"
        val emptyArg = expression.args["empty"]
        var empty = if (emptyArg is Expression) "DEFAULT ${sql(emptyArg)} ON EMPTY" else sql(expression, "empty")

        val errorArg = expression.args["error"]
        var error = if (errorArg is Expression) "DEFAULT ${sql(errorArg)} ON ERROR" else sql(expression, "error")

        if (error.isNotEmpty() && empty.isNotEmpty()) {
            error = if (onConditionEmptyBeforeError) "$empty $error" else "$error $empty"
            empty = ""
        }

        val nullSql = sql(expression, "null")

        return "$empty$error$nullSql"
    }

    // sqlglot: Generator.introducer_sql
    open fun introducerSql(expression: Introducer): String =
        "${sql(expression, "this")} ${sql(expression, "expression")}"

    // sqlglot: Generator.hexstring_sql
    open fun hexstringSql(expression: HexString, binaryFunctionRepr: String? = null): String {
        val thisSql = sql(expression, "this")
        val isIntegerType = expression.args["is_integer"] == true

        if ((isIntegerType && !hexStringIsIntegerType) || (hexStart == null && binaryFunctionRepr == null)) {
            // Integer representation will be returned if:
            // - The read dialect treats the hex value as integer literal but not the write
            // - The transpilation is not supported (write dialect has no HEX_START/param flag)
            return thisSql.toLong(16).toString()
        }

        if (!isIntegerType) {
            // Read dialect treats the hex value as BINARY/BLOB
            if (binaryFunctionRepr != null) {
                return func(binaryFunctionRepr, Literal.string(thisSql))
            }
            if (hexStringIsIntegerType) {
                unsupported("Unsupported transpilation from BINARY/BLOB hex string")
            }
        }

        return "$hexStart$thisSql${hexEnd ?: ""}"
    }

    // sqlglot: Generator.tsordstodate_sql
    open fun tsordstodateSql(expression: TsOrDsToDate): String {
        val this_ = expression.thisArg as? Expression
        val timeFormat = formatTime(expression)
        val safe = expression.args["safe"]
        if (!timeFormat.isNullOrEmpty() && timeFormat != dialectTimeFormat && timeFormat != dialectDateFormat) {
            return sql(
                Cast(
                    args(
                        "this" to StrToTime(
                            args(
                                "this" to this_,
                                "format" to expression.args["format"],
                                "safe" to safe,
                            )
                        ),
                        "to" to DataType(args("this" to DType.DATE)),
                    )
                )
            )
        }

        if (this_ is TsOrDsToDate) return sql(this_)

        if (safe == true) {
            return sql(TryCast(args("this" to this_, "to" to DataType(args("this" to DType.DATE)))))
        }

        return sql(Cast(args("this" to this_, "to" to DataType(args("this" to DType.DATE)))))
    }

    // sqlglot: Generator.show_sql (base: unsupported)
    open fun showSql(expression: Show): String {
        unsupported("Unsupported SHOW statement")
        return ""
    }

    // sqlglot: Generator._escaped_byte_quote_end
    protected open val escapedByteQuoteEnd: String
        get() = byteEnd?.let { tokenizerConfig.stringEscapes.first() + it } ?: ""

    // sqlglot: Generator.bytestring_sql
    open fun bytestringSql(expression: ByteString): String {
        val this_ = when (val t = expression.thisArg) {
            null -> ""
            is String -> t
            else -> t.toString()
        }
        val start = byteStart
        if (start != null) {
            val escaped = escapeStr(
                this_,
                escapeBackslash = false,
                delimiter = byteEnd,
                escapedDelimiter = escapedByteQuoteEnd,
                isByteString = true,
            )
            val isBytes = expression.args["is_bytes"] == true
            val delimited = "$start$escaped${byteEnd ?: ""}"
            if (isBytes && !dialectByteStringIsBytesType) {
                return sql(
                    Cast(
                        args(
                            "this" to Literal.string(delimited),
                            "to" to DataType(args("this" to DType.BINARY)),
                        )
                    )
                )
            }
            if (!isBytes && dialectByteStringIsBytesType) {
                return sql(
                    Cast(
                        args(
                            "this" to Literal.string(delimited),
                            "to" to DataType(args("this" to DType.VARCHAR)),
                        )
                    )
                )
            }

            return delimited
        }

        if ('\\' in tokenizerConfig.stringEscapes) {
            return sql(Literal.string(this_))
        }

        unsupported("Byte strings are not supported for this dialect")
        return ""
    }

    // sqlglot: Generator.sequenceproperties_sql
    open fun sequencepropertiesSql(expression: SequenceProperties): String {
        var start = sql(expression, "start")
        if (start.isNotEmpty()) start = "START WITH $start"
        var increment = sql(expression, "increment")
        if (increment.isNotEmpty()) increment = " INCREMENT BY $increment"
        var minvalue = sql(expression, "minvalue")
        if (minvalue.isNotEmpty()) minvalue = " MINVALUE $minvalue"
        var maxvalue = sql(expression, "maxvalue")
        if (maxvalue.isNotEmpty()) maxvalue = " MAXVALUE $maxvalue"
        var owned = sql(expression, "owned")
        if (owned.isNotEmpty()) owned = " OWNED BY $owned"

        val cache = expression.args["cache"]
        val cacheStr = when (cache) {
            null -> ""
            true -> " CACHE"
            else -> " CACHE ${sql(cache)}"
        }

        var options = expressions(expression, key = "options", flat = true, sep = " ")
        if (options.isNotEmpty()) options = " $options"

        return "$start$increment$minvalue$maxvalue$cacheStr$options$owned".trimStart()
    }

    // sqlglot: Generator.when_sql
    open fun whenSql(expression: When): String {
        val matched = if (expression.args["matched"] == true) "MATCHED" else "NOT MATCHED"
        val source = if (matchedBySource && expression.args["source"] == true) " BY SOURCE" else ""
        var condition = sql(expression, "condition")
        if (condition.isNotEmpty()) condition = " AND $condition"

        val thenExpression = expression.args["then"]
        var then: String
        if (thenExpression is Insert) {
            var this_ = sql(thenExpression, "this")
            this_ = if (this_.isNotEmpty()) "INSERT $this_" else "INSERT"
            val thenExpr = sql(thenExpression, "expression")
            then = if (thenExpr.isNotEmpty()) "$this_ VALUES $thenExpr" else this_
        } else if (thenExpression is Update) {
            if (thenExpression.args["expressions"] is Star) {
                then = "UPDATE ${sql(thenExpression, "expressions")}"
            } else {
                val exprsSql = expressions(thenExpression)
                then = if (exprsSql.isNotEmpty()) "UPDATE SET${sep()}$exprsSql" else "UPDATE"
            }
        } else {
            then = sql(thenExpression)
        }

        if (thenExpression is Insert || thenExpression is Update) {
            var where = sql(thenExpression as Expression, "where")
            if (where.isNotEmpty() && !supportsMergeWhere) {
                val kind = if (thenExpression is Insert) "INSERT" else "UPDATE"
                unsupported("WHERE clause in MERGE $kind is not supported")
                where = ""
            }
            then = "$then$where"
        }
        return "WHEN $matched$source$condition THEN $then"
    }

    // sqlglot: Generator.whens_sql
    open fun whensSql(expression: Whens): String =
        expressions(expression, sep = " ", indent = false)

    // sqlglot: Generator.merge_sql
    open fun mergeSql(expression: Merge): String {
        val table = expression.thisArg as Expression
        var tableAlias = ""

        val hints = table.args["hints"] as? List<*>
        val aliasArg = table.args["alias"]
        if (!hints.isNullOrEmpty() && aliasArg != null && hints[0] is WithTableHint) {
            // T-SQL syntax is MERGE ... <target_table> [WITH (<merge_hint>)] [[AS] table_alias]
            val popped = (aliasArg as Expression).also { it.pop() }
            tableAlias = " AS ${sql(popped)}"
        }

        val this_ = sql(table)
        val using = "USING ${sql(expression, "using")}"
        var whens = sql(expression, "whens")

        var on = sql(expression, "on")
        if (on.isNotEmpty()) {
            on = "ON $on"
        } else {
            on = expressions(expression, key = "using_cond")
            if (on.isNotEmpty()) on = "USING ($on)"
        }

        val returning = sql(expression, "returning")
        if (returning.isNotEmpty()) {
            whens = "$whens$returning"
        }

        val sepStr = sep()

        return prependCtes(
            expression,
            "MERGE INTO $this_$tableAlias$sepStr$using$sepStr$on$sepStr$whens",
        )
    }

    // sqlglot: Generator.ARRAY_SIZE_NAME
    open val arraySizeName: String get() = "ARRAY_LENGTH"

    // sqlglot: Generator.ARRAY_SIZE_DIM_REQUIRED
    open val arraySizeDimRequired: Boolean? get() = null

    // sqlglot: Generator.arraysize_sql
    open fun arraysizeSql(expression: ArraySize): String {
        var dim = expression.args["expression"] as? Expression

        // For dialects that don't support the dimension arg, we can safely transpile
        // its default value (1st dimension)
        if (dim != null && arraySizeDimRequired == null) {
            val isIntOne = dim is Literal && !dim.isString && dim.name == "1"
            if (!isIntOne) {
                unsupported("Cannot transpile dimension argument for ARRAY_LENGTH")
            }
            dim = null
        }

        // If dimension is required but not specified, default initialize it
        if (arraySizeDimRequired == true && dim == null) {
            dim = Literal.number("1")
        }

        return func(arraySizeName, expression.thisArg, dim)
    }

    // sqlglot: Generator.fromiso8601date_sql
    open fun fromiso8601dateSql(expression: FromISO8601Date): String =
        sql(
            Cast(
                args("this" to expression.thisArg, "to" to DataType(args("this" to DType.DATE)))
            )
        )

    // sqlglot: Generator.fromiso8601timestamp_sql
    open fun fromiso8601timestampSql(expression: FromISO8601Timestamp): String =
        sql(
            Cast(
                args(
                    "this" to expression.thisArg,
                    "to" to DataType(args("this" to DType.TIMESTAMPTZ)),
                )
            )
        )

    // sqlglot: Generator.fromiso8601timestampnanos_sql
    open fun fromiso8601timestampnanosSql(expression: FromISO8601TimestampNanos): String =
        sql(
            Cast(
                args(
                    "this" to expression.thisArg,
                    "to" to DataType(args("this" to DType.TIMESTAMPTZ)),
                )
            )
        )

    // sqlglot: Generator.jsonarray_sql
    open fun jsonarraySql(expression: JSONArray): String {
        var nullHandling = sql(expression, "null_handling")
        if (nullHandling.isNotEmpty()) nullHandling = " $nullHandling"
        var returnType = sql(expression, "return_type")
        if (returnType.isNotEmpty()) returnType = " RETURNING $returnType"
        val strict = if (expression.args["strict"] == true) " STRICT" else ""
        return func(
            "JSON_ARRAY",
            *expression.expressionsArg.toTypedArray(),
            suffix = "$nullHandling$returnType$strict)",
        )
    }

    // sqlglot: Generator.localtime_sql
    open fun localtimeSql(expression: Localtime): String {
        val this_ = expression.thisArg
        return if (this_ != null) func("LOCALTIME", this_) else "LOCALTIME"
    }

    // sqlglot: Generator.localtimestamp_sql
    open fun localtimestampSql(expression: Localtimestamp): String {
        val this_ = expression.thisArg
        return if (this_ != null) func("LOCALTIMESTAMP", this_) else "LOCALTIMESTAMP"
    }

    // sqlglot: Generator.columns_sql
    open fun columnsSql(expression: Columns): String {
        var funcSql = functionFallbackSql(expression)
        if (expression.args["unpack"] == true) funcSql = "*$funcSql"
        return funcSql
    }

    // sqlglot: Generator.historicaldata_sql
    open fun historicaldataSql(expression: HistoricalData): String {
        val this_ = sql(expression, "this")
        val kind = sql(expression, "kind")
        val expr = sql(expression, "expression")
        return "$this_ ($kind => $expr)"
    }

    // sqlglot: Generator.install_sql (base: unsupported)
    open fun installSql(expression: Install): String {
        unsupported("Unsupported INSTALL statement")
        return ""
    }

    // sqlglot: Generator.summarize_sql
    open fun summarizeSql(expression: Summarize): String {
        val table = if (expression.args["table"] == true) " TABLE" else ""
        return "SUMMARIZE$table ${sql(expression.thisArg)}"
    }

    // sqlglot: Generator.attach_sql
    open fun attachSql(expression: Attach): String {
        val this_ = sql(expression, "this")
        val existsSql = if (expression.args["exists"] == true) " IF NOT EXISTS" else ""
        var exprs = expressions(expression)
        if (exprs.isNotEmpty()) exprs = " ($exprs)"

        return "ATTACH$existsSql $this_$exprs"
    }

    // sqlglot: Generator.detach_sql
    open fun detachSql(expression: Detach): String {
        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = " $kind"
        // the DATABASE keyword is required if IF EXISTS is set for DuckDB
        // ref: https://duckdb.org/docs/stable/sql/statements/attach.html#detach-syntax
        val exists = if (expression.args["exists"] == true) " IF EXISTS" else ""
        if (exists.isNotEmpty() && kind.isEmpty()) {
            kind = " DATABASE"
        }

        var this_ = sql(expression, "this")
        if (this_.isNotEmpty()) this_ = " $this_"
        var cluster = sql(expression, "cluster")
        if (cluster.isNotEmpty()) cluster = " $cluster"
        val permanent = if (expression.args["permanent"] == true) " PERMANENTLY" else ""
        val sync = if (expression.args["sync"] == true) " SYNC" else ""
        return "DETACH$kind$exists$this_$cluster$permanent$sync"
    }

    // sqlglot: Generator.attachoption_sql
    open fun attachoptionSql(expression: AttachOption): String {
        val this_ = sql(expression, "this")
        var value = sql(expression, "expression")
        if (value.isNotEmpty()) value = " $value"
        return "$this_$value"
    }

    // sqlglot: Generator.macrooverloads_sql
    open fun macrooverloadsSql(expression: MacroOverloads): String =
        expressions(expression, flat = true)

    // sqlglot: Generator.macrooverload_sql
    open fun macrooverloadSql(expression: MacroOverload): String {
        val params = noIdentify { expressions(expression, flat = true) }
        val body = sql(expression, "this")
        val prefix = if (expression.args["is_table"] == true) "TABLE " else ""
        return "($params) AS $prefix$body"
    }

    // sqlglot: Generator.COPY_PARAMS_ARE_WRAPPED
    open val copyParamsAreWrapped: Boolean get() = true

    // sqlglot: Generator.COPY_PARAMS_EQ_REQUIRED
    open val copyParamsEqRequired: Boolean get() = false

    // sqlglot: Generator.COPY_HAS_INTO_KEYWORD
    open val copyHasIntoKeyword: Boolean get() = true

    // sqlglot: Dialect.COPY_PARAMS_ARE_CSV (generator-side read)
    open val dialectCopyParamsAreCsv: Boolean get() = true

    // sqlglot: Generator.copyparameter_sql
    open fun copyparameterSql(expression: CopyParameter): String {
        val option = sql(expression, "this")

        val exprsArg = expression.args["expressions"]
        if (exprsArg is List<*> && exprsArg.isNotEmpty()) {
            val upper = option.uppercase()

            // Snowflake FILE_FORMAT options are separated by whitespace
            val sep = if (upper == "FILE_FORMAT") " " else ", "

            // Databricks copy/format options do not set their list of values with EQ
            val op = if (upper in setOf("COPY_OPTIONS", "FORMAT_OPTIONS")) " " else " = "
            val values = expressions(expression, flat = true, sep = sep)
            return "$option$op($values)"
        }

        val value = sql(expression, "expression")

        if (value.isEmpty()) return option

        val op = if (copyParamsEqRequired) " = " else " "

        return "$option$op$value"
    }

    // sqlglot: Generator.credentials_sql
    open fun credentialsSql(expression: Credentials): String {
        val credExpr = expression.args["credentials"]
        var credentials: String
        if (credExpr is Literal) {
            // Redshift case: CREDENTIALS <string>
            credentials = sql(expression, "credentials")
            if (credentials.isNotEmpty()) credentials = "CREDENTIALS $credentials"
        } else {
            // Snowflake case: CREDENTIALS = (...)
            val creds = expressions(expression, key = "credentials", flat = true, sep = " ")
            credentials = if (credExpr != null) "CREDENTIALS = ($creds)" else ""
        }

        var storage = sql(expression, "storage")
        if (storage.isNotEmpty()) storage = "STORAGE_INTEGRATION = $storage"

        var encryption = expressions(expression, key = "encryption", flat = true, sep = " ")
        if (encryption.isNotEmpty()) encryption = " ENCRYPTION = ($encryption)"

        var iamRole = sql(expression, "iam_role")
        if (iamRole.isNotEmpty()) iamRole = "IAM_ROLE $iamRole"

        var region = sql(expression, "region")
        if (region.isNotEmpty()) region = " REGION $region"

        return "$credentials$storage$encryption$iamRole$region"
    }

    // sqlglot: Generator.copy_sql
    open fun copySql(expression: Copy): String {
        var this_ = sql(expression, "this")
        this_ = if (copyHasIntoKeyword) " INTO $this_" else " $this_"

        var credentials = sql(expression, "credentials")
        if (credentials.isNotEmpty()) credentials = seg(credentials)
        val files = expressions(expression, key = "files", flat = true)
        val kind = if (files.isNotEmpty()) {
            seg(if (expression.args["kind"] == true) "FROM" else "TO")
        } else ""

        val sep = if (dialectCopyParamsAreCsv) ", " else " "
        var params = expressions(
            expression,
            key = "params",
            sep = sep,
            newLine = true,
            skipLast = true,
            skipFirst = true,
            indent = copyParamsAreWrapped,
        )

        if (params.isNotEmpty()) {
            if (copyParamsAreWrapped) {
                params = " WITH ($params)"
            } else if (!pretty && (files.isNotEmpty() || credentials.isNotEmpty())) {
                params = " $params"
            }
        }

        return "COPY$this_$kind $files$credentials$params"
    }

    // sqlglot: Generator.unpivotcolumns_sql
    open fun unpivotcolumnsSql(expression: UnpivotColumns): String {
        val name = sql(expression, "this")
        val values = expressions(expression, flat = true)

        return "NAME $name VALUE $values"
    }

    // sqlglot: Generator.partitionbyrangeproperty_sql
    open fun partitionbyrangepropertySql(expression: PartitionByRangeProperty): String {
        val partitions = expressions(expression, key = "partition_expressions")
        val create = expressions(expression, key = "create_expressions")
        return "PARTITION BY RANGE ${wrap(partitions)} ${wrap(create)}"
    }

    // sqlglot: Generator.addconstraint_sql
    open fun addconstraintSql(expression: AddConstraint): String =
        "ADD ${expressions(expression, indent = false)}"

    // sqlglot: Generator.addpartition_sql
    open fun addpartitionSql(expression: AddPartition): String {
        val exists = if (expression.args["exists"] == true) "IF NOT EXISTS " else ""
        var location = sql(expression, "location")
        if (location.isNotEmpty()) location = " $location"
        return "ADD $exists${sql(expression.thisArg)}$location"
    }

    // sqlglot: Generator.distinct_sql (base: MULTI_ARG_DISTINCT=true)
    open fun distinctSql(expression: Distinct): String {
        var thisSql = expressions(expression, flat = true)
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        var on = sql(expression, "on")
        if (on.isNotEmpty()) on = " ON $on"
        return "DISTINCT$thisSql$on"
    }

    // sqlglot: Generator.ignorenulls_sql
    open fun ignorenullsSql(expression: IgnoreNulls): String =
        embedIgnoreNulls(expression, "IGNORE NULLS")

    // sqlglot: Generator.respectnulls_sql
    open fun respectnullsSql(expression: RespectNulls): String =
        embedIgnoreNulls(expression, "RESPECT NULLS")

    // sqlglot: Generator.IGNORE_NULLS_BEFORE_ORDER
    open val ignoreNullsBeforeOrder: Boolean get() = true

    // sqlglot: Generator.RESPECT_IGNORE_NULLS_UNSUPPORTED_EXPRESSIONS (base: empty)
    open val respectIgnoreNullsUnsupportedExpressions: Set<KClass<out Expression>>
        get() = emptySet()

    // sqlglot: Generator._embed_ignore_nulls
    protected open fun embedIgnoreNulls(expression: Expression, text: String): String {
        var this_ = expression.args["this"]
        if (this_ is Expression && this_::class in respectIgnoreNullsUnsupportedExpressions) {
            unsupported("RESPECT/IGNORE NULLS is not supported for ${this_::class.simpleName}")
            return sql(this_)
        }

        if (ignoreNullsInFunc && expression.metaOrNull?.get("inline") != true) {
            if (ignoreNullsBeforeOrder) {
                // The first modifier here will be the one closest to the AggFunc's arg
                val mods = expression.findAll(HavingMax::class, Order::class, Limit::class)
                    .sortedBy { if (it is HavingMax) 0 else if (it is Order) 1 else 2 }
                    .toList()

                val mod = mods.firstOrNull()
                if (mod != null) {
                    val modThis = mod.args["this"] as Expression
                    val wrapped = when (expression) {
                        is IgnoreNulls -> IgnoreNulls(args("this" to modThis.copy()))
                        else -> RespectNulls(args("this" to modThis.copy()))
                    }
                    wrapped.meta["inline"] = true
                    modThis.replace(wrapped)
                    return sql(expression.args["this"])
                }
            }

            val aggFunc = expression.walk().firstOrNull { it is AggFunc }

            if (aggFunc != null) {
                val aggFuncSql = sql(aggFunc, comment = false).dropLast(1) + " $text)"
                return maybeComment(aggFuncSql, comments = aggFunc.comments)
            }
        }

        return "${sql(expression, "this")} $text"
    }

    // sqlglot: Generator.havingmax_sql
    open fun havingmaxSql(expression: HavingMax): String {
        val thisSql = sql(expression, "this")
        val expressionSql = sql(expression, "expression")
        val kind = if (expression.args["max"] == true) "MAX" else "MIN"
        return "$thisSql HAVING $kind $expressionSql"
    }

    // sqlglot: Generator.intdiv_sql
    open fun intdivSql(expression: IntDiv): String = sql(
        Cast(
            args(
                "this" to Div(args("this" to expression.args["this"], "expression" to expression.args["expression"])),
                "to" to DataType(args("this" to DType.INT)),
            )
        )
    )

    // sqlglot: Generator.dpipe_sql (base: STRICT_STRING_CONCAT=false)
    open fun dpipeSql(expression: DPipe): String {
        if (dialectStrictStringConcat && expression.args["safe"] == true) {
            val operands = mutableListOf<Expression>()
            // sqlglot: expression.flatten() (same-class binary chain, parens unnested)
            fun collect(node: Expression) {
                if (node is DPipe) {
                    collect(node.left)
                    collect(node.right)
                } else {
                    operands.add(node.unnest())
                }
            }
            collect(expression)
            return func(
                "CONCAT",
                *operands.map { e ->
                    Cast(args("this" to e, "to" to DataType(args("this" to DType.TEXT))))
                }.toTypedArray(),
            )
        }
        return binary(expression, "||")
    }

    // sqlglot: Generator.div_sql (base: SAFE_DIVISION=false, TYPED_DIVISION=false)
    open fun divSql(expression: Div): String {
        val l = expression.left
        if (!dialectSafeDivision && expression.args["safe"] == true) {
            val r = expression.right
            r.replace(Nullif(args("this" to r.copy(), "expression" to Literal.number("0"))))
        }

        // sqlglot: div_sql TYPED_DIVISION handling. Untyped ASTs answer is_type()=False
        // like Python's un-annotated expressions; Cast nodes check their target type.
        if (dialectTypedDivision && expression.args["typed"] != true) {
            if (!isCastToType(l, REAL_TYPES) && !isCastToType(expression.right, REAL_TYPES)) {
                l.replace(
                    Cast(args("this" to l.copy(), "to" to DataType(args("this" to DType.DOUBLE))))
                )
            }
        } else if (!dialectTypedDivision && expression.args["typed"] == true) {
            if (isCastToType(l, INTEGER_TYPES) && isCastToType(expression.right, INTEGER_TYPES)) {
                return sql(
                    Cast(
                        args(
                            "this" to expression.copy(),
                            "to" to DataType(args("this" to DType.BIGINT)),
                        )
                    )
                )
            }
        }

        return binary(expression, "/")
    }

    // sqlglot: Expression.is_type — only Cast carries syntactic type info in untyped ASTs
    protected fun isCastToType(expression: Expression?, types: kotlin.collections.Set<DType>): Boolean {
        val cast = expression as? Cast ?: return false
        return (cast.args["to"] as? Expression)?.thisArg in types
    }

    // sqlglot: Generator.overlaps_sql
    open fun overlapsSql(expression: Overlaps): String = binary(expression, "OVERLAPS")

    // sqlglot: Generator.dot_sql
    open fun dotSql(expression: Dot): String =
        "${sql(expression, "this")}.${sql(expression, "expression")}"

    // sqlglot: Generator.escape_sql (base: SUPPORTS_LIKE_QUANTIFIERS=true)
    open fun escapeSql(expression: Escape): String = binary(expression, "ESCAPE")

    // sqlglot: Generator.is_sql (base: IS_BOOL_ALLOWED=true)
    open fun isSql(expression: Is): String {
        if (!isBoolAllowed && expression.expressionArg is BooleanNode) {
            val rhs = expression.expressionArg as BooleanNode
            return if (rhs.thisArg == true) sql(expression, "this")
            else sql(Not(args("this" to expression.args["this"])))
        }
        return binary(expression, "IS")
    }

    // sqlglot: Generator._like_sql (base: SUPPORTS_LIKE_QUANTIFIERS=true -> plain binary)
    protected open fun likeOpSql(expression: Binary, op: String): String {
        val negatedOp = if (expression.args["negate"] == true) "NOT $op" else op
        return binary(expression, negatedOp)
    }

    // sqlglot: Generator.like_sql
    open fun likeSql(expression: Like): String = likeOpSql(expression, "LIKE")

    // sqlglot: Generator.ilike_sql
    open fun ilikeSql(expression: ILike): String = likeOpSql(expression, "ILIKE")

    // sqlglot: Generator.trycast_sql
    open fun trycastSql(expression: TryCast): String = castSql(expression, safePrefix = "TRY_")

    // sqlglot: Generator.try_sql (base: TRY_SUPPORTED=true)
    open fun trySql(expression: Try): String = func("TRY", expression.thisArg)

    // sqlglot: Generator.log_sql (base: LOG_BASE_FIRST=true)
    open fun logSql(expression: Log): String =
        func("LOG", expression.thisArg, expression.expressionArg)

    // sqlglot: Generator.use_sql
    open fun useSql(expression: Use): String {
        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = " $kind"
        var thisSql = sql(expression, "this").ifEmpty { expressions(expression, flat = true) }
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        return "USE$kind$thisSql"
    }

    // sqlglot: Generator.ceil_floor
    open fun ceilFloor(expression: Expression): String {
        val toClause = sql(expression, "to")
        if (toClause.isNotEmpty()) {
            return "${(expression as Func).sqlName()}(${sql(expression, "this")} TO $toClause)"
        }
        return functionFallbackSql(expression as Func)
    }

    // sqlglot: Generator.userdefinedfunction_sql
    open fun userdefinedfunctionSql(expression: UserDefinedFunction): String {
        val thisSql = sql(expression, "this")
        val exprsRaw = noIdentify { expressions(expression) }
        val exprs = if (expression.args["wrapped"] == true) wrap(exprsRaw) else " $exprsRaw"
        return if (exprs.trim().isNotEmpty()) "$thisSql$exprs" else thisSql
    }

    // sqlglot: Generator.joinhint_sql
    open fun joinhintSql(expression: JoinHint): String {
        val thisSql = sql(expression, "this")
        val exprs = expressions(expression, flat = true)
        return "$thisSql($exprs)"
    }

    // sqlglot: Generator.kwarg_sql
    open fun kwargSql(expression: Kwarg): String = binary(expression, "=>")

    // sqlglot: Generator.tochar_sql
    open fun tocharSql(expression: ToChar): String =
        sql(Cast(args("this" to expression.args["this"], "to" to DataType(args("this" to DType.TEXT)))))

    // sqlglot: Generator.dictproperty_sql
    open fun dictpropertySql(expression: DictProperty): String {
        val thisSql = sql(expression, "this")
        val kind = sql(expression, "kind")
        val settingsSql = expressions(expression, key = "settings", sep = " ")
        val argsSql = if (settingsSql.isNotEmpty()) {
            "(${sep("")}$settingsSql${seg(")", sep = "")}"
        } else "()"
        return "$thisSql($kind$argsSql)"
    }

    // sqlglot: Generator.dictrange_sql
    open fun dictrangeSql(expression: DictRange): String {
        val thisSql = sql(expression, "this")
        val max = sql(expression, "max")
        val min = sql(expression, "min")
        return "$thisSql(MIN $min MAX $max)"
    }

    // sqlglot: Generator.dictsubproperty_sql
    open fun dictsubpropertySql(expression: DictSubProperty): String =
        "${sql(expression, "this")} ${sql(expression, "value")}"

    // sqlglot: Generator.oncluster_sql
    open fun onclusterSql(expression: OnCluster): String = ""

    // sqlglot: Generator.clusteredbyproperty_sql
    open fun clusteredbypropertySql(expression: ClusteredByProperty): String {
        val exprs = expressions(expression, key = "expressions", flat = true)
        var sortedBy = expressions(expression, key = "sorted_by", flat = true)
        if (sortedBy.isNotEmpty()) sortedBy = " SORTED BY ($sortedBy)"
        val buckets = sql(expression, "buckets")
        return "CLUSTERED BY ($exprs)$sortedBy INTO $buckets BUCKETS"
    }

    // sqlglot: Generator.anyvalue_sql
    open fun anyvalueSql(expression: AnyValue): String {
        var thisSql = sql(expression, "this")
        val having = sql(expression, "having")
        if (having.isNotEmpty()) {
            thisSql = "$thisSql HAVING ${if (expression.args["max"] == true) "MAX" else "MIN"} $having"
        }
        return func("ANY_VALUE", thisSql)
    }

    // sqlglot: Generator.comprehension_sql
    open fun comprehensionSql(expression: Comprehension): String {
        val thisSql = sql(expression, "this")
        val expr = sql(expression, "expression")
        var position = sql(expression, "position")
        if (position.isNotEmpty()) position = ", $position"
        val iterator = sql(expression, "iterator")
        var condition = sql(expression, "condition")
        if (condition.isNotEmpty()) condition = " IF $condition"
        return "$thisSql FOR $expr$position IN $iterator$condition"
    }

    // sqlglot: Generator.columnprefix_sql
    open fun columnprefixSql(expression: ColumnPrefix): String =
        "${sql(expression, "this")}(${sql(expression, "expression")})"

    // sqlglot: Generator.opclass_sql
    open fun opclassSql(expression: Opclass): String =
        "${sql(expression, "this")} ${sql(expression, "expression")}"

    // sqlglot: Generator.parsejson_sql (base: PARSE_JSON_NAME="PARSE_JSON")
    open fun parsejsonSql(expression: ParseJSON): String =
        func(parseJsonName ?: return sql(expression.thisArg), expression.thisArg, expression.expressionArg)

    // sqlglot: Generator.rand_sql
    open fun randSql(expression: Rand): String {
        val lower = sql(expression, "lower")
        val upper = sql(expression, "upper")
        if (lower.isNotEmpty() && upper.isNotEmpty()) {
            return "($upper - $lower) * ${func("RAND", expression.thisArg)} + $lower"
        }
        return func("RAND", expression.thisArg)
    }

    // sqlglot: Generator.pad_sql (base: PAD_FILL_PATTERN_IS_REQUIRED=false)
    open fun padSql(expression: Pad): String {
        val prefix = if (expression.args["is_left"] == true) "L" else "R"
        var fillPattern: kotlin.Any? = sql(expression, "fill_pattern").ifEmpty { null }
        if (fillPattern == null && padFillPatternIsRequired) fillPattern = "' '"
        return func("${prefix}PAD", expression.thisArg, expression.expressionArg, fillPattern)
    }

    // sqlglot: Generator.arrayagg_sql (base: ARRAY_AGG_INCLUDES_NULLS=true; the NULL
    // filter only fires when nulls_excluded is set, which base parsing doesn't do)
    open fun arrayaggSql(expression: ArrayAgg): String {
        val arrayAgg = functionFallbackSql(expression)
        if (dialectArrayAggIncludesNulls && expression.args["nulls_excluded"] == true) {
            unsupported("ARRAY_AGG null filtering is not supported")
        }
        return arrayAgg
    }

    // sqlglot: Generator.slice_sql
    open fun sliceSql(expression: Slice): String {
        val step = sql(expression, "step")
        val end = sql(expression.expressionArg)
        val begin = sql(expression.thisArg)
        val sqlText = if (step.isNotEmpty()) "$end:$step" else end
        return if (sqlText.isNotEmpty()) "$begin:$sqlText" else "$begin:"
    }

    // sqlglot: Generator._grant_or_revoke_sql
    protected open fun grantOrRevokeSql(
        expression: Expression,
        keyword: String,
        preposition: String,
        grantOptionPrefix: String = "",
        grantOptionSuffix: String = "",
    ): String {
        val privilegesSql = expressions(expression, key = "privileges", flat = true)

        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = " $kind"

        var securable = sql(expression, "securable")
        if (securable.isNotEmpty()) securable = " $securable"

        val principals = expressions(expression, key = "principals", flat = true)

        var prefix = grantOptionPrefix
        var suffix = grantOptionSuffix
        if (expression.args["grant_option"] != true) {
            prefix = ""
            suffix = ""
        }

        // cascade for revoke only
        var cascade = sql(expression, "cascade")
        if (cascade.isNotEmpty()) cascade = " $cascade"

        return "$keyword $prefix$privilegesSql ON$kind$securable $preposition $principals$suffix$cascade"
    }

    // sqlglot: Generator.grant_sql
    open fun grantSql(expression: Grant): String = grantOrRevokeSql(
        expression,
        keyword = "GRANT",
        preposition = "TO",
        grantOptionSuffix = " WITH GRANT OPTION",
    )

    // sqlglot: Generator.revoke_sql
    open fun revokeSql(expression: Revoke): String = grantOrRevokeSql(
        expression,
        keyword = "REVOKE",
        preposition = "FROM",
        grantOptionPrefix = "GRANT OPTION FOR ",
    )

    // sqlglot: Generator.grantprivilege_sql
    open fun grantprivilegeSql(expression: GrantPrivilege): String {
        val thisSql = sql(expression, "this")
        var columns = expressions(expression, flat = true)
        if (columns.isNotEmpty()) columns = "($columns)"
        return "$thisSql$columns"
    }

    // sqlglot: Generator.grantprincipal_sql
    open fun grantprincipalSql(expression: GrantPrincipal): String {
        val thisSql = sql(expression, "this")
        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = "$kind "
        return "$kind$thisSql"
    }

    // sqlglot: Generator.median_sql (base: SUPPORTS_MEDIAN=true)
    open fun medianSql(expression: Median): String = functionFallbackSql(expression)

    // sqlglot: Generator.analyzesample_sql
    open fun analyzesampleSql(expression: AnalyzeSample): String {
        val kind = sql(expression, "kind")
        val sample = sql(expression, "sample")
        return "SAMPLE $sample $kind"
    }

    // sqlglot: Generator.analyzestatistics_sql
    open fun analyzestatisticsSql(expression: AnalyzeStatistics): String {
        val kind = sql(expression, "kind")
        var option = sql(expression, "option")
        if (option.isNotEmpty()) option = " $option"
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        var columns = expressions(expression)
        if (columns.isNotEmpty()) columns = " $columns"
        return "$kind$option STATISTICS$thisSql$columns"
    }

    // sqlglot: Generator.analyzehistogram_sql
    open fun analyzehistogramSql(expression: AnalyzeHistogram): String {
        val thisSql = sql(expression, "this")
        val columns = expressions(expression)
        var innerExpression = sql(expression, "expression")
        if (innerExpression.isNotEmpty()) innerExpression = " $innerExpression"
        var updateOptions = sql(expression, "update_options")
        if (updateOptions.isNotEmpty()) updateOptions = " $updateOptions UPDATE"
        return "$thisSql HISTOGRAM ON $columns$innerExpression$updateOptions"
    }

    // sqlglot: Generator.analyzedelete_sql
    open fun analyzedeleteSql(expression: AnalyzeDelete): String {
        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = " $kind"
        return "DELETE$kind STATISTICS"
    }

    // sqlglot: Generator.analyzelistchainedrows_sql
    open fun analyzelistchainedrowsSql(expression: AnalyzeListChainedRows): String {
        val innerExpression = sql(expression, "expression")
        return "LIST CHAINED ROWS$innerExpression"
    }

    // sqlglot: Generator.analyzevalidate_sql
    open fun analyzevalidateSql(expression: AnalyzeValidate): String {
        val kind = sql(expression, "kind")
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        val innerExpression = sql(expression, "expression")
        return "VALIDATE $kind$thisSql$innerExpression"
    }

    // sqlglot: Generator.analyze_sql
    open fun analyzeSql(expression: Analyze): String {
        var options = expressions(expression, key = "options", sep = " ")
        if (options.isNotEmpty()) options = " $options"
        var kind = sql(expression, "kind")
        if (kind.isNotEmpty()) kind = " $kind"
        var thisSql = sql(expression, "this")
        if (thisSql.isNotEmpty()) thisSql = " $thisSql"
        var mode = sql(expression, "mode")
        if (mode.isNotEmpty()) mode = " $mode"
        var properties = sql(expression, "properties")
        if (properties.isNotEmpty()) properties = " $properties"
        var partition = sql(expression, "partition")
        if (partition.isNotEmpty()) partition = " $partition"
        var innerExpression = sql(expression, "expression")
        if (innerExpression.isNotEmpty()) innerExpression = " $innerExpression"
        return "ANALYZE$options$kind$thisSql$partition$mode$innerExpression$properties"
    }

    // sqlglot: Generator.struct_sql
    open fun structSql(expression: Struct): String = baseStructSql(expression)

    // Base impl callable by grandchild dialects (sqlglot: Generator.struct_sql(self, e)).
    protected fun baseStructSql(expression: Struct): String {
        expression.set(
            "expressions",
            expression.expressionsArg.map { e ->
                if (e is PropertyEQ) {
                    val alias = if ((e.args["this"] as? Expression)?.isString == true) {
                        Identifier(args("this" to e.name))
                    } else {
                        e.args["this"]
                    }
                    Alias(args("this" to e.args["expression"], "alias" to alias))
                } else e
            },
        )
        return functionFallbackSql(expression)
    }

    // sqlglot: Generator.partitionrange_sql
    open fun partitionrangeSql(expression: PartitionRange): String =
        "${sql(expression, "this")} TO ${sql(expression, "expression")}"

    // sqlglot: Generator.chr_sql
    open fun chrSql(expression: Chr, name: String = "CHR"): String {
        val thisSql = expressions(expression)
        val charset = sql(expression, "charset")
        val using = if (charset.isNotEmpty()) " USING $charset" else ""
        return func(name, thisSql + using)
    }

    // sqlglot: Generator.property_name
    open fun propertyName(expression: Property, stringKey: Boolean = false): String {
        if (expression.thisArg is Dot) return sql(expression, "this")
        return if (stringKey) "'${expression.name}'" else expression.name
    }

    // sqlglot: Generator.property_sql
    open fun propertySql(expression: Property): String {
        if (expression::class == Property::class) {
            return "${propertyName(expression)}=${sql(expression, "value")}"
        }
        val propertyName = GeneratorTables.PROPERTY_TO_NAME[expression::class]
        if (propertyName == null) {
            unsupported("Unsupported property ${expression.key}")
        }
        return "$propertyName=${sql(expression, "this")}"
    }

    // sqlglot: Generator.duplicatekeyproperty_sql
    open fun duplicatekeypropertySql(expression: DuplicateKeyProperty): String =
        "DUPLICATE KEY (${expressions(expression, flat = true)})"

    // sqlglot: Generator.uniquekeyproperty_sql
    // https://docs.starrocks.io/docs/sql-reference/sql-statements/table_bucket_part_index/CREATE_TABLE/
    open fun uniquekeypropertySql(expression: UniqueKeyProperty, prefix: String = "UNIQUE KEY"): String =
        "$prefix (${expressions(expression, flat = true)})"

    // sqlglot: Generator.distributedbyproperty_sql
    // https://docs.starrocks.io/docs/sql-reference/sql-statements/data-definition/CREATE_TABLE/#distribution_desc
    open fun distributedbypropertySql(expression: DistributedByProperty): String {
        var exprs = expressions(expression, flat = true)
        if (exprs.isNotEmpty()) exprs = " ${wrap(exprs)}"
        var buckets = sql(expression, "buckets")
        val kind = sql(expression, "kind")
        if (buckets.isNotEmpty()) buckets = " BUCKETS $buckets"
        val order = sql(expression, "order")
        return "DISTRIBUTED BY $kind$exprs$buckets$order"
    }

    // sqlglot: Generator.buildproperty_sql
    open fun buildpropertySql(expression: BuildProperty): String =
        "BUILD ${sql(expression, "this")}"

    // sqlglot: Generator.refreshtriggerproperty_sql
    open fun refreshtriggerpropertySql(expression: RefreshTriggerProperty): String {
        val method = sql(expression, "method")
        val kind = expression.args["kind"]
        if (kind == null || kind == false || kind == "") return "REFRESH $method"

        var every = sql(expression, "every")
        val unit = sql(expression, "unit")
        if (every.isNotEmpty()) every = " EVERY $every $unit"
        var starts = sql(expression, "starts")
        if (starts.isNotEmpty()) starts = " STARTS $starts"

        return "REFRESH $method ON $kind$every$starts"
    }

    // sqlglot: Generator.function_fallback_sql
    open fun functionFallbackSql(expression: Func): String {
        val node = expression as Expression
        val args = mutableListOf<kotlin.Any?>()
        for (key in node.argTypes.keys) {
            when (val argValue = node.args[key]) {
                is List<*> -> args.addAll(argValue)
                null -> {}
                else -> args.add(argValue)
            }
        }
        // sqlglot: PRESERVE_ORIGINAL_NAMES (ClickHouse) — regenerate with the exact
        // name the function was parsed with
        val name = if (dialectPreserveOriginalNames) {
            (node.metaOrNull?.get("name") as? String) ?: expression.sqlName()
        } else {
            expression.sqlName()
        }
        return func(name, *args.toTypedArray())
    }

    // sqlglot: Generator.parameterizedagg_sql
    open fun parameterizedaggSql(expression: ParameterizedAgg): String {
        val params = expressions(expression, key = "params", flat = true)
        return func(expression.name, *expression.expressionsArg.toTypedArray()) + "($params)"
    }

    // sqlglot: Generator.anonymousaggfunc_sql
    open fun anonymousaggfuncSql(expression: AnonymousAggFunc): String =
        func(expression.name, *expression.expressionsArg.toTypedArray())

    // sqlglot: Generator.apply_sql
    open fun applySql(expression: Apply): String {
        val thisSql = sql(expression, "this")
        val expr = sql(expression, "expression")

        return "$thisSql APPLY($expr)"
    }

    // sqlglot: Generator.uuidproperty_sql
    open fun uuidpropertySql(expression: UuidProperty): String =
        "UUID ${sql(expression, "this")}"

    // sqlglot: Generator.mergetreettl_sql
    open fun mergetreettlSql(expression: MergeTreeTTL): String {
        val where = sql(expression, "where")
        val group = sql(expression, "group")
        var aggregates = expressions(expression, key = "aggregates")
        aggregates = if (aggregates.isNotEmpty()) seg("SET") + seg(aggregates) else ""

        if (where.isEmpty() && group.isEmpty() && aggregates.isEmpty() &&
            expression.expressionsArg.size == 1
        ) {
            return "TTL ${expressions(expression, flat = true)}"
        }

        return "TTL${seg(expressions(expression))}$where$group$aggregates"
    }

    // sqlglot: Generator.dateadd_sql (uses dialect.unit_to_str)
    open fun dateaddSql(expression: DateAdd): String {
        // sqlglot: dialect.unit_to_str
        val unit = expression.args["unit"] as? Expression
        val unitStr: Expression? = when {
            unit == null -> Literal.string("DAY")
            unit is Placeholder || (unit !is Var && unit !is Literal) -> unit
            else -> Literal.string(unit.name)
        }
        return func("DATE_ADD", expression.thisArg, expression.args["expression"], unitStr)
    }

    // sqlglot: Generator.mergetreettlaction_sql
    open fun mergetreettlactionSql(expression: MergeTreeTTLAction): String {
        val thisSql = sql(expression, "this")
        val delete = if (expression.args["delete"] == true) " DELETE" else ""
        var recompress = sql(expression, "recompress")
        if (recompress.isNotEmpty()) recompress = " RECOMPRESS $recompress"
        var toDisk = sql(expression, "to_disk")
        if (toDisk.isNotEmpty()) toDisk = " TO DISK $toDisk"
        var toVolume = sql(expression, "to_volume")
        if (toVolume.isNotEmpty()) toVolume = " TO VOLUME $toVolume"

        return "$thisSql$delete$recompress$toDisk$toVolume"
    }

    // sqlglot: Generator.skipjsoncolumn_sql
    open fun skipjsoncolumnSql(expression: SkipJSONColumn): String {
        val regexp = if (expression.args["regexp"] == true) " REGEXP" else ""
        return "SKIP$regexp ${sql(expression.args["expression"])}"
    }

    // ------------------------------------------------------------------
    // 7. Pipe syntax (brikk-native — sqlglot has NO pipe generation: it desugars pipe
    //    queries at parse time. brikk keeps stages first-class, so PipeQuery trees can
    //    be re-generated as pipe SQL. Output is deterministic and re-parseable: the head
    //    followed by ` |> OPERATOR ...` per stage (newline-separated when pretty).
    // ------------------------------------------------------------------

    open fun pipequerySql(expression: PipeQuery, forceFromFirstHead: Boolean = false): String {
        val head = expression.thisArg as Expression
        val headSql = when {
            // A bare `SELECT * FROM x` head is the shape the parser synthesizes for a
            // leading `FROM x` — emit it FROM-first so the output re-parses identically.
            head is Select && isPipeBareStarFrom(head) -> sql(head, "from_").trimStart()
            forceFromFirstHead -> "FROM ${sql(head)}"
            else -> sql(head)
        }
        val stages = expression.expressionsArg.map { sql(it) }
        val sep = if (pretty) "\n" else " "
        return (listOf(headSql) + stages).joinToString(sep)
    }

    /** True for the parser-synthesized `SELECT * FROM ...` head shape (bare star + from only). */
    protected fun isPipeBareStarFrom(select: Select): Boolean {
        val exprs = select.expressionsArg
        if (exprs.size != 1) return false
        val star = exprs[0]
        if (star !is Star) return false
        if (star.args.values.any { it != null && it != false && !(it is List<*> && it.isEmpty()) }) return false
        if (select.args["from_"] !is From) return false
        return select.args.all { (key, value) ->
            key == "expressions" || key == "from_" ||
                value == null || value == false || (value is List<*> && value.isEmpty())
        }
    }

    /**
     * brikk pipe: `SELECT * FROM (<PipeQuery with a Subquery head>)` is the shape the
     * parser synthesizes when a leading `FROM (…) |> …` pipeline is consumed at the
     * table level — regenerate it as FROM-first pipe text, which re-parses identically.
     */
    protected open fun pipeSyntheticStarSelectSql(expression: Select): String? {
        if (!isPipeBareStarFrom(expression)) return null
        val from = expression.args["from_"] as? From ?: return null
        val subquery = from.thisArg as? Subquery ?: return null
        if (!subquery.isWrapper) return null
        val pipeQuery = subquery.thisArg as? PipeQuery ?: return null
        if (pipeQuery.thisArg !is Subquery) return null
        return pipequerySql(pipeQuery, forceFromFirstHead = true)
    }

    open fun pipeselectSql(expression: PipeSelect): String =
        "|> SELECT ${expressions(expression, flat = true)}"

    open fun pipeextendSql(expression: PipeExtend): String =
        "|> EXTEND ${expressions(expression, flat = true)}"

    open fun pipeasSql(expression: PipeAs): String =
        "|> AS ${sql(expression, "alias")}"

    open fun pipewhereSql(expression: PipeWhere): String {
        val where = expression.thisArg as Where
        return "|> WHERE ${sql(where, "this")}"
    }

    open fun pipeaggregateSql(expression: PipeAggregate): String {
        var sqlText = "|> AGGREGATE ${expressions(expression, flat = true)}"
        val group = expressions(expression, key = "group", flat = true)
        if (group.isNotEmpty()) {
            val keyword = if (expression.args["group_and_order"] == true) {
                "GROUP AND ORDER BY"
            } else {
                "GROUP BY"
            }
            sqlText += " $keyword $group"
        }
        return sqlText
    }

    open fun pipedistinctSql(expression: PipeDistinct): String = "|> DISTINCT"

    open fun pipeorderbySql(expression: PipeOrderBy): String {
        val order = expression.thisArg as Order
        return "|> ORDER BY ${expressions(order, flat = true)}"
    }

    open fun pipelimitSql(expression: PipeLimit): String {
        val limit = expression.thisArg as Limit
        var sqlText = "|> LIMIT ${sql(limit, "expression")}"
        val offset = expression.args["offset"] as? Offset
        if (offset != null) sqlText += " OFFSET ${sql(offset, "expression")}"
        return sqlText
    }

    open fun pipeoffsetSql(expression: PipeOffset): String {
        val offset = expression.thisArg as Offset
        return "|> OFFSET ${sql(offset, "expression")}"
    }

    // googlesql: pipe SET (docs/pipe-syntax.md ~664) — EQ elements render `col = expr`
    open fun pipesetSql(expression: PipeSet): String =
        "|> SET ${expressions(expression, flat = true)}"

    // googlesql: pipe DROP (docs/pipe-syntax.md ~713)
    open fun pipedropSql(expression: PipeDrop): String =
        "|> DROP ${expressions(expression, flat = true)}"

    // googlesql: pipe RENAME (docs/pipe-syntax.md ~763) — Alias elements render `old AS new`
    open fun piperenameSql(expression: PipeRename): String =
        "|> RENAME ${expressions(expression, flat = true)}"

    // googlesql: pipe CALL (docs/pipe-syntax.md ~1323)
    open fun pipecallSql(expression: PipeCall): String {
        val alias = sql(expression, "alias")
        val aliasSql = if (alias.isNotEmpty()) " AS $alias" else ""
        return "|> CALL ${sql(expression, "this")}$aliasSql"
    }

    // googlesql: pipe WINDOW (docs/pipe-syntax.md ~2361, deprecated alias of EXTEND)
    open fun pipewindowSql(expression: PipeWindow): String =
        "|> WINDOW ${expressions(expression, flat = true)}"

    open fun pipetablesampleSql(expression: PipeTableSample): String =
        "|> ${sql(expression, "this").trim()}"

    open fun pipepivotSql(expression: PipePivot): String =
        "|> ${expression.expressionsArg.joinToString(" ") { sql(it).trim() }}"

    open fun pipeunpivotSql(expression: PipeUnpivot): String =
        "|> ${expression.expressionsArg.joinToString(" ") { sql(it).trim() }}"

    open fun pipejoinSql(expression: PipeJoin): String =
        "|> ${sql(expression, "this").trim()}"

    open fun pipesetoperationSql(expression: PipeSetOperation): String {
        val opName = (expression.thisArg as String).uppercase()

        // Mirrors setOperation(): the dialect-default distinct-ness is left implicit so
        // the output re-parses to the same flags (base: SET_OP_DISTINCT_BY_DEFAULT=true).
        val distinct = expression.args["distinct"] as? Boolean ?: true
        val distinctOrAll = if (distinct) "" else " ALL"

        var sideKind = listOf(expression.text("side").uppercase(), expression.text("kind").uppercase())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (sideKind.isNotEmpty()) sideKind = "$sideKind "

        val byName = if (expression.args["by_name"] == true) " BY NAME" else ""
        var on = expressions(expression, key = "on", flat = true)
        if (on.isNotEmpty()) on = " ON ($on)"

        val queries = expression.expressionsArg.joinToString(", ") { "(${sql(it)})" }

        return "|> $sideKind$opName$distinctOrAll$byName$on $queries"
    }
}
