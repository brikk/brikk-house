package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.BigqueryTokenizerTables
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set

private fun seqGet(a: List<Expression?>, index: kotlin.Int): Expression? = a.getOrNull(index)

// sqlglot: dialect.build_formatted_time applied with BigQuery's TIME_MAPPING.
internal fun bqFormatTime(expression: Expression?): Expression? {
    if (expression is Literal && expression.isString) {
        val converted = formatTimeString(expression.thisArg as? String, BigqueryDialect.TIME_MAPPING)
        return Literal(args("this" to converted, "is_string" to true))
    }
    return expression
}

// sqlglot: dialect.build_formatted_time(expr_type)([fmt, this]) — TimeToStr with format.
private fun buildFormattedTime(
    factory: (Args) -> Expression,
    thisIdx: kotlin.Int,
    fmtIdx: kotlin.Int,
): (List<Expression?>) -> Expression = { a ->
    factory(args("this" to seqGet(a, thisIdx), "format" to bqFormatTime(seqGet(a, fmtIdx))))
}

// sqlglot: parsers.bigquery.build_date_diff
private fun buildDateDiff(a: List<Expression?>): Expression {
    val expr = DateDiff(
        args(
            "this" to seqGet(a, 0),
            "expression" to seqGet(a, 1),
            "unit" to seqGet(a, 2),
            "date_part_boundary" to true,
        )
    )
    val unit = expr.args["unit"]
    if (unit is Var && unit.name.uppercase() == "WEEK") {
        expr.set("unit", WeekStart(args("this" to Var(args("this" to "SUNDAY")))))
    }
    return expr
}

// sqlglot: parsers.bigquery.build_date_delta_with_interval (no default_unit)
private fun buildDateDeltaWithInterval(factory: (Args) -> Expression): (List<Expression?>) -> Expression = { a ->
    if (a.size < 2) throw ParseError("INTERVAL expression expected")
    val interval = a[1]
    if (interval !is Interval) throw ParseError("INTERVAL expression expected but got '$interval'")
    val unit = interval.args["unit"] as? Expression
    val unitStr: Expression =
        if (unit == null) Literal.string("DAY")
        else if (unit is Var || unit is Literal) Literal.string(unit.name)
        else unit
    factory(args("this" to a[0], "expression" to interval.thisArg, "unit" to unitStr))
}

// sqlglot: parsers.bigquery._build_date
private fun buildDate(a: List<Expression?>): Expression =
    if (a.size == 3) {
        DateFromParts(args("year" to seqGet(a, 0), "month" to seqGet(a, 1), "day" to seqGet(a, 2)))
    } else {
        Date(args("this" to seqGet(a, 0), "zone" to seqGet(a, 1)))
    }

// sqlglot: parsers.bigquery._build_datetime
private fun buildDatetime(a: List<Expression?>): Expression = when (a.size) {
    1 -> TsOrDsToDatetime(args("this" to seqGet(a, 0)))
    2 -> Datetime(args("this" to seqGet(a, 0), "zone" to seqGet(a, 1)))
    else -> TimestampFromParts(
        args(
            "year" to seqGet(a, 0), "month" to seqGet(a, 1), "day" to seqGet(a, 2),
            "hour" to seqGet(a, 3), "min" to seqGet(a, 4), "sec" to seqGet(a, 5),
        )
    )
}

// sqlglot: parsers.bigquery._build_time
private fun buildTime(a: List<Expression?>): Expression = when (a.size) {
    1 -> TsOrDsToTime(args("this" to seqGet(a, 0)))
    2 -> Time(args("this" to seqGet(a, 0), "zone" to seqGet(a, 1)))
    else -> TimeFromParts(
        args("hour" to seqGet(a, 0), "min" to seqGet(a, 1), "sec" to seqGet(a, 2))
    )
}

// sqlglot: parsers.bigquery._build_timestamp
private fun buildTimestamp(a: List<Expression?>): Expression =
    Timestamp(args("this" to seqGet(a, 0), "zone" to seqGet(a, 1), "with_tz" to true))

