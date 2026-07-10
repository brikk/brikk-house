package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AnyValue
import dev.brikk.house.sql.ast.ApproxDistinct
import dev.brikk.house.sql.ast.ApproxQuantile
import dev.brikk.house.sql.ast.ArrayContains
import dev.brikk.house.sql.ast.ArraySize
import dev.brikk.house.sql.ast.ArraySlice
import dev.brikk.house.sql.ast.ArrayUniqueAgg
import dev.brikk.house.sql.ast.BitwiseAnd
import dev.brikk.house.sql.ast.BitwiseNot
import dev.brikk.house.sql.ast.BitwiseOr
import dev.brikk.house.sql.ast.BitwiseXor
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.Concat
import dev.brikk.house.sql.ast.ConcatWs
import dev.brikk.house.sql.ast.CurrentTimestamp
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.DateTrunc
import dev.brikk.house.sql.ast.DayOfWeekIso
import dev.brikk.house.sql.ast.DayOfYear
import dev.brikk.house.sql.ast.Decode
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Encode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GenerateSeries
import dev.brikk.house.sql.ast.Greatest
import dev.brikk.house.sql.ast.GroupConcat
import dev.brikk.house.sql.ast.JSONFormat
import dev.brikk.house.sql.ast.Least
import dev.brikk.house.sql.ast.Levenshtein
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Localtime
import dev.brikk.house.sql.ast.Localtimestamp
import dev.brikk.house.sql.ast.Log
import dev.brikk.house.sql.ast.MD5Digest
import dev.brikk.house.sql.ast.OverflowTruncateBehavior
import dev.brikk.house.sql.ast.RegexpExtract
import dev.brikk.house.sql.ast.RegexpExtractAll
import dev.brikk.house.sql.ast.RegexpReplace
import dev.brikk.house.sql.ast.Replace
import dev.brikk.house.sql.ast.SHA2Digest
import dev.brikk.house.sql.ast.StrPosition
import dev.brikk.house.sql.ast.StrToMap
import dev.brikk.house.sql.ast.StrToTime
import dev.brikk.house.sql.ast.Struct
import dev.brikk.house.sql.ast.TimeToStr
import dev.brikk.house.sql.ast.TimeToUnix
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.Unhex
import dev.brikk.house.sql.ast.UnixToTime
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.WeekOfYear
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.applyTimeUnitCoercion
import dev.brikk.house.sql.parser.formatTimeString
import dev.brikk.house.sql.parser.fromArgList

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: Dialect["presto"].format_time (Presto.TIME_MAPPING = MySQL.TIME_MAPPING)
private fun prestoFormatTime(expression: Expression?): Expression? {
    if (expression is Literal && expression.isString) {
        val converted = formatTimeString(expression.thisArg as? String, MysqlDialect.TIME_MAPPING)
        return Literal(args("this" to converted, "is_string" to true))
    }
    return expression
}

// sqlglot: dialects.teradata.Teradata.TIME_MAPPING (used by presto's TO_CHAR builder)
internal val TERADATA_TIME_MAPPING: Map<String, String> = mapOf(
    "YY" to "%y", "Y4" to "%Y", "YYYY" to "%Y", "M4" to "%B", "M3" to "%b", "M" to "%-M",
    "MI" to "%M", "MM" to "%m", "MMM" to "%b", "MMMM" to "%B", "D" to "%-d", "DD" to "%d",
    "D3" to "%j", "DDD" to "%j", "H" to "%-H", "HH" to "%H", "HH24" to "%H", "S" to "%-S",
    "SS" to "%S", "SSSSSS" to "%f", "E" to "%a", "EE" to "%a", "E3" to "%a", "E4" to "%A",
    "EEE" to "%a", "EEEE" to "%A",
)

// sqlglot: dialect.binary_from_function
internal fun binaryFromFunction(factory: NodeFactory): (List<Expression?>) -> Expression =
    { a -> factory(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1))) }

// sqlglot: parsers.presto._build_approx_percentile
private fun buildApproxPercentile(a: List<Expression?>): Expression = when (a.size) {
    4 -> ApproxQuantile(
        args(
            "this" to seqGet(a, 0),
            "weight" to seqGet(a, 1),
            "quantile" to seqGet(a, 2),
            "accuracy" to seqGet(a, 3),
        )
    )
    3 -> ApproxQuantile(
        args("this" to seqGet(a, 0), "quantile" to seqGet(a, 1), "accuracy" to seqGet(a, 2))
    )
    else -> fromArgList(
        listOf("this", "quantile", "accuracy", "weight", "error_tolerance"), false
    ) { ApproxQuantile(it) }(a)
}

