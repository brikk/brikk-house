package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AlterColumn
import dev.brikk.house.sql.ast.ApproxQuantile
import dev.brikk.house.sql.ast.ArrayAgg
import dev.brikk.house.sql.ast.ArraySize
import dev.brikk.house.sql.ast.ArrayUniqueAgg
import dev.brikk.house.sql.ast.CurrentTimestamp
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.Day
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.FileFormatProperty
import dev.brikk.house.sql.ast.First
import dev.brikk.house.sql.ast.FirstValue
import dev.brikk.house.sql.ast.FromBase64
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.GenerateSeries
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.IgnoreNulls
import dev.brikk.house.sql.ast.JSONExtractScalar
import dev.brikk.house.sql.ast.JSONFormat
import dev.brikk.house.sql.ast.Last
import dev.brikk.house.sql.ast.LastValue
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Ln
import dev.brikk.house.sql.ast.Month
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.QueryTransform
import dev.brikk.house.sql.ast.Quantile
import dev.brikk.house.sql.ast.RegexpExtract
import dev.brikk.house.sql.ast.RegexpExtractAll
import dev.brikk.house.sql.ast.RegexpSplit
import dev.brikk.house.sql.ast.SerdeProperties
import dev.brikk.house.sql.ast.StrToMap
import dev.brikk.house.sql.ast.StrToUnix
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.TimeStrToTime
import dev.brikk.house.sql.ast.TimeToStr
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.ToBase64
import dev.brikk.house.sql.ast.Transform
import dev.brikk.house.sql.ast.TsOrDsAdd
import dev.brikk.house.sql.ast.TsOrDsToDate
import dev.brikk.house.sql.ast.UnixToStr
import dev.brikk.house.sql.ast.UsingProperty
import dev.brikk.house.sql.ast.Year
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.buildVarMap
import dev.brikk.house.sql.parser.formatTimeString
import dev.brikk.house.sql.parser.fromArgList

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: Dialect["hive"].format_time (Hive.TIME_MAPPING)
internal fun hiveFormatTime(expression: Expression?): Expression? {
    if (expression is Literal && expression.isString) {
        val converted = formatTimeString(expression.thisArg as? String, HiveDialect.TIME_MAPPING)
        return Literal(args("this" to converted, "is_string" to true))
    }
    return expression
}

// sqlglot: parsers.hive.build_with_ignore_nulls — FIRST/FIRST_VALUE/LAST/LAST_VALUE with an
// ignore-nulls boolean 2nd arg. IgnoreNulls wraps the target when the flag is true.
private fun buildWithIgnoreNulls(factory: (Expression?) -> Expression): (List<Expression?>) -> Expression =
    { a ->
        val this_ = factory(seqGet(a, 0))
        val flag = seqGet(a, 1)
        // sqlglot: `seq_get(args, 1) == exp.true()` — a boolean TRUE literal (exp.Boolean).
        if (flag is dev.brikk.house.sql.ast.Boolean && flag.thisArg == true) {
            IgnoreNulls(args("this" to this_))
        } else {
            this_
        }
    }

// sqlglot: dialect.build_formatted_time(expr, default=...) — applied with Hive's TIME_MAPPING.
private fun buildFormattedTime(
    default: Any? = null,
    factory: (dev.brikk.house.sql.ast.Args) -> Expression,
): (List<Expression?>) -> Expression = { a ->
    var fmt: Expression? = seqGet(a, 1)
    if (fmt == null) {
        val f = if (default == true) HiveDialect.TIME_FORMAT else default as? String
        fmt = f?.let { Literal(args("this" to it.trim('\''), "is_string" to true)) }
    }
    factory(args("this" to seqGet(a, 0), "format" to hiveFormatTime(fmt)))
}

// sqlglot: dialect.build_regexp_extract(expr) with Hive.REGEXP_EXTRACT_DEFAULT_GROUP=1
private fun hiveBuildRegexpExtract(all: Boolean): (List<Expression?>) -> Expression = { a ->
    val kwargs = args(
        "this" to seqGet(a, 0),
        "expression" to seqGet(a, 1),
        "group" to (seqGet(a, 2) ?: Literal.number("1")),
        "parameters" to seqGet(a, 3),
    )
    // Hive.REGEXP_EXTRACT_POSITION_OVERFLOW_RETURNS_NULL is the base default (false).
    if (all) RegexpExtractAll(kwargs) else RegexpExtract(kwargs)
}

