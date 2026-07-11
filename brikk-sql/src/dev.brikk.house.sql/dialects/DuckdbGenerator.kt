package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.parser.DuckdbTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set
import kotlin.reflect.KClass

// sqlglot: dialect.unit_to_str
private fun unitToStr(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression
        ?: return if (default.isNotEmpty()) Literal.string(default) else null

    if (unit is Placeholder || (unit !is Var && unit !is Literal)) return unit

    return Literal.string(unit.name)
}

/**
 * Port of sqlglot's DuckDBGenerator (reference/sqlglot/sqlglot/generators/duckdb.py).
 * TRANSFORMS entries live in [TRANSFORMS] (a dispatch-map overlay handed to the base
 * constructor); flag overrides are open-val overrides; multi-line methods below.
 *
 * NOT PORTED (no Kotlin equivalents of sqlglot's transforms/annotate_types yet):
 * the exp.Select preprocess pipeline (connect_by_to_recursive_cte,
 * _seq_to_range_in_generator), the exp.Pivot unqualify_columns preprocess and the
 * exp.Array inherit_struct_field_names preprocess; the Snowflake-oriented template
 * transpilations (ZIPF, NORMAL, MINHASH, BITMAP_x, ARRAYS_ZIP, STRTOK, ...).
 * Mismatches are ledgered.
 */
