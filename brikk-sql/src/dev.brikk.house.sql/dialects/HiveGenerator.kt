package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.generator.eliminateDistinctOn
import dev.brikk.house.sql.generator.unnestToExplode
import dev.brikk.house.sql.generator.anyToExists
import dev.brikk.house.sql.generator.inheritStructFieldNames
import dev.brikk.house.sql.parser.HiveTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set
import kotlin.reflect.KClass

// sqlglot: dialect.unit_to_str
private fun hiveUnitToStr(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression
        ?: return if (default.isNotEmpty()) Literal.string(default) else null
    if (unit is Placeholder || (unit !is Var && unit !is Literal)) return unit
    return Literal.string(unit.name)
}

// sqlglot: generators.hive HIVE_DATEINT_FORMAT / HIVE_TIME_FORMAT / HIVE_DATE_FORMAT
private const val HIVE_TIME_FORMAT = "'yyyy-MM-dd HH:mm:ss'"
private const val HIVE_DATE_FORMAT = "'yyyy-MM-dd'"
private const val HIVE_DATEINT_FORMAT = "'yyyyMMdd'"

// sqlglot: generators.hive DATE_DELTA_INTERVAL (FuncName, Multiplier)
private val DATE_DELTA_INTERVAL: Map<String, Pair<String, Int>> = mapOf(
    "YEAR" to ("ADD_MONTHS" to 12),
    "MONTH" to ("ADD_MONTHS" to 1),
    "QUARTER" to ("ADD_MONTHS" to 3),
    "WEEK" to ("DATE_ADD" to 7),
    "DAY" to ("DATE_ADD" to 1),
)

// sqlglot: generators.hive TIME_DIFF_FACTOR
private val TIME_DIFF_FACTOR: Map<String, String> = mapOf(
    "MILLISECOND" to " * 1000", "SECOND" to "", "MINUTE" to " / 60", "HOUR" to " / 3600",
)

private val DIFF_MONTH_SWITCH = setOf("YEAR", "QUARTER", "MONTH")

/**
 * Port of sqlglot's HiveGenerator (reference/sqlglot/sqlglot/generators/hive.py).
 * TRANSFORMS entries live in [TRANSFORMS] (a dispatch-map overlay handed to the base
 * constructor); flag overrides are open-val overrides; multi-line methods below.
 *
 * NOT PORTED (no Kotlin equivalents of sqlglot's transforms/preprocess pipelines yet):
 * the exp.Select preprocess (eliminate_qualify/eliminate_distinct_on/unnest_to_explode/
 * any_to_exists), exp.Array (inherit_struct_field_names), exp.Create (remove_unique_
 * constraints/ctas_with_tmp_tables_to_create_tmp_view/move_schema_columns_to_partitioned_by),
 * exp.Table (unnest_generate_series). These render via the base generator; any mismatches
 * are ledgered.
 */
