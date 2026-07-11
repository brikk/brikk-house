package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.AlterModifySqlSecurity
import dev.brikk.house.sql.ast.And
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.AnonymousAggFunc
import dev.brikk.house.sql.ast.AnyValue
import dev.brikk.house.sql.ast.Apply
import dev.brikk.house.sql.ast.ApproxDistinct
import dev.brikk.house.sql.ast.Args
import dev.brikk.house.sql.ast.ArrayCompact
import dev.brikk.house.sql.ast.ArrayConcat
import dev.brikk.house.sql.ast.ArrayContains
import dev.brikk.house.sql.ast.ArrayDistinct
import dev.brikk.house.sql.ast.ArrayExcept
import dev.brikk.house.sql.ast.ArrayFilter
import dev.brikk.house.sql.ast.ArrayMax
import dev.brikk.house.sql.ast.ArrayMin
import dev.brikk.house.sql.ast.ArrayReverse
import dev.brikk.house.sql.ast.ArraySlice
import dev.brikk.house.sql.ast.ArraySum
import dev.brikk.house.sql.ast.AssumeColumnConstraint
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.CheckColumnConstraint
import dev.brikk.house.sql.ast.CityHash64
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Columns
import dev.brikk.house.sql.ast.CombinedAggFunc
import dev.brikk.house.sql.ast.CombinedParameterizedAgg
import dev.brikk.house.sql.ast.Connector
import dev.brikk.house.sql.ast.CosineDistance
import dev.brikk.house.sql.ast.CountIf
import dev.brikk.house.sql.ast.CurrentDatabase
import dev.brikk.house.sql.ast.CurrentSchemas
import dev.brikk.house.sql.ast.CurrentVersion
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.DateSub
import dev.brikk.house.sql.ast.DefinerProperty
import dev.brikk.house.sql.ast.Detach
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.EngineProperty
import dev.brikk.house.sql.ast.Escape
import dev.brikk.house.sql.ast.EuclideanDistance
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Final
import dev.brikk.house.sql.ast.GenerateSeries
import dev.brikk.house.sql.ast.GroupConcat
import dev.brikk.house.sql.ast.ILike
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.In
import dev.brikk.house.sql.ast.IndexColumnConstraint
import dev.brikk.house.sql.ast.JSONCast
import dev.brikk.house.sql.ast.JSONExtractScalar
import dev.brikk.house.sql.ast.JSONPath
import dev.brikk.house.sql.ast.JSONPathKey
import dev.brikk.house.sql.ast.JSONPathRoot
import dev.brikk.house.sql.ast.JSONPathSubscript
import dev.brikk.house.sql.ast.JarowinklerSimilarity
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Lambda
import dev.brikk.house.sql.ast.Length
import dev.brikk.house.sql.ast.Levenshtein
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Ln
import dev.brikk.house.sql.ast.Log
import dev.brikk.house.sql.ast.MD5Digest
import dev.brikk.house.sql.ast.NestedJSONSelect
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.OnCluster
import dev.brikk.house.sql.ast.Or
import dev.brikk.house.sql.ast.ParameterizedAgg
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.ParseDatetime
import dev.brikk.house.sql.ast.Partition
import dev.brikk.house.sql.ast.PartitionId
import dev.brikk.house.sql.ast.PartitionedByProperty
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.ProjectionDef
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.Quantile
import dev.brikk.house.sql.ast.Rand
import dev.brikk.house.sql.ast.RegexpExtract
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.RegexpSplit
import dev.brikk.house.sql.ast.ReplacePartition
import dev.brikk.house.sql.ast.SHA2
import dev.brikk.house.sql.ast.Split
import dev.brikk.house.sql.ast.StrToDate
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.SubstringIndex
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TimeToStr
import dev.brikk.house.sql.ast.TimestampAdd
import dev.brikk.house.sql.ast.TimestampSub
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.Transform
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.Typeof
import dev.brikk.house.sql.ast.UtcTimestamp
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.VarMap
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.Xor
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.applyTimeUnitCoercion
import dev.brikk.house.sql.parser.buildVarMap
import dev.brikk.house.sql.parser.fromArgList
import dev.brikk.house.sql.ast.Array as ArrayNode

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: helper.is_int
private fun isInt(text: String): Boolean = text.toLongOrNull() != null

// sqlglot: Dialect["clickhouse"].format_time — the TIME_MAPPING is empty, so string
// literals pass through unchanged (rebuilt as fresh Literals like the Python original).
private fun clickhouseFormatTime(expression: Expression?): Expression? {
    if (expression is Literal && expression.isString) {
        return Literal(args("this" to expression.thisArg, "is_string" to true))
    }
    return expression
}

// sqlglot: parsers.clickhouse._build_datetime_format
private fun buildDatetimeFormat(
    factory: (Args) -> Expression,
): (List<Expression?>) -> Expression = { a ->
    // sqlglot: build_formatted_time(expr_type)(args, dialect)
    val expr = factory(
        args("this" to seqGet(a, 0), "format" to clickhouseFormatTime(seqGet(a, 1)))
    )
    val timezone = seqGet(a, 2)
    if (timezone != null) expr.set("zone", timezone)
    expr
}

// sqlglot: parsers.clickhouse._build_count_if
private fun buildCountIf(a: List<Expression?>): Expression {
    if (a.size == 1) return CountIf(args("this" to seqGet(a, 0)))
    return CombinedAggFunc(args("this" to "countIf", "expressions" to a))
}

// sqlglot: parsers.clickhouse._build_str_to_date
private fun buildStrToDate(a: List<Expression?>): Expression {
    if (a.size == 3) return Anonymous(args("this" to "STR_TO_DATE", "expressions" to a))

    val strtodate = fromArgList(listOf("this", "format", "safe", "default_year"), false) { StrToDate(it) }(a)
    return Cast(args("this" to strtodate, "to" to DataType(args("this" to DType.DATETIME))))
}

// sqlglot: parsers.clickhouse._build_timestamp_trunc
private fun buildTimestampTrunc(unit: String): (List<Expression?>) -> Expression = { a ->
    TimestampTrunc(
        args(
            "this" to seqGet(a, 0),
            "unit" to Var(args("this" to unit)),
            "zone" to seqGet(a, 1),
        )
    )
}

// sqlglot: parsers.clickhouse._build_split
private fun buildSplit(factory: (Args) -> Expression): (List<Expression?>) -> Expression = { a ->
    factory(
        args("this" to seqGet(a, 1), "expression" to seqGet(a, 0), "limit" to seqGet(a, 2))
    )
}

// sqlglot: parsers.clickhouse._build_split_by_char
private fun buildSplitByChar(a: List<Expression?>): Expression {
    val sep = seqGet(a, 0)
    if (sep is Literal && sep.isString &&
        sep.name.encodeToByteArray().size == 1
    ) {
        return buildSplit { argsIn: Args -> Split(argsIn) }(a)
    }

    return Anonymous(args("this" to "splitByChar", "expressions" to a))
}

