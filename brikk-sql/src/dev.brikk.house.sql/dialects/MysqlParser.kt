package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AlterIndex
import dev.brikk.house.sql.ast.And
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Args
import dev.brikk.house.sql.ast.AutoIncrementProperty
import dev.brikk.house.sql.ast.BitwiseAndAgg
import dev.brikk.house.sql.ast.BitwiseCount
import dev.brikk.house.sql.ast.BitwiseOrAgg
import dev.brikk.house.sql.ast.BitwiseXorAgg
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.ColumnPrefix
import dev.brikk.house.sql.ast.ComputedColumnConstraint
import dev.brikk.house.sql.ast.Concat
import dev.brikk.house.sql.ast.ConvertTimezone
import dev.brikk.house.sql.ast.CurrentSchema
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DateAdd
import dev.brikk.house.sql.ast.DateDiff
import dev.brikk.house.sql.ast.DateSub
import dev.brikk.house.sql.ast.Day
import dev.brikk.house.sql.ast.DayOfMonth
import dev.brikk.house.sql.ast.DayOfWeek
import dev.brikk.house.sql.ast.DayOfYear
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.DropPrimaryKey
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GeneratedAsIdentityColumnConstraint
import dev.brikk.house.sql.ast.GroupConcat
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.IndexColumnConstraint
import dev.brikk.house.sql.ast.IndexConstraintOption
import dev.brikk.house.sql.ast.Interval
import dev.brikk.house.sql.ast.InvisibleColumnConstraint
import dev.brikk.house.sql.ast.Is
import dev.brikk.house.sql.ast.JSONArrayContains
import dev.brikk.house.sql.ast.JSONValue
import dev.brikk.house.sql.ast.Length
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Ln
import dev.brikk.house.sql.ast.Localtime
import dev.brikk.house.sql.ast.Localtimestamp
import dev.brikk.house.sql.ast.LockProperty
import dev.brikk.house.sql.ast.Log
import dev.brikk.house.sql.ast.ModifyColumn
import dev.brikk.house.sql.ast.Month
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.NumberToStr
import dev.brikk.house.sql.ast.OnCondition
import dev.brikk.house.sql.ast.Or
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Partition
import dev.brikk.house.sql.ast.PartitionByListProperty
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.PartitionList
import dev.brikk.house.sql.ast.PartitionRange
import dev.brikk.house.sql.ast.RenameIndex
import dev.brikk.house.sql.ast.SetItem
import dev.brikk.house.sql.ast.Show
import dev.brikk.house.sql.ast.Soundex
import dev.brikk.house.sql.ast.StrToDate
import dev.brikk.house.sql.ast.StrToTime
import dev.brikk.house.sql.ast.TimeToStr
import dev.brikk.house.sql.ast.TimestampDiff
import dev.brikk.house.sql.ast.TsOrDsToDate
import dev.brikk.house.sql.ast.TsOrDsToTimestamp
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.Week
import dev.brikk.house.sql.ast.WeekOfYear
import dev.brikk.house.sql.ast.Xor
import dev.brikk.house.sql.ast.Year
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.NodeFactory
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString

// sqlglot: parsers.mysql.TIME_SPECIFIERS — specifiers for time (vs date) parts
private val TIME_SPECIFIERS = setOf('f', 'H', 'h', 'I', 'i', 'k', 'l', 'p', 'r', 'S', 's', 'T')

// sqlglot: parsers.mysql._has_time_specifier
private fun hasTimeSpecifier(dateFormat: String): Boolean {
    var i = 0
    while (i < dateFormat.length) {
        if (dateFormat[i] == '%') {
            i += 1
            if (i < dateFormat.length && dateFormat[i] in TIME_SPECIFIERS) return true
        }
        i += 1
    }
    return false
}

private fun seqGet(argsList: List<Expression?>, index: Int): Expression? = argsList.getOrNull(index)

// sqlglot: Dialect["mysql"].format_time on a parsed expression
private fun mysqlFormatTime(expression: Expression?): Expression? {
    if (expression is Literal && expression.isString) {
        val converted = formatTimeString(expression.thisArg as? String, MysqlDialect.TIME_MAPPING)
        return Literal(args("this" to converted, "is_string" to true))
    }
    return expression
}

// sqlglot: parsers.mysql._str_to_date
private fun buildStrToDate(argsList: List<Expression?>): Expression {
    val mysqlDateFormat = seqGet(argsList, 1)
    val dateFormat = mysqlFormatTime(mysqlDateFormat)
    val this_ = seqGet(argsList, 0)

    if (mysqlDateFormat != null && hasTimeSpecifier(mysqlDateFormat.name)) {
        return StrToTime(args("this" to this_, "format" to dateFormat))
    }
    return StrToDate(args("this" to this_, "format" to dateFormat))
}

// sqlglot: expressions.TimeUnit.UNABBREVIATED_UNIT_NAME
private val UNABBREVIATED_UNIT_NAME: Map<String, String> = mapOf(
    "D" to "DAY", "H" to "HOUR", "M" to "MINUTE", "MS" to "MILLISECOND",
    "NS" to "NANOSECOND", "Q" to "QUARTER", "S" to "SECOND", "US" to "MICROSECOND",
    "W" to "WEEK", "Y" to "YEAR",
)

/**
 * sqlglot: TimeUnit.__init__ — the Kotlin AST nodes don't run Python's automatic
 * unit-to-Var conversion, so builders that construct TimeUnit nodes normalize here.
 */
internal fun normalizeTimeUnit(unit: Expression?): Expression? {
    if (unit == null) return null
    if (unit is Var || unit is Literal || (unit is Column && unit.args["table"] == null)) {
        val name = UNABBREVIATED_UNIT_NAME[unit.name] ?: unit.name
        return Var(args("this" to name.uppercase()))
    }
    return unit
}

