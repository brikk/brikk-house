package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.ArrayOverlaps
import dev.brikk.house.sql.ast.ArrayPrepend
import dev.brikk.house.sql.ast.BitwiseAndAgg
import dev.brikk.house.sql.ast.BitwiseNot
import dev.brikk.house.sql.ast.BitwiseOrAgg
import dev.brikk.house.sql.ast.BitwiseXor
import dev.brikk.house.sql.ast.BitwiseXorAgg
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ComputedColumnConstraint
import dev.brikk.house.sql.ast.Concat
import dev.brikk.house.sql.ast.ConcatWs
import dev.brikk.house.sql.ast.CurrentCatalog
import dev.brikk.house.sql.ast.CurrentSchema
import dev.brikk.house.sql.ast.CurrentTimestamp
import dev.brikk.house.sql.ast.CurrentVersion
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.ExplodingGenerateSeries
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Extract
import dev.brikk.house.sql.ast.Getbit
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.InOutColumnConstraint
import dev.brikk.house.sql.ast.IntDiv
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.JSONArrayAgg
import dev.brikk.house.sql.ast.JSONBExists
import dev.brikk.house.sql.ast.JSONBObjectAgg
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractScalar
import dev.brikk.house.sql.ast.JSONObjectAgg
import dev.brikk.house.sql.ast.JSONPath
import dev.brikk.house.sql.ast.JSONPathKey
import dev.brikk.house.sql.ast.JSONPathRoot
import dev.brikk.house.sql.ast.JSONPathSubscript
import dev.brikk.house.sql.ast.Length
import dev.brikk.house.sql.ast.Levenshtein
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Localtime
import dev.brikk.house.sql.ast.Localtimestamp
import dev.brikk.house.sql.ast.MatchAgainst
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Pow
import dev.brikk.house.sql.ast.RegexpReplace
import dev.brikk.house.sql.ast.SHA2
import dev.brikk.house.sql.ast.SessionUser
import dev.brikk.house.sql.ast.SetConfigProperty
import dev.brikk.house.sql.ast.StrToDate
import dev.brikk.house.sql.ast.StrToTime
import dev.brikk.house.sql.ast.TimeFromParts
import dev.brikk.house.sql.ast.TimeToStr
import dev.brikk.house.sql.ast.TimestampFromParts
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.UnixToTime
import dev.brikk.house.sql.ast.Uuid
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.Variadic
import dev.brikk.house.sql.ast.WidthBucket
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.applyTimeUnitCoercion
import dev.brikk.house.sql.parser.binaryRangeParser
import dev.brikk.house.sql.parser.formatTimeString
import dev.brikk.house.sql.parser.fromArgList

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: helper.is_int
private fun isInt(text: String): Boolean = text.toLongOrNull() != null

// sqlglot: Dialect["postgres"].format_time on a parsed expression
private fun postgresFormatTime(expression: Expression?): Expression? {
    if (expression is Literal && expression.isString) {
        val converted = formatTimeString(expression.thisArg as? String, PostgresDialect.TIME_MAPPING)
        return Literal(args("this" to converted, "is_string" to true))
    }
    return expression
}

// sqlglot: exp.INTERVAL_STRING_RE (used by exp.to_interval through maybe_parse)
private val INTERVAL_STRING_RE = Regex("\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)\\s*")

// sqlglot: exp.to_interval — builds INTERVAL '1' DAY from a string like '1 day'
private fun toInterval(text: String): Expression {
    val match = INTERVAL_STRING_RE.find(text)
    return if (match != null) {
        Interval(
            args(
                "this" to Literal.string(match.groupValues[1]),
                "unit" to Var(args("this" to match.groupValues[2].uppercase())),
            )
        )
    } else {
        Interval(args("this" to Literal.string(text.trim())))
    }
}

// sqlglot: parsers.postgres._build_generate_series
private fun buildGenerateSeries(a: List<Expression?>): Expression {
    // The goal is to convert step values like '1 day' or INTERVAL '1 day' into INTERVAL '1' day
    // Note: postgres allows calls with just two arguments -- the "step" argument defaults to 1
    val argsList = a.toMutableList()
    val step = seqGet(argsList, 2)
    if (step != null) {
        if (step.isString) {
            argsList[2] = toInterval(step.name)
        } else if (step is Interval && step.args["unit"] == null) {
            argsList[2] = toInterval((step.thisArg as? Expression)?.name ?: "")
        }
    }

    return fromArgList(
        listOf("start", "end", "step", "is_end_exclusive"), false
    ) { ExplodingGenerateSeries(it) }(argsList)
}