// sqlglot: dialect.build_date_delta (default_unit=None)
private fun buildDateDelta(
    factory: (Args) -> Expression,
    supportsTimezone: Boolean = false,
): (List<Expression?>) -> Expression = { a ->
    val unitBased = a.size >= 3
    val hasTimezone = a.size == 4
    val this_ = if (unitBased) a[2] else seqGet(a, 0)
    // default_unit=None: unit is only set in the unit-based form
    val unit = if (unitBased) a[0] else null
    // sqlglot: exp.TimeUnit.__init__ — DateAdd & co. extend TimeUnit, whose constructor
    // converts var-like unit args into Vars
    val expr = applyTimeUnitCoercion(
        factory(args("this" to this_, "expression" to seqGet(a, 1), "unit" to unit))
    )
    if (supportsTimezone && hasTimezone) expr.set("zone", a.last())
    expr
}

// sqlglot: dialect.build_like (clickhouse imports the dialect-level builder)
private fun buildDialectLike(
    factory: (Args) -> Expression,
    notLike: Boolean = false,
): (List<Expression?>) -> Expression = { a ->
    var likeExpr: Expression =
        factory(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))

    val escape = seqGet(a, 2)
    if (escape != null) {
        likeExpr = Escape(args("this" to likeExpr, "expression" to escape))
    }

    if (notLike) likeExpr = Not(args("this" to likeExpr)) else Unit
    likeExpr
}

// sqlglot: dialect.build_json_extract_path(exp.JSONExtractScalar, zero_based_indexing=False)
private fun buildJsonExtractStringPath(fnArgs: List<Expression?>): Expression {
    val segments = mutableListOf<Expression>(JSONPathRoot())
    for (arg in fnArgs.drop(1)) {
        if (arg !is Literal) {
            // We use the fallback parser because we can't really transpile non-literals safely
            return fromArgList(
                listOf("this", "expression", "only_json_types", "expressions", "json_type", "scalar_only"),
                true,
            ) { JSONExtractScalar(it) }(fnArgs)
        }
        val text = arg.name
        if (isInt(text)) {
            segments.add(JSONPathSubscript(args("this" to (text.toInt() - 1))))
        } else {
            segments.add(JSONPathKey(args("this" to text)))
        }
    }

    return JSONExtractScalar(
        args(
            "this" to seqGet(fnArgs, 0),
            "expression" to JSONPath(args("expressions" to segments)),
            "only_json_types" to false,
        )
    )
}

// sqlglot: exp._combine (wrap=True) as used by exp.and_/or_/xor
private fun combineConnector(
    factory: (Args) -> Expression,
    expressions: List<Expression?>,
): Expression {
    val conditions = expressions.filterNotNull()
    fun wrap(e: Expression): Expression = if (e is Connector) Paren(args("this" to e)) else e

    var this_ = wrap(conditions.first())
    if (conditions.size == 1) this_ = conditions.first()
    for (expr in conditions.drop(1)) {
        this_ = factory(args("this" to this_, "expression" to wrap(expr)))
    }
    return this_
}

/**
 * Port of sqlglot's ClickHouseParser (reference/sqlglot/sqlglot/parsers/clickhouse.py).
 * Table merges live in [ClickhouseParserTables]; flag overrides and method overrides below.
 */
