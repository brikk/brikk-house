package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AddMonths
import dev.brikk.house.sql.ast.ArrayUniqueAgg
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DateSub
import dev.brikk.house.sql.ast.EuclideanDistance
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Partition
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.PartitionByRangePropertyDynamic
import dev.brikk.house.sql.ast.PartitionRange
import dev.brikk.house.sql.ast.Property
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.TsOrDsToDate
import dev.brikk.house.sql.ast.UniqueKeyProperty
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: dialect.build_date_delta_with_interval (default_unit="DAY")
private fun buildDateDeltaWithIntervalDefaultDay(
    factory: NodeFactory,
): (List<Expression?>) -> Expression = { argsList ->
    if (argsList.size < 2) throw ParseError("INTERVAL expression expected")
    val interval = argsList[1]
    if (interval !is Interval) {
        // default_unit branch: DATE_ADD(x, 7) -> DateAdd(this=x, expression=7, unit='DAY')
        factory(
            args(
                "this" to argsList[0],
                "expression" to interval,
                // sqlglot: exp.Literal.string(default_unit), then TimeUnit.__init__ -> Var
                "unit" to normalizeTimeUnit(Literal.string("DAY")),
            )
        )
    } else {
        factory(
            args(
                "this" to argsList[0],
                "expression" to interval.thisArg,
                // sqlglot: unit_to_str(interval), then TimeUnit.__init__ converts to Var
                "unit" to normalizeTimeUnit(dorisIntervalUnitToStr(interval)),
            )
        )
    }
}

// sqlglot: dialect.unit_to_str for Interval nodes (default "DAY")
private fun dorisIntervalUnitToStr(interval: Interval): Expression? {
    val unit = interval.args["unit"] as? Expression ?: return Literal.string("DAY")
    return if (unit is dev.brikk.house.sql.ast.Var || unit is Literal) Literal.string(unit.name) else unit
}

// sqlglot: parsers.doris._build_date_trunc — accepts both
// DATE_TRUNC(datetime, unit) and DATE_TRUNC(unit, datetime)
private fun buildDorisDateTrunc(argsList: List<Expression?>): Expression {
    val a0 = seqGet(argsList, 0)
    val a1 = seqGet(argsList, 1)

    fun isUnitLike(e: Expression?): Boolean {
        if (e !is Literal || !e.isString) return false
        val text = e.thisArg as? String ?: return false
        return text.none { it.isDigit() }
    }

    val (unit, this_) = if (isUnitLike(a0)) a0 to a1 else a1 to a0

    // sqlglot: exp.TimestampTrunc(this=this, unit=unit); TimeUnit.__init__ normalizes unit
    return TimestampTrunc(args("this" to this_, "unit" to normalizeTimeUnit(unit)))
}

/**
 * Port of sqlglot's DorisParser (reference/sqlglot/sqlglot/parsers/doris.py).
 * Table merges live in [DorisParserTables]; overridden _parse_* methods below.
 */
