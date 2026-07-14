package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.generator.eliminateDistinctOn
import dev.brikk.house.sql.generator.eliminateSemiAndAntiJoins
import dev.brikk.house.sql.parser.BigqueryTokenizerTables
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
        ?: return if (default.isNotEmpty()) Var(args("this" to default)) else null
    if (unit is Var || unit is Placeholder || unit is WeekStart || unit is Column) return unit
    val value = unit.name
    return if (value.isNotEmpty()) Var(args("this" to value)) else null
}

/**
 * Port of sqlglot's BigQueryGenerator (reference/sqlglot/sqlglot/generators/bigquery.py
 * class BigQueryGenerator(generator.Generator)). TRANSFORMS entries and the bigquery
 * method overrides are handed to the base constructor as a dispatch overlay; flag
 * overrides are open-val overrides.
 *
 * NOT PORTED (ledgered — need transform/annotate infrastructure the port lacks):
 *  - the exp.Select preprocess pipeline (explode_projection_to_unnest, unqualify_unnest,
 *    eliminate_distinct_on, _alias_ordered_group, eliminate_semi_and_anti_joins);
 *  - exp.CTE _pushdown_cte_column_names, exp.Create _create_sql (TABLE FUNCTION), the
 *    exp.Values -> UNNEST(ARRAY<STRUCT>) rewrite, exp.ArrayContains -> EXISTS(UNNEST),
 *    ArrayFilter/ArrayRemove filter_array_using_unnest;
 *  - annotate_types-dependent branches of bracket_sql (STRUCT field access);
 *  - the AFTER_HAVING_MODIFIER ordering (QUALIFY before WINDOW).
 */
