package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.eliminateQualify
import dev.brikk.house.sql.generator.eliminateDistinctOn
import dev.brikk.house.sql.generator.eliminateSemiAndAntiJoins
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.parser.MysqlTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set
import kotlin.reflect.KClass

// sqlglot: dialect.unit_to_var
private fun unitToVar(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression

    if (unit is Var || unit is Placeholder || unit is WeekStart || unit is Column) return unit

    val value = unit?.name ?: default
    return if (value.isNotEmpty()) Var(args("this" to value)) else null
}

// sqlglot: time.subsecond_precision (datetime.fromisoformat based; regex approximation)
private val ISO_TIMESTAMP_RE = Regex(
    "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.(\\d+))?)?([+-]\\d{2}:\\d{2}(:\\d{2})?)?$"
)

private fun subsecondPrecision(timestampLiteral: String): Int {
    val m = ISO_TIMESTAMP_RE.matchEntire(timestampLiteral) ?: return 0
    val fraction = m.groupValues[3]
    if (fraction.isEmpty() || fraction.length > 6) return 0
    val micros = fraction.padEnd(6, '0').toIntOrNull() ?: return 0
    if (micros == 0) return 0
    // python: len(str(parsed.microsecond).rstrip("0")) — digits of the microsecond
    // value (no leading zeros) after stripping trailing zeros
    val digitCount = micros.toString().trimEnd('0').length
    return if (digitCount > 3) 6 else 3
}

// sqlglot: generators.mysql._MAKE_INTERVAL_UNIT_ALIASES
private val MAKE_INTERVAL_UNIT_ALIASES: Map<String, String> = mapOf(
    "years" to "year",
    "months" to "month",
    "weeks" to "week",
    "days" to "day",
    "hours" to "hour",
    "minutes" to "minute",
    "mins" to "minute",
    "seconds" to "second",
    "secs" to "second",
)

/**
 * Port of sqlglot's MySQLGenerator (reference/sqlglot/sqlglot/generators/mysql.py).
 * TRANSFORMS entries live in [TRANSFORMS] (a dispatch-map overlay handed to the base
 * constructor); flag overrides are open-val overrides; multi-line methods below.
 */