// sqlglot: generators.duckdb.DuckDBGenerator
open class DuckdbGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    normalizeFunctions: kotlin.Any = "upper",
    tokenizerConfig: TokenizerConfig = DuckdbTokenizerTables.CONFIG,
    // extra dispatch overlay for subclasses (sqlglot: further TRANSFORMS merges)
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
) : Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    normalizeFunctions = normalizeFunctions,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
) {

    // ------------------------------------------------------------------
    // Flags (sqlglot: DuckDBGenerator class attributes)
    // ------------------------------------------------------------------

    override val parameterToken: String get() = "$"
    override val namedPlaceholderToken: String get() = "$"
    override val joinHints: Boolean get() = false
    override val tableHints: Boolean get() = false
    override val queryHints: Boolean get() = false
    override val limitFetch: String get() = "LIMIT"
    override val structDelimiter: Pair<String, String> get() = "(" to ")"
    override val renameTableWithDb: Boolean get() = false
    override val semiAntiJoinWithSide: Boolean get() = false
    override val tablesampleKeywords: String get() = "USING SAMPLE"
    override val tablesampleSeedKeyword: String get() = "REPEATABLE"
    override val jsonKeyValuePairSep: String get() = ","
    override val ignoreNullsInFunc: Boolean get() = true
    override val jsonPathBracketedKeySupported: Boolean get() = false
    override val supportsCreateTableLike: Boolean get() = false
    override val multiArgDistinct: Boolean get() = false
    override val supportsToNumber: Boolean get() = false
    override val supportsDropAlterIcebergProperty: Boolean get() = false
    override val supportsWindowExclude: Boolean get() = true
    override val copyHasIntoKeyword: Boolean get() = false
    override val starExcept: String get() = "EXCLUDE"
    override val padFillPatternIsRequired: Boolean get() = true
    override val supportsLikeQuantifiers: Boolean get() = false
    override val setAssignmentRequiresVariableKeyword: Boolean get() = true

    // sqlglot: DuckDBGenerator.IGNORE_NULLS_BEFORE_ORDER = False
    override val ignoreNullsBeforeOrder: Boolean get() = false

    // sqlglot: DuckDB BYTE_STRINGS = [("e'", "'")] (tokenizer-derived)
    override val byteStart: String? get() = "e'"
    override val byteEnd: String? get() = "'"
    override val dialectByteStringsSupportEscapedSequences: Boolean get() = true
    override val escapedSequences: Map<String, String> get() = MysqlGenerator.ESCAPED_SEQUENCES

    // sqlglot: DuckDB dialect-level flags read by the generator
    override val dialectNullOrdering: String get() = "nulls_are_last"
    override val dialectIndexOffset: Int get() = 1
    override val dialectSafeDivision: Boolean get() = true
    override val dialectConcatCoalesce: Boolean get() = true
    override val dialectConcatWsCoalesce: Boolean get() = true
    override val inverseTimeMapping: Map<String, String> get() = INVERSE_TIME_MAPPING

    // sqlglot: DuckDBGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: DuckDBGenerator.RESERVED_KEYWORDS
    override val reservedKeywords: Set<String> get() = RESERVED_KEYWORDS

    // sqlglot: DuckDBGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // sqlglot: DuckDBGenerator.UNWRAPPED_INTERVAL_VALUES = (exp.Literal, exp.Paren)
    override fun isUnwrappedIntervalValue(thisArg: kotlin.Any?): Boolean =
        thisArg is Literal || thisArg is Paren

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // sqlglot: dialect.rename_func
    internal fun renameFuncSql(name: String, expression: Expression): String {
        val flattened = expression.args.values.flatMap { v ->
            if (v is List<*>) v else listOf(v)
        }
        return func(name, *flattened.toTypedArray())
    }

    // ------------------------------------------------------------------
    // Methods (sqlglot: DuckDBGenerator methods)
    // ------------------------------------------------------------------

    // sqlglot: DuckDBGenerator.lambda_sql
    override fun lambdaSql(expression: Lambda, arrowSep: String, wrapArgs: Boolean): String {
        if (expression.args["colon"] == true) {
            return "LAMBDA " + super.lambdaSql(expression, arrowSep = ":", wrapArgs = false)
        }
        return super.lambdaSql(expression, arrowSep = arrowSep, wrapArgs = wrapArgs)
    }

    // sqlglot: DuckDBGenerator.show_sql
    override fun showSql(expression: Show): String {
        var from = sql(expression, "from_")
        if (from.isNotEmpty()) from = " FROM $from"
        return "SHOW ${expression.name}$from"
    }

    // sqlglot: DuckDBGenerator.install_sql
    override fun installSql(expression: Install): String {
        val force = if (expression.args["force"] == true) "FORCE " else ""
        val this_ = sql(expression, "this")
        val fromClause = expression.args["from_"]
        val from = if (fromClause != null) " FROM ${sql(fromClause)}" else ""
        return "${force}INSTALL $this_$from"
    }

    // sqlglot: DuckDBGenerator.sortarray_sql
    open fun sortarraySql(expression: SortArray): String {
        val arr = expression.thisArg
        val asc = expression.args["asc"]
        val nullsFirst = expression.args["nulls_first"]

        if (asc !is BooleanNode && nullsFirst !is BooleanNode) {
            return func("LIST_SORT", arr, asc, nullsFirst)
        }

        val nullsAreFirst = nullsFirst is BooleanNode && nullsFirst.thisArg == true
        val nullsFirstSql = if (nullsAreFirst) Literal.string("NULLS FIRST") else null

        if (asc !is BooleanNode) {
            return func("LIST_SORT", arr, asc, nullsFirstSql)
        }

        val descending = asc.thisArg == false

        if (!descending && !nullsAreFirst) {
            return func("LIST_SORT", arr)
        }
        if (!nullsAreFirst) {
            return func("ARRAY_REVERSE_SORT", arr)
        }
        return func(
            "LIST_SORT",
            arr,
            Literal.string(if (descending) "DESC" else "ASC"),
            Literal.string("NULLS FIRST"),
        )
    }

    // sqlglot: generators.duckdb._struct_sql
    open fun duckdbStructSql(expression: Struct): String {
        var ancestorCast = expression.findAncestor(Cast::class, Select::class)
        if (ancestorCast is Select) ancestorCast = null

        // Empty struct cast works with MAP() since DuckDB can't parse {}
        if (expression.expressionsArg.isEmpty()) {
            val to = (ancestorCast as? Cast)?.args?.get("to") as? DataType
            if (to?.thisArg == DType.MAP) return "MAP()"
        }

        // BigQuery allows inline construction such as "STRUCT<a STRING, b INTEGER>('str', 1)"
        // which is canonicalized to "ROW('str', 1) AS STRUCT(a TEXT, b INT)" in DuckDB
        val isBqInlineStruct = expression.find(PropertyEQ::class) == null &&
            ancestorCast != null &&
            ancestorCast.findAll(DataType::class).any { it.thisArg == DType.STRUCT }

        val argsSql = mutableListOf<String>()
        for ((i, expr) in expression.expressionsArg.withIndex()) {
            val e = expr as? Expression ?: continue
            val isPropertyEq = e is PropertyEQ
            val this_ = e.args["this"]
            val value = if (isPropertyEq) e.args["expression"] else e

            if (isBqInlineStruct) {
                argsSql.add(sql(value))
            } else {
                val key = when {
                    this_ is Identifier -> sql(Literal.string(e.name))
                    isPropertyEq -> sql(this_)
                    else -> sql(Literal.string("_$i"))
                }
                argsSql.add("$key: ${sql(value)}")
            }
        }

        val csvArgs = argsSql.joinToString(", ")
        return if (isBqInlineStruct) "ROW($csvArgs)" else "{$csvArgs}"
    }

    // sqlglot: generators.duckdb._datatype_sql
    open fun duckdbDatatypeSql(expression: DataType): String {
        if (expression.thisArg == DType.ARRAY) {
            val inner = expressions(expression, flat = true)
            val values = expressions(expression, key = "values", flat = true)
            return "$inner[$values]"
        }

        // Modifiers are not supported for TIME, [TIME | TIMESTAMP] WITH TIME ZONE
        if (expression.thisArg in setOf(DType.TIME, DType.TIMETZ, DType.TIMESTAMPTZ)) {
            return (expression.thisArg as DType).value
        }

        return datatypeSql(expression)
    }

    // sqlglot: generators.duckdb._unix_to_time_sql
    open fun unixToTimeSql(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        var timestamp = expression.thisArg
        val targetType = expression.args["target_type"] as? DataType

        // Check if we need NTZ (naive timestamp in UTC)
        val isNtz = targetType != null &&
            targetType.thisArg in setOf(DType.TIMESTAMP, DType.TIMESTAMPNTZ)

        val scaleValue = (scale as? Literal)?.takeIf { !it.isString }?.name

        if (scaleValue == "3") {
            // EPOCH_MS already returns TIMESTAMP (naive, UTC)
            return func("EPOCH_MS", timestamp)
        }
        if (scaleValue == "6") {
            // MAKE_TIMESTAMP already returns TIMESTAMP (naive, UTC)
            return func("MAKE_TIMESTAMP", timestamp)
        }

        // Other scales: divide and use TO_TIMESTAMP
        if (scale != null && scaleValue != "0") {
            timestamp = Div(
                args(
                    "this" to timestamp,
                    "expression" to Anonymous(
                        args(
                            "this" to "POW",
                            "expressions" to listOf(Literal.number("10"), scale),
                        )
                    ),
                )
            )
        }

        var toTimestamp: Expression =
            Anonymous(args("this" to "TO_TIMESTAMP", "expressions" to listOf(timestamp)))

        if (isNtz) {
            toTimestamp = AtTimeZone(
                args("this" to toTimestamp, "zone" to Literal.string("UTC"))
            )
        }

        return sql(toTimestamp)
    }

    // sqlglot: generators.duckdb._arrow_json_extract_sql (+ dialect.arrow_json_extract_sql)
    open fun arrowJsonExtractSql(expression: Binary): String {
        val op = if (expression is JSONExtract) "->" else "->>"
        var arrowSql = binary(expression, op)
        val parent = expression.parent
        val sameParent = parent != null && parent::class == expression::class
        if (!sameParent &&
            (parent is Binary || parent is Bracket || parent is In || parent is Not)
        ) {
            arrowSql = wrap(arrowSql)
        }
        return arrowSql
    }

    // sqlglot: generators.duckdb._json_format_sql
    open fun jsonformatSql(expression: JSONFormat): String {
        val sqlText = func("TO_JSON", expression.thisArg, expression.args["options"])
        return "CAST($sqlText AS TEXT)"
    }

    // sqlglot: dialect.generate_series_sql("GENERATE_SERIES", "RANGE")
    open fun generateseriesSql(expression: GenerateSeries): String {
        val name = if (expression.args["is_end_exclusive"] == true) "RANGE" else "GENERATE_SERIES"
        return func(
            name,
            expression.args["start"],
            expression.args["end"],
            expression.args["step"],
        )
    }

    // sqlglot: generators.duckdb._regexp_extract_sql
    open fun regexpExtractSql(expression: Expression): String {
        val all = expression is RegexpExtractAll
        val funcName = if (all) "REGEXP_EXTRACT_ALL" else "REGEXP_EXTRACT"

        val group = expression.args["group"] as? Expression
        val groupIsZero = group is Literal && !group.isString && group.name == "0"
        val params = expression.args["parameters"]

        return func(
            funcName,
            expression.thisArg,
            expression.args["expression"],
            if (groupIsZero && params == null) null else group,
            params,
        )
    }

    // sqlglot: DuckDBGenerator.IGNORE_RESPECT_NULLS_WINDOW_FUNCTIONS gate for
    // respectnulls_sql — RESPECT NULLS renders only for general-purpose window funcs
    override fun respectnullsSql(expression: RespectNulls): String {
        val this_ = expression.thisArg
        if (this_ is FirstValue || this_ is Lag || this_ is LastValue || this_ is Lead ||
            this_ is NthValue
        ) {
            return super.respectnullsSql(expression)
        }

        unsupported("RESPECT NULLS is not supported for non-window functions.")
        return sql(expression, "this")
    }

    // sqlglot: DuckDBGenerator.nthvalue_sql
    open fun nthvalueSql(expression: NthValue): String {
        val fromFirst = expression.args["from_first"] ?: true
        if (fromFirst == false) {
            unsupported("DuckDB's NTH_VALUE doesn't support starting from the end ")
        }

        return functionFallbackSql(expression)
    }

    // sqlglot: DuckDBGenerator.jarowinklersimilarity_sql
    open fun jarowinklersimilaritySql(expression: JarowinklerSimilarity): String {
        var this_ = expression.thisArg as? Expression
        var expr = expression.args["expression"] as? Expression

        if (expression.args["case_insensitive"] == true) {
            this_ = Upper(args("this" to this_))
            expr = Upper(args("this" to expr))
        }

        var result: Expression = Anonymous(
            args("this" to "JARO_WINKLER_SIMILARITY", "expressions" to listOf(this_, expr))
        )

        if (expression.args["integer_scale"] == true) {
            result = Cast(
                args(
                    "this" to Mul(args("this" to result, "expression" to Literal.number("100"))),
                    "to" to DataType(args("this" to DType.INT)),
                )
            )
        }

        return sql(result)
    }

    // sqlglot: dialect.getbit_sql (annotate_types-dependent int branch approximated
    // via the expression's own type slot; untyped values fall back to GET_BIT)
    open fun getbitSql(expression: Getbit): String {
        val value = expression.thisArg as? Expression
        val position = expression.args["expression"]

        val valueType = value?.type?.thisArg as? DType
        if (expression.args["zero_is_msb"] != true && valueType in INTEGER_TYPES) {
            // Use bitwise operations: (value >> position) & 1
            val shifted = BitwiseRightShift(args("this" to value, "expression" to position))
            val masked = BitwiseAnd(args("this" to shifted, "expression" to Literal.number("1")))
            return sql(masked)
        }

        return func("GET_BIT", value, position)
    }

    // sqlglot: generators.duckdb._sha_sql (annotate_types-dependent cast branch
    // approximated via the expression's own type slot)
    open fun shaSql(expression: Expression, hashFunc: String, isBinary: Boolean = false): String {
        var arg = expression.thisArg as? Expression

        // For SHA2 variants, check digest length (DuckDB only supports SHA256)
        if (hashFunc == "SHA256") {
            val length = expression.text("length").ifEmpty { "256" }
            if (length != "256") {
                unsupported("DuckDB only supports SHA256 hashing algorithm.")
            }
        }

        // Cast if type is incompatible with DuckDB
        val argType = arg?.type?.thisArg as? DType
        if (argType != null && argType != DType.UNKNOWN &&
            argType !in TEXT_TYPES && argType !in BINARY_TYPES
        ) {
            arg = Cast(args("this" to arg, "to" to DataType(args("this" to DType.VARCHAR))))
        }

        val result = func(hashFunc, arg)
        return if (isBinary) func("UNHEX", result) else result
    }

    // sqlglot: dialect.groupconcat_sql(func_name="LISTAGG", sep=",", within_group=False)
    open fun groupconcatSql(expression: GroupConcat): String {
        var this_ = expression.thisArg as? Expression
        val separator = sql(expression.args["separator"] ?: Literal.string(","))

        // on_overflow=False — the arg is ignored for DuckDB
        var limit: Expression? = null
        if (this_ is Limit && this_.thisArg != null) {
            limit = this_
            val inner = this_.thisArg as Expression
            this_.set("this", null)
            this_ = inner
        }

        val order = this_?.find(Order::class) as? Order
        if (order != null && order.thisArg != null) {
            val inner = order.thisArg as Expression
            order.set("this", null)
            this_ = if (this_ === order) inner else this_
        }

        val argsSql = formatArgs(this_, separator.ifEmpty { null })

        var modifiers = sql(limit)
        if (order != null) {
            // within_group=False — order becomes an in-function modifier
            modifiers = "${sql(order)}$modifiers"
        }

        return func("LISTAGG", "$argsSql$modifiers")
    }

    // sqlglot: dialect.date_delta_to_binary_interval_op wrapped by
    // generators.duckdb._date_delta_to_binary_interval_op (nanosecond and
    // float-interval branches; the float branch is annotate_types-driven and
    // approximated via the expression's own type slot)
    open fun dateDeltaSql(expression: Expression): String {
        val unit = expression.args["unit"] as? Expression
        var intervalValue = expression.args["expression"] as? Expression

        // Handle NANOSECOND unit (DuckDB doesn't support INTERVAL ... NANOSECOND)
        if (unit != null && unit.name.uppercase() in setOf("NANOSECOND", "NANOSECONDS")) {
            if (intervalValue is Interval) {
                intervalValue = intervalValue.thisArg as? Expression
            }

            val timestampNs = Cast(
                args(
                    "this" to expression.thisArg,
                    "to" to DataType(args("this" to DType.TIMESTAMP_NS)),
                )
            )

            return sql(
                Anonymous(
                    args(
                        "this" to "MAKE_TIMESTAMP_NS",
                        "expressions" to listOf(
                            Add(
                                args(
                                    "this" to Anonymous(
                                        args(
                                            "this" to "EPOCH_NS",
                                            "expressions" to listOf(timestampNs),
                                        )
                                    ),
                                    "expression" to intervalValue,
                                )
                            ),
                        ),
                    )
                )
            )
        }

        // Handle float/decimal interval values as duckDB INTERVAL requires integers
        if (intervalValue != null && intervalValue !is Interval) {
            val ivType = intervalValue.type?.thisArg as? DType
            if (ivType in REAL_TYPES) {
                expression.set(
                    "expression",
                    Cast(
                        args(
                            "this" to Anonymous(
                                args("this" to "ROUND", "expressions" to listOf(intervalValue))
                            ),
                            "to" to DataType(args("this" to DType.INT)),
                        )
                    ),
                )
            }
        }

        // sqlglot: dialect.date_delta_to_binary_interval_op(cast=True)
        var this_ = expression.thisArg as Expression
        val unitVar = unitToVar(expression)
        val op = if (
            expression is DateAdd || expression is DatetimeAdd || expression is TimeAdd ||
            expression is TimestampAdd || expression is TsOrDsAdd
        ) "+" else "-"

        var toType: DType? = null
        if (expression is TsOrDsAdd) {
            toType = ((expression.args["return_type"] as? DataType)?.thisArg as? DType)
                ?: DType.DATE
        } else if (this_ is Literal && this_.isString) {
            // Cast string literals to the appropriate type for +/- interval to work
            toType = if (expression is DatetimeAdd || expression is DatetimeSub) {
                DType.DATETIME
            } else {
                DType.DATE
            }
        }

        if (toType != null) {
            this_ = Cast(args("this" to this_, "to" to DataType(args("this" to toType))))
        }

        val expr = expression.args["expression"] as? Expression
        val interval = if (expr is Interval) expr else Interval(
            args("this" to expr, "unit" to unitVar)
        )

        return "${sql(this_)} $op ${sql(interval)}"
    }

    // sqlglot: dialect.unit_to_var
    private fun unitToVar(expression: Expression, default: String = "DAY"): Expression? {
        val unit = expression.args["unit"] as? Expression

        if (unit is Var || unit is Placeholder || unit is WeekStart || unit is Column) return unit

        val value = unit?.name ?: default
        return if (value.isNotEmpty()) Var(args("this" to value)) else null
    }

    // sqlglot: generators.duckdb._date_from_parts_sql
    open fun datefrompartsSql(expression: DateFromParts): String {
        val yearExpr = expression.args["year"]
        val monthExpr = expression.args["month"] as? Expression
        val dayExpr = expression.args["day"] as? Expression

        if (expression.args["allow_overflow"] == true) {
            var baseDate: Expression = Anonymous(
                args(
                    "this" to "MAKE_DATE",
                    "expressions" to listOf(yearExpr, Literal.number("1"), Literal.number("1")),
                )
            )

            if (monthExpr != null) {
                baseDate = Add(
                    args(
                        "this" to baseDate,
                        "expression" to Interval(
                            args(
                                "this" to Sub(
                                    args("this" to monthExpr, "expression" to Literal.number("1"))
                                ),
                                "unit" to Var(args("this" to "MONTH")),
                            )
                        ),
                    )
                )
            }

            if (dayExpr != null) {
                baseDate = Add(
                    args(
                        "this" to baseDate,
                        "expression" to Interval(
                            args(
                                "this" to Sub(
                                    args("this" to dayExpr, "expression" to Literal.number("1"))
                                ),
                                "unit" to Var(args("this" to "DAY")),
                            )
                        ),
                    )
                )
            }

            return sql(
                Cast(args("this" to baseDate, "to" to DataType(args("this" to DType.DATE))))
            )
        }

        return func("MAKE_DATE", yearExpr, monthExpr, dayExpr)
    }

    // sqlglot: DuckDBGenerator.timestampfromparts_sql
    open fun timestampfrompartsSql(expression: TimestampFromParts): String {
        // Date/time expression form: TIMESTAMP_FROM_PARTS(date_expr, time_expr)
        val dateExpr = expression.args["this"]
        val timeExpr = expression.args["expression"]

        if (dateExpr != null && timeExpr != null) {
            // In DuckDB, DATE + TIME produces TIMESTAMP
            return sql(Add(args("this" to dateExpr, "expression" to timeExpr)))
        }

        // Component-based form: TIMESTAMP_FROM_PARTS(year, month, day, hour, minute, second)
        var sec = expression.args["sec"] as? Expression
            ?: return renameFuncSql("MAKE_TIMESTAMP", expression)

        val milli = expression.args["milli"] as? Expression
        if (milli != null) {
            milli.pop()
            sec = Add(
                args(
                    "this" to sec,
                    "expression" to Div(
                        args("this" to milli, "expression" to Literal.number("1000.0"))
                    ),
                )
            )
        }

        val nano = expression.args["nano"] as? Expression
        if (nano != null) {
            nano.pop()
            sec = Add(
                args(
                    "this" to sec,
                    "expression" to Div(
                        args("this" to nano, "expression" to Literal.number("1000000000.0"))
                    ),
                )
            )
        }

        if (milli != null || nano != null) {
            expression.set("sec", sec)
        }

        return renameFuncSql("MAKE_TIMESTAMP", expression)
    }

    // sqlglot: DuckDBGenerator.approxtopk_sql
    open fun approxtopkSql(expression: ApproxTopK): String {
        unsupported(
            "APPROX_TOP_K cannot be transpiled to DuckDB due to incompatible return types. "
        )
        return functionFallbackSql(expression)
    }

    // sqlglot: DuckDBGenerator._strptime_default_year
    protected open fun strptimeDefaultYear(expression: Expression): Pair<kotlin.Any?, kotlin.Any?> {
        var value: kotlin.Any? = expression.thisArg
        var formattedTime: kotlin.Any? = formatTime(expression)

        val defaultYear = expression.args["default_year"] as? Expression
        if (defaultYear != null) {
            value = DPipe(
                args("this" to Literal.string("${defaultYear.name} "), "expression" to value)
            )
            formattedTime = DPipe(
                args("this" to Literal.string("%Y "), "expression" to formattedTime)
            )
        }

        return value to formattedTime
    }

    // sqlglot: DuckDBGenerator.strtotime_sql
    open fun strtotimeSql(expression: StrToTime): String {
        // Check if target_type requires TIMESTAMPTZ (for LTZ/TZ variants)
        val targetType = expression.args["target_type"] as? DataType
        val needsTz = targetType != null &&
            targetType.thisArg in setOf(DType.TIMESTAMPLTZ, DType.TIMESTAMPTZ)

        val (value, formattedTime) = strptimeDefaultYear(expression)

        if (expression.args["safe"] == true) {
            val castType = if (needsTz) DType.TIMESTAMPTZ else DType.TIMESTAMP
            return sql(
                Cast(
                    args(
                        "this" to func("TRY_STRPTIME", value, formattedTime),
                        "to" to DataType(args("this" to castType)),
                    )
                )
            )
        }

        val baseSql = func("STRPTIME", value, formattedTime)
        if (needsTz) {
            return sql(
                Cast(
                    args("this" to baseSql, "to" to DataType(args("this" to DType.TIMESTAMPTZ)))
                )
            )
        }
        return baseSql
    }

    // sqlglot: DuckDBGenerator.strtodate_sql
    open fun strtodateSql(expression: StrToDate): String {
        val (value, formattedTime) = strptimeDefaultYear(expression)
        val functionName = if (expression.args["safe"] == true) "TRY_STRPTIME" else "STRPTIME"
        return sql(
            Cast(
                args(
                    "this" to func(functionName, value, formattedTime),
                    "to" to DataType(args("this" to DType.DATE)),
                )
            )
        )
    }

    // sqlglot: DuckDBGenerator.parsejson_sql
    override fun parsejsonSql(expression: ParseJSON): String {
        val arg = expression.thisArg as? Expression
        if (expression.args["safe"] == true) {
            return sql(
                Case(
                    args(
                        "ifs" to listOf(
                            If(
                                args(
                                    "this" to Anonymous(
                                        args(
                                            "this" to "json_valid",
                                            "expressions" to listOf(arg),
                                        )
                                    ),
                                    "true" to Cast(
                                        args(
                                            "this" to arg?.copy(),
                                            "to" to DataType(args("this" to DType.JSON)),
                                        )
                                    ),
                                )
                            ),
                        ),
                        "default" to Null(),
                    )
                )
            )
        }
        return func("JSON", arg)
    }

    // sqlglot: DuckDBGenerator.arraydistinct_sql
    open fun arraydistinctSql(expression: ArrayDistinct): String {
        val arr = expression.thisArg
        val funcSql = func("LIST_DISTINCT", arr)

        if (expression.args["check_null"] == true) {
            val arrExpr = arr as? Expression
            val addNullToArray = Anonymous(
                args(
                    "this" to "LIST_APPEND",
                    "expressions" to listOf(
                        Anonymous(
                            args(
                                "this" to "LIST_DISTINCT",
                                "expressions" to listOf(ArrayCompact(args("this" to arrExpr))),
                            )
                        ),
                        Null(),
                    ),
                )
            )
            return sql(
                If(
                    args(
                        "this" to NEQ(
                            args(
                                "this" to ArraySize(args("this" to arrExpr?.copy())),
                                "expression" to Anonymous(
                                    args(
                                        "this" to "LIST_COUNT",
                                        "expressions" to listOf(arrExpr?.copy()),
                                    )
                                ),
                            )
                        ),
                        "true" to addNullToArray,
                        "false" to funcSql,
                    )
                )
            )
        }

        return funcSql
    }

    // sqlglot: DuckDBGenerator._validate_regexp_flags
    protected open fun validateRegexpFlags(flags: Expression?, supportedFlags: String): String? {
        if (flags == null) return null

        if (!(flags is Literal && flags.isString)) {
            unsupported("Non-literal regexp flags are not fully supported in DuckDB")
            return null
        }

        val flagStr = flags.thisArg as? String ?: return null
        val unsupportedFlags = flagStr.toSet() - supportedFlags.toSet()

        if (unsupportedFlags.isNotEmpty()) {
            unsupported(
                "Regexp flags ${unsupportedFlags.sorted()} are not supported in this context"
            )
        }

        val filtered = flagStr.filter { it in supportedFlags }
        return filtered.ifEmpty { null }
    }

    // sqlglot: DuckDBGenerator.regexplike_sql
    open fun regexplikeSql(expression: RegexpLike): String {
        val this_ = expression.thisArg
        val pattern = expression.args["expression"]
        var flag: Expression? = expression.args["flag"] as? Expression

        if (expression.args["full_match"] == true) {
            val validatedFlags = validateRegexpFlags(flag, supportedFlags = "cims")
            flag = if (validatedFlags != null) Literal.string(validatedFlags) else null
            return func("REGEXP_FULL_MATCH", this_, pattern, flag)
        }

        return func("REGEXP_MATCHES", this_, pattern, flag)
    }

    // sqlglot: DuckDBGenerator.split_sql
    open fun splitSql(expression: Split): String {
        val this_ = expression.thisArg as? Expression
        val delim = expression.args["expression"] as? Expression
        val baseFunc = Anonymous(
            args("this" to "STR_SPLIT", "expressions" to listOf(this_, delim))
        )

        val ifs = mutableListOf<Expression>()

        if (expression.args["null_returns_null"] == true) {
            ifs.add(
                If(
                    args(
                        "this" to Is(args("this" to delim?.copy(), "expression" to Null())),
                        "true" to Null(),
                    )
                )
            )
        }

        if (expression.args["empty_delimiter_returns_whole"] == true) {
            // When delimiter is empty string, return input string as single array element
            ifs.add(
                If(
                    args(
                        "this" to EQ(
                            args("this" to delim?.copy(), "expression" to Literal.string(""))
                        ),
                        "true" to ArrayNode(args("expressions" to listOf(this_?.copy()))),
                    )
                )
            )
        }

        if (ifs.isEmpty()) return sql(baseFunc)

        return sql(Case(args("ifs" to ifs, "default" to baseFunc)))
    }

    // sqlglot: DuckDBGenerator.bitwisexor_sql (binary blob preparation is
    // annotate_types-driven and approximated via the expressions' own type slots)
    open fun bitwisexorSql(expression: BitwiseXor): String {
        return func("XOR", expression.thisArg, expression.args["expression"])
    }

    // sqlglot: generators.duckdb._bitwise_agg_sql (annotate_types approximated via
    // Cast-carried types; untyped args pass through unchanged)
    open fun bitwiseAggSql(expression: Expression): String {
        val funcName = when (expression) {
            is BitwiseOrAgg -> "BIT_OR"
            is BitwiseAndAgg -> "BIT_AND"
            else -> "BIT_XOR"
        }

        var arg = expression.thisArg as? Expression

        val argType = (arg as? Cast)?.let { (it.args["to"] as? DataType)?.thisArg as? DType }
            ?: arg?.type?.thisArg as? DType
        if (argType in REAL_TYPES || argType in TEXT_TYPES) {
            if (argType in FLOAT_TYPES) {
                // float types need to be rounded first due to precision loss
                arg = Anonymous(args("this" to "ROUND", "expressions" to listOf(arg)))
            }

            arg = Cast(args("this" to arg, "to" to DataType(args("this" to DType.INT))))
        }

        return func(funcName, arg)
    }

    // sqlglot: DuckDBGenerator.ignorenulls_sql
    override fun ignorenullsSql(expression: IgnoreNulls): String {
        var this_ = expression.thisArg as? Expression

        if (this_ is FirstValue || this_ is Lag || this_ is LastValue || this_ is Lead ||
            this_ is NthValue
        ) {
            // DuckDB should render IGNORE NULLS only for the general-purpose
            // window functions that accept it e.g. FIRST_VALUE(... IGNORE NULLS) OVER (...)
            return super.ignorenullsSql(expression)
        }

        if (this_ is First) {
            this_ = AnyValue(args("this" to this_.thisArg))
        }

        if (!(this_ is AnyValue || this_ is ApproxQuantiles)) {
            unsupported("IGNORE NULLS is not supported for non-window functions.")
        }

        return sql(this_)
    }

    // sqlglot: generators.duckdb._anyvalue_sql
    override fun anyvalueSql(expression: AnyValue): String {
        // Transform ANY_VALUE(expr HAVING MAX/MIN having_expr) to ARG_MAX_NULL/ARG_MIN_NULL
        val having = expression.thisArg
        if (having is HavingMax) {
            val funcName = if (having.args["max"] == true) "ARG_MAX_NULL" else "ARG_MIN_NULL"
            return func(funcName, having.thisArg, having.args["expression"])
        }
        return functionFallbackSql(expression)
    }

    // sqlglot: generators.duckdb._date_diff_sql (nanosecond/week-boundary branches are
    // annotate_types/unit-metadata dependent; _implicit_datetime_cast is fully ported)
    open fun dateDiffSql(expression: Expression): String {
        val unit = expression.args["unit"] as? Expression

        var this_ = implicitDatetimeCast(expression.thisArg as? Expression)
        var expr = implicitDatetimeCast(expression.args["expression"] as? Expression)

        // DuckDB's WEEK diff does not respect week boundaries; other dialects that set
        // date_part_boundary need truncation to week starts
        val datePartBoundary = expression.args["date_part_boundary"] == true
        val weekStart = weekUnitToDow(unit)
        if (datePartBoundary && weekStart != null && this_ != null && expr != null) {
            expression.set("unit", Literal.string("WEEK"))

            this_ = buildWeekTruncExpression(this_, weekStart)
            expr = buildWeekTruncExpression(expr, weekStart)
        }

        return func("DATE_DIFF", unitToStr(expression), expr, this_)
    }

    // sqlglot: generators.duckdb._implicit_datetime_cast
    protected open fun implicitDatetimeCast(
        arg: Expression?,
        type: DType = DType.DATE,
    ): Expression? {
        if (arg is Literal && arg.isString) {
            var castType = type
            val ts = arg.name
            if (type == DType.DATE && ":" in ts) {
                castType = if (TIMEZONE_PATTERN.containsMatchIn(ts)) {
                    DType.TIMESTAMPTZ
                } else {
                    DType.TIMESTAMP
                }
            }

            return Cast(args("this" to arg, "to" to DataType(args("this" to castType))))
        }

        return arg
    }

    // sqlglot: generators.duckdb._week_unit_to_dow
    protected open fun weekUnitToDow(unit: Expression?): Int? {
        if (unit is Var && unit.name.uppercase() in "ISOWEEK") return 1
        if (unit is WeekStart) return WEEK_START_DAY_TO_DOW[unit.name.uppercase()]
        return null
    }

    // sqlglot: generators.duckdb._build_week_trunc_expression
    protected open fun buildWeekTruncExpression(
        dateExpr: Expression,
        startDow: Int,
        preserveStartDay: Boolean = false,
    ): Expression {
        val shiftDays = if (startDow == 7) 1 else 1 - startDow
        val truncated: Expression = Anonymous(
            args(
                "this" to "DATE_TRUNC",
                "expressions" to listOf(Literal.string("WEEK"), dateExpr),
            )
        )

        if (shiftDays == 0) return truncated

        val shift = Interval(
            args("this" to Literal.string(shiftDays.toString()), "unit" to Var(args("this" to "DAY")))
        )
        val shiftedDate = DateAdd(args("this" to dateExpr, "expression" to shift))
        truncated.set("expressions", listOf(Literal.string("WEEK"), shiftedDate))

        if (preserveStartDay) {
            val interval = Interval(
                args(
                    "this" to Literal.string((-shiftDays).toString()),
                    "unit" to Var(args("this" to "DAY")),
                )
            )
            return Cast(
                args(
                    "this" to DateAdd(args("this" to truncated, "expression" to interval)),
                    "to" to DataType(args("this" to DType.DATE)),
                )
            )
        }

        return truncated
    }

    // sqlglot: generators.duckdb._array_insert_sql
    open fun arrayinsertSql(expression: ArrayInsert): String {
        val this_ = expression.thisArg as Expression
        val position = expression.args["position"] as? Expression
        val element = expression.args["expression"]
        val elementArray = ArrayNode(args("expressions" to listOf(element)))
        val indexOffset = (expression.args["offset"] as? Int)
            ?: ((expression.args["offset"] as? Expression)?.name?.toIntOrNull()) ?: 0

        // sqlglot: position.is_int / position.to_py()
        val posLiteral: Long? = when {
            position is Literal && !position.isString ->
                (position.thisArg as? String)?.toLongOrNull()
            position is Neg ->
                ((position.thisArg as? Literal)?.takeIf { !it.isString }?.thisArg as? String)
                    ?.toLongOrNull()?.let { -it }
            else -> null
        }
        if (posLiteral == null) {
            unsupported("ARRAY_INSERT can only be transpiled with a literal position")
            return func("ARRAY_INSERT", this_, position, element)
        }

        var posValue = posLiteral.toInt()

        // Normalize one-based indexing to zero-based for slice calculations
        if (posValue > 0) {
            posValue -= indexOffset
        } else if (posValue < 0) {
            posValue += indexOffset
        }

        val concatExprs: List<Expression?>
        if (posValue == 0) {
            // insert at beginning
            concatExprs = listOf(elementArray, this_)
        } else if (posValue > 0) {
            // Positive position: LIST_CONCAT(arr[1:pos], [elem], arr[pos+1:])
            val sliceStart = Bracket(
                args(
                    "this" to this_,
                    "expressions" to listOf(
                        Slice(
                            args(
                                "this" to Literal.number("1"),
                                "expression" to Literal.number(posValue.toString()),
                            )
                        ),
                    ),
                )
            )

            val sliceEnd = Bracket(
                args(
                    "this" to this_.copy(),
                    "expressions" to listOf(
                        Slice(args("this" to Literal.number((posValue + 1).toString()))),
                    ),
                )
            )

            concatExprs = listOf(sliceStart, elementArray, sliceEnd)
        } else {
            // Negative position: arr[1:LEN(arr)+pos], [elem], arr[LEN(arr)+pos+1:]
            val arrLen = Length(args("this" to this_.copy()))

            val sliceEndPos = Add(
                args(
                    "this" to arrLen,
                    "expression" to intLiteral(posValue.toLong()),
                )
            )
            val sliceStartPos = Add(
                args("this" to sliceEndPos.copy(), "expression" to Literal.number("1"))
            )

            val sliceStart = Bracket(
                args(
                    "this" to this_,
                    "expressions" to listOf(
                        Slice(
                            args("this" to Literal.number("1"), "expression" to sliceEndPos)
                        ),
                    ),
                )
            )

            val sliceEnd = Bracket(
                args(
                    "this" to this_.copy(),
                    "expressions" to listOf(Slice(args("this" to sliceStartPos))),
                )
            )

            concatExprs = listOf(sliceStart, elementArray, sliceEnd)
        }

        // All dialects that support ARRAY_INSERT propagate NULLs
        return sql(
            If(
                args(
                    "this" to Is(args("this" to this_.copy(), "expression" to Null())),
                    "true" to Null(),
                    "false" to func("LIST_CONCAT", *concatExprs.toTypedArray()),
                )
            )
        )
    }

    // sqlglot: DuckDBGenerator.datetrunc_sql
    open fun duckdbDatetruncSql(expression: DateTrunc): String {
        val unitExpr = expression.args["unit"] as? Expression
        val date = expression.thisArg as? Expression

        val weekStart = weekUnitToDow(unitExpr)
        val unit = unitToStr(expression)

        val result = if (weekStart != null && date != null) {
            sql(buildWeekTruncExpression(date, weekStart, preserveStartDay = true))
        } else {
            func("DATE_TRUNC", unit, date)
        }

        // input_type_preserved branch is annotate_types-driven; only Cast-carried
        // types are honored here
        if (expression.args["input_type_preserved"] == true) {
            val dateType = date?.type?.thisArg as? DType
            val isDateUnit = (unit as? Literal)?.name?.uppercase() in DATE_UNITS
            if (dateType != null && dateType in TEMPORAL_TYPES &&
                !(isDateUnit && dateType == DType.DATE)
            ) {
                return sql(Cast(args("this" to result, "to" to date.type)))
            }
        }

        return result
    }

    // sqlglot: DuckDBGenerator.timestamptrunc_sql
    open fun duckdbTimestamptruncSql(expression: TimestampTrunc): String {
        val unit = unitToStr(expression)
        val zone = expression.args["zone"] as? Expression
        var timestamp = expression.thisArg as? Expression
        val dateUnit = (unit as? Literal)?.name?.uppercase() in DATE_UNITS

        if (dateUnit && zone != null) {
            // Double AT TIME ZONE needed for BigQuery compatibility
            timestamp = AtTimeZone(args("this" to timestamp, "zone" to zone))
            val resultSql = func("DATE_TRUNC", unit, timestamp)
            return sql(AtTimeZone(args("this" to resultSql, "zone" to zone.copy())))
        }

        val result = func("DATE_TRUNC", unit, timestamp)
        if (expression.args["input_type_preserved"] == true) {
            val tsType = timestamp?.type?.thisArg as? DType
            if (tsType == DType.TIME || tsType == DType.TIMETZ) {
                val dummyDate = Cast(
                    args(
                        "this" to Literal.string("1970-01-01"),
                        "to" to DataType(args("this" to DType.DATE)),
                    )
                )
                val dateTime = Add(args("this" to dummyDate, "expression" to timestamp))
                return sql(
                    Cast(
                        args(
                            "this" to func("DATE_TRUNC", unit, dateTime),
                            "to" to timestamp?.type,
                        )
                    )
                )
            }

            if (tsType != null && tsType in TEMPORAL_TYPES && !(dateUnit && tsType == DType.DATE)) {
                return sql(Cast(args("this" to result, "to" to timestamp?.type)))
            }
        }

        return result
    }

    // sqlglot: dialect.encode_decode_sql (replace=False)
    open fun encodeDecodeSql(expression: Expression, name: String): String {
        val charset = expression.args["charset"] as? Expression
        if (charset != null && charset.name.lowercase() !in setOf("utf-8", "utf8")) {
            unsupported("Expected utf-8 character set, got ${charset.name}.")
        }

        return func(name, expression.thisArg)
    }

    // sqlglot: DuckDBGenerator.ARRAY_SIZE_DIM_REQUIRED = False
    override val arraySizeDimRequired: Boolean? get() = false

    // sqlglot: dialect.array_compact_sql
    open fun arraycompactSql(expression: ArrayCompact): String {
        val lambdaId = Identifier(args("this" to "_u", "quoted" to false))
        val cond = Not(
            args(
                "this" to Is(args("this" to lambdaId, "expression" to Null()))
            )
        )
        return sql(
            ArrayFilter(
                args(
                    "this" to expression.thisArg,
                    "expression" to Lambda(
                        args("this" to cond, "expressions" to listOf(lambdaId.copy()))
                    ),
                )
            )
        )
    }

    // sqlglot: dialect.array_concat_sql("LIST_CONCAT")
    open fun arrayconcatSql(expression: ArrayConcat): String {
        val name = "LIST_CONCAT"
        val this_ = expression.thisArg as? Expression
        val exprs = expression.expressionsArg.filterIsInstance<Expression>()
        val allArgs = listOf(this_) + exprs

        // sqlglot: _build_func_call with ARRAY_CONCAT_IS_VAR_LEN=True
        fun buildFuncCall(argsList: List<Expression?>): String = func(name, *argsList.toTypedArray())

        val sourceNullPropagation = expression.args["null_propagation"] == true
        // DuckDB: ARRAY_FUNCS_PROPAGATES_NULLS=False
        val targetNullPropagation = false

        if (sourceNullPropagation == targetNullPropagation ||
            this_ is ArrayNode || exprs.isEmpty()
        ) {
            return buildFuncCall(allArgs)
        }

        if (sourceNullPropagation) {
            // Source propagates NULLs, target doesn't: NULL-check every argument
            val nullChecks: List<Expression> = allArgs.map { arg ->
                Is(args("this" to arg?.copy(), "expression" to Null()))
            }
            val combinedCheck = nullChecks.reduce { a, b ->
                Or(args("this" to a, "expression" to b))
            }

            return sql(
                If(
                    args(
                        "this" to combinedCheck,
                        "true" to Null(),
                        "false" to buildFuncCall(allArgs),
                    )
                )
            )
        }

        // Source doesn't propagate NULLs, target does: COALESCE args to empty arrays
        val wrappedArgs = allArgs.map { arg ->
            Coalesce(
                args(
                    "expressions" to listOf(arg?.copy(), ArrayNode(args("expressions" to emptyList<Expression>())))
                )
            )
        }
        return buildFuncCall(wrappedArgs)
    }

    // sqlglot: DuckDBGenerator.tablesample_sql
    override fun tablesampleSql(expression: TableSample, tablesampleKeyword: String?): String {
        var keyword = tablesampleKeyword
        if (expression.parent !is Select) {
            // This sample clause only applies to a single source, not the entire relation
            keyword = "TABLESAMPLE"
        }

        if (expression.args["size"] != null) {
            val method = expression.args["method"] as? Expression
            if (method != null && method.name.uppercase() != "RESERVOIR") {
                unsupported(
                    "Sampling method ${method.name} is not supported with a discrete sample " +
                        "count, defaulting to reservoir sampling"
                )
                expression.set("method", Var(args("this" to "RESERVOIR")))
            }
        }

        return super.tablesampleSql(expression, tablesampleKeyword = keyword)
    }

    // sqlglot: DuckDBGenerator.join_sql
    override fun joinSql(expression: Join): String {
        if (expression.args["using"] == null &&
            expression.args["on"] == null &&
            expression.text("method").isEmpty() &&
            expression.text("kind").uppercase() in setOf("", "INNER", "OUTER")
        ) {
            // Some dialects support `LEFT/INNER JOIN UNNEST(...)` without an explicit ON
            // clause; DuckDB doesn't, but we can add a dummy ON clause that is always true
            if (expression.thisArg is Unnest) {
                expression.set("on", BooleanNode(args("this" to true)))
                return super.joinSql(expression)
            }

            expression.set("side", null)
            expression.set("kind", null)
        }

        return super.joinSql(expression)
    }

    // sqlglot: DuckDBGenerator.withingroup_sql
    override fun withingroupSql(expression: WithinGroup): String {
        val funcExpr = expression.thisArg as? Expression

        // For ARRAY_AGG, DuckDB requires ORDER BY inside the function, not in WITHIN GROUP
        if (funcExpr is ArrayAgg) {
            val order = expression.args["expression"] as? Order
                ?: return sql(funcExpr)

            funcExpr.set(
                "this",
                Order(
                    args(
                        "this" to (funcExpr.thisArg as? Expression)?.copy(),
                        "expressions" to order.expressionsArg,
                    )
                ),
            )

            return functionFallbackSql(funcExpr)
        }

        // For other functions (like PERCENTILES), use existing logic
        val expressionSql = sql(expression, "expression")

        if (funcExpr is PercentileCont || funcExpr is PercentileDisc) {
            // Make the order key the first arg and slide the fraction to the right
            // https://duckdb.org/docs/sql/aggregates#ordered-set-aggregate-functions
            val orderCol = expression.find(Ordered::class)
            if (orderCol != null) {
                funcExpr.set("expression", funcExpr.thisArg)
                funcExpr.set("this", orderCol.thisArg)
            }
        }

        val thisSql = sql(expression, "this").trimEnd(')')

        return "$thisSql$expressionSql)"
    }

    // sqlglot: DuckDBGenerator TRANSFORMS via companion (see below)

    companion object {

        // sqlglot: expressions.datatypes.DataType type groups (the members needed here)
        val INTEGER_TYPES: Set<DType> = setOf(
            DType.BIGINT, DType.INT, DType.INT128, DType.INT256, DType.MEDIUMINT,
            DType.SMALLINT, DType.TINYINT, DType.UBIGINT, DType.UINT, DType.UINT128,
            DType.UINT256, DType.UMEDIUMINT, DType.USMALLINT, DType.UTINYINT, DType.BIT,
        )

        val TEXT_TYPES: Set<DType> = setOf(
            DType.CHAR, DType.NCHAR, DType.NVARCHAR, DType.TEXT, DType.VARCHAR,
            DType.NAME,
        )

        val BINARY_TYPES: Set<DType> = setOf(
            DType.BINARY, DType.VARBINARY, DType.BLOB, DType.LONGBLOB, DType.MEDIUMBLOB,
            DType.TINYBLOB,
        )

        val REAL_TYPES: Set<DType> = setOf(
            DType.DECIMAL, DType.UDECIMAL, DType.DECIMAL32, DType.DECIMAL64,
            DType.DECIMAL128, DType.DECIMAL256, DType.BIGDECIMAL, DType.DOUBLE,
            DType.UDOUBLE, DType.FLOAT, DType.DECFLOAT,
        )

        val FLOAT_TYPES: Set<DType> = setOf(DType.DOUBLE, DType.UDOUBLE, DType.FLOAT)

        val TEMPORAL_TYPES: Set<DType> = setOf(
            DType.DATE, DType.DATE32, DType.DATETIME, DType.DATETIME2, DType.DATETIME64,
            DType.SMALLDATETIME, DType.TIME, DType.TIMESTAMP, DType.TIMESTAMPNTZ,
            DType.TIMESTAMPLTZ, DType.TIMESTAMPTZ, DType.TIMESTAMP_MS, DType.TIMESTAMP_NS,
            DType.TIMESTAMP_S, DType.TIMETZ,
        )

        // sqlglot: helper.DATE_UNITS (upper-cased for the comparisons here)
        val DATE_UNITS: Set<String> = setOf("DAY", "WEEK", "MONTH", "QUARTER", "YEAR", "YEAR_MONTH")

        // sqlglot: generators.duckdb.WEEK_START_DAY_TO_DOW
        val WEEK_START_DAY_TO_DOW: Map<String, Int> = mapOf(
            "MONDAY" to 1, "TUESDAY" to 2, "WEDNESDAY" to 3, "THURSDAY" to 4,
            "FRIDAY" to 5, "SATURDAY" to 6, "SUNDAY" to 7,
        )

        // sqlglot: generators.duckdb.TIMEZONE_PATTERN
        val TIMEZONE_PATTERN: Regex = Regex(":\\d{2}.*?[+\\-]\\d{2}(?::\\d{2})?")

        // sqlglot: DuckDB.INVERSE_TIME_MAPPING (auto-inverse of empty TIME_MAPPING
        // merged with the class-level overrides)
        val INVERSE_TIME_MAPPING: Map<String, String> = mapOf(
            "%e" to "%-d",
            "%:z" to "%z",
            "%-z" to "%z",
            "%f_zero" to "%n",
            "%f_one" to "%n",
            "%f_two" to "%n",
            "%f_three" to "%g",
            "%f_four" to "%n",
            "%f_five" to "%n",
            "%f_seven" to "%n",
            "%f_eight" to "%n",
            "%f_nine" to "%n",
        )

        // sqlglot: generator.Generator.TYPE_MAPPING (the base entries DuckDB inherits)
        private val BASE_TYPE_MAPPING: Map<DType, String> = mapOf(
            DType.DATETIME2 to "TIMESTAMP",
            DType.NCHAR to "CHAR",
            DType.NVARCHAR to "VARCHAR",
            DType.MEDIUMTEXT to "TEXT",
            DType.LONGTEXT to "TEXT",
            DType.TINYTEXT to "TEXT",
            DType.BLOB to "VARBINARY",
            DType.MEDIUMBLOB to "BLOB",
            DType.LONGBLOB to "BLOB",
            DType.TINYBLOB to "BLOB",
            DType.INET to "INET",
            DType.ROWVERSION to "VARBINARY",
            DType.SMALLDATETIME to "TIMESTAMP",
        )

        // sqlglot: DuckDBGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = BASE_TYPE_MAPPING + mapOf(
            DType.BINARY to "BLOB",
            DType.BPCHAR to "TEXT",
            DType.CHAR to "TEXT",
            DType.DATETIME to "TIMESTAMP",
            DType.DECFLOAT to "DECIMAL",
            DType.FLOAT to "REAL",
            DType.JSONB to "JSON",
            DType.NCHAR to "TEXT",
            DType.NVARCHAR to "TEXT",
            DType.UINT to "UINTEGER",
            DType.VARBINARY to "BLOB",
            DType.ROWVERSION to "BLOB",
            DType.VARCHAR to "TEXT",
            DType.TIMESTAMPLTZ to "TIMESTAMPTZ",
            DType.TIMESTAMPNTZ to "TIMESTAMP",
            DType.TIMESTAMP_S to "TIMESTAMP_S",
            DType.TIMESTAMP_MS to "TIMESTAMP_MS",
            DType.TIMESTAMP_NS to "TIMESTAMP_NS",
            DType.BIGDECIMAL to "DECIMAL",
        )

        // sqlglot: DuckDBGenerator.RESERVED_KEYWORDS
        val RESERVED_KEYWORDS: Set<String> = setOf(
            "array", "analyse", "union", "all", "when", "in_p", "default", "create_p",
            "window", "asymmetric", "to", "else", "localtime", "from", "end_p", "select",
            "current_date", "foreign", "with", "grant", "session_user", "or", "except",
            "references", "fetch", "limit", "group_p", "leading", "into", "collate",
            "offset", "do", "then", "localtimestamp", "check_p", "lateral_p",
            "current_role", "where", "asc_p", "placing", "desc_p", "user", "unique",
            "initially", "column", "both", "some", "as", "any", "only", "deferrable",
            "null_p", "current_time", "true_p", "table", "case", "trailing", "variadic",
            "for", "on", "distinct", "false_p", "not", "constraint", "current_timestamp",
            "returning", "primary", "intersect", "having", "analyze", "current_user",
            "and", "cast", "symmetric", "using", "order", "current_catalog",
        )

        // sqlglot: DuckDBGenerator.PROPERTIES_LOCATION (all base locations become
        // UNSUPPORTED except the explicitly overridden ones)
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            GeneratorTables.PROPERTIES_LOCATION.mapValues { GeneratorTables.PropLocation.UNSUPPORTED } +
                mapOf(
                    LikeProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                    TemporaryProperty::class to GeneratorTables.PropLocation.POST_CREATE,
                    ReturnsProperty::class to GeneratorTables.PropLocation.POST_ALIAS,
                    SequenceProperties::class to GeneratorTables.PropLocation.POST_EXPRESSION,
                    IcebergProperty::class to GeneratorTables.PropLocation.POST_CREATE,
                )

        // sqlglot: DuckDBGenerator.TRANSFORMS (dispatch-map overlay over the base;
        // preprocess-pipeline entries are approximated or skipped — see class KDoc)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.dg(): DuckdbGenerator = this as DuckdbGenerator

            // sqlglot: dialect.approx_count_distinct_sql (only `this` is forwarded)
            reg(ApproxDistinct::class) { e -> func("APPROX_COUNT_DISTINCT", e.thisArg) }
            // sqlglot: generators.duckdb._array_sort_sql (comparator args are dropped)
            reg(ArraySort::class) { e -> func("ARRAY_SORT", e.thisArg) }
            // sqlglot: generators.duckdb._xor_sql (round_input is annotate-independent)
            reg(Xor::class) { e ->
                fun roundArg(arg: kotlin.Any?): Expression? {
                    val a = arg as? Expression
                    return if (e.args["round_input"] == true && a != null) {
                        Anonymous(
                            args(
                                "this" to "ROUND",
                                "expressions" to listOf(a, Literal.number("0")),
                            )
                        )
                    } else a
                }
                val left = roundArg(e.thisArg)
                val right = roundArg(e.args["expression"])
                sql(
                    Or(
                        args(
                            "this" to Paren(
                                args(
                                    "this" to And(
                                        args(
                                            "this" to left?.copy(),
                                            "expression" to Paren(
                                                args("this" to Not(args("this" to right?.copy())))
                                            ),
                                        )
                                    )
                                )
                            ),
                            "expression" to Paren(
                                args(
                                    "this" to And(
                                        args(
                                            "this" to Paren(
                                                args("this" to Not(args("this" to left?.copy())))
                                            ),
                                            "expression" to right?.copy(),
                                        )
                                    )
                                )
                            ),
                        )
                    )
                )
            }
            // sqlglot: DuckDBGenerator.numbertostr_sql (@unsupported_args("culture"))
            reg(NumberToStr::class) { e ->
                if (e.args["culture"] != null) {
                    unsupported("Argument 'culture' is not supported for expression 'NumberToStr'")
                }
                val fmt = e.args["format"] as? Expression
                val fmtIsInt = fmt is Literal && !fmt.isString && fmt.name.toLongOrNull() != null
                if (fmtIsInt) {
                    func("FORMAT", "'{:,.${fmt!!.name}f}'", e.thisArg)
                } else {
                    unsupported("Only integer formats are supported by NumberToStr")
                    functionFallbackSql(e as Func)
                }
            }
            // sqlglot: DuckDBGenerator.format_sql
            reg(Format::class) { e ->
                if (e.name.lowercase() == "%s" && e.expressionsArg.size == 1) {
                    func("FORMAT", "'{}'", e.expressionsArg[0])
                } else {
                    functionFallbackSql(e as Func)
                }
            }
            // sqlglot: TRANSFORMS[exp.Array] (inherit_struct_field_names preprocess
            // skipped; generator=inline_array_unless_query)
            reg(ArrayNode::class) { e ->
                val elem = e.expressionsArg.firstOrNull() as? Expression
                if (elem?.find(Select::class, Union::class, Except::class, Intersect::class) != null) {
                    func("ARRAY", elem)
                } else {
                    "[" + expressions(
                        e, dynamic = true, newLine = true, skipFirst = true, skipLast = true
                    ) + "]"
                }
            }
            reg(ArrayFilter::class) { e -> dg().renameFuncSql("LIST_FILTER", e) }
            reg(ArraySum::class) { e -> dg().renameFuncSql("LIST_SUM", e) }
            reg(ArrayMax::class) { e -> dg().renameFuncSql("LIST_MAX", e) }
            reg(ArrayMin::class) { e -> dg().renameFuncSql("LIST_MIN", e) }
            // sqlglot: exp.ArrayContains: _array_contains_sql (check_null branch needs
            // exp.If/Nullif — plain path here)
            reg(ArrayContains::class) { e ->
                func("ARRAY_CONTAINS", (e as ArrayContains).thisArg, e.args["expression"])
            }
            reg(ArrayOverlaps::class) { e ->
                // sqlglot: _array_overlaps_sql (null_safe=false path)
                binary(e as Binary, "&&")
            }
            reg(BitwiseAnd::class) { e -> binary(e as Binary, "&") }
            reg(BitwiseOr::class) { e -> binary(e as Binary, "|") }
            reg(BitwiseCount::class) { e -> dg().renameFuncSql("BIT_COUNT", e) }
            reg(CosineDistance::class) { e -> dg().renameFuncSql("LIST_COSINE_DISTANCE", e) }
            reg(CurrentTime::class) { _ -> "CURRENT_TIME" }
            reg(CurrentTimestamp::class) { e ->
                if (e.args["sysdate"] == true) {
                    sql(
                        AtTimeZone(
                            args(
                                "this" to Var(args("this" to "CURRENT_TIMESTAMP")),
                                "zone" to Literal.string("UTC"),
                            )
                        )
                    )
                } else "CURRENT_TIMESTAMP"
            }
            reg(CurrentVersion::class) { e -> dg().renameFuncSql("VERSION", e) }
            reg(Localtime::class) { _ -> "LOCALTIME" }
            reg(DayOfMonth::class) { e -> dg().renameFuncSql("DAYOFMONTH", e) }
            reg(DayOfWeek::class) { e -> dg().renameFuncSql("DAYOFWEEK", e) }
            reg(DayOfWeekIso::class) { e -> dg().renameFuncSql("ISODOW", e) }
            reg(DayOfYear::class) { e -> dg().renameFuncSql("DAYOFYEAR", e) }
            reg(Dayname::class) { e ->
                if (e.args["abbreviated"] == true) {
                    func("STRFTIME", e.thisArg, Literal.string("%a"))
                } else {
                    func("DAYNAME", e.thisArg)
                }
            }
            reg(Monthname::class) { e ->
                if (e.args["abbreviated"] == true) {
                    func("STRFTIME", e.thisArg, Literal.string("%b"))
                } else {
                    func("MONTHNAME", e.thisArg)
                }
            }
            reg(DataType::class) { e -> dg().duckdbDatatypeSql(e as DataType) }
            reg(EuclideanDistance::class) { e -> dg().renameFuncSql("LIST_DISTANCE", e) }
            reg(GenerateSeries::class) { e -> dg().generateseriesSql(e as GenerateSeries) }
            reg(Explode::class) { e -> dg().renameFuncSql("UNNEST", e) }
            reg(IntDiv::class) { e -> binary(e as Binary, "//") }
            reg(IsInf::class) { e -> dg().renameFuncSql("ISINF", e) }
            reg(IsNan::class) { e -> dg().renameFuncSql("ISNAN", e) }
            reg(JSONExtract::class) { e -> dg().arrowJsonExtractSql(e as JSONExtract) }
            reg(JSONExtractScalar::class) { e -> dg().arrowJsonExtractSql(e as JSONExtractScalar) }
            reg(JSONFormat::class) { e -> dg().jsonformatSql(e as JSONFormat) }
            // sqlglot: _cast_to_boolean — untyped args always cast
            fun castToBoolean(arg: kotlin.Any?): kotlin.Any? {
                val e = arg as? Expression ?: return arg
                val t = e.type?.thisArg as? DType
                return if (t == DType.BOOLEAN) e
                else Cast(args("this" to e, "to" to DataType(args("this" to DType.BOOLEAN))))
            }
            reg(LogicalOr::class) { e -> func("BOOL_OR", castToBoolean(e.thisArg)) }
            reg(LogicalAnd::class) { e -> func("BOOL_AND", castToBoolean(e.thisArg)) }
            reg(Getbit::class) { e -> dg().getbitSql(e as Getbit) }
            reg(JarowinklerSimilarity::class) { e ->
                dg().jarowinklersimilaritySql(e as JarowinklerSimilarity)
            }
            reg(NthValue::class) { e -> dg().nthvalueSql(e as NthValue) }
            reg(GroupConcat::class) { e -> dg().groupconcatSql(e as GroupConcat) }
            reg(SHA::class) { e -> dg().shaSql(e, "SHA1") }
            reg(SHA1Digest::class) { e -> dg().shaSql(e, "SHA1", isBinary = true) }
            reg(SHA2::class) { e -> dg().shaSql(e, "SHA256") }
            reg(SHA2Digest::class) { e -> dg().shaSql(e, "SHA256", isBinary = true) }
            reg(MD5Digest::class) { e -> func("UNHEX", func("MD5", e.thisArg)) }
            reg(DateFromParts::class) { e -> dg().datefrompartsSql(e as DateFromParts) }
            reg(TimestampFromParts::class) { e ->
                dg().timestampfrompartsSql(e as TimestampFromParts)
            }
            reg(DateAdd::class) { e -> dg().dateDeltaSql(e) }
            reg(DateSub::class) { e -> dg().dateDeltaSql(e) }
            reg(DatetimeAdd::class) { e -> dg().dateDeltaSql(e) }
            reg(DatetimeSub::class) { e -> dg().dateDeltaSql(e) }
            reg(TimeAdd::class) { e -> dg().dateDeltaSql(e) }
            reg(TimeSub::class) { e -> dg().dateDeltaSql(e) }
            reg(TimestampAdd::class) { e -> dg().dateDeltaSql(e) }
            reg(TimestampSub::class) { e -> dg().dateDeltaSql(e) }
            reg(TsOrDsAdd::class) { e -> dg().dateDeltaSql(e) }
            reg(DateDiff::class) { e -> dg().dateDiffSql(e) }
            reg(DatetimeDiff::class) { e -> dg().dateDiffSql(e) }
            reg(ApproxTopK::class) { e -> dg().approxtopkSql(e as ApproxTopK) }
            reg(StrToTime::class) { e -> dg().strtotimeSql(e as StrToTime) }
            reg(StrToDate::class) { e -> dg().strtodateSql(e as StrToDate) }
            reg(ParseJSON::class) { e -> dg().parsejsonSql(e as ParseJSON) }
            reg(ArrayDistinct::class) { e -> dg().arraydistinctSql(e as ArrayDistinct) }
            reg(RegexpLike::class) { e -> dg().regexplikeSql(e as RegexpLike) }
            reg(Split::class) { e -> dg().splitSql(e as Split) }
            reg(BitwiseXor::class) { e -> dg().bitwisexorSql(e as BitwiseXor) }
            reg(BitwiseOrAgg::class) { e -> dg().bitwiseAggSql(e) }
            reg(BitwiseAndAgg::class) { e -> dg().bitwiseAggSql(e) }
            reg(BitwiseXorAgg::class) { e -> dg().bitwiseAggSql(e) }
            reg(AnyValue::class) { e -> dg().anyvalueSql(e as AnyValue) }
            reg(ArrayInsert::class) { e -> dg().arrayinsertSql(e as ArrayInsert) }
            reg(DateTrunc::class) { e -> dg().duckdbDatetruncSql(e as DateTrunc) }
            reg(TimestampTrunc::class) { e -> dg().duckdbTimestamptruncSql(e as TimestampTrunc) }
            reg(Encode::class) { e -> dg().encodeDecodeSql(e, "ENCODE") }
            reg(Decode::class) { e -> dg().encodeDecodeSql(e, "DECODE") }
            reg(CommentColumnConstraint::class) { e ->
                // sqlglot: dialect.no_comment_column_constraint_sql
                unsupported("CommentColumnConstraint unsupported")
                ""
            }
            reg(IcebergProperty::class) { _ -> "" }
            reg(ArrayCompact::class) { e -> dg().arraycompactSql(e as ArrayCompact) }
            reg(ArrayConstructCompact::class) { e ->
                sql(ArrayCompact(args("this" to ArrayNode(args("expressions" to e.expressionsArg)))))
            }
            reg(ArrayConcat::class) { e -> dg().arrayconcatSql(e as ArrayConcat) }
            reg(ToVariant::class) { e ->
                sql(
                    Cast(
                        args("this" to e.thisArg, "to" to DataType(args("this" to DType.VARIANT)))
                    )
                )
            }
            reg(PercentileCont::class) { e -> dg().renameFuncSql("QUANTILE_CONT", e) }
            reg(PercentileDisc::class) { e -> dg().renameFuncSql("QUANTILE_DISC", e) }
            reg(RegexpExtract::class) { e -> dg().regexpExtractSql(e) }
            reg(RegexpExtractAll::class) { e -> dg().regexpExtractSql(e) }
            reg(RegexpILike::class) { e ->
                func("REGEXP_MATCHES", e.thisArg, e.args["expression"], Literal.string("i"))
            }
            reg(RegexpSplit::class) { e -> dg().renameFuncSql("STR_SPLIT_REGEX", e) }
            reg(Return::class) { e -> sql(e, "this") }
            reg(ReturnsProperty::class) { e -> if (e.thisArg is Schema) "TABLE" else "" }
            reg(SortArray::class) { e -> dg().sortarraySql(e as SortArray) }
            reg(StrToUnix::class) { e ->
                func("EPOCH", func("STRPTIME", e.thisArg, formatTime(e)))
            }
            reg(Struct::class) { e -> dg().duckdbStructSql(e as Struct) }
            reg(Transform::class) { e -> dg().renameFuncSql("LIST_TRANSFORM", e) }
            reg(TimeToStr::class) { e -> func("STRFTIME", e.thisArg, formatTime(e)) }
            reg(TimeToUnix::class) { e -> dg().renameFuncSql("EPOCH", e) }
            reg(TimestampDiff::class) { e ->
                func(
                    "DATE_DIFF",
                    Literal.string((e.args["unit"] as? Expression)?.name ?: "DAY"),
                    e.args["expression"],
                    e.thisArg,
                )
            }
            reg(UnixToTime::class) { e -> dg().unixToTimeSql(e as UnixToTime) }
            reg(UnixToTimeStr::class) { e -> "CAST(TO_TIMESTAMP(${sql(e, "this")}) AS TEXT)" }
            reg(VariancePop::class) { e -> dg().renameFuncSql("VAR_POP", e) }
            reg(WeekOfYear::class) { e -> dg().renameFuncSql("WEEKOFYEAR", e) }
            reg(JSONObjectAgg::class) { e -> dg().renameFuncSql("JSON_GROUP_OBJECT", e) }
            reg(JSONBObjectAgg::class) { e -> dg().renameFuncSql("JSON_GROUP_OBJECT", e) }
            reg(DateBin::class) { e -> dg().renameFuncSql("TIME_BUCKET", e) }
            reg(JSONBExists::class) { e -> dg().renameFuncSql("JSON_EXISTS", e) }
            // sqlglot: DuckDBGenerator.rand_sql
            reg(Rand::class) { e ->
                if (e.thisArg != null) {
                    unsupported("RANDOM with seed is not supported in DuckDB")
                }
                val lower = e.args["lower"] as? Expression
                val upper = e.args["upper"] as? Expression
                if (lower != null && upper != null) {
                    // scale DuckDB's [0,1) to the specified range
                    val rangeSize = Paren(
                        args("this" to Sub(args("this" to upper.copy(), "expression" to lower.copy())))
                    )
                    val scaled = Add(
                        args(
                            "this" to lower.copy(),
                            "expression" to Mul(args("this" to Rand(), "expression" to rangeSize)),
                        )
                    )
                    sql(
                        Cast(
                            args("this" to scaled, "to" to DataType(args("this" to DType.BIGINT)))
                        )
                    )
                } else {
                    // Default DuckDB behavior - just return RANDOM() as float
                    "RANDOM()"
                }
            }
        }
    }
}
