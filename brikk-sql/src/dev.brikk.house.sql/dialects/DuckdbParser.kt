package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Abs
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.AnyValue
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.ArrayAgg
import dev.brikk.house.sql.ast.ArrayAppend
import dev.brikk.house.sql.ast.ArrayContains
import dev.brikk.house.sql.ast.ArrayDistinct
import dev.brikk.house.sql.ast.ArrayFilter
import dev.brikk.house.sql.ast.ArrayIntersect
import dev.brikk.house.sql.ast.ArrayMax
import dev.brikk.house.sql.ast.ArrayMin
import dev.brikk.house.sql.ast.ArrayOverlaps
import dev.brikk.house.sql.ast.ArrayPrepend
import dev.brikk.house.sql.ast.Attach
import dev.brikk.house.sql.ast.AttachOption
import dev.brikk.house.sql.ast.BitwiseAndAgg
import dev.brikk.house.sql.ast.BitwiseOrAgg
import dev.brikk.house.sql.ast.BitwiseXor
import dev.brikk.house.sql.ast.BitwiseXorAgg
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Concat
import dev.brikk.house.sql.ast.ConcatWs
import dev.brikk.house.sql.ast.CosineDistance
import dev.brikk.house.sql.ast.CurrentCatalog
import dev.brikk.house.sql.ast.CurrentDate
import dev.brikk.house.sql.ast.CurrentTime
import dev.brikk.house.sql.ast.CurrentVersion
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DataTypeParam
import dev.brikk.house.sql.ast.DateBin
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.DateFromParts
import dev.brikk.house.sql.ast.Decode
import dev.brikk.house.sql.ast.Detach
import dev.brikk.house.sql.ast.Encode
import dev.brikk.house.sql.ast.EuclideanDistance
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GenerateSeries
import dev.brikk.house.sql.ast.Getbit
import dev.brikk.house.sql.ast.IgnoreNulls
import dev.brikk.house.sql.ast.Install
import dev.brikk.house.sql.ast.JSONArray
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractScalar
import dev.brikk.house.sql.ast.JarowinklerSimilarity
import dev.brikk.house.sql.ast.Lambda
import dev.brikk.house.sql.ast.Levenshtein
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Localtime
import dev.brikk.house.sql.ast.Localtimestamp
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.ast.ParseJSON
import dev.brikk.house.sql.ast.PercentileCont
import dev.brikk.house.sql.ast.PercentileDisc
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.PositionalColumn
import dev.brikk.house.sql.ast.Pow
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.RegexpExtract
import dev.brikk.house.sql.ast.RegexpExtractAll
import dev.brikk.house.sql.ast.RegexpFullMatch
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.RegexpReplace
import dev.brikk.house.sql.ast.RegexpSplit
import dev.brikk.house.sql.ast.ReturnsProperty
import dev.brikk.house.sql.ast.SHA2
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.ast.SessionUser
import dev.brikk.house.sql.ast.Show
import dev.brikk.house.sql.ast.SortArray
import dev.brikk.house.sql.ast.Split
import dev.brikk.house.sql.ast.StartsWith
import dev.brikk.house.sql.ast.StrToTime
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TimeFromParts
import dev.brikk.house.sql.ast.TimeToStr
import dev.brikk.house.sql.ast.TimeToUnix
import dev.brikk.house.sql.ast.TimestampFromParts
import dev.brikk.house.sql.ast.ToMap
import dev.brikk.house.sql.ast.Transform
import dev.brikk.house.sql.ast.UnixToTime
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.applyTimeUnitCoercion
import dev.brikk.house.sql.parser.binaryRangeParser
import dev.brikk.house.sql.parser.fromArgList
import dev.brikk.house.sql.parser.parseJsonPath

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: parsers.duckdb._build_sort_array_desc
private fun buildSortArrayDesc(a: List<Expression?>): Expression =
    SortArray(args("this" to seqGet(a, 0), "asc" to BooleanNode(args("this" to false))))

// sqlglot: parsers.duckdb._build_array_prepend
private fun buildArrayPrependDuckdb(a: List<Expression?>): Expression =
    ArrayPrepend(args("this" to seqGet(a, 1), "expression" to seqGet(a, 0)))