// sqlglot: parsers.postgres._build_to_timestamp
private fun buildToTimestamp(a: List<Expression?>): Expression {
    // TO_TIMESTAMP accepts either a single double argument or (text, text)
    if (a.size == 1) {
        return fromArgList(
            listOf("this", "scale", "zone", "hours", "minutes", "format", "target_type"), false
        ) { UnixToTime(it) }(a)
    }

    // sqlglot: build_formatted_time(exp.StrToTime)
    return StrToTime(args("this" to seqGet(a, 0), "format" to postgresFormatTime(seqGet(a, 1))))
}

// sqlglot: parsers.postgres._build_regexp_replace — the annotate_types call is
// approximated: annotate_types resolves string literals to TEXT, which is the only
// case the fallback needs (non-literal flags stay on the from_arg_list path).
private fun buildRegexpReplace(a: List<Expression?>): Expression {
    var regexpReplace: Expression? = null
    if (a.size > 3) {
        val last = a.last()
        if (last != null && !isInt(last.name)) {
            if (last is Literal && last.isString) {
                regexpReplace = fromArgList(
                    listOf("this", "expression", "replacement", "position", "occurrence"), false
                ) { RegexpReplace(it) }(a.dropLast(1))
                regexpReplace.set("modifiers", last)
            }
        }
    }

    val result = regexpReplace ?: fromArgList(
        listOf("this", "expression", "replacement", "position", "occurrence", "modifiers"), false
    ) { RegexpReplace(it) }(a)
    result.set("single_replace", true)
    return result
}

// sqlglot: parsers.postgres._build_levenshtein_less_equal
private fun buildLevenshteinLessEqual(a: List<Expression?>): Expression {
    // Postgres has two signatures for levenshtein_less_equal function, but in both cases
    // max_dist is the last argument
    val argsList = a.dropLast(1)
    val maxDist = a.last()

    return Levenshtein(
        args(
            "this" to seqGet(argsList, 0),
            "expression" to seqGet(argsList, 1),
            "ins_cost" to seqGet(argsList, 2),
            "del_cost" to seqGet(argsList, 3),
            "sub_cost" to seqGet(argsList, 4),
            "max_dist" to maxDist,
        )
    )
}

// sqlglot: dialect.build_json_extract_path (zero_based_indexing=true, json_type=null)
internal fun buildJsonExtractPath(
    scalar: Boolean,
    arrowReqJsonType: Boolean = false,
): (List<Expression?>) -> Expression = { fnArgs ->
    var fallback = false
    val segments = mutableListOf<Expression>(JSONPathRoot())
    for (arg in fnArgs.drop(1)) {
        if (arg !is Literal) {
            // We use the fallback parser because we can't really transpile non-literals safely
            fallback = true
            break
        }
        val text = arg.name
        if (isInt(text) && (!arrowReqJsonType || !arg.isString)) {
            segments.add(JSONPathSubscript(args("this" to text.toInt())))
        } else {
            segments.add(JSONPathKey(args("this" to text)))
        }
    }

    if (fallback) {
        // sqlglot: expr_type.from_arg_list(args)
        val kwargs = args("this" to seqGet(fnArgs, 0), "expression" to seqGet(fnArgs, 1))
        val node: Expression = if (scalar) JSONExtractScalar(kwargs) else JSONExtract(kwargs)
        if (fnArgs.size > 2) node.set("expressions", fnArgs.drop(2))
        node
    } else {
        val kwargs = args(
            "this" to seqGet(fnArgs, 0),
            "expression" to JSONPath(args("expressions" to segments)),
            "only_json_types" to arrowReqJsonType,
        )
        if (scalar) JSONExtractScalar(kwargs) else JSONExtract(kwargs)
    }
}

/**
 * Port of sqlglot's PostgresParser (reference/sqlglot/sqlglot/parsers/postgres.py).
 * Table merges live in [PostgresParserTables]; flag overrides and method overrides below.
 */