// sqlglot: parsers.clickhouse.ClickHouseParser
open class ClickhouseParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.ClickhouseTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.CLICKHOUSE

    // sqlglot: ClickHouseParser.MODIFIERS_ATTACHED_TO_SET_OP = False
    override val modifiersAttachedToSetOp: Boolean get() = false

    // sqlglot: ClickHouseParser.INTERVAL_SPANS = False
    override val intervalSpans: Boolean get() = false

    // sqlglot: ClickHouseParser.OPTIONAL_ALIAS_TOKEN_CTE = False
    override val optionalAliasTokenCte: Boolean get() = false

    // sqlglot: ClickHouseParser.JOINS_HAVE_EQUAL_PRECEDENCE = True
    override val joinsHaveEqualPrecedence: Boolean get() = true

    // sqlglot: ClickHouse.INDEX_OFFSET = 1
    override val indexOffset: Int get() = 1

    // sqlglot: ClickHouse.NULL_ORDERING = "nulls_are_last"
    override val nullOrdering: String get() = "nulls_are_last"

    // sqlglot: ClickHouse.SAFE_DIVISION = True
    override val safeDivision: Boolean get() = true

    // sqlglot: ClickHouse.SUPPORTS_USER_DEFINED_TYPES = False
    override val supportsUserDefinedTypes: Boolean get() = false

    // sqlglot: ClickHouse.PRESERVE_ORIGINAL_NAMES = True
    override val preserveOriginalNames: Boolean get() = true

    // sqlglot: ClickHouse.CREATABLE_KIND_MAPPING = {"DATABASE": "SCHEMA"}
    override val creatableKindMapping: Map<String, String> get() = mapOf("DATABASE" to "SCHEMA")

    // sqlglot: ClickHouse.SET_OP_DISTINCT_BY_DEFAULT (Except/Intersect False, Union None)
    override fun setOpDistinctByDefault(setOpToken: TokenType): Boolean? = when (setOpToken) {
        TokenType.EXCEPT, TokenType.INTERSECT -> false
        TokenType.UNION -> null
        else -> true
    }

    // Table overrides (sqlglot: ClickHouseParser class-level dict/set merges)

    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = ClickhouseParserTables.FUNCTIONS

    override val funcTokens: Set<TokenType> get() = ClickhouseParserTables.FUNC_TOKENS

    override val reservedTokens: Set<TokenType> get() = ClickhouseParserTables.RESERVED_TOKENS

    override val idVarTokens: Set<TokenType> get() = ClickhouseParserTables.ID_VAR_TOKENS

    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = ClickhouseParserTables.FUNCTION_PARSERS

    override val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = ClickhouseParserTables.PROPERTY_PARSERS

    override val noParenFunctionParsers: Map<String, (Parser) -> Expression?>
        get() = ClickhouseParserTables.NO_PAREN_FUNCTION_PARSERS

    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = ClickhouseParserTables.NO_PAREN_FUNCTIONS

    override val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?>
        get() = ClickhouseParserTables.RANGE_PARSERS

    override val columnOperators: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?>
        get() = ClickhouseParserTables.COLUMN_OPERATORS

    override val joinKinds: Set<TokenType> get() = ClickhouseParserTables.JOIN_KINDS

    override val tableAliasTokens: Set<TokenType>
        get() = ClickhouseParserTables.TABLE_ALIAS_TOKENS

    override val aliasTokens: Set<TokenType> get() = ClickhouseParserTables.ALIAS_TOKENS

    override val queryModifierParsers: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>>
        get() = ClickhouseParserTables.QUERY_MODIFIER_PARSERS

    override val constraintParsers: Map<String, (Parser) -> Expression?>
        get() = ClickhouseParserTables.CONSTRAINT_PARSERS

    // sqlglot: ClickHouseParser.ALTER_PARSERS (plus MODIFY/REPLACE; the base table is
    // an instance property, so the merge happens here rather than in the tables object)
    override val alterParsers: Map<String, (Parser) -> kotlin.Any?>
        get() = super.alterParsers + mapOf<String, (Parser) -> kotlin.Any?>(
            "MODIFY" to { p -> (p as ClickhouseParser).parseAlterTableModify() },
            "REPLACE" to { p -> (p as ClickhouseParser).parseAlterTableReplace() },
        )

    override val schemaUnnamedConstraints: Set<String>
        get() = ClickhouseParserTables.SCHEMA_UNNAMED_CONSTRAINTS

    override val placeholderParsers: Map<TokenType, (Parser) -> Expression?>
        get() = ClickhouseParserTables.PLACEHOLDER_PARSERS

    override val statementParsers: Map<TokenType, (Parser) -> Expression>
        get() = ClickhouseParserTables.STATEMENT_PARSERS

    // ------------------------------------------------------------------
    // Aggregate-combinator resolution
    // ------------------------------------------------------------------

    // sqlglot: ClickHouseParser._resolve_clickhouse_agg
    protected fun resolveClickhouseAgg(nameIn: String): Pair<String, List<String>>? {
        var name = nameIn
        val accumulatedSuffixes = ArrayDeque<String>()
        var parts: Pair<String, String?>? = AGG_FUNC_MAPPING[name]
        while (parts == null) {
            var stripped = false
            for (suffix in AGG_FUNCTIONS_SUFFIXES) {
                if (name.endsWith(suffix) && name.length != suffix.length) {
                    accumulatedSuffixes.addFirst(suffix)
                    name = name.dropLast(suffix.length)
                    stripped = true
                    break
                }
            }
            if (!stripped) return null
            parts = AGG_FUNC_MAPPING[name]
        }

        val (aggFuncName, innerSuffix) = parts
        if (innerSuffix != null) accumulatedSuffixes.addFirst(innerSuffix)

        return aggFuncName to accumulatedSuffixes.toList()
    }

    // ------------------------------------------------------------------
    // Method overrides (sqlglot: ClickHouseParser methods)
    // ------------------------------------------------------------------

    // sqlglot: ClickHouseParser._parse_wrapped_select_or_assignment
    protected fun parseWrappedSelectOrAssignment(): Expression? =
        parseWrapped({ parseSelect() ?: parseAssignment() }, optional = true)

    // sqlglot: ClickHouseParser._parse_check_constraint
    override fun parseCheckConstraint(): Expression? = expression(
        CheckColumnConstraint(args("this" to parseWrappedSelectOrAssignment()))
    )

    // sqlglot: ClickHouseParser._parse_assume_constraint
    open fun parseAssumeConstraint(): Expression? = expression(
        AssumeColumnConstraint(args("this" to parseWrappedSelectOrAssignment()))
    )

    // sqlglot: ClickHouseParser._parse_engine_property
    open fun parseEngineProperty(): Expression {
        match(TokenType.EQ)
        return expression(
            EngineProperty(args("this" to parseField(anyToken = true, anonymousFunc = true)))
        )
    }

    // sqlglot: ClickHouseParser._parse_user_defined_function_expression
    // https://clickhouse.com/docs/en/sql-reference/statements/create/function
    override fun parseUserDefinedFunctionExpression(): Expression? = parseLambda()

    // sqlglot: ClickHouseParser._parse_types — mark every type as non-nullable (the
    // ClickHouse default), unless it's already marked as nullable
    override fun parseTypes(
        checkFunc: Boolean,
        schema: Boolean,
        allowIdentifiers: Boolean,
        withCollation: Boolean,
    ): Expression? {
        val dtype = super.parseTypes(
            checkFunc = checkFunc,
            schema = schema,
            allowIdentifiers = allowIdentifiers,
            withCollation = withCollation,
        )
        if (dtype is DataType && dtype.args["nullable"] != true) {
            dtype.set("nullable", false)
        }

        return dtype
    }

    // sqlglot: ClickHouseParser._parse_extract
    override fun parseExtract(): Expression {
        val startIndex = index
        val this_ = parseBitwise()
        if (match(TokenType.FROM)) {
            retreat(startIndex)
            return super.parseExtract()
        }

        // We return Anonymous here because extract and regexpExtract have different semantics
        match(TokenType.COMMA)
        return expression(
            Anonymous(args("this" to "extract", "expressions" to listOf(this_, parseBitwise())))
        )
    }

    // sqlglot: ClickHouseParser._parse_assignment — ternary `cond ? a : b`
    override fun parseAssignment(): Expression? {
        val this_ = super.parseAssignment()

        if (match(TokenType.PLACEHOLDER)) {
            return expression(
                If(
                    args(
                        "this" to this_,
                        "true" to parseAssignment(),
                        "false" to (if (match(TokenType.COLON)) parseAssignment() else false),
                    )
                )
            )
        }

        return this_
    }

    /**
     * sqlglot: ClickHouseParser._parse_query_parameter — placeholder expressions like
     * SELECT {abc: UInt32} or FROM {table: Identifier}.
     */
    open fun parseQueryParameter(): Expression? {
        val startIndex = index

        var this_: Expression? = parseIdVar()
        match(TokenType.COLON)
        val kind: kotlin.Any? = parseTypes(checkFunc = false, allowIdentifiers = false)
            ?: (if (matchTextSeq("IDENTIFIER")) "Identifier" else null)

        if (kind == null) {
            retreat(startIndex)
            return null
        } else if (!match(TokenType.R_BRACE)) {
            raiseError("Expecting }")
        }

        if (this_ is Identifier && this_.args["quoted"] != true) {
            this_ = Var(args("this" to this_.name))
        }

        return expression(Placeholder(args("this" to this_, "kind" to kind)))
    }

    // sqlglot: ClickHouseParser._parse_bracket
    override fun parseBracket(this_: Expression?): Expression? {
        if (this_ != null) {
            var bracketJsonType: Expression? = null

            while (matchPair(TokenType.L_BRACKET, TokenType.R_BRACKET)) {
                bracketJsonType = DataType(
                    args(
                        "this" to DType.ARRAY,
                        "expressions" to listOf(
                            bracketJsonType
                                ?: DataType(args("this" to DType.JSON, "nullable" to false))
                        ),
                        "nested" to true,
                    )
                )
            }

            if (bracketJsonType != null) {
                return expression(JSONCast(args("this" to this_, "to" to bracketJsonType)))
            }
        }

        val lBrace = match(TokenType.L_BRACE, advance = false)
        val bracket = super.parseBracket(this_)

        if (lBrace && bracket is Struct) {
            val varmap = VarMap(
                args("keys" to ArrayNode(), "values" to ArrayNode())
            )
            for (expr in bracket.expressionsArg) {
                if (expr !is PropertyEQ) break

                (varmap.args["keys"] as Expression).append(
                    "expressions", Literal.string(expr.name)
                )
                (varmap.args["values"] as Expression).append(
                    "expressions", expr.args["expression"]
                )
            }

            return varmap
        }

        return bracket
    }

    // sqlglot: ClickHouseParser._parse_global_in
    open fun parseGlobalIn(this_: Expression?): Expression? {
        val isNegated = match(TokenType.NOT)
        var inExpr: Expression? = null
        if (match(TokenType.IN)) {
            inExpr = parseIn(this_)
            inExpr.set("is_global", true)
        }
        return if (isNegated) expression(Not(args("this" to inExpr))) else inExpr
    }

    // sqlglot: ClickHouseParser._parse_table
    override fun parseTable(
        schema: Boolean,
        joins: Boolean,
        aliasTokens: Collection<TokenType>?,
        parseBracket: Boolean,
        isDbReference: Boolean,
        parsePartition: Boolean,
        consumePipe: Boolean,
    ): Expression? {
        var this_ = super.parseTable(
            schema = schema,
            joins = joins,
            aliasTokens = aliasTokens,
            parseBracket = parseBracket,
            isDbReference = isDbReference,
            parsePartition = false,
            consumePipe = false,
        )

        if (this_ is Table) {
            val inner = this_.thisArg
            val alias = this_.args["alias"] as? TableAlias

            if (inner is GenerateSeries && alias != null &&
                (alias.args["columns"] as? List<*>).isNullOrEmpty()
            ) {
                alias.set(
                    "columns",
                    listOf(Identifier(args("this" to "generate_series", "quoted" to false))),
                )
            }
        }

        if (match(TokenType.FINAL)) {
            this_ = expression(Final(args("this" to this_)))
        }

        return this_
    }

    // sqlglot: ClickHouseParser._parse_position
    override fun parsePosition(haystackFirst: Boolean): Expression =
        super.parsePosition(haystackFirst = true)

    // sqlglot: ClickHouseParser._parse_cte
    // https://clickhouse.com/docs/en/sql-reference/statements/select/with/
    override fun parseCte(): Expression? {
        // WITH <identifier> AS <subquery expression>
        var cte: Expression? = tryParse({ super.parseCte() })

        if (cte == null) {
            // WITH <expression> AS <identifier>
            cte = expression(
                CTE(
                    args(
                        "this" to parseAssignment(),
                        "alias" to parseTableAlias(),
                        "scalar" to true,
                    )
                )
            )
        }

        return cte
    }

    // sqlglot: ClickHouseParser._parse_join_parts
    override fun parseJoinParts(): Triple<Token?, Token?, Token?> {
        val isGlobal = if (match(TokenType.GLOBAL)) prevToken else null

        val kindPre = if (matchSet(joinKinds)) prevToken else null
        val side = if (matchSet(joinSides)) prevToken else null
        val kind = if (matchSet(joinKinds)) prevToken else null

        return Triple(isGlobal, side ?: kind, kindPre ?: kind)
    }

    // sqlglot: Table.to_column (expressions/query.py) — used for ARRAY JOIN operands
    private fun tableToColumn(table: Table): Expression {
        val parts = table.parts
        val lastPart = parts.lastOrNull()

        var col: Expression
        if (lastPart is Identifier) {
            // sqlglot: exp.column(*reversed(parts[0:4]), fields=parts[4:])
            val kwargs = mutableListOf<Pair<String, kotlin.Any?>>("this" to lastPart)
            if (parts.size > 1) kwargs.add("table" to parts[parts.size - 2])
            if (parts.size > 2) kwargs.add("db" to parts[parts.size - 3])
            if (parts.size > 3) kwargs.add("catalog" to parts[parts.size - 4])
            col = Column(args(*kwargs.toTypedArray()))
        } else {
            // This branch is reached if a function or array is wrapped in a `Table`
            col = lastPart ?: table
        }

        val alias = table.args["alias"] as? TableAlias
        if (alias != null) {
            col = Alias(args("this" to col, "alias" to alias.thisArg))
        }

        return col
    }

    // sqlglot: ClickHouseParser._parse_join
    override fun parseJoin(
        skipJoinToken: Boolean,
        parseBracket: Boolean,
        aliasTokens: Collection<TokenType>?,
    ): Expression? {
        val join = super.parseJoin(
            skipJoinToken = skipJoinToken, parseBracket = true, aliasTokens = aliasTokens
        )
        if (join != null) {
            val method = join.args["method"]
            join.set("method", null)
            join.set("global_", method)

            // tbl ARRAY JOIN arr <-- this should be a `Column` reference, not a `Table`
            // https://clickhouse.com/docs/en/sql-reference/statements/select/array-join
            if (join is Join && join.kind == "ARRAY") {
                for (table in join.findAll(Table::class).toList()) {
                    table.replace(tableToColumn(table as Table))
                }
            }
        }

        return join
    }

    // sqlglot: ClickHouseParser._parse_function
    override fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>?,
        anonymous: Boolean,
        optionalParens: Boolean,
        anyToken: Boolean,
    ): Expression? {
        var expr = super.parseFunction(
            functions = functions,
            anonymous = anonymous,
            optionalParens = optionalParens,
            anyToken = anyToken,
        )

        var func: Expression? = if (expr is Window) expr.thisArg as? Expression else expr

        // Aggregate functions can be split in 2 parts: <func_name><suffix[es]>
        val parts = if (func is Anonymous) {
            (func.thisArg as? String)?.let { resolveClickhouseAgg(it) }
        } else {
            null
        }

        if (parts != null) {
            val anonFunc = func as Anonymous
            val params = parseFuncParams(anonFunc)

            val instance: Expression = if (parts.second.isNotEmpty()) {
                if (params != null) {
                    CombinedParameterizedAgg(
                        args("this" to anonFunc.thisArg, "expressions" to anonFunc.expressionsArg)
                    )
                } else {
                    CombinedAggFunc(
                        args("this" to anonFunc.thisArg, "expressions" to anonFunc.expressionsArg)
                    )
                }
            } else {
                if (params != null) {
                    ParameterizedAgg(
                        args("this" to anonFunc.thisArg, "expressions" to anonFunc.expressionsArg)
                    )
                } else {
                    AnonymousAggFunc(
                        args("this" to anonFunc.thisArg, "expressions" to anonFunc.expressionsArg)
                    )
                }
            }
            if (params != null) instance.set("params", params)
            func = expression(instance)

            if (expr is Window) {
                // The window's func was parsed as Anonymous in base parser, fix its
                // type to be ClickHouse style CombinedAnonymousAggFunc / AnonymousAggFunc
                expr.set("this", func)
            } else if (params != null) {
                // Params have blocked super()._parse_function() from parsing the following
                // window (if that exists), as they're standing between the function call
                // and the window spec
                expr = parseWindow(func)
            } else {
                expr = func
            }
        }

        return expr
    }

    // sqlglot: ClickHouseParser._parse_func_params
    protected fun parseFuncParams(this_: Expression? = null): List<Expression>? {
        if (matchPair(TokenType.R_PAREN, TokenType.L_PAREN)) {
            return parseCsv { parseLambda() }
        }

        if (match(TokenType.L_PAREN)) {
            val params = parseCsv { parseLambda() }
            matchRParen(this_)
            return params
        }

        return null
    }

    // sqlglot: ClickHouseParser._parse_group_concat
    open fun parseGroupConcat(): Expression {
        val groupArgs = parseCsv { parseLambda() }
        val params = parseFuncParams()

        if (params != null) {
            // groupConcat(sep [, limit])(expr)
            val separator = seqGet(groupArgs, 0)
            val limit = seqGet(groupArgs, 1)
            var this_: Expression? = seqGet(params, 0)
            if (limit != null) {
                this_ = Limit(args("this" to this_, "expression" to limit))
            }
            return expression(GroupConcat(args("this" to this_, "separator" to separator)))
        }

        // groupConcat(expr)
        return expression(GroupConcat(args("this" to seqGet(groupArgs, 0))))
    }

    // sqlglot: ClickHouseParser._parse_quantile
    open fun parseQuantile(): Expression {
        val this_ = parseLambda()
        val params = parseFuncParams()
        if (params != null) {
            return expression(Quantile(args("this" to params[0], "quantile" to this_)))
        }
        return expression(
            Quantile(args("this" to this_, "quantile" to Literal.number("0.5")))
        )
    }

    // sqlglot: ClickHouseParser._parse_wrapped_id_vars
    override fun parseWrappedIdVars(optional: Boolean): MutableList<Expression> =
        super.parseWrappedIdVars(optional = true)

    // sqlglot: ClickHouseParser._parse_column_def
    override fun parseColumnDef(thisIn: Expression?, computedColumn: Boolean): Expression? {
        if (match(TokenType.DOT)) {
            return Dot(args("this" to thisIn, "expression" to parseIdVar()))
        }

        return super.parseColumnDef(thisIn, computedColumn = computedColumn)
    }

    // sqlglot: ClickHouseParser._parse_primary_key
    override fun parsePrimaryKey(
        wrappedOptional: Boolean,
        inProps: Boolean,
        namedPrimaryKey: Boolean,
    ): Expression = super.parsePrimaryKey(
        wrappedOptional = wrappedOptional || inProps,
        inProps = inProps,
        namedPrimaryKey = namedPrimaryKey,
    )

    // sqlglot: ClickHouseParser._parse_on_property
    override fun parseOnProperty(): Expression? {
        val startIndex = index
        if (matchTextSeq("CLUSTER")) {
            val this_ = parseString() ?: parseIdVar()
            if (this_ != null) {
                return expression(OnCluster(args("this" to this_)))
            } else {
                retreat(startIndex)
            }
        }
        return null
    }

    // sqlglot: ClickHouseParser._parse_index_constraint
    open fun parseIndexConstraint(kind: String? = null): Expression {
        // INDEX name1 expr TYPE type1(args) GRANULARITY value
        val this_ = parseIdVar()
        val expr = parseAssignment()

        val indexType: kotlin.Any? =
            if (matchTextSeq("TYPE")) (parseFunction() ?: parseVar()) else false

        val granularity: kotlin.Any? = if (matchTextSeq("GRANULARITY")) parseTerm() else false

        return expression(
            IndexColumnConstraint(
                args(
                    "this" to this_,
                    "expression" to expr,
                    "index_type" to indexType,
                    "granularity" to granularity,
                )
            )
        )
    }

    // sqlglot: ClickHouseParser._parse_partition
    // https://clickhouse.com/docs/en/sql-reference/statements/alter/partition
    override fun parsePartition(): Expression? {
        if (!match(TokenType.PARTITION)) return null

        val expressions: List<Expression> = if (matchTextSeq("ID")) {
            // Corresponds to the PARTITION ID <string_value> syntax
            listOf(expression(PartitionId(args("this" to parseString()))))
        } else {
            parseExpressions()
        }

        return expression(Partition(args("expressions" to expressions)))
    }

    // sqlglot: ClickHouseParser._parse_alter_table_replace
    open fun parseAlterTableReplace(): Expression? {
        val partition = parsePartition()

        if (partition == null || !match(TokenType.FROM)) return null

        return expression(
            ReplacePartition(args("expression" to partition, "source" to parseTableParts()))
        )
    }

    // sqlglot: ClickHouseParser._parse_alter_table_modify
    open fun parseAlterTableModify(): Expression? {
        val properties = parseProperties()
        if (properties != null) {
            return expression(
                AlterModifySqlSecurity(args("expressions" to properties.expressionsArg))
            )
        }
        return null
    }

    // sqlglot: ClickHouseParser._parse_definer
    override fun parseDefiner(): Expression? {
        match(TokenType.EQ)
        if (match(TokenType.CURRENT_USER)) {
            return DefinerProperty(args("this" to Var(args("this" to prevToken.text.uppercase()))))
        }
        return DefinerProperty(args("this" to parseString()))
    }

    // sqlglot: ClickHouseParser._parse_projection_def
    open fun parseProjectionDef(): Expression? {
        if (!match(TokenType.PROJECTION)) return null

        return expression(
            ProjectionDef(
                args(
                    "this" to parseIdVar(),
                    "expression" to parseWrapped({ parseStatement() }),
                )
            )
        )
    }

    // sqlglot: ClickHouseParser._parse_constraint
    override fun parseConstraint(): Expression? =
        super.parseConstraint() ?: parseProjectionDef()

    // sqlglot: ClickHouseParser._parse_alias — "SELECT <expr> APPLY(...)" is a query
    // modifier, so "APPLY" shouldn't be parsed as <expr>'s alias
    override fun parseAlias(this_: Expression?, explicit: Boolean): Expression? {
        if (matchPair(TokenType.APPLY, TokenType.L_PAREN, advance = false)) {
            return this_
        }

        return super.parseAlias(this_, explicit = explicit)
    }

    // sqlglot: ClickHouseParser._parse_expression — "SELECT <expr> [APPLY(func)] [...]]"
    override fun parseExpression(): Expression? {
        var this_ = super.parseExpression()

        while (matchPair(TokenType.APPLY, TokenType.L_PAREN)) {
            this_ = Apply(args("this" to this_, "expression" to parseVar(anyToken = true)))
            match(TokenType.R_PAREN)
        }

        return this_
    }

    // sqlglot: ClickHouseParser._parse_columns
    open fun parseColumns(): Expression {
        var this_: Expression = expression(Columns(args("this" to parseLambda())))

        while (nextToken.exists && matchTextSeq(")", "APPLY", "(")) {
            match(TokenType.R_PAREN)
            this_ = Apply(args("this" to this_, "expression" to parseVar(anyToken = true)))
        }
        return this_
    }

    // sqlglot: ClickHouseParser._parse_value — canonicalize VALUES into tuple-of-tuples
    override fun parseValue(values: Boolean): Expression? {
        val value = super.parseValue(values) ?: return null

        // In Clickhouse "SELECT * FROM VALUES (1, 2, 3)" generates a table with a single
        // column, in contrast to other dialects.
        val expressions = value.expressionsArg
        if (values && expressions.isNotEmpty() && expressions.last() !is Tuple) {
            value.set(
                "expressions",
                expressions.map { expr ->
                    expression(Tuple(args("expressions" to listOf(expr))))
                },
            )
        }

        return value
    }

    // sqlglot: ClickHouseParser._parse_partitioned_by — ClickHouse allows custom
    // expressions as partition key
    override fun parsePartitionedBy(): Expression = expression(
        PartitionedByProperty(args("this" to parseAssignment()))
    )

    // sqlglot: ClickHouseParser._parse_detach
    open fun parseDetach(): Expression {
        val kind: kotlin.Any? = if (matchSet(dbCreatables)) prevToken.text.uppercase() else false
        val exists = parseExists()
        val this_ = parseTableParts()

        return expression(
            Detach(
                args(
                    "this" to this_,
                    "kind" to kind,
                    "exists" to exists,
                    "cluster" to (if (match(TokenType.ON)) parseOnProperty() else null),
                    "permanent" to matchTextSeq("PERMANENTLY"),
                    "sync" to matchTextSeq("SYNC"),
                )
            )
        )
    }

    // Accessor for the protected base parseFunctionArgs (used by the TUPLE/AND/OR/XOR
    // FUNCTION_PARSERS table entries)
    internal fun clickhouseFunctionArgs(alias: Boolean): MutableList<Expression> =
        parseFunctionArgs(alias = alias)

    // sqlglot: ClickHouseParser SETTINGS query-modifier body (the lambda advances past
    // the SETTINGS token before parsing the assignment list)
    internal fun parseSettingsModifier(): List<Expression> {
        advance()
        return parseCsv { parseAssignment() }
    }

    // sqlglot: ClickHouseParser FORMAT query-modifier body
    internal fun parseFormatModifier(): Expression? {
        advance()
        return parseIdVar()
    }

    companion object {
        // sqlglot: parsers.clickhouse.TIMESTAMP_TRUNC_UNITS — 'week' is skipped since
        // toStartOfWeek takes an extra mode argument
        val TIMESTAMP_TRUNC_UNITS: Set<String> = setOf(
            "MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY",
            "MONTH", "QUARTER", "YEAR",
        )

        // sqlglot: parsers.clickhouse.AGG_FUNCTIONS
        val AGG_FUNCTIONS: Set<String> = setOf(
            "count", "min", "max", "sum", "avg", "any", "stddevPop", "stddevSamp",
            "varPop", "varSamp", "corr", "covarPop", "covarSamp", "entropy",
            "exponentialMovingAverage", "intervalLengthSum", "kolmogorovSmirnovTest",
            "mannWhitneyUTest", "median", "rankCorr", "sumKahan", "studentTTest",
            "welchTTest", "anyHeavy", "anyLast", "boundingRatio", "first_value",
            "last_value", "argMin", "argMax", "avgWeighted", "topK", "approx_top_sum",
            "topKWeighted", "deltaSum", "deltaSumTimestamp", "groupArray",
            "groupArrayLast", "groupConcat", "groupUniqArray", "groupArrayInsertAt",
            "groupArrayMovingAvg", "groupArrayMovingSum", "groupArraySample",
            "groupBitAnd", "groupBitOr", "groupBitXor", "groupBitmap", "groupBitmapAnd",
            "groupBitmapOr", "groupBitmapXor", "sumWithOverflow", "sumMap", "minMap",
            "maxMap", "skewSamp", "skewPop", "kurtSamp", "kurtPop", "uniq", "uniqExact",
            "uniqCombined", "uniqCombined64", "uniqHLL12", "uniqTheta", "quantile",
            "quantiles", "quantileExact", "quantilesExact", "quantilesExactExclusive",
            "quantileExactLow", "quantilesExactLow", "quantileExactHigh",
            "quantilesExactHigh", "quantileExactWeighted", "quantilesExactWeighted",
            "quantileTiming", "quantilesTiming", "quantileTimingWeighted",
            "quantilesTimingWeighted", "quantileDeterministic", "quantilesDeterministic",
            "quantileTDigest", "quantilesTDigest", "quantileTDigestWeighted",
            "quantilesTDigestWeighted", "quantileBFloat16", "quantilesBFloat16",
            "quantileBFloat16Weighted", "quantilesBFloat16Weighted",
            "simpleLinearRegression", "stochasticLinearRegression",
            "stochasticLogisticRegression", "categoricalInformationValue", "contingency",
            "cramersV", "cramersVBiasCorrected", "theilsU", "maxIntersections",
            "maxIntersectionsPosition", "meanZTest", "quantileInterpolatedWeighted",
            "quantilesInterpolatedWeighted", "quantileGK", "quantilesGK", "sparkBar",
            "sumCount", "largestTriangleThreeBuckets", "histogram", "sequenceMatch",
            "sequenceCount", "windowFunnel", "retention", "uniqUpTo",
            "sequenceNextNode", "exponentialTimeDecayedAvg",
        )

        // sqlglot: parsers.clickhouse.AGG_FUNCTIONS_SUFFIXES (sorted longest-first)
        val AGG_FUNCTIONS_SUFFIXES: List<String> = listOf(
            "If", "Array", "ArrayIf", "Map", "SimpleState", "State", "Merge",
            "MergeState", "ForEach", "Distinct", "OrDefault", "OrNull", "Resample",
            "ArgMin", "ArgMax",
        ).sortedByDescending { it.length }

        // sqlglot: parsers.clickhouse.AGG_FUNC_MAPPING — memoized 0- and 1-suffix names
        val AGG_FUNC_MAPPING: Map<String, Pair<String, String?>> = buildMap {
            for (sfx in AGG_FUNCTIONS_SUFFIXES) {
                for (f in AGG_FUNCTIONS) {
                    put("$f$sfx", f to sfx)
                }
            }
            for (f in AGG_FUNCTIONS) put(f, f to null)
        }
    }
}