// sqlglot: dialect.build_date_delta_with_interval (default_unit=None)
private fun buildDateDeltaWithInterval(
    factory: NodeFactory,
): (List<Expression?>) -> Expression = { argsList ->
    if (argsList.size < 2) throw ParseError("INTERVAL expression expected")
    val interval = argsList[1]
    if (interval !is Interval) throw ParseError("INTERVAL expression expected but got '$interval'")
    factory(
        args(
            "this" to argsList[0],
            "expression" to interval.thisArg,
            // sqlglot: unit_to_str(interval), then TimeUnit.__init__ converts to Var
            "unit" to normalizeTimeUnit(intervalUnitToStr(interval)),
        )
    )
}

// sqlglot: dialect.unit_to_str for Interval nodes
private fun intervalUnitToStr(interval: Interval): Expression? {
    val unit = interval.args["unit"] as? Expression ?: return Literal.string("DAY")
    return if (unit is Var || unit is Literal) Literal.string(unit.name) else unit
}

// sqlglot: dialect.build_date_delta (unit_mapping=None, default_unit="DAY")
private fun buildDateDelta(factory: NodeFactory): (List<Expression?>) -> Expression = { argsList ->
    val unitBased = argsList.size >= 3
    val this_ = if (unitBased) argsList[2] else seqGet(argsList, 0)
    val unit: Expression? = if (unitBased) argsList[0] else Literal.string("DAY")
    factory(
        args(
            "this" to this_,
            "expression" to seqGet(argsList, 1),
            "unit" to normalizeTimeUnit(unit),
        )
    )
}

/**
 * Port of sqlglot's MySQLParser (reference/sqlglot/sqlglot/parsers/mysql.py).
 * Table merges live in [MysqlParserTables]; overridden _parse_* methods below.
 */