// sqlglot: parsers.presto._build_from_unixtime
private fun buildFromUnixtime(a: List<Expression?>): Expression = when (a.size) {
    3 -> UnixToTime(
        args("this" to seqGet(a, 0), "hours" to seqGet(a, 1), "minutes" to seqGet(a, 2))
    )
    2 -> UnixToTime(args("this" to seqGet(a, 0), "zone" to seqGet(a, 1)))
    else -> fromArgList(
        listOf("this", "scale", "zone", "hours", "minutes", "format", "target_type"), false
    ) { UnixToTime(it) }(a)
}

// sqlglot: parsers.presto._build_to_char (build_formatted_time via "teradata")
private fun buildToChar(a: List<Expression?>): Expression {
    val fmt = seqGet(a, 1)
    if (fmt is Literal && fmt.isString) {
        // We uppercase this to match Teradata's format mapping keys
        fmt.set("this", (fmt.thisArg as? String)?.uppercase())
    }
    val converted = if (fmt is Literal && fmt.isString) {
        Literal(
            args(
                "this" to formatTimeString(fmt.thisArg as? String, TERADATA_TIME_MAPPING),
                "is_string" to true,
            )
        )
    } else fmt
    return TimeToStr(args("this" to seqGet(a, 0), "format" to converted))
}

// sqlglot: dialect.date_trunc_to_time
internal fun dateTruncToTime(a: List<Expression?>): Expression {
    val unit = seqGet(a, 0)
    val this_ = seqGet(a, 1)

    if (this_ is Cast && (this_.args["to"] as? Expression)?.thisArg == DType.DATE) {
        // sqlglot: exp.DateTrunc.__init__ — unit unabbreviated + uppercased into a Literal
        val normalizedUnit = if (unit is Var || unit is Literal) {
            Literal.string(unit.name.uppercase())
        } else unit
        return DateTrunc(args("unit" to normalizedUnit, "this" to this_))
    }
    return applyTimeUnitCoercion(TimestampTrunc(args("this" to this_, "unit" to unit)))
}

// sqlglot: dialect.build_regexp_extract (presto REGEXP_EXTRACT_DEFAULT_GROUP=0,
// REGEXP_EXTRACT_POSITION_OVERFLOW_RETURNS_NULL=True; the latter only for RegexpExtract)
internal fun buildRegexpExtract(all: Boolean): (List<Expression?>) -> Expression = { a ->
    val kwargs = args(
        "this" to seqGet(a, 0),
        "expression" to seqGet(a, 1),
        "group" to (seqGet(a, 2) ?: Literal.number("0")),
        "parameters" to seqGet(a, 3),
    )
    if (all) RegexpExtractAll(kwargs)
    else RegexpExtract(kwargs + args("null_if_pos_overflow" to true))
}

/**
 * Port of sqlglot's PrestoParser (reference/sqlglot/sqlglot/parsers/presto.py).
 * Table merges live in [PrestoParserTables]; flag overrides below.
 */
// sqlglot: parsers.presto.PrestoParser
open class PrestoParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.PrestoTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: PrestoParser.VALUES_FOLLOWED_BY_PAREN = False
    override val valuesFollowedByParen: Boolean get() = false

    // sqlglot: PrestoParser.ZONE_AWARE_TIMESTAMP_CONSTRUCTOR = True
    override val zoneAwareTimestampConstructor: Boolean get() = true

    // sqlglot: Presto.INDEX_OFFSET = 1
    override val indexOffset: Int get() = 1

    // sqlglot: Presto.NULL_ORDERING = "nulls_are_last"
    override val nullOrdering: String get() = "nulls_are_last"

    // sqlglot: Presto.STRICT_STRING_CONCAT = True
    override val strictStringConcat: Boolean get() = true

    // sqlglot: Presto.TYPED_DIVISION = True
    override val typedDivision: Boolean get() = true

    // sqlglot: Presto.TABLESAMPLE_SIZE_IS_PERCENT = True
    override val tablesampleSizeIsPercent: Boolean get() = true

    // sqlglot: Presto.SUPPORTS_LIMIT_ALL = True
    override val supportsLimitAll: Boolean get() = true

    // sqlglot: Presto.SUPPORTS_VALUES_DEFAULT = False
    override val supportsValuesDefault: Boolean get() = false

    // sqlglot: PrestoParser.NO_PAREN_FUNCTIONS
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = PrestoParserTables.NO_PAREN_FUNCTIONS

    // sqlglot: PrestoParser.TABLE_ALIAS_TOKENS
    override val tableAliasTokens: Set<TokenType> get() = PrestoParserTables.TABLE_ALIAS_TOKENS

    // sqlglot: PrestoParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = PrestoParserTables.FUNCTIONS

    // sqlglot: PrestoParser.FUNCTION_PARSERS (base minus TRIM)
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = PrestoParserTables.FUNCTION_PARSERS

}