/**
 * Merged parser tables for ClickHouse (sqlglot: ClickHouseParser class-level dict merges).
 * Kept in an object so the merges happen once.
 */
object ClickhouseParserTables {

    // sqlglot: ClickHouseParser.FUNCTIONS (base minus TRANSFORM/APPROX_TOP_SUM plus
    // the ClickHouse-specific builders)
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(BaseParserTables.FUNCTIONS)
        remove("TRANSFORM")
        remove("APPROX_TOP_SUM")
        for (name in listOf("REGEXPEXTRACT", "REGEXP_EXTRACT", "REGEXP_SUBSTR")) {
            put(name) { a ->
                RegexpExtract(
                    args(
                        "this" to seqGet(a, 0),
                        "expression" to seqGet(a, 1),
                        "group" to seqGet(a, 2),
                    )
                )
            }
        }
        for (unit in ClickhouseParser.TIMESTAMP_TRUNC_UNITS) {
            put("TOSTARTOF$unit", buildTimestampTrunc(unit))
        }
        put("ANY", fromArgList(listOf("this"), false) { AnyValue(it) })
        put("ARRAYCOMPACT", fromArgList(listOf("this"), false) { ArrayCompact(it) })
        put("ARRAYCONCAT", fromArgList(listOf("this", "expressions", "null_propagation"), true) { ArrayConcat(it) })
        put("ARRAYDISTINCT", fromArgList(listOf("this", "check_null"), false) { ArrayDistinct(it) })
        put("ARRAYEXCEPT", fromArgList(listOf("this", "expression", "is_multiset"), false) { ArrayExcept(it) })
        put("ARRAYSUM", fromArgList(listOf("this", "expression"), false) { ArraySum(it) })
        put("ARRAYMAX", fromArgList(listOf("this"), false) { ArrayMax(it) })
        put("ARRAYMIN", fromArgList(listOf("this"), false) { ArrayMin(it) })
        put("ARRAYREVERSE", fromArgList(listOf("this"), false) { ArrayReverse(it) })
        put("ARRAYSLICE", fromArgList(listOf("this", "start", "end", "step", "zero_based"), false) { ArraySlice(it) })
        put("ARRAYFILTER") { a ->
            ArrayFilter(args("this" to seqGet(a, 1), "expression" to seqGet(a, 0)))
        }
        put("ARRAYMAP") { a ->
            Transform(args("this" to seqGet(a, 1), "expression" to seqGet(a, 0)))
        }
        put("CURRENTDATABASE", fromArgList(listOf(), false) { CurrentDatabase(it) })
        put("CURRENTSCHEMAS", fromArgList(listOf("this"), false) { CurrentSchemas(it) })
        put("COUNTIF", ::buildCountIf)
        put("CITYHASH64", fromArgList(listOf("expressions"), true) { CityHash64(it) })
        put("COSINEDISTANCE", fromArgList(listOf("this", "expression"), false) { CosineDistance(it) })
        put("VERSION", fromArgList(listOf(), false) { CurrentVersion(it) })
        put("DATE_ADD", buildDateDelta({ a: Args -> DateAdd(a) }))
        put("DATEADD", buildDateDelta({ a: Args -> DateAdd(a) }))
        put("DATE_DIFF", buildDateDelta({ a: Args -> DateDiff(a) }, supportsTimezone = true))
        put("DATEDIFF", buildDateDelta({ a: Args -> DateDiff(a) }, supportsTimezone = true))
        put("DATE_FORMAT", buildDatetimeFormat { a: Args -> TimeToStr(a) })
        put("DATE_SUB", buildDateDelta({ a: Args -> DateSub(a) }))
        put("DATESUB", buildDateDelta({ a: Args -> DateSub(a) }))
        put("FORMATDATETIME", buildDatetimeFormat { a: Args -> TimeToStr(a) })
        put("HAS", fromArgList(listOf("this", "expression", "ensure_variant", "check_null"), false) { ArrayContains(it) })
        put("ILIKE", buildDialectLike({ a: Args -> ILike(a) }))
        put("JSONEXTRACTSTRING", ::buildJsonExtractStringPath)
        put("LENGTH") { a -> Length(args("this" to seqGet(a, 0), "binary" to true)) }
        put("LIKE", buildDialectLike({ a: Args -> Like(a) }))
        // NOTE (sqlglot parity): the Python key is "L2Distance", which never matches the
        // parser's uppercased lookup — kept verbatim so behavior is identical.
        put("L2Distance", fromArgList(listOf("this", "expression"), false) { EuclideanDistance(it) })
        put("MAP", ::buildVarMap)
        put("MATCH", fromArgList(listOf("this", "expression", "flag", "full_match"), false) { RegexpLike(it) })
        put("NOTLIKE", buildDialectLike({ a: Args -> Like(a) }, notLike = true))
        put("PARSEDATETIME", buildDatetimeFormat { a: Args -> ParseDatetime(a) })
        put("RANDCANONICAL", fromArgList(listOf("this", "lower", "upper"), false) { Rand(it) })
        put("STR_TO_DATE", ::buildStrToDate)
        put("TIMESTAMP_SUB", buildDateDelta({ a: Args -> TimestampSub(a) }))
        put("TIMESTAMPSUB", buildDateDelta({ a: Args -> TimestampSub(a) }))
        put("TIMESTAMP_ADD", buildDateDelta({ a: Args -> TimestampAdd(a) }))
        put("TIMESTAMPADD", buildDateDelta({ a: Args -> TimestampAdd(a) }))
        put("TOMONDAY", buildTimestampTrunc("WEEK"))
        put("UNIQ", fromArgList(listOf("this", "accuracy"), false) { ApproxDistinct(it) })
        put("MD5", fromArgList(listOf("this", "expressions"), true) { MD5Digest(it) })
        put("SHA256") { a ->
            SHA2(args("this" to seqGet(a, 0), "length" to Literal.number("256")))
        }
        put("SHA512") { a ->
            SHA2(args("this" to seqGet(a, 0), "length" to Literal.number("512")))
        }
        put("SPLITBYCHAR", ::buildSplitByChar)
        put("SPLITBYREGEXP", buildSplit { a: Args -> RegexpSplit(a) })
        put("SPLITBYSTRING", buildSplit { a: Args -> Split(a) })
        put(
            "SUBSTRINGINDEX",
            fromArgList(listOf("this", "delimiter", "count"), false) { SubstringIndex(it) },
        )
        put("TOTYPENAME", fromArgList(listOf("this"), false) { Typeof(it) })
        put(
            "EDITDISTANCE",
            fromArgList(
                listOf("this", "expression", "ins_cost", "del_cost", "sub_cost", "max_dist"),
                false,
            ) { Levenshtein(it) },
        )
        put(
            "JAROWINKLERSIMILARITY",
            fromArgList(
                listOf("this", "expression", "case_insensitive", "integer_scale"), false
            ) { JarowinklerSimilarity(it) },
        )
        put(
            "LEVENSHTEINDISTANCE",
            fromArgList(
                listOf("this", "expression", "ins_cost", "del_cost", "sub_cost", "max_dist"),
                false,
            ) { Levenshtein(it) },
        )
        put("UTCTIMESTAMP", fromArgList(listOf("this"), false) { UtcTimestamp(it) })
        // sqlglot: parser.build_logarithm with ClickHouse.LOG_BASE_FIRST=None and
        // ClickHouseParser.LOG_DEFAULTS_TO_LN=True
        put("LOG") { a ->
            val this_ = seqGet(a, 0)
            val expr = seqGet(a, 1)
            if (expr != null) Log(args("this" to expr, "expression" to this_))
            else Ln(args("this" to this_))
        }
    }

    // sqlglot: ClickHouseParser.FUNC_TOKENS
    val FUNC_TOKENS: Set<TokenType> = BaseParserTables.FUNC_TOKENS + setOf(
        TokenType.AND, TokenType.FILE, TokenType.OR, TokenType.SET,
    )

    // sqlglot: ClickHouseParser.RESERVED_TOKENS = base - {SELECT}
    val RESERVED_TOKENS: Set<TokenType> =
        BaseParserTables.RESERVED_TOKENS - TokenType.SELECT

    // sqlglot: ClickHouseParser.ID_VAR_TOKENS
    val ID_VAR_TOKENS: Set<TokenType> = BaseParserTables.ID_VAR_TOKENS + TokenType.LIKE

    // sqlglot: ClickHouseParser.FUNCTION_PARSERS (base minus MATCH)
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        (BaseParserTables.FUNCTION_PARSERS - "MATCH") + mapOf<String, (Parser) -> Expression?>(
            "ARRAYJOIN" to { p ->
                p.expression(Explode(args("this" to (p as ClickhouseParser).parseExpression())))
            },
            "GROUPCONCAT" to { p -> (p as ClickhouseParser).parseGroupConcat() },
            "QUANTILE" to { p -> (p as ClickhouseParser).parseQuantile() },
            "MEDIAN" to { p -> (p as ClickhouseParser).parseQuantile() },
            "COLUMNS" to { p -> (p as ClickhouseParser).parseColumns() },
            "TUPLE" to { p ->
                Struct(args("expressions" to (p as ClickhouseParser).clickhouseFunctionArgs(alias = true)))
            },
            "AND" to { p ->
                combineConnector({ a: Args -> And(a) }, (p as ClickhouseParser).clickhouseFunctionArgs(alias = false))
            },
            "OR" to { p ->
                combineConnector({ a: Args -> Or(a) }, (p as ClickhouseParser).clickhouseFunctionArgs(alias = false))
            },
            "XOR" to { p ->
                combineConnector({ a: Args -> Xor(a) }, (p as ClickhouseParser).clickhouseFunctionArgs(alias = false))
            },
        )

    // sqlglot: ClickHouseParser.PROPERTY_PARSERS (base minus DYNAMIC plus ENGINE/UUID)
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> =
        (BaseParserTables.PROPERTY_PARSERS - "DYNAMIC") +
            mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
                "ENGINE" to { p, _ -> (p as ClickhouseParser).parseEngineProperty() },
                "UUID" to { p, _ ->
                    p.expression(
                        dev.brikk.house.sql.ast.UuidProperty(args("this" to p.parseString()))
                    )
                },
            )

    // sqlglot: ClickHouseParser.NO_PAREN_FUNCTION_PARSERS (base minus ANY)
    val NO_PAREN_FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.NO_PAREN_FUNCTION_PARSERS - "ANY"

    // sqlglot: ClickHouseParser.NO_PAREN_FUNCTIONS (base minus CURRENT_TIMESTAMP)
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        BaseParserTables.NO_PAREN_FUNCTIONS - TokenType.CURRENT_TIMESTAMP

    // sqlglot: ClickHouseParser.RANGE_PARSERS (plus GLOBAL [NOT] IN)
    val RANGE_PARSERS: Map<TokenType, (Parser, Expression?) -> Expression?> =
        BaseParserTables.RANGE_PARSERS + mapOf<TokenType, (Parser, Expression?) -> Expression?>(
            TokenType.GLOBAL to { p, this_ -> (p as ClickhouseParser).parseGlobalIn(this_) },
        )

    // sqlglot: ClickHouseParser.COLUMN_OPERATORS (base minus PLACEHOLDER plus DOTCARET)
    val COLUMN_OPERATORS: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?> =
        (BaseParserTables.COLUMN_OPERATORS - TokenType.PLACEHOLDER) +
            mapOf<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?>(
                TokenType.DOTCARET to { p, this_, field ->
                    p.expression(NestedJSONSelect(args("this" to this_, "expression" to field)))
                },
            )

    // sqlglot: ClickHouseParser.JOIN_KINDS
    val JOIN_KINDS: Set<TokenType> = BaseParserTables.JOIN_KINDS + setOf(
        TokenType.ALL, TokenType.ANY, TokenType.ASOF, TokenType.ARRAY,
    )

    // sqlglot: ClickHouseParser.TABLE_ALIAS_TOKENS
    val TABLE_ALIAS_TOKENS: Set<TokenType> = BaseParserTables.TABLE_ALIAS_TOKENS - setOf(
        TokenType.ALL, TokenType.ANY, TokenType.ARRAY, TokenType.ASOF, TokenType.FINAL,
        TokenType.FORMAT, TokenType.SETTINGS,
    )

    // sqlglot: ClickHouseParser.ALIAS_TOKENS
    val ALIAS_TOKENS: Set<TokenType> = BaseParserTables.ALIAS_TOKENS - setOf(
        TokenType.FORMAT, TokenType.SETTINGS,
    )

    // sqlglot: ClickHouseParser.QUERY_MODIFIER_PARSERS (plus SETTINGS/FORMAT)
    val QUERY_MODIFIER_PARSERS: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>> =
        BaseParserTables.QUERY_MODIFIER_PARSERS +
            mapOf<TokenType, (Parser) -> Pair<String, kotlin.Any?>>(
                TokenType.SETTINGS to { p ->
                    "settings" to (p as ClickhouseParser).parseSettingsModifier()
                },
                TokenType.FORMAT to { p ->
                    "format" to (p as ClickhouseParser).parseFormatModifier()
                },
            )

    // sqlglot: ClickHouseParser.CONSTRAINT_PARSERS (plus INDEX/CODEC/ASSUME)
    val CONSTRAINT_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.CONSTRAINT_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "INDEX" to { p -> (p as ClickhouseParser).parseIndexConstraint() },
            "CODEC" to { p -> p.parseCompress() },
            "ASSUME" to { p -> (p as ClickhouseParser).parseAssumeConstraint() },
        )

    // sqlglot: ClickHouseParser.SCHEMA_UNNAMED_CONSTRAINTS (plus INDEX minus CHECK)
    val SCHEMA_UNNAMED_CONSTRAINTS: Set<String> =
        (BaseParserTables.SCHEMA_UNNAMED_CONSTRAINTS + "INDEX") - "CHECK"

    // sqlglot: ClickHouseParser.PLACEHOLDER_PARSERS (plus {name: Type})
    val PLACEHOLDER_PARSERS: Map<TokenType, (Parser) -> Expression?> =
        BaseParserTables.PLACEHOLDER_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
            TokenType.L_BRACE to { p -> (p as ClickhouseParser).parseQueryParameter() },
        )

    // sqlglot: ClickHouseParser.STATEMENT_PARSERS (plus DETACH)
    val STATEMENT_PARSERS: Map<TokenType, (Parser) -> Expression> =
        BaseParserTables.STATEMENT_PARSERS + mapOf<TokenType, (Parser) -> Expression>(
            TokenType.DETACH to { p -> (p as ClickhouseParser).parseDetach() },
        )
}