// sqlglot: parsers.hive._build_to_date (safe TsOrDsToDate)
private fun buildToDate(a: List<Expression?>): Expression =
    TsOrDsToDate(
        args("this" to seqGet(a, 0), "format" to hiveFormatTime(seqGet(a, 1)), "safe" to true)
    )

// sqlglot: parsers.hive._build_named_struct — named_struct('k', v, ...) -> Struct of PropertyEQ.
private fun buildNamedStruct(a: List<Expression?>): Expression {
    val expressions = mutableListOf<Expression>()
    var i = 0
    while (i < a.size - 1) {
        val key = a[i]
        val value = a[i + 1]
        val name = key?.name ?: ""
        expressions.add(
            PropertyEQ(args("this" to Identifier(args("this" to name)), "expression" to value))
        )
        i += 2
    }
    return Struct(args("expressions" to expressions))
}

// sqlglot: Expression._binop — wraps Binary operands in parens (matches `x * -1`)
private fun mulNegOne(expression: Expression): Expression {
    val this_ = if (expression is dev.brikk.house.sql.ast.Binary) {
        dev.brikk.house.sql.ast.Paren(args("this" to expression))
    } else {
        expression
    }
    return dev.brikk.house.sql.ast.Mul(
        args("this" to this_, "expression" to dev.brikk.house.sql.ast.Neg(args("this" to Literal.number("1"))))
    )
}

// sqlglot: parsers.hive._build_date_add — DATE_SUB(x, n) -> TsOrDsAdd(x, n * -1, DAY)
private fun buildDateSub(a: List<Expression?>): Expression {
    val expression = seqGet(a, 1)?.let { mulNegOne(it) }
    return dev.brikk.house.sql.parser.applyTimeUnitCoercion(
        TsOrDsAdd(
            args(
                "this" to seqGet(a, 0),
                "expression" to expression,
                "unit" to Literal(args("this" to "DAY", "is_string" to true)),
            )
        )
    )
}

/**
 * Port of sqlglot's HiveParser (reference/sqlglot/sqlglot/parsers/hive.py).
 * Table merges live in [HiveParserTables]; overridden _parse_* methods below.
 */
