package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.optimizer.annotateTypes
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.BigqueryTokenizerTables
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString
import dev.brikk.house.sql.parser.parseJsonPath
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

// sqlglot: parsers.bigquery._normalize_bare_week — a bare WEEK date part is WEEK(SUNDAY)
private fun normalizeBareWeek(expr: Expression): Expression {
    val unit = expr.args["unit"]
    if ((unit is Literal || unit is Var) && unit.name.uppercase() == "WEEK") {
        expr.set("unit", WeekStart(args("this" to Var(args("this" to "SUNDAY")))))
    }
    return expr
}

// sqlglot: parsers.bigquery.build_date_diff — the expr_type constructor runs
// exp.TimeUnit.__init__ (unit VAR_LIKE -> uppercased Var) before the bare-week fixup.
private fun buildDateDiff(factory: (Args) -> Expression): (List<Expression?>) -> Expression = { a ->
    normalizeBareWeek(
        dev.brikk.house.sql.parser.applyTimeUnitCoercion(
            factory(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "unit" to seqGet(a, 2),
                    "date_part_boundary" to true,
                )
            )
        )
    )
}

// sqlglot: helper.split_num_words (fill_from_start=True)
private fun splitNumWords(value: String, sep: String, minNumWords: Int): List<String?> {
    val words = value.split(sep)
    val padding = maxOf(0, minNumWords - words.size)
    return List<String?>(padding) { null } + words
}

// sqlglot: parsers.bigquery._DOMAIN_DOT — placeholder; cannot occur in a SQL identifier
private const val DOMAIN_DOT = '\u0000'

// sqlglot: parsers.bigquery._split_qualified_name — masks domain-scoped (legacy) project
// IDs of the form `domain.com:project-id` so the domain's dots survive the split.
internal fun bqSplitQualifiedName(name: String, minNumWords: Int): List<String?> {
    val colon = name.indexOf(':')
    if (colon != -1 && name.substring(0, colon).contains('.')) {
        val masked = name.substring(0, colon).replace('.', DOMAIN_DOT) + name.substring(colon)
        return splitNumWords(masked, ".", minNumWords).map { it?.replace(DOMAIN_DOT, '.') }
    }

    return splitNumWords(name, ".", minNumWords)
}

// sqlglot: BigQuery.JSONPathTokenizer.VAR_TOKENS = {*base, DASH, NUMBER}
private val BQ_JSONPATH_VAR_TOKENS: Set<TokenType> =
    setOf(TokenType.VAR, TokenType.DASH, TokenType.NUMBER)

// sqlglot: Dialect.to_json_path specialized by BigQuery's JSONPathTokenizer and
// JSON_PATH_SINGLE_DOT_IS_WILDCARD=True.
internal fun bqToJsonPath(path: Expression?): Expression? {
    if (path is Literal) {
        var pathText = path.name
        if (path.isNumber) pathText = "[$pathText]"
        try {
            return parseJsonPath(pathText, BQ_JSONPATH_VAR_TOKENS, singleDotIsWildcard = true)
        } catch (e: ParseError) {
            // sqlglot: logger.warning on invalid JSON path syntax, then fall through
        } catch (e: TokenError) {
            // sqlglot: TokenError siblings fall through too
        }
    }
    return path
}

/**
 * Counts the capturing groups of [pattern] the way Python's `re.compile(...).groups`
 * does, returning null when Python's `re` would raise `re.error` — a best-effort port
 * covering unbalanced parens/brackets/escapes and malformed `(?...)` extensions.
 *
 * sqlglot: parsers.bigquery._build_regexp_extract's `re.compile(args[1].name).groups`.
 */