// sqlglot: generators.mysql.MySQLGenerator
open class MysqlGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = MysqlTokenizerTables.CONFIG,
    // extra dispatch overlay for subclasses (sqlglot: further TRANSFORMS merges, e.g. Doris)
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
    sourceDialect: String? = null,
) : Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
    sourceDialect = sourceDialect,
) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.MYSQL

    // ------------------------------------------------------------------
    // Flags (sqlglot: MySQLGenerator class attributes)
    // ------------------------------------------------------------------

    override val selectKinds: Set<String> get() = emptySet()
    override val trySupported: Boolean get() = false
    override val supportsUescape: Boolean get() = false
    override val supportsModifyColumn: Boolean get() = true
    override val supportsChangeColumn: Boolean get() = true
    override val intervalAllowsPluralForm: Boolean get() = false
    override val lockingReadsSupported: Boolean get() = true
    override val nullOrderingSupported: Boolean? get() = null
    override val joinHints: Boolean get() = false
    override val tableHints: Boolean get() = true
    override val duplicateKeyUpdateWithSet: Boolean get() = false
    override val queryHintSep: String get() = " "
    override val valuesAsTable: Boolean get() = false
    override val nvl2Supported: Boolean get() = false
    override val jsonPathBracketedKeySupported: Boolean get() = false
    override val jsonKeyValuePairSep: String get() = ","
    override val supportsToNumber: Boolean get() = false
    override val parseJsonName: String? get() = null
    override val padFillPatternIsRequired: Boolean get() = true
    override val wrapDerivedValues: Boolean get() = false
    override val supportsMedian: Boolean get() = false
    override val updateStatementSupportsFrom: Boolean get() = false
    override val limitFetch: String get() = "LIMIT"

    // sqlglot: MySQLGenerator.LIMIT_ONLY_LITERALS = True
    override val limitOnlyLiterals: Boolean get() = true

    // sqlglot: MySQL dialect-level flags read by the generator
    override val dialectSafeDivision: Boolean get() = true
    override val dialectStrictStringConcat: Boolean get() = false
    override val dialectIdentifiersCanStartWithDigit: Boolean get() = true
    override val inverseTimeMapping: Map<String, String> get() = MysqlDialect.INVERSE_TIME_MAPPING
    // sqlglot: MySQL.TIME_FORMAT
    override val dialectTimeFormat: String get() = "'%Y-%m-%d %T'"
    // sqlglot: STRING_ESCAPES contains "\\" -> STRINGS_SUPPORT_ESCAPED_SEQUENCES
    override val dialectStringsSupportEscapedSequences: Boolean get() = true
    // sqlglot: Dialect.ESCAPED_SEQUENCES ({v: k for UNESCAPED_SEQUENCES})
    override val escapedSequences: Map<String, String> get() = ESCAPED_SEQUENCES
    // sqlglot: Generator._escaped_quote_end (STRING_ESCAPES[0] == "'")
    override val escapedQuoteEnd: String get() = "''"
    // sqlglot: MySQL HEX_STRINGS = [("x'", "'"), ...]
    override val hexStart: String? get() = "x'"
    override val hexEnd: String? get() = "'"

    // sqlglot: MySQLGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: MySQLGenerator.VARCHAR_REQUIRES_SIZE = True (Doris resets to False)
    open val varcharRequiresSize: Boolean get() = true

    // sqlglot: MySQLGenerator.CAST_MAPPING (Doris resets to {})
    open val castMapping: Map<DType, String> get() = CAST_MAPPING

    // sqlglot: MySQLGenerator.TIMESTAMP_FUNC_TYPES (Doris resets to set())
    open val timestampFuncTypes: Set<DType> get() = TIMESTAMP_FUNC_TYPES

    // sqlglot: MySQLGenerator.RESERVED_KEYWORDS
    override val reservedKeywords: Set<String> get() = RESERVED_KEYWORDS

    // sqlglot: MySQLGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // sqlglot: MySQLGenerator.AFTER_HAVING_MODIFIER_TRANSFORMS (windows/qualify only)
    override fun afterHavingModifierSqls(expression: Expression): List<String> = listOf(
        windowsModifierSql(expression),
        sql(expression, "qualify"),
    )

    // ------------------------------------------------------------------
    // Transform helpers (sqlglot: module-level functions in generators/mysql.py)
    // ------------------------------------------------------------------

    // sqlglot: dialect.rename_func
    internal fun renameFuncSql(name: String, expression: Expression): String {
        val flattened = expression.args.values.flatMap { v ->
            if (v is List<*>) v else listOf(v)
        }
        return func(name, *flattened.toTypedArray())
    }

    // sqlglot: generators.mysql._remove_ts_or_ds_to_date
    internal fun removeTsOrDsToDate(expression: Expression, vararg argKeys: String) {
        for (argKey in argKeys) {
            val arg = expression.args[argKey]
            if ((arg is TsOrDsToDate || arg is TsOrDsToTimestamp) &&
                (arg as Expression).args["format"] == null
            ) {
                expression.set(argKey, arg.thisArg)
            }
        }
    }

    // sqlglot: generators.mysql._date_trunc_sql
    open fun dateTruncSql(expression: DateTrunc): String {
        val expr = sql(expression, "this")
        val unit = expression.text("unit").uppercase()

        val concat: String
        val dateFormat: String
        when (unit) {
            "WEEK" -> {
                concat = "CONCAT(YEAR($expr), ' ', WEEK($expr, 1), ' 1')"
                dateFormat = "%Y %u %w"
            }
            "MONTH" -> {
                concat = "CONCAT(YEAR($expr), ' ', MONTH($expr), ' 1')"
                dateFormat = "%Y %c %e"
            }
            "QUARTER" -> {
                concat = "CONCAT(YEAR($expr), ' ', QUARTER($expr) * 3 - 2, ' 1')"
                dateFormat = "%Y %c %e"
            }
            "YEAR" -> {
                concat = "CONCAT(YEAR($expr), ' 1 1')"
                dateFormat = "%Y %c %e"
            }
            else -> {
                if (unit != "DAY") unsupported("Unexpected interval unit: $unit")
                return func("DATE", expr)
            }
        }

        return func("STR_TO_DATE", concat, "'$dateFormat'")
    }

    // sqlglot: generators.mysql._str_to_date_sql
    open fun strToDateSql(expression: Expression): String =
        func("STR_TO_DATE", expression.thisArg, formatTime(expression))

    // sqlglot: generators.mysql._unix_to_time_sql
    open fun unixToTimeSql(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        val timestamp = expression.thisArg

        // sqlglot: `scale in (None, exp.UnixToTime.SECONDS)` (SECONDS = Literal.number(0))
        val isSeconds = scale == null ||
            (scale is Literal && !scale.isString && scale.thisArg == "0")
        if (isSeconds) {
            return func("FROM_UNIXTIME", timestamp, formatTime(expression))
        }

        return func(
            "FROM_UNIXTIME",
            Div(
                args(
                    "this" to timestamp,
                    "expression" to Pow(
                        args("this" to Literal.number("10"), "expression" to scale)
                    ),
                )
            ),
            formatTime(expression),
        )
    }

    // sqlglot: generators.mysql.date_add_sql
    open fun dateAddSql(kind: String, expression: Expression): String =
        func(
            "DATE_$kind",
            expression.thisArg,
            Interval(
                args(
                    "this" to expression.args["expression"],
                    "unit" to normalizeTimeUnit(unitToVar(expression)),
                )
            ),
        )

    // sqlglot: generators.mysql._ts_or_ds_to_date_sql
    open fun tsOrDsToDateSql(expression: TsOrDsToDate): String {
        val timeFormat = expression.args["format"]
        return if (timeFormat != null) strToDateSql(expression)
        else func("DATE", expression.thisArg)
    }

    // sqlglot: dialect.no_paren_current_date_sql
    open fun noParenCurrentDateSql(expression: CurrentDate): String {
        val zone = sql(expression, "this")
        return if (zone.isNotEmpty()) "CURRENT_DATE AT TIME ZONE $zone" else "CURRENT_DATE"
    }

    // sqlglot: dialect.no_ilike_sql
    open fun noIlikeSql(expression: ILike): String =
        likeSql(
            Like(
                args(
                    "this" to Lower(args("this" to expression.thisArg)),
                    "expression" to Lower(args("this" to expression.args["expression"])),
                    "negate" to expression.args["negate"],
                )
            )
        )

    // sqlglot: dialect.arrow_json_extract_sql (JSON_TYPE_REQUIRED_FOR_EXTRACTION=True)
    open fun arrowJsonExtractSql(expression: Binary): String {
        val this_ = expression.thisArg
        if (this_ is Literal && this_.isString) {
            this_.replace(
                Cast(args("this" to this_, "to" to DataType(args("this" to DType.JSON))))
            )
        }
        return binary(expression, if (expression is JSONExtract) "->" else "->>")
    }

    // sqlglot: dialect.length_or_char_length_sql
    open fun lengthOrCharLengthSql(expression: Length): String {
        val lengthFunc = if (isTruthy(expression.args["binary"])) "LENGTH" else "CHAR_LENGTH"
        return func(lengthFunc, expression.thisArg)
    }

    // sqlglot: dialect.max_or_greatest / min_or_least
    open fun maxOrGreatestSql(expression: Max): String =
        renameFuncSql(if (expression.expressionsArg.isNotEmpty()) "GREATEST" else "MAX", expression)

    open fun minOrLeastSql(expression: Min): String =
        renameFuncSql(if (expression.expressionsArg.isNotEmpty()) "LEAST" else "MIN", expression)

    // sqlglot: dialect.no_pivot_sql
    open fun noPivotSql(expression: Pivot): String {
        unsupported("PIVOT unsupported")
        return ""
    }

    // sqlglot: dialect.no_tablesample_sql
    open fun noTablesampleSql(expression: TableSample): String {
        unsupported("TABLESAMPLE unsupported")
        return sql(expression.thisArg)
    }

    // sqlglot: dialect.strposition_sql (func_name="LOCATE", supports_position=True)
    open fun strpositionSql(expression: StrPosition): String {
        val string = expression.thisArg
        val substr = expression.args["substr"]
        val position = expression.args["position"]
        val occurrence = expression.args["occurrence"]

        val fnArgs = mutableListOf(substr, string)
        fnArgs.add(position)
        if (occurrence != null) {
            unsupported("LOCATE does not support the occurrence parameter.")
        }
        return sql(
            Anonymous(args("this" to "LOCATE", "expressions" to fnArgs.filterNotNull()))
        )
    }

    // sqlglot: dialect.trim_sql (TRIM with dialect-specific FROM form)
    open fun mysqlTrimSql(expression: Trim): String {
        val removeChars = sql(expression, "expression")

        // Use TRIM/LTRIM/RTRIM syntax if the expression isn't database-specific
        if (removeChars.isEmpty()) return trimSql(expression)

        val target = sql(expression, "this")
        var trimType = sql(expression, "position")
        var collation = sql(expression, "collation")

        trimType = if (trimType.isNotEmpty()) "$trimType " else ""
        val remove = "$removeChars "
        val fromPart = "FROM "
        collation = if (collation.isNotEmpty()) " COLLATE $collation" else ""
        return "TRIM($trimType$remove$fromPart$target$collation)"
    }

    // sqlglot: dialect.timestrtotime_sql (include_precision=not zone)
    open fun timeStrToTimeSql(expression: TimeStrToTime): String {
        val includePrecision = expression.args["zone"] == null
        val dtype = if (expression.args["zone"] != null) DType.TIMESTAMPTZ else DType.TIMESTAMP
        var datatype = DataType(args("this" to dtype))

        val this_ = expression.thisArg
        if (this_ is Literal && includePrecision) {
            val precision = subsecondPrecision(this_.name)
            if (precision > 0) {
                datatype = DataType(
                    args(
                        "this" to dtype,
                        "expressions" to listOf(
                            DataTypeParam(args("this" to Literal.number(precision.toString())))
                        ),
                    )
                )
            }
        }

        return sql(Cast(args("this" to this_, "to" to datatype)))
    }

    // sqlglot: dialect.date_add_interval_sql("DATE", kind)
    open fun dateAddIntervalSql(kind: String, expression: Expression): String {
        val thisSql = sql(expression, "this")
        val interval = Interval(
            args("this" to expression.args["expression"], "unit" to normalizeTimeUnit(unitToVar(expression)))
        )
        return "DATE_$kind($thisSql, ${sql(interval)})"
    }

    // ------------------------------------------------------------------
    // Overridden generator methods (sqlglot: generators/mysql.py methods)
    // ------------------------------------------------------------------

    // sqlglot: MySQLGenerator.makeinterval_sql
    open fun makeintervalSql(expression: MakeInterval): String {
        val intervals = mutableListOf<Interval>()
        for ((argKey, valueRaw) in expression.args) {
            if (valueRaw == null) continue

            var value = valueRaw
            val unitName: String
            if (value is Kwarg) {
                val kwargName = (value.thisArg as? Expression)?.name?.lowercase() ?: ""
                unitName = MAKE_INTERVAL_UNIT_ALIASES[kwargName] ?: kwargName
                value = value.args["expression"]
            } else {
                unitName = argKey
            }

            val valueExpr = value as? Expression ?: continue
            intervals.add(
                Interval(
                    args(
                        "this" to valueExpr.copy(),
                        "unit" to Var(args("this" to unitName.uppercase())),
                    )
                )
            )
        }

        if (intervals.isEmpty()) return functionFallbackSql(expression)

        val parent = expression.parent
        val sep = if (parent is Sub && parent.args["expression"] === expression) " - " else " + "

        return intervals.joinToString(sep) { sql(it) }
    }

    // sqlglot: MySQLGenerator.locate_properties (SQL SECURITY before VIEW)
    override fun locateProperties(properties: Properties): Map<GeneratorTables.PropLocation, List<Expression>> {
        val locations = LinkedHashMap<GeneratorTables.PropLocation, MutableList<Expression>>()
        for ((loc, props) in super.locateProperties(properties)) {
            locations[loc] = props.toMutableList()
        }

        val create = properties.parent as? Create
        if (create != null && create.text("kind") == "VIEW") {
            val postSchema = locations[GeneratorTables.PropLocation.POST_SCHEMA]
            if (postSchema != null) {
                val idx = postSchema.indexOfFirst { it is SqlSecurityProperty }
                if (idx >= 0) {
                    val p = postSchema.removeAt(idx)
                    locations.getOrPut(GeneratorTables.PropLocation.POST_CREATE) { mutableListOf() }.add(p)
                }
            }
        }

        return locations
    }

    // sqlglot: MySQLGenerator.computedcolumnconstraint_sql
    override fun computedcolumnconstraintSql(expression: ComputedColumnConstraint): String {
        val persisted = if (expression.args["persisted"] == true) "STORED" else "VIRTUAL"
        val inner = (expression.thisArg as? Expression)?.unnest()
        return "GENERATED ALWAYS AS (${sql(inner)}) $persisted"
    }

    // sqlglot: MySQLGenerator.array_sql
    open fun arraySql(expression: ArrayNode): String {
        unsupported("Arrays are not supported by MySQL")
        return functionFallbackSql(expression)
    }

    // sqlglot: MySQLGenerator.arraycontainsall_sql / arraycontainedby_sql
    open fun arrayOpUnsupportedSql(expression: Expression): String {
        unsupported("Array operations are not supported by MySQL")
        return functionFallbackSql(expression as Func)
    }

    // sqlglot: MySQLGenerator.dpipe_sql
    override fun dpipeSql(expression: DPipe): String {
        // sqlglot: expression.flatten()
        val flattened = mutableListOf<kotlin.Any?>()
        fun flatten(node: kotlin.Any?) {
            if (node is DPipe) {
                flatten(node.thisArg)
                flatten(node.args["expression"])
            } else {
                flattened.add(node)
            }
        }
        flatten(expression)
        return func("CONCAT", *flattened.toTypedArray())
    }

    // sqlglot: MySQLGenerator.extract_sql
    override fun extractSql(expression: Extract): String {
        val unit = expression.name
        if (unit.isNotEmpty() && unit.lowercase() == "epoch") {
            return func("UNIX_TIMESTAMP", expression.args["expression"])
        }
        return super.extractSql(expression)
    }

    // sqlglot: MySQLGenerator.datatype_sql (VARCHAR_REQUIRES_SIZE=True)
    override fun datatypeSql(expression: DataType): String {
        if (varcharRequiresSize &&
            expression.thisArg == DType.VARCHAR &&
            expression.expressionsArg.isEmpty()
        ) {
            // `VARCHAR` must always have a size - if it doesn't, we always generate `TEXT`
            return "TEXT"
        }

        // https://dev.mysql.com/doc/refman/8.0/en/numeric-type-syntax.html
        var result = super.datatypeSql(expression)
        if (expression.thisArg in UNSIGNED_TYPE_MAPPING) {
            result = "$result UNSIGNED"
        }

        return result
    }

    // sqlglot: MySQLGenerator.jsonarraycontains_sql
    open fun jsonarraycontainsSql(expression: JSONArrayContains): String =
        "${sql(expression, "this")} MEMBER OF(${sql(expression, "expression")})"

    // sqlglot: MySQLGenerator.cast_sql
    override fun castSql(expression: Cast, safePrefix: String?): String {
        val to = expression.args["to"] as? DataType
        if (to?.thisArg in timestampFuncTypes) {
            return func("TIMESTAMP", expression.thisArg)
        }

        val mapped = castMapping[to?.thisArg]
        if (mapped != null) to?.set("this", mapped)

        return super.castSql(expression, safePrefix)
    }

    // sqlglot: MySQLGenerator.show_sql
    override fun showSql(expression: Show): String {
        val thisSql = " ${expression.name}"
        val full = if (isTruthy(expression.args["full"])) " FULL" else ""
        val global_ = if (isTruthy(expression.args["global_"])) " GLOBAL" else ""

        var target = sql(expression, "target")
        target = if (target.isNotEmpty()) " $target" else ""
        when (expression.name) {
            "COLUMNS", "INDEX" -> target = " FROM$target"
            "GRANTS" -> target = " FOR$target"
            "LINKS", "PARTITIONS" -> target = if (target.isNotEmpty()) " ON$target" else ""
            "PROJECTIONS" -> target = if (target.isNotEmpty()) " ON TABLE$target" else ""
        }

        val db = prefixedSql("FROM", expression, "db")

        val like = prefixedSql("LIKE", expression, "like")
        val where = sql(expression, "where")

        var types = expressions(expression, key = "types")
        types = if (types.isNotEmpty()) " $types" else types
        val query = prefixedSql("FOR QUERY", expression, "query")

        val offset: String
        val limit: String
        if (expression.name == "PROFILE") {
            offset = prefixedSql("OFFSET", expression, "offset")
            limit = prefixedSql("LIMIT", expression, "limit")
        } else {
            offset = ""
            limit = oldstyleLimitSql(expression)
        }

        val log = prefixedSql("IN", expression, "log")
        val position = prefixedSql("FROM", expression, "position")

        val channel = prefixedSql("FOR CHANNEL", expression, "channel")

        val mutexOrStatus = if (expression.name == "ENGINE") {
            if (isTruthy(expression.args["mutex"])) " MUTEX" else " STATUS"
        } else ""

        val forTable = prefixedSql("FOR TABLE", expression, "for_table")
        val forGroup = prefixedSql("FOR GROUP", expression, "for_group")
        val forUser = prefixedSql("FOR USER", expression, "for_user")
        val forRole = prefixedSql("FOR ROLE", expression, "for_role")
        val intoOutfile = prefixedSql("INTO OUTFILE", expression, "into_outfile")
        val json = if (isTruthy(expression.args["json"])) " JSON" else ""

        return "SHOW$full$global_$thisSql$json$target$forTable$types$db$query$log$position" +
            "$channel$mutexOrStatus$like$where$offset$limit$forGroup$forUser$forRole$intoOutfile"
    }

    // sqlglot: MySQLGenerator.alterrename_sql (no TO keyword)
    override fun alterrenameSql(expression: AlterRename, includeTo: Boolean): String =
        super.alterrenameSql(expression, includeTo = false)

    // sqlglot: MySQLGenerator.altercolumn_sql
    override fun altercolumnSql(expression: AlterColumn): String {
        val dtype = sql(expression, "dtype")
        if (dtype.isEmpty()) return super.altercolumnSql(expression)

        val thisSql = sql(expression, "this")
        return "MODIFY COLUMN $thisSql $dtype"
    }

    // sqlglot: MySQLGenerator._prefixed_sql
    protected fun prefixedSql(prefix: String, expression: Expression, arg: String): String {
        val argSql = sql(expression, arg)
        return if (argSql.isNotEmpty()) " $prefix $argSql" else ""
    }

    // sqlglot: MySQLGenerator._oldstyle_limit_sql
    protected fun oldstyleLimitSql(expression: Show): String {
        val limit = sql(expression, "limit")
        val offset = sql(expression, "offset")
        if (limit.isNotEmpty()) {
            val limitOffset = if (offset.isNotEmpty()) "$offset, $limit" else limit
            return " LIMIT $limitOffset"
        }
        return ""
    }

    // sqlglot: MySQLGenerator.timestamptrunc_sql
    open fun timestamptruncSql(expression: TimestampTrunc): String {
        val unit = expression.args["unit"] as? Expression

        // Pick an old-enough date to avoid negative timestamp diffs
        val startTs = "'0000-01-01 00:00:00'"

        // Source: https://stackoverflow.com/a/32955740
        // sqlglot: build_date_delta(exp.TimestampDiff)([unit, start_ts, expression.this])
        val timestampDiff = TimestampDiff(
            args("this" to expression.thisArg, "expression" to startTs, "unit" to normalizeTimeUnit(unit))
        )
        val interval = Interval(args("this" to timestampDiff, "unit" to normalizeTimeUnit(unit)))
        // sqlglot: build_date_delta_with_interval(exp.DateAdd)([start_ts, interval])
        val dateadd = DateAdd(
            args(
                "this" to startTs,
                "expression" to interval.thisArg,
                "unit" to normalizeTimeUnit(unit),
            )
        )

        return sql(dateadd)
    }

    // sqlglot: MySQLGenerator.converttimezone_sql
    open fun converttimezoneSql(expression: ConvertTimezone): String {
        val fromTz = expression.args["source_tz"]
        val toTz = expression.args["target_tz"]
        val dt = expression.args["timestamp"]
        return func("CONVERT_TZ", dt, fromTz, toTz)
    }

    // sqlglot: MySQLGenerator.attimezone_sql
    override fun attimezoneSql(expression: AtTimeZone): String {
        unsupported("AT TIME ZONE is not supported by MySQL")
        return sql(expression.thisArg)
    }

    // sqlglot: MySQLGenerator.isascii_sql
    open fun isasciiSql(expression: IsAscii): String =
        "REGEXP_LIKE(${sql(expression.thisArg)}, '^[[:ascii:]]*$')"

    // sqlglot: MySQLGenerator.ignorenulls_sql
    override fun ignorenullsSql(expression: IgnoreNulls): String {
        // https://dev.mysql.com/doc/refman/8.4/en/window-function-descriptions.html
        unsupported("MySQL does not support IGNORE NULLS.")
        return sql(expression.thisArg)
    }

    // sqlglot: MySQLGenerator.currentschema_sql (@unsupported_args("this"))
    open fun currentschemaSql(expression: CurrentSchema): String {
        if (isTruthy(expression.args["this"])) {
            unsupported("Argument 'this' is not supported for expression 'CurrentSchema' when targeting MySQL.")
        }
        return func("SCHEMA")
    }

    // sqlglot: MySQLGenerator.partition_sql
    override fun partitionSql(expression: Partition): String {
        val parent = expression.parent
        if (parent is PartitionByRangeProperty || parent is PartitionByListProperty) {
            return expressions(expression, flat = true)
        }
        return super.partitionSql(expression)
    }

    // sqlglot: MySQLGenerator._partition_by_sql
    protected fun partitionBySql(expression: Expression, kind: String): String {
        val partitions = expressions(expression, key = "partition_expressions", flat = true)
        val create = expressions(expression, key = "create_expressions", flat = true)
        return "PARTITION BY $kind ($partitions) ($create)"
    }

    // sqlglot: MySQLGenerator.partitionbyrangeproperty_sql
    override fun partitionbyrangepropertySql(expression: PartitionByRangeProperty): String =
        partitionBySql(expression, "RANGE")

    // sqlglot: MySQLGenerator.partitionbylistproperty_sql
    open fun partitionbylistpropertySql(expression: PartitionByListProperty): String =
        partitionBySql(expression, "LIST")

    // sqlglot: MySQLGenerator.partitionlist_sql
    open fun partitionlistSql(expression: PartitionList): String {
        val name = sql(expression, "this")
        val values = expressions(expression, flat = true)
        return "PARTITION $name VALUES IN ($values)"
    }

    // sqlglot: MySQLGenerator.partitionrange_sql
    override fun partitionrangeSql(expression: PartitionRange): String {
        val name = sql(expression, "this")
        val values = expressions(expression, flat = true)
        return "PARTITION $name VALUES LESS THAN ($values)"
    }

    // Python truthiness for raw arg values.
    protected fun isTruthy(value: kotlin.Any?): Boolean = when (value) {
        null, false, "", 0, 0L, 0.0 -> false
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    companion object {

        // sqlglot: dialect.UNESCAPED_SEQUENCES inverted (non-printable chars + backslash)
        val ESCAPED_SEQUENCES: Map<String, String> = mapOf(
            "\u0007" to "\\a",
            "\b" to "\\b",
            "\u000C" to "\\f",
            "\n" to "\\n",
            "\r" to "\\r",
            "\t" to "\\t",
            "\u000B" to "\\v",
            "\\" to "\\\\",
        )

        // sqlglot: MySQLGenerator.UNSIGNED_TYPE_MAPPING
        val UNSIGNED_TYPE_MAPPING: Map<DType, String> = mapOf(
            DType.UBIGINT to "BIGINT",
            DType.UINT to "INT",
            DType.UMEDIUMINT to "MEDIUMINT",
            DType.USMALLINT to "SMALLINT",
            DType.UTINYINT to "TINYINT",
            DType.UDECIMAL to "DECIMAL",
            DType.UDOUBLE to "DOUBLE",
        )

        // sqlglot: MySQLGenerator.TIMESTAMP_TYPE_MAPPING
        val TIMESTAMP_TYPE_MAPPING: Map<DType, String> = mapOf(
            DType.DATETIME2 to "DATETIME",
            DType.SMALLDATETIME to "DATETIME",
            DType.TIMESTAMP to "DATETIME",
            DType.TIMESTAMPNTZ to "DATETIME",
            DType.TIMESTAMPTZ to "TIMESTAMP",
            DType.TIMESTAMPLTZ to "TIMESTAMP",
        )

        // sqlglot: MySQLGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = mapOf(
            DType.NCHAR to "CHAR",
            DType.NVARCHAR to "VARCHAR",
            DType.INET to "INET",
            DType.ROWVERSION to "VARBINARY",
        ) + UNSIGNED_TYPE_MAPPING + TIMESTAMP_TYPE_MAPPING

        // sqlglot: MySQLGenerator.CAST_MAPPING
        val CAST_MAPPING: Map<DType, String> = mapOf(
            DType.LONGTEXT to "CHAR",
            DType.LONGBLOB to "CHAR",
            DType.MEDIUMBLOB to "CHAR",
            DType.MEDIUMTEXT to "CHAR",
            DType.TEXT to "CHAR",
            DType.TINYBLOB to "CHAR",
            DType.TINYTEXT to "CHAR",
            DType.VARCHAR to "CHAR",
            DType.BIGINT to "SIGNED",
            DType.BOOLEAN to "SIGNED",
            DType.INT to "SIGNED",
            DType.SMALLINT to "SIGNED",
            DType.TINYINT to "SIGNED",
            DType.MEDIUMINT to "SIGNED",
            DType.UBIGINT to "UNSIGNED",
        )

        // sqlglot: MySQLGenerator.TIMESTAMP_FUNC_TYPES
        val TIMESTAMP_FUNC_TYPES: Set<DType> = setOf(DType.TIMESTAMPTZ, DType.TIMESTAMPLTZ)

        // sqlglot: MySQLGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            GeneratorTables.PROPERTIES_LOCATION + mapOf(
                TransientProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                VolatileProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                PartitionedByProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                PartitionByRangeProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                PartitionByListProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
            )

        // sqlglot: MySQLGenerator.TRANSFORMS (dispatch-map overlay; multi-line entries are
        // methods on MysqlGenerator, one-liners are inlined like Python's lambdas)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.mg(): MysqlGenerator = this as MysqlGenerator

            reg(ArrayAgg::class) { e -> mg().renameFuncSql("GROUP_CONCAT", e) }
            reg(BitwiseAndAgg::class) { e -> mg().renameFuncSql("BIT_AND", e) }
            reg(BitwiseOrAgg::class) { e -> mg().renameFuncSql("BIT_OR", e) }
            reg(BitwiseXorAgg::class) { e -> mg().renameFuncSql("BIT_XOR", e) }
            reg(BitwiseCount::class) { e -> mg().renameFuncSql("BIT_COUNT", e) }
            // MySQL/Doris reject a bare COUNT(); normalize zero-arg count() -> COUNT(*)
            // (e.g. transpiling ClickHouse count()). Inherited by DorisGenerator.
            reg(Count::class) { e -> countStarSql(e as Count) }
            reg(Chr::class) { e -> chrSql(e as Chr, "CHAR") }
            reg(CurrentDate::class) { e -> mg().noParenCurrentDateSql(e as CurrentDate) }
            reg(CurrentVersion::class) { e -> mg().renameFuncSql("VERSION", e) }
            reg(DateDiff::class) { e ->
                mg().removeTsOrDsToDate(e, "this", "expression")
                func("DATEDIFF", e.thisArg, e.args["expression"])
            }
            reg(DateAdd::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                mg().dateAddSql("ADD", e)
            }
            reg(DateStrToDate::class) { e ->
                sql(Cast(args("this" to e.thisArg, "to" to DataType(args("this" to DType.DATE)))))
            }
            reg(DateSub::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                mg().dateAddSql("SUB", e)
            }
            reg(DateTrunc::class) { e -> mg().dateTruncSql(e as DateTrunc) }
            reg(Day::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                functionFallbackSql(e as Func)
            }
            reg(DayOfMonth::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                mg().renameFuncSql("DAYOFMONTH", e)
            }
            reg(DayOfWeek::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                mg().renameFuncSql("DAYOFWEEK", e)
            }
            reg(DayOfYear::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                mg().renameFuncSql("DAYOFYEAR", e)
            }
            reg(GroupConcat::class) { e ->
                val sep = sql(e, "separator").ifEmpty { "','" }
                "GROUP_CONCAT(${sql(e, "this")} SEPARATOR $sep)"
            }
            reg(ILike::class) { e -> mg().noIlikeSql(e as ILike) }
            reg(JSONExtractScalar::class) { e -> mg().arrowJsonExtractSql(e as Binary) }
            reg(Length::class) { e -> mg().lengthOrCharLengthSql(e as Length) }
            reg(LogicalOr::class) { e -> mg().renameFuncSql("MAX", e) }
            reg(LogicalAnd::class) { e -> mg().renameFuncSql("MIN", e) }
            reg(Max::class) { e -> mg().maxOrGreatestSql(e as Max) }
            reg(Min::class) { e -> mg().minOrLeastSql(e as Min) }
            reg(Month::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                functionFallbackSql(e as Func)
            }
            reg(NullSafeEQ::class) { e -> binary(e as Binary, "<=>") }
            reg(NullSafeNEQ::class) { e -> "NOT ${binary(e as Binary, "<=>")}" }
            reg(NumberToStr::class) { e -> mg().renameFuncSql("FORMAT", e) }
            reg(Pivot::class) { e -> mg().noPivotSql(e as Pivot) }
            // sqlglot mysql order: [eliminate_distinct_on, eliminate_semi_and_anti_joins,
            // eliminate_qualify, eliminate_full_outer_join,
            // unnest_generate_date_array_using_recursive_cte]. The last two remain
            // NOT PORTED (ledgered).
            reg(Select::class) { e ->
                var s = eliminateDistinctOn(e)
                s = eliminateSemiAndAntiJoins(s)
                s = eliminateQualify(s)
                selectSql(s as Select)
            }
            reg(StrPosition::class) { e -> mg().strpositionSql(e as StrPosition) }
            reg(StrToDate::class) { e -> mg().strToDateSql(e) }
            reg(StrToTime::class) { e -> mg().strToDateSql(e) }
            reg(Stuff::class) { e -> mg().renameFuncSql("INSERT", e) }
            reg(SessionUser::class) { _ -> "SESSION_USER()" }
            reg(TableSample::class) { e -> mg().noTablesampleSql(e as TableSample) }
            reg(TimeFromParts::class) { e -> mg().renameFuncSql("MAKETIME", e) }
            reg(TimestampAdd::class) { e -> mg().dateAddIntervalSql("ADD", e) }
            reg(TimestampDiff::class) { e ->
                func("TIMESTAMPDIFF", unitToVar(e), e.args["expression"], e.thisArg)
            }
            reg(TimestampSub::class) { e -> mg().dateAddIntervalSql("SUB", e) }
            reg(TimeStrToUnix::class) { e -> mg().renameFuncSql("UNIX_TIMESTAMP", e) }
            reg(TimeStrToTime::class) { e -> mg().timeStrToTimeSql(e as TimeStrToTime) }
            reg(TimeToStr::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                func("DATE_FORMAT", e.thisArg, formatTime(e))
            }
            reg(Trim::class) { e -> mg().mysqlTrimSql(e as Trim) }
            reg(Trunc::class) { e -> mg().renameFuncSql("TRUNCATE", e) }
            reg(TryCast::class) { e -> castSql(e as TryCast) }
            reg(TsOrDsAdd::class) { e -> mg().dateAddSql("ADD", e) }
            reg(TsOrDsDiff::class) { e -> func("DATEDIFF", e.thisArg, e.args["expression"]) }
            reg(TsOrDsToDate::class) { e -> mg().tsOrDsToDateSql(e as TsOrDsToDate) }
            reg(Unicode::class) { e -> "ORD(CONVERT(${sql(e.thisArg)} USING utf32))" }
            reg(UnixToTime::class) { e -> mg().unixToTimeSql(e as UnixToTime) }
            reg(Week::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                functionFallbackSql(e as Func)
            }
            reg(WeekOfYear::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                mg().renameFuncSql("WEEKOFYEAR", e)
            }
            reg(Year::class) { e ->
                mg().removeTsOrDsToDate(e, "this")
                functionFallbackSql(e as Func)
            }
            reg(UtcTimestamp::class) { e -> mg().renameFuncSql("UTC_TIMESTAMP", e) }
            reg(UtcTime::class) { e -> mg().renameFuncSql("UTC_TIME", e) }

            // sqlglot: auto-discovered <name>_sql methods that have no base dispatch entry
            reg(ArrayNode::class) { e -> mg().arraySql(e as ArrayNode) }
            reg(ArrayContainsAll::class) { e -> mg().arrayOpUnsupportedSql(e) }
            reg(ArrayContainedBy::class) { e -> mg().arrayOpUnsupportedSql(e) }
            reg(JSONArrayContains::class) { e -> mg().jsonarraycontainsSql(e as JSONArrayContains) }
            reg(MakeInterval::class) { e -> mg().makeintervalSql(e as MakeInterval) }
            reg(TimestampTrunc::class) { e -> mg().timestamptruncSql(e as TimestampTrunc) }
            reg(ConvertTimezone::class) { e -> mg().converttimezoneSql(e as ConvertTimezone) }
            reg(IsAscii::class) { e -> mg().isasciiSql(e as IsAscii) }
            reg(CurrentSchema::class) { e -> mg().currentschemaSql(e as CurrentSchema) }
            reg(PartitionByListProperty::class) { e -> mg().partitionbylistpropertySql(e as PartitionByListProperty) }
            reg(PartitionList::class) { e -> mg().partitionlistSql(e as PartitionList) }
        }

        // sqlglot: MySQLGenerator.RESERVED_KEYWORDS
        // https://dev.mysql.com/doc/refman/8.0/en/keywords.html
        val RESERVED_KEYWORDS: Set<String> = setOf(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive",
            "before", "between", "bigint", "binary", "blob", "both", "by", "call", "cascade",
            "case", "change", "char", "character", "check", "collate", "column", "condition",
            "constraint", "continue", "convert", "create", "cross", "cube", "cume_dist",
            "current_date", "current_time", "current_timestamp", "current_user", "cursor",
            "database", "databases", "day_hour", "day_microsecond", "day_minute", "day_second",
            "dec", "decimal", "declare", "default", "delayed", "delete", "dense_rank", "desc",
            "describe", "deterministic", "distinct", "distinctrow", "div", "double", "drop",
            "dual", "each", "else", "elseif", "empty", "enclosed", "escaped", "except",
            "exists", "exit", "explain", "false", "fetch", "first_value", "float", "float4",
            "float8", "for", "force", "foreign", "from", "fulltext", "function", "generated",
            "get", "grant", "group", "grouping", "groups", "having", "high_priority",
            "hour_microsecond", "hour_minute", "hour_second", "if", "ignore", "in", "index",
            "infile", "inner", "inout", "insensitive", "insert", "int", "int1", "int2", "int3",
            "int4", "int8", "integer", "intersect", "interval", "into", "io_after_gtids",
            "io_before_gtids", "is", "iterate", "join", "json_table", "key", "keys", "kill",
            "lag", "last_value", "lateral", "lead", "leading", "leave", "left", "like",
            "limit", "linear", "lines", "load", "localtime", "localtimestamp", "lock", "long",
            "longblob", "longtext", "loop", "low_priority", "master_bind",
            "master_ssl_verify_server_cert", "match", "maxvalue", "mediumblob", "mediumint",
            "mediumtext", "middleint", "minute_microsecond", "minute_second", "mod",
            "modifies", "natural", "not", "no_write_to_binlog", "nth_value", "ntile", "null",
            "numeric", "of", "on", "optimize", "optimizer_costs", "option", "optionally",
            "or", "order", "out", "outer", "outfile", "over", "partition", "percent_rank",
            "precision", "primary", "procedure", "purge", "range", "rank", "read", "reads",
            "read_write", "real", "recursive", "references", "regexp", "release", "rename",
            "repeat", "replace", "require", "resignal", "restrict", "return", "revoke",
            "right", "rlike", "row", "rows", "row_number", "schema", "schemas",
            "second_microsecond", "select", "sensitive", "separator", "set", "show", "signal",
            "smallint", "spatial", "specific", "sql", "sqlexception", "sqlstate",
            "sqlwarning", "sql_big_result", "sql_calc_found_rows", "sql_small_result", "ssl",
            "starting", "stored", "straight_join", "system", "table", "terminated", "then",
            "tinyblob", "tinyint", "tinytext", "to", "trailing", "trigger", "true", "undo",
            "union", "unique", "unlock", "unsigned", "update", "usage", "use", "using",
            "utc_date", "utc_time", "utc_timestamp", "values", "varbinary", "varchar",
            "varcharacter", "varying", "virtual", "when", "where", "while", "window", "with",
            "write", "xor", "year_month", "zerofill",
        )
    }
}