// sqlglot: parsers.hive.HiveParser
open class HiveParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.HiveTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.HIVE

    // sqlglot: HiveParser.LOG_DEFAULTS_TO_LN = True
    override val logDefaultsToLn: Boolean get() = true

    // sqlglot: HiveParser.STRICT_CAST = False
    override val strictCast: Boolean get() = false

    // sqlglot: HiveParser.VALUES_FOLLOWED_BY_PAREN = False
    override val valuesFollowedByParen: Boolean get() = false

    // sqlglot: HiveParser.JOINS_HAVE_EQUAL_PRECEDENCE = True
    override val joinsHaveEqualPrecedence: Boolean get() = true

    // sqlglot: HiveParser.ADD_JOIN_ON_TRUE = True
    override val addJoinOnTrue: Boolean get() = true

    // sqlglot: HiveParser.ALTER_TABLE_PARTITIONS = True
    override val alterTablePartitions: Boolean get() = true

    // sqlglot: Hive.ALTER_TABLE_SUPPORTS_CASCADE = True
    override val alterTableSupportsCascade: Boolean get() = true

    // sqlglot: Hive.SUPPORTS_USER_DEFINED_TYPES = False
    override val supportsUserDefinedTypes: Boolean get() = false

    // sqlglot: Hive.ALIAS_POST_TABLESAMPLE = True
    override val aliasPostTablesample: Boolean get() = true

    // sqlglot: Hive.SAFE_DIVISION = True
    override val safeDivision: Boolean get() = true

    // sqlglot: HiveParser.CHANGE_COLUMN_ALTER_SYNTAX = False
    protected open val changeColumnAlterSyntax: Boolean get() = false

    // sqlglot: HiveParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = HiveParserTables.FUNCTIONS

    // sqlglot: HiveParser.FUNCTION_PARSERS
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = HiveParserTables.FUNCTION_PARSERS

    // sqlglot: HiveParser.NO_PAREN_FUNCTIONS (base minus CURRENT_TIME)
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = HiveParserTables.NO_PAREN_FUNCTIONS

    // sqlglot: HiveParser.NO_PAREN_FUNCTION_PARSERS (+ TRANSFORM)
    override val noParenFunctionParsers: Map<String, (Parser) -> Expression?>
        get() = HiveParserTables.NO_PAREN_FUNCTION_PARSERS

    // sqlglot: HiveParser.PROPERTY_PARSERS (+ SERDEPROPERTIES / USING)
    override val propertyParsers: Map<String, (Parser, Parser.PropertyKwargs) -> Any?>
        get() = HiveParserTables.PROPERTY_PARSERS

    // sqlglot: HiveParser.ALTER_PARSERS (+ CHANGE)
    override val alterParsers: Map<String, (Parser) -> Any?>
        get() = super.alterParsers + mapOf<String, (Parser) -> Any?>(
            "CHANGE" to { p -> (p as HiveParser).parseAlterTableChange() },
        )

    // sqlglot: HiveParser._parse_transform
    open fun parseTransform(): Expression? {
        if (!match(TokenType.L_PAREN, advance = false)) {
            retreat(index - 1)
            return null
        }

        val transformArgs = parseWrappedCsv({ parseLambda() })
        val rowFormatBefore = parseRowFormat(matchRow = true)

        var recordWriter: Expression? = null
        if (matchTextSeq("RECORDWRITER")) {
            recordWriter = parseString()
        }

        if (!match(TokenType.USING)) {
            return expression(Transform(args("this" to transformArgs.getOrNull(0), "expression" to transformArgs.getOrNull(1))))
        }

        val commandScript = parseString()
        match(TokenType.ALIAS)
        val schema = parseSchema()

        val rowFormatAfter = parseRowFormat(matchRow = true)
        var recordReader: Expression? = null
        if (matchTextSeq("RECORDREADER")) {
            recordReader = parseString()
        }

        return expression(
            QueryTransform(
                args(
                    "expressions" to transformArgs,
                    "command_script" to commandScript,
                    "schema" to schema,
                    "record_writer" to recordWriter,
                    "row_format_before" to rowFormatBefore,
                    "row_format_after" to rowFormatAfter,
                    "record_reader" to recordReader,
                )
            )
        )
    }

    // sqlglot: HiveParser._parse_distinct_arg_function — PERCENTILE / PERCENTILE_APPROX
    open fun parseDistinctArgFunction(factory: (List<Expression?>) -> Expression, distinctIndex: Int = 0): Expression {
        var isDistinct = match(TokenType.DISTINCT)
        if (!isDistinct) match(TokenType.ALL)

        val argList = mutableListOf<Expression?>(parseLambda())
        if (match(TokenType.COMMA)) {
            argList.addAll(parseFunctionArgs())
        }

        val target = argList.getOrNull(distinctIndex)
        if (isDistinct && target != null) {
            argList[distinctIndex] = expression(Distinct(args("expressions" to listOf(target))))
        }

        return factory(argList)
    }

    // sqlglot: HiveParser._parse_alter_table_change (CHANGE COLUMN old new TYPE ...)
    open fun parseAlterTableChange(): Expression? {
        match(TokenType.COLUMN)
        val this_ = parseField(anyToken = true)

        if (changeColumnAlterSyntax && matchTextSeq("TYPE")) {
            return expression(
                AlterColumn(args("this" to this_, "dtype" to parseTypes(schema = true)))
            )
        }

        val columnNew = parseField(anyToken = true)
        val dtype = parseTypes(schema = true)
        // sqlglot: `self._match(COMMENT) and self._parse_string()` — False when absent.
        val comment: Any? = if (match(TokenType.COMMENT)) parseString() else false

        if (this_ == null || columnNew == null || dtype == null) {
            raiseError(
                "Expected 'CHANGE COLUMN' to be followed by 'column_name' 'column_name' 'data_type'"
            )
        }

        return expression(
            AlterColumn(
                args("this" to this_, "rename_to" to columnNew, "dtype" to dtype, "comment" to comment)
            )
        )
    }

    // sqlglot: HiveParser._parse_using_property
    open fun parseUsingProperty(): Expression {
        if (matchTexts(setOf("JAR", "FILE", "ARCHIVE"))) {
            val kind = prevToken.text.uppercase()
            return UsingProperty(args("this" to parseString(), "kind" to kind))
        }
        return parsePropertyAssignment({ a -> FileFormatProperty(a) })
    }

    // sqlglot: HiveParser._parse_types — casts to CHAR/VARCHAR(len) become STRING outside
    // schema definitions (Spark/Hive treat char/varchar as string in expression contexts).
    override fun parseTypes(
        checkFunc: Boolean,
        schema: Boolean,
        allowIdentifiers: Boolean,
        withCollation: Boolean,
    ): Expression? {
        val this_ = super.parseTypes(
            checkFunc = checkFunc,
            schema = schema,
            allowIdentifiers = allowIdentifiers,
            withCollation = withCollation,
        )

        if (this_ != null && !schema) {
            this_.transform(copy = false) { node ->
                if (node is DataType && node.isType(DType.CHAR, DType.VARCHAR)) {
                    node.set("this", DType.TEXT)
                    node.set("expressions", null)
                }
                node
            }
        }
        return this_
    }

    // sqlglot: HiveParser._parse_partition_and_order (PARTITION BY / DISTRIBUTE BY, SORT BY)
    override fun parsePartitionAndOrder(): Pair<List<Expression>, Expression?> {
        val partition = if (matchSet(setOf(TokenType.PARTITION_BY, TokenType.DISTRIBUTE_BY))) {
            parseCsv { parseAssignment() }
        } else {
            emptyList()
        }
        val order = parseOrder(skipOrderToken = match(TokenType.SORT_BY))
        return partition to order
    }

    // sqlglot: HiveParser._parse_parameter — ${var} / ${hiveconf:var}
    override fun parseParameter(): Expression {
        match(TokenType.L_BRACE)
        val this_ = parseIdentifier() ?: parsePrimaryOrVar()
        val expr = if (match(TokenType.COLON)) parseIdentifier() ?: parsePrimaryOrVar() else null
        match(TokenType.R_BRACE)
        return expression(Parameter(args("this" to this_, "expression" to expr)))
    }
}