// sqlglot: parsers.postgres.PostgresParser
open class PostgresParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.PostgresTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: PostgresParser.SUPPORTS_OMITTED_INTERVAL_SPAN_UNIT = True
    override val supportsOmittedIntervalSpanUnit: Boolean get() = true

    // sqlglot: PostgresParser.JSON_ARROWS_REQUIRE_JSON_TYPE = True
    override val jsonArrowsRequireJsonType: Boolean get() = true

    // sqlglot: Postgres.INDEX_OFFSET = 1
    override val indexOffset: Int get() = 1

    // sqlglot: Postgres.TYPED_DIVISION = True
    override val typedDivision: Boolean get() = true

    // sqlglot: Postgres.NULL_ORDERING = "nulls_are_large"
    override val nullOrdering: String get() = "nulls_are_large"

    // sqlglot: Postgres.SUPPORTS_LIMIT_ALL = True
    override val supportsLimitAll: Boolean get() = true

    // sqlglot: Postgres.TABLESAMPLE_SIZE_IS_PERCENT = True
    override val tablesampleSizeIsPercent: Boolean get() = true

    // Table overrides (sqlglot: PostgresParser class-level dict/set merges)

    override val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = PostgresParserTables.PROPERTY_PARSERS

    override val placeholderParsers: Map<TokenType, (Parser) -> Expression?>
        get() = PostgresParserTables.PLACEHOLDER_PARSERS

    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = PostgresParserTables.FUNCTIONS

    override val noParenFunctionParsers: Map<String, (Parser) -> Expression?>
        get() = PostgresParserTables.NO_PAREN_FUNCTION_PARSERS

    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = PostgresParserTables.NO_PAREN_FUNCTIONS

    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = PostgresParserTables.FUNCTION_PARSERS

    override val bitwise: Map<TokenType, NodeFactory> get() = PostgresParserTables.BITWISE

    override val exponent: Map<TokenType, NodeFactory> get() = PostgresParserTables.EXPONENT

    override val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?>
        get() = PostgresParserTables.RANGE_PARSERS

    override val statementParsers: Map<TokenType, (Parser) -> Expression>
        get() = PostgresParserTables.STATEMENT_PARSERS

    override val unaryParsers: Map<TokenType, (Parser) -> Expression?>
        get() = PostgresParserTables.UNARY_PARSERS

    override val columnOperators: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?>
        get() = PostgresParserTables.COLUMN_OPERATORS

    // sqlglot: PostgresParser.ARG_MODE_TOKENS
    protected open val argModeTokens: kotlin.collections.Set<TokenType> = setOf(
        TokenType.IN, TokenType.OUT, TokenType.INOUT, TokenType.VARIADIC,
    )

    /**
     * sqlglot: PostgresParser._parse_parameter_mode — disambiguates between mode
     * keywords and identifiers with the same name.
     */
    protected fun parseParameterMode(): TokenType? {
        if (!matchSet(argModeTokens, advance = false) || !nextToken.exists) {
            return null
        }

        val modeToken = currToken

        // Check Pattern 1: MODE TYPE — if the next token parses as a built-in type,
        // the keyword is an identifier, not a mode
        val isFollowedByBuiltinType = tryParse(
            {
                advance()
                parseTypes(checkFunc = false, allowIdentifiers = false)
            },
            retreat = true,
        )
        if (isFollowedByBuiltinType != null) {
            return null // Pattern: "out INT" -> out is parameter name
        }

        // Check Pattern 2: MODE NAME TYPE
        if (nextToken.tokenType !in idVarTokens) {
            return null
        }

        val isFollowedByAnyType = tryParse(
            {
                advance(2)
                parseTypes(checkFunc = false, allowIdentifiers = true)
            },
            retreat = true,
        )

        if (isFollowedByAnyType != null) {
            return modeToken.tokenType // Pattern: "OUT x INT" -> OUT is mode
        }

        return null
    }

    // sqlglot: PostgresParser._create_mode_constraint
    protected fun createModeConstraint(paramMode: TokenType): Expression = expression(
        InOutColumnConstraint(
            args(
                "input_" to (paramMode in setOf(TokenType.IN, TokenType.INOUT)),
                "output" to (paramMode in setOf(TokenType.OUT, TokenType.INOUT)),
                "variadic" to (paramMode == TokenType.VARIADIC),
            )
        )
    )

    // sqlglot: PostgresParser._parse_function_parameter
    override fun parseFunctionParameter(): Expression? {
        val paramMode = parseParameterMode()

        if (paramMode != null) {
            advance()
        }

        // Parse parameter name and type
        val paramName = parseIdVar()
        val columnDef = parseColumnDef(paramName, computedColumn = false)

        // Attach mode as constraint
        if (paramMode != null && columnDef != null) {
            val constraint = createModeConstraint(paramMode)
            val constraints =
                (columnDef.args["constraints"] as? List<*>)?.toMutableList() ?: mutableListOf()
            constraints.add(0, constraint)
            columnDef.set("constraints", constraints)
        }

        return columnDef
    }

    // sqlglot: PostgresParser._parse_query_parameter
    open fun parseQueryParameter(): Expression? {
        val this_: Expression? = if (match(TokenType.L_PAREN, advance = false)) {
            parseWrapped({ parseIdVar() })
        } else {
            null
        }
        matchTextSeq("S")
        return expression(Placeholder(args("this" to this_)))
    }

    // sqlglot: PostgresParser._parse_date_part
    open fun parseDatePart(): Expression {
        var part = parseType()
        match(TokenType.COMMA)
        val value = parseBitwise()

        if (part != null && (part is Column || part is Literal)) {
            part = Var(args("this" to part.name))
        }

        return expression(Extract(args("this" to part, "expression" to value)))
    }

    // sqlglot: PostgresParser._parse_unique_key
    override fun parseUniqueKey(): Expression? = null

    // sqlglot: PostgresParser._parse_jsonb_exists
    open fun parseJsonbExists(): Expression = expression(
        JSONBExists(
            args(
                "this" to parseBitwise(),
                "path" to if (match(TokenType.COMMA)) toJsonPath(parseBitwise()) else null,
            )
        )
    )

    // sqlglot: PostgresParser._parse_generated_as_identity
    override fun parseGeneratedAsIdentity(): Expression {
        var this_ = super.parseGeneratedAsIdentity()

        if (matchTextSeq("STORED")) {
            this_ = expression(ComputedColumnConstraint(args("this" to this_.args["expression"])))
        }

        return this_
    }

    // sqlglot: PostgresParser._parse_user_defined_type — keeps the Identifier/Dot
    // expression (preserving quoting) instead of flattening to a string name
    override fun parseUserDefinedType(identifier: Identifier): Expression? {
        var udtType: Expression = identifier

        while (match(TokenType.DOT)) {
            val part = parseIdVar()
            if (part != null) {
                udtType = Dot(args("this" to udtType, "expression" to part))
            }
        }

        // sqlglot: exp.DataType.build(udt_type, udt=True)
        return DataType(args("this" to DType.USERDEFINED, "kind" to udtType))
    }
}

