package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.generator.eliminateQualify
import dev.brikk.house.sql.generator.eliminateDistinctOn
import dev.brikk.house.sql.generator.unnestToExplode
import dev.brikk.house.sql.generator.anyToExists
import dev.brikk.house.sql.parser.Spark2TokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.reflect.KClass

// sqlglot: dialect.unit_to_str (default "DAY")
private fun spark2UnitToStr(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression
        ?: return if (default.isNotEmpty()) Literal.string(default) else null
    if (unit is Placeholder || (unit !is Var && unit !is Literal)) return unit
    return Literal.string(unit.name)
}

// sqlglot: dialect.is_parse_json
private fun isParseJson(expression: Expression?): Boolean =
    expression is ParseJSON || (expression is Cast && expression.isType(DType.JSON))

private const val HIVE_DATE_FORMAT = "'yyyy-MM-dd'"

/**
 * Port of sqlglot's Spark2Generator (reference/sqlglot/sqlglot/generators/spark2.py
 * class Spark2Generator(HiveGenerator)). TRANSFORMS entries live in [TRANSFORMS], passed
 * through HiveGenerator's dispatch overlay; flag overrides are open-val overrides.
 *
 * NOT PORTED (no Kotlin equivalents of sqlglot's transforms/preprocess pipelines yet):
 * exp.Select preprocess (unnest_to_explode / any_to_exists / eliminate_distinct_on — only
 * eliminate_qualify is applied), exp.From (_unalias_pivot), exp.Pivot
 * (_unqualify_pivot_columns), exp.WithinGroup (remove_within_group_for_percentiles),
 * exp.Create (remove_unique_constraints / ctas_with_tmp_tables_to_create_tmp_view /
 * move_schema_columns_to_partitioned_by). These render via the inherited generator; any
 * mismatches are ledgered.
 */