// sqlglot: parsers.doris.DorisParser
open class DorisParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.DorisTokenizerTables.CONFIG,
) : MysqlParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: DorisParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = DorisParserTables.FUNCTIONS

    // sqlglot: DorisParser.FUNCTION_PARSERS (mysql's minus GROUP_CONCAT)
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = DorisParserTables.FUNCTION_PARSERS

    // sqlglot: DorisParser.NO_PAREN_FUNCTIONS (mysql's minus CURRENT_DATE)
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = DorisParserTables.NO_PAREN_FUNCTIONS

    // sqlglot: DorisParser.PROPERTY_PARSERS
    override val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = DorisParserTables.PROPERTY_PARSERS

    // sqlglot: DorisParser._parse_partition_property
    override fun parsePartitionProperty(): kotlin.Any? {
        val expr = super.parsePartitionProperty()

        // sqlglot: `if not expr`
        if (expr == null || (expr is List<*> && expr.isEmpty())) {
            return parsePartitionedBy()
        }

        if (expr is Property) return expr

        matchLParen()

        val createExpressions: List<Expression>? = if (matchTextSeq("FROM", advance = false)) {
            parseCsv { parsePartitioningGranularityDynamic() }
        } else {
            null
        }

        matchRParen()

        return expression(
            PartitionByRangeProperty(
                args(
                    "partition_expressions" to expr,
                    "create_expressions" to createExpressions,
                )
            )
        )
    }

    // sqlglot: DorisParser._parse_partitioning_granularity_dynamic
    open fun parsePartitioningGranularityDynamic(): Expression {
        matchTextSeq("FROM")
        val start = parseWrapped({ parseString() })
        matchTextSeq("TO")
        val end = parseWrapped({ parseString() })
        matchTextSeq("INTERVAL")
        val number = parseNumber()
        val unit = parseVar(anyToken = true)
        val every = expression(
            Interval(args("this" to number, "unit" to normalizeTimeUnit(unit)))
        )
        return expression(
            PartitionByRangePropertyDynamic(args("start" to start, "end" to end, "every" to every))
        )
    }

    // sqlglot: DorisParser._parse_partition_range_value
    override fun parsePartitionRangeValue(): Expression? {
        val expr = super.parsePartitionRangeValue()

        if (expr is Partition) return expr

        matchTextSeq("VALUES")
        val name = expr

        // Doris-specific bracket syntax: VALUES [(...), (...))
        match(TokenType.L_BRACKET)
        val values = parseCsv { parseWrappedCsv({ parseExpression() }) }

        match(TokenType.R_BRACKET)
        match(TokenType.R_PAREN)

        val partRange = expression(PartitionRange(args("this" to name, "expressions" to values)))
        return expression(Partition(args("expressions" to listOf(partRange))))
    }

    // sqlglot: DorisParser._parse_build_property
    open fun parseBuildProperty(): Expression =
        expression(
            dev.brikk.house.sql.ast.BuildProperty(args("this" to parseVar(upper = true)))
        )

    // sqlglot: DorisParser._parse_refresh_property
    open fun parseRefreshProperty(): Expression {
        val method = parseVar(upper = true)

        match(TokenType.ON)

        // sqlglot: `self._match_texts((...)) and self._prev.text.upper()` (False when absent)
        val kind: kotlin.Any =
            if (matchTexts(setOf("MANUAL", "COMMIT", "SCHEDULE"))) prevToken.text.uppercase()
            else false
        val every: kotlin.Any? = if (matchTextSeq("EVERY")) parseNumber() else false
        val unit: Expression? =
            if (every != false && every != null) parseVar(anyToken = true) else null
        val starts: kotlin.Any? = if (matchTextSeq("STARTS")) parseString() else false

        return expression(
            dev.brikk.house.sql.ast.RefreshTriggerProperty(
                args(
                    "method" to method,
                    "kind" to kind,
                    "every" to every,
                    "unit" to unit,
                    "starts" to starts,
                )
            )
        )
    }
}

/**
 * Merged parser tables for Doris (sqlglot: DorisParser class-level dict merges over
 * MySQLParser). Kept in an object so the merges happen once.
 */
object DorisParserTables {

    // sqlglot: DorisParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(MysqlParserTables.FUNCTIONS)
        put("ADDDATE", buildDateDeltaWithIntervalDefaultDay { a -> DateAdd(a) })
        put("COLLECT_SET") { a -> ArrayUniqueAgg(args("this" to seqGet(a, 0))) }
        put("DATE_ADD", buildDateDeltaWithIntervalDefaultDay { a -> DateAdd(a) })
        put("DATE_SUB", buildDateDeltaWithIntervalDefaultDay { a -> DateSub(a) })
        put("DATE_TRUNC", ::buildDorisDateTrunc)
        put("L2_DISTANCE") { a ->
            EuclideanDistance(args("this" to seqGet(a, 0), "expression" to seqGet(a, 1)))
        }
        put("MONTHS_ADD") { a ->
            AddMonths(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "preserve_end_of_month" to seqGet(a, 2),
                )
            )
        }
        put("REGEXP") { a ->
            RegexpLike(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "flag" to seqGet(a, 2),
                    "full_match" to seqGet(a, 3),
                )
            )
        }
        put("SUBDATE", buildDateDeltaWithIntervalDefaultDay { a -> DateSub(a) })
        put("TO_DATE") { a ->
            TsOrDsToDate(args("this" to seqGet(a, 0), "format" to seqGet(a, 1), "safe" to seqGet(a, 2)))
        }
    }

    // sqlglot: DorisParser.FUNCTION_PARSERS (mysql's minus GROUP_CONCAT)
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        MysqlParserTables.FUNCTION_PARSERS - "GROUP_CONCAT"

    // sqlglot: DorisParser.NO_PAREN_FUNCTIONS (mysql's minus CURRENT_DATE)
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        MysqlParserTables.NO_PAREN_FUNCTIONS - TokenType.CURRENT_DATE

    // sqlglot: DorisParser.PROPERTY_PARSERS
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> =
        MysqlParserTables.PROPERTY_PARSERS + mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
            "PROPERTIES" to { p, _ -> p.parseWrappedProperties() },
            "UNIQUE" to { p, _ -> p.parseCompositeKeyProperty { a -> UniqueKeyProperty(a) } },
            // Plain KEY without UNIQUE/DUPLICATE/AGGREGATE prefixes is treated as
            // UniqueKeyProperty (rendered back as bare KEY for materialized views)
            "KEY" to { p, _ -> p.parseCompositeKeyProperty { a -> UniqueKeyProperty(a) } },
            "BUILD" to { p, _ -> (p as DorisParser).parseBuildProperty() },
            "REFRESH" to { p, _ -> (p as DorisParser).parseRefreshProperty() },
        )
}