// sqlglot: parsers.mysql.MySQLParser
open class MysqlParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.MysqlTokenizerTables.CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.MYSQL

    // -------------------------------------------------------------------
    // Flags (sqlglot: MySQLParser / MySQL dialect class attributes)
    // -------------------------------------------------------------------

    // sqlglot: MySQLParser.STRING_ALIASES = True
    override val stringAliases: Boolean get() = true

    // sqlglot: MySQLParser.VALUES_FOLLOWED_BY_PAREN = False
    override val valuesFollowedByParen: Boolean get() = false

    // sqlglot: MySQLParser.SUPPORTS_PARTITION_SELECTION = True
    override val supportsPartitionSelection: Boolean get() = true

    // sqlglot: MySQL.SUPPORTS_USER_DEFINED_TYPES = False
    override val supportsUserDefinedTypes: Boolean get() = false

    // sqlglot: MySQL.DPIPE_IS_STRING_CONCAT = False
    override val dpipeIsStringConcat: Boolean get() = false

    // sqlglot: MySQL.SAFE_DIVISION = True
    override val safeDivision: Boolean get() = true

    // sqlglot: MySQL.VALID_INTERVAL_UNITS (extended with compound units)
    override val validIntervalUnits: Set<String> get() = MysqlParserTables.VALID_INTERVAL_UNITS

    // sqlglot: MySQLParser.OPERATION_MODIFIERS
    override val operationModifiers: Set<String> get() = MysqlParserTables.OPERATION_MODIFIERS

    // -------------------------------------------------------------------
    // Table overrides (sqlglot: MySQLParser class-level dict/set merges)
    // -------------------------------------------------------------------

    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = MysqlParserTables.NO_PAREN_FUNCTIONS
    override val idVarTokens: Set<TokenType> get() = MysqlParserTables.ID_VAR_TOKENS
    override val funcTokens: Set<TokenType> get() = MysqlParserTables.FUNC_TOKENS
    override val conjunction: Map<TokenType, NodeFactory> get() = MysqlParserTables.CONJUNCTION
    override val disjunction: Map<TokenType, NodeFactory> get() = MysqlParserTables.DISJUNCTION
    override val tableAliasTokens: Set<TokenType> get() = MysqlParserTables.TABLE_ALIAS_TOKENS
    override val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?>
        get() = MysqlParserTables.RANGE_PARSERS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = MysqlParserTables.FUNCTIONS
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = MysqlParserTables.FUNCTION_PARSERS
    override val statementParsers: Map<TokenType, (Parser) -> Expression>
        get() = MysqlParserTables.STATEMENT_PARSERS
    override val propertyParsers: Map<String, (Parser, PropertyKwargs) -> kotlin.Any?>
        get() = MysqlParserTables.PROPERTY_PARSERS
    override val setParsers: Map<String, (Parser) -> Expression?>
        get() = super.setParsers + MysqlParserTables.SET_PARSERS_EXTRA
    override val constraintParsers: Map<String, (Parser) -> Expression?>
        get() = MysqlParserTables.CONSTRAINT_PARSERS
    override val alterParsers: Map<String, (Parser) -> kotlin.Any?>
        get() = super.alterParsers + MysqlParserTables.ALTER_PARSERS_EXTRA
    override val alterAlterParsers: Map<String, (Parser) -> Expression?>
        get() = super.alterAlterParsers + MysqlParserTables.ALTER_ALTER_PARSERS_EXTRA
    override val schemaUnnamedConstraints: Set<String>
        get() = MysqlParserTables.SCHEMA_UNNAMED_CONSTRAINTS
    override val typeTokens: Set<TokenType> get() = MysqlParserTables.TYPE_TOKENS
    override val enumTypeTokens: Set<TokenType> get() = MysqlParserTables.ENUM_TYPE_TOKENS

    // sqlglot: MySQLParser.SHOW_PARSERS
    open val showParsers: Map<String, (MysqlParser) -> Expression>
        get() = MysqlParserTables.SHOW_PARSERS

    // sqlglot: MySQLParser.PROFILE_TYPES
    open val profileTypes: Map<String, List<List<String>>> get() = MysqlParserTables.PROFILE_TYPES

    // -------------------------------------------------------------------
    // Overridden parse methods (sqlglot: MySQLParser._parse_*)
    // -------------------------------------------------------------------

    // sqlglot: MySQLParser._parse_alter_table_rename
    override fun parseAlterTableRename(): Expression? {
        if (matchTexts(setOf("INDEX", "KEY"))) {
            val old = parseField(anyToken = true)
            matchTextSeq("TO")
            val new = parseField(anyToken = true)
            return expression(RenameIndex(args("this" to old, "to" to new)))
        }
        return super.parseAlterTableRename()
    }

    // sqlglot: MySQLParser._parse_alter_drop_action
    override fun parseAlterDropAction(): Expression? {
        if (matchPair(TokenType.DROP, TokenType.PRIMARY_KEY)) {
            return expression(DropPrimaryKey())
        }
        return super.parseAlterDropAction()
    }

    // sqlglot: MySQLParser._parse_alter_table_modify
    open fun parseAlterTableModify(rename: Boolean = false): Expression? {
        // MODIFY [COLUMN]            col_name      column_definition [FIRST | AFTER col_name]
        // CHANGE [COLUMN] old_col_name new_col_name column_definition [FIRST | AFTER col_name]
        match(TokenType.COLUMN)

        var column = parseField(anyToken = true) ?: return null

        var renameFrom: Expression? = null
        if (rename) {
            renameFrom = column
            column = parseField(anyToken = true) ?: return null
        }

        val columnDef = parseColumnDef(column)
        if (columnDef !is ColumnDef) return null

        return expression(ModifyColumn(args("this" to columnDef, "rename_from" to renameFrom)))
    }

    // sqlglot: MySQLParser._parse_generated_as_identity
    override fun parseGeneratedAsIdentity(): Expression {
        var this_ = super.parseGeneratedAsIdentity()

        if (matchTexts(setOf("STORED", "VIRTUAL"))) {
            val persisted = prevToken.text.uppercase() == "STORED"

            if (this_ is ComputedColumnConstraint) {
                this_.set("persisted", persisted)
            } else if (this_ is GeneratedAsIdentityColumnConstraint) {
                this_ = expression(
                    ComputedColumnConstraint(
                        args("this" to this_.args["expression"], "persisted" to persisted)
                    )
                )
            }
        }

        return this_
    }

    // sqlglot: MySQLParser._parse_primary_key_part
    override fun parsePrimaryKeyPart(): Expression? {
        val this_ = parseIdVar()
        if (!match(TokenType.L_PAREN)) {
            return this_
        }

        val expr = parseNumber()
        matchRParen()
        return expression(ColumnPrefix(args("this" to this_, "expression" to expr)))
    }

    // sqlglot: MySQLParser._parse_index_constraint
    open fun parseIndexConstraint(kind: String? = null): Expression {
        if (kind != null) {
            matchTexts(setOf("INDEX", "KEY"))
        }

        val this_ = parseIdVar(anyToken = false)
        // sqlglot: `self._match(TokenType.USING) and self._advance_any() and self._prev.text`
        // (evaluates to False, not None, when USING is absent)
        val indexType: kotlin.Any =
            if (match(TokenType.USING) && advanceAny() != null) prevToken.text else false
        val expressions = parseWrappedCsv({ parseOrdered() })

        val options = mutableListOf<Expression>()
        while (true) {
            val opt: Expression? = if (matchTextSeq("KEY_BLOCK_SIZE")) {
                match(TokenType.EQ)
                IndexConstraintOption(args("key_block_size" to parseNumber()))
            } else if (match(TokenType.USING)) {
                IndexConstraintOption(
                    args("using" to (if (advanceAny() != null) prevToken.text else null))
                )
            } else if (matchTextSeq("WITH", "PARSER")) {
                IndexConstraintOption(args("parser" to parseVar(anyToken = true)))
            } else if (match(TokenType.COMMENT)) {
                IndexConstraintOption(args("comment" to parseString()))
            } else if (matchTextSeq("VISIBLE")) {
                IndexConstraintOption(args("visible" to true))
            } else if (matchTextSeq("INVISIBLE")) {
                IndexConstraintOption(args("visible" to false))
            } else if (matchTextSeq("ENGINE_ATTRIBUTE")) {
                match(TokenType.EQ)
                IndexConstraintOption(args("engine_attr" to parseString()))
            } else if (matchTextSeq("SECONDARY_ENGINE_ATTRIBUTE")) {
                match(TokenType.EQ)
                IndexConstraintOption(args("secondary_engine_attr" to parseString()))
            } else {
                null
            }

            if (opt == null) break
            options.add(opt)
        }

        return expression(
            IndexColumnConstraint(
                args(
                    "this" to this_,
                    "expressions" to expressions,
                    "kind" to kind,
                    "index_type" to indexType,
                    "options" to options,
                )
            )
        )
    }

    // sqlglot: Parser._parse_show (dispatch through SHOW_PARSERS via _find_parser)
    open fun parseShow(): Expression {
        val parser = findParser(showParsers)
        if (parser != null) return parser(this)
        return parseAsCommand(prevToken)
    }

    // sqlglot: MySQLParser._parse_show_mysql
    open fun parseShowMysql(
        thisName: String,
        target: kotlin.Any = false,
        full: Boolean? = null,
        global_: Boolean? = null,
    ): Expression {
        val json = matchTextSeq("JSON")

        var targetId: Expression? = null
        if (target != false) {
            if (target is String) {
                matchTextSeq(*target.split(" ").toTypedArray())
            }
            targetId = parseIdVar()
        }

        val startIndex = index
        var log: Expression? = null
        if (matchTextSeq("IN")) {
            log = parseString()
            if (log == null) retreat(startIndex)
        }

        var position: Expression? = null
        var db: Expression? = null
        if (thisName in setOf("BINLOG EVENTS", "RELAYLOG EVENTS")) {
            position = if (matchTextSeq("FROM")) parseNumber() else null
        } else {
            if (match(TokenType.FROM) || matchTextSeq("IN")) {
                db = parseIdVar()
            } else if (match(TokenType.DOT)) {
                db = targetId
                targetId = parseIdVar()
            }
        }

        val channel = if (matchTextSeq("FOR", "CHANNEL")) parseIdVar() else null

        val like = if (matchTextSeq("LIKE")) parseString() else null
        val where = parseWhere()

        val types: List<Expression>?
        val query: Expression?
        var offset: Expression? = null
        var limit: Expression? = null
        if (thisName == "PROFILE") {
            types = parseCsv { parseVarFromOptions(profileTypes) }
            query = if (matchTextSeq("FOR", "QUERY")) parseNumber() else null
            offset = if (matchTextSeq("OFFSET")) parseNumber() else null
            limit = if (matchTextSeq("LIMIT")) parseNumber() else null
        } else {
            types = null
            query = null
            val (o, l) = parseOldstyleLimit()
            offset = o
            limit = l
        }

        var mutex: Boolean? = if (matchTextSeq("MUTEX")) true else null
        mutex = if (matchTextSeq("STATUS")) false else mutex

        val forTable = if (matchTextSeq("FOR", "TABLE")) parseIdVar() else null
        val forGroup = if (matchTextSeq("FOR", "GROUP")) parseString() else null
        val forUser = if (matchTextSeq("FOR", "USER")) parseString() else null
        val forRole = if (matchTextSeq("FOR", "ROLE")) parseString() else null
        val intoOutfile = if (matchTextSeq("INTO", "OUTFILE")) parseString() else null

        return expression(
            Show(
                args(
                    "this" to thisName,
                    "target" to targetId,
                    "full" to full,
                    "log" to log,
                    "position" to position,
                    "db" to db,
                    "channel" to channel,
                    "like" to like,
                    "where" to where,
                    "types" to types,
                    "query" to query,
                    "offset" to offset,
                    "limit" to limit,
                    "mutex" to mutex,
                    "for_table" to forTable,
                    "for_group" to forGroup,
                    "for_user" to forUser,
                    "for_role" to forRole,
                    "into_outfile" to intoOutfile,
                    "json" to json,
                    "global_" to global_,
                )
            )
        )
    }

    // sqlglot: MySQLParser._parse_oldstyle_limit
    protected fun parseOldstyleLimit(): Pair<Expression?, Expression?> {
        var limit: Expression? = null
        var offset: Expression? = null
        if (matchTextSeq("LIMIT")) {
            val parts = parseCsv { parseNumber() }
            if (parts.size == 1) {
                limit = parts[0]
            } else if (parts.size == 2) {
                limit = parts[1]
                offset = parts[0]
            }
        }
        return offset to limit
    }

    // sqlglot: MySQLParser._parse_set_item_charset
    open fun parseSetItemCharset(kind: String): Expression {
        val this_ = parseString() ?: parseUnquotedField()
        return expression(SetItem(args("this" to this_, "kind" to kind)))
    }

    // sqlglot: MySQLParser._parse_charset_name
    override fun parseCharsetName(): Expression? {
        val identifier = parseIdentifier()
        if (identifier is Identifier) {
            val name = identifier.name
            return if (SAFE_IDENTIFIER_RE.matches(name)) Var(args("this" to name)) else identifier
        }
        return parseVar(tokens = setOf(TokenType.BINARY))
    }

    // sqlglot: MySQLParser._parse_set_item_names
    open fun parseSetItemNames(): Expression {
        val charset = parseString() ?: parseUnquotedField()
        val collate = if (matchTextSeq("COLLATE")) parseString() ?: parseUnquotedField() else null

        return expression(SetItem(args("this" to charset, "collate" to collate, "kind" to "NAMES")))
    }

    // sqlglot: MySQLParser._parse_type — mysql BINARY works like a no-paren func
    override fun parseType(parseInterval: Boolean, fallbackToIdentifier: Boolean): Expression? {
        if (match(TokenType.BINARY, advance = false)) {
            val dataType = parseTypes(checkFunc = true, allowIdentifiers = false)

            if (dataType is DataType) {
                return expression(Cast(args("this" to parseColumn(), "to" to dataType)))
            }
        }

        return super.parseType(parseInterval = parseInterval, fallbackToIdentifier = fallbackToIdentifier)
    }

    // sqlglot: MySQLParser._parse_alter_table_alter_index
    open fun parseAlterTableAlterIndex(): Expression {
        val index = parseField(anyToken = true)

        val visible: Boolean? = if (matchTextSeq("VISIBLE")) {
            true
        } else if (matchTextSeq("INVISIBLE")) {
            false
        } else {
            null
        }

        return expression(AlterIndex(args("this" to index, "visible" to visible)))
    }

    // sqlglot: MySQLParser._parse_partition_property
    open fun parsePartitionProperty(): kotlin.Any? {
        val isRange = matchTextSeq("RANGE")
        val isList = !isRange && matchTextSeq("LIST")

        if (!isRange && !isList) return null

        val partitionExpressions = parseWrappedCsv({ parseAssignment() })

        // For Doris and Starrocks
        if (!matchTextSeq("(", "PARTITION", advance = false)) {
            return partitionExpressions
        }

        val createExpressions = parseWrappedCsv({
            if (isRange) parsePartitionRangeValue() else parsePartitionListValue()
        })

        val ctorArgs = args(
            "partition_expressions" to partitionExpressions,
            "create_expressions" to createExpressions,
        )
        return expression(
            if (isRange) PartitionByRangeProperty(ctorArgs) else PartitionByListProperty(ctorArgs)
        )
    }

    // sqlglot: MySQLParser._parse_partition_range_value
    protected open fun parsePartitionRangeValue(): Expression? {
        matchTextSeq("PARTITION")
        val name = parseIdVar()

        if (!matchTextSeq("VALUES", "LESS", "THAN")) {
            return name
        }

        var values: List<Expression?> = parseWrappedCsv({ parseExpression() })

        val single = values.singleOrNull()
        if (single is Column && single.name.uppercase() == "MAXVALUE") {
            values = listOf(Var(args("this" to "MAXVALUE")))
        }

        val partRange = expression(PartitionRange(args("this" to name, "expressions" to values)))
        return expression(Partition(args("expressions" to listOf(partRange))))
    }

    // sqlglot: MySQLParser._parse_partition_list_value
    protected fun parsePartitionListValue(): Expression {
        matchTextSeq("PARTITION")
        val name = parseIdVar()
        matchTextSeq("VALUES", "IN")
        val values = parseWrappedCsv({ parseExpression() })
        val partList = expression(PartitionList(args("this" to name, "expressions" to values)))
        return expression(Partition(args("expressions" to listOf(partList))))
    }

    // sqlglot: MySQLParser._parse_primary_key (named_primary_key=True)
    override fun parsePrimaryKey(
        wrappedOptional: Boolean,
        inProps: Boolean,
        namedPrimaryKey: Boolean,
    ): Expression = super.parsePrimaryKey(
        wrappedOptional = wrappedOptional,
        inProps = inProps,
        namedPrimaryKey = true,
    )

    // -------------------------------------------------------------------
    // Ported base-parser methods only reachable from mysql tables
    // -------------------------------------------------------------------

    // sqlglot: Parser._parse_group_concat
    open fun parseGroupConcat(): Expression? {
        val csvArgs = parseCsv { parseLambda() }

        // sqlglot: nested concat_exprs closure (bug-compatible: the else branch
        // concatenates the whole csv list, not just `exprs`)
        fun concatExprs(node: Expression?, exprs: List<Expression?>): Expression? {
            if (node is Distinct && node.expressionsArg.size > 1) {
                val concat = expression(
                    Concat(
                        args(
                            "expressions" to node.args["expressions"],
                            "safe" to true,
                            // sqlglot: MySQL.CONCAT_COALESCE = False
                            "coalesce" to false,
                        )
                    )
                )
                node.set("expressions", listOf(concat))
                return node
            }
            if (exprs.size == 1) return exprs[0]
            return expression(
                Concat(args("expressions" to csvArgs, "safe" to true, "coalesce" to false))
            )
        }

        val this_: Expression?
        if (csvArgs.isNotEmpty()) {
            val order = csvArgs.last() as? Order

            if (order != null) {
                // Order By is the last (or only) expression in the list and has consumed the
                // 'expr' before it, remove 'expr' from exp.Order and add it back to args
                val orderThis = order.thisArg as? Expression
                if (orderThis != null) csvArgs[csvArgs.size - 1] = orderThis
                order.set("this", concatExprs(orderThis, csvArgs))
            }

            this_ = order ?: concatExprs(csvArgs[0], csvArgs)
        } else {
            this_ = null
        }

        val separator = if (match(TokenType.SEPARATOR)) parseField() else null

        return expression(GroupConcat(args("this" to this_, "separator" to separator)))
    }

    // sqlglot: Parser._parse_json_value
    open fun parseJsonValue(): Expression {
        val this_ = parseBitwise()
        match(TokenType.COMMA)
        val path = parseBitwise()

        val returning = if (match(TokenType.RETURNING)) parseType() else null

        return expression(
            JSONValue(
                args(
                    "this" to this_,
                    "path" to toJsonPath(path),
                    "returning" to returning,
                    "on_condition" to parseOnCondition(),
                )
            )
        )
    }

    // sqlglot: Parser.ON_CONDITION_TOKENS
    protected val onConditionTokens: Set<String> get() = setOf("ERROR", "NULL", "TRUE", "FALSE", "EMPTY")

    // sqlglot: Parser._parse_on_condition (MySQL: ON_CONDITION_EMPTY_BEFORE_ERROR=True)
    protected fun parseOnCondition(): Expression? {
        val empty = parseOnHandling("EMPTY")
        val error = parseOnHandling("ERROR")
        val nullHandling = parseOnHandling("NULL")

        if (empty == null && error == null && nullHandling == null) return null

        return expression(
            OnCondition(args("empty" to empty, "error" to error, "null" to nullHandling))
        )
    }

    // sqlglot: Parser._parse_on_handling
    protected fun parseOnHandling(on: String): kotlin.Any? {
        for (value in onConditionTokens) {
            if (matchTextSeq(value, "ON", on)) return "$value ON $on"
        }

        val startIndex = index
        if (match(TokenType.DEFAULT)) {
            val defaultValue = parseBitwise()
            if (matchTextSeq("ON", on)) return defaultValue

            retreat(startIndex)
        }

        return null
    }

    companion object {
        // sqlglot: expressions.SAFE_IDENTIFIER_RE
        private val SAFE_IDENTIFIER_RE = Regex("^[_a-zA-Z][\\w]*$")
    }
}