// sqlglot: generators.hive.HiveGenerator
open class HiveGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = HiveTokenizerTables.CONFIG,
    // extra dispatch overlay for subclasses (sqlglot: Spark2/Spark TRANSFORMS merges)
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
    override val dialect: Dialect get() = Dialects.HIVE

    // ------------------------------------------------------------------
    // Flags (sqlglot: HiveGenerator class attributes)
    // ------------------------------------------------------------------

    override val selectKinds: Set<String> get() = emptySet()
    override val trySupported: Boolean get() = false
    override val supportsUescape: Boolean get() = false
    override val limitFetch: String get() = "LIMIT"

    // sqlglot: HiveGenerator.EXPRESSIONS_WITHOUT_NESTED_CTES (drives move_ctes_to_top_level)
    override val expressionsWithoutNestedCtes:
        Set<kotlin.reflect.KClass<out Expression>>
        get() = setOf(Insert::class, Select::class, Subquery::class, SetOperation::class)
    override val tablesampleWithMethod: Boolean get() = false
    override val joinHints: Boolean get() = false
    override val tableHints: Boolean get() = false
    override val queryHints: Boolean get() = false
    override val indexOn: String get() = "ON TABLE"
    override val extractAllowsQuotes: Boolean get() = false
    override val nvl2Supported: Boolean get() = false
    override val jsonPathSingleQuoteEscape: Boolean get() = true
    override val supportsToNumber: Boolean get() = false
    override val withPropertiesPrefix: String get() = "TBLPROPERTIES"
    override val parseJsonName: String? get() = "PARSE_JSON"
    override val padFillPatternIsRequired: Boolean get() = true
    override val supportsMedian: Boolean get() = false
    override val arraySizeName: String get() = "SIZE"
    override val alterSetType: String get() = ""

    // sqlglot: Hive dialect-level flags read by the generator
    override val dialectTimeFormat: String get() = HIVE_TIME_FORMAT
    override val inverseTimeMapping: Map<String, String> get() = HIVE_INVERSE_TIME_MAPPING

    // sqlglot: Hive.STRINGS_SUPPORT_ESCAPED_SEQUENCES = True + Hive.ESCAPED_SEQUENCES
    override val dialectStringsSupportEscapedSequences: Boolean get() = true
    override val escapedSequences: Map<String, String> get() = ESCAPED_SEQUENCES

    // sqlglot: Hive.ALTER_TABLE_SUPPORTS_CASCADE = True
    override val dialectAlterTableSupportsCascade: Boolean get() = true

    // sqlglot: Hive.IDENTIFIERS_CAN_START_WITH_DIGIT = True
    override val dialectIdentifiersCanStartWithDigit: Boolean get() = true

    // sqlglot: Hive.ALIAS_POST_TABLESAMPLE = True (sample rendered before the alias)
    override val dialectAliasPostTablesample: Boolean get() = true

    // sqlglot: Hive.SAFE_DIVISION = True (division does not add a NULLIF guard)
    override val dialectSafeDivision: Boolean get() = true

    // sqlglot: HiveGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: HiveGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // sqlglot: HiveGenerator.TS_OR_DS_EXPRESSIONS
    protected open val tsOrDsExpressions: Set<KClass<out Expression>>
        get() = setOf(DateDiff::class, Day::class, Month::class, Year::class)

    // sqlglot: HiveGenerator.IGNORE_NULLS_FUNCS
    protected open val ignoreNullsFuncs: Set<KClass<out Expression>>
        get() = setOf(First::class, Last::class, FirstValue::class, LastValue::class)

    // ------------------------------------------------------------------
    // Transform helpers (sqlglot: module-level functions / dialect helpers)
    // ------------------------------------------------------------------

    internal fun isTruthy(value: kotlin.Any?): Boolean = when (value) {
        null, false -> false
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    // sqlglot: dialect.rename_func
    internal fun renameFuncSql(name: String, expression: Expression): String {
        val flattened = expression.args.values.flatMap { v ->
            if (v is List<*>) v else listOf(v)
        }
        return func(name, *flattened.toTypedArray())
    }

    // sqlglot: generators.hive._add_date_sql (DateAdd/DateSub/TsOrDsAdd)
    internal fun addDateSql(expression: Expression): String {
        if (expression is TsOrDsAdd && expression.args["unit"] == null) {
            return func("DATE_ADD", expression.thisArg, expression.args["expression"])
        }

        val unit = (expression.args["unit"] as? Expression)?.name?.uppercase() ?: ""
        var (funcName, multiplier) = DATE_DELTA_INTERVAL[unit] ?: ("DATE_ADD" to 1)

        if (expression is DateSub) multiplier *= -1

        var increment = expression.args["expression"] as? Expression
        if (increment is Literal) {
            val value = if (increment.isNumber) increment.name.toLong() else increment.name.toLong()
            increment = Literal.number((value * multiplier).toString())
        } else if (multiplier != 1 && increment != null) {
            increment = Mul(args("this" to increment, "expression" to Literal.number(multiplier.toString())))
        }

        return func(funcName, expression.thisArg, increment)
    }

    // sqlglot: generators.hive._date_diff_sql (DateDiff/TsOrDsDiff)
    internal fun dateDiffSql(expression: Expression): String {
        val unit = (expression.args["unit"] as? Expression)?.name?.uppercase() ?: ""

        val factor = TIME_DIFF_FACTOR[unit]
        if (factor != null) {
            val left = sql(expression, "this")
            val right = sql(expression, "expression")
            val secDiff = "UNIX_TIMESTAMP($left) - UNIX_TIMESTAMP($right)"
            return if (factor.isNotEmpty()) "($secDiff)$factor" else secDiff
        }

        val monthsBetween = unit in DIFF_MONTH_SWITCH
        val sqlFunc = if (monthsBetween) "MONTHS_BETWEEN" else "DATEDIFF"
        val multiplier = DATE_DELTA_INTERVAL[unit]?.second ?: 1
        val multiplierSql = if (multiplier > 1) " / $multiplier" else ""
        var diffSql = "$sqlFunc(${formatArgs(expression.thisArg, expression.args["expression"])})"

        if (monthsBetween || multiplierSql.isNotEmpty()) {
            diffSql = "CAST($diffSql$multiplierSql AS INT)"
        }
        return diffSql
    }

    // sqlglot: generators.hive._to_date_sql (TsOrDsToDate)
    internal fun toDateSql(expression: TsOrDsToDate): String {
        val timeFormat = formatTime(expression)
        if (timeFormat != null && timeFormat != HIVE_TIME_FORMAT && timeFormat != HIVE_DATE_FORMAT) {
            return func("TO_DATE", expression.thisArg, timeFormat)
        }
        if (expression.parent?.let { it::class in tsOrDsExpressions } == true) {
            return sql(expression, "this")
        }
        return func("TO_DATE", expression.thisArg)
    }

    // sqlglot: generators.hive._str_to_date_sql / _str_to_time_sql
    internal fun strToTemporalSql(expression: Expression, castTo: String): String {
        var this_ = sql(expression, "this")
        val timeFormat = formatTime(expression)
        if (timeFormat != null && timeFormat != HIVE_TIME_FORMAT && timeFormat != HIVE_DATE_FORMAT) {
            this_ = "FROM_UNIXTIME(UNIX_TIMESTAMP($this_, $timeFormat))"
        }
        return "CAST($this_ AS $castTo)"
    }

    // sqlglot: dialect.time_format("hive") — the rendered format, unless it equals the
    // dialect's default TIME_FORMAT (then null so it isn't emitted).
    internal fun hiveTimeFormat(expression: Expression): String? {
        val tf = formatTime(expression)
        return if (tf != HIVE_TIME_FORMAT) tf else null
    }

    // sqlglot: generators.hive._str_to_unix_sql
    internal fun strToUnixSql(expression: StrToUnix): String =
        func("UNIX_TIMESTAMP", expression.thisArg, hiveTimeFormat(expression))

    // sqlglot: generators.hive._unix_to_time_sql
    internal fun unixToTimeSql(expression: UnixToTime): String {
        val timestamp = sql(expression, "this")
        val scale = expression.args["scale"]
        if (scale == null || (scale as? Expression)?.name == "0") {
            return renameFuncSql("FROM_UNIXTIME", expression)
        }
        return "FROM_UNIXTIME($timestamp / POW(10, ${sql(scale)}))"
    }

    // sqlglot: dialect.arg_max_or_min_no_count
    internal fun argMaxOrMinNoCount(name: String, expression: Expression): String =
        func(name, expression.thisArg, expression.args["expression"])

    // sqlglot: dialect.max_or_greatest / min_or_least
    internal fun maxOrGreatestSql(expression: Max): String =
        if (expression.args["expressions"] != null || expression.args["expression"] != null) {
            renameFuncSql("GREATEST", expression)
        } else {
            renameFuncSql("MAX", expression)
        }

    internal fun minOrLeastSql(expression: Min): String =
        if (expression.args["expressions"] != null || expression.args["expression"] != null) {
            renameFuncSql("LEAST", expression)
        } else {
            renameFuncSql("MIN", expression)
        }

    // sqlglot: dialect.timestrtotime_sql
    internal fun timestrtotimeSql(expression: TimeStrToTime): String =
        "CAST(${sql(expression, "this")} AS TIMESTAMP)"

    // sqlglot: dialect.datestrtodate_sql
    internal fun datestrtodateSql(expression: DateStrToDate): String =
        "CAST(${sql(expression, "this")} AS DATE)"

    // sqlglot: dialect.left_to_substring_sql
    internal fun leftToSubstringSql(expression: Left): String =
        sql(
            Substring(
                args(
                    "this" to expression.thisArg,
                    "start" to Literal.number("1"),
                    "length" to expression.args["expression"],
                )
            )
        )

    // sqlglot: dialect.right_to_substring_sql
    internal fun rightToSubstringSql(expression: Right): String {
        val start = Neg(args("this" to expression.args["expression"]))
        return sql(Substring(args("this" to expression.thisArg, "start" to start)))
    }

    // sqlglot: dialect.no_ilike_sql
    internal fun noIlikeSql(expression: ILike): String =
        binary(
            Like(
                args(
                    "this" to Lower(args("this" to expression.thisArg)),
                    "expression" to expression.args["expression"],
                )
            ),
            "LIKE",
        )

    // sqlglot: dialect.no_trycast_sql
    internal fun noTrycastSql(expression: TryCast): String = castSql(expression)

    // sqlglot: dialect.no_recursive_cte_sql
    internal fun noRecursiveCteSql(expression: With): String {
        if (isTruthy(expression.args["recursive"])) {
            unsupported("Recursive CTEs are unsupported")
            expression.set("recursive", false)
        }
        return withSql(expression)
    }

    // sqlglot: dialect.var_map_sql
    internal fun varMapSql(expression: Expression, mapFuncName: String = "MAP"): String {
        val keys = expression.args["keys"]
        val values = expression.args["values"]
        if (keys !is ArrayNode || values !is ArrayNode) {
            unsupported("Cannot convert array columns into map.")
            return func(mapFuncName, keys, values)
        }
        val out = keys.expressionsArg.zip(values.expressionsArg)
            .flatMap { (k, v) -> listOf<kotlin.Any?>(k, v) }
        return func(mapFuncName, *out.toTypedArray())
    }

    // sqlglot: dialect.regexp_extract_sql (Hive.REGEXP_EXTRACT_DEFAULT_GROUP=1)
    internal fun regexpExtractSql(expression: Expression): String {
        var group = expression.args["group"] as? Expression
        if (group != null && group.name == "1") group = null
        return func(
            (expression as Func).sqlName(),
            expression.thisArg,
            expression.args["expression"],
            group,
        )
    }

    // sqlglot: dialect.regexp_replace_sql
    internal fun regexpReplaceSql(expression: RegexpReplace): String =
        func("REGEXP_REPLACE", expression.thisArg, expression.args["expression"], expression.args["replacement"])

    // sqlglot: dialect.struct_extract_sql
    internal fun structExtractSql(expression: StructExtract): String {
        val name = (expression.args["expression"] as? Expression)?.name ?: ""
        val identifier = if (SAFE_IDENTIFIER_RE.matches(name)) {
            Identifier(args("this" to name))
        } else {
            Identifier(args("this" to name, "quoted" to true))
        }
        return "${sql(expression, "this")}.${sql(identifier)}"
    }

    // sqlglot: dialect.strposition_sql func_name=LOCATE, supports_position=True
    internal fun strpositionLocateSql(expression: StrPosition): String {
        val this_ = expression.thisArg
        val substr = expression.args["substr"]
        val position = expression.args["position"]
        return if (position != null) {
            func("LOCATE", substr, this_, position)
        } else {
            func("LOCATE", substr, this_)
        }
    }

    // ------------------------------------------------------------------
    // Method overrides (sqlglot: HiveGenerator methods)
    // ------------------------------------------------------------------

    // sqlglot: HiveGenerator.ignorenulls_sql
    override fun ignorenullsSql(expression: IgnoreNulls): String {
        val this_ = expression.thisArg as? Expression
        if (this_ != null && this_::class in ignoreNullsFuncs) {
            return func(funcName(this_), this_.thisArg, BooleanNode(args("this" to true)))
        }
        return super.ignorenullsSql(expression)
    }

    private fun funcName(e: Expression): String = when (e) {
        is First -> "FIRST"
        is Last -> "LAST"
        is FirstValue -> "FIRST_VALUE"
        is LastValue -> "LAST_VALUE"
        else -> e::class.simpleName?.uppercase() ?: ""
    }

    // sqlglot: HiveGenerator.arrayagg_sql — COLLECT_LIST(order.this or this)
    override fun arrayaggSql(expression: ArrayAgg): String {
        val this_ = expression.thisArg as? Expression
        val target = if (this_ is Order) this_.thisArg else this_
        return func("COLLECT_LIST", target)
    }

    // sqlglot: HiveGenerator.datatype_sql
    override fun datatypeSql(expression: DataType): String {
        val thisType = expression.thisArg
        if (thisType in parameterizableTextTypes &&
            (expression.expressionsArg.isEmpty() ||
                (expression.expressionsArg.firstOrNull() as? Expression)?.name == "MAX")
        ) {
            expression.set("this", DType.TEXT)
            expression.set("expressions", null)
        } else if (expression.isType(DType.TEXT) && expression.expressionsArg.isNotEmpty()) {
            expression.set("this", DType.VARCHAR)
        } else if (thisType in DataType.TEMPORAL_TYPES) {
            expression.set("expressions", null)
        } else if (expression.isType(DType.FLOAT)) {
            val sizeExpr = expression.find(DataTypeParam::class)
            if (sizeExpr != null) {
                val size = sizeExpr.name.toIntOrNull() ?: 0
                expression.set("this", if (size <= 32) DType.FLOAT else DType.DOUBLE)
                expression.set("expressions", null)
            }
        }
        return super.datatypeSql(expression)
    }

    // sqlglot: HiveGenerator.version_sql (strips leading FOR)
    override fun versionSql(expression: Version): String =
        super.versionSql(expression).replaceFirst("FOR ", "")

    // sqlglot: HiveGenerator.struct_sql (no named structs)
    override fun structSql(expression: Struct): String {
        val values = mutableListOf<kotlin.Any?>()
        for (e in expression.expressionsArg) {
            if (e is PropertyEQ) {
                unsupported("Hive does not support named structs.")
                values.add(e.args["expression"])
            } else {
                values.add(e)
            }
        }
        return func("STRUCT", *values.toTypedArray())
    }

    // sqlglot: HiveGenerator.columndef_sql (struct field sep ": ")
    override fun columndefSql(expression: ColumnDef, sep: String): String {
        val parent = expression.parent
        val effSep = if (parent is DataType && parent.isType(DType.STRUCT)) ": " else sep
        return super.columndefSql(expression, effSep)
    }

    // sqlglot: HiveGenerator.altercolumn_sql (CHANGE COLUMN)
    override fun altercolumnSql(expression: AlterColumn): String {
        val this_ = sql(expression, "this")
        val newName = sql(expression, "rename_to").ifEmpty { this_ }
        val dtype = sql(expression, "dtype")
        val commentSql = sql(expression, "comment")
        val comment = if (commentSql.isNotEmpty()) " COMMENT $commentSql" else ""

        val default = sql(expression, "default")
        val visible = expression.args["visible"]
        val allowNull = expression.args["allow_null"]
        val drop = expression.args["drop"]

        if (isTruthy(default) || isTruthy(drop) || isTruthy(visible) || isTruthy(allowNull)) {
            unsupported("Unsupported CHANGE COLUMN syntax")
        }
        if (dtype.isEmpty()) {
            unsupported("CHANGE COLUMN without a type is not supported")
        }
        return "CHANGE COLUMN $this_ $newName $dtype$comment"
    }

    // sqlglot: HiveGenerator.renamecolumn_sql
    override fun renamecolumnSql(expression: RenameColumn): String {
        unsupported("Cannot rename columns without data type defined in Hive")
        return ""
    }

    // sqlglot: HiveGenerator.alterset_sql
    override fun altersetSql(expression: AlterSet): String {
        val exprsRaw = expressions(expression, flat = true)
        val exprs = if (exprsRaw.isNotEmpty()) " $exprsRaw" else ""
        val locationRaw = sql(expression, "location")
        val location = if (locationRaw.isNotEmpty()) " LOCATION $locationRaw" else ""
        val fileFormatRaw = expressions(expression, key = "file_format", flat = true, sep = " ")
        val fileFormat = if (fileFormatRaw.isNotEmpty()) " FILEFORMAT $fileFormatRaw" else ""
        val serdeRaw = sql(expression, "serde")
        val serde = if (serdeRaw.isNotEmpty()) " SERDE $serdeRaw" else ""
        val tagsRaw = expressions(expression, key = "tag", flat = true, sep = "")
        val tags = if (tagsRaw.isNotEmpty()) " TAGS $tagsRaw" else ""
        return "SET$serde$exprs$location$fileFormat$tags"
    }

    // sqlglot: HiveGenerator.serdeproperties_sql
    open fun serdepropertiesSql(expression: SerdeProperties): String {
        val prefix = if (isTruthy(expression.args["with_"])) "WITH " else ""
        val exprs = expressions(expression, flat = true)
        return "${prefix}SERDEPROPERTIES ($exprs)"
    }

    // sqlglot: HiveGenerator.exists_sql — EXISTS(subquery, expression) becomes a function
    // call when a second (predicate) argument is present.
    override fun existsSql(expression: Exists): String {
        if (expression.args["expression"] != null) {
            return func("EXISTS", expression.thisArg, expression.args["expression"])
        }
        return super.existsSql(expression)
    }

    // sqlglot: HiveGenerator.timetostr_sql
    open fun timetostrSql(expression: TimeToStr): String {
        var this_ = expression.thisArg as? Expression
        if (this_ is TimeStrToTime) this_ = this_.thisArg as? Expression
        return func("DATE_FORMAT", this_, formatTime(expression))
    }

    // sqlglot: HiveGenerator.usingproperty_sql
    open fun usingpropertySql(expression: UsingProperty): String {
        val kind = expression.args["kind"]
        return "USING $kind ${sql(expression, "this")}"
    }

    // sqlglot: HiveGenerator.fileformatproperty_sql
    open fun fileformatpropertySql(expression: FileFormatProperty): String {
        val this_ = expression.thisArg
        val rendered = if (this_ is InputOutputFormat) sql(expression, "this") else expression.name.uppercase()
        return "STORED AS $rendered"
    }

    // sqlglot: HiveGenerator.parameter_sql
    override fun parameterSql(expression: Parameter): String {
        var this_ = sql(expression, "this")
        val expressionSql = sql(expression, "expression")
        val parent = expression.parent
        this_ = if (expressionSql.isNotEmpty()) "$this_:$expressionSql" else this_
        if (parent is EQ && parent.parent is SetItem) {
            return this_
        }
        return "\${$this_}"
    }

    // sqlglot: HiveGenerator.trunc_sql (numeric TRUNC via CAST to BIGINT)
    open fun truncSql(expression: Trunc): String {
        if (expression.args["decimals"] != null) unsupported("TRUNC decimals not supported in Hive")
        return sql(Cast(args("this" to expression.thisArg, "to" to DataType(args("this" to DType.BIGINT)))))
    }

    // sqlglot: Generator.PARAMETERIZABLE_TEXT_TYPES
    private val parameterizableTextTypes: Set<DType> =
        setOf(DType.CHAR, DType.NCHAR, DType.VARCHAR, DType.NVARCHAR)

    companion object {
        // sqlglot: dialects.hive.Hive.ESCAPED_SEQUENCES (control chars + backslash)
        val ESCAPED_SEQUENCES: Map<String, String> = mapOf(
            "\u0007" to "\\a", "\u0008" to "\\b", "\u000c" to "\\f", "\n" to "\\n",
            "\r" to "\\r", "\t" to "\\t", "\u000b" to "\\v", "\\" to "\\\\",
        )

        // sqlglot: HiveGenerator.TYPE_MAPPING (base Generator.TYPE_MAPPING + Hive overrides)
        val TYPE_MAPPING: Map<DType, String> = buildMap {
            // sqlglot: generator.Generator.TYPE_MAPPING defaults
            put(DType.DATETIME2, "TIMESTAMP")
            put(DType.NCHAR, "CHAR")
            put(DType.NVARCHAR, "VARCHAR")
            put(DType.MEDIUMTEXT, "TEXT")
            put(DType.LONGTEXT, "TEXT")
            put(DType.TINYTEXT, "TEXT")
            put(DType.BLOB, "VARBINARY")
            put(DType.MEDIUMBLOB, "BLOB")
            put(DType.LONGBLOB, "BLOB")
            put(DType.TINYBLOB, "BLOB")
            put(DType.INET, "INET")
            put(DType.ROWVERSION, "VARBINARY")
            put(DType.SMALLDATETIME, "TIMESTAMP")
            // sqlglot: HiveGenerator.TYPE_MAPPING overrides
            put(DType.BIT, "BOOLEAN")
            put(DType.BLOB, "BINARY")
            put(DType.DATETIME, "TIMESTAMP")
            put(DType.ROWVERSION, "BINARY")
            put(DType.TEXT, "STRING")
            put(DType.TIME, "TIMESTAMP")
            put(DType.TIMESTAMPNTZ, "TIMESTAMP")
            put(DType.TIMESTAMPTZ, "TIMESTAMP")
            put(DType.UTINYINT, "SMALLINT")
            put(DType.VARBINARY, "BINARY")
        }

        // sqlglot: HiveGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> = buildMap {
            putAll(GeneratorTables.PROPERTIES_LOCATION)
            @Suppress("UNUSED_EXPRESSION")
            put(FileFormatProperty::class, GeneratorTables.PropLocation.POST_SCHEMA)
            put(PartitionedByProperty::class, GeneratorTables.PropLocation.POST_SCHEMA)
            put(VolatileProperty::class, GeneratorTables.PropLocation.UNSUPPORTED)
            put(WithDataProperty::class, GeneratorTables.PropLocation.UNSUPPORTED)
        }

        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.hg(): HiveGenerator = this as HiveGenerator

            // sqlglot: dialect.property_sql (string_key=True) — Hive quotes bare property keys
            reg(Property::class) { e ->
                val p = e as Property
                if (p::class == Property::class) {
                    "${propertyName(p, stringKey = true)}=${sql(p, "value")}"
                } else {
                    propertySql(p)
                }
            }
            reg(AnyValue::class) { e -> hg().renameFuncSql("FIRST", e) }
            // sqlglot: dialect.approx_count_distinct_sql (@unsupported_args("accuracy"))
            reg(ApproxDistinct::class) { e ->
                if (e.args["accuracy"] != null) {
                    unsupported("Argument 'accuracy' is not supported for expression 'ApproxDistinct' when targeting Hive.")
                }
                func("APPROX_COUNT_DISTINCT", e.thisArg)
            }
            reg(ArgMax::class) { e -> hg().argMaxOrMinNoCount("MAX_BY", e) }
            reg(ArgMin::class) { e -> hg().argMaxOrMinNoCount("MIN_BY", e) }
            reg(ArrayConcat::class) { e -> hg().renameFuncSql("CONCAT", e) }
            reg(ArrayToString::class) { e -> func("CONCAT_WS", e.args["expression"], e.thisArg) }
            reg(ArraySort::class) { e ->
                if (e.args["expression"] != null) {
                    unsupported("Hive's SORT_ARRAY does not support a comparator.")
                }
                func("SORT_ARRAY", e.thisArg)
            }
            reg(With::class) { e -> hg().noRecursiveCteSql(e as With) }
            // sqlglot: hive exp.Array preprocess [inherit_struct_field_names]
            reg(ArrayNode::class) { e -> functionFallbackSql(inheritStructFieldNames(e) as ArrayNode) }
            // sqlglot: hive exp.Select preprocess pipeline
            // [eliminate_distinct_on, unnest_to_explode(unnest_using_arrays_zip=False), any_to_exists]
            reg(Select::class) { e ->
                var s = eliminateDistinctOn(e)
                s = unnestToExplode(s, unnestUsingArraysZip = false)
                s = anyToExists(s)
                selectSql(s as Select)
            }
            reg(DateAdd::class) { e -> hg().addDateSql(e) }
            reg(DateDiff::class) { e -> hg().dateDiffSql(e) }
            reg(DateStrToDate::class) { e -> hg().datestrtodateSql(e as DateStrToDate) }
            reg(DateSub::class) { e -> hg().addDateSql(e) }
            reg(DateToDi::class) { e ->
                "CAST(DATE_FORMAT(${sql(e, "this")}, $HIVE_DATEINT_FORMAT) AS INT)"
            }
            reg(DiToDate::class) { e ->
                "TO_DATE(CAST(${sql(e, "this")} AS STRING), $HIVE_DATEINT_FORMAT)"
            }
            reg(StorageHandlerProperty::class) { e -> "STORED BY ${sql(e, "this")}" }
            reg(FromBase64::class) { e -> hg().renameFuncSql("UNBASE64", e) }
            reg(If::class) { e -> ifSql(e as If) }
            reg(ILike::class) { e -> hg().noIlikeSql(e as ILike) }
            reg(IntDiv::class) { e -> binary(e as Binary, "DIV") }
            reg(IsNan::class) { e -> hg().renameFuncSql("ISNAN", e) }
            reg(JSONExtract::class) { e -> func("GET_JSON_OBJECT", e.thisArg, e.args["expression"]) }
            reg(JSONExtractScalar::class) { e -> func("GET_JSON_OBJECT", e.thisArg, e.args["expression"]) }
            reg(JSONFormat::class) { e -> hg().renameFuncSql("TO_JSON", e) }
            reg(Left::class) { e -> hg().leftToSubstringSql(e as Left) }
            reg(MapNode::class) { e -> hg().varMapSql(e) }
            reg(Max::class) { e -> hg().maxOrGreatestSql(e as Max) }
            reg(MD5Digest::class) { e -> func("UNHEX", func("MD5", e.thisArg)) }
            reg(Min::class) { e -> hg().minOrLeastSql(e as Min) }
            reg(MonthsBetween::class) { e -> func("MONTHS_BETWEEN", e.thisArg, e.args["expression"]) }
            reg(NotNullColumnConstraint::class) { e ->
                if (hg().isTruthy(e.args["allow_null"])) "" else "NOT NULL"
            }
            reg(VarMap::class) { e -> hg().varMapSql(e) }
            reg(Quantile::class) { e -> hg().renameFuncSql("PERCENTILE", e) }
            reg(ApproxQuantile::class) { e -> hg().renameFuncSql("PERCENTILE_APPROX", e) }
            reg(RegexpExtract::class) { e -> hg().regexpExtractSql(e) }
            reg(RegexpExtractAll::class) { e -> hg().regexpExtractSql(e) }
            reg(RegexpReplace::class) { e -> hg().regexpReplaceSql(e as RegexpReplace) }
            reg(RegexpLike::class) { e -> binary(e as Binary, "RLIKE") }
            reg(RegexpSplit::class) { e -> hg().renameFuncSql("SPLIT", e) }
            reg(Right::class) { e -> hg().rightToSubstringSql(e as Right) }
            reg(SchemaCommentProperty::class) { e -> nakedProperty(e as Property) }
            reg(ArrayUniqueAgg::class) { e -> hg().renameFuncSql("COLLECT_SET", e) }
            reg(Split::class) { e ->
                func("SPLIT", e.thisArg, func("CONCAT", "'\\\\Q'", e.args["expression"], "'\\\\E'"))
            }
            reg(StrPosition::class) { e -> hg().strpositionLocateSql(e as StrPosition) }
            reg(StrToDate::class) { e -> hg().strToTemporalSql(e, "DATE") }
            reg(StrToTime::class) { e -> hg().strToTemporalSql(e, "TIMESTAMP") }
            reg(StrToUnix::class) { e -> hg().strToUnixSql(e as StrToUnix) }
            reg(StructExtract::class) { e -> hg().structExtractSql(e as StructExtract) }
            reg(StarMap::class) { e -> hg().renameFuncSql("MAP", e) }
            reg(TimeStrToDate::class) { e -> hg().renameFuncSql("TO_DATE", e) }
            reg(TimeStrToTime::class) { e -> hg().timestrtotimeSql(e as TimeStrToTime) }
            reg(TimeStrToUnix::class) { e -> hg().renameFuncSql("UNIX_TIMESTAMP", e) }
            reg(TimestampTrunc::class) { e -> func("TRUNC", e.thisArg, hiveUnitToStr(e)) }
            reg(TimeToUnix::class) { e -> hg().renameFuncSql("UNIX_TIMESTAMP", e) }
            reg(ToBase64::class) { e -> hg().renameFuncSql("BASE64", e) }
            reg(TsOrDiToDi::class) { e ->
                "CAST(SUBSTR(REPLACE(CAST(${sql(e, "this")} AS STRING), '-', ''), 1, 8) AS INT)"
            }
            reg(TsOrDsAdd::class) { e -> hg().addDateSql(e) }
            reg(TsOrDsDiff::class) { e -> hg().dateDiffSql(e) }
            reg(TsOrDsToDate::class) { e -> hg().toDateSql(e as TsOrDsToDate) }
            reg(TryCast::class) { e -> hg().noTrycastSql(e as TryCast) }
            reg(Trim::class) { e -> trimSql(e as Trim) }
            reg(Unicode::class) { e -> hg().renameFuncSql("ASCII", e) }
            reg(UnixToStr::class) { e -> func("FROM_UNIXTIME", e.thisArg, hg().hiveTimeFormat(e)) }
            reg(UnixToTime::class) { e -> hg().unixToTimeSql(e as UnixToTime) }
            reg(UnixToTimeStr::class) { e -> hg().renameFuncSql("FROM_UNIXTIME", e) }
            reg(Unnest::class) { e -> hg().renameFuncSql("EXPLODE", e) }
            reg(PartitionedByProperty::class) { e -> "PARTITIONED BY ${sql(e, "this")}" }
            reg(NumberToStr::class) { e -> hg().renameFuncSql("FORMAT_NUMBER", e) }
            reg(National::class) { e -> nationalSql(e as National, prefix = "") }
            reg(ClusteredColumnConstraint::class) { e ->
                "(${expressions(e, key = "this", indent = false)})"
            }
            reg(NonClusteredColumnConstraint::class) { e ->
                "(${expressions(e, key = "this", indent = false)})"
            }
            reg(NotForReplicationColumnConstraint::class) { _ -> "" }
            reg(OnProperty::class) { _ -> "" }
            reg(PartitionedByBucket::class) { e -> func("BUCKET", e.args["expression"], e.thisArg) }
            reg(PartitionByTruncate::class) { e -> func("TRUNCATE", e.args["expression"], e.thisArg) }
            reg(PrimaryKeyColumnConstraint::class) { _ -> "PRIMARY KEY" }
            reg(WeekOfYear::class) { e -> hg().renameFuncSql("WEEKOFYEAR", e) }
            reg(DayOfMonth::class) { e -> hg().renameFuncSql("DAYOFMONTH", e) }
            reg(DayOfWeek::class) { e -> hg().renameFuncSql("DAYOFWEEK", e) }
            reg(Levenshtein::class) { e -> hg().renameFuncSql("LEVENSHTEIN", e) }
            reg(SerdeProperties::class) { e -> hg().serdepropertiesSql(e as SerdeProperties) }
            // sqlglot: Generator.inputoutputformat_sql (base method; not yet in Kotlin base)
            reg(InputOutputFormat::class) { e ->
                val inFmt = sql(e, "input_format").let { if (it.isNotEmpty()) "INPUTFORMAT $it" else "" }
                val outFmt = sql(e, "output_format").let { if (it.isNotEmpty()) "OUTPUTFORMAT $it" else "" }
                listOf(inFmt, outFmt).filter { it.isNotEmpty() }.joinToString(sep())
            }
            // sqlglot: HiveGenerator.rowformatserdeproperty_sql
            reg(RowFormatSerdeProperty::class) { e ->
                val serdePropsRaw = sql(e, "serde_properties")
                val serdeProps = if (serdePropsRaw.isNotEmpty()) " $serdePropsRaw" else ""
                "ROW FORMAT SERDE ${sql(e, "this")}$serdeProps"
            }
            reg(TimeToStr::class) { e -> hg().timetostrSql(e as TimeToStr) }
            reg(UsingProperty::class) { e -> hg().usingpropertySql(e as UsingProperty) }
            reg(FileFormatProperty::class) { e -> hg().fileformatpropertySql(e as FileFormatProperty) }
            reg(Parameter::class) { e -> hg().parameterSql(e as Parameter) }
            reg(Trunc::class) { e -> hg().truncSql(e as Trunc) }
        }
    }
}
