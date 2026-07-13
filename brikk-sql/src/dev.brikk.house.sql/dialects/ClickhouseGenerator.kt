package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Any as AnyNode
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.parser.ClickhouseTokenizerTables
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

// sqlglot: dialect.unit_to_var
private fun unitToVar(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression

    if (unit is Var || unit is Placeholder || unit is WeekStart || unit is Column) return unit

    val value = unit?.name ?: default
    return if (value.isNotEmpty()) Var(args("this" to value)) else null
}

// sqlglot: generators.clickhouse._lower_func
private fun lowerFunc(sql: String): String {
    val index = sql.indexOf("(")
    if (index < 0) return sql
    return sql.take(index).lowercase() + sql.substring(index)
}

/**
 * sqlglot: generators.clickhouse._timestrtotime_sql's use of
 * datetime.datetime.fromisoformat(...).replace(tzinfo=None).isoformat(sep=" ") —
 * parses an ISO timestamp (fraction pre-padded to 6 digits by the caller), drops any
 * UTC offset and re-renders it. Returns null when the string doesn't look ISO-ish,
 * in which case the caller keeps the original literal (Python would raise instead;
 * corpus inputs are always parseable).
 */
private fun isoformatWithoutTimezone(tsString: String): String? {
    val m = Regex(
        "^(\\d{4}-\\d{2}-\\d{2})" +
            "(?:[T ](\\d{2}:\\d{2})(?::(\\d{2}))?(?:\\.(\\d{1,6}))?)?" +
            "(?:[+-]\\d{2}:\\d{2}(?::\\d{2})?|Z)?$"
    ).find(tsString) ?: return null

    val (date, hm, sec, frac) = m.destructured
    val time = if (hm.isEmpty()) "00:00:00" else "$hm:${sec.ifEmpty { "00" }}"
    val fraction = if (frac.isNotEmpty() && frac.toLong() != 0L) ".${frac.padEnd(6, '0')}" else ""
    return "$date $time$fraction"
}

/**
 * Port of sqlglot's ClickHouseGenerator (reference/sqlglot/sqlglot/generators/clickhouse.py).
 * TRANSFORMS entries live in [TRANSFORMS] (a dispatch-map overlay handed to the base
 * constructor and accepting a further overlay for subclasses); flag overrides are
 * open-val overrides.
 *
 * NOT PORTED: LAST_DAY_SUPPORTS_DATE_PART / CAN_IMPLEMENT_ARRAY_ANY /
 * SUPPORTS_DECODE_CASE (the base generator paths that read these flags — last_day,
 * array_any and decode_case rewrites — have no Kotlin equivalents yet).
 */