/**
 * Merged parser tables for MySQL (sqlglot: MySQLParser class-level dict merges over
 * parser.Parser). Kept in an object so the merges happen once.
 */
object MysqlParserTables {

    // sqlglot: MySQLParser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        BaseParserTables.NO_PAREN_FUNCTIONS + mapOf(
            TokenType.LOCALTIME to { Localtime() },
            TokenType.LOCALTIMESTAMP to { Localtimestamp() },
        )

    // sqlglot: MySQLParser.ID_VAR_TOKENS
    val ID_VAR_TOKENS: Set<TokenType> =
        BaseParserTables.ID_VAR_TOKENS - setOf(TokenType.STRAIGHT_JOIN)

    // sqlglot: MySQLParser.FUNC_TOKENS
    val FUNC_TOKENS: Set<TokenType> = BaseParserTables.FUNC_TOKENS + setOf(
        TokenType.DATABASE,
        TokenType.MOD,
        TokenType.SCHEMA,
        TokenType.VALUES,
        TokenType.CHARACTER_SET,
    )

    // sqlglot: MySQLParser.CONJUNCTION
    val CONJUNCTION: Map<TokenType, NodeFactory> = BaseParserTables.CONJUNCTION + mapOf<TokenType, NodeFactory>(
        TokenType.DAMP to { a -> And(a) },
        TokenType.XOR to { a -> Xor(a) },
    )

    // sqlglot: MySQLParser.DISJUNCTION
    val DISJUNCTION: Map<TokenType, NodeFactory> = BaseParserTables.DISJUNCTION + mapOf<TokenType, NodeFactory>(
        TokenType.DPIPE to { a -> Or(a) },
    )

    // sqlglot: Parser.TABLE_INDEX_HINT_TOKENS
    private val TABLE_INDEX_HINT_TOKENS: Set<TokenType> =
        setOf(TokenType.FORCE, TokenType.IGNORE, TokenType.USE)

    // sqlglot: MySQLParser.TABLE_ALIAS_TOKENS
    val TABLE_ALIAS_TOKENS: Set<TokenType> =
        (BaseParserTables.TABLE_ALIAS_TOKENS + setOf(TokenType.ANTI, TokenType.SEMI)) -
            TABLE_INDEX_HINT_TOKENS - setOf(TokenType.STRAIGHT_JOIN)

    // sqlglot: MySQLParser.RANGE_PARSERS
    val RANGE_PARSERS: Map<TokenType, (Parser, Expression?) -> Expression?> =
        BaseParserTables.RANGE_PARSERS + mapOf<TokenType, (Parser, Expression?) -> Expression?>(
            TokenType.SOUNDS_LIKE to { p, this_ ->
                p.expression(
                    EQ(
                        args(
                            "this" to p.expression(Soundex(args("this" to this_))),
                            "expression" to p.expression(Soundex(args("this" to p.parseTerm()))),
                        )
                    )
                )
            },
            TokenType.MEMBER_OF to { p, this_ ->
                p.expression(
                    JSONArrayContains(
                        args(
                            "this" to this_,
                            "expression" to p.parseWrapped({ p.parseExpression() }),
                        )
                    )
                )
            },
        )

    // sqlglot: MySQLParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(BaseParserTables.FUNCTIONS)
        put("BIT_AND") { a -> BitwiseAndAgg(args("this" to seqGet(a, 0))) }
        put("BIT_OR") { a -> BitwiseOrAgg(args("this" to seqGet(a, 0))) }
        put("BIT_XOR") { a -> BitwiseXorAgg(args("this" to seqGet(a, 0))) }
        put("BIT_COUNT") { a -> BitwiseCount(args("this" to seqGet(a, 0))) }
        put("CONVERT_TZ") { a ->
            ConvertTimezone(
                args(
                    "source_tz" to seqGet(a, 1),
                    "target_tz" to seqGet(a, 2),
                    "timestamp" to seqGet(a, 0),
                )
            )
        }
        put("CURDATE", BaseParserTables.FUNCTIONS.getValue("CURRENT_DATE"))
        put("CURTIME", BaseParserTables.FUNCTIONS.getValue("CURRENT_TIME"))
        put("DATE") { a -> TsOrDsToDate(args("this" to seqGet(a, 0))) }
        put("DATE_ADD", buildDateDeltaWithInterval { a -> DateAdd(a) })
        put("DATE_FORMAT") { a ->
            TimeToStr(
                args(
                    "this" to TsOrDsToTimestamp(args("this" to seqGet(a, 0))),
                    "format" to mysqlFormatTime(seqGet(a, 1)),
                )
            )
        }
        put("DATE_SUB", buildDateDeltaWithInterval { a -> DateSub(a) })
        put("DAY") { a -> Day(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("DAYOFMONTH") { a -> DayOfMonth(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("DAYOFWEEK") { a -> DayOfWeek(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("DAYOFYEAR") { a -> DayOfYear(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("FORMAT") { a -> NumberToStr(args("this" to seqGet(a, 0), "format" to seqGet(a, 1), "culture" to seqGet(a, 2))) }
        // sqlglot: build_formatted_time(exp.UnixToTime) — no default format
        put("FROM_UNIXTIME") { a ->
            dev.brikk.house.sql.ast.UnixToTime(
                args("this" to seqGet(a, 0), "format" to mysqlFormatTime(seqGet(a, 1)))
            )
        }
        // sqlglot: dialect.isnull_to_is_null
        put("ISNULL") { a -> Paren(args("this" to Is(args("this" to seqGet(a, 0), "expression" to Null())))) }
        put("LENGTH") { a -> Length(args("this" to seqGet(a, 0), "binary" to true)) }
        put("MAKETIME", BaseParserTables.FUNCTIONS.getValue("TIMEFROMPARTS"))
        // sqlglot: MySQLParser.LOG_DEFAULTS_TO_LN = True (build_logarithm)
        put("LOG") { a ->
            val this_ = seqGet(a, 0)
            val expr = seqGet(a, 1)
            if (expr != null) Log(args("this" to this_, "expression" to expr))
            else Ln(args("this" to this_))
        }
        put("MONTH") { a -> Month(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("MONTHNAME") { a ->
            TimeToStr(
                args(
                    "this" to TsOrDsToDate(args("this" to seqGet(a, 0))),
                    "format" to Literal.string("%B"),
                )
            )
        }
        put("SCHEMA") { a -> CurrentSchema(args("this" to seqGet(a, 0))) }
        put("DATABASE") { a -> CurrentSchema(args("this" to seqGet(a, 0))) }
        put("STR_TO_DATE", ::buildStrToDate)
        put("TIMESTAMPDIFF", buildDateDelta { a -> TimestampDiff(a) })
        put("TO_DAYS") { a ->
            Paren(
                args(
                    "this" to dev.brikk.house.sql.ast.Add(
                        args(
                            "this" to DateDiff(
                                args(
                                    "this" to TsOrDsToDate(args("this" to seqGet(a, 0))),
                                    "expression" to TsOrDsToDate(args("this" to Literal.string("0000-01-01"))),
                                    "unit" to Var(args("this" to "DAY")),
                                )
                            ),
                            "expression" to Literal.number("1"),
                        )
                    )
                )
            )
        }
        put("VERSION", BaseParserTables.FUNCTIONS.getValue("CURRENT_VERSION"))
        put("WEEK") { a ->
            Week(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))), "mode" to seqGet(a, 1)))
        }
        put("WEEKOFYEAR") { a -> WeekOfYear(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
        put("YEAR") { a -> Year(args("this" to TsOrDsToDate(args("this" to seqGet(a, 0))))) }
    }

    // sqlglot: MySQLParser.FUNCTION_PARSERS
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "GROUP_CONCAT" to { p -> (p as MysqlParser).parseGroupConcat() },
            // https://dev.mysql.com/doc/refman/5.7/en/miscellaneous-functions.html#function_values
            "VALUES" to { p ->
                p.expression(
                    Anonymous(args("this" to "VALUES", "expressions" to listOf(p.parseIdVar())))
                )
            },
            "JSON_VALUE" to { p -> (p as MysqlParser).parseJsonValue() },
            "SUBSTR" to { p -> p.parseSubstring() },
        )

    // sqlglot: MySQLParser.STATEMENT_PARSERS
    val STATEMENT_PARSERS: Map<TokenType, (Parser) -> Expression> =
        BaseParserTables.STATEMENT_PARSERS + mapOf<TokenType, (Parser) -> Expression>(
            TokenType.SHOW to { p -> (p as MysqlParser).parseShow() },
        )

    // sqlglot: parsers.mysql._show_parser
    private fun showParser(
        thisName: String,
        target: kotlin.Any = false,
        full: Boolean? = null,
        global_: Boolean? = null,
    ): (MysqlParser) -> Expression =
        { p -> p.parseShowMysql(thisName, target = target, full = full, global_ = global_) }

    // sqlglot: MySQLParser.SHOW_PARSERS
    val SHOW_PARSERS: Map<String, (MysqlParser) -> Expression> = mapOf(
        "BINARY LOGS" to showParser("BINARY LOGS"),
        "MASTER LOGS" to showParser("BINARY LOGS"),
        "BINLOG EVENTS" to showParser("BINLOG EVENTS"),
        "CHARACTER SET" to showParser("CHARACTER SET"),
        "CHARSET" to showParser("CHARACTER SET"),
        "COLLATION" to showParser("COLLATION"),
        "FULL COLUMNS" to showParser("COLUMNS", target = "FROM", full = true),
        "COLUMNS" to showParser("COLUMNS", target = "FROM"),
        "CREATE DATABASE" to showParser("CREATE DATABASE", target = true),
        "CREATE EVENT" to showParser("CREATE EVENT", target = true),
        "CREATE FUNCTION" to showParser("CREATE FUNCTION", target = true),
        "CREATE PROCEDURE" to showParser("CREATE PROCEDURE", target = true),
        "CREATE TABLE" to showParser("CREATE TABLE", target = true),
        "CREATE TRIGGER" to showParser("CREATE TRIGGER", target = true),
        "CREATE VIEW" to showParser("CREATE VIEW", target = true),
        "DATABASES" to showParser("DATABASES"),
        "SCHEMAS" to showParser("DATABASES"),
        "ENGINE" to showParser("ENGINE", target = true),
        "STORAGE ENGINES" to showParser("ENGINES"),
        "ENGINES" to showParser("ENGINES"),
        "ERRORS" to showParser("ERRORS"),
        "EVENTS" to showParser("EVENTS"),
        "FUNCTION CODE" to showParser("FUNCTION CODE", target = true),
        "FUNCTION STATUS" to showParser("FUNCTION STATUS"),
        "GRANTS" to showParser("GRANTS", target = "FOR"),
        "INDEX" to showParser("INDEX", target = "FROM"),
        "MASTER STATUS" to showParser("MASTER STATUS"),
        "OPEN TABLES" to showParser("OPEN TABLES"),
        "PLUGINS" to showParser("PLUGINS"),
        "PROCEDURE CODE" to showParser("PROCEDURE CODE", target = true),
        "PROCEDURE STATUS" to showParser("PROCEDURE STATUS"),
        "PRIVILEGES" to showParser("PRIVILEGES"),
        "FULL PROCESSLIST" to showParser("PROCESSLIST", full = true),
        "PROCESSLIST" to showParser("PROCESSLIST"),
        "PROFILE" to showParser("PROFILE"),
        "PROFILES" to showParser("PROFILES"),
        "RELAYLOG EVENTS" to showParser("RELAYLOG EVENTS"),
        "REPLICAS" to showParser("REPLICAS"),
        "SLAVE HOSTS" to showParser("REPLICAS"),
        "REPLICA STATUS" to showParser("REPLICA STATUS"),
        "SLAVE STATUS" to showParser("REPLICA STATUS"),
        "GLOBAL STATUS" to showParser("STATUS", global_ = true),
        "SESSION STATUS" to showParser("STATUS"),
        "STATUS" to showParser("STATUS"),
        "TABLE STATUS" to showParser("TABLE STATUS"),
        "FULL TABLES" to showParser("TABLES", full = true),
        "TABLES" to showParser("TABLES"),
        "TRIGGERS" to showParser("TRIGGERS"),
        "GLOBAL VARIABLES" to showParser("VARIABLES", global_ = true),
        "SESSION VARIABLES" to showParser("VARIABLES"),
        "VARIABLES" to showParser("VARIABLES"),
        "WARNINGS" to showParser("WARNINGS"),
    )

    // sqlglot: MySQLParser.PROPERTY_PARSERS
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> =
        BaseParserTables.PROPERTY_PARSERS + mapOf<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?>(
            "LOCK" to { p, _ -> p.parsePropertyAssignment({ a -> LockProperty(a) }) },
            "PARTITION BY" to { p, _ -> (p as MysqlParser).parsePartitionProperty() },
        )

    // sqlglot: MySQLParser.SET_PARSERS (extra entries over the base map)
    val SET_PARSERS_EXTRA: Map<String, (Parser) -> Expression?> = mapOf(
        "PERSIST" to { p -> p.parseSetItemAssignment("PERSIST") },
        "PERSIST_ONLY" to { p -> p.parseSetItemAssignment("PERSIST_ONLY") },
        "CHARACTER SET" to { p -> (p as MysqlParser).parseSetItemCharset("CHARACTER SET") },
        "CHARSET" to { p -> (p as MysqlParser).parseSetItemCharset("CHARACTER SET") },
        "NAMES" to { p -> (p as MysqlParser).parseSetItemNames() },
    )

    // sqlglot: MySQLParser.CONSTRAINT_PARSERS
    val CONSTRAINT_PARSERS: Map<String, (Parser) -> Expression?> =
        BaseParserTables.CONSTRAINT_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "FULLTEXT" to { p -> (p as MysqlParser).parseIndexConstraint(kind = "FULLTEXT") },
            "INDEX" to { p -> (p as MysqlParser).parseIndexConstraint() },
            "KEY" to { p -> (p as MysqlParser).parseIndexConstraint() },
            "SPATIAL" to { p -> (p as MysqlParser).parseIndexConstraint(kind = "SPATIAL") },
            "ZEROFILL" to { p -> p.expression(dev.brikk.house.sql.ast.ZeroFillColumnConstraint()) },
            "INVISIBLE" to { p -> p.expression(InvisibleColumnConstraint()) },
        )

    // sqlglot: MySQLParser.ALTER_PARSERS (extra entries over the base map)
    val ALTER_PARSERS_EXTRA: Map<String, (Parser) -> kotlin.Any?> = mapOf(
        "CHANGE" to { p -> (p as MysqlParser).parseAlterTableModify(rename = true) },
        "MODIFY" to { p -> (p as MysqlParser).parseAlterTableModify() },
        "AUTO_INCREMENT" to { p -> p.parsePropertyAssignment({ a -> AutoIncrementProperty(a) }) },
    )

    // sqlglot: MySQLParser.ALTER_ALTER_PARSERS (extra entries over the base map)
    val ALTER_ALTER_PARSERS_EXTRA: Map<String, (Parser) -> Expression?> = mapOf(
        "INDEX" to { p -> (p as MysqlParser).parseAlterTableAlterIndex() },
    )

    // sqlglot: MySQLParser.SCHEMA_UNNAMED_CONSTRAINTS
    val SCHEMA_UNNAMED_CONSTRAINTS: Set<String> =
        BaseParserTables.SCHEMA_UNNAMED_CONSTRAINTS + setOf("FULLTEXT", "INDEX", "KEY", "SPATIAL")

    // sqlglot: MySQLParser.PROFILE_TYPES
    val PROFILE_TYPES: Map<String, List<List<String>>> = mapOf(
        "ALL" to emptyList(),
        "CPU" to emptyList(),
        "IPC" to emptyList(),
        "MEMORY" to emptyList(),
        "SOURCE" to emptyList(),
        "SWAPS" to emptyList(),
        "BLOCK" to listOf(listOf("IO")),
        "CONTEXT" to listOf(listOf("SWITCHES")),
        "PAGE" to listOf(listOf("FAULTS")),
    )

    // sqlglot: MySQLParser.TYPE_TOKENS
    val TYPE_TOKENS: Set<TokenType> = BaseParserTables.TYPE_TOKENS + setOf(TokenType.SET)

    // sqlglot: MySQLParser.ENUM_TYPE_TOKENS
    val ENUM_TYPE_TOKENS: Set<TokenType> = BaseParserTables.ENUM_TYPE_TOKENS + setOf(TokenType.SET)

    // sqlglot: MySQLParser.OPERATION_MODIFIERS
    val OPERATION_MODIFIERS: Set<String> = setOf(
        "HIGH_PRIORITY",
        "STRAIGHT_JOIN",
        "SQL_SMALL_RESULT",
        "SQL_BIG_RESULT",
        "SQL_BUFFER_RESULT",
        "SQL_NO_CACHE",
        "SQL_CALC_FOUND_ROWS",
    )

    // sqlglot: MySQL.VALID_INTERVAL_UNITS
    val VALID_INTERVAL_UNITS: Set<String> = BaseParserTables.VALID_INTERVAL_UNITS + setOf(
        "SECOND_MICROSECOND",
        "MINUTE_MICROSECOND",
        "MINUTE_SECOND",
        "HOUR_MICROSECOND",
        "HOUR_SECOND",
        "HOUR_MINUTE",
        "DAY_MICROSECOND",
        "DAY_SECOND",
        "DAY_MINUTE",
        "DAY_HOUR",
        "YEAR_MONTH",
    )
}
