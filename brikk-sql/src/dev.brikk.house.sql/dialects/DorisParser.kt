package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AddMonths
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.AggregateKeyProperty
import dev.brikk.house.sql.ast.AggregateTypeColumnConstraint
import dev.brikk.house.sql.ast.ArrayUniqueAgg
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.AutoPartitionProperty
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DateSub
import dev.brikk.house.sql.ast.DorisRollupIndex
import dev.brikk.house.sql.ast.EuclideanDistance
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.IndexPropertiesOption
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Partition
import dev.brikk.house.sql.ast.PartitionByListProperty
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.PartitionByRangePropertyDynamic
import dev.brikk.house.sql.ast.PartitionList
import dev.brikk.house.sql.ast.PartitionRange
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.Property
import dev.brikk.house.sql.ast.RegexpLike
import dev.brikk.house.sql.ast.RollupProperty
import dev.brikk.house.sql.ast.TimestampTrunc
import dev.brikk.house.sql.ast.TsOrDsToDate
import dev.brikk.house.sql.ast.UniqueKeyProperty
import dev.brikk.house.sql.ast.Var
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
    tokenizerConfig: TokenizerConfig = DorisDialect.TOKENIZER_CONFIG,
) : MysqlParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.DORIS

    // brikk extension (registry entry 7): Doris array subscripts are 1-based (ELEMENT_AT /
    // arr[i] start at 1); sqlglot inherits MySQL's INDEX_OFFSET = 0. Mirrored by
    // DorisGenerator.dialectIndexOffset so doris->doris round-trips and cross-dialect
    // subscripts keep their semantics.
    override val indexOffset: Int get() = 1

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

    // brikk-native (docs/brikk-extensions.md #19): MySQL's + the aggregate-key column
    // aggregator suffix.
    override val constraintParsers: Map<String, (Parser) -> Expression?>
        get() = DorisParserTables.CONSTRAINT_PARSERS

    // brikk-native (docs/brikk-extensions.md #19): MySQL's + BITMAP / HLL / QUANTILE_STATE.
    override val typeTokens: Set<TokenType> get() = DorisParserTables.TYPE_TOKENS

    // sqlglot: DorisParser._parse_partition_property
    //
    // brikk-native (docs/brikk-extensions.md #19): the definition list is parsed here
    // in full rather than through MySQLParser._parse_partition_property, because Doris'
    // `partitionDef` grammar is a superset sqlglot's port cannot express:
    //  - `()` — the empty list dynamic / auto partitioned tables emit — yields a
    //    PartitionByRange/ListProperty with an EMPTY `create_expressions` (sqlglot raises
    //    "Required keyword: 'create_expressions' missing");
    //  - `PARTITION BY LIST (...)` yields PartitionByListProperty even without explicit
    //    definitions (sqlglot always builds the RANGE node here);
    //  - entries may mix `PARTITION p VALUES LESS THAN (..) | MAXVALUE | [(..), (..))`,
    //    `PARTITION p VALUES IN (..)` and `FROM (..) TO (..) INTERVAL n [unit]` in one
    //    list, each optionally followed by a per-partition `("k" = "v")` property list.
    override fun parsePartitionProperty(): kotlin.Any? {
        val isRange = matchTextSeq("RANGE")
        val isList = !isRange && matchTextSeq("LIST")

        // sqlglot: `if not expr: return self._parse_partitioned_by()`
        if (!isRange && !isList) return parsePartitionedBy()

        val partitionExpressions = parseWrappedCsv({ parseAssignment() })

        matchLParen()
        val createExpressions: List<Expression> = parseCsv { parsePartitionDefinition(isRange) }
        matchRParen()

        val nodeArgs = args(
            "partition_expressions" to partitionExpressions,
            "create_expressions" to createExpressions,
        )
        val node = if (isList) PartitionByListProperty(nodeArgs) else PartitionByRangeProperty(nodeArgs)
        // An empty definition list is legal Doris; build the node directly so the
        // required-arg validation in expression() does not reject it.
        return if (createExpressions.isEmpty()) node else expression(node)
    }

    // brikk-native (docs/brikk-extensions.md #19): one Doris `partitionDef` entry, or null
    // at the end of an empty list. A trailing `("k" = "v")` property list is appended to
    // the Partition's expressions as a Properties node (rendered by
    // DorisGenerator.partitionSql without the PROPERTIES keyword).
    open fun parsePartitionDefinition(isRange: kotlin.Boolean): Expression? {
        val partition: Expression = when {
            matchTextSeq("FROM", advance = false) -> parsePartitioningGranularityDynamic()
            matchTextSeq("PARTITION", advance = false) ->
                if (isRange) parsePartitionRangeValue()!! else parsePartitionListValue()
            else -> return null
        }
        if (partition is Partition && matchTextSeq("(", advance = false)) {
            val props = expression(Properties(args("expressions" to parseWrappedProperties())))
            partition.append("expressions", props)
        }
        return partition
    }

    // brikk-native (docs/brikk-extensions.md #19): Doris' two parameterized storage types
    // that sqlglot cannot parse (both are inherited-MySQL parse failures at the `<`):
    //  - `VARIANT<'name':type, ..., properties("k" = "v")>` -> DataType(VARIANT, nested,
    //    expressions = ColumnDef(this = string literal, kind = type)* + Properties?);
    //  - `AGG_STATE<fn(type [NULL | NOT NULL], ...)>` -> DataType(AGG_STATE, nested,
    //    expressions = [Anonymous(fn, DataType*)]) with the argument nullability kept on
    //    each DataType's `nullable` arg.
    // Everything else goes to the MySQL/base parseTypes.
    override fun parseTypes(
        checkFunc: kotlin.Boolean,
        schema: kotlin.Boolean,
        allowIdentifiers: kotlin.Boolean,
        withCollation: kotlin.Boolean,
    ): Expression? {
        if (nextToken.tokenType == TokenType.LT) {
            if (match(TokenType.VARIANT)) return parseVariantType()
            if (match(TokenType.AGG_STATE)) return parseAggStateType()
        }
        return super.parseTypes(checkFunc, schema, allowIdentifiers, withCollation)
    }

    private fun parseVariantType(): Expression {
        match(TokenType.LT)
        val fields = parseCsv<Expression> {
            if (matchTextSeq("PROPERTIES")) {
                expression(Properties(args("expressions" to parseWrappedProperties())))
            } else {
                val name = parseString() ?: raiseError("Expecting a quoted VARIANT field name")
                match(TokenType.COLON)
                val kind = parseTypes(schema = true) ?: raiseError("Expecting a type for VARIANT field")
                expression(ColumnDef(args("this" to name, "kind" to kind)))
            }
        }
        if (!match(TokenType.GT)) raiseError("Expecting >")
        return DataType(args("this" to DType.VARIANT, "expressions" to fields, "nested" to true))
    }

    private fun parseAggStateType(): Expression {
        match(TokenType.LT)
        val fnName = parseIdVar(anyToken = true)
        if (fnName == null) raiseError("Expecting an aggregate function name in AGG_STATE")
        val argTypes = parseWrappedCsv<Expression>({
            val t = parseTypes(schema = true) ?: raiseError("Expecting a type in AGG_STATE")
            if (matchTextSeq("NOT", "NULL")) {
                t?.set("nullable", false)
            } else if (match(TokenType.NULL)) {
                t?.set("nullable", true)
            }
            t
        })
        if (!match(TokenType.GT)) raiseError("Expecting >")
        val fn = expression(Anonymous(args("this" to (fnName?.name ?: ""), "expressions" to argTypes)))
        return DataType(args("this" to DType.AGG_STATE, "expressions" to listOf(fn), "nested" to true))
    }

    // brikk-native (docs/brikk-extensions.md #19): `AUTO PARTITION BY RANGE|LIST (...) (...)`.
    // sqlglot leaves the whole CREATE as an opaque Command. Returns null (and gives the
    // AUTO token back) when AUTO is not followed by PARTITION BY.
    open fun parseAutoPartitionProperty(): Expression? {
        if (!matchTextSeq("PARTITION BY")) {
            retreat(index - 1)
            return null
        }
        val inner = parsePartitionProperty() as? Expression
            ?: return raiseError("Expecting RANGE or LIST partition after AUTO PARTITION BY")
        return expression(AutoPartitionProperty(args("this" to inner)))
    }

    // brikk-native (docs/brikk-extensions.md #19): aggregate-key column aggregator suffix,
    // e.g. `v BIGINT SUM`, `b BITMAP BITMAP_UNION`. The aggregator keyword has already been
    // consumed by the CONSTRAINT_PARSERS dispatch; it is prevToken.
    open fun parseAggregateTypeConstraint(): Expression =
        expression(
            AggregateTypeColumnConstraint(
                args("this" to Var(args("this" to prevToken.text.uppercase())))
            )
        )

    // brikk-native (docs/brikk-extensions.md #19): `INDEX ... USING INVERTED PROPERTIES (...)`
    // on top of MySQL's index options.
    override fun parseIndexConstraintOption(): Expression? {
        if (matchTextSeq("PROPERTIES")) {
            return expression(IndexPropertiesOption(args("expressions" to parseWrappedProperties())))
        }
        return super.parseIndexConstraintOption()
    }

    // brikk-native (docs/brikk-extensions.md #19): `ROLLUP (name (cols) [DUPLICATE KEY (cols)]
    // [PROPERTIES (...)], ...)` per DorisParser.g4 `rollupDef`. Shaped after
    // StarRocksParser._parse_rollup_property (upstream #4509, never propagated to Doris),
    // but Doris' entry grammar has DUPLICATE KEY where StarRocks has FROM, so the entries
    // are DorisRollupIndex nodes rather than sqlglot's RollupIndex.
    open fun parseRollupProperty(): Expression {
        fun parseRollupIndex(): Expression =
            expression(
                DorisRollupIndex(
                    args(
                        "this" to parseIdVar(),
                        "expressions" to parseWrappedIdVars(),
                        "duplicate_key" to if (matchTextSeq("DUPLICATE", "KEY")) parseWrappedIdVars() else null,
                        "properties" to if (matchTextSeq("PROPERTIES")) {
                            expression(Properties(args("expressions" to parseWrappedProperties())))
                        } else {
                            null
                        },
                    )
                )
            )

        return expression(
            RollupProperty(args("expressions" to parseWrappedCsv({ parseRollupIndex() })))
        )
    }

    // sqlglot: DorisParser._parse_partitioning_granularity_dynamic
    open fun parsePartitioningGranularityDynamic(): Expression {
        matchTextSeq("FROM")
        // brikk-native (docs/brikk-extensions.md #19): bounds may be numbers for integer
        // partition columns (`FROM (1) TO (100)`); sqlglot's port only accepts strings.
        val start = parseWrapped({ parseString() ?: parseNumber() })
        matchTextSeq("TO")
        val end = parseWrapped({ parseString() ?: parseNumber() })
        matchTextSeq("INTERVAL")
        val number = parseNumber()
        // brikk-native (docs/brikk-extensions.md #19): the unit is optional for numeric
        // partition columns (`FROM (1) TO (100) INTERVAL 10`); sqlglot's port would
        // swallow the following `,` or `)` as the unit.
        val unit = if (currToken.tokenType in setOf(TokenType.COMMA, TokenType.R_PAREN)) null else parseVar(anyToken = true)
        val every = expression(
            Interval(args("this" to number, "unit" to unit?.let { normalizeTimeUnit(it) }))
        )
        return expression(
            PartitionByRangePropertyDynamic(args("start" to start, "end" to end, "every" to every))
        )
    }

    // sqlglot: DorisParser._parse_partition_range_value
    override fun parsePartitionRangeValue(): Expression? {
        // brikk-native (docs/brikk-extensions.md #19): `VALUES LESS THAN MAXVALUE` without
        // parentheses (Doris `lessThanPartitionDef`); sqlglot's MySQL base insists on `(`.
        val startIndex = index
        if (matchTextSeq("PARTITION")) {
            val name = parseIdVar()
            if (matchTextSeq("VALUES", "LESS", "THAN", "MAXVALUE")) {
                val partRange = expression(
                    PartitionRange(args("this" to name, "expressions" to listOf(Var(args("this" to "MAXVALUE")))))
                )
                return expression(Partition(args("expressions" to listOf(partRange))))
            }
            // Not the bare form: hand the whole entry back to the MySQL base path.
            retreat(startIndex)
        }
        val expr = super.parsePartitionRangeValue()

        if (expr is Partition) {
            // brikk-native (docs/brikk-extensions.md #19): MySQL's base only turns a lone
            // MAXVALUE into a Var; Doris allows it per column (`LESS THAN ('2020-01-01', MAXVALUE)`).
            expr.find(PartitionRange::class)?.let { range ->
                range.set("expressions", range.expressionsArg.map { normalizeMaxValue(it) })
            }
            return expr
        }

        matchTextSeq("VALUES")
        val name = expr

        // Doris-specific bracket syntax: VALUES [(...), (...))
        match(TokenType.L_BRACKET)
        val values = parseCsv { parseWrappedCsv({ normalizeMaxValue(parseExpression()) }) }

        match(TokenType.R_BRACKET)
        match(TokenType.R_PAREN)

        val partRange = expression(PartitionRange(args("this" to name, "expressions" to values)))
        return expression(Partition(args("expressions" to listOf(partRange))))
    }

    // brikk-native (docs/brikk-extensions.md #19): a bare MAXVALUE partition bound parses as
    // a Column (and would be rendered back-quoted, since it is reserved); keep it a Var.
    private fun normalizeMaxValue(value: kotlin.Any?): kotlin.Any? =
        if (value is Column && value.table.isEmpty() && value.name.uppercase() == "MAXVALUE") {
            Var(args("this" to "MAXVALUE"))
        } else {
            value
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
            // brikk-native (docs/brikk-extensions.md #19): Doris DDL clauses sqlglot lacks.
            "AGGREGATE" to { p, _ -> p.parseCompositeKeyProperty { a -> AggregateKeyProperty(a) } },
            "AUTO" to { p, _ -> (p as DorisParser).parseAutoPartitionProperty() },
            "ROLLUP" to { p, _ -> (p as DorisParser).parseRollupProperty() },
        )

    // brikk-native (docs/brikk-extensions.md #19): aggregate-key column aggregators
    // (reference/doris .../DorisParser.g4 `aggTypeDef`). Dispatch is by keyword text after
    // the column type, in the same slot MySQL parses its column constraints.
    val AGGREGATE_TYPES: Set<String> = setOf(
        "SUM", "MAX", "MIN", "REPLACE", "REPLACE_IF_NOT_NULL",
        "HLL_UNION", "BITMAP_UNION", "QUANTILE_UNION", "GENERIC",
    )

    // brikk-native (docs/brikk-extensions.md #19): MySQL's constraint parsers + aggregators.
    val CONSTRAINT_PARSERS: Map<String, (Parser) -> Expression?> =
        MysqlParserTables.CONSTRAINT_PARSERS +
            AGGREGATE_TYPES.associateWith<String, (Parser) -> Expression?> {
                { p -> (p as DorisParser).parseAggregateTypeConstraint() }
            }

    // brikk-native (docs/brikk-extensions.md #19): MySQL's type tokens + Doris storage types.
    val TYPE_TOKENS: Set<TokenType> =
        MysqlParserTables.TYPE_TOKENS + setOf(TokenType.BITMAP, TokenType.HLL, TokenType.QUANTILE_STATE, TokenType.AGG_STATE)
}