// sqlglot: parsers.bigquery._build_to_hex
private fun buildToHex(a: List<Expression?>): Expression {
    val arg = seqGet(a, 0)
    return if (arg is MD5Digest) MD5(args("this" to arg.thisArg)) else LowerHex(args("this" to arg))
}

// sqlglot: parsers.bigquery._build_contains_substring
private fun buildContainsSubstring(a: List<Expression?>): Expression =
    Contains(
        args(
            "this" to Lower(args("this" to seqGet(a, 0))),
            "expression" to Lower(args("this" to seqGet(a, 1))),
            "json_scope" to seqGet(a, 2),
        )
    )

// sqlglot: parsers.bigquery._build_levenshtein
private fun buildLevenshtein(a: List<Expression?>): Expression {
    val maxDist = seqGet(a, 2)
    return Levenshtein(
        args(
            "this" to seqGet(a, 0),
            "expression" to seqGet(a, 1),
            "max_dist" to (maxDist?.args?.get("expression")),
        )
    )
}

// sqlglot: parser.build_extract_json_with_path(expr_type) with default "$" path.
private fun buildExtractJsonWithDefaultPath(
    factory: (Args) -> Expression,
): (List<Expression?>) -> Expression = { a0 ->
    val a = if (a0.size == 1) a0 + listOf<Expression?>(Literal.string("$")) else a0
    factory(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
}

// sqlglot: parser.build_extract_json_with_path (no default)
private fun buildExtractJsonWithPath(
    factory: (Args) -> Expression,
): (List<Expression?>) -> Expression = { a ->
    factory(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
}

// sqlglot: parsers.bigquery._build_json_strip_nulls
private fun buildJsonStripNulls(a: List<Expression?>): Expression {
    val expression = JSONStripNulls(args("this" to seqGet(a, 0)))
    for (arg in a.drop(1)) {
        if (arg is Kwarg) {
            expression.set((arg.thisArg as? Expression)?.name?.lowercase() ?: "", arg)
        } else {
            expression.set("expression", arg)
        }
    }
    return expression
}

// sqlglot: parsers.bigquery._build_parse_date — build_formatted_time([value, format])
private fun buildParseDate(a: List<Expression?>): Expression {
    val this_ = StrToDate(args("this" to seqGet(a, 1), "format" to bqFormatTime(seqGet(a, 0))))
    this_.set("default_year", Literal.number("1970"))
    return this_
}

// sqlglot: parsers.bigquery._build_parse_timestamp
private fun buildParseTimestamp(a: List<Expression?>): Expression {
    val this_ = StrToTime(args("this" to seqGet(a, 1), "format" to bqFormatTime(seqGet(a, 0))))
    this_.set("zone", seqGet(a, 2))
    this_.set("default_year", Literal.number("1970"))
    return this_
}

// sqlglot: parsers.bigquery._build_parse_datetime
private fun buildParseDatetime(a: List<Expression?>): Expression {
    val this_ = ParseDatetime(args("this" to seqGet(a, 1), "format" to bqFormatTime(seqGet(a, 0))))
    this_.set("default_year", Literal.number("1970"))
    return this_
}

// sqlglot: parsers.bigquery._build_regexp_extract
private fun buildRegexpExtract(
    all: Boolean,
    defaultGroup: Expression? = null,
): (List<Expression?>) -> Expression = { a ->
    // group detection from a compiled regex (single capture group) is best-effort;
    // ported behavior: no group flag unless the pattern trivially has one group.
    val kwargs = args(
        "this" to seqGet(a, 0),
        "expression" to seqGet(a, 1),
        "position" to seqGet(a, 2),
        "occurrence" to seqGet(a, 3),
        "group" to defaultGroup,
    )
    if (all) RegexpExtractAll(kwargs) else RegexpExtract(kwargs)
}

private val binaryFromFunctionIntDiv: (List<Expression?>) -> Expression = { a ->
    IntDiv(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
}

/**
 * Port of sqlglot's BigQueryParser (reference/sqlglot/sqlglot/parsers/bigquery.py class
 * BigQueryParser(parser.Parser)). Function/parser-table merges live in [BigqueryParserTables].
 *
 * NOT PORTED (ledgered — need infrastructure the port lacks):
 *  - _parse_table_part dashed-table-name assembly, INFORMATION_SCHEMA merging, and the
 *    project/dataset/table dotted-name unraveling in _parse_table_parts/_parse_column
 *    (position tracking + _split_qualified_name);
 *  - _parse_unnest's annotate-driven explode_array flag (needs mid-parse annotation);
 *  - ML.* / AI.* function parsers (VECTOR_SEARCH, FORECAST, FEATURES_AT_TIME,
 *    GENERATE_x), which use custom TVF grammar.
 */
// sqlglot: parsers.bigquery.BigQueryParser
open class BigqueryParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = BigqueryTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    override val dialect: Dialect get() = Dialects.BIGQUERY

    // sqlglot: BigQueryParser.PREFIXED_PIVOT_COLUMNS = True
    override val prefixedPivotColumns: Boolean get() = true

    // sqlglot: BigQueryParser.LOG_DEFAULTS_TO_LN = True
    override val logDefaultsToLn: Boolean get() = true

    // sqlglot: BigQueryParser.SUPPORTS_IMPLICIT_UNNEST = True
    override val supportsImplicitUnnest: Boolean get() = true

    // sqlglot: BigQueryParser.JOINS_HAVE_EQUAL_PRECEDENCE = True
    override val joinsHaveEqualPrecedence: Boolean get() = true

    // sqlglot: BigQuery.SUPPORTS_USER_DEFINED_TYPES = False
    override val supportsUserDefinedTypes: Boolean get() = false

    // sqlglot: BigQuery.PRESERVE_ORIGINAL_NAMES = True
    override val preserveOriginalNames: Boolean get() = true

    // sqlglot: BigQuery.ALIAS_POST_VERSION = False
    override val aliasPostVersion: Boolean get() = false

    // sqlglot: BigQuery.JSON_EXTRACT_SCALAR_SCALAR_ONLY = True
    override val jsonExtractScalarScalarOnly: Boolean get() = true

    // sqlglot: BigQueryParser.ID_VAR_TOKENS = {*base, GRANT} - {ASC, DESC}
    override val idVarTokens: Set<TokenType>
        get() = (BaseParserTables.ID_VAR_TOKENS + TokenType.GRANT) - setOf(TokenType.ASC, TokenType.DESC)

    // sqlglot: BigQueryParser.ALIAS_TOKENS
    override val aliasTokens: Set<TokenType>
        get() = (BaseParserTables.ALIAS_TOKENS + TokenType.GRANT) - setOf(TokenType.ASC, TokenType.DESC)

    // sqlglot: BigQueryParser.TABLE_ALIAS_TOKENS
    override val tableAliasTokens: Set<TokenType>
        get() = (BaseParserTables.TABLE_ALIAS_TOKENS + setOf(TokenType.ANTI, TokenType.GRANT, TokenType.SEMI)) -
            setOf(TokenType.ASC, TokenType.DESC)

    // sqlglot: BigQueryParser.NESTED_TYPE_TOKENS (+ TABLE)
    override val nestedTypeTokens: Set<TokenType>
        get() = BaseParserTables.NESTED_TYPE_TOKENS + TokenType.TABLE

    // sqlglot: BigQueryParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = BigqueryParserTables.FUNCTIONS

    // sqlglot: BYTE_STRING_IS_BYTES_TYPE / HEX_STRING_IS_INTEGER_TYPE — bigquery tags
    // b'..' with is_bytes and 0x.. with is_integer.
    private val bqStringOverrides: Map<TokenType, (Parser, Token) -> Expression?> =
        mapOf(
            TokenType.BYTE_STRING to { p, token ->
                p.expression(ByteString(args("this" to token.text, "is_bytes" to true)), token)
            },
            TokenType.HEX_STRING to { p, token ->
                p.expression(HexString(args("this" to token.text, "is_integer" to true)), token)
            },
        )

    override val numericParsers: Map<TokenType, (Parser, Token) -> Expression?>
        get() = super.numericParsers + bqStringOverrides

    override val primaryParsers: Map<TokenType, (Parser, Token) -> Expression?>
        get() = super.primaryParsers + bqStringOverrides

    // sqlglot: BigQueryParser.FUNCTION_PARSERS (- TRIM, + ARRAY/JSON_ARRAY/MAKE_INTERVAL/TRANSLATE)
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = super.functionParsers.filterKeys { it != "TRIM" } +
            mapOf<String, (Parser) -> Expression?>(
                "ARRAY" to { p ->
                    p.expression(
                        dev.brikk.house.sql.ast.Array(
                            args(
                                "expressions" to listOfNotNull(p.parseStatement()),
                                "struct_name_inheritance" to true,
                            )
                        )
                    )
                },
                "JSON_ARRAY" to { p ->
                    p.expression(
                        JSONArray(args("expressions" to p.parseCsv { p.parseBitwise() }))
                    )
                },
                "MAKE_INTERVAL" to { p -> (p as BigqueryParser).parseMakeInterval() },
                "TRANSLATE" to { p -> (p as BigqueryParser).parseTranslate() },
            )

    // sqlglot: BigQueryParser.NO_PAREN_FUNCTIONS (+ CURRENT_DATETIME)
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = super.noParenFunctions + mapOf<TokenType, () -> Expression>(
            TokenType.CURRENT_DATETIME to { CurrentDatetime() },
        )

    // sqlglot: BigQueryParser.RANGE_PARSERS (- OVERLAPS)
    override val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?>
        get() = super.rangeParsers.filterKeys { it != TokenType.OVERLAPS }

    // sqlglot: BigQueryParser.STATEMENT_PARSERS
    override val statementParsers: Map<TokenType, (Parser) -> Expression>
        get() = super.statementParsers + mapOf<TokenType, (Parser) -> Expression>(
            TokenType.ELSE to { p -> p.parseAsCommand(p.prevToken) },
            TokenType.END to { p -> p.parseAsCommand(p.prevToken) },
            TokenType.EXPORT to { p -> (p as BigqueryParser).parseExportData() },
        )

    // sqlglot: BigQueryParser.PROPERTY_PARSERS
    override val propertyParsers: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>
        get() = super.propertyParsers + mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
            "NOT DETERMINISTIC" to { _, _ -> StabilityProperty(args("this" to Literal.string("VOLATILE"))) },
            "OPTIONS" to { p, _ -> p.parseWithProperty() },
        )

    // sqlglot: BigQueryParser._parse_table_part — dashed table names (project-id.dataset)
    // and numeric-suffixed parts (`foo.bar.25`).
    override fun parseTablePart(schema: Boolean): Expression? {
        var this_: Expression? = super.parseTablePart(schema) ?: parseNumber()

        if (this_ is Identifier) {
            var tableName = this_.name
            while (match(TokenType.DASH, advance = false) && nextToken.exists) {
                val start = currToken
                while (isConnected() && !matchSet(DASHED_TABLE_PART_FOLLOW_TOKENS, advance = false)) {
                    advance()
                }
                if (start === currToken) break
                tableName += findSql(start, prevToken)
            }
            this_ = Identifier(args("this" to tableName, "quoted" to this_.args["quoted"]))
                .updatePositions(this_)
        } else if (this_ is Literal) {
            var tableName = this_.name
            if (isConnected() && parseVar(anyToken = true) != null) {
                tableName += prevToken.text
            }
            this_ = Identifier(args("this" to tableName, "quoted" to true)).updatePositions(this_)
        }

        return this_
    }

    // sqlglot: BigQueryParser._parse_export_data
    open fun parseExportData(): Expression {
        matchTextSeq("DATA")
        val connection = if (matchTextSeq("WITH", "CONNECTION")) parseTableParts() else null
        return expression(
            Export(
                args(
                    "connection" to connection,
                    "options" to parseProperties(),
                    "this" to if (matchTextSeq("AS")) parseSelect(nested = true) else null,
                )
            )
        )
    }

    // sqlglot: BigQueryParser._parse_json_object — converts BQ signature-2 into canonical.
    override fun parseJsonObject(agg: Boolean): Expression {
        val jsonObject = super.parseJsonObject(agg)
        val arrayKvPair = jsonObject.expressionsArg.filterIsInstance<Expression>().firstOrNull()
        val thisArr = arrayKvPair?.args?.get("this") as? dev.brikk.house.sql.ast.Array
        val exprArr = arrayKvPair?.args?.get("expression") as? dev.brikk.house.sql.ast.Array
        if (arrayKvPair != null && thisArr != null && exprArr != null) {
            val keys = thisArr.expressionsArg.filterIsInstance<Expression>()
            val values = exprArr.expressionsArg.filterIsInstance<Expression>()
            jsonObject.set(
                "expressions",
                keys.zip(values).map { (k, v) -> JSONKeyValue(args("this" to k, "expression" to v)) },
            )
        }
        return jsonObject
    }

    // sqlglot: BigQueryParser._parse_bracket — OFFSET/ORDINAL/SAFE_* bracket forms.
    override fun parseBracket(this_: Expression?): Expression? {
        val bracket = super.parseBracket(this_)

        if (bracket is dev.brikk.house.sql.ast.Array) {
            bracket.set("struct_name_inheritance", true)
        }

        if (this_ === bracket) return bracket

        if (bracket is Bracket) {
            for (expr in bracket.expressionsArg.filterIsInstance<Expression>()) {
                val name = expr.name.uppercase()
                val exprs = expr.expressionsArg.filterIsInstance<Expression>()
                val offsets = BRACKET_OFFSETS[name]
                if (offsets == null || exprs.isEmpty()) break
                bracket.set("offset", offsets.first)
                bracket.set("safe", offsets.second)
                expr.replace(exprs[0])
            }
        }

        return bracket
    }

    // sqlglot: BigQueryParser._parse_make_interval
    open fun parseMakeInterval(): Expression {
        val expr = MakeInterval()
        for (argKey0 in MAKE_INTERVAL_KWARGS) {
            var argKey = argKey0
            val value = parseLambda() ?: break
            if (value is Kwarg) {
                argKey = (value.thisArg as? Expression)?.name ?: argKey
            }
            expr.set(argKey, value)
            match(TokenType.COMMA)
        }
        return expr
    }

    // sqlglot: BigQueryParser._parse_translate — ML.TRANSLATE routed to ML parser (not
    // ported: ML.* TVF grammar), otherwise Translate.from_arg_list.
    open fun parseTranslate(): Expression {
        val a = parseFunctionArgs()
        return Translate(
            args(
                "this" to a.getOrNull(0),
                "from_" to a.getOrNull(1),
                "to" to a.getOrNull(2),
            )
        )
    }

    // sqlglot: BigQueryParser._parse_column_ops — SAFE./NET. prefixed functions become
    // SafeFunc/NetFunc. (AI./ML. re-parse not ported: custom signatures.)
    override fun parseColumnOps(this_: Expression?): Expression? {
        val funcIndex = index + 1
        var result = super.parseColumnOps(this_)

        if (result is Dot && result.args["expression"] is Func) {
            val prefix = (result.args["this"] as? Expression)?.name?.uppercase()
            val func: ((Args) -> Expression)? = when (prefix) {
                "NET" -> { a -> NetFunc(a) }
                "SAFE" -> { a -> SafeFunc(a) }
                else -> null
            }
            if (func != null) {
                retreat(funcIndex)
                result = func(args("this" to parseFunction(anyToken = true)))
            }
        }

        return result
    }

    companion object {
        // sqlglot: parsers.bigquery.MAKE_INTERVAL_KWARGS
        val MAKE_INTERVAL_KWARGS: List<String> =
            listOf("year", "month", "day", "hour", "minute", "second")

        // sqlglot: BigQueryParser.DASHED_TABLE_PART_FOLLOW_TOKENS
        val DASHED_TABLE_PART_FOLLOW_TOKENS: Set<TokenType> = setOf(
            TokenType.DOT, TokenType.L_PAREN, TokenType.R_PAREN,
        )

        // sqlglot: BigQueryParser.BRACKET_OFFSETS
        val BRACKET_OFFSETS: Map<String, Pair<kotlin.Int, Boolean>> = mapOf(
            "OFFSET" to (0 to false),
            "ORDINAL" to (1 to false),
            "SAFE_OFFSET" to (0 to true),
            "SAFE_ORDINAL" to (1 to true),
        )
    }
}

/**
 * Merged parser tables for BigQuery (sqlglot: BigQueryParser class-level dict merges over
 * the base Parser). Kept in an object so the merges happen once.
 */
object BigqueryParserTables {

    // sqlglot: BigQueryParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        for ((k, v) in BaseParserTables.FUNCTIONS) if (k != "SEARCH") put(k, v)
        put("APPROX_TOP_COUNT") { a -> ApproxTopK(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("BIT_AND") { a -> BitwiseAndAgg(args("this" to seqGet(a, 0))) }
        put("BIT_OR") { a -> BitwiseOrAgg(args("this" to seqGet(a, 0))) }
        put("BIT_XOR") { a -> BitwiseXorAgg(args("this" to seqGet(a, 0))) }
        put("BIT_COUNT") { a -> BitwiseCount(args("this" to seqGet(a, 0))) }
        put("BOOL") { a -> JSONBool(args("this" to seqGet(a, 0))) }
        put("CONTAINS_SUBSTR", ::buildContainsSubstring)
        put("DATE", ::buildDate)
        put("DATE_ADD", buildDateDeltaWithInterval { DateAdd(it) })
        put("DATE_DIFF", ::buildDateDiff)
        put("DATE_SUB", buildDateDeltaWithInterval { DateSub(it) })
        put("DATE_TRUNC") { a ->
            DateTrunc(args("unit" to seqGet(a, 1), "this" to seqGet(a, 0), "zone" to seqGet(a, 2)))
        }
        put("DATETIME", ::buildDatetime)
        put("DATETIME_ADD", buildDateDeltaWithInterval { DatetimeAdd(it) })
        put("DATETIME_SUB", buildDateDeltaWithInterval { DatetimeSub(it) })
        put("DIV", binaryFromFunctionIntDiv)
        put("EDIT_DISTANCE", ::buildLevenshtein)
        put("EMBED") { a -> AIEmbed(args("this" to seqGet(a, 0))) }
        put("FORMAT_DATE", buildFormattedTime({ TimeToStr(it) }, thisIdx = 1, fmtIdx = 0).wrapTsOrDs("TsOrDsToDate"))
        put("GENERATE") { a -> AIGenerate(args("this" to seqGet(a, 0))) }
        put("GENERATE_ARRAY") { a -> GenerateSeries(genSeriesArgs(a)) }
        put("JSON_EXTRACT_SCALAR", buildExtractJsonWithDefaultPath { JSONExtractScalar(it) })
        put("JSON_EXTRACT_ARRAY", buildExtractJsonWithDefaultPath { JSONExtractArray(it) })
        put("JSON_EXTRACT_STRING_ARRAY", buildExtractJsonWithDefaultPath { JSONValueArray(it) })
        put("JSON_KEYS") { a -> JSONKeysAtDepth(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("JSON_QUERY", buildExtractJsonWithPath { JSONExtract(it) })
        put("JSON_QUERY_ARRAY", buildExtractJsonWithDefaultPath { JSONExtractArray(it) })
        put("JSON_STRIP_NULLS", ::buildJsonStripNulls)
        put("JSON_VALUE", buildExtractJsonWithDefaultPath { JSONExtractScalar(it) })
        put("JSON_VALUE_ARRAY", buildExtractJsonWithDefaultPath { JSONValueArray(it) })
        put("LENGTH") { a -> Length(args("this" to seqGet(a, 0), "binary" to true)) }
        put("MD5") { a -> MD5Digest(args("this" to seqGet(a, 0))) }
        put("SHA1") { a -> SHA1Digest(args("this" to seqGet(a, 0))) }
        put("NORMALIZE_AND_CASEFOLD") { a ->
            Normalize(args("this" to seqGet(a, 0), "form" to seqGet(a, 1), "is_casefold" to true))
        }
        put("OCTET_LENGTH") { a -> ByteLength(args("this" to seqGet(a, 0))) }
        put("TO_HEX", ::buildToHex)
        put("PARSE_DATE", ::buildParseDate)
        put("PARSE_TIME", buildFormattedTime({ ParseTime(it) }, thisIdx = 1, fmtIdx = 0))
        put("PARSE_TIMESTAMP", ::buildParseTimestamp)
        put("PARSE_DATETIME", ::buildParseDatetime)
        put("REGEXP_CONTAINS") { a -> RegexpLike(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("REGEXP_EXTRACT", buildRegexpExtract(all = false))
        put("REGEXP_SUBSTR", buildRegexpExtract(all = false))
        put("REGEXP_EXTRACT_ALL", buildRegexpExtract(all = true, defaultGroup = Literal.number("0")))
        put("SHA256") { a -> SHA2Digest(args("this" to seqGet(a, 0), "length" to Literal.number("256"))) }
        put("SHA512") { a -> SHA2Digest(args("this" to seqGet(a, 0), "length" to Literal.number("512"))) }
        put("SIMILARITY") { a -> AISimilarity(args("this" to seqGet(a, 0))) }
        put("SPLIT") { a ->
            Split(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to (seqGet(a, 1) ?: Literal.string(",")),
                )
            )
        }
        put("STRPOS") { a -> StrPosition(args("this" to seqGet(a, 0), "substr" to seqGet(a, 1))) }
        put("TIME", ::buildTime)
        put("TIME_ADD", buildDateDeltaWithInterval { TimeAdd(it) })
        put("TIME_SUB", buildDateDeltaWithInterval { TimeSub(it) })
        put("TIMESTAMP", ::buildTimestamp)
        put("TIMESTAMP_ADD", buildDateDeltaWithInterval { TimestampAdd(it) })
        put("TIMESTAMP_SUB", buildDateDeltaWithInterval { TimestampSub(it) })
        put("TIMESTAMP_MICROS") { a -> UnixToTime(args("this" to seqGet(a, 0), "scale" to Literal.number("6"))) }
        put("TIMESTAMP_MILLIS") { a -> UnixToTime(args("this" to seqGet(a, 0), "scale" to Literal.number("3"))) }
        put("TIMESTAMP_SECONDS") { a -> UnixToTime(args("this" to seqGet(a, 0))) }
        put("TO_JSON") { a ->
            JSONFormat(args("this" to seqGet(a, 0), "options" to seqGet(a, 1), "to_json" to true))
        }
        put("TO_JSON_STRING") { a -> JSONFormat(args("this" to seqGet(a, 0), "options" to seqGet(a, 1))) }
        put("FORMAT_DATETIME", buildFormattedTime({ TimeToStr(it) }, thisIdx = 1, fmtIdx = 0).wrapTsOrDs("TsOrDsToDatetime"))
        put("FORMAT_TIMESTAMP", buildFormattedTime({ TimeToStr(it) }, thisIdx = 1, fmtIdx = 0).wrapTsOrDs("TsOrDsToTimestamp"))
        put("FORMAT_TIME", buildFormattedTime({ TimeToStr(it) }, thisIdx = 1, fmtIdx = 0).wrapTsOrDs("TsOrDsToTime"))
        put("FROM_HEX") { a -> Unhex(args("this" to seqGet(a, 0))) }
        put("WEEK") { a -> WeekStart(args("this" to Var(args("this" to seqGet(a, 0)?.name)))) }
    }

    // sqlglot: GENERATE_ARRAY -> exp.GenerateSeries.from_arg_list
    private fun genSeriesArgs(a: List<Expression?>): Args =
        args("start" to seqGet(a, 0), "end" to seqGet(a, 1), "step" to seqGet(a, 2))

    // sqlglot: _build_format_time wraps the this-arg in a TS_OR_DS type before build_formatted_time.
    private fun ((List<Expression?>) -> Expression).wrapTsOrDs(
        tsOrDsType: String,
    ): (List<Expression?>) -> Expression = { a ->
        val built = this(a) as TimeToStr
        val inner: Expression? = built.args["this"] as? Expression
        val wrapped: Expression = when (tsOrDsType) {
            "TsOrDsToDate" -> TsOrDsToDate(args("this" to inner))
            "TsOrDsToDatetime" -> TsOrDsToDatetime(args("this" to inner))
            "TsOrDsToTimestamp" -> TsOrDsToTimestamp(args("this" to inner))
            else -> TsOrDsToTime(args("this" to inner))
        }
        built.set("this", wrapped)
        built.set("zone", seqGet(a, 2))
        built
    }
}