// sqlglot: generators.clickhouse.ClickHouseGenerator
open class ClickhouseGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    // sqlglot: ClickHouse.NORMALIZE_FUNCTIONS = False
    normalizeFunctions: kotlin.Any = false,
    tokenizerConfig: TokenizerConfig = ClickhouseTokenizerTables.CONFIG,
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

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.CLICKHOUSE

    // ------------------------------------------------------------------
    // Flags (sqlglot: ClickHouseGenerator class attributes)
    // ------------------------------------------------------------------

    override val selectKinds: Set<String> get() = emptySet()
    override val trySupported: Boolean get() = false
    override val supportsUescape: Boolean get() = false
    override val queryHints: Boolean get() = false
    override val structDelimiter: Pair<String, String> get() = "(" to ")"
    override val nvl2Supported: Boolean get() = false
    override val tablesampleRequiresParens: Boolean get() = false
    override val tablesampleSizeIsRows: Boolean get() = false
    override val tablesampleKeywords: String get() = "SAMPLE"
    override val supportsToNumber: Boolean get() = false
    override val joinHints: Boolean get() = false
    override val tableHints: Boolean get() = false
    override val groupingsSep: String get() = ""
    override val setOpModifiers: Boolean get() = false
    override val arraySizeName: String get() = "LENGTH"
    override val wrapDerivedValues: Boolean get() = false

    // sqlglot: ClickHouse dialect-level flags read by the generator
    override val dialectNullOrdering: String get() = "nulls_are_last"
    override val dialectIndexOffset: Int get() = 1
    override val dialectSafeDivision: Boolean get() = true
    override val dialectPreserveOriginalNames: Boolean get() = true
    override val hexStringIsIntegerType: Boolean get() = true
    override val dialectIdentifiersCanStartWithDigit: Boolean get() = true
    override val inverseCreatableKindMapping: Map<String, String>
        get() = mapOf("SCHEMA" to "DATABASE")

    // sqlglot: ClickHouse HEX_STRINGS = [("0x", ""), ("0X", "")] / BIT_STRINGS (tokenizer)
    override val hexStart: String? get() = "0x"
    override val hexEnd: String? get() = ""

    // sqlglot: ClickHouse STRING_ESCAPES include "\\" -> ESCAPED_SEQUENCES active
    override val dialectStringsSupportEscapedSequences: Boolean get() = true
    override val escapedSequences: Map<String, String> get() = ESCAPED_SEQUENCES

    // sqlglot: ClickHouseGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: ClickHouseGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

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
    // Methods (sqlglot: ClickHouseGenerator methods + module-level helpers)
    // ------------------------------------------------------------------

    // sqlglot: generators.clickhouse._unix_to_time_sql
    open fun unixToTimeSql(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        val timestamp = expression.thisArg

        fun scaleIs(value: String): Boolean =
            scale is Literal && !scale.isString && scale.name == value

        fun castBigint(e: kotlin.Any?): Expression =
            Cast(args("this" to e, "to" to DataType(args("this" to DType.BIGINT))))

        // sqlglot: exp.UnixToTime.SECONDS/MILLIS/MICROS/NANOS = Literal.number(0/3/6/9)
        if (scale == null || scaleIs("0")) {
            return func("fromUnixTimestamp", castBigint(timestamp))
        }
        if (scaleIs("3")) return func("fromUnixTimestamp64Milli", castBigint(timestamp))
        if (scaleIs("6")) return func("fromUnixTimestamp64Micro", castBigint(timestamp))
        if (scaleIs("9")) return func("fromUnixTimestamp64Nano", castBigint(timestamp))

        return func(
            "fromUnixTimestamp",
            castBigint(
                Div(
                    args(
                        "this" to timestamp,
                        "expression" to Pow(
                            args("this" to Literal.number("10"), "expression" to scale)
                        ),
                    )
                )
            ),
        )
    }

    // sqlglot: generators.clickhouse._quantile_sql
    open fun quantileSql(expression: Quantile): String {
        val quantile = expression.args["quantile"]
        val argsSql = "(${sql(expression, "this")})"

        val funcSql = if (quantile is ArrayNode) {
            func("quantiles", *quantile.expressionsArg.toTypedArray())
        } else {
            func("quantile", quantile)
        }

        return funcSql + argsSql
    }

    // sqlglot: generators.clickhouse._datetime_delta_sql
    open fun datetimeDeltaSql(name: String, expression: Expression): String {
        if (expression.args["unit"] == null) {
            return renameFuncSql(name, expression)
        }

        return func(
            name,
            unitToVar(expression),
            expression.args["expression"],
            expression.thisArg,
            expression.args["zone"],
        )
    }

    // sqlglot: generators.clickhouse._timestrtotime_sql
    open fun timestrtotimeSql(expression: TimeStrToTime): String {
        var ts: kotlin.Any? = expression.thisArg

        val tz = expression.args["zone"]
        if (tz != null && ts is Literal) {
            // Clickhouse will not accept timestamps that include a UTC offset, so we
            // must remove them; the fractional seconds are padded to 6 digits first.
            var tsString = ts.name.trim()

            val tsParts = tsString.split(".")
            if (tsParts.size == 2) {
                val offsetSep = if ("+" in tsParts[1]) "+" else "-"
                val tsFracParts = tsParts[1].split(offsetSep)
                val numFracParts = tsFracParts.size

                val frac = tsFracParts[0].padEnd(6, '0')
                tsString = buildString {
                    append(tsParts[0])
                    append(".")
                    append(frac)
                    if (numFracParts > 1) {
                        append(offsetSep)
                        append(tsFracParts[1])
                    }
                }
            }

            val tsWithoutTz = isoformatWithoutTimezone(tsString)
            if (tsWithoutTz != null) ts = Literal.string(tsWithoutTz)
        }

        // Non-nullable DateTime64 with microsecond precision
        val expressions = if (tz != null) listOf(DataTypeParam(args("this" to tz))) else emptyList()
        val datatype = DataType(
            args(
                "this" to DType.DATETIME64,
                "expressions" to (
                    listOf(DataTypeParam(args("this" to Literal.number("6")))) + expressions
                    ),
                "nullable" to false,
            )
        )

        return sql(Cast(args("this" to ts, "to" to datatype)))
    }

    // sqlglot: generators.clickhouse._map_sql
    open fun mapSql(expression: Expression): String {
        // sqlglot: `expression.parent.arg_key == "settings"`
        val parent = expression.parent
        if (!(parent != null && parent.argKey == "settings")) {
            return lowerFunc(varMapSql(expression))
        }

        val keys = expression.args["keys"]
        val values = expression.args["values"]

        if (keys !is ArrayNode || values !is ArrayNode) {
            unsupported("Cannot convert array columns into map.")
            return ""
        }

        val argsSql = mutableListOf<String>()
        for ((key, value) in keys.expressionsArg.zip(values.expressionsArg)) {
            argsSql.add("${sql(key)}: ${sql(value)}")
        }

        val csvArgs = argsSql.joinToString(", ")

        return "{$csvArgs}"
    }

    // sqlglot: dialect.var_map_sql
    open fun varMapSql(expression: Expression, mapFuncName: String = "MAP"): String {
        val keys = expression.args["keys"]
        val values = expression.args["values"]

        if (keys !is ArrayNode || values !is ArrayNode) {
            unsupported("Cannot convert array columns into map.")
            return func(mapFuncName, keys, values)
        }

        val argsSql = mutableListOf<String>()
        for ((key, value) in keys.expressionsArg.zip(values.expressionsArg)) {
            argsSql.add(sql(key))
            argsSql.add(sql(value))
        }

        return func(mapFuncName, *argsSql.toTypedArray())
    }

    // sqlglot: generators.clickhouse._json_cast_sql
    open fun jsonCastSql(expression: JSONCast): String {
        val this_ = sql(expression, "this")
        val to = expression.args["to"] as? Expression
        var toSql = sql(to)

        if (to != null && to.expressionsArg.isNotEmpty()) {
            // sqlglot: exp.to_identifier(to_sql)
            toSql = sql(
                Identifier(
                    args("this" to toSql, "quoted" to !SAFE_IDENTIFIER_RE.matches(toSql))
                )
            )
        }

        return "$this_.:$toSql"
    }

    // sqlglot: ClickHouseGenerator.groupconcat_sql
    open fun groupconcatSql(expression: GroupConcat): String {
        var this_ = expression.thisArg
        val separator = expression.args["separator"]

        if (this_ is Limit && this_.thisArg != null) {
            val limit = this_
            this_ = (limit.thisArg as Expression).pop()
            return sql(
                CombinedParameterizedAgg::class.let {
                    ParameterizedAgg(
                        args(
                            "this" to "groupConcat",
                            "params" to listOf(this_),
                            "expressions" to listOf(separator, limit.args["expression"]),
                        )
                    )
                }
            )
        }

        if (separator != null) {
            return sql(
                ParameterizedAgg(
                    args(
                        "this" to "groupConcat",
                        "params" to listOf(this_),
                        "expressions" to listOf(separator),
                    )
                )
            )
        }

        return func("groupConcat", this_)
    }

    // sqlglot: ClickHouseGenerator.offset_sql — OFFSET ... FETCH requires "ROWS"
    override fun offsetSql(expression: Offset): String {
        var offset = super.offsetSql(expression)

        val parent = expression.parent
        if (parent is Select && parent.args["limit"] is Fetch) {
            offset = "$offset ROWS"
        }

        return offset
    }

    // sqlglot: ClickHouseGenerator.strtodate_sql
    open fun strtodateSql(expression: StrToDate): String {
        val strtodateSql = functionFallbackSql(expression)

        if (expression.parent !is Cast) {
            // StrToDate returns DATEs in other dialects (eg. postgres), so
            // this branch aims to improve the transpilation to clickhouse
            return castSql(
                Cast(
                    args(
                        "this" to expression.copy(),
                        "to" to DataType(args("this" to DType.DATE)),
                    )
                )
            )
        }

        return strtodateSql
    }

    // sqlglot: ClickHouseGenerator.cast_sql
    override fun castSql(expression: Cast, safePrefix: String?): String {
        val this_ = expression.thisArg

        if (this_ is StrToDate &&
            expression.args["to"] == DataType(args("this" to DType.DATETIME))
        ) {
            return sql(this_)
        }

        return super.castSql(expression, safePrefix = safePrefix)
    }

    // sqlglot: ClickHouseGenerator.trycast_sql — casting into Nullable(T) behaves
    // similarly to TRY_CAST(x AS T)
    override fun trycastSql(expression: TryCast): String {
        val dtype = expression.args["to"] as? DataType
        if (dtype != null &&
            !dtype.isType(*NON_NULLABLE_TYPES.toTypedArray(), checkNullable = true)
        ) {
            dtype.set("nullable", true)
        }

        return super.castSql(expression, safePrefix = null)
    }

    // sqlglot: ClickHouseGenerator._jsonpathsubscript_sql
    override fun jsonpathsubscriptSql(expression: JSONPathSubscript): String {
        val this_ = jsonPathPart(expression.thisArg)
        val asInt = this_.toLongOrNull()
        return if (asInt != null) (asInt + 1).toString() else this_
    }

    // sqlglot: ClickHouseGenerator.likeproperty_sql
    override fun likepropertySql(expression: LikeProperty): String =
        "AS ${sql(expression, "this")}"

    // sqlglot: ClickHouseGenerator._any_to_has
    protected fun anyToHas(
        expression: Binary,
        default: (Expression) -> String,
        prefix: String = "",
    ): String {
        val left = expression.thisArg
        val right = expression.args["expression"]

        val arr: AnyNode?
        val this_: kotlin.Any?
        if (left is AnyNode) {
            arr = left
            this_ = right
        } else if (right is AnyNode) {
            arr = right
            this_ = left
        } else {
            return default(expression)
        }

        // sqlglot: arr.this.unnest()
        var arrThis = arr.thisArg as? Expression
        while (arrThis is Paren) arrThis = arrThis.thisArg as? Expression

        return prefix + func("has", arrThis, this_)
    }

    // sqlglot: ClickHouseGenerator.regexpilike_sql — manually add a flag to make the
    // search case-insensitive
    open fun regexpilikeSql(expression: RegexpILike): String {
        val regex = func("CONCAT", "'(?i)'", expression.args["expression"])
        return func("match", expression.thisArg, regex)
    }

    // sqlglot: ClickHouseGenerator.datatype_sql
    override fun datatypeSql(expression: DataType): String {
        // String is the standard ClickHouse type, every other variant is just an alias.
        // https://clickhouse.com/docs/en/sql-reference/data-types/string
        var dtype = if (expression.thisArg in STRING_TYPE_MAPPING) {
            "String"
        } else {
            super.datatypeSql(expression)
        }

        // Wrap in `Nullable(...)` when nullable (or unmarked, unless it's a Map key or
        // a composite type)
        val parent = expression.parent
        val nullable = expression.args["nullable"]
        if (nullable == true || (
                nullable == null &&
                    !(
                        parent is DataType &&
                            parent.isType(DType.MAP, checkNullable = true) &&
                            (expression.index == null || expression.index == 0)
                        ) &&
                    !expression.isType(*NON_NULLABLE_TYPES.toTypedArray(), checkNullable = true)
                )
        ) {
            dtype = "Nullable($dtype)"
        }

        return dtype
    }

    // sqlglot: ClickHouseGenerator.cte_sql
    override fun cteSql(expression: CTE): String {
        if (expression.args["scalar"] == true) {
            val this_ = sql(expression, "this")
            val alias = sql(expression, "alias")
            return "$this_ AS $alias"
        }

        return super.cteSql(expression)
    }

    // sqlglot: ClickHouseGenerator.after_limit_modifiers
    override fun afterLimitModifiers(expression: Expression): List<String> {
        val settingsArg = expression.args["settings"]
        val hasSettings = if (settingsArg is List<*>) settingsArg.isNotEmpty() else settingsArg != null
        return super.afterLimitModifiers(expression) + listOf(
            if (hasSettings) {
                seg("SETTINGS ") + expressions(expression, key = "settings", flat = true)
            } else {
                ""
            },
            if (expression.args["format"] != null) {
                seg("FORMAT ") + sql(expression, "format")
            } else {
                ""
            },
        )
    }

    // sqlglot: ClickHouseGenerator.placeholder_sql
    override fun placeholderSql(expression: Placeholder): String =
        "{${expression.name}: ${sql(expression, "kind")}}"

    // sqlglot: ClickHouseGenerator.oncluster_sql
    override fun onclusterSql(expression: OnCluster): String =
        "ON CLUSTER ${sql(expression, "this")}"

    // sqlglot: ClickHouseGenerator.createable_sql
    override fun createableSql(
        expression: Create,
        locations: Map<GeneratorTables.PropLocation, List<Expression>>,
    ): String {
        val postName = locations[GeneratorTables.PropLocation.POST_NAME]
        if (expression.text("kind") in ON_CLUSTER_TARGETS && !postName.isNullOrEmpty()) {
            val thisName = sql(
                if (expression.thisArg is Schema) expression.thisArg else expression,
                "this",
            )
            val thisProperties = postName.joinToString(" ") { sql(it) }
            var thisSchema = schemaColumnsSql(expression.thisArg as Expression)
            if (thisSchema.isNotEmpty()) thisSchema = "${sep()}$thisSchema"

            return "$thisName${sep()}$thisProperties$thisSchema"
        }

        return super.createableSql(expression, locations)
    }

    // sqlglot: ClickHouseGenerator.create_sql — the comment property comes last in
    // CTAS statements, i.e. after the query
    override fun createSql(expression: Create): String {
        val query = expression.args["expression"]
        var commentProp: Expression? = null
        if (query is Expression && query is Query) {
            commentProp = expression.find(SchemaCommentProperty::class)
            if (commentProp != null) {
                commentProp.pop()
                query.replace(Paren(args("this" to query.copy())))
            }
        }

        val createSql = super.createSql(expression)

        var commentSql = sql(commentProp)
        if (commentSql.isNotEmpty()) commentSql = " $commentSql"

        return "$createSql$commentSql"
    }

    // sqlglot: ClickHouseGenerator.prewhere_sql
    override fun prewhereSql(expression: PreWhere): String {
        val this_ = indent(sql(expression, "this"))
        return "${seg("PREWHERE")}${sep()}$this_"
    }

    // sqlglot: ClickHouseGenerator.indexcolumnconstraint_sql
    override fun indexcolumnconstraintSql(expression: IndexColumnConstraint): String {
        var this_ = sql(expression, "this")
        if (this_.isNotEmpty()) this_ = " $this_"
        var expr = sql(expression, "expression")
        if (expr.isNotEmpty()) expr = " $expr"
        var indexType = sql(expression, "index_type")
        if (indexType.isNotEmpty()) indexType = " TYPE $indexType"
        var granularity = sql(expression, "granularity")
        if (granularity.isNotEmpty()) granularity = " GRANULARITY $granularity"

        return "INDEX$this_$expr$indexType$granularity"
    }

    // sqlglot: ClickHouseGenerator.partition_sql
    override fun partitionSql(expression: Partition): String =
        "PARTITION ${expressions(expression, flat = true)}"

    // sqlglot: ClickHouseGenerator.partitionid_sql
    open fun partitionidSql(expression: PartitionId): String =
        "ID ${sql(expression.thisArg)}"

    // sqlglot: ClickHouseGenerator.replacepartition_sql
    open fun replacepartitionSql(expression: ReplacePartition): String =
        "REPLACE ${sql(expression.args["expression"])} FROM ${sql(expression, "source")}"

    // sqlglot: ClickHouseGenerator.projectiondef_sql
    open fun projectiondefSql(expression: ProjectionDef): String =
        "PROJECTION ${sql(expression.thisArg)} ${wrap(expression.args["expression"])}"

    // sqlglot: ClickHouseGenerator.nestedjsonselect_sql
    open fun nestedjsonselectSql(expression: NestedJSONSelect): String =
        "${sql(expression, "this")}.^${sql(expression, "expression")}"

    // sqlglot: ClickHouseGenerator.is_sql — value IS NOT NULL -> NOT (value IS NULL)
    override fun isSql(expression: Is): String {
        var isSql = super.isSql(expression)

        if (expression.parent is Not) {
            isSql = wrap(isSql)
        }

        return isSql
    }

    // sqlglot: ClickHouseGenerator.in_sql
    override fun inSql(expression: In): String {
        var inSql = super.inSql(expression)

        if (expression.parent is Not && expression.args["is_global"] == true) {
            inSql = inSql.replaceFirst("GLOBAL IN", "GLOBAL NOT IN")
        }

        return inSql
    }

    // sqlglot: ClickHouseGenerator.not_sql
    override fun notSql(expression: Not): String {
        val this_ = expression.thisArg
        if (this_ is In) {
            if (this_.args["is_global"] == true) {
                // let `GLOBAL IN` child interpose `NOT`
                return sql(expression, "this")
            }

            expression.set("this", Paren(args("this" to this_)))
        }

        return super.notSql(expression)
    }

    // sqlglot: ClickHouseGenerator.values_sql — VALUES with tuples of expressions must
    // be treated as a table since Clickhouse auto-aliases it as such
    override fun valuesSql(expression: Values, valuesAsTableParam: Boolean): String {
        val alias = expression.args["alias"] as? TableAlias

        val valuesAsTable: Boolean
        if (alias != null &&
            !(alias.args["columns"] as? List<*>).isNullOrEmpty() &&
            expression.expressionsArg.isNotEmpty()
        ) {
            val values = (expression.expressionsArg[0] as Expression).expressionsArg
            valuesAsTable = values.any { it is Tuple }
        } else {
            valuesAsTable = true
        }

        return super.valuesSql(expression, valuesAsTableParam = valuesAsTable)
    }

    // sqlglot: ClickHouseGenerator.timestamptrunc_sql — dialect.version defaults to
    // the latest, so the pre-23.12 lowercase rewrite is never taken
    open fun timestamptruncSql(expression: Expression): String {
        val unit = unitToStr(expression)
        return func("dateTrunc", unit, expression.thisArg, expression.args["zone"])
    }

    // BUGS-clickhouse-generator-mappings rows 3 (P1) + 12 (P2): the Log node.
    // The base generator rendered it verbatim as LOG(...), which is wrong for ClickHouse:
    //   - single-arg log(x) [Log(this=x), no base] means log-base-10 in the source
    //     (DuckDB LOG_DEFAULTS_TO_LN=false), but ClickHouse LOG(x) is the NATURAL log
    //     (silent base change);
    //   - log10(x)/log2(x) parse to Log(this=10|2, expression=x) and were emitted as
    //     LOG(10, x) / LOG(2, x), which ClickHouse REJECTS (it has no 2-arg log).
    // ClickHouse exposes dedicated log10/log2; a bare natural log is `log`. So:
    //   - no base                -> log10(value)         (source single-arg log = base 10)
    //   - base literal 10        -> log10(value)
    //   - base literal 2         -> log2(value)
    //   - arbitrary base b       -> log(value) / log(b)  (change-of-base via natural log,
    //                                                      exactly log_b(value))
    open fun clickhouseLogSql(expression: Log): String {
        // Parser (build_logarithm, LOG_BASE_FIRST=true): LOG(base, value) -> Log(this=base,
        // expression=value); single-arg LOG(x) -> Log(this=x) with no expression.
        val value = expression.args["expression"] as? Expression
        if (value == null) {
            // Single-arg: `this` is the value; source semantics are base-10.
            return func("log10", expression.thisArg)
        }
        // Two-arg LOG(base, value): `this` is the base.
        val base = expression.thisArg as? Expression
        return when {
            base is Literal && !base.isString && base.name == "10" -> func("log10", value)
            base is Literal && !base.isString && base.name == "2" -> func("log2", value)
            // Change-of-base via natural log = log_b(value); parenthesized so it stays
            // atomic inside a larger expression.
            else -> "(${func("log", value)} / ${func("log", base)})"
        }
    }

    // BUGS-clickhouse-generator-mappings row 4 (P1): RegexpReplace. ClickHouse
    // REGEXP_REPLACE aliases replaceRegexpAll (replaces EVERY match). DuckDB
    // regexp_replace(s, p, r) replaces only the FIRST match unless the 'g' flag is
    // present (the parser records single_replace=true + the flag in `modifiers`); Trino
    // regexp_replace always replaces all (single_replace unset). Emit the matching
    // ClickHouse primitive: replaceRegexpOne for the DuckDB first-only form, else
    // replaceRegexpAll. Any regex FLAG other than 'g' (case-insensitive 'i', ...) has no
    // ClickHouse argument form, so flag it rather than silently drop it.
    // BUGS-clickhouse-generator-mappings row 6 (P1): DuckDB/Trino millisecond(t) reaches
    // us as an unresolved Anonymous call (no canonical node) and used to pass through
    // verbatim, but ClickHouse has NO `millisecond` function — its toMillisecond returns
    // only the SUB-SECOND component, whereas the source millisecond is
    // seconds-within-minute*1000 + ms. Emit the compound. Live-differential-verified vs
    // ClickHouse 26.5.1.1 + DuckDB 1.5.4 (30123, 0, 5789, 56001 all match). `millisecond`
    // is not a ClickHouse function name, so this never fires on ClickHouse->ClickHouse.
    override fun anonymousSql(expression: Anonymous): String {
        if (expression.name.equals("millisecond", ignoreCase = true)) {
            val args = expression.expressionsArg
            if (args.size == 1) {
                val t = args[0]
                return "(${func("toSecond", t)} * 1000 + ${func("toMillisecond", t)})"
            }
        }
        return super.anonymousSql(expression)
    }

    open fun clickhouseRegexpReplaceSql(expression: RegexpReplace): String {
        val single = expression.args["single_replace"] == true
        val modifiers = expression.args["modifiers"]
        val modStr = if (modifiers is Literal && !modifiers.isString) "" else (modifiers as? Literal)?.name ?: ""
        val global = modStr.contains("g")
        for (flag in modStr) {
            if (flag != 'g') {
                unsupported(
                    "regexp_replace flag '$flag' has no ClickHouse replaceRegexp* equivalent"
                )
            }
        }
        val fn = if (single && !global) "replaceRegexpOne" else "replaceRegexpAll"
        return func(fn, expression.thisArg, expression.args["expression"], expression.args["replacement"])
    }

    companion object {

        // sqlglot: ClickHouse.ESCAPED_SEQUENCES (MySQL's map + the \0 unescape)
        val ESCAPED_SEQUENCES: Map<String, String> =
            MysqlGenerator.ESCAPED_SEQUENCES + mapOf("\u0000" to "\\0")

        // sqlglot: ClickHouseGenerator.STRING_TYPE_MAPPING (keys only; every entry
        // renders as "String")
        val STRING_TYPE_MAPPING: Set<DType> = setOf(
            DType.BLOB, DType.CHAR, DType.LONGBLOB, DType.LONGTEXT, DType.MEDIUMBLOB,
            DType.MEDIUMTEXT, DType.TINYBLOB, DType.TINYTEXT, DType.TEXT,
            DType.VARBINARY, DType.VARCHAR,
        )

        // sqlglot: ClickHouseGenerator.NON_NULLABLE_TYPES
        // https://clickhouse.com/docs/en/sql-reference/data-types/nullable
        val NON_NULLABLE_TYPES: Set<DType> = setOf(
            DType.ARRAY, DType.MAP, DType.STRUCT, DType.POINT, DType.RING,
            DType.LINESTRING, DType.MULTILINESTRING, DType.POLYGON, DType.MULTIPOLYGON,
        )

        // sqlglot: ClickHouseGenerator.ON_CLUSTER_TARGETS
        val ON_CLUSTER_TARGETS: Set<String> = setOf(
            "SCHEMA", // Transpiled CREATE SCHEMA may have OnCluster property set
            "DATABASE", "TABLE", "VIEW", "DICTIONARY", "INDEX", "FUNCTION",
            "NAMED COLLECTION",
        )

        // sqlglot: generator.Generator.TYPE_MAPPING (the base entries ClickHouse inherits)
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

        // sqlglot: ClickHouseGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = BASE_TYPE_MAPPING + mapOf(
            DType.BLOB to "String",
            DType.CHAR to "String",
            DType.LONGBLOB to "String",
            DType.LONGTEXT to "String",
            DType.MEDIUMBLOB to "String",
            DType.MEDIUMTEXT to "String",
            DType.TINYBLOB to "String",
            DType.TINYTEXT to "String",
            DType.TEXT to "String",
            DType.VARBINARY to "String",
            DType.VARCHAR to "String",
            DType.ARRAY to "Array",
            DType.BOOLEAN to "Bool",
            DType.BIGINT to "Int64",
            DType.DATE32 to "Date32",
            DType.DATETIME to "DateTime",
            DType.DATETIME2 to "DateTime",
            DType.SMALLDATETIME to "DateTime",
            DType.DATETIME64 to "DateTime64",
            DType.DECIMAL to "Decimal",
            DType.DECIMAL32 to "Decimal32",
            DType.DECIMAL64 to "Decimal64",
            DType.DECIMAL128 to "Decimal128",
            DType.DECIMAL256 to "Decimal256",
            DType.TIMESTAMP to "DateTime",
            DType.TIMESTAMPNTZ to "DateTime",
            DType.TIMESTAMPTZ to "DateTime",
            DType.DOUBLE to "Float64",
            DType.ENUM to "Enum",
            DType.ENUM8 to "Enum8",
            DType.ENUM16 to "Enum16",
            DType.FIXEDSTRING to "FixedString",
            DType.FLOAT to "Float32",
            DType.INT to "Int32",
            DType.MEDIUMINT to "Int32",
            DType.INT128 to "Int128",
            DType.INT256 to "Int256",
            DType.LOWCARDINALITY to "LowCardinality",
            DType.MAP to "Map",
            DType.NESTED to "Nested",
            DType.NOTHING to "Nothing",
            DType.SMALLINT to "Int16",
            DType.STRUCT to "Tuple",
            DType.TINYINT to "Int8",
            DType.UBIGINT to "UInt64",
            DType.UINT to "UInt32",
            DType.UINT128 to "UInt128",
            DType.UINT256 to "UInt256",
            DType.USMALLINT to "UInt16",
            DType.UTINYINT to "UInt8",
            DType.IPV4 to "IPv4",
            DType.IPV6 to "IPv6",
            DType.POINT to "Point",
            DType.RING to "Ring",
            DType.LINESTRING to "LineString",
            DType.MULTILINESTRING to "MultiLineString",
            DType.POLYGON to "Polygon",
            DType.MULTIPOLYGON to "MultiPolygon",
            DType.AGGREGATEFUNCTION to "AggregateFunction",
            DType.SIMPLEAGGREGATEFUNCTION to "SimpleAggregateFunction",
            DType.DYNAMIC to "Dynamic",
        )

        // sqlglot: ClickHouseGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            GeneratorTables.PROPERTIES_LOCATION + mapOf(
                DefinerProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                OnCluster::class to GeneratorTables.PropLocation.POST_NAME,
                PartitionedByProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                ToTableProperty::class to GeneratorTables.PropLocation.POST_NAME,
                UuidProperty::class to GeneratorTables.PropLocation.POST_NAME,
                VolatileProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
            )

        // sqlglot: ClickHouseGenerator.TRANSFORMS (dispatch-map overlay over the base)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.ch(): ClickhouseGenerator = this as ClickhouseGenerator

            reg(AnyValue::class) { e -> ch().renameFuncSql("any", e) }
            reg(ApproxDistinct::class) { e -> ch().renameFuncSql("uniq", e) }
            // BUGS-clickhouse-generator-mappings row 1 (P1): ClickHouse LOWER/UPPER are
            // ASCII-only (non-ASCII passes through unchanged); the source LOWER/UPPER fold
            // full Unicode. lowerUTF8/upperUTF8 fold multibyte codepoints (a residual
            // İ/ß full-case-folding edge divergence remains — kept as a hazard).
            reg(Lower::class) { e -> func("lowerUTF8", e.thisArg) }
            reg(Upper::class) { e -> func("upperUTF8", e.thisArg) }
            // BUGS-clickhouse-generator-mappings rows 3/12 (P1/P2): natural-log collision
            // + invalid 2-arg LOG. See clickhouseLogSql.
            reg(Log::class) { e -> ch().clickhouseLogSql(e as Log) }
            // BUGS-clickhouse-generator-mappings row 4 (P1): first-vs-all replace. See
            // clickhouseRegexpReplaceSql.
            reg(RegexpReplace::class) { e -> ch().clickhouseRegexpReplaceSql(e as RegexpReplace) }
            // BUGS-clickhouse-generator-mappings row 13 (P3): DuckDB xor is BITWISE; the
            // base emitted `a ^ b`, which ClickHouse has no operator for. bitXor(a, b) is
            // ClickHouse's bitwise xor (result-identical to DuckDB xor).
            reg(BitwiseXor::class) { e ->
                func("bitXor", (e as Binary).thisArg, e.args["expression"])
            }
            // BUGS-clickhouse-generator-mappings row 5 (P1): DuckDB week is ISO-8601;
            // ClickHouse WEEK/toWeek default mode 0 is Sunday-based. toISOWeek matches.
            reg(Week::class) { e -> func("toISOWeek", e.thisArg) }
            // BUGS-clickhouse-generator-mappings row 10 (P2): Trino week parses to
            // WeekOfYear; the emitted WEEK_OF_YEAR does not exist in ClickHouse. Trino week
            // is ISO -> toISOWeek (result-identical).
            reg(WeekOfYear::class) { e -> func("toISOWeek", e.thisArg) }
            // BUGS-clickhouse-generator-mappings row 9 (P2): the emitted DAY_OF_WEEK does
            // not exist in ClickHouse. toDayOfWeek is the valid name (a residual numbering
            // divergence — DuckDB Sunday=0 vs ClickHouse ISO Monday=1..Sunday=7 — remains,
            // kept as a hazard).
            reg(DayOfWeek::class) { e -> func("toDayOfWeek", e.thisArg) }
            // BUGS-clickhouse-generator-mappings row 11 (P2): the leaked internal node name
            // TIME_TO_UNIX does not exist in ClickHouse. toUnixTimestamp is the real name.
            reg(TimeToUnix::class) { e -> func("toUnixTimestamp", e.thisArg) }
            // TODO(clickhouse-bugs): DEFERRED rows from BUGS-clickhouse-generator-mappings
            // (millisecond, row 6, is now fixed via anonymousSql below — verified live):
            //   row 2  round  : half-away shim `sign(x)*floor(abs(x)*pow(10,d)+0.5)/pow(10,d)`
            //                    is live-VERIFIED (matches DuckDB across 2.5/0.5/-2.5/2dp),
            //                    but `round` is a ClickHouse-NATIVE name and this generator
            //                    is source-unaware, so applying it would regress CH->CH
            //                    banker's rounding — deferred pending that policy call.
            //   row 7  bin    : strip-leading-zeros shim
            //                    `if(x=0,'0',substring(bin(x),position(bin(x),'1')))` is
            //                    live-VERIFIED, but `bin` is CH-native too — same CH->CH
            //                    regression concern as round.
            //   row 8  to_days: the ToDays node is source-ambiguous (DuckDB interval-builder
            //                    vs MySQL day-number) — fix belongs in the DuckDB PARSER,
            //                    not here, so a target rewrite isn't safe for all sources.
            //   row 14 age    : return-type mismatch — DuckDB age(a,b) yields an INTERVAL,
            //                    ClickHouse age('unit',start,end) a scalar; no clean map.
            reg(ArrayDistinct::class) { e -> ch().renameFuncSql("arrayDistinct", e) }
            reg(ArrayConcat::class) { e -> ch().renameFuncSql("arrayConcat", e) }
            reg(ArrayContains::class) { e -> ch().renameFuncSql("has", e) }
            reg(ArrayFilter::class) { e ->
                func("arrayFilter", e.args["expression"], e.thisArg)
            }
            reg(Transform::class) { e -> func("arrayMap", e.args["expression"], e.thisArg) }
            // sqlglot: dialect.remove_from_array_using_filter
            reg(ArrayRemove::class) { e ->
                val lambdaId = Identifier(args("this" to "_u", "quoted" to false))
                val cond = NEQ(args("this" to lambdaId, "expression" to e.args["expression"]))
                val filterSql = sql(
                    ArrayFilter(
                        args(
                            "this" to e.thisArg,
                            "expression" to Lambda(
                                args("this" to cond, "expressions" to listOf(lambdaId))
                            ),
                        )
                    )
                )

                val removalValue = e.args["expression"] as? Expression
                // ClickHouse: ARRAY_FUNCS_PROPAGATES_NULLS=False (target)
                if (e.args["null_propagation"] == true) {
                    if ((removalValue is Literal && removalValue !is Null) ||
                        removalValue is ArrayNode
                    ) {
                        filterSql
                    } else {
                        sql(
                            If(
                                args(
                                    "this" to Is(
                                        args(
                                            "this" to removalValue?.copy(),
                                            "expression" to Null(),
                                        )
                                    ),
                                    "true" to Null(),
                                    "false" to filterSql,
                                )
                            )
                        )
                    }
                } else {
                    filterSql
                }
            }
            reg(ArrayReverse::class) { e -> ch().renameFuncSql("arrayReverse", e) }
            reg(ArraySlice::class) { e -> ch().renameFuncSql("arraySlice", e) }
            reg(ArraySum::class) { e -> ch().renameFuncSql("arraySum", e) }
            reg(ArrayMax::class) { e -> ch().renameFuncSql("arrayMax", e) }
            reg(ArrayMin::class) { e -> ch().renameFuncSql("arrayMin", e) }
            // sqlglot: dialect.arg_max_or_min_no_count
            reg(ArgMax::class) { e ->
                if (e.args["count"] != null) {
                    unsupported(
                        "Argument 'count' is not supported for expression 'ArgMax' when targeting ClickHouse."
                    )
                }
                func("argMax", e.thisArg, e.args["expression"])
            }
            reg(ArgMin::class) { e ->
                if (e.args["count"] != null) {
                    unsupported(
                        "Argument 'count' is not supported for expression 'ArgMin' when targeting ClickHouse."
                    )
                }
                func("argMin", e.thisArg, e.args["expression"])
            }
            // sqlglot: dialect.inline_array_sql
            reg(ArrayNode::class) { e ->
                "[" + expressions(
                    e, dynamic = true, newLine = true, skipFirst = true, skipLast = true
                ) + "]"
            }
            reg(CityHash64::class) { e -> ch().renameFuncSql("cityHash64", e) }
            reg(CastToStrType::class) { e -> ch().renameFuncSql("CAST", e) }
            reg(CurrentDatabase::class) { e -> ch().renameFuncSql("CURRENT_DATABASE", e) }
            reg(CurrentSchemas::class) { e -> ch().renameFuncSql("CURRENT_SCHEMAS", e) }
            reg(CountIf::class) { e -> ch().renameFuncSql("countIf", e) }
            reg(CosineDistance::class) { e -> ch().renameFuncSql("cosineDistance", e) }
            reg(CompressColumnConstraint::class) { e ->
                "CODEC(${expressions(e, key = "this", flat = true)})"
            }
            reg(ComputedColumnConstraint::class) { e ->
                val keyword = if (e.args["persisted"] == true) "MATERIALIZED" else "ALIAS"
                "$keyword ${sql(e, "this")}"
            }
            reg(CurrentDate::class) { _ -> func("CURRENT_DATE") }
            reg(CurrentVersion::class) { e -> ch().renameFuncSql("VERSION", e) }
            reg(DateAdd::class) { e -> ch().datetimeDeltaSql("DATE_ADD", e) }
            reg(DateDiff::class) { e -> ch().datetimeDeltaSql("DATE_DIFF", e) }
            reg(DateStrToDate::class) { e -> ch().renameFuncSql("toDate", e) }
            reg(DateSub::class) { e -> ch().datetimeDeltaSql("DATE_SUB", e) }
            reg(Explode::class) { e -> ch().renameFuncSql("arrayJoin", e) }
            reg(FarmFingerprint::class) { e -> ch().renameFuncSql("farmFingerprint64", e) }
            reg(Final::class) { e -> "${sql(e, "this")} FINAL" }
            reg(IsNan::class) { e -> ch().renameFuncSql("isNaN", e) }
            // sqlglot: dialect.jarowinkler_similarity("jaroWinklerSimilarity")
            reg(JarowinklerSimilarity::class) { e ->
                var this_ = e.thisArg as? Expression
                var expr = e.args["expression"] as? Expression
                if (e.args["case_insensitive"] == true) {
                    this_ = Upper(args("this" to this_))
                    expr = Upper(args("this" to expr))
                }
                func("jaroWinklerSimilarity", this_, expr)
            }
            reg(JSONCast::class) { e -> ch().jsonCastSql(e as JSONCast) }
            reg(JSONExtract::class) { e ->
                ch().jsonExtractSegments(e, "JSONExtractString", quotedIndex = false)
            }
            reg(JSONExtractScalar::class) { e ->
                ch().jsonExtractSegments(e, "JSONExtractString", quotedIndex = false)
            }
            // sqlglot: dialect.json_path_key_only_name
            reg(JSONPathKey::class) { e ->
                if (e.thisArg is JSONPathWildcard) {
                    unsupported("Unsupported wildcard in JSONPathKey expression")
                }
                e.name
            }
            reg(JSONPathRoot::class) { _ -> "" }
            // sqlglot: dialect.length_or_char_length_sql
            reg(Length::class) { e ->
                val lengthFunc = if (e.args["binary"] == true) "LENGTH" else "CHAR_LENGTH"
                func(lengthFunc, e.thisArg)
            }
            reg(MapNode::class) { e -> ch().mapSql(e) }
            reg(Median::class) { e -> ch().renameFuncSql("median", e) }
            reg(Nullif::class) { e -> ch().renameFuncSql("nullIf", e) }
            reg(PartitionedByProperty::class) { e -> "PARTITION BY ${sql(e, "this")}" }
            // sqlglot: dialect.no_pivot_sql
            reg(Pivot::class) { _ ->
                unsupported("PIVOT unsupported")
                ""
            }
            reg(Quantile::class) { e -> ch().quantileSql(e as Quantile) }
            reg(RegexpLike::class) { e -> func("match", e.thisArg, e.args["expression"]) }
            reg(Rand::class) { e -> ch().renameFuncSql("randCanonical", e) }
            reg(StartsWith::class) { e -> ch().renameFuncSql("startsWith", e) }
            reg(Struct::class) { e -> ch().renameFuncSql("tuple", e) }
            reg(Trunc::class) { e -> ch().renameFuncSql("trunc", e) }
            reg(EndsWith::class) { e -> ch().renameFuncSql("endsWith", e) }
            reg(EuclideanDistance::class) { e -> ch().renameFuncSql("L2Distance", e) }
            // sqlglot: dialect.strposition_sql(func_name="POSITION",
            // supports_position=True, use_ansi_position=False)
            reg(StrPosition::class) { e ->
                val string = e.thisArg
                val substr = e.args["substr"]
                val position = e.args["position"]
                val occurrence = e.args["occurrence"]

                val callArgs = mutableListOf<kotlin.Any?>(string, substr)
                if (position != null) callArgs.add(position)
                if (occurrence != null) {
                    unsupported("POSITION does not support the occurrence parameter.")
                }
                sql(Anonymous(args("this" to "POSITION", "expressions" to callArgs)))
            }
            reg(TimeToStr::class) { e ->
                val this_ = e.thisArg
                val target = if (this_ is TsOrDsToTimestamp) this_.thisArg else this_
                func("formatDateTime", target, formatTime(e), e.args["zone"])
            }
            reg(TimeStrToTime::class) { e -> ch().timestrtotimeSql(e as TimeStrToTime) }
            reg(TimestampAdd::class) { e -> ch().datetimeDeltaSql("TIMESTAMP_ADD", e) }
            reg(TimestampSub::class) { e -> ch().datetimeDeltaSql("TIMESTAMP_SUB", e) }
            reg(Typeof::class) { e -> ch().renameFuncSql("toTypeName", e) }
            reg(VarMap::class) { e -> ch().mapSql(e) }
            reg(Xor::class) { e -> func("xor", e.thisArg, e.args["expression"]) }
            reg(MD5Digest::class) { e -> ch().renameFuncSql("MD5", e) }
            reg(MD5::class) { e ->
                func("LOWER", func("HEX", func("MD5", e.thisArg)))
            }
            reg(SHA::class) { e -> ch().renameFuncSql("SHA1", e) }
            reg(SHA1Digest::class) { e -> ch().renameFuncSql("SHA1", e) }
            // sqlglot: dialect.sha256_sql
            reg(SHA2::class) { e ->
                func("SHA${e.text("length").ifEmpty { "256" }}", e.thisArg)
            }
            // sqlglot: dialect.sha2_digest_sql
            reg(SHA2Digest::class) { e ->
                func("SHA${e.text("length").ifEmpty { "256" }}", e.thisArg)
            }
            reg(Split::class) { e ->
                func("splitByString", e.args["expression"], e.thisArg, e.args["limit"])
            }
            reg(RegexpSplit::class) { e ->
                func("splitByRegexp", e.args["expression"], e.thisArg, e.args["limit"])
            }
            reg(UnixToTime::class) { e -> ch().unixToTimeSql(e as UnixToTime) }
            // sqlglot: dialect.trim_sql(default_trim_type="BOTH")
            reg(Trim::class) { e ->
                val removeChars = sql(e, "expression")
                if (removeChars.isEmpty()) {
                    trimSql(e as Trim)
                } else {
                    val target = sql(e, "this")
                    var trimType = sql(e, "position").ifEmpty { "BOTH" }
                    var collation = sql(e, "collation")

                    trimType = if (trimType.isNotEmpty()) "$trimType " else ""
                    val removeCharsPart = "$removeChars "
                    val fromPart = "FROM "
                    collation = if (collation.isNotEmpty()) " COLLATE $collation" else ""
                    "TRIM($trimType$removeCharsPart$fromPart$target$collation)"
                }
            }
            reg(Variance::class) { e -> ch().renameFuncSql("varSamp", e) }
            reg(SchemaCommentProperty::class) { e -> nakedProperty(e as Property) }
            reg(Stddev::class) { e -> ch().renameFuncSql("stddevSamp", e) }
            reg(Chr::class) { e -> ch().renameFuncSql("CHAR", e) }
            reg(Lag::class) { e ->
                func("lagInFrame", e.thisArg, e.args["offset"], e.args["default"])
            }
            reg(Lead::class) { e ->
                func("leadInFrame", e.thisArg, e.args["offset"], e.args["default"])
            }
            // sqlglot: unsupported_args(...)(rename_func("editDistance"))
            reg(Levenshtein::class) { e ->
                for (argName in listOf("ins_cost", "del_cost", "sub_cost", "max_dist")) {
                    if (e.args[argName] != null && e.args[argName] != false) {
                        unsupported(
                            "Argument '$argName' is not supported for expression 'Levenshtein' when targeting ClickHouse."
                        )
                    }
                }
                ch().renameFuncSql("editDistance", e)
            }
            reg(ParseDatetime::class) { e ->
                func("parseDateTime", e.thisArg, e.args["format"], e.args["zone"])
            }

            // Auto-discovered <name>_sql methods on the Python class that have no base
            // dispatch entry (the Kotlin dispatch is class-keyed):
            reg(GroupConcat::class) { e -> ch().groupconcatSql(e as GroupConcat) }
            reg(StrToDate::class) { e -> ch().strtodateSql(e as StrToDate) }
            reg(RegexpILike::class) { e -> ch().regexpilikeSql(e as RegexpILike) }
            reg(TimestampTrunc::class) { e -> ch().timestamptruncSql(e) }
            reg(DateTrunc::class) { e -> ch().timestamptruncSql(e) }
            reg(PartitionId::class) { e -> ch().partitionidSql(e as PartitionId) }
            reg(ReplacePartition::class) { e -> ch().replacepartitionSql(e as ReplacePartition) }
            reg(ProjectionDef::class) { e -> ch().projectiondefSql(e as ProjectionDef) }
            reg(NestedJSONSelect::class) { e -> ch().nestedjsonselectSql(e as NestedJSONSelect) }
            reg(EQ::class) { e -> ch().anyToHas(e as EQ, { x -> binary(x as Binary, "=") }) }
            reg(NEQ::class) { e ->
                ch().anyToHas(e as NEQ, { x -> binary(x as Binary, "<>") }, "NOT ")
            }
        }
    }

    // sqlglot: dialect.json_extract_segments (quoted_index=False for ClickHouse)
    open fun jsonExtractSegments(
        expression: Expression,
        name: String,
        quotedIndex: Boolean = true,
        op: String? = null,
    ): String {
        val path = expression.args["expression"]
        if (path !is JSONPath) return renameFuncSql(name, expression)

        val segments = mutableListOf<String>()
        for (segment in path.expressionsArg) {
            val seg = segment as? Expression ?: continue
            val escape = seg.args["quoted"] == true
            var segSql = sql(seg)
            if (segSql.isNotEmpty()) {
                if (seg is JSONPathPart && (quotedIndex || seg !is JSONPathSubscript)) {
                    if (escape) segSql = escapeStr(segSql)
                    segSql = "$quoteStart$segSql$quoteEnd"
                }
                segments.add(segSql)
            }
        }

        if (op != null) {
            return (listOf(sql(expression, "this")) + segments).joinToString(" $op ")
        }
        return func(name, expression.thisArg, *segments.toTypedArray())
    }
}