/**
 * Merged parser tables for Presto (sqlglot: PrestoParser class-level dict merges).
 * Kept in an object so the merges happen once.
 */
object PrestoParserTables {

    // sqlglot: PrestoParser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        BaseParserTables.NO_PAREN_FUNCTIONS + mapOf(
            TokenType.LOCALTIME to { Localtime() },
            TokenType.LOCALTIMESTAMP to { Localtimestamp() },
        )

    // sqlglot: PrestoParser.TABLE_ALIAS_TOKENS
    val TABLE_ALIAS_TOKENS: Set<TokenType> =
        BaseParserTables.TABLE_ALIAS_TOKENS + setOf(TokenType.ANTI, TokenType.SEMI)

    // sqlglot: PrestoParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(BaseParserTables.FUNCTIONS)
        put("ARBITRARY", fromArgList(listOf("this"), false) { AnyValue(it) })
        put("APPROX_DISTINCT", fromArgList(listOf("this", "accuracy"), false) { ApproxDistinct(it) })
        put("APPROX_PERCENTILE", ::buildApproxPercentile)
        put("BITWISE_AND", binaryFromFunction { a -> BitwiseAnd(a) })
        put("BITWISE_NOT") { a -> BitwiseNot(args("this" to seqGet(a, 0))) }
        put("BITWISE_OR", binaryFromFunction { a -> BitwiseOr(a) })
        put("BITWISE_XOR", binaryFromFunction { a -> BitwiseXor(a) })
        put("CARDINALITY", fromArgList(listOf("this", "expression"), false) { ArraySize(it) })
        // sqlglot: parser.py FUNCTIONS["CONCAT"/"CONCAT_WS"] bake in
        // safe=not dialect.STRICT_STRING_CONCAT (Presto: True -> safe=false) and
        // coalesce=dialect.CONCAT[_WS]_COALESCE (Presto: false)
        put("CONCAT") { a ->
            Concat(args("expressions" to a, "safe" to false, "coalesce" to false))
        }
        put("CONCAT_WS") { a ->
            ConcatWs(args("expressions" to a, "safe" to false, "coalesce" to false))
        }
        put(
            "CONTAINS",
            fromArgList(
                listOf("this", "expression", "ensure_variant", "check_null"), false
            ) { ArrayContains(it) },
        )
        put("DATE_ADD") { a ->
            applyTimeUnitCoercion(
                DateAdd(
                    args("this" to seqGet(a, 2), "expression" to seqGet(a, 1), "unit" to seqGet(a, 0))
                )
            )
        }
        put("DATE_DIFF") { a ->
            applyTimeUnitCoercion(
                DateDiff(
                    args("this" to seqGet(a, 2), "expression" to seqGet(a, 1), "unit" to seqGet(a, 0))
                )
            )
        }
        // sqlglot: build_formatted_time(exp.TimeToStr) / (exp.StrToTime)
        put("DATE_FORMAT") { a ->
            TimeToStr(args("this" to seqGet(a, 0), "format" to prestoFormatTime(seqGet(a, 1))))
        }
        put("DATE_PARSE") { a ->
            StrToTime(args("this" to seqGet(a, 0), "format" to prestoFormatTime(seqGet(a, 1))))
        }
        put("DATE_TRUNC", ::dateTruncToTime)
        put("DAY_OF_WEEK", fromArgList(listOf("this"), false) { DayOfWeekIso(it) })
        put("DOW", fromArgList(listOf("this"), false) { DayOfWeekIso(it) })
        put("DOY", fromArgList(listOf("this"), false) { DayOfYear(it) })
        put("ELEMENT_AT") { a ->
            Bracket(
                args(
                    "this" to seqGet(a, 0),
                    "expressions" to listOf(seqGet(a, 1)),
                    "offset" to 1,
                    "safe" to true,
                )
            )
        }
        put("FROM_HEX", fromArgList(listOf("this", "expression"), false) { Unhex(it) })
        put("FROM_UNIXTIME", ::buildFromUnixtime)
        put("FROM_UTF8") { a ->
            Decode(
                args(
                    "this" to seqGet(a, 0),
                    "replace" to seqGet(a, 1),
                    "charset" to Literal.string("utf-8"),
                )
            )
        }
        put("JSON_FORMAT") { a ->
            JSONFormat(
                args("this" to seqGet(a, 0), "options" to seqGet(a, 1), "is_json" to true)
            )
        }
        put(
            "LEVENSHTEIN_DISTANCE",
            fromArgList(
                listOf("this", "expression", "ins_cost", "del_cost", "sub_cost", "max_dist"), false
            ) { Levenshtein(it) },
        )
        put("NOW", fromArgList(listOf("this", "sysdate"), false) { CurrentTimestamp(it) })
        put("REGEXP_EXTRACT", buildRegexpExtract(all = false))
        put("REGEXP_EXTRACT_ALL", buildRegexpExtract(all = true))
        put("REGEXP_REPLACE") { a ->
            RegexpReplace(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "replacement" to (seqGet(a, 2) ?: Literal.string("")),
                )
            )
        }
        // sqlglot: dialect.build_replace_with_optional_replacement
        put("REPLACE") { a ->
            Replace(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "replacement" to (seqGet(a, 2) ?: Literal.string("")),
                )
            )
        }
        put("ROW", fromArgList(listOf("expressions"), true) { Struct(it) })
        put(
            "SEQUENCE",
            fromArgList(listOf("start", "end", "step", "is_end_exclusive"), false) {
                GenerateSeries(it)
            },
        )
        put("SET_AGG", fromArgList(listOf("this"), false) { ArrayUniqueAgg(it) })
        put(
            "SPLIT_TO_MAP",
            fromArgList(
                listOf("this", "pair_delim", "key_value_delim", "duplicate_resolution_callback"),
                false,
            ) { StrToMap(it) },
        )
        put("STRPOS") { a ->
            StrPosition(
                args("this" to seqGet(a, 0), "substr" to seqGet(a, 1), "occurrence" to seqGet(a, 2))
            )
        }
        put(
            "SLICE",
            fromArgList(listOf("this", "start", "end", "step", "zero_based"), false) {
                ArraySlice(it)
            },
        )
        put("TO_CHAR", ::buildToChar)
        put("TO_UNIXTIME", fromArgList(listOf("this"), false) { TimeToUnix(it) })
        put("TO_UTF8") { a ->
            Encode(args("this" to seqGet(a, 0), "charset" to Literal.string("utf-8")))
        }
        put("MD5", fromArgList(listOf("this"), false) { MD5Digest(it) })
        put("SHA256") { a ->
            SHA2Digest(args("this" to seqGet(a, 0), "length" to Literal.number("256")))
        }
        put("SHA512") { a ->
            SHA2Digest(args("this" to seqGet(a, 0), "length" to Literal.number("512")))
        }
        put("WEEK", fromArgList(listOf("this"), false) { WeekOfYear(it) })

        // sqlglot: parser.build_logarithm with Presto.LOG_BASE_FIRST = None (falsy -> swap)
        put("LOG") { a ->
            val this_ = seqGet(a, 0)
            val expr = seqGet(a, 1)
            if (expr != null) Log(args("this" to expr, "expression" to this_))
            else Log(args("this" to this_))
        }

        // sqlglot: parser FUNCTIONS[LEAST/GREATEST] with Presto.LEAST_GREATEST_IGNORES_NULLS=False
        put("LEAST") { a ->
            Least(
                args("this" to seqGet(a, 0), "expressions" to a.drop(1), "ignore_nulls" to false)
            )
        }
        put("GREATEST") { a ->
            Greatest(
                args("this" to seqGet(a, 0), "expressions" to a.drop(1), "ignore_nulls" to false)
            )
        }
    }

    // sqlglot: PrestoParser.FUNCTION_PARSERS = {k: v for base if k != "TRIM"}
    // ("STRING_AGG" is a base sqlglot FUNCTION_PARSERS entry — see Parser.parseStringAgg)
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        (BaseParserTables.FUNCTION_PARSERS - "TRIM") + mapOf<String, (Parser) -> Expression?>(
            "STRING_AGG" to { p -> p.parseStringAgg() },
        )
}