// sqlglot: parsers.duckdb._build_date_diff
private fun buildDateDiff(a: List<Expression?>): Expression =
    applyTimeUnitCoercion(
        DateDiff(args("this" to seqGet(a, 2), "expression" to seqGet(a, 1), "unit" to seqGet(a, 0)))
    )

// sqlglot: parsers.duckdb._build_generate_series
private fun buildGenerateSeries(endExclusive: Boolean = false): (List<Expression?>) -> Expression =
    { a ->
        // DuckDB uses 0 as a default for the series' start when it's omitted
        val argsList = if (a.size == 1) listOf(Literal.number("0")) + a else a

        val genSeries = fromArgList(
            listOf("start", "end", "step", "is_end_exclusive"), false
        ) { GenerateSeries(it) }(argsList)
        genSeries.set("is_end_exclusive", endExclusive)

        genSeries
    }

// sqlglot: parsers.duckdb._build_make_timestamp
private fun buildMakeTimestamp(a: List<Expression?>): Expression {
    if (a.size == 1) {
        // sqlglot: exp.UnixToTime.MICROS
        return UnixToTime(args("this" to seqGet(a, 0), "scale" to Literal.number("6")))
    }

    return TimestampFromParts(
        args(
            "year" to seqGet(a, 0),
            "month" to seqGet(a, 1),
            "day" to seqGet(a, 2),
            "hour" to seqGet(a, 3),
            "min" to seqGet(a, 4),
            "sec" to seqGet(a, 5),
        )
    )
}

// sqlglot: DuckDB.to_json_path — also supports the JSON pointer syntax ("/"-prefixed)
// and back-of-list access ("[#-i]"); those are kept as raw path literals.
internal fun duckdbToJsonPath(path: Expression?): Expression? {
    if (path is Literal) {
        val pathText = path.name
        if (pathText.startsWith("/") || "[#" in pathText) {
            return path
        }
        var text = pathText
        if (path.isNumber) text = "[$text]"
        try {
            return parseJsonPath(text)
        } catch (e: ParseError) {
            // sqlglot: STRICT_JSON_PATH_SYNTAX=False — no warning, fall through
        }
    }
    return path
}

// sqlglot: parser.build_extract_json_with_path (dialect-aware via DuckDB.to_json_path)
private fun duckdbBuildExtractJsonWithPath(
    scalar: Boolean,
): (List<Expression?>) -> Expression = { fnArgs ->
    val initArgs = args(
        "this" to seqGet(fnArgs, 0),
        "expression" to duckdbToJsonPath(seqGet(fnArgs, 1)),
    )
    val expression: Expression = if (scalar) JSONExtractScalar(initArgs) else JSONExtract(initArgs)
    if (fnArgs.size > 2 && !scalar) {
        expression.set("expressions", fnArgs.drop(2))
    }
    if (scalar) {
        // base dialect flag JSON_EXTRACT_SCALAR_SCALAR_ONLY=false (duckdb default)
        expression.set("scalar_only", false)
    }
    expression
}

/**
 * Port of sqlglot's DuckDBParser (reference/sqlglot/sqlglot/parsers/duckdb.py).
 * Table merges live in [DuckdbParserTables]; flag overrides and method overrides below.
 */