// sqlglot: generators.bigquery.BigQueryGenerator
open class BigqueryGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = BigqueryTokenizerTables.CONFIG,
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
    sourceDialect: String? = null,
) : Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    // sqlglot: BigQuery.NORMALIZE_FUNCTIONS = False (UDFs are case-sensitive)
    normalizeFunctions = false,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
    sourceDialect = sourceDialect,
) {

    override val dialect: Dialect get() = Dialects.BIGQUERY

    // sqlglot: BigQueryGenerator flags
    override val trySupported: Boolean get() = false
    override val supportsUescape: Boolean get() = false
    override val intervalAllowsPluralForm: Boolean get() = false
    override val joinHints: Boolean get() = false
    override val queryHints: Boolean get() = false
    override val tableHints: Boolean get() = false
    override val limitFetch: String get() = "LIMIT"
    override val renameTableWithDb: Boolean get() = false
    override val nvl2Supported: Boolean get() = false
    override val unnestWithOrdinality: Boolean get() = false
    override val collateIsFunc: Boolean get() = true
    override val limitOnlyLiterals: Boolean get() = true
    override val supportsTableAliasColumns: Boolean get() = false
    override val supportsNamedCteColumns: Boolean get() = false
    override val unpivotAliasesAreIdentifiers: Boolean get() = false
    override val jsonKeyValuePairSep: String get() = ","
    override val nullOrderingSupported: Boolean? get() = false
    override val ignoreNullsInFunc: Boolean get() = true
    override val jsonPathSingleQuoteEscape: Boolean get() = true
    override val supportsToNumber: Boolean get() = false
    override val namedPlaceholderToken: String get() = "@"
    override val withPropertiesPrefix: String get() = "OPTIONS"
    override val supportsExplodingProjections: Boolean get() = false
    override val exceptIntersectSupportAllClause: Boolean get() = false
    override val reservedKeywords: Set<String> get() = RESERVED_KEYWORDS

    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: BigQuery.BYTE_START/BYTE_END (from tokenizer BYTE_STRINGS b'..') and
    // BYTE_STRING_IS_BYTES_TYPE (renders b'..' rather than casting to BYTES).
    override val byteStart: String? get() = "b'"
    override val byteEnd: String? get() = "'"
    override val dialectByteStringIsBytesType: Boolean get() = true

    // sqlglot: BigQueryGenerator dispatch helper used by TRANSFORMS entries.
    internal fun renameFuncSql(name: String, expression: Expression): String {
        val exprs = expression.expressionsArg.filterIsInstance<Expression>()
        val theseArgs: List<kotlin.Any?> =
            if (exprs.isNotEmpty()) exprs
            else listOfNotNull(
                expression.args["this"], expression.args["expression"],
            )
        return func(name, *theseArgs.toTypedArray())
    }

    // sqlglot: BigQueryGenerator.datetrunc_sql
    fun datetruncSql(expression: DateTrunc): String {
        val unit = expression.args["unit"] as? Expression
        val unitSql = if (unit is Literal && unit.isString) unit.name else sql(unit)
        return func("DATE_TRUNC", expression.args["this"], unitSql, expression.args["zone"])
    }

    // sqlglot: BigQueryGenerator.mod_sql
    fun modSql(expression: Mod): String {
        val this0 = expression.args["this"] as? Expression
        val expr0 = expression.args["expression"] as? Expression
        return func(
            "MOD",
            if (this0 is Paren) this0.unnest() else this0,
            if (expr0 is Paren) expr0.unnest() else expr0,
        )
    }

    // sqlglot: BigQueryGenerator.eq_sql — operands of = cannot be NULL.
    fun eqSql(expression: EQ): String {
        val left = expression.args["this"] as? Expression
        val right = expression.args["expression"] as? Expression
        if ((left is Null || right is Null) && expression.parent !is Update) {
            return "NULL"
        }
        return binary(expression, "=")
    }

    // sqlglot: BigQueryGenerator.contains_sql
    fun containsSql(expression: Contains): String {
        var this0 = expression.args["this"] as? Expression
        var expr0 = expression.args["expression"] as? Expression
        if (this0 is Lower && expr0 is Lower) {
            this0 = this0.thisArg as? Expression
            expr0 = expr0.thisArg as? Expression
        }
        return func("CONTAINS_SUBSTR", this0, expr0, expression.args["json_scope"])
    }

    // sqlglot: BigQueryGenerator.trycast_sql
    override fun trycastSql(expression: TryCast): String = castSql(expression, safePrefix = "SAFE_")

    // sqlglot: BigQueryGenerator.cast_sql — inline ARRAY<T>[..] literals round-trip.
    override fun castSql(expression: Cast, safePrefix: String?): String {
        // sqlglot: transforms.remove_precision_parameterized_types — drop DataTypeParam
        // args from the target type (e.g. NUMERIC(10, 2) -> NUMERIC, STRING(10) -> STRING).
        val to = expression.args["to"] as? DataType
        if (to != null) {
            val kept = to.expressionsArg.filterIsInstance<Expression>()
                .filter { it !is DataTypeParam }
            if (kept.size != to.expressionsArg.size) {
                to.set("expressions", if (kept.isEmpty()) null else kept)
            }
        }
        val this0 = expression.args["this"]
        if (this0 is ArrayNode) {
            val elem = this0.expressionsArg.filterIsInstance<Expression>().firstOrNull()
            val hasQuery = elem != null && elem.walk().any { it is dev.brikk.house.sql.ast.Query }
            if (!hasQuery) {
                return "${sql(expression, "to")}${sql(this0)}"
            }
        }
        return super.castSql(expression, safePrefix)
    }

    // sqlglot: BigQueryGenerator.bracket_sql — OFFSET/ORDINAL/SAFE_ forms.
    override fun bracketSql(expression: Bracket): String {
        val this0 = expression.args["this"] as? Expression
        var expressionsSql = expressions(expression, flat = true)
        val offset = expression.args["offset"]

        when (offset) {
            0 -> expressionsSql = "OFFSET($expressionsSql)"
            1 -> expressionsSql = "ORDINAL($expressionsSql)"
            else -> if (offset != null) unsupported("Unsupported array offset: $offset")
        }
        if (expression.args["safe"] == true) {
            expressionsSql = "SAFE_$expressionsSql"
        }
        return "${sql(this0)}[$expressionsSql]"
    }

    // sqlglot: BigQueryGenerator.in_unnest_op
    override fun inUnnestOp(unnest: Unnest): String = sql(unnest)

    // sqlglot: BigQueryGenerator.version_sql
    override fun versionSql(expression: Version): String {
        if (expression.name == "TIMESTAMP") expression.set("this", "SYSTEM_TIME")
        return super.versionSql(expression)
    }

    // sqlglot: BigQueryGenerator.clusterproperty_sql
    override fun clusterpropertySql(expression: ClusterProperty): String {
        if (expression.args["this"] != null) {
            unsupported("Unsupported CLUSTER BY ${sql(expression, "this")}")
            return ""
        }
        return opExpressions("CLUSTER BY", expression)
    }

    // sqlglot: BigQueryGenerator.column_parts — preserve quoted table path.
    override fun columnParts(expression: Column): String {
        if (expression.metaOrNull?.get("quoted_column") == true) {
            val parts = expression.parts
            val tableParts = parts.dropLast(1).joinToString(".") { it.name }
            val tablePath = sql(Identifier(args("this" to tableParts, "quoted" to true)))
            return "$tablePath.${sql(expression, "this")}"
        }
        return super.columnParts(expression)
    }

    // sqlglot: BigQueryGenerator.table_parts — quoted-table path preservation.
    override fun tableParts(expression: Table): String {
        if (expression.metaOrNull?.get("quoted_table") == true) {
            val tableParts = expression.parts.joinToString(".") { it.name }
            return sql(Identifier(args("this" to tableParts, "quoted" to true)))
        }
        return super.tableParts(expression)
    }

    // sqlglot: base log_sql renders LOG(expression, this) — value then base (LOG_DEFAULTS_TO_LN).
    override fun logSql(expression: Log): String {
        val expr = expression.args["expression"]
        return if (expr != null) func("LOG", expr, expression.args["this"])
        else func("LOG", expression.args["this"])
    }

    // sqlglot: bigquery _unix_to_time_sql
    fun unixToTimeSql(expression: UnixToTime): String {
        val scale = (expression.args["scale"] as? Expression)?.name
        val timestamp = expression.args["this"]
        return when (scale) {
            null, "0", "seconds" -> func("TIMESTAMP_SECONDS", timestamp)
            "3" -> func("TIMESTAMP_MILLIS", timestamp)
            "6" -> func("TIMESTAMP_MICROS", timestamp)
            else -> {
                val unixSeconds = Cast(
                    args(
                        "this" to Div(
                            args(
                                "this" to timestamp,
                                "expression" to Pow(
                                    args(
                                        "this" to Literal.number("10"),
                                        "expression" to (expression.args["scale"] as? Expression),
                                    )
                                ),
                            )
                        ),
                        "to" to DataType(args("this" to DType.BIGINT)),
                    )
                )
                func("TIMESTAMP_SECONDS", unixSeconds)
            }
        }
    }

    // sqlglot: dialect.groupconcat_sql(func_name="STRING_AGG", within_group=False, sep=None)
    fun groupConcatSql(expression: GroupConcat): String {
        var this0 = expression.args["this"] as? Expression
        val sepArg = expression.args["separator"] as? Expression

        var limit: Limit? = null
        if (this0 is Limit && this0.args["this"] != null) {
            limit = this0
            val inner = this0.args["this"] as Expression
            this0.set("this", null)
            this0 = inner
        }

        val order = this0?.find(Order::class) as? Order
        if (order != null && order.args["this"] != null) {
            val inner = order.args["this"] as Expression
            order.set("this", null)
            if (this0 === order) this0 = inner
        }

        val argsSql = if (sepArg != null) formatArgs(this0, sepArg) else sql(this0)

        var modifiers = if (limit != null) sql(limit) else ""
        if (order != null) modifiers = "${sql(order)}$modifiers"

        return "STRING_AGG($argsSql$modifiers)"
    }

    // sqlglot: bigquery _str_to_datetime_sql (StrToDate/StrToTime -> PARSE_DATE/PARSE_TIMESTAMP)
    fun strToDatetimeSql(expression: Expression): String {
        val this0 = expression.args["this"]
        val dtype = if (expression is StrToDate) "DATE" else "TIMESTAMP"
        // safe branch (SAFE_CAST ... FORMAT) not ported; plain PARSE_ path.
        val fmt = formatTime(expression)
        return func("PARSE_$dtype", fmt, this0, expression.args["zone"])
    }

    // sqlglot: bigquery timetostr_sql
    fun timeToStrSql(expression: TimeToStr): String {
        val this0 = expression.args["this"] as? Expression
        val funcName = when (this0) {
            is TsOrDsToDatetime -> "FORMAT_DATETIME"
            is TsOrDsToTimestamp -> "FORMAT_TIMESTAMP"
            is TsOrDsToTime -> "FORMAT_TIME"
            else -> "FORMAT_DATE"
        }
        val timeExpr: Expression? = when (this0) {
            is TsOrDsToDatetime, is TsOrDsToTimestamp, is TsOrDsToTime, is TsOrDsToDate ->
                this0.args["this"] as? Expression
            else -> expression.args["this"] as? Expression
        }
        return func(funcName, formatTime(expression), timeExpr, expression.args["zone"])
    }

    // sqlglot: dialect.date_add_interval_sql(data_type, kind)
    internal fun dateAddIntervalSql(dataType: String, kind: String, expression: Expression): String {
        val this0 = sql(expression, "this")
        val interval = Interval(
            args("this" to expression.args["expression"], "unit" to unitToVar(expression))
        )
        return "${dataType}_$kind($this0, ${sql(interval)})"
    }

    companion object {
        // sqlglot: BigQueryGenerator.TYPE_MAPPING (base generator.Generator.TYPE_MAPPING
        // defaults + BigQuery overrides)
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
        } + mapOf(
            DType.BIGDECIMAL to "BIGNUMERIC",
            DType.BIGINT to "INT64",
            DType.BINARY to "BYTES",
            DType.BLOB to "BYTES",
            DType.BOOLEAN to "BOOL",
            DType.CHAR to "STRING",
            DType.DECIMAL to "NUMERIC",
            DType.DOUBLE to "FLOAT64",
            DType.FLOAT to "FLOAT64",
            DType.INT to "INT64",
            DType.NCHAR to "STRING",
            DType.NVARCHAR to "STRING",
            DType.SMALLINT to "INT64",
            DType.TEXT to "STRING",
            DType.TIMESTAMP to "DATETIME",
            DType.TIMESTAMPNTZ to "DATETIME",
            DType.TIMESTAMPTZ to "TIMESTAMP",
            DType.TIMESTAMPLTZ to "TIMESTAMP",
            DType.TINYINT to "INT64",
            DType.ROWVERSION to "BYTES",
            DType.UUID to "STRING",
            DType.VARBINARY to "BYTES",
            DType.VARCHAR to "STRING",
            DType.VARIANT to "ANY TYPE",
        )

        // sqlglot: BigQueryGenerator.RESERVED_KEYWORDS
        val RESERVED_KEYWORDS: Set<String> = setOf(
            "all", "and", "any", "array", "as", "asc", "assert_rows_modified", "at",
            "between", "by", "case", "cast", "collate", "contains", "create", "cross",
            "cube", "current", "default", "define", "desc", "distinct", "else", "end",
            "enum", "escape", "except", "exclude", "exists", "extract", "false", "fetch",
            "following", "for", "from", "full", "group", "grouping", "groups", "hash",
            "having", "if", "ignore", "in", "inner", "intersect", "interval", "into", "is",
            "join", "lateral", "left", "like", "limit", "lookup", "merge", "natural",
            "new", "no", "not", "null", "nulls", "of", "on", "or", "order", "outer",
            "over", "partition", "preceding", "proto", "qualify", "range", "recursive",
            "respect", "right", "rollup", "rows", "select", "set", "some", "struct",
            "tablesample", "then", "to", "treat", "true", "unbounded", "union", "unnest",
            "using", "when", "where", "window", "with", "within",
        )

        // sqlglot: BigQueryGenerator.TRANSFORMS (dispatch overlay over the base generator)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.bg(): BigqueryGenerator = this as BigqueryGenerator

            // sqlglot: dialect.inline_array_unless_query
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
            reg(AIEmbed::class) { e -> bg().renameFuncSql("EMBED", e) }
            reg(AIGenerate::class) { e -> bg().renameFuncSql("GENERATE", e) }
            reg(AISimilarity::class) { e -> bg().renameFuncSql("SIMILARITY", e) }
            reg(ApproxTopK::class) { e -> bg().renameFuncSql("APPROX_TOP_COUNT", e) }
            reg(ApproxDistinct::class) { e -> bg().renameFuncSql("APPROX_COUNT_DISTINCT", e) }
            reg(BitwiseAndAgg::class) { e -> bg().renameFuncSql("BIT_AND", e) }
            reg(BitwiseOrAgg::class) { e -> bg().renameFuncSql("BIT_OR", e) }
            reg(BitwiseXorAgg::class) { e -> bg().renameFuncSql("BIT_XOR", e) }
            reg(BitwiseCount::class) { e -> bg().renameFuncSql("BIT_COUNT", e) }
            reg(ByteLength::class) { e -> bg().renameFuncSql("BYTE_LENGTH", e) }
            reg(Commit::class) { _ -> "COMMIT TRANSACTION" }
            reg(CountIf::class) { e -> bg().renameFuncSql("COUNTIF", e) }
            reg(DateAdd::class) { e -> bg().dateAddIntervalSql("DATE", "ADD", e) }
            reg(DateDiff::class) { e ->
                func("DATE_DIFF", e.args["this"], e.args["expression"], unitToVar(e))
            }
            reg(DateFromParts::class) { e ->
                func("DATE", e.args["year"], e.args["month"], e.args["day"])
            }
            reg(TimeFromParts::class) { e ->
                func("TIME", e.args["hour"], e.args["min"], e.args["sec"])
            }
            reg(TimestampFromParts::class) { e ->
                func(
                    "DATETIME",
                    e.args["year"], e.args["month"], e.args["day"],
                    e.args["hour"], e.args["min"], e.args["sec"],
                )
            }
            reg(DateSub::class) { e -> bg().dateAddIntervalSql("DATE", "SUB", e) }
            reg(DatetimeAdd::class) { e -> bg().dateAddIntervalSql("DATETIME", "ADD", e) }
            reg(DatetimeSub::class) { e -> bg().dateAddIntervalSql("DATETIME", "SUB", e) }
            reg(DateFromUnixDate::class) { e -> bg().renameFuncSql("DATE_FROM_UNIX_DATE", e) }
            reg(GroupConcat::class) { e -> bg().groupConcatSql(e as GroupConcat) }
            reg(Hex::class) { e -> func("UPPER", func("TO_HEX", sql(e, "this"))) }
            reg(LowerHex::class) { e -> bg().renameFuncSql("TO_HEX", e) }
            reg(HexString::class) { e -> bg().hexstringSql(e as HexString, binaryFunctionRepr = "FROM_HEX") }
            reg(IntDiv::class) { e -> bg().renameFuncSql("DIV", e) }
            reg(Int64::class) { e -> bg().renameFuncSql("INT64", e) }
            reg(JSONBool::class) { e -> bg().renameFuncSql("BOOL", e) }
            reg(JSONFormat::class) { e ->
                func(
                    if (e.args["to_json"] == true) "TO_JSON" else "TO_JSON_STRING",
                    e.args["this"],
                    e.args["options"],
                )
            }
            reg(JSONKeysAtDepth::class) { e -> bg().renameFuncSql("JSON_KEYS", e) }
            reg(JSONValueArray::class) { e -> bg().renameFuncSql("JSON_VALUE_ARRAY", e) }
            reg(MD5::class) { e -> func("TO_HEX", func("MD5", e.args["this"])) }
            reg(MD5Digest::class) { e -> bg().renameFuncSql("MD5", e) }
            reg(Normalize::class) { e ->
                func(
                    if (e.args["is_casefold"] == true) "NORMALIZE_AND_CASEFOLD" else "NORMALIZE",
                    e.args["this"],
                    e.args["form"],
                )
            }
            reg(PartitionedByProperty::class) { e -> "PARTITION BY ${sql(e, "this")}" }
            reg(RegexpExtract::class) { e ->
                func(
                    "REGEXP_EXTRACT",
                    e.args["this"], e.args["expression"],
                    e.args["position"], e.args["occurrence"],
                )
            }
            reg(RegexpExtractAll::class) { e ->
                func("REGEXP_EXTRACT_ALL", e.args["this"], e.args["expression"])
            }
            reg(RegexpLike::class) { e -> bg().renameFuncSql("REGEXP_CONTAINS", e) }
            reg(Rollback::class) { _ -> "ROLLBACK TRANSACTION" }
            reg(ParseTime::class) { e -> func("PARSE_TIME", bg().formatTime(e), e.args["this"]) }
            reg(ParseDatetime::class) { e -> func("PARSE_DATETIME", bg().formatTime(e), e.args["this"]) }
            // sqlglot bigquery order: [explode_projection_to_unnest(), unqualify_unnest,
            // eliminate_distinct_on, _alias_ordered_group, eliminate_semi_and_anti_joins].
            // explode_projection_to_unnest and _alias_ordered_group remain NOT PORTED
            // (ledgered). unqualify_unnest is also NOT wired here: our parser stores an
            // explicit UNNEST alias in TableAlias.this (sqlglot's bigquery parser moves it
            // to TableAlias.columns via _implicit_unnests_to_explicit), so sqlglot's
            // .alias-based unnest-alias collection is a no-op there while ours would
            // over-strip `h.c2` -> `c2`. Porting it cleanly needs the parser-side implicit
            // unnest rewrite; left ledgered.
            reg(Select::class) { e ->
                var s = eliminateDistinctOn(e)
                s = eliminateSemiAndAntiJoins(s)
                selectSql(s as Select)
            }
            reg(SHA::class) { e -> bg().renameFuncSql("SHA1", e) }
            reg(SHA1Digest::class) { e -> bg().renameFuncSql("SHA1", e) }
            reg(StabilityProperty::class) { e ->
                if (e.name == "IMMUTABLE") "DETERMINISTIC" else "NOT DETERMINISTIC"
            }
            reg(dev.brikk.house.sql.ast.String::class) { e -> bg().renameFuncSql("STRING", e) }
            reg(SessionUser::class) { _ -> "SESSION_USER()" }
            reg(TimeAdd::class) { e -> bg().dateAddIntervalSql("TIME", "ADD", e) }
            reg(TimeSub::class) { e -> bg().dateAddIntervalSql("TIME", "SUB", e) }
            reg(TimestampAdd::class) { e -> bg().dateAddIntervalSql("TIMESTAMP", "ADD", e) }
            reg(TimestampDiff::class) { e -> bg().renameFuncSql("TIMESTAMP_DIFF", e) }
            reg(TimestampSub::class) { e -> bg().dateAddIntervalSql("TIMESTAMP", "SUB", e) }
            reg(Transaction::class) { _ -> "BEGIN TRANSACTION" }
            reg(TsOrDsToTime::class) { e -> bg().renameFuncSql("TIME", e) }
            reg(TsOrDsToDatetime::class) { e -> bg().renameFuncSql("DATETIME", e) }
            reg(TsOrDsToTimestamp::class) { e -> bg().renameFuncSql("TIMESTAMP", e) }
            reg(Unhex::class) { e -> bg().renameFuncSql("FROM_HEX", e) }
            reg(UnixDate::class) { e -> bg().renameFuncSql("UNIX_DATE", e) }
            reg(Uuid::class) { _ -> "GENERATE_UUID()" }
            reg(UnixToTime::class) { e -> bg().unixToTimeSql(e as UnixToTime) }
            reg(WeekStart::class) { e -> func("WEEK", e.args["this"]) }
            reg(CollateProperty::class) { e ->
                if (e.args["default"] == true) "DEFAULT COLLATE ${sql(e, "this")}"
                else "COLLATE ${sql(e, "this")}"
            }
            reg(VariancePop::class) { e -> bg().renameFuncSql("VAR_POP", e) }
            reg(SafeDivide::class) { e -> bg().renameFuncSql("SAFE_DIVIDE", e) }
            reg(SafeFunc::class) { e -> "SAFE.${sql(e, "this")}" }
            reg(NetFunc::class) { e -> "NET.${sql(e, "this")}" }
            reg(StrToDate::class) { e -> bg().strToDatetimeSql(e) }
            reg(StrToTime::class) { e -> bg().strToDatetimeSql(e) }
            reg(TimeToStr::class) { e -> bg().timeToStrSql(e as TimeToStr) }
            // method-dispatch overrides for base classes:
            reg(DateTrunc::class) { e -> bg().datetruncSql(e as DateTrunc) }
            reg(Mod::class) { e -> bg().modSql(e as Mod) }
            reg(EQ::class) { e -> bg().eqSql(e as EQ) }
            reg(Contains::class) { e -> bg().containsSql(e as Contains) }
        }
    }
}
