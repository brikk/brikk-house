package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.DateSub
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Flatten
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.PartitionByRangePropertyDynamic
import dev.brikk.house.sql.ast.PartitionedByProperty
import dev.brikk.house.sql.ast.PrimaryKey
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.Property
import dev.brikk.house.sql.ast.RefreshTriggerProperty
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.RollupIndex
import dev.brikk.house.sql.ast.RollupProperty
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.TableFromRows
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.UniqueKeyProperty
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.StarrocksTokenizerTables
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.buildVarMap

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
                // sqlglot: exp.Literal.string(default_unit), then TimeUnit.__init__ -> Var
                "expression" to interval,
                "unit" to normalizeTimeUnit(Literal.string("DAY")),
            )
        )
    } else {
        factory(
            args(
                "this" to argsList[0],
                "expression" to interval.thisArg,
                // sqlglot: unit_to_str(interval), then TimeUnit.__init__ converts to Var
                "unit" to normalizeTimeUnit(starrocksIntervalUnitToStr(interval)),
            )
        )
    }
}

// sqlglot: dialect.unit_to_str for Interval nodes (default "DAY")
private fun starrocksIntervalUnitToStr(interval: Interval): Expression? {
    val unit = interval.args["unit"] as? Expression ?: return Literal.string("DAY")
    return if (unit is dev.brikk.house.sql.ast.Var || unit is Literal) Literal.string(unit.name) else unit
}

// sqlglot: dialect.build_timestamp_trunc -> TimestampTrunc(this=args[1], unit=args[0])
private fun buildStarrocksTimestampTrunc(argsList: List<Expression?>): Expression =
    TimestampTrunc(
        args(
            "this" to seqGet(argsList, 1),
            "unit" to normalizeTimeUnit(seqGet(argsList, 0)),
        )
    )

/**
 * Port of sqlglot's StarRocksParser (reference/sqlglot/sqlglot/parsers/starrocks.py
 * class StarRocksParser(MySQLParser)). Table merges live in [StarrocksParserTables];
 * overridden _parse_* methods below.
 */