// sqlglot: parsers.duckdb.DuckDBParser
open class DuckdbParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.DuckdbTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: DuckDBParser.MAP_KEYS_ARE_ARBITRARY_EXPRESSIONS = True
    override val mapKeysAreArbitraryExpressions: Boolean get() = true

    // sqlglot: DuckDBParser.PIVOT_COLUMN_NAMING = "agg_name_if_aliased_or_multiple"
    override val pivotColumnNaming: String get() = "agg_name_if_aliased_or_multiple"

    // sqlglot: DuckDB.NULL_ORDERING = "nulls_are_last"
    override val nullOrdering: String get() = "nulls_are_last"

    // sqlglot: DuckDB.SAFE_DIVISION = True
    override val safeDivision: Boolean get() = true

    // sqlglot: DuckDB.INDEX_OFFSET = 1
    override val indexOffset: Int get() = 1

    // sqlglot: DuckDB.SUPPORTS_ORDER_BY_ALL = True
    override val supportsOrderByAll: Boolean get() = true

    // sqlglot: DuckDB.SUPPORTS_LIMIT_ALL = True
    override val supportsLimitAll: Boolean get() = true

    // sqlglot: DuckDB.SUPPORTS_FIXED_SIZE_ARRAYS = True
    override val supportsFixedSizeArrays: Boolean get() = true

    // sqlglot: DuckDB.to_json_path
    override fun toJsonPath(path: Expression?): Expression? = duckdbToJsonPath(path)

    // Table overrides (sqlglot: DuckDBParser class-level dict/set merges)

    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = DuckdbParserTables.NO_PAREN_FUNCTIONS

    override val bitwise: Map<TokenType, NodeFactory> get() = DuckdbParserTables.BITWISE

    override val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?>
        get() = DuckdbParserTables.RANGE_PARSERS

    override val exponent: Map<TokenType, NodeFactory> get() = DuckdbParserTables.EXPONENT

    override val functionsWithAliasedArgs: Set<String>
        get() = DuckdbParserTables.FUNCTIONS_WITH_ALIASED_ARGS

    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = DuckdbParserTables.FUNCTIONS

    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = DuckdbParserTables.FUNCTION_PARSERS

    override val noParenFunctionParsers: Map<String, (Parser) -> Expression?>
        get() = DuckdbParserTables.NO_PAREN_FUNCTION_PARSERS

    override val placeholderParsers: Map<TokenType, (Parser) -> Expression?>
        get() = DuckdbParserTables.PLACEHOLDER_PARSERS

    override val typeConverters: Map<DType, (DataType) -> Expression>
        get() = DuckdbParserTables.TYPE_CONVERTERS

    override val statementParsers: Map<TokenType, (Parser) -> Expression>
        get() = DuckdbParserTables.STATEMENT_PARSERS

    override val setParsers: Map<String, (Parser) -> Expression?>
        get() = super.setParsers + DuckdbParserTables.SET_PARSERS_EXTRA

    // sqlglot: DuckDBParser.SHOW_PARSERS
    open val showParsers: Map<String, (DuckdbParser) -> Expression>
        get() = DuckdbParserTables.SHOW_PARSERS

    // sqlglot: DuckDBParser._parse_function_properties
    override fun parseFunctionProperties(): Expression? {
        if (match(TokenType.TABLE)) {
            return expression(
                Properties(
                    args(
                        "expressions" to listOf(
                            expression(
                                ReturnsProperty(
                                    args(
                                        "this" to Schema(args("this" to Var(args("this" to "TABLE")))),
                                        "is_table" to true,
                                    )
                                )
                            ),
                        )
                    )
                )
            )
        }
        return super.parseFunctionProperties()
    }

    // sqlglot: DuckDBParser._parse_lambda (LAMBDA arg1, arg2 : expr syntax)
    override fun parseLambda(alias: Boolean): Expression? {
        val startIndex = index
        if (!matchTextSeq("LAMBDA")) {
            return super.parseLambda(alias = alias)
        }

        val expressions = parseCsv { parseLambdaArg() }
        if (!match(TokenType.COLON)) {
            retreat(startIndex)
            return null
        }

        val this_ = replaceLambda(parseAssignment(), expressions)
        return expression(
            Lambda(args("this" to this_, "expressions" to expressions, "colon" to true))
        )
    }

    // sqlglot: DuckDBParser._parse_expression (prefix aliases, e.g. foo: 1)
    override fun parseExpression(): Expression? {
        if (nextToken.tokenType == TokenType.COLON) {
            val alias = parseIdVar(tokens = aliasTokens)
            match(TokenType.COLON)
            val comments = prevComments.toMutableList()

            val this_ = parseAssignment()
            if (this_ != null) {
                // Moves the comment next to the alias in `alias: expr /* comment */`
                comments += this_.popComments()
            }

            return expression(
                Alias(args("this" to this_, "alias" to alias)),
                comments = comments,
            )
        }

        return super.parseExpression()
    }

    // sqlglot: DuckDBParser._parse_table (prefix aliases, e.g. FROM foo: bar)
    override fun parseTable(
        schema: Boolean,
        joins: Boolean,
        aliasTokens: Collection<TokenType>?,
        parseBracket: Boolean,
        isDbReference: Boolean,
        parsePartition: Boolean,
        consumePipe: Boolean,
    ): Expression? {
        val alias: TableAlias?
        val comments: MutableList<String>
        if (nextToken.tokenType == TokenType.COLON) {
            alias = parseTableAlias(aliasTokens = aliasTokens ?: tableAliasTokens)
            match(TokenType.COLON)
            comments = prevComments.toMutableList()
        } else {
            alias = null
            comments = mutableListOf()
        }

        // sqlglot: the super() call intentionally omits consume_pipe
        val table = super.parseTable(
            schema = schema,
            joins = joins,
            aliasTokens = aliasTokens,
            parseBracket = parseBracket,
            isDbReference = isDbReference,
            parsePartition = parsePartition,
            consumePipe = false,
        )
        if (table != null && alias != null) {
            // Moves the comment next to the alias in `alias: table /* comment */`
            comments += table.popComments()
            alias.comments = (alias.popComments() + comments).toMutableList()
            table.set("alias", alias)
        }

        return table
    }

    // sqlglot: DuckDBParser._parse_table_sample (https://duckdb.org/docs/sql/samples.html)
    override fun parseTableSample(asModifier: Boolean): Expression? {
        val sample = super.parseTableSample(asModifier = asModifier)
        if (sample != null && sample.args["method"] == null) {
            if (sample.args["size"] != null) {
                sample.set("method", Var(args("this" to "RESERVOIR")))
            } else {
                sample.set("method", Var(args("this" to "SYSTEM")))
            }
        }

        return sample
    }

    // sqlglot: DuckDBParser._parse_bracket — the `dialect.version < (1, 2)`
    // returns_list_for_maps branch is elided: the port always runs at the pinned
    // sqlglot's default (latest) version.

    // sqlglot: DuckDBParser._parse_map
    open fun parseMap(): Expression {
        if (match(TokenType.L_BRACE, advance = false)) {
            return expression(ToMap(args("this" to parseBracket(null))))
        }

        val argsList = parseWrappedCsv({ parseAssignment() })
        return expression(
            MapNode(args("keys" to seqGet(argsList, 0), "values" to seqGet(argsList, 1)))
        )
    }

    // sqlglot: DuckDBParser._parse_struct_types
    override fun parseStructTypes(typeRequired: Boolean): Expression? = parseFieldDef()

    // sqlglot: DuckDBParser._pivot_column_names
    override fun pivotColumnNames(aggregations: List<Expression>): List<String> {
        if (aggregations.size == 1) {
            return super.pivotColumnNames(aggregations)
        }
        // sqlglot: dialect.pivot_column_names(aggregations, dialect="duckdb")
        return aggregations.map { agg ->
            if (agg is Alias) {
                agg.alias
            } else {
                // Aggregations without aliases are used as suffixes (e.g. col_avg(foo));
                // identifiers are unquoted because _parse_pivot will re-quote them.
                val unquoted = agg.transform(copy = true) { node ->
                    if (node is dev.brikk.house.sql.ast.Identifier) {
                        dev.brikk.house.sql.ast.Identifier(
                            args("this" to node.name, "quoted" to false)
                        )
                    } else node
                }
                DuckdbGenerator(normalizeFunctions = "lower").generate(unquoted, copy = false)
            }
        }
    }

    // sqlglot: DuckDBParser._parse_attach_detach
    open fun parseAttachDetach(isAttach: Boolean = true): Expression {
        fun parseAttachOption(): Expression = expression(
            AttachOption(
                args(
                    "this" to parseVar(anyToken = true),
                    "expression" to parseField(anyToken = true),
                )
            )
        )

        match(TokenType.DATABASE)
        val exists = parseExists(not_ = isAttach)
        val this_ = parseAlias(parsePrimaryOrVar(), explicit = true)

        val expressions: List<Expression>? = if (match(TokenType.L_PAREN, advance = false)) {
            parseWrappedCsv({ parseAttachOption() })
        } else {
            null
        }

        return if (isAttach) {
            expression(
                Attach(args("this" to this_, "exists" to exists, "expressions" to expressions))
            )
        } else {
            expression(Detach(args("this" to this_, "exists" to exists)))
        }
    }

    // sqlglot: DuckDBParser._parse_show_duckdb
    open fun parseShowDuckdb(thisName: String): Expression {
        val from = if (match(TokenType.FROM)) parseTable(schema = true) else null
        return expression(Show(args("this" to thisName, "from_" to from)))
    }

    // sqlglot: Parser._parse_show (dispatch through SHOW_PARSERS via _find_parser)
    open fun parseShow(): Expression {
        val parser = findParser(showParsers)
        if (parser != null) return parser(this)
        return parseAsCommand(prevToken)
    }

    // sqlglot: DuckDBParser._parse_force — FORCE can only be followed by INSTALL or
    // CHECKPOINT; in the case of CHECKPOINT, we fallback
    open fun parseForce(): Expression {
        if (!match(TokenType.INSTALL)) {
            return parseAsCommand(prevToken)
        }

        return parseInstall(force = true)
    }

    // sqlglot: DuckDBParser._parse_install
    open fun parseInstall(force: Boolean = false): Expression = expression(
        Install(
            args(
                "this" to parseIdVar(),
                "from_" to if (match(TokenType.FROM)) parseVarOrString() else null,
                "force" to force,
            )
        )
    )

    // sqlglot: DuckDBParser._parse_primary (#N positional columns)
    override fun parsePrimary(): Expression? {
        if (matchPair(TokenType.HASH, TokenType.NUMBER)) {
            return PositionalColumn(args("this" to Literal.number(prevToken.text)))
        }

        return super.parsePrimary()
    }
}