// sqlglot: generators.spark2.Spark2Generator
open class Spark2Generator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = Spark2TokenizerTables.CONFIG,
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
    sourceDialect: String? = null,
) : HiveGenerator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
    sourceDialect = sourceDialect,
) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.SPARK2

    // sqlglot: Spark2Generator.QUERY_HINTS = True
    override val queryHints: Boolean get() = true

    // sqlglot: Spark2Generator.NVL2_SUPPORTED = True
    override val nvl2Supported: Boolean get() = true

    // sqlglot: Spark2Generator.ALTER_SET_TYPE = "TYPE"
    override val alterSetType: String get() = "TYPE"

    // sqlglot: Spark2Generator.PARSE_JSON_NAME = None
    override val parseJsonName: String? get() = null

    // sqlglot: Spark2Generator.WRAP_DERIVED_VALUES = False
    override val wrapDerivedValues: Boolean get() = false

    // sqlglot: Spark2Generator.CREATE_FUNCTION_RETURN_AS = False
    override val createFunctionReturnAs: Boolean get() = false

    // sqlglot: Spark2Generator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = SPARK2_PROPERTIES_LOCATION

    // sqlglot: Spark2Generator.TS_OR_DS_EXPRESSIONS (adds day/week-of-year family)
    override val tsOrDsExpressions: Set<KClass<out Expression>>
        get() = super.tsOrDsExpressions +
            setOf(DayOfMonth::class, DayOfWeek::class, DayOfYear::class, WeekOfYear::class)

    // sqlglot: Spark2Generator.struct_sql — delegates to the base Generator (named structs OK)
    override fun structSql(expression: Struct): String = baseStructSql(expression)

    // sqlglot: Spark2Generator.cast_sql
    override fun castSql(expression: Cast, safePrefix: String?): String {
        val arg = expression.thisArg as? Expression
        val isJsonExtract = (arg is JSONExtract || arg is JSONExtractScalar) &&
            arg.args["variant_extract"] == null

        val to = expression.args["to"] as? DataType
        if (to?.args?.get("nested") == true && (isParseJson(arg) || isJsonExtract)) {
            val schema = "'${sql(expression, "to")}'"
            val target = if (isJsonExtract) arg else (arg as? ParseJSON)?.thisArg
            return func("FROM_JSON", target, schema)
        }

        if (isParseJson(expression)) {
            return func("TO_JSON", arg)
        }

        return baseCastSql(expression, safePrefix)
    }

    // sqlglot: Spark2Generator.fileformatproperty_sql
    override fun fileformatpropertySql(expression: FileFormatProperty): String {
        if (expression.args["hive_format"] == true) {
            return super.fileformatpropertySql(expression)
        }
        return "USING ${expression.name.uppercase()}"
    }

    // sqlglot: Spark2Generator.altercolumn_sql
    override fun altercolumnSql(expression: AlterColumn): String {
        val this_ = sql(expression, "this")
        val newName = sql(expression, "rename_to").ifEmpty { this_ }
        val commentSql = sql(expression, "comment")
        if (newName == this_) {
            if (commentSql.isNotEmpty()) return "ALTER COLUMN $this_ COMMENT $commentSql"
            return baseAltercolumnSql(expression)
        }
        return "RENAME COLUMN $this_ TO $newName"
    }

    // sqlglot: Spark2Generator.renamecolumn_sql — restores the base behavior (Hive forbids it)
    override fun renamecolumnSql(expression: RenameColumn): String =
        baseRenamecolumnSql(expression)

    // sqlglot: Spark2Generator.bracket_sql
    override fun bracketSql(expression: Bracket): String {
        if (expression.args["safe"] == false) {
            return bracketToElementAtSql(expression)
        }
        return super.bracketSql(expression)
    }

    // sqlglot: dialect.bracket_to_element_at_sql — index offset 1 - expression.offset
    protected fun bracketToElementAtSql(expression: Bracket): String {
        val index = bracketOffsetExpressions(expression, indexOffset = 1).firstOrNull()
        return func("ELEMENT_AT", expression.thisArg, index)
    }

    // sqlglot: Spark2Generator TRANSFORMS[exp.JSONFormat] (_json_format_sql)
    internal fun jsonFormatSql(expression: JSONFormat): String {
        val this_ = expression.thisArg as? Expression
        if (isParseJson(this_)) {
            val inner = (this_ as? ParseJSON)?.thisArg as? Expression
            if (inner is Literal && inner.isString) {
                val wrappedJson = Literal.string("[${inner.name}]")
                val fromJson = func("FROM_JSON", wrappedJson, func("SCHEMA_OF_JSON", wrappedJson))
                val toJson = func("TO_JSON", fromJson)
                return func("REGEXP_EXTRACT", toJson, "'^.(.*).\$'", "1")
            }
            return sql(this_)
        }
        return func("TO_JSON", this_, expression.args["options"])
    }

    // sqlglot: Spark2Generator TRANSFORMS[exp.Map] (_map_sql)
    internal fun mapSql(expression: MapNode): String {
        val keys = expression.args["keys"]
        val values = expression.args["values"]
        if (keys == null || values == null) return func("MAP")
        return func("MAP_FROM_ARRAYS", keys, values)
    }

    // sqlglot: Spark2Generator TRANSFORMS[exp.StrToDate] (_str_to_date)
    internal fun strToDateSpark(expression: StrToDate): String {
        val timeFormat = formatTime(expression)
        if (timeFormat == HIVE_DATE_FORMAT) return func("TO_DATE", expression.thisArg)
        return func("TO_DATE", expression.thisArg, timeFormat)
    }

    // sqlglot: Spark2Generator TRANSFORMS[exp.UnixToTime] (_unix_to_time_sql)
    internal fun unixToTimeSpark(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        val timestamp = expression.thisArg
        if (scale == null) {
            val fromUnix = anon("FROM_UNIXTIME", timestamp)
            return sql(Cast(args("this" to fromUnix, "to" to DataType.build(DType.TIMESTAMP))))
        }
        val scaleName = (scale as? Expression)?.name
        return when (scaleName) {
            "0" -> func("TIMESTAMP_SECONDS", timestamp)
            "3" -> func("TIMESTAMP_MILLIS", timestamp)
            "6" -> func("TIMESTAMP_MICROS", timestamp)
            else -> {
                val pow = anon("POW", Literal.number("10"), scale)
                val unixSeconds = Div(args("this" to timestamp, "expression" to pow))
                func("TIMESTAMP_SECONDS", unixSeconds)
            }
        }
    }

    private fun anon(name: String, vararg fnArgs: kotlin.Any?): Expression =
        Anonymous(args("this" to name, "expressions" to fnArgs.toList()))

    // sqlglot: dialect.trim_sql (Hive/Spark TRANSFORMS[exp.Trim]) — FROM syntax when a
    // removal pattern is present, else base LTRIM/RTRIM/TRIM.
    internal fun sparkTrimSql(expression: Trim): String {
        val removeChars = sql(expression, "expression")
        if (removeChars.isEmpty()) return trimSql(expression)

        val target = sql(expression, "this")
        var trimType = sql(expression, "position")
        var collation = sql(expression, "collation")

        trimType = if (trimType.isNotEmpty()) "$trimType " else ""
        val remove = "$removeChars "
        collation = if (collation.isNotEmpty()) " COLLATE $collation" else ""
        return "TRIM($trimType${remove}FROM $target$collation)"
    }

    companion object {
        // sqlglot: Spark2Generator.PROPERTIES_LOCATION
        val SPARK2_PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            HiveGenerator.PROPERTIES_LOCATION + mapOf(
                EngineProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                AutoIncrementProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                CharacterSetProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                CollateProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
            )

        // sqlglot: Spark2Generator.TRANSFORMS (dispatch-map overlay over HiveGenerator's)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.sg(): Spark2Generator = this as Spark2Generator

            reg(ApproxDistinct::class) { e -> sg().renameFuncSql("APPROX_COUNT_DISTINCT", e) }
            reg(ArraySum::class) { e ->
                "AGGREGATE(${sql(e, "this")}, 0, (acc, x) -> acc + x, acc -> acc)"
            }
            reg(ArrayToString::class) { e -> sg().renameFuncSql("ARRAY_JOIN", e) }
            reg(ArraySlice::class) { e -> sg().renameFuncSql("SLICE", e) }
            reg(AtTimeZone::class) { e -> func("FROM_UTC_TIMESTAMP", e.thisArg, e.args["zone"]) }
            reg(BitwiseLeftShift::class) { e -> sg().renameFuncSql("SHIFTLEFT", e) }
            reg(BitwiseRightShift::class) { e -> sg().renameFuncSql("SHIFTRIGHT", e) }
            reg(DateFromParts::class) { e -> sg().renameFuncSql("MAKE_DATE", e) }
            reg(DateTrunc::class) { e -> func("TRUNC", e.thisArg, spark2UnitToStr(e)) }
            reg(DayOfMonth::class) { e -> sg().renameFuncSql("DAYOFMONTH", e) }
            reg(DayOfWeek::class) { e -> sg().renameFuncSql("DAYOFWEEK", e) }
            reg(DayOfWeekIso::class) { e -> "((${func("DAYOFWEEK", e.thisArg)} % 7) + 1)" }
            reg(DayOfYear::class) { e -> sg().renameFuncSql("DAYOFYEAR", e) }
            reg(Format::class) { e -> sg().renameFuncSql("FORMAT_STRING", e) }
            // sqlglot: HiveGenerator TRANSFORMS[exp.If] = if_sql() — IF(cond, true, false)
            reg(If::class) { e ->
                func("IF", e.thisArg, e.args["true"], e.args["false"])
            }
            reg(FromTimeZone::class) { e -> func("TO_UTC_TIMESTAMP", e.thisArg, e.args["zone"]) }
            reg(JSONFormat::class) { e -> sg().jsonFormatSql(e as JSONFormat) }
            reg(LogicalAnd::class) { e -> sg().renameFuncSql("BOOL_AND", e) }
            reg(LogicalOr::class) { e -> sg().renameFuncSql("BOOL_OR", e) }
            reg(MapNode::class) { e -> sg().mapSql(e as MapNode) }
            reg(Reduce::class) { e -> sg().renameFuncSql("AGGREGATE", e) }
            reg(RegexpReplace::class) { e ->
                func("REGEXP_REPLACE", e.thisArg, e.args["expression"], e.args["replacement"], e.args["position"])
            }
            // sqlglot: spark2 exp.Select preprocess pipeline
            // [eliminate_qualify, eliminate_distinct_on, unnest_to_explode, any_to_exists]
            reg(Select::class) { e ->
                var s = eliminateQualify(e)
                s = eliminateDistinctOn(s)
                s = unnestToExplode(s)
                s = anyToExists(s)
                selectSql(s as Select)
            }
            reg(SHA2Digest::class) { e ->
                func("SHA2", e.thisArg, e.args["length"] ?: Literal.number("256"))
            }
            reg(StrToDate::class) { e -> sg().strToDateSpark(e as StrToDate) }
            reg(StrToTime::class) { e -> func("TO_TIMESTAMP", e.thisArg, sg().formatTime(e)) }
            reg(TimestampTrunc::class) { e -> func("DATE_TRUNC", spark2UnitToStr(e), e.thisArg) }
            reg(Trim::class) { e -> sg().sparkTrimSql(e as Trim) }
            reg(UnixToTime::class) { e -> sg().unixToTimeSpark(e as UnixToTime) }
            reg(VariancePop::class) { e -> sg().renameFuncSql("VAR_POP", e) }
            reg(WeekOfYear::class) { e -> sg().renameFuncSql("WEEKOFYEAR", e) }
            // sqlglot: Spark2Generator TRANSFORMS removals (= None) — restore base rendering,
            // overriding HiveGenerator's SORT_ARRAY / no-ILIKE / SUBSTRING / MONTHS_BETWEEN.
            reg(ArraySort::class) { e -> functionFallbackSql(e as Func) }
            reg(ILike::class) { e -> sg().ilikeSql(e as ILike) }
            reg(Left::class) { e -> functionFallbackSql(e as Func) }
            reg(MonthsBetween::class) { e -> functionFallbackSql(e as Func) }
            reg(Right::class) { e -> functionFallbackSql(e as Func) }
        }
    }
}