/**
 * Merged parser tables for Hive (sqlglot: HiveParser class-level dict merges). Kept in an
 * object so the merges happen once.
 */
object HiveParserTables {

    // sqlglot: HiveParser.NO_PAREN_FUNCTIONS — base minus CURRENT_TIME
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        BaseParserTables.NO_PAREN_FUNCTIONS - TokenType.CURRENT_TIME

    // sqlglot: HiveParser.NO_PAREN_FUNCTION_PARSERS
    val NO_PAREN_FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.NO_PAREN_FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "TRANSFORM" to { p -> (p as HiveParser).parseTransform() },
        )

    // sqlglot: HiveParser.FUNCTION_PARSERS
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "PERCENTILE" to { p ->
                (p as HiveParser).parseDistinctArgFunction(::buildQuantile)
            },
            "PERCENTILE_APPROX" to { p ->
                (p as HiveParser).parseDistinctArgFunction(::buildApproxQuantile)
            },
        )

    // sqlglot: HiveParser.PROPERTY_PARSERS
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> Any?> =
        BaseParserTables.PROPERTY_PARSERS + mapOf<String, (Parser, Parser.PropertyKwargs) -> Any?>(
            "SERDEPROPERTIES" to { p, _ ->
                SerdeProperties(args("expressions" to p.parseWrappedCsv({ p.parseProperty() })))
            },
            "USING" to { p, _ -> (p as HiveParser).parseUsingProperty() },
        )

    // sqlglot: HiveParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(BaseParserTables.FUNCTIONS)
        put("BASE64") { a -> ToBase64(args("this" to seqGet(a, 0))) }
        put("COLLECT_LIST") { a -> ArrayAgg(args("this" to seqGet(a, 0), "nulls_excluded" to true)) }
        put("COLLECT_SET") { a -> ArrayUniqueAgg(args("this" to seqGet(a, 0))) }
        put("DATE_ADD") { a ->
            dev.brikk.house.sql.parser.applyTimeUnitCoercion(
                TsOrDsAdd(
                    args(
                        "this" to seqGet(a, 0),
                        "expression" to seqGet(a, 1),
                        "unit" to Literal(args("this" to "DAY", "is_string" to true)),
                    )
                )
            )
        }
        put("DATE_FORMAT") { a ->
            TimeToStr(
                args(
                    "this" to TimeStrToTime(args("this" to seqGet(a, 0))),
                    "format" to hiveFormatTime(seqGet(a, 1)),
                )
            )
        }
        put("DATE_SUB", ::buildDateSub)
        put("DATEDIFF") { a ->
            DateDiff(
                args(
                    "this" to TsOrDsToDate(args("this" to seqGet(a, 0))),
                    "expression" to TsOrDsToDate(args("this" to seqGet(a, 1))),
                )
            )
        }
        put("DAY") { a -> Day(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("FIRST", buildWithIgnoreNulls { First(args("this" to it)) })
        put("FIRST_VALUE", buildWithIgnoreNulls { FirstValue(args("this" to it)) })
        put("FROM_UNIXTIME", buildFormattedTime(default = true) { UnixToStr(it) })
        put("GET_JSON_OBJECT") { a ->
            JSONExtractScalar(
                args("this" to seqGet(a, 0), "expression" to toJsonPathArg(seqGet(a, 1)))
            )
        }
        put("LAST", buildWithIgnoreNulls { Last(args("this" to it)) })
        put("LAST_VALUE", buildWithIgnoreNulls { LastValue(args("this" to it)) })
        put("MAP", ::buildVarMap)
        put("MONTH") { a -> Month(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("NAMED_STRUCT", ::buildNamedStruct)
        put("REGEXP_EXTRACT", hiveBuildRegexpExtract(all = false))
        put("REGEXP_EXTRACT_ALL", hiveBuildRegexpExtract(all = true))
        put("SEQUENCE", fromArgList(listOf("start", "end", "step", "is_end_exclusive"), false) { GenerateSeries(it) })
        put("SIZE") { a -> ArraySize(args("this" to seqGet(a, 0))) }
        put("SPLIT", fromArgList(listOf("this", "expression", "limit"), false) { RegexpSplit(it) })
        put("STR_TO_MAP") { a ->
            StrToMap(
                args(
                    "this" to seqGet(a, 0),
                    "pair_delim" to (seqGet(a, 1) ?: Literal(args("this" to ",", "is_string" to true))),
                    "key_value_delim" to (seqGet(a, 2) ?: Literal(args("this" to ":", "is_string" to true))),
                )
            )
        }
        put("TO_DATE", ::buildToDate)
        put("TO_JSON", fromArgList(listOf("this", "options", "is_json", "to_json"), false) { JSONFormat(it) })
        put("TRUNC", fromArgList(listOf("this", "unit", "zone", "input_type_preserved"), false) { TimestampTrunc(it) })
        put("UNBASE64") { a -> FromBase64(args("this" to seqGet(a, 0))) }
        put("UNIX_TIMESTAMP") { a ->
            val effArgs = if (a.isEmpty()) listOf<Expression?>(CurrentTimestamp()) else a
            buildFormattedTime(default = true) { StrToUnix(it) }(effArgs)
        }
        put("YEAR") { a -> Year(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        // sqlglot: parser.build_logarithm with LOG_DEFAULTS_TO_LN=True — LOG(x) -> LN(x)
        put("LOG") { a ->
            val this_ = seqGet(a, 0)
            val expr = seqGet(a, 1)
            if (expr != null) {
                dev.brikk.house.sql.ast.Log(args("this" to this_, "expression" to expr))
            } else {
                Ln(args("this" to this_))
            }
        }
    }

}

// sqlglot: exp.Quantile.from_arg_list (arg_types: this, quantile)
private fun buildQuantile(a: List<Expression?>): Expression =
    Quantile(args("this" to seqGet(a, 0), "quantile" to seqGet(a, 1)))

// sqlglot: exp.ApproxQuantile.from_arg_list (arg_types: this, quantile, accuracy, weight, error_tolerance)
private fun buildApproxQuantile(a: List<Expression?>): Expression =
    ApproxQuantile(
        args(
            "this" to seqGet(a, 0), "quantile" to seqGet(a, 1), "accuracy" to seqGet(a, 2),
            "weight" to seqGet(a, 3), "error_tolerance" to seqGet(a, 4),
        )
    )

// sqlglot: Dialect.to_json_path (delegates to base helper)
private fun toJsonPathArg(expr: Expression?): Expression? =
    expr?.let { dev.brikk.house.sql.parser.toJsonPath(it) }
