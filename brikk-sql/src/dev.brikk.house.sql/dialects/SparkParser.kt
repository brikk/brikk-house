package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AnyValue
import dev.brikk.house.sql.ast.BitwiseAndAgg
import dev.brikk.house.sql.ast.BitwiseCount
import dev.brikk.house.sql.ast.BitwiseOrAgg
import dev.brikk.house.sql.ast.BitwiseXorAgg
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.ComputedColumnConstraint
import dev.brikk.house.sql.ast.CurrentDate
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.Escape
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GeneratedAsIdentityColumnConstraint
import dev.brikk.house.sql.ast.Getbit
import dev.brikk.house.sql.ast.GroupConcat
import dev.brikk.house.sql.ast.ILike
import dev.brikk.house.sql.ast.JSONKeys
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.SafeAdd
import dev.brikk.house.sql.ast.SafeDivide
import dev.brikk.house.sql.ast.SafeMultiply
import dev.brikk.house.sql.ast.SafeSubtract
import dev.brikk.house.sql.ast.SessionUser
import dev.brikk.house.sql.ast.TimestampAdd
import dev.brikk.house.sql.ast.TimestampDiff
import dev.brikk.house.sql.ast.TimestampFromParts
import dev.brikk.house.sql.ast.TsOrDsAdd
import dev.brikk.house.sql.ast.TsOrDsToDate
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.applyTimeUnitCoercion
import dev.brikk.house.sql.parser.fromArgList

private fun seqGet(a: List<Expression?>, index: Int): Expression? = a.getOrNull(index)

// sqlglot: parsers.spark._build_datediff — 2-arg (this, expr) or 3-arg (unit, this, expr)
private fun buildSparkDatediff(a: List<Expression?>): Expression {
    var unit: Expression? = null
    var this_ = seqGet(a, 0)
    val expr = seqGet(a, 1)
    if (a.size == 3) {
        unit = Var(args("this" to this_?.name))
        this_ = a[2]
    }
    return applyTimeUnitCoercion(
        DateDiff(
            args(
                "this" to TsOrDsToDate(args("this" to this_)),
                "expression" to TsOrDsToDate(args("this" to expr)),
                "unit" to unit,
            )
        )
    )
}

// sqlglot: parsers.spark._build_dateadd — 2-arg -> TsOrDsAdd DAY; 3-arg -> TimestampAdd(unit,..)
private fun buildSparkDateadd(a: List<Expression?>): Expression {
    val expr = seqGet(a, 1)
    if (a.size == 2) {
        return applyTimeUnitCoercion(
            TsOrDsAdd(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to expr,
                    "unit" to Literal.string("DAY"),
                )
            )
        )
    }
    return applyTimeUnitCoercion(
        TimestampAdd(args("this" to seqGet(a, 2), "expression" to expr, "unit" to seqGet(a, 0)))
    )
}

// sqlglot: dialect.build_date_delta(exp.TimestampDiff) — unit-based delta (default "DAY")
private fun buildTimestampDiff(a: List<Expression?>): Expression {
    val unitBased = a.size >= 3
    val this_ = if (unitBased) a.getOrNull(2) else seqGet(a, 0)
    val unit = if (unitBased) a.getOrNull(0) else Literal.string("DAY")
    return applyTimeUnitCoercion(
        TimestampDiff(args("this" to this_, "expression" to seqGet(a, 1), "unit" to unit))
    )
}

// sqlglot: parser.build_like(exp.Like/exp.ILike) — LIKE(pattern, this) with optional escape
private fun buildLikeSpark(a: List<Expression?>, ilike: kotlin.Boolean): Expression {
    val base: Expression =
        if (ilike) ILike(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
        else Like(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
    return if (a.size > 2) Escape(args("this" to base, "expression" to seqGet(a, 2))) else base
}

// sqlglot: parsers.hive.build_with_ignore_nulls — 2nd boolean arg wraps in IgnoreNulls
private fun buildAnyValueIgnoreNulls(a: List<Expression?>): Expression {
    val this_ = AnyValue(args("this" to seqGet(a, 0)))
    val flag = seqGet(a, 1)
    return if (flag is dev.brikk.house.sql.ast.Boolean && flag.thisArg == true) {
        dev.brikk.house.sql.ast.IgnoreNulls(args("this" to this_))
    } else {
        this_
    }
}

/**
 * Port of sqlglot's SparkParser (reference/sqlglot/sqlglot/parsers/spark.py class
 * SparkParser(Spark2Parser)). Table merges live in [SparkParserTables].
 */
// sqlglot: parsers.spark.SparkParser
open class SparkParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.SparkTokenizerTables.CONFIG,
) : Spark2Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.SPARK

    // sqlglot: SparkParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = SparkParserTables.FUNCTIONS

    // sqlglot: SparkParser.FUNCTION_PARSERS (+ SUBSTR)
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = Spark2ParserTables.FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "SUBSTR" to { p -> p.parseSubstring() },
        )

    // sqlglot: SparkParser.NO_PAREN_FUNCTIONS (+ SESSION_USER)
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = super.noParenFunctions + mapOf<TokenType, () -> Expression>(
            TokenType.SESSION_USER to { SessionUser() },
        )

    // sqlglot: SparkParser.SET_PARSERS (VAR/VARIABLE -> VARIABLE assignment)
    override val setParsers: Map<String, (Parser) -> Expression?>
        get() = super.setParsers + mapOf<String, (Parser) -> Expression?>(
            "VAR" to { p -> p.parseSetItemAssignment("VARIABLE") },
            "VARIABLE" to { p -> p.parseSetItemAssignment("VARIABLE") },
        )

    // sqlglot: SparkParser.PLACEHOLDER_PARSERS (+ L_BRACE widget parameter)
    override val placeholderParsers: Map<TokenType, (Parser) -> Expression?>
        get() = super.placeholderParsers + mapOf<TokenType, (Parser) -> Expression?>(
            TokenType.L_BRACE to { p -> (p as SparkParser).parseQueryParameter() },
        )

    // sqlglot: SparkParser._parse_query_parameter ({name} widget)
    open fun parseQueryParameter(): Expression? {
        val this_ = parseIdVar()
        match(TokenType.R_BRACE)
        return expression(Placeholder(args("this" to this_, "widget" to true)))
    }

    // sqlglot: SparkParser._parse_generated_as_identity — GENERATED ALWAYS AS (expr) becomes
    // a ComputedColumnConstraint.
    override fun parseGeneratedAsIdentity(): Expression {
        val this_ = super.parseGeneratedAsIdentity()
        val expr = (this_ as? GeneratedAsIdentityColumnConstraint)?.args?.get("expression")
        if (expr != null) {
            return expression(ComputedColumnConstraint(args("this" to expr)))
        }
        return this_
    }

    // sqlglot: SparkParser._parse_pivot_aggregation — Spark3+ allows non-aggregate functions
    override fun parsePivotAggregation(): Expression? {
        val aggregateExpr = parseFunction() ?: parseDisjunction()
        return parseAlias(aggregateExpr)
    }
}