/**
 * Merged parser tables for Postgres (sqlglot: PostgresParser class-level dict merges).
 * Kept in an object so the merges happen once.
 */
object PostgresParserTables {

    // sqlglot: PostgresParser.PROPERTY_PARSERS (base minus INPUT, plus SET)
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> =
        (BaseParserTables.PROPERTY_PARSERS - "INPUT") +
            mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
                "SET" to { p, _ ->
                    p.expression(SetConfigProperty(args("this" to p.parseSet())))
                },
            )

    // sqlglot: PostgresParser.PLACEHOLDER_PARSERS
    val PLACEHOLDER_PARSERS: Map<TokenType, (Parser) -> Expression?> =
        BaseParserTables.PLACEHOLDER_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
            TokenType.PLACEHOLDER to { p -> p.expression(Placeholder(args("jdbc" to true))) },
            TokenType.MOD to { p -> (p as PostgresParser).parseQueryParameter() },
        )

    // sqlglot: PostgresParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(BaseParserTables.FUNCTIONS)
        put("ARRAY_PREPEND") { a ->
            ArrayPrepend(args("this" to seqGet(a, 1), "expression" to seqGet(a, 0)))
        }
        put("BIT_AND", fromArgList(listOf("this"), false) { BitwiseAndAgg(it) })
        put("BIT_OR", fromArgList(listOf("this"), false) { BitwiseOrAgg(it) })
        put("BIT_XOR", fromArgList(listOf("this"), false) { BitwiseXorAgg(it) })
        put("VERSION", fromArgList(listOf(), false) { CurrentVersion(it) })
        // sqlglot: dialect.build_timestamp_trunc
        put("DATE_TRUNC") { a ->
            applyTimeUnitCoercion(
                TimestampTrunc(args("this" to seqGet(a, 1), "unit" to seqGet(a, 0)))
            )
        }
        put("DIV") { a ->
            Cast(
                args(
                    "this" to IntDiv(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))),
                    "to" to DataType(args("this" to DType.DECIMAL)),
                )
            )
        }
        put("GENERATE_SERIES", ::buildGenerateSeries)
        put("GET_BIT") { a ->
            Getbit(
                args("this" to seqGet(a, 0), "expression" to seqGet(a, 1), "zero_is_msb" to true)
            )
        }
        put("JSON_EXTRACT_PATH", buildJsonExtractPath(scalar = false))
        put("JSON_EXTRACT_PATH_TEXT", buildJsonExtractPath(scalar = true))
        put("LENGTH") { a ->
            Length(args("this" to seqGet(a, 0), "encoding" to seqGet(a, 1)))
        }
        put(
            "MAKE_TIME",
            fromArgList(
                listOf("hour", "min", "sec", "nano", "fractions", "precision", "overflow"), false
            ) { TimeFromParts(it) },
        )
        put(
            "MAKE_TIMESTAMP",
            fromArgList(
                listOf("year", "month", "day", "hour", "min", "sec", "nano", "zone", "milli"), false
            ) { TimestampFromParts(it) },
        )
        put("NOW", fromArgList(listOf("this", "sysdate"), false) { CurrentTimestamp(it) })
        put("REGEXP_REPLACE", ::buildRegexpReplace)
        // sqlglot: build_formatted_time(exp.TimeToStr) / (exp.StrToDate)
        put("TO_CHAR") { a ->
            TimeToStr(args("this" to seqGet(a, 0), "format" to postgresFormatTime(seqGet(a, 1))))
        }
        put("TO_DATE") { a ->
            StrToDate(args("this" to seqGet(a, 0), "format" to postgresFormatTime(seqGet(a, 1))))
        }
        put("TO_TIMESTAMP", ::buildToTimestamp)
        put("UNNEST", fromArgList(listOf("this", "expressions"), true) { Explode(it) })
        put("SHA256") { a ->
            SHA2(args("this" to seqGet(a, 0), "length" to Literal.number("256")))
        }
        put("SHA384") { a ->
            SHA2(args("this" to seqGet(a, 0), "length" to Literal.number("384")))
        }
        put("SHA512") { a ->
            SHA2(args("this" to seqGet(a, 0), "length" to Literal.number("512")))
        }
        put("LEVENSHTEIN_LESS_EQUAL", ::buildLevenshteinLessEqual)
        put("JSON_OBJECT_AGG") { a -> JSONObjectAgg(args("expressions" to a)) }
        put(
            "JSONB_OBJECT_AGG",
            fromArgList(listOf("this", "expression"), false) { JSONBObjectAgg(it) },
        )
        put("WIDTH_BUCKET") { a ->
            if (a.size == 2) {
                WidthBucket(args("this" to seqGet(a, 0), "threshold" to seqGet(a, 1)))
            } else {
                fromArgList(
                    listOf("this", "min_value", "max_value", "num_buckets", "threshold"), false
                ) { WidthBucket(it) }(a)
            }
        }
        put("UUID") { a ->
            if (a.isNotEmpty()) {
                Cast(args("this" to seqGet(a, 0), "to" to DataType(args("this" to DType.UUID))))
            } else {
                Uuid()
            }
        }
        // sqlglot: parser.py FUNCTIONS["CONCAT"/"CONCAT_WS"] bake in
        // safe=not dialect.STRICT_STRING_CONCAT (Postgres: False -> safe=true) and
        // coalesce=dialect.CONCAT[_WS]_COALESCE (Postgres: true for both)
        put("CONCAT") { a ->
            Concat(args("expressions" to a, "safe" to true, "coalesce" to true))
        }
        put("CONCAT_WS") { a ->
            ConcatWs(args("expressions" to a, "safe" to true, "coalesce" to true))
        }
    }

    // sqlglot: PostgresParser.NO_PAREN_FUNCTION_PARSERS
    val NO_PAREN_FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.NO_PAREN_FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "VARIADIC" to { p -> p.expression(Variadic(args("this" to p.parseBitwise()))) },
        )

    // sqlglot: PostgresParser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        BaseParserTables.NO_PAREN_FUNCTIONS + mapOf(
            TokenType.LOCALTIME to { Localtime() },
            TokenType.LOCALTIMESTAMP to { Localtimestamp() },
            TokenType.CURRENT_CATALOG to { CurrentCatalog() },
            TokenType.SESSION_USER to { SessionUser() },
            TokenType.CURRENT_SCHEMA to { CurrentSchema() },
        )

    // sqlglot: PostgresParser.FUNCTION_PARSERS
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "DATE_PART" to { p -> (p as PostgresParser).parseDatePart() },
            "JSON_AGG" to { p ->
                p.expression(
                    JSONArrayAgg(
                        args("this" to p.parseLambda(), "order" to p.parseOrder())
                    )
                )
            },
            "JSONB_EXISTS" to { p -> (p as PostgresParser).parseJsonbExists() },
        )

    // sqlglot: PostgresParser.BITWISE
    val BITWISE: Map<TokenType, NodeFactory> = BaseParserTables.BITWISE + mapOf(
        TokenType.HASH to { a: dev.brikk.house.sql.ast.Args -> BitwiseXor(a) },
    )

    // sqlglot: PostgresParser.EXPONENT (replaces the base table)
    val EXPONENT: Map<TokenType, NodeFactory> = mapOf(
        TokenType.CARET to { a: dev.brikk.house.sql.ast.Args -> Pow(a) },
    )

    // sqlglot: PostgresParser.RANGE_PARSERS
    val RANGE_PARSERS: Map<TokenType, (Parser, Expression?) -> Expression?> =
        BaseParserTables.RANGE_PARSERS + mapOf(
            TokenType.DAMP to binaryRangeParser { ArrayOverlaps(it) },
            TokenType.DAT to { parser: Parser, this_: Expression? ->
                parser.expression(
                    MatchAgainst(
                        args("this" to parser.parseBitwise(), "expressions" to listOf(this_))
                    )
                )
            },
        )

    // sqlglot: PostgresParser.STATEMENT_PARSERS
    val STATEMENT_PARSERS: Map<TokenType, (Parser) -> Expression> =
        BaseParserTables.STATEMENT_PARSERS + mapOf<TokenType, (Parser) -> Expression>(
            TokenType.END to { p -> p.parseCommitOrRollback() },
        )

    // sqlglot: PostgresParser.UNARY_PARSERS — the `~` token is remapped from TILDE to
    // RLIKE in Postgres due to the binary REGEXP LIKE operator
    val UNARY_PARSERS: Map<TokenType, (Parser) -> Expression?> =
        BaseParserTables.UNARY_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
            TokenType.RLIKE to { p -> p.expression(BitwiseNot(args("this" to p.parseUnary()))) },
        )

    // sqlglot: PostgresParser.COLUMN_OPERATORS (ARROW/DARROW rebound through
    // build_json_extract_path with arrow_req_json_type=JSON_ARROWS_REQUIRE_JSON_TYPE)
    val COLUMN_OPERATORS: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?> =
        BaseParserTables.COLUMN_OPERATORS + mapOf<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?>(
            TokenType.ARROW to { parser, this_, path ->
                parser.expression(
                    buildJsonExtractPath(
                        scalar = false, arrowReqJsonType = parser.jsonArrowsRequireJsonType
                    )(listOf(this_, path))
                )
            },
            TokenType.DARROW to { parser, this_, path ->
                parser.expression(
                    buildJsonExtractPath(
                        scalar = true, arrowReqJsonType = parser.jsonArrowsRequireJsonType
                    )(listOf(this_, path))
                )
            },
        )
}
