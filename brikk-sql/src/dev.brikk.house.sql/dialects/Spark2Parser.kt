package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.ApproxQuantile
import dev.brikk.house.sql.ast.ArraySlice
import dev.brikk.house.sql.ast.AtTimeZone
import dev.brikk.house.sql.ast.BitwiseLeftShift
import dev.brikk.house.sql.ast.BitwiseRightShift
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DateTrunc
import dev.brikk.house.sql.ast.DayOfMonth
import dev.brikk.house.sql.ast.DayOfWeek
import dev.brikk.house.sql.ast.DayOfYear
import dev.brikk.house.sql.ast.Drop
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Format
import dev.brikk.house.sql.ast.FromTimeZone
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.StrToTime
import dev.brikk.house.sql.ast.StrToUnix
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.TsOrDsToDate
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.WeekOfYear
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.buildTrim
import dev.brikk.house.sql.parser.fromArgList

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: parsers.spark2.build_as_cast(to_type) — CAST(x AS <to_type>)
private fun buildAsCast(toType: String): (List<Expression?>) -> Expression = { a ->
    Cast(args("this" to seqGet(a, 0), "to" to dev.brikk.house.sql.optimizer.dataTypeFromStr(toType)))
}

// sqlglot: exp.cast(x, exp.DType.TIMESTAMP)
private fun timestampDataType(): DataType = DataType.build(DType.TIMESTAMP)



/**
 * Port of sqlglot's Spark2Parser (reference/sqlglot/sqlglot/parsers/spark2.py class
 * Spark2Parser(HiveParser)). Table merges live in [Spark2ParserTables].
 */
// sqlglot: parsers.spark2.Spark2Parser
open class Spark2Parser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.Spark2TokenizerTables.CONFIG,
) : HiveParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.SPARK2

    // sqlglot: Spark2Parser.TRIM_PATTERN_FIRST = True
    override val trimPatternFirst: Boolean get() = true

    // sqlglot: Spark2Parser.CHANGE_COLUMN_ALTER_SYNTAX = True
    override val changeColumnAlterSyntax: Boolean get() = true

    // sqlglot: Spark2Parser.PIVOT_COLUMN_NAMING = "agg_name_if_multiple"
    override val pivotColumnNaming: String get() = "agg_name_if_multiple"

    // sqlglot: Spark2Parser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = Spark2ParserTables.FUNCTIONS

    // sqlglot: Spark2Parser.FUNCTION_PARSERS
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = Spark2ParserTables.FUNCTION_PARSERS

    // sqlglot: Spark2Parser._parse_drop_column (DROP COLUMNS (...))
    override fun parseAlterDropAction(): Expression? {
        if (matchTextSeq("DROP", "COLUMNS", advance = false)) {
            match(TokenType.DROP)
            matchTextSeq("COLUMNS")
            return expression(Drop(args("this" to parseSchema(), "kind" to "COLUMNS")))
        }
        return super.parseAlterDropAction()
    }

    // sqlglot: Spark2Parser._pivot_column_names — [] for a single aggregation; otherwise
    // pivot_column_names(aggregations, dialect="spark"). We approximate the multi-agg case
    // via the base alias-based naming.
    override fun pivotColumnNames(aggregations: List<Expression>): List<String> {
        if (aggregations.size == 1) return emptyList()
        return super.pivotColumnNames(aggregations)
    }
}

/**
 * Merged parser tables for Spark2 (sqlglot: Spark2Parser class-level dict merges over
 * HiveParser). Kept in an object so the merges happen once.
 */
object Spark2ParserTables {