/**
 * Merged parser tables for Spark (sqlglot: SparkParser class-level dict merges over
 * Spark2Parser). Kept in an object so the merges happen once.
 */
object SparkParserTables {

    // sqlglot: SparkParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(Spark2ParserTables.FUNCTIONS)
        put("ANY_VALUE", ::buildAnyValueIgnoreNulls)
        put("ARRAY_INSERT") { a ->
            dev.brikk.house.sql.ast.ArrayInsert(
                args(
                    "this" to seqGet(a, 0),
                    "position" to seqGet(a, 1),
                    "expression" to seqGet(a, 2),
                    "offset" to 1,
                )
            )
        }
        put("BIT_AND") { a -> BitwiseAndAgg(args("this" to seqGet(a, 0))) }
        put("BIT_GET") { a -> Getbit(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("BIT_OR") { a -> BitwiseOrAgg(args("this" to seqGet(a, 0))) }
        put("BIT_XOR") { a -> BitwiseXorAgg(args("this" to seqGet(a, 0))) }
        put("BIT_COUNT") { a -> BitwiseCount(args("this" to seqGet(a, 0))) }
        put("CURDATE") { CurrentDate() }
        put("DATE_ADD", ::buildSparkDateadd)
        put("DATEADD", ::buildSparkDateadd)
        put("MAKE_TIMESTAMP") { a -> TimestampFromParts(makeTimestampArgs(a)) }
        put("TIMESTAMPADD", ::buildSparkDateadd)
        put("TIMESTAMPDIFF", ::buildTimestampDiff)
        put("TRY_ADD") { a -> SafeAdd(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("TRY_DIVIDE") { a -> SafeDivide(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("TRY_MULTIPLY") { a -> SafeMultiply(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("TRY_SUBTRACT") { a -> SafeSubtract(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("DATEDIFF", ::buildSparkDatediff)
        put("DATE_DIFF", ::buildSparkDatediff)
        put("JSON_OBJECT_KEYS") { a -> JSONKeys(args("this" to seqGet(a, 0))) }
        put("LISTAGG") { a -> GroupConcat(args("this" to seqGet(a, 0), "separator" to seqGet(a, 1))) }
        put("TIMESTAMP_LTZ", buildAsCastSpark("TIMESTAMP_LTZ"))
        put("TIMESTAMP_NTZ", buildAsCastSpark("TIMESTAMP_NTZ"))
        put("TRY_ELEMENT_AT") { a ->
            Bracket(
                args(
                    "this" to seqGet(a, 0),
                    "expressions" to listOfNotNull(seqGet(a, 1)),
                    "offset" to 1,
                    "safe" to true,
                )
            )
        }
        put("LIKE") { a -> buildLikeSpark(a, ilike = false) }
        put("ILIKE") { a -> buildLikeSpark(a, ilike = true) }
    }
}

// sqlglot: exp.TimestampFromParts.from_arg_list
private fun makeTimestampArgs(a: List<Expression?>): dev.brikk.house.sql.ast.Args =
    args(
        "year" to a.getOrNull(0), "month" to a.getOrNull(1), "day" to a.getOrNull(2),
        "hour" to a.getOrNull(3), "min" to a.getOrNull(4), "sec" to a.getOrNull(5),
    )

// sqlglot: parsers.spark2.build_as_cast reused for Spark's TIMESTAMP_LTZ/NTZ builders
private fun buildAsCastSpark(toType: String): (List<Expression?>) -> Expression = { a ->
    dev.brikk.house.sql.ast.Cast(
        args("this" to seqGet(a, 0), "to" to dev.brikk.house.sql.optimizer.dataTypeFromStr(toType))
    )
}