internal fun pythonRegexCaptureGroups(pattern: String): kotlin.Int? {
    var groups = 0
    var depth = 0
    var i = 0
    val n = pattern.length
    var inClass = false

    while (i < n) {
        val ch = pattern[i]
        when {
            ch == '\\' -> {
                if (i + 1 >= n) return null // bad escape (end of pattern)
                i += 1
            }
            inClass -> {
                if (ch == ']') inClass = false
            }
            ch == '[' -> {
                inClass = true
                var j = i + 1
                if (j < n && pattern[j] == '^') j += 1
                if (j < n && pattern[j] == ']') j += 1 // leading ] is a literal
                // ensure the class terminates
                var k = j
                var closed = false
                while (k < n) {
                    if (pattern[k] == '\\') k += 1
                    else if (pattern[k] == ']') { closed = true; break }
                    k += 1
                }
                if (!closed) return null // unterminated character set
                i = j - 1
            }
            ch == ')' -> {
                if (depth == 0) return null // unbalanced parenthesis
                depth -= 1
            }
            ch == '(' -> {
                if (i + 1 < n && pattern[i + 1] == '?') {
                    if (i + 2 >= n) return null // unexpected end of pattern
                    when (val ext = pattern[i + 2]) {
                        ':', '=', '!' -> { depth += 1; i += 2 }
                        '#' -> {
                            // comment group: consume up to the closing paren
                            val close = pattern.indexOf(')', i + 3)
                            if (close == -1) return null // missing ), unterminated comment
                            i = close
                        }
                        '<' -> {
                            if (i + 3 >= n) return null
                            when (pattern[i + 3]) {
                                '=', '!' -> { depth += 1; i += 3 } // lookbehind
                                else -> return null // unknown extension ?<...
                            }
                        }
                        'P' -> {
                            if (i + 3 >= n) return null
                            when (pattern[i + 3]) {
                                '<' -> { groups += 1; depth += 1; i += 3 } // named group
                                '=' -> { depth += 1; i += 3 } // named backref
                                else -> return null // unknown extension ?P...
                            }
                        }
                        '(' -> { depth += 1; i += 2 } // conditional (?(id)...)
                        'a', 'i', 'L', 'm', 's', 'u', 'x', '-' -> { depth += 1; i += 2 } // flags
                        else -> return null // unknown extension ?<ext>
                    }
                } else {
                    groups += 1
                    depth += 1
                }
            }
        }
        i += 1
    }

    if (inClass || depth != 0) return null // unterminated set / missing )
    return groups
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

// sqlglot: parser.build_extract_json_with_path(expr_type)(args, BigQuery()) — converts
// the path literal via BigQuery's to_json_path; JSONExtract carries extra args, and
// JSONExtractScalar gets scalar_only=JSON_EXTRACT_SCALAR_SCALAR_ONLY (BigQuery: True).
private fun buildExtractJsonWithPath(
    factory: (Args) -> Expression,
    scalar: Boolean = false,
    isJsonExtract: Boolean = false,
): (List<Expression?>) -> Expression = { a ->
    val expr = factory(args("this" to seqGet(a, 0), "expression" to bqToJsonPath(seqGet(a, 1))))
    if (a.size > 2 && isJsonExtract) expr.set("expressions", a.drop(2))
    if (scalar) expr.set("scalar_only", true)
    expr
}

// sqlglot: parsers.bigquery._build_extract_json_with_default_path
private fun buildExtractJsonWithDefaultPath(
    factory: (Args) -> Expression,
    scalar: Boolean = false,
): (List<Expression?>) -> Expression = { a0 ->
    val a = if (a0.size == 1) a0 + listOf<Expression?>(Literal.string("$")) else a0
    buildExtractJsonWithPath(factory, scalar = scalar)(a)
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
private fun bqBuildRegexpExtract(
    all: Boolean,
    defaultGroup: Expression? = null,
): (List<Expression?>) -> Expression = { a ->
    // sqlglot: `re.compile(args[1].name).groups == 1` (re.error -> False)
    val group = pythonRegexCaptureGroups(a.getOrNull(1)?.name ?: "") == 1

    val kwargs = LinkedHashMap<String, kotlin.Any?>()
    kwargs["this"] = seqGet(a, 0)
    kwargs["expression"] = seqGet(a, 1)
    kwargs["position"] = seqGet(a, 2)
    kwargs["occurrence"] = seqGet(a, 3)
    kwargs["group"] = if (group) Literal.number("1") else defaultGroup
    if (all) {
        RegexpExtractAll(kwargs)
    } else {
        // sqlglot: REGEXP_EXTRACT_POSITION_OVERFLOW_RETURNS_NULL (base dialect: True)
        kwargs["null_if_pos_overflow"] = true
        RegexpExtract(kwargs)
    }
}

private val binaryFromFunctionIntDiv: (List<Expression?>) -> Expression = { a ->
    IntDiv(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
}

/**
 * Port of sqlglot's BigQueryParser (reference/sqlglot/sqlglot/parsers/bigquery.py class
 * BigQueryParser(parser.Parser)). Function/parser-table merges live in [BigqueryParserTables].
 */
// sqlglot: parsers.bigquery.BigQueryParser
open class BigqueryParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = BigqueryTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    override val dialect: Dialect get() = Dialects.BIGQUERY
    override val supportsDigitPrefixedFieldNames: Boolean get() = true

    // sqlglot: BigQueryParser.PREFIXED_PIVOT_COLUMNS = True
    override val prefixedPivotColumns: Boolean get() = true

    // sqlglot: BigQueryParser.LOG_DEFAULTS_TO_LN = True
    override val logDefaultsToLn: Boolean get() = true

    // sqlglot: BigQueryParser.SUPPORTS_IMPLICIT_UNNEST = True
    override val supportsImplicitUnnest: Boolean get() = true

    // sqlglot: BigQueryParser.JOINS_HAVE_EQUAL_PRECEDENCE = True
    override val joinsHaveEqualPrecedence: Boolean get() = true

    // sqlglot: BigQueryParser.ADJACENT_STRINGS_CANNOT_BE_CONNECTED = True
    override val adjacentStringsCannotBeConnected: Boolean get() = true

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
        get() = (BaseParserTables.ALIAS_TOKENS + TokenType.GRANT) -
            (setOf(TokenType.ASC, TokenType.DESC) + BaseParserTables.JOIN_SIDES)

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

    // sqlglot: BigQueryParser.FUNCTION_PARSERS (- TRIM, + ARRAY/JSON_ARRAY/MAKE_INTERVAL/
    // TRANSLATE/PREDICT/FEATURES_AT_TIME/GENERATE_*/VECTOR_SEARCH/FORECAST)
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
                "PREDICT" to { p -> (p as BigqueryParser).parseMl({ a -> Predict(a) }) },
                "TRANSLATE" to { p -> (p as BigqueryParser).parseTranslate() },
                "FEATURES_AT_TIME" to { p -> (p as BigqueryParser).parseFeaturesAtTime() },
                "GENERATE_EMBEDDING" to { p ->
                    (p as BigqueryParser).parseMl({ a -> GenerateEmbedding(a) })
                },
                "GENERATE_TEXT_EMBEDDING" to { p ->
                    (p as BigqueryParser).parseMl(
                        { a -> GenerateEmbedding(a) },
                        extra = args("is_text" to true),
                    )
                },
                "GENERATE_TEXT" to { p -> (p as BigqueryParser).parseGenerate { a -> GenerateText(a) } },
                "GENERATE_TABLE" to { p -> (p as BigqueryParser).parseGenerate { a -> GenerateTable(a) } },
                "GENERATE_BOOL" to { p -> (p as BigqueryParser).parseGenerate { a -> GenerateBool(a) } },
                "GENERATE_INT" to { p -> (p as BigqueryParser).parseGenerate { a -> GenerateInt(a) } },
                "GENERATE_DOUBLE" to { p -> (p as BigqueryParser).parseGenerate { a -> GenerateDouble(a) } },
                "VECTOR_SEARCH" to { p -> (p as BigqueryParser).parseVectorSearch() },
                "FORECAST" to { p -> (p as BigqueryParser).parseForecast() },
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
            TokenType.FOR to { p -> (p as BigqueryParser).parseForIn() },
            TokenType.EXPORT to { p -> (p as BigqueryParser).parseExportData() },
        )

    // sqlglot: BigQueryParser._parse_for_in
    open fun parseForIn(): Expression {
        val startIndex = index
        val this_ = parseRange()
        matchTextSeq("DO")
        if (match(TokenType.COMMAND)) {
            retreat(startIndex)
            return parseAsCommand(prevToken)
        }
        return expression(ForIn(args("this" to this_, "expression" to parseStatement())))
    }

    // sqlglot: BigQueryParser.PROPERTY_PARSERS
    override val propertyParsers: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>
        get() = super.propertyParsers + mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
            "OPTIONS" to { p, _ -> p.parseWithProperty() },
        )

    // sqlglot: BigQueryParser.CONSTRAINT_PARSERS (+ OPTIONS column options)
    override val constraintParsers: Map<String, (Parser) -> Expression?>
        get() = super.constraintParsers + mapOf<String, (Parser) -> Expression?>(
            "OPTIONS" to { p ->
                p.expression(Properties(args("expressions" to p.parseWithProperty())))
            },
        )

    // sqlglot: BigQuery dialect JSONPathTokenizer (VAR_TOKENS + DASH/NUMBER) and
    // JSON_PATH_SINGLE_DOT_IS_WILDCARD=True.
    override fun toJsonPath(path: Expression?): Expression? = bqToJsonPath(path)

    // sqlglot: BigQueryParser._parse_cluster_property — no parens around the columns.
    override fun parseClusterProperty(): Expression =
        expression(ClusterProperty(args("expressions" to parseCsv { parseColumn() })))

    // sqlglot #81c19435a: `NOT DETERMINISTIC` is no longer a single token; match it as a
    // text-sequence here so a bare `NOT` stays a boolean operator elsewhere.
    override fun parseProperty(): kotlin.Any? {
        if (matchTextSeq("NOT", "DETERMINISTIC")) {
            return expression(StabilityProperty(args("this" to Literal.string("VOLATILE"))))
        }
        return super.parseProperty()
    }

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

    // sqlglot: BigQueryParser._parse_table_parts — unravels dotted/dashed project names,
    // splits quoted multi-part identifiers, and merges INFORMATION_SCHEMA views.
    override fun parseTableParts(
        schema: Boolean,
        isDbReference: Boolean,
        wildcard: Boolean,
        fast: Boolean,
    ): Expression? {
        val parsed = super.parseTableParts(
            schema = schema, isDbReference = isDbReference, wildcard = true, fast = fast,
        )

        var table = parsed as? Table ?: return parsed

        // proj-1.db.tbl -- `1.` is tokenized as a float so we need to unravel it here
        if (table.args["catalog"] == null) {
            val previousDb = table.args["db"] as? Expression
            if (previousDb != null) {
                val parts = table.db.split(".")
                if (parts.size == 2 && (previousDb as? Identifier)?.args?.get("quoted") != true) {
                    table.set("catalog", Identifier(args("this" to parts[0])).updatePositions(previousDb))
                    table.set("db", Identifier(args("this" to parts[1])).updatePositions(previousDb))
                }
            } else {
                val previousThis = table.thisArg as? Expression
                val parts = table.name.split(".")
                if (parts.size == 2 && previousThis != null &&
                    (previousThis as? Identifier)?.args?.get("quoted") != true
                ) {
                    table.set("db", Identifier(args("this" to parts[0])).updatePositions(previousThis))
                    table.set("this", Identifier(args("this" to parts[1])).updatePositions(previousThis))
                }
            }
        }

        var alias: Identifier? = null
        val tableThis = table.thisArg
        if (tableThis is Identifier && table.parts.any { "." in it.name }) {
            alias = tableThis
            val split = bqSplitQualifiedName(table.parts.joinToString(".") { it.name }, 3)
            val identifiers = split.map { part ->
                part?.let { Identifier(args("this" to it, "quoted" to true)) }
            }
            val catalog = identifiers.getOrNull(0)
            val db = identifiers.getOrNull(1)
            val thisId = identifiers.getOrNull(2)
            val rest = identifiers.drop(3).filterNotNull()

            for (part in listOf(catalog, db, thisId)) {
                part?.updatePositions(tableThis)
            }

            var newThis: Expression? = thisId
            if (rest.isNotEmpty() && newThis != null) {
                newThis = Dot.build(listOf(newThis) + rest)
            }

            table = Table(
                args(
                    "this" to newThis,
                    "db" to db,
                    "catalog" to catalog,
                    "pivots" to table.args["pivots"],
                )
            )
            table.meta["quoted_table"] = true
        } else {
            alias = null
        }

        // The `INFORMATION_SCHEMA` views in BigQuery need to be qualified by a region or
        // dataset, so if the project identifier is omitted we need to fix the ast so that
        // the `INFORMATION_SCHEMA.X` bit is represented as a single (quoted) Identifier.
        // See BigQueryParser._parse_table_parts in the reference for the rationale.
        val tableParts = table.parts
        if (tableParts.size > 1 &&
            tableParts[tableParts.size - 2].name.uppercase() == "INFORMATION_SCHEMA"
        ) {
            // We need to alias the table here to avoid breaking existing qualified columns.
            // sqlglot: exp.alias_(table, alias or table_parts[-1], table=True, copy=False)
            table.set("alias", TableAlias(args("this" to (alias ?: tableParts.last()))))

            val secondLast = tableParts[tableParts.size - 2]
            val last = tableParts[tableParts.size - 1]
            val infoSchemaView = "${secondLast.name}.${last.name}"
            val newThis = Identifier(args("this" to infoSchemaView, "quoted" to true))
                .updatePositions(
                    line = secondLast.metaOrNull?.get("line") as? kotlin.Int,
                    col = last.metaOrNull?.get("col") as? kotlin.Int,
                    start = secondLast.metaOrNull?.get("start") as? kotlin.Int,
                    end = last.metaOrNull?.get("end") as? kotlin.Int,
                )
            table.set("this", newThis)
            table.set("db", tableParts.getOrNull(tableParts.size - 3))
            table.set("catalog", tableParts.getOrNull(tableParts.size - 4))
        }

        return table
    }

    // sqlglot: BigQueryParser._parse_column — splits quoted multi-part column names.
    override fun parseColumn(): Expression? {
        val column = super.parseColumn()
        if (column is Column) {
            val parts = column.parts
            if (parts.any { "." in it.name }) {
                val split = bqSplitQualifiedName(parts.joinToString(".") { it.name }, 4)
                val identifiers = split.map { part ->
                    part?.let { Identifier(args("this" to it, "quoted" to true)) }
                }
                val catalog = identifiers.getOrNull(0)
                val db = identifiers.getOrNull(1)
                val tablePart = identifiers.getOrNull(2)
                val thisId = identifiers.getOrNull(3)
                val rest = identifiers.drop(4).filterNotNull()

                var newThis: Expression? = thisId
                if (rest.isNotEmpty() && newThis != null) {
                    newThis = Dot.build(listOf(newThis) + rest)
                }

                val newColumn = Column(
                    args(
                        "this" to newThis,
                        "table" to tablePart,
                        "db" to db,
                        "catalog" to catalog,
                    )
                )
                newColumn.meta["quoted_column"] = true
                return newColumn
            }
        }

        return column
    }

    // sqlglot: BigQueryParser._parse_unnest — unnesting an array of structs explodes the
    // top-level struct fields, detected via mid-parse type annotation.
    override fun parseUnnest(withAlias: Boolean): Expression? {
        val unnest = super.parseUnnest(withAlias) ?: return null

        val unnestExpr = unnest.expressionsArg.firstOrNull() as? Expression
        if (unnestExpr != null) {
            val annotated = annotateTypes(unnestExpr, dialect = dialect)
            val annotatedType = annotated.typeSlot as? DataType

            if (annotatedType?.thisArg == DType.ARRAY &&
                annotatedType.expressionsArg.any { elem ->
                    (elem as? DataType)?.thisArg == DType.STRUCT
                }
            ) {
                unnest.set("explode_array", true)
            }
        }

        return unnest
    }

    // sqlglot: BigQueryParser._parse_export_data
    open fun parseExportData(): Expression {
        matchTextSeq("DATA")
        // sqlglot: `self._match_text_seq("WITH", "CONNECTION") and self._parse_table_parts()`
        // — an unmatched WITH CONNECTION leaves the Python falsy False, not None.
        val connection: kotlin.Any? =
            if (matchTextSeq("WITH", "CONNECTION")) parseTableParts() else false
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

    /**
     * sqlglot: helper.seq_get over the raw token list — Python's `seq[index]` supports
     * negative indexes (wrap from the end), which `_parse_translate`/_parse_forecast hit
     * when the function appears near the start of the statement.
     */
    private fun tokenAt(i: kotlin.Int): Token? =
        tokens.getOrNull(if (i < 0) tokens.size + i else i)

    // sqlglot: BigQueryParser._parse_ml
    open fun parseMl(factory: (Args) -> Expression, extra: Args = emptyMap()): Expression {
        matchTextSeq("MODEL")
        val this_ = parseTable()

        match(TokenType.COMMA)
        matchTextSeq("TABLE")

        // Certain functions like ML.FORECAST require a STRUCT argument but not a TABLE/SELECT one
        val expr = if (!match(TokenType.STRUCT, advance = false)) parseTable() else null

        match(TokenType.COMMA)

        return expression(
            factory(
                args(
                    "this" to this_,
                    "expression" to expr,
                    "params_struct" to parseBitwise(),
                ) + extra
            )
        )
    }

    // sqlglot: BigQueryParser._parse_generate
    open fun parseGenerate(factory: (Args) -> Expression): Expression {
        matchTextSeq("MODEL")
        val this_ = parseTable()

        match(TokenType.COMMA)

        val expr = if (matchTextSeq("TABLE")) {
            parseTable()
        } else if (match(TokenType.L_PAREN, advance = false)) {
            parseTable()
        } else {
            parseBitwise()
        }

        // sqlglot: `self._match(TokenType.COMMA) and self._parse_bitwise()` — False when
        // there is no comma.
        val paramsStruct: kotlin.Any? = if (match(TokenType.COMMA)) parseBitwise() else false

        return expression(
            factory(args("this" to this_, "expression" to expr, "params_struct" to paramsStruct))
        )
    }

    // sqlglot: BigQueryParser._parse_translate — ML.TRANSLATE routed to the ML parser,
    // otherwise Translate.from_arg_list.
    open fun parseTranslate(): Expression {
        // Check if this is ML.TRANSLATE by looking at previous tokens
        val token = tokenAt(index - 4)
        if (token != null && token.text.uppercase() == "ML") {
            return parseMl({ a -> MLTranslate(a) })
        }

        val a = parseFunctionArgs()
        return Translate(
            args(
                "this" to a.getOrNull(0),
                "from_" to a.getOrNull(1),
                "to" to a.getOrNull(2),
            )
        )
    }

    // sqlglot: BigQueryParser._parse_forecast
    open fun parseForecast(): Expression? {
        // Check if this is ML.FORECAST by looking at previous tokens.
        val token = tokenAt(index - 4)
        if (token != null && token.text.uppercase() == "ML") {
            return parseMl({ a -> MLForecast(a) })
        }

        // AI.FORECAST is a TVF, where the first argument is either TABLE <table>
        // or a parenthesized query statement, followed by named arguments.
        match(TokenType.TABLE)
        val this_ = parseTable()
        if (this_ == null) {
            return raiseError("Expected table or query statement")
        }

        val expr = expression(AIForecast(args("this" to this_)))
        while (match(TokenType.COMMA)) {
            val arg = parseLambda()
            if (arg is Kwarg) {
                expr.set((arg.thisArg as Expression).name, arg)
            } else {
                raiseError("Expected key => value syntax for AI.FORECAST, got $arg")
                break
            }
        }

        return expr
    }

    // sqlglot: BigQueryParser._parse_features_at_time
    open fun parseFeaturesAtTime(): Expression {
        match(TokenType.TABLE)
        val this_ = parseTable()

        val expr = expression(FeaturesAtTime(args("this" to this_)))

        while (match(TokenType.COMMA)) {
            // Get the LHS of the Kwarg and set the arg to that value, e.g
            // "num_rows => 1" sets the expr's `num_rows` arg
            val arg = parseLambda()
            if (arg != null) {
                expr.set((arg.thisArg as Expression).name, arg)
            }
        }

        return expr
    }

    // sqlglot: BigQueryParser._parse_vector_search
    open fun parseVectorSearch(): Expression {
        match(TokenType.TABLE)
        val baseTable = parseTable()

        match(TokenType.COMMA)

        val columnToSearch = parseBitwise()
        match(TokenType.COMMA)

        match(TokenType.TABLE)
        val queryTable = parseTable()

        val expr = expression(
            VectorSearch(
                args(
                    "this" to baseTable,
                    "column_to_search" to columnToSearch,
                    "query_table" to queryTable,
                )
            )
        )

        while (match(TokenType.COMMA)) {
            // query_column_to_search can be named argument or positional
            if (match(TokenType.STRING, advance = false)) {
                expr.set("query_column_to_search", parseString())
            } else {
                val arg = parseLambda()
                if (arg != null) {
                    expr.set((arg.thisArg as Expression).name, arg)
                }
            }
        }

        return expr
    }

    // sqlglot: BigQueryParser._parse_column_ops — SAFE./NET. prefixed functions become
    // SafeFunc/NetFunc; AI./ML. calls are re-parsed non-anonymously so their custom
    // function parsers apply.
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
                // Retreat to try and parse a known function instead of an anonymous one,
                // which is parsed by the base column ops parser due to anonymous_func=true
                retreat(funcIndex)
                result = func(args("this" to parseFunction(anyToken = true)))
            } else if (prefix == "AI" || prefix == "ML") {
                // AI.* and ML.* function calls can use custom BigQuery signatures that rely on
                // function parsers, so re-parse the function in non-anonymous mode.
                val dotThis = result.thisArg
                retreat(funcIndex)
                val parsed = parseFunction(anyToken = true)
                if (parsed != null) {
                    result = expression(Dot(args("this" to dotThis, "expression" to parsed)))
                }
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
        put("DATE_DIFF", buildDateDiff { DateDiff(it) })
        put("DATE_SUB", buildDateDeltaWithInterval { DateSub(it) })
        put("DATE_TRUNC") { a ->
            normalizeBareWeek(
                dev.brikk.house.sql.parser.applyTimeUnitCoercion(
                    DateTrunc(args("unit" to seqGet(a, 1), "this" to seqGet(a, 0), "zone" to seqGet(a, 2)))
                )
            )
        }
        put("DATETIME", ::buildDatetime)
        put("DATETIME_ADD", buildDateDeltaWithInterval { DatetimeAdd(it) })
        put("DATETIME_DIFF", buildDateDiff { DatetimeDiff(it) })
        put("DATETIME_SUB", buildDateDeltaWithInterval { DatetimeSub(it) })
        put("DATETIME_TRUNC", normalizedBareWeek(BaseParserTables.FUNCTIONS.getValue("DATETIME_TRUNC")))
        put("DIV", binaryFromFunctionIntDiv)
        put("EDIT_DISTANCE", ::buildLevenshtein)
        put("EMBED") { a -> AIEmbed(args("expressions" to a)) }
        put("FORMAT_DATE", buildFormattedTime({ TimeToStr(it) }, thisIdx = 1, fmtIdx = 0).wrapTsOrDs("TsOrDsToDate"))
        put("GENERATE") { a -> AIGenerate(args("expressions" to a)) }
        put("GENERATE_ARRAY") { a -> GenerateSeries(genSeriesArgs(a)) }
        put("JSON_EXTRACT", buildExtractJsonWithPath({ JSONExtract(it) }, isJsonExtract = true))
        put("JSON_EXTRACT_PATH_TEXT", buildExtractJsonWithPath({ JSONExtractScalar(it) }, scalar = true))
        put("JSON_EXTRACT_SCALAR", buildExtractJsonWithDefaultPath({ JSONExtractScalar(it) }, scalar = true))
        put("JSON_EXTRACT_ARRAY", buildExtractJsonWithDefaultPath({ JSONExtractArray(it) }))
        put("JSON_EXTRACT_STRING_ARRAY", buildExtractJsonWithDefaultPath({ JSONValueArray(it) }))
        // sqlglot: "JSON_KEYS": exp.JSONKeysAtDepth.from_arg_list
        put("JSON_KEYS") { a ->
            JSONKeysAtDepth(
                args("this" to seqGet(a, 0), "expression" to seqGet(a, 1), "mode" to seqGet(a, 2))
            )
        }
        put("JSON_QUERY", buildExtractJsonWithPath({ JSONExtract(it) }, isJsonExtract = true))
        put("JSON_QUERY_ARRAY", buildExtractJsonWithDefaultPath({ JSONExtractArray(it) }))
        put("JSON_STRIP_NULLS", ::buildJsonStripNulls)
        put("JSON_VALUE", buildExtractJsonWithDefaultPath({ JSONExtractScalar(it) }, scalar = true))
        put("JSON_VALUE_ARRAY", buildExtractJsonWithDefaultPath({ JSONValueArray(it) }))
        put("LAST_DAY", normalizedBareWeek(BaseParserTables.FUNCTIONS.getValue("LAST_DAY")))
        put("LENGTH") { a -> Length(args("this" to seqGet(a, 0), "binary" to true)) }
        // sqlglot: parser.build_logarithm (BigQuery: LOG_BASE_FIRST=False, LOG_DEFAULTS_TO_LN=True)
        put("LOG") { a ->
            val first = seqGet(a, 0)
            val second = seqGet(a, 1)
            if (second != null) {
                Log(args("this" to second, "expression" to first))
            } else {
                Ln(args("this" to first))
            }
        }
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
        put("REGEXP_EXTRACT", bqBuildRegexpExtract(all = false))
        put("REGEXP_SUBSTR", bqBuildRegexpExtract(all = false))
        put("REGEXP_EXTRACT_ALL", bqBuildRegexpExtract(all = true, defaultGroup = Literal.number("0")))
        put("SHA256") { a -> SHA2Digest(args("this" to seqGet(a, 0), "length" to Literal.number("256"))) }
        put("SHA512") { a -> SHA2Digest(args("this" to seqGet(a, 0), "length" to Literal.number("512"))) }
        put("SIMILARITY") { a -> AISimilarity(args("expressions" to a)) }
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
        put("TIMESTAMP_TRUNC", normalizedBareWeek(BaseParserTables.FUNCTIONS.getValue("TIMESTAMP_TRUNC")))
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

    // sqlglot: `lambda args: _normalize_bare_week(<base builder>(args))`
    private fun normalizedBareWeek(
        base: (List<Expression?>) -> Expression,
    ): (List<Expression?>) -> Expression = { a -> normalizeBareWeek(base(a)) }

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