/**
 * Merged parser tables for DuckDB (sqlglot: DuckDBParser class-level dict merges).
 * Kept in an object so the merges happen once.
 */
object DuckdbParserTables {

    // sqlglot: DuckDBParser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        BaseParserTables.NO_PAREN_FUNCTIONS + mapOf(
            TokenType.LOCALTIME to { Localtime() },
            TokenType.LOCALTIMESTAMP to { Localtimestamp() },
            TokenType.CURRENT_CATALOG to { CurrentCatalog() },
            TokenType.SESSION_USER to { SessionUser() },
        )

    // sqlglot: DuckDBParser.BITWISE = {k: v for base if k != CARET}
    val BITWISE: Map<TokenType, NodeFactory> = BaseParserTables.BITWISE - TokenType.CARET

    // sqlglot: DuckDBParser.RANGE_PARSERS
    val RANGE_PARSERS: Map<TokenType, (Parser, Expression?) -> Expression?> =
        BaseParserTables.RANGE_PARSERS + mapOf(
            TokenType.DAMP to binaryRangeParser { ArrayOverlaps(it) },
            TokenType.CARET_AT to binaryRangeParser { StartsWith(it) },
            TokenType.TILDE to binaryRangeParser { RegexpFullMatch(it) },
        )

    // sqlglot: DuckDBParser.EXPONENT
    val EXPONENT: Map<TokenType, NodeFactory> = BaseParserTables.EXPONENT + mapOf(
        TokenType.CARET to { a: dev.brikk.house.sql.ast.Args -> Pow(a) },
        TokenType.DSTAR to { a: dev.brikk.house.sql.ast.Args -> Pow(a) },
    )

    // sqlglot: DuckDBParser.FUNCTIONS_WITH_ALIASED_ARGS
    val FUNCTIONS_WITH_ALIASED_ARGS: Set<String> =
        BaseParserTables.FUNCTIONS_WITH_ALIASED_ARGS + setOf("STRUCT_PACK")

    // sqlglot: DuckDBParser.SHOW_PARSERS
    val SHOW_PARSERS: Map<String, (DuckdbParser) -> Expression> = mapOf(
        "TABLES" to { p -> p.parseShowDuckdb("TABLES") },
        "ALL TABLES" to { p -> p.parseShowDuckdb("ALL TABLES") },
    )

    // sqlglot: DuckDBParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(BaseParserTables.FUNCTIONS)
        remove("DATE_SUB")
        remove("GLOB")
        put("ANY_VALUE") { a ->
            IgnoreNulls(
                args("this" to fromArgList(listOf("this"), false) { AnyValue(it) }(a))
            )
        }
        put("ARRAY_PREPEND", ::buildArrayPrependDuckdb)
        put("ARRAY_REVERSE_SORT", ::buildSortArrayDesc)
        put("ARRAY_INTERSECT") { a -> ArrayIntersect(args("expressions" to a)) }
        put("ARRAY_SORT", fromArgList(listOf("this", "asc", "nulls_first"), false) { SortArray(it) })
        put("BIT_AND", fromArgList(listOf("this"), false) { BitwiseAndAgg(it) })
        put("BIT_OR", fromArgList(listOf("this"), false) { BitwiseOrAgg(it) })
        put("BIT_XOR", fromArgList(listOf("this"), false) { BitwiseXorAgg(it) })
        // sqlglot: parser.py FUNCTIONS["CONCAT"/"CONCAT_WS"] bake in
        // safe=not dialect.STRICT_STRING_CONCAT (DuckDB: False -> safe=true) and
        // coalesce=dialect.CONCAT[_WS]_COALESCE (DuckDB: true)
        put("CONCAT") { a ->
            Concat(args("expressions" to a, "safe" to true, "coalesce" to true))
        }
        put("CONCAT_WS") { a ->
            ConcatWs(args("expressions" to a, "safe" to true, "coalesce" to true))
        }
        put("CURRENT_LOCALTIMESTAMP", fromArgList(listOf("this"), false) { Localtimestamp(it) })
        put("DATEDIFF", ::buildDateDiff)
        put("DATE_DIFF", ::buildDateDiff)
        put("DATE_TRUNC", ::dateTruncToTime)
        put("DATETRUNC", ::dateTruncToTime)
        put("DECODE") { a ->
            Decode(args("this" to seqGet(a, 0), "charset" to Literal.string("utf-8")))
        }
        put(
            "EDITDIST3",
            fromArgList(
                listOf("this", "expression", "ins_cost", "del_cost", "sub_cost", "max_dist"), false
            ) { Levenshtein(it) },
        )
        put("ENCODE") { a ->
            Encode(args("this" to seqGet(a, 0), "charset" to Literal.string("utf-8")))
        }
        put("EPOCH", fromArgList(listOf("this"), false) { TimeToUnix(it) })
        put("EPOCH_MS") { a ->
            // sqlglot: exp.UnixToTime.MILLIS
            UnixToTime(args("this" to seqGet(a, 0), "scale" to Literal.number("3")))
        }
        put("GENERATE_SERIES", buildGenerateSeries())
        put("GET_CURRENT_TIME", fromArgList(listOf("this"), false) { CurrentTime(it) })
        put("GET_BIT") { a ->
            Getbit(
                args("this" to seqGet(a, 0), "expression" to seqGet(a, 1), "zero_is_msb" to true)
            )
        }
        put(
            "JARO_WINKLER_SIMILARITY",
            fromArgList(
                listOf("this", "expression", "case_insensitive", "integer_scale"), false
            ) { JarowinklerSimilarity(it) },
        )
        put("JSON", fromArgList(listOf("this", "expression", "safe"), false) { ParseJSON(it) })
        put("JSON_ARRAY") { a -> JSONArray(args("expressions" to a)) }
        // the base JSON_EXTRACT* builders re-bound to DuckDB's to_json_path
        put("JSON_EXTRACT", duckdbBuildExtractJsonWithPath(scalar = false))
        put("JSON_EXTRACT_SCALAR", duckdbBuildExtractJsonWithPath(scalar = true))
        put("JSON_EXTRACT_PATH_TEXT", duckdbBuildExtractJsonWithPath(scalar = true))
        put("JSON_EXTRACT_PATH", duckdbBuildExtractJsonWithPath(scalar = false))
        put("JSON_EXTRACT_STRING", duckdbBuildExtractJsonWithPath(scalar = true))
        put("LIST", fromArgList(listOf("this", "nulls_excluded"), false) { ArrayAgg(it) })
        put("LIST_DISTINCT", fromArgList(listOf("this", "check_null"), false) { ArrayDistinct(it) })
        put(
            "LIST_APPEND",
            fromArgList(listOf("this", "expression", "null_propagation"), false) { ArrayAppend(it) },
        )
        put("LIST_CONCAT", dev.brikk.house.sql.parser.BaseParserTables.FUNCTIONS.getValue("ARRAY_CONCAT"))
        put(
            "LIST_CONTAINS",
            fromArgList(
                listOf("this", "expression", "ensure_variant", "check_null"), false
            ) { ArrayContains(it) },
        )
        put(
            "LIST_COSINE_DISTANCE",
            fromArgList(listOf("this", "expression"), false) { CosineDistance(it) },
        )
        put("LIST_DISTANCE", fromArgList(listOf("this", "expression"), false) { EuclideanDistance(it) })
        put("LIST_FILTER", fromArgList(listOf("this", "expression"), false) { ArrayFilter(it) })
        put(
            "LIST_HAS",
            fromArgList(
                listOf("this", "expression", "ensure_variant", "check_null"), false
            ) { ArrayContains(it) },
        )
        put(
            "LIST_HAS_ANY",
            fromArgList(listOf("this", "expression", "null_safe"), false) { ArrayOverlaps(it) },
        )
        put("LIST_MAX", fromArgList(listOf("this"), false) { ArrayMax(it) })
        put("LIST_MIN", fromArgList(listOf("this"), false) { ArrayMin(it) })
        put("LIST_PREPEND", ::buildArrayPrependDuckdb)
        put("LIST_REVERSE_SORT", ::buildSortArrayDesc)
        put("LIST_SORT", fromArgList(listOf("this", "asc", "nulls_first"), false) { SortArray(it) })
        put("LIST_TRANSFORM", fromArgList(listOf("this", "expression"), false) { Transform(it) })
        put("LIST_VALUE") { a -> ArrayNode(args("expressions" to a)) }
        put(
            "MAKE_DATE",
            fromArgList(listOf("year", "month", "day", "allow_overflow"), false) { DateFromParts(it) },
        )
        put(
            "MAKE_TIME",
            fromArgList(
                listOf("hour", "min", "sec", "nano", "fractions", "precision", "overflow"), false
            ) { TimeFromParts(it) },
        )
        put("MAKE_TIMESTAMP", ::buildMakeTimestamp)
        put("QUANTILE_CONT", fromArgList(listOf("this", "expression"), false) { PercentileCont(it) })
        put("QUANTILE_DISC", fromArgList(listOf("this", "expression"), false) { PercentileDisc(it) })
        put("RANGE", buildGenerateSeries(endExclusive = true))
        put("REGEXP_EXTRACT", buildRegexpExtract(all = false))
        put("REGEXP_EXTRACT_ALL", buildRegexpExtract(all = true))
        put(
            "REGEXP_MATCHES",
            fromArgList(listOf("this", "expression", "flag", "full_match"), false) { RegexpLike(it) },
        )
        put("REGEXP_REPLACE") { a ->
            RegexpReplace(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "replacement" to seqGet(a, 2),
                    "modifiers" to seqGet(a, 3),
                    "single_replace" to true,
                )
            )
        }
        put("SHA256") { a ->
            SHA2(args("this" to seqGet(a, 0), "length" to Literal.number("256")))
        }
        // sqlglot: build_formatted_time(exp.TimeToStr/exp.StrToTime) — DuckDB
        // TIME_MAPPING={} so format_time is the identity on the format literal
        put("STRFTIME") { a ->
            TimeToStr(args("this" to seqGet(a, 0), "format" to seqGet(a, 1)))
        }
        put("STRPTIME") { a ->
            StrToTime(args("this" to seqGet(a, 0), "format" to seqGet(a, 1)))
        }
        put(
            "STRING_SPLIT",
            fromArgList(
                listOf("this", "expression", "limit", "null_returns_null", "empty_delimiter_returns_whole"),
                false,
            ) { Split(it) },
        )
        put(
            "STRING_SPLIT_REGEX",
            fromArgList(listOf("this", "expression", "limit"), false) { RegexpSplit(it) },
        )
        put(
            "STRING_TO_ARRAY",
            fromArgList(
                listOf("this", "expression", "limit", "null_returns_null", "empty_delimiter_returns_whole"),
                false,
            ) { Split(it) },
        )
        put("STRUCT_PACK", fromArgList(listOf("expressions"), true) { Struct(it) })
        put(
            "STR_SPLIT",
            fromArgList(
                listOf("this", "expression", "limit", "null_returns_null", "empty_delimiter_returns_whole"),
                false,
            ) { Split(it) },
        )
        put(
            "STR_SPLIT_REGEX",
            fromArgList(listOf("this", "expression", "limit"), false) { RegexpSplit(it) },
        )
        put("TODAY", fromArgList(listOf("this"), false) { CurrentDate(it) })
        put(
            "TIME_BUCKET",
            fromArgList(listOf("this", "expression", "unit", "zone", "origin"), false) { DateBin(it) },
        )
        put(
            "TO_TIMESTAMP",
            fromArgList(
                listOf("this", "scale", "zone", "hours", "minutes", "format", "target_type"), false
            ) { UnixToTime(it) },
        )
        put("UNNEST", fromArgList(listOf("this", "expressions"), true) { Explode(it) })
        put("VERSION", fromArgList(listOf(), false) { CurrentVersion(it) })
        put("XOR", binaryFromFunction { a -> BitwiseXor(a) })
    }

    // sqlglot: DuckDBParser.FUNCTION_PARSERS (base minus DECODE, plus string-agg aliases)
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        (BaseParserTables.FUNCTION_PARSERS - "DECODE") +
            listOf("GROUP_CONCAT", "LISTAGG", "STRINGAGG").associateWith {
                { p: Parser -> p.parseStringAgg() }
            }

    // sqlglot: DuckDBParser.NO_PAREN_FUNCTION_PARSERS
    val NO_PAREN_FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.NO_PAREN_FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "MAP" to { p -> (p as DuckdbParser).parseMap() },
            "@" to { p -> p.expression(Abs(args("this" to p.parseBitwise()))) },
        )

    // sqlglot: DuckDBParser.PLACEHOLDER_PARSERS
    val PLACEHOLDER_PARSERS: Map<TokenType, (Parser) -> Expression?> =
        BaseParserTables.PLACEHOLDER_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
            TokenType.PARAMETER to { p ->
                if (p.match(TokenType.NUMBER) || p.matchSet(p.idVarTokens)) {
                    p.expression(Placeholder(args("this" to p.prevToken.text)))
                } else {
                    null
                }
            },
        )

    // sqlglot: DuckDBParser.TYPE_CONVERTERS
    val TYPE_CONVERTERS: Map<DType, (DataType) -> Expression> = mapOf(
        // https://duckdb.org/docs/sql/data_types/numeric
        // sqlglot: dialect.build_default_decimal_type(precision=18, scale=3)
        DType.DECIMAL to { dtype: DataType ->
            if ((dtype.args["expressions"] as? List<*>)?.isNotEmpty() == true) {
                dtype
            } else {
                DataType(
                    args(
                        "this" to DType.DECIMAL,
                        "expressions" to listOf(
                            DataTypeParam(args("this" to Literal.number("18"))),
                            DataTypeParam(args("this" to Literal.number("3"))),
                        ),
                        "nested" to false,
                    )
                )
            }
        },
        // https://duckdb.org/docs/sql/data_types/text
        // sqlglot: parsers.duckdb._convert_text_type
        DType.TEXT to { dtype: DataType ->
            dtype.set("expressions", null)
            dtype
        },
    )

    // sqlglot: DuckDBParser.STATEMENT_PARSERS
    val STATEMENT_PARSERS: Map<TokenType, (Parser) -> Expression> =
        BaseParserTables.STATEMENT_PARSERS + mapOf<TokenType, (Parser) -> Expression>(
            TokenType.ATTACH to { p -> (p as DuckdbParser).parseAttachDetach() },
            TokenType.DETACH to { p -> (p as DuckdbParser).parseAttachDetach(isAttach = false) },
            TokenType.FORCE to { p -> (p as DuckdbParser).parseForce() },
            TokenType.INSTALL to { p -> (p as DuckdbParser).parseInstall() },
            TokenType.SHOW to { p -> (p as DuckdbParser).parseShow() },
        )

    // sqlglot: DuckDBParser.SET_PARSERS extras
    val SET_PARSERS_EXTRA: Map<String, (Parser) -> Expression?> = mapOf(
        "VARIABLE" to { p -> p.parseSetItemAssignment("VARIABLE") },
    )
}