// sqlglot: parsers.starrocks.StarRocksParser
open class StarrocksParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = StarrocksTokenizerTables.CONFIG,
) : MysqlParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.STARROCKS

    // sqlglot: StarRocks.INDEX_OFFSET = 1 (mirrored by StarrocksGenerator.dialectIndexOffset)
    override val indexOffset: Int get() = 1

    // sqlglot: StarRocksParser.TABLE_ALIAS_TOKENS = MySQLParser.TABLE_ALIAS_TOKENS - {ANTI, SEMI}
    // StarRocks supports LEFT SEMI JOIN and LEFT ANTI JOIN natively.
    override val tableAliasTokens: Set<TokenType>
        get() = StarrocksParserTables.TABLE_ALIAS_TOKENS

    // sqlglot: StarRocksParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = StarrocksParserTables.FUNCTIONS

    // sqlglot: StarRocksParser.PROPERTY_PARSERS
    override val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = StarrocksParserTables.PROPERTY_PARSERS

    // sqlglot: StarRocksParser._parse_rollup_property
    open fun parseRollupProperty(): Expression {
        fun parseRollupIndex(): RollupIndex =
            expression(
                RollupIndex(
                    args(
                        "this" to parseIdVar(),
                        "expressions" to parseWrappedIdVars(),
                        "from_index" to if (matchTextSeq("FROM")) parseIdVar() else null,
                        "properties" to if (matchTextSeq("PROPERTIES")) {
                            expression(Properties(args("expressions" to parseWrappedProperties())))
                        } else {
                            null
                        },
                    )
                )
            ) as RollupIndex

        return expression(
            RollupProperty(args("expressions" to parseWrappedCsv({ parseRollupIndex() })))
        )
    }

    // sqlglot: StarRocksParser._parse_create — StarRocks' primary key is defined outside
    // the schema, so move it in.
    override fun parseCreate(): Expression {
        val create = super.parseCreate()

        if (create is Create && create.thisArg is Schema) {
            val schema = create.thisArg as Schema
            val props = create.args["properties"] as? Expression
            if (props != null) {
                val primaryKey = props.find(PrimaryKey::class)
                if (primaryKey != null) {
                    schema.append("expressions", primaryKey.pop())
                }
            }
        }

        return create
    }

    // sqlglot: StarRocksParser._parse_unnest — StarRocks defaults the UNNEST table
    // alias/column to "unnest".
    override fun parseUnnest(withAlias: kotlin.Boolean): Expression? {
        val unnest = super.parseUnnest(withAlias) ?: return null

        val alias = unnest.args["alias"] as? TableAlias
        if (alias == null) {
            unnest.set(
                "alias",
                expression(
                    TableAlias(
                        args(
                            "this" to toIdentifier("unnest"),
                            "columns" to mutableListOf(toIdentifier("unnest")),
                        )
                    )
                ),
            )
        } else if ((alias.args["columns"] as? List<*>).orEmpty().isEmpty()) {
            alias.set("columns", mutableListOf(toIdentifier("unnest")))
        }

        return unnest
    }

    // sqlglot: StarRocksParser._parse_partitioned_by
    override fun parsePartitionedBy(): Expression =
        expression(
            PartitionedByProperty(
                args(
                    "this" to expression(
                        Schema(
                            args(
                                "expressions" to parseWrappedCsv({ parseAssignment() }, optional = true)
                            )
                        )
                    )
                )
            )
        )

    // sqlglot: StarRocksParser._parse_partition_property
    override fun parsePartitionProperty(): kotlin.Any? {
        val expr = super.parsePartitionProperty()

        // sqlglot: `if not expr`
        if (expr == null || (expr is List<*> && expr.isEmpty())) {
            return parsePartitionedBy()
        }

        if (expr is Property) return expr

        matchLParen()

        val createExpressions: List<Expression>? = if (matchTextSeq("START", advance = false)) {
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

    // sqlglot: StarRocksParser._parse_partitioning_granularity_dynamic
    open fun parsePartitioningGranularityDynamic(): Expression {
        matchTextSeq("START")
        val start = parseWrapped({ parseString() })
        matchTextSeq("END")
        val end = parseWrapped({ parseString() })
        matchTextSeq("EVERY")
        val every = parseWrapped({ parseInterval() ?: parseNumber() })
        return expression(
            PartitionByRangePropertyDynamic(args("start" to start, "end" to end, "every" to every))
        )
    }

    // sqlglot: StarRocksParser._parse_refresh_property
    //   REFRESH [DEFERRED | IMMEDIATE]
    //           [ASYNC | ASYNC [START (<start_time>)] EVERY (INTERVAL <refresh_interval>) | MANUAL]
    open fun parseRefreshProperty(): Expression {
        // sqlglot: `self._match_texts((...)) and self._prev.text.upper()` (False when absent)
        val method: kotlin.Any =
            if (matchTexts(setOf("DEFERRED", "IMMEDIATE"))) prevToken.text.uppercase() else false
        val kind: kotlin.Any =
            if (matchTexts(setOf("ASYNC", "MANUAL"))) prevToken.text.uppercase() else false
        val starts: kotlin.Any =
            if (matchTextSeq("START")) parseWrapped({ parseString() }) ?: false else false

        val every: Expression?
        val unit: Expression?
        if (matchTextSeq("EVERY")) {
            matchLParen()
            matchTextSeq("INTERVAL")
            every = parseNumber()
            unit = parseVar(anyToken = true)
            matchRParen()
        } else {
            every = null
            unit = null
        }

        return expression(
            RefreshTriggerProperty(
                args(
                    "method" to method,
                    "kind" to kind,
                    "starts" to starts,
                    "every" to every,
                    "unit" to unit,
                )
            )
        )
    }
}

/**
 * Merged parser tables for StarRocks (sqlglot: StarRocksParser class-level dict merges
 * over MySQLParser). Kept in an object so the merges happen once.
 */
object StarrocksParserTables {

    // sqlglot: StarRocksParser.TABLE_ALIAS_TOKENS = MySQLParser.TABLE_ALIAS_TOKENS - {ANTI, SEMI}
    val TABLE_ALIAS_TOKENS: Set<TokenType> =
        MysqlParserTables.TABLE_ALIAS_TOKENS - setOf(TokenType.ANTI, TokenType.SEMI)

    // sqlglot: StarRocksParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(MysqlParserTables.FUNCTIONS)
        put("ADDDATE", buildDateDeltaWithIntervalDefaultDay { a -> DateAdd(a) })
        put("DATE_ADD", buildDateDeltaWithIntervalDefaultDay { a -> DateAdd(a) })
        put("DATE_SUB", buildDateDeltaWithIntervalDefaultDay { a -> DateSub(a) })
        put("SUBDATE", buildDateDeltaWithIntervalDefaultDay { a -> DateSub(a) })
        put("DATE_TRUNC", ::buildStarrocksTimestampTrunc)
        put("DATEDIFF") { a ->
            DateDiff(
                args(
                    "this" to seqGet(a, 0),
                    "expression" to seqGet(a, 1),
                    "unit" to normalizeTimeUnit(Literal.string("DAY")),
                )
            )
        }
        put("DATE_DIFF") { a ->
            DateDiff(
                args(
                    "this" to seqGet(a, 1),
                    "expression" to seqGet(a, 2),
                    "unit" to normalizeTimeUnit(seqGet(a, 0)),
                )
            )
        }
        put("ARRAY_FLATTEN") { a -> Flatten(args("this" to seqGet(a, 0))) }
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
        // StarRocks' MAP() is a variadic constructor: MAP(k1, v1, k2, v2, ...)
        put("MAP", ::buildVarMap)
        // TABLE(<tvf>) wraps a table function invocation whose arguments are constants
        put("TABLE") { a -> TableFromRows(args("this" to seqGet(a, 0))) }
    }

    // sqlglot: StarRocksParser.PROPERTY_PARSERS
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> =
        MysqlParserTables.PROPERTY_PARSERS + mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
            "PROPERTIES" to { p, _ -> p.parseWrappedProperties() },
            "UNIQUE" to { p, _ -> p.parseCompositeKeyProperty { a -> UniqueKeyProperty(a) } },
            "ROLLUP" to { p, _ -> (p as StarrocksParser).parseRollupProperty() },
            "REFRESH" to { p, _ -> (p as StarrocksParser).parseRefreshProperty() },
        )
}