    // sqlglot: Spark2Parser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(HiveParserTables.FUNCTIONS)
        put("AGGREGATE") { a -> dev.brikk.house.sql.ast.Reduce(a.foldReduceArgs()) }
        put("BOOLEAN", buildAsCast("boolean"))
        put("DATE", buildAsCast("date"))
        put("DATE_TRUNC") { a ->
            TimestampTrunc(args("this" to seqGet(a, 1), "unit" to Var(args("this" to seqGet(a, 0)?.name))))
        }
        put("DAYOFMONTH") { a -> DayOfMonth(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("DAYOFWEEK") { a -> DayOfWeek(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("DAYOFYEAR") { a -> DayOfYear(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("DOUBLE", buildAsCast("double"))
        put("ELEMENT_AT") { a ->
            Bracket(
                args(
                    "this" to seqGet(a, 0),
                    "expressions" to listOfNotNull(seqGet(a, 1)),
                    "offset" to 1,
                    "safe" to false,
                )
            )
        }
        put("FLOAT", buildAsCast("float"))
        put("FORMAT_STRING") { a -> Format(args("this" to seqGet(a, 0), "expressions" to a.drop(1))) }
        put("FROM_UTC_TIMESTAMP") { a ->
            AtTimeZone(
                args(
                    "this" to Cast(args("this" to (seqGet(a, 0) ?: Var(args("this" to ""))), "to" to timestampDataType())),
                    "zone" to seqGet(a, 1),
                )
            )
        }
        put("LTRIM") { a -> buildTrim(reverseArgsSpark(a)) }
        put("INT", buildAsCast("int"))
        put("MAP_FROM_ARRAYS") { a -> MapNode(args("keys" to seqGet(a, 0), "values" to seqGet(a, 1))) }
        put("RLIKE") { a -> RegexpLike(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("RTRIM") { a -> buildTrim(reverseArgsSpark(a), isLeft = false) }
        put("SHIFTLEFT") { a -> BitwiseLeftShift(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("SHIFTRIGHT") { a -> BitwiseRightShift(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }
        put("STRING", buildAsCast("string"))
        put("SLICE", fromArgList(listOf("this", "start", "length"), false) { ArraySlice(it) })
        put("TIMESTAMP", buildAsCast("timestamp"))
        put("TO_TIMESTAMP") { a ->
            if (a.size == 1) {
                buildAsCast("timestamp")(a)
            } else {
                StrToTime(args("this" to seqGet(a, 0), "format" to hiveFormatTime(seqGet(a, 1))))
            }
        }
        put("TO_UNIX_TIMESTAMP") { a -> StrToUnix(args("this" to seqGet(a, 0), "format" to seqGet(a, 1))) }
        put("TO_UTC_TIMESTAMP") { a ->
            FromTimeZone(
                args(
                    "this" to Cast(args("this" to (seqGet(a, 0) ?: Var(args("this" to ""))), "to" to timestampDataType())),
                    "zone" to seqGet(a, 1),
                )
            )
        }
        put("TRUNC") { a ->
            dev.brikk.house.sql.parser.applyTimeUnitCoercion(
                DateTrunc(args("unit" to seqGet(a, 1), "this" to seqGet(a, 0)))
            )
        }
        put("WEEKOFYEAR") { a -> WeekOfYear(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
    }

    // sqlglot: Spark2Parser.FUNCTION_PARSERS
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> = buildMap {
        putAll(HiveParserTables.FUNCTION_PARSERS)
        put("APPROX_PERCENTILE") { p ->
            (p as Spark2Parser).parseDistinctArgFunction({ a -> ApproxQuantile(spreadApproxQuantile(a)) })
        }
        for (hint in listOf(
            "BROADCAST", "BROADCASTJOIN", "MAPJOIN", "MERGE", "SHUFFLEMERGE",
            "MERGEJOIN", "SHUFFLE_HASH", "SHUFFLE_REPLICATE_NL",
        )) {
            put(hint) { p -> p.parseJoinHint(hint) }
        }
    }
}

// sqlglot: exp.Reduce.from_arg_list arg order (this, initial, merge, finish)
private fun List<Expression?>.foldReduceArgs(): dev.brikk.house.sql.ast.Args =
    args(
        "this" to getOrNull(0), "initial" to getOrNull(1),
        "merge" to getOrNull(2), "finish" to getOrNull(3),
    )

// sqlglot: exp.ApproxQuantile.from_arg_list
private fun spreadApproxQuantile(a: List<Expression?>): dev.brikk.house.sql.ast.Args =
    args(
        "this" to a.getOrNull(0), "quantile" to a.getOrNull(1), "accuracy" to a.getOrNull(2),
        "weight" to a.getOrNull(3), "error_tolerance" to a.getOrNull(4),
    )

// sqlglot: build_trim(args, reverse_args=True) — swap this/expression
private fun reverseArgsSpark(a: List<Expression?>): List<Expression?> =
    if (a.size >= 2) listOf(a[1], a[0]) + a.drop(2) else a
