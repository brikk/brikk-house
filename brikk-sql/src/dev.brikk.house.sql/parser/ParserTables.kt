package dev.brikk.house.sql.parser

import dev.brikk.house.sql.ast.All
import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.Any
import dev.brikk.house.sql.ast.Args
import dev.brikk.house.sql.ast.Avg
import dev.brikk.house.sql.ast.BitwiseAnd
import dev.brikk.house.sql.ast.BitwiseNot
import dev.brikk.house.sql.ast.BitwiseOr
import dev.brikk.house.sql.ast.BitwiseXor
import dev.brikk.house.sql.ast.Boolean
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.Collate
import dev.brikk.house.sql.ast.ConnectByRoot
import dev.brikk.house.sql.ast.Count
import dev.brikk.house.sql.ast.Div
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GT
import dev.brikk.house.sql.ast.GTE
import dev.brikk.house.sql.ast.ILike
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.IntDiv
import dev.brikk.house.sql.ast.LT
import dev.brikk.house.sql.ast.LTE
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Max
import dev.brikk.house.sql.ast.Min
import dev.brikk.house.sql.ast.Mod
import dev.brikk.house.sql.ast.Mul
import dev.brikk.house.sql.ast.NEQ
import dev.brikk.house.sql.ast.Neg
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.NullSafeEQ
import dev.brikk.house.sql.ast.Or
import dev.brikk.house.sql.ast.Placeholder
import dev.brikk.house.sql.ast.Pow
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.RowNumber
import dev.brikk.house.sql.ast.Semicolon
import dev.brikk.house.sql.ast.Sub
import dev.brikk.house.sql.ast.Sum
import dev.brikk.house.sql.ast.And
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.args

/**
 * Default (base dialect) parser tables. Port of the class-level tables of
 * reference/sqlglot/sqlglot/parser.py `Parser`. The tables are exposed to [Parser]
 * through open provider properties so that dialect subclasses can replace any of
 * them, mirroring Python's class-variable override mechanism.
 *
 * Only the SELECT-family subset is populated; omitted entries carry a
 * `not ported` note referencing the Python original.
 */

/** Factory building a node from Python-style kwargs (insertion order preserved). */
typealias NodeFactory = (Args) -> Expression

/** sqlglot: parser.binary_range_parser (negate handling lives in Parser._parse_range). */
internal fun binaryRangeParser(
    reverseArgs: kotlin.Boolean = false,
    factory: NodeFactory,
): (Parser, Expression?) -> Expression? =
    { parser, thisIn ->
        var this_ = thisIn
        var right = parser.parseBitwise()
        if (reverseArgs) {
            val tmp = this_
            this_ = right
            right = tmp
        }
        parser.parseEscape(
            parser.expression(factory(args("this" to this_, "expression" to right)))
        )
    }

/**
 * sqlglot: Func.from_arg_list — positional args zipped against arg_types keys;
 * for var-len functions the remaining args land in the last key as a list.
 */
internal fun fromArgList(
    argKeys: List<String>,
    isVarLenArgs: kotlin.Boolean,
    factory: NodeFactory,
): (List<Expression?>) -> Expression = { argsList ->
    val kwargs = LinkedHashMap<String, kotlin.Any?>()
    if (isVarLenArgs) {
        val nonVarKeys = argKeys.dropLast(1)
        for ((arg, key) in argsList.zip(nonVarKeys)) kwargs[key] = arg
        kwargs[argKeys.last()] = argsList.drop(nonVarKeys.size)
    } else {
        for ((arg, key) in argsList.zip(argKeys)) kwargs[key] = arg
    }
    applyTimeUnitCoercion(factory(kwargs))
}

// sqlglot: exp.TimeUnit.UNABBREVIATED_UNIT_NAME
private val UNABBREVIATED_UNIT_NAME: Map<String, String> = mapOf(
    "D" to "DAY", "H" to "HOUR", "M" to "MINUTE", "MS" to "MILLISECOND",
    "NS" to "NANOSECOND", "Q" to "QUARTER", "S" to "SECOND", "US" to "MICROSECOND",
    "W" to "WEEK", "Y" to "YEAR",
)

/**
 * sqlglot: exp.TimeUnit.__init__ — automatically converts the unit arg into a Var.
 * Python performs this in the node constructor; we apply it at the from_arg_list
 * construction site, which is the only parser path that passes raw unit columns.
 */
internal fun applyTimeUnitCoercion(expression: Expression): Expression {
    if (expression !is dev.brikk.house.sql.ast.TimeUnit) return expression

    val unit = expression.args["unit"]
    val isVarLike = unit != null &&
        (unit::class == dev.brikk.house.sql.ast.Column::class ||
            unit::class == Literal::class ||
            unit::class == Var::class)
    if (isVarLike) {
        val unitExpr = unit as Expression
        if (unitExpr is dev.brikk.house.sql.ast.Column) {
            val parts = listOf("catalog", "db", "table", "this")
                .count { unitExpr.args[it] is Expression }
            if (parts != 1) return expression
        }
        val name = (UNABBREVIATED_UNIT_NAME[unitExpr.name] ?: unitExpr.name).uppercase()
        expression.set("unit", Var(args("this" to name)))
    } else if (unit is dev.brikk.house.sql.ast.Week) {
        unit.set("this", Var(args("this" to unit.name.uppercase())))
    }
    return expression
}

object BaseParserTables {

    // sqlglot: Parser.FUNCTIONS — exp.FUNCTION_BY_NAME from_arg_list builders
    // (GeneratedFunctionRegistry.kt), overlaid with the hand-ported custom builders
    // (ParserFunctionBuilders.kt), exactly mirroring the Python dict-merge order.
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> =
        GENERATED_FUNCTION_BY_NAME + customFunctionBuilders()

    // sqlglot: Parser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> = mapOf(
        TokenType.CURRENT_DATE to { dev.brikk.house.sql.ast.CurrentDate() },
        TokenType.CURRENT_DATETIME to { dev.brikk.house.sql.ast.CurrentDate() },
        TokenType.CURRENT_TIME to { dev.brikk.house.sql.ast.CurrentTime() },
        TokenType.CURRENT_TIMESTAMP to { dev.brikk.house.sql.ast.CurrentTimestamp() },
        TokenType.CURRENT_USER to { dev.brikk.house.sql.ast.CurrentUser() },
        TokenType.CURRENT_ROLE to { dev.brikk.house.sql.ast.CurrentRole() },
    )

    // sqlglot: Parser.STRUCT_TYPE_TOKENS
    val STRUCT_TYPE_TOKENS: Set<TokenType> = setOf(
        TokenType.NESTED, TokenType.OBJECT, TokenType.STRUCT, TokenType.UNION,
    )

    // sqlglot: Parser.NESTED_TYPE_TOKENS
    val NESTED_TYPE_TOKENS: Set<TokenType> = setOf(
        TokenType.ARRAY, TokenType.LIST, TokenType.LOWCARDINALITY, TokenType.MAP,
        TokenType.NULLABLE, TokenType.RANGE,
    ) + STRUCT_TYPE_TOKENS

    // sqlglot: Parser.ENUM_TYPE_TOKENS
    val ENUM_TYPE_TOKENS: Set<TokenType> = setOf(
        TokenType.DYNAMIC, TokenType.ENUM, TokenType.ENUM8, TokenType.ENUM16,
    )

    // sqlglot: Parser.AGGREGATE_TYPE_TOKENS
    val AGGREGATE_TYPE_TOKENS: Set<TokenType> = setOf(
        TokenType.AGGREGATEFUNCTION, TokenType.SIMPLEAGGREGATEFUNCTION,
    )

    // sqlglot: Parser.TYPE_TOKENS
    val TYPE_TOKENS: Set<TokenType> = setOf(
        TokenType.BIT, TokenType.BOOLEAN, TokenType.TINYINT, TokenType.UTINYINT,
        TokenType.SMALLINT, TokenType.USMALLINT, TokenType.INT, TokenType.UINT,
        TokenType.BIGINT, TokenType.UBIGINT, TokenType.BIGNUM, TokenType.INT128,
        TokenType.UINT128, TokenType.INT256, TokenType.UINT256, TokenType.MEDIUMINT,
        TokenType.UMEDIUMINT, TokenType.FIXEDSTRING, TokenType.FLOAT, TokenType.DOUBLE,
        TokenType.UDOUBLE, TokenType.CHAR, TokenType.NCHAR, TokenType.VARCHAR,
        TokenType.NVARCHAR, TokenType.BPCHAR, TokenType.TEXT, TokenType.MEDIUMTEXT,
        TokenType.LONGTEXT, TokenType.BLOB, TokenType.MEDIUMBLOB, TokenType.LONGBLOB,
        TokenType.BINARY, TokenType.VARBINARY, TokenType.JSON, TokenType.JSONB,
        TokenType.INTERVAL, TokenType.TINYBLOB, TokenType.TINYTEXT, TokenType.TIME,
        TokenType.TIMETZ, TokenType.TIME_NS, TokenType.TIMESTAMP, TokenType.TIMESTAMP_S,
        TokenType.TIMESTAMP_MS, TokenType.TIMESTAMP_NS, TokenType.TIMESTAMPTZ,
        TokenType.TIMESTAMPLTZ, TokenType.TIMESTAMPNTZ, TokenType.DATETIME,
        TokenType.DATETIME2, TokenType.DATETIME64, TokenType.SMALLDATETIME,
        TokenType.DATE, TokenType.DATE32, TokenType.INT4RANGE, TokenType.INT4MULTIRANGE,
        TokenType.INT8RANGE, TokenType.INT8MULTIRANGE, TokenType.NUMRANGE,
        TokenType.NUMMULTIRANGE, TokenType.TSRANGE, TokenType.TSMULTIRANGE,
        TokenType.TSTZRANGE, TokenType.TSTZMULTIRANGE, TokenType.DATERANGE,
        TokenType.DATEMULTIRANGE, TokenType.DECIMAL, TokenType.DECIMAL32,
        TokenType.DECIMAL64, TokenType.DECIMAL128, TokenType.DECIMAL256,
        TokenType.DECFLOAT, TokenType.UDECIMAL, TokenType.BIGDECIMAL, TokenType.UUID,
        TokenType.GEOGRAPHY, TokenType.GEOGRAPHYPOINT, TokenType.GEOMETRY,
        TokenType.POINT, TokenType.RING, TokenType.LINESTRING, TokenType.MULTILINESTRING,
        TokenType.POLYGON, TokenType.MULTIPOLYGON, TokenType.HLLSKETCH, TokenType.HSTORE,
        TokenType.PSEUDO_TYPE, TokenType.SUPER, TokenType.SERIAL, TokenType.SMALLSERIAL,
        TokenType.BIGSERIAL, TokenType.XML, TokenType.YEAR, TokenType.USERDEFINED,
        TokenType.MONEY, TokenType.SMALLMONEY, TokenType.ROWVERSION, TokenType.IMAGE,
        TokenType.VARIANT, TokenType.VECTOR, TokenType.VOID, TokenType.OBJECT,
        TokenType.OBJECT_IDENTIFIER, TokenType.INET, TokenType.IPADDRESS,
        TokenType.IPPREFIX, TokenType.IPV4, TokenType.IPV6, TokenType.UNKNOWN,
        TokenType.NOTHING, TokenType.NULL, TokenType.NAME, TokenType.TDIGEST,
        TokenType.DYNAMIC,
    ) + ENUM_TYPE_TOKENS + NESTED_TYPE_TOKENS + AGGREGATE_TYPE_TOKENS

    // sqlglot: Parser.SIGNED_TO_UNSIGNED_TYPE_TOKEN
    val SIGNED_TO_UNSIGNED_TYPE_TOKEN: Map<TokenType, TokenType> = mapOf(
        TokenType.BIGINT to TokenType.UBIGINT,
        TokenType.INT to TokenType.UINT,
        TokenType.MEDIUMINT to TokenType.UMEDIUMINT,
        TokenType.SMALLINT to TokenType.USMALLINT,
        TokenType.TINYINT to TokenType.UTINYINT,
        TokenType.DECIMAL to TokenType.UDECIMAL,
        TokenType.DOUBLE to TokenType.UDOUBLE,
    )

    // sqlglot: Parser.SUBQUERY_PREDICATES
    val SUBQUERY_PREDICATES: Map<TokenType, NodeFactory> = mapOf(
        TokenType.ANY to { Any(it) },
        TokenType.ALL to { All(it) },
        TokenType.EXISTS to { dev.brikk.house.sql.ast.Exists(it) },
        TokenType.SOME to { Any(it) },
    )

    // sqlglot: Parser.SUBQUERY_TOKENS
    val SUBQUERY_TOKENS: Set<TokenType> = setOf(TokenType.SELECT, TokenType.WITH, TokenType.FROM)

    // sqlglot: Parser.RESERVED_TOKENS (base tokenizer single tokens + SELECT - IDENTIFIER)
    val RESERVED_TOKENS: Set<TokenType> =
        (BaseTokenizerTables.SINGLE_TOKENS.values.toSet() + TokenType.SELECT) - TokenType.IDENTIFIER

    // sqlglot: Parser.TEXT_MATCH_EXCLUDED_TOKENS
    val TEXT_MATCH_EXCLUDED_TOKENS: Set<TokenType> = setOf(
        TokenType.BIT_STRING, TokenType.BYTE_STRING, TokenType.HEREDOC_STRING,
        TokenType.HEX_STRING, TokenType.IDENTIFIER, TokenType.NATIONAL_STRING,
        TokenType.RAW_STRING, TokenType.STRING, TokenType.UNICODE_STRING,
    )

    // sqlglot: Parser.DB_CREATABLES (NAMESPACE/SEMANTIC_VIEW/SINK/SOURCE included; enum has them)
    val DB_CREATABLES: Set<TokenType> = setOf(
        TokenType.DATABASE, TokenType.DICTIONARY, TokenType.FILE_FORMAT, TokenType.MODEL,
        TokenType.NAMESPACE, TokenType.SCHEMA, TokenType.SEMANTIC_VIEW, TokenType.SEQUENCE,
        TokenType.SINK, TokenType.SOURCE, TokenType.STAGE, TokenType.STORAGE_INTEGRATION,
        TokenType.STREAMLIT, TokenType.TABLE, TokenType.TAG, TokenType.VIEW,
        TokenType.WAREHOUSE,
    )

    // sqlglot: Parser.CREATABLES
    val CREATABLES: Set<TokenType> = setOf(
        TokenType.COLUMN, TokenType.CONSTRAINT, TokenType.FOREIGN_KEY, TokenType.FUNCTION,
        TokenType.INDEX, TokenType.PROCEDURE, TokenType.TRIGGER, TokenType.TYPE,
    ) + DB_CREATABLES

    // sqlglot: Parser.ALTERABLES
    val ALTERABLES: Set<TokenType> = setOf(
        TokenType.INDEX, TokenType.TABLE, TokenType.VIEW, TokenType.SESSION,
    )

    // sqlglot: Parser.ID_VAR_TOKENS
    val ID_VAR_TOKENS: Set<TokenType> = (setOf(
        TokenType.ALL, TokenType.ANALYZE, TokenType.ATTACH, TokenType.VAR, TokenType.ANTI,
        TokenType.APPLY, TokenType.ASC, TokenType.ASOF, TokenType.AUTO_INCREMENT,
        TokenType.BEGIN, TokenType.BPCHAR, TokenType.CACHE, TokenType.CASE,
        TokenType.COLLATE, TokenType.COMMAND, TokenType.COMMENT, TokenType.COMMIT,
        TokenType.CONSTRAINT, TokenType.COPY, TokenType.CUBE, TokenType.CURRENT_SCHEMA,
        TokenType.DEFAULT, TokenType.DELETE, TokenType.DESC, TokenType.DESCRIBE,
        TokenType.DETACH, TokenType.DICTIONARY, TokenType.DIV, TokenType.END,
        TokenType.EXECUTE, TokenType.EXPORT, TokenType.ESCAPE, TokenType.FALSE,
        TokenType.FIRST, TokenType.FILE, TokenType.FILTER, TokenType.FINAL,
        TokenType.FORMAT, TokenType.FULL, TokenType.GET, TokenType.IDENTIFIER,
        TokenType.INOUT, TokenType.IS, TokenType.ISNULL, TokenType.INTERVAL,
        TokenType.KEEP, TokenType.KILL, TokenType.LEFT, TokenType.LIMIT, TokenType.LOAD,
        TokenType.LOCK, TokenType.MATCH, TokenType.MERGE, TokenType.NATURAL,
        TokenType.NEXT, TokenType.OFFSET, TokenType.OPERATOR, TokenType.ORDINALITY,
        TokenType.OVER, TokenType.OVERLAPS, TokenType.OVERWRITE, TokenType.PARTITION,
        TokenType.PERCENT, TokenType.PIVOT, TokenType.PROJECTION, TokenType.PRAGMA,
        TokenType.PUT, TokenType.RANGE, TokenType.RECURSIVE, TokenType.REFERENCES,
        TokenType.REFRESH, TokenType.RENAME, TokenType.REPLACE, TokenType.RIGHT,
        TokenType.ROLLUP, TokenType.ROW, TokenType.ROWS, TokenType.SEMI, TokenType.SET,
        TokenType.SETTINGS, TokenType.SHOW, TokenType.STREAM, TokenType.STREAMLIT,
        TokenType.TEMPORARY, TokenType.TOP, TokenType.TRUE, TokenType.TRUNCATE,
        TokenType.UNIQUE, TokenType.UNNEST, TokenType.UNPIVOT, TokenType.UPDATE,
        TokenType.USE, TokenType.VOLATILE, TokenType.WINDOW, TokenType.CURRENT_CATALOG,
        TokenType.LOCALTIME, TokenType.LOCALTIMESTAMP, TokenType.SESSION_USER,
        TokenType.STRAIGHT_JOIN,
    ) + ALTERABLES + CREATABLES + SUBQUERY_PREDICATES.keys + TYPE_TOKENS +
        NO_PAREN_FUNCTIONS.keys) - TokenType.UNION

    // sqlglot: Parser.TABLE_ALIAS_TOKENS
    val TABLE_ALIAS_TOKENS: Set<TokenType> = ID_VAR_TOKENS - setOf(
        TokenType.ANTI, TokenType.ASOF, TokenType.FULL, TokenType.LEFT, TokenType.LOCK,
        TokenType.NATURAL, TokenType.RIGHT, TokenType.SEMI, TokenType.WINDOW,
    )

    // sqlglot: Parser.ALIAS_TOKENS
    val ALIAS_TOKENS: Set<TokenType> = ID_VAR_TOKENS

    // sqlglot: Parser.COLON_PLACEHOLDER_TOKENS
    val COLON_PLACEHOLDER_TOKENS: Set<TokenType> = ID_VAR_TOKENS

    // sqlglot: Parser.IDENTIFIER_TOKENS
    val IDENTIFIER_TOKENS: Set<TokenType> = setOf(TokenType.VAR, TokenType.IDENTIFIER)

    // sqlglot: Parser.BRACKETS
    val BRACKETS: Set<TokenType> = setOf(TokenType.L_BRACKET, TokenType.L_BRACE)

    // sqlglot: Parser.ARRAY_CONSTRUCTORS
    val ARRAY_CONSTRUCTORS: Map<String, NodeFactory> = mapOf(
        "ARRAY" to { a: Args -> dev.brikk.house.sql.ast.Array(a) },
        "LIST" to { a: Args -> dev.brikk.house.sql.ast.List(a) },
    )

    // sqlglot: Dialect.VALID_INTERVAL_UNITS (base: DATE_PART_MAPPING keys+values)
    val VALID_INTERVAL_UNITS: Set<String> = setOf(
        "C", "CENT", "CENTS", "CENTURIES", "CENTURY", "D", "DAY", "DAY OF WEEK", "DAY OF YEAR",
        "DAYOFMONTH", "DAYOFWEEK", "DAYOFWEEKISO", "DAYOFWEEK_ISO", "DAYOFYEAR", "DAYS", "DD",
        "DEC", "DECADE", "DECADES", "DECS", "DOW", "DOW_ISO", "DOY", "DW", "DW_ISO", "DY",
        "EPOCH", "EPOCH_MICROSECOND", "EPOCH_MICROSECONDS", "EPOCH_MILLISECOND",
        "EPOCH_MILLISECONDS", "EPOCH_NANOSECOND", "EPOCH_NANOSECONDS", "EPOCH_SECOND",
        "EPOCH_SECONDS", "H", "HH", "HOUR", "HOURS", "HR", "HRS", "M", "MI", "MICROSEC",
        "MICROSECOND", "MICROSECONDS", "MICROSECS", "MIL", "MILLENIA", "MILLENNIUM",
        "MILLISEC", "MILLISECON", "MILLISECOND", "MILLISECONDS", "MILLISECS", "MILS", "MIN",
        "MINS", "MINUTE", "MINUTES", "MM", "MON", "MONS", "MONTH", "MONTHS", "MS", "MSEC",
        "MSECOND", "MSECONDS", "MSECS", "NANOSEC", "NANOSECOND", "NANOSECS", "NS", "NSEC",
        "NSECOND", "NSECONDS", "Q", "QTR", "QTRS", "QUARTER", "QUARTERS", "S", "SEC", "SECOND",
        "SECONDS", "SECS", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TZH", "TZM", "US", "USEC",
        "USECOND", "USECONDS", "USECS", "W", "WEEK", "WEEKDAY", "WEEKDAY_ISO", "WEEKISO",
        "WEEKOFYEAR", "WEEKOFYEARISO", "WEEKOFYEAR_ISO", "WEEK_ISO", "WK", "WOY", "WY", "Y",
        "YEAR", "YEARS", "YR", "YRS", "YY", "YYY", "YYYY",
    )

    // sqlglot: Parser.COLUMN_POSTFIX_TOKENS
    val COLUMN_POSTFIX_TOKENS: Set<TokenType> = setOf(
        TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE, TokenType.COLON,
        TokenType.JOIN_MARKER,
    )

    // sqlglot: Parser.TABLE_POSTFIX_TOKENS
    val TABLE_POSTFIX_TOKENS: Set<TokenType> = setOf(
        TokenType.L_PAREN, TokenType.L_BRACKET, TokenType.L_BRACE, TokenType.PIVOT,
        TokenType.UNPIVOT, TokenType.TABLE_SAMPLE,
    )

    // sqlglot: Parser.FUNC_TOKENS
    val FUNC_TOKENS: Set<TokenType> = setOf(
        TokenType.COLLATE, TokenType.COMMAND, TokenType.CURRENT_DATE,
        TokenType.CURRENT_DATETIME, TokenType.CURRENT_SCHEMA, TokenType.CURRENT_TIMESTAMP,
        TokenType.CURRENT_TIME, TokenType.CURRENT_USER, TokenType.CURRENT_CATALOG,
        TokenType.FILTER, TokenType.FIRST, TokenType.FORMAT, TokenType.GET,
        TokenType.GLOB, TokenType.IDENTIFIER, TokenType.INDEX, TokenType.ISNULL,
        TokenType.ILIKE, TokenType.INSERT, TokenType.LIKE, TokenType.LOCALTIME,
        TokenType.LOCALTIMESTAMP, TokenType.MERGE, TokenType.NEXT, TokenType.OFFSET,
        TokenType.PRIMARY_KEY, TokenType.RANGE, TokenType.REPLACE, TokenType.RLIKE,
        TokenType.ROW, TokenType.SESSION_USER, TokenType.UNNEST, TokenType.VAR,
        TokenType.LEFT, TokenType.RIGHT, TokenType.SEQUENCE, TokenType.DATE,
        TokenType.DATETIME, TokenType.TABLE, TokenType.TIMESTAMP, TokenType.TIMESTAMPTZ,
        TokenType.TRUNCATE, TokenType.UTC_DATE, TokenType.UTC_TIME,
        TokenType.UTC_TIMESTAMP, TokenType.WINDOW, TokenType.XOR,
    ) + TYPE_TOKENS + SUBQUERY_PREDICATES.keys

    // sqlglot: Parser.CONJUNCTION
    val CONJUNCTION: Map<TokenType, NodeFactory> = mapOf(
        TokenType.AND to { And(it) },
    )

    // sqlglot: Parser.ASSIGNMENT
    val ASSIGNMENT: Map<TokenType, NodeFactory> = mapOf(
        TokenType.COLON_EQ to { PropertyEQ(it) },
    )

    // sqlglot: Parser.DISJUNCTION
    val DISJUNCTION: Map<TokenType, NodeFactory> = mapOf(
        TokenType.OR to { Or(it) },
    )

    // sqlglot: Parser.EQUALITY
    val EQUALITY: Map<TokenType, NodeFactory> = mapOf(
        TokenType.EQ to { EQ(it) },
        TokenType.NEQ to { NEQ(it) },
        TokenType.NULLSAFE_EQ to { NullSafeEQ(it) },
    )

    // sqlglot: Parser.COMPARISON
    val COMPARISON: Map<TokenType, NodeFactory> = mapOf(
        TokenType.GT to { GT(it) },
        TokenType.GTE to { GTE(it) },
        TokenType.LT to { LT(it) },
        TokenType.LTE to { LTE(it) },
    )

    // sqlglot: Parser.BITWISE
    val BITWISE: Map<TokenType, NodeFactory> = mapOf(
        TokenType.AMP to { BitwiseAnd(it) },
        TokenType.CARET to { BitwiseXor(it) },
        TokenType.PIPE to { BitwiseOr(it) },
    )

    // sqlglot: Parser.TERM
    val TERM: Map<TokenType, NodeFactory> = mapOf(
        TokenType.DASH to { Sub(it) },
        TokenType.PLUS to { Add(it) },
        TokenType.MOD to { Mod(it) },
        TokenType.COLLATE to { Collate(it) },
    )

    // sqlglot: Parser.FACTOR (LR_ARROW/LLRR_ARROW -> Distance/DistanceNd not ported)
    val FACTOR: Map<TokenType, NodeFactory> = mapOf(
        TokenType.DIV to { IntDiv(it) },
        TokenType.SLASH to { Div(it) },
        TokenType.STAR to { Mul(it) },
    )

    // sqlglot: Parser.EXPONENT
    val EXPONENT: Map<TokenType, NodeFactory> = emptyMap()

    // sqlglot: Parser.TIMES
    val TIMES: Set<TokenType> = setOf(TokenType.TIME, TokenType.TIMETZ)

    // sqlglot: Parser.TIMESTAMPS
    val TIMESTAMPS: Set<TokenType> = setOf(
        TokenType.TIMESTAMP, TokenType.TIMESTAMPNTZ, TokenType.TIMESTAMPTZ,
        TokenType.TIMESTAMPLTZ,
    ) + TIMES

    // sqlglot: Parser.SET_OPERATIONS
    val SET_OPERATIONS: Set<TokenType> = setOf(
        TokenType.UNION, TokenType.INTERSECT, TokenType.EXCEPT,
    )

    // sqlglot: Parser.JOIN_METHODS
    val JOIN_METHODS: Set<TokenType> = setOf(
        TokenType.ASOF, TokenType.NATURAL, TokenType.POSITIONAL,
    )

    // sqlglot: Parser.JOIN_SIDES
    val JOIN_SIDES: Set<TokenType> = setOf(TokenType.LEFT, TokenType.RIGHT, TokenType.FULL)

    // sqlglot: Parser.JOIN_KINDS
    val JOIN_KINDS: Set<TokenType> = setOf(
        TokenType.ANTI, TokenType.CROSS, TokenType.INNER, TokenType.OUTER, TokenType.SEMI,
        TokenType.STRAIGHT_JOIN,
    )

    // sqlglot: Parser.JOIN_HINTS
    val JOIN_HINTS: Set<String> = emptySet()

    // sqlglot: Parser.TABLE_TERMINATORS
    val TABLE_TERMINATORS: Set<TokenType> = setOf(
        TokenType.COMMA, TokenType.GROUP_BY, TokenType.HAVING, TokenType.JOIN,
        TokenType.LIMIT, TokenType.ON, TokenType.ORDER_BY, TokenType.R_PAREN,
        TokenType.SEMICOLON, TokenType.SENTINEL, TokenType.WHERE,
    ) + SET_OPERATIONS + JOIN_KINDS + JOIN_METHODS + JOIN_SIDES

    // sqlglot: Parser.LAMBDAS — Lambda/Kwarg nodes not in the catalog yet; empty map keeps
    // Parser.parseLambda's structure faithful (the branches simply never fire).
    val LAMBDAS: Map<TokenType, (Parser, List<Expression?>) -> Expression?> = mapOf(
        TokenType.ARROW to { parser, expressions ->
            parser.expression(
                dev.brikk.house.sql.ast.Lambda(
                    args(
                        "this" to parser.replaceLambda(parser.parseDisjunction(), expressions),
                        "expressions" to expressions,
                    )
                )
            )
        },
        TokenType.FARROW to { parser, expressions ->
            parser.expression(
                dev.brikk.house.sql.ast.Kwarg(
                    args(
                        "this" to Var(args("this" to (expressions.getOrNull(0)?.name ?: ""))),
                        "expression" to parser.parseDisjunction(),
                    )
                )
            )
        },
    )

    // sqlglot: Parser.LAMBDA_ARG_TERMINATORS
    val LAMBDA_ARG_TERMINATORS: Set<TokenType> = setOf(TokenType.COMMA, TokenType.R_PAREN)

    // sqlglot: Parser.COLUMN_OPERATORS (DOTCOLON -> JSONCast not ported)
    val COLUMN_OPERATORS: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?> =
        mapOf(
            TokenType.DOT to null,
            TokenType.DCOLON to { parser, this_, to ->
                parser.buildCast(strict = parser.strictCast, this_ = this_, to = to)
            },
            TokenType.ARROW to { parser, this_, path ->
                parser.expression(
                    dev.brikk.house.sql.ast.JSONExtract(
                        args(
                            "this" to this_,
                            "expression" to parser.toJsonPath(path),
                            "only_json_types" to parser.jsonArrowsRequireJsonType,
                        )
                    )
                )
            },
            TokenType.DARROW to { parser, this_, path ->
                parser.expression(
                    dev.brikk.house.sql.ast.JSONExtractScalar(
                        args(
                            "this" to this_,
                            "expression" to parser.toJsonPath(path),
                            "only_json_types" to parser.jsonArrowsRequireJsonType,
                            "scalar_only" to parser.jsonExtractScalarScalarOnly,
                        )
                    )
                )
            },
            TokenType.HASH_ARROW to { parser, this_, path ->
                parser.expression(
                    dev.brikk.house.sql.ast.JSONBExtract(args("this" to this_, "expression" to path))
                )
            },
            TokenType.DHASH_ARROW to { parser, this_, path ->
                parser.expression(
                    dev.brikk.house.sql.ast.JSONBExtractScalar(args("this" to this_, "expression" to path))
                )
            },
            TokenType.PLACEHOLDER to { parser, this_, key ->
                parser.expression(
                    dev.brikk.house.sql.ast.JSONBContains(args("this" to this_, "expression" to key))
                )
            },
        )

    // sqlglot: Parser.CAST_COLUMN_OPERATORS (DOTCOLON -> JSONCast not ported)
    val CAST_COLUMN_OPERATORS: Set<TokenType> = setOf(TokenType.DCOLON)

    // sqlglot: Parser.STATEMENT_PARSERS — only SEMICOLON from the DDL/DML table is in
    // scope for the SELECT-family subset; the other statements arrive with their nodes.
    val STATEMENT_PARSERS: Map<TokenType, (Parser) -> Expression> = mapOf(
        TokenType.ALTER to { p -> p.parseAlter() },
        TokenType.ANALYZE to { p -> p.parseAnalyze() },
        TokenType.BEGIN to { p -> p.parseTransaction() },
        TokenType.CACHE to { p -> p.parseCache() },
        TokenType.COMMENT to { p -> p.parseCommentStatement() },
        TokenType.COMMIT to { p -> p.parseCommitOrRollback() },
        // sqlglot: TokenType.COPY -> Parser._parse_copy — not ported (no corpus coverage).
        TokenType.COPY to { p -> p.parseAsCommandGate("COPY statements are not supported yet") },
        TokenType.CREATE to { p -> p.parseCreate() },
        TokenType.DELETE to { p -> p.parseDelete() },
        TokenType.DESC to { p -> p.parseDescribe() },
        TokenType.DESCRIBE to { p -> p.parseDescribe() },
        TokenType.DROP to { p -> p.parseDrop() },
        TokenType.GRANT to { p -> p.parseGrant() },
        TokenType.REVOKE to { p -> p.parseRevoke() },
        TokenType.INSERT to { p -> p.parseInsert() },
        TokenType.KILL to { p -> p.parseKill() },
        TokenType.LOAD to { p -> p.parseLoad() },
        TokenType.MERGE to { p -> p.parseMerge() },
        // sqlglot: TokenType.PIVOT/UNPIVOT -> _parse_simplified_pivot — not ported.
        TokenType.PIVOT to { p -> p.parseAsCommandGate("Simplified PIVOT statements are not supported yet") },
        TokenType.PRAGMA to { p ->
            p.expression(dev.brikk.house.sql.ast.Pragma(args("this" to p.parseExpression())))
        },
        TokenType.REFRESH to { p -> p.parseRefresh() },
        TokenType.ROLLBACK to { p -> p.parseCommitOrRollback() },
        TokenType.SET to { p -> p.parseSet() },
        TokenType.TRUNCATE to { p -> p.parseTruncateTable() ?: p.parseAsCommandGate("Unparseable TRUNCATE") },
        TokenType.UNCACHE to { p -> p.parseUncache() },
        TokenType.UNPIVOT to { p -> p.parseAsCommandGate("Simplified UNPIVOT statements are not supported yet") },
        TokenType.UPDATE to { p -> p.parseUpdate() },
        TokenType.USE to { p -> p.parseUse() },
        TokenType.SEMICOLON to { _ -> Semicolon() },
    )

    // sqlglot: Parser.UNARY_PARSERS (PIPE_SLASH/DPIPE_SLASH -> Sqrt/Cbrt not ported)
    val UNARY_PARSERS: Map<TokenType, (Parser) -> Expression?> = mapOf(
        // sqlglot: Unary + is handled as a no-op
        TokenType.PLUS to { parser -> parser.parseUnary() },
        TokenType.NOT to { parser ->
            parser.expression(Not(args("this" to parser.parseEquality())))
        },
        TokenType.TILDE to { parser ->
            parser.expression(BitwiseNot(args("this" to parser.parseUnary())))
        },
        TokenType.DASH to { parser ->
            parser.expression(Neg(args("this" to parser.parseUnary())))
        },
    )

    // sqlglot: Parser.STRING_PARSERS (base: BYTE_STRING_IS_BYTES_TYPE=false,
    // HEX_STRING_IS_INTEGER_TYPE=false -> the flag args are omitted)
    val STRING_PARSERS: Map<TokenType, (Parser, Token) -> Expression?> = mapOf(
        TokenType.HEREDOC_STRING to { parser, token ->
            parser.expression(dev.brikk.house.sql.ast.RawString(args("this" to token.text)), token)
        },
        TokenType.NATIONAL_STRING to { parser, token ->
            parser.expression(dev.brikk.house.sql.ast.National(args("this" to token.text)), token)
        },
        TokenType.RAW_STRING to { parser, token ->
            parser.expression(dev.brikk.house.sql.ast.RawString(args("this" to token.text)), token)
        },
        TokenType.STRING to { parser, token ->
            parser.expression(Literal(args("this" to token.text, "is_string" to true)), token)
        },
        TokenType.UNICODE_STRING to { parser, token ->
            parser.expression(
                dev.brikk.house.sql.ast.UnicodeString(
                    args(
                        "this" to token.text,
                        "escape" to (if (parser.matchTextSeq("UESCAPE")) parser.parseString() else false),
                    )
                ),
                token,
            )
        },
    )

    // sqlglot: Parser.NUMERIC_PARSERS
    val NUMERIC_PARSERS: Map<TokenType, (Parser, Token) -> Expression?> = mapOf(
        TokenType.BIT_STRING to { parser, token ->
            parser.expression(dev.brikk.house.sql.ast.BitString(args("this" to token.text)), token)
        },
        TokenType.BYTE_STRING to { parser, token ->
            parser.expression(dev.brikk.house.sql.ast.ByteString(args("this" to token.text)), token)
        },
        TokenType.HEX_STRING to { parser, token ->
            parser.expression(dev.brikk.house.sql.ast.HexString(args("this" to token.text)), token)
        },
        TokenType.NUMBER to { parser, token ->
            parser.expression(Literal(args("this" to token.text, "is_string" to false)), token)
        },
    )

    // sqlglot: Parser.PRIMARY_PARSERS
    val PRIMARY_PARSERS: Map<TokenType, (Parser, Token) -> Expression?> =
        STRING_PARSERS + NUMERIC_PARSERS + mapOf(
            TokenType.INTRODUCER to { parser, token -> parser.parseIntroducer(token) },
            TokenType.SESSION_PARAMETER to { parser, _ -> parser.parseSessionParameter() },
            TokenType.NULL to { parser, _ -> parser.expression(Null()) },
            TokenType.TRUE to { parser, _ ->
                parser.expression(Boolean(args("this" to true)))
            },
            TokenType.FALSE to { parser, _ ->
                parser.expression(Boolean(args("this" to false)))
            },
            TokenType.STAR to { parser, _ -> parser.parseStarOps() },
        )

    // sqlglot: Parser.PLACEHOLDER_PARSERS
    val PLACEHOLDER_PARSERS: Map<TokenType, (Parser) -> Expression?> = mapOf(
        TokenType.PLACEHOLDER to { parser -> parser.expression(Placeholder()) },
        TokenType.PARAMETER to { parser -> parser.parseParameter() },
        TokenType.COLON to { parser ->
            if (parser.matchSet(parser.colonPlaceholderTokens)) {
                parser.expression(Placeholder(args("this" to parser.prevToken.text)))
            } else {
                null
            }
        },
    )

    // sqlglot: Parser.RANGE_PARSERS (FOR -> _parse_comprehension and OPERATOR ->
    // _parse_operator omitted: the base tokenizer never emits those in range position
    // for the ported corpus; they raise via the generic unexpected-token path).
    val RANGE_PARSERS: Map<TokenType, (Parser, Expression?) -> Expression?> = mapOf(
        TokenType.AT_GT to binaryRangeParser { dev.brikk.house.sql.ast.ArrayContainsAll(it) },
        TokenType.BETWEEN to { parser, this_ -> parser.parseBetween(this_) },
        TokenType.GLOB to binaryRangeParser { dev.brikk.house.sql.ast.Glob(it) },
        TokenType.ILIKE to binaryRangeParser { ILike(it) },
        TokenType.IN to { parser, this_ -> parser.parseIn(this_) },
        TokenType.IRLIKE to binaryRangeParser { dev.brikk.house.sql.ast.RegexpILike(it) },
        TokenType.IS to { parser, this_ -> parser.parseIs(this_) },
        TokenType.LIKE to binaryRangeParser { Like(it) },
        TokenType.LT_AT to binaryRangeParser { dev.brikk.house.sql.ast.ArrayContainedBy(it) },
        TokenType.OVERLAPS to binaryRangeParser { dev.brikk.house.sql.ast.Overlaps(it) },
        TokenType.RLIKE to binaryRangeParser { dev.brikk.house.sql.ast.RegexpLike(it) },
        TokenType.SIMILAR_TO to binaryRangeParser { dev.brikk.house.sql.ast.SimilarTo(it) },
        TokenType.QMARK_AMP to binaryRangeParser { dev.brikk.house.sql.ast.JSONBContainsAllTopKeys(it) },
        TokenType.QMARK_PIPE to binaryRangeParser { dev.brikk.house.sql.ast.JSONBContainsAnyTopKeys(it) },
        TokenType.HASH_DASH to binaryRangeParser { dev.brikk.house.sql.ast.JSONBDeleteAtPath(it) },
        TokenType.AT_QMARK to binaryRangeParser { dev.brikk.house.sql.ast.JSONBPathExists(it) },
        TokenType.ADJACENT to binaryRangeParser { dev.brikk.house.sql.ast.Adjacent(it) },
        TokenType.AMP_LT to binaryRangeParser { dev.brikk.house.sql.ast.ExtendsLeft(it) },
        TokenType.AMP_GT to binaryRangeParser { dev.brikk.house.sql.ast.ExtendsRight(it) },
    )

    // sqlglot: Parser.NO_PAREN_FUNCTION_PARSERS
    val NO_PAREN_FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> = mapOf(
        "ANY" to { parser -> parser.expression(Any(args("this" to parser.parseBitwise()))) },
        "CASE" to { parser -> parser.parseCase() },
        "CONNECT_BY_ROOT" to { parser ->
            parser.expression(ConnectByRoot(args("this" to parser.parseColumn())))
        },
        "IF" to { parser -> parser.parseIf() },
    )

    // sqlglot: Parser.INVALID_FUNC_NAME_TOKENS
    val INVALID_FUNC_NAME_TOKENS: Set<TokenType> = setOf(TokenType.IDENTIFIER, TokenType.STRING)

    // sqlglot: Parser.FUNCTIONS_WITH_ALIASED_ARGS
    val FUNCTIONS_WITH_ALIASED_ARGS: Set<String> = setOf("STRUCT")

    // sqlglot: Parser.FUNCTION_PARSERS (GAP_FILL, OPENJSON, XMLELEMENT, XMLTABLE not
    // ported yet — no base-corpus coverage).
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> = buildMap {
        put("CONVERT") { parser -> parser.parseConvert(parser.strictCast) }
        put("TRY_CONVERT") { parser -> parser.parseConvert(false, safe = true) }
        for (name in listOf("ARG_MAX", "ARGMAX", "MAX_BY")) {
            put(name) { parser -> parser.parseMaxMinBy { a: Args -> dev.brikk.house.sql.ast.ArgMax(a) } }
        }
        for (name in listOf("ARG_MIN", "ARGMIN", "MIN_BY")) {
            put(name) { parser -> parser.parseMaxMinBy { a: Args -> dev.brikk.house.sql.ast.ArgMin(a) } }
        }
        put("CAST") { parser -> parser.parseCast(parser.strictCast) }
        put("CEIL") { parser -> parser.parseCeilFloor { a: Args -> dev.brikk.house.sql.ast.Ceil(a) } }
        put("CHAR") { parser -> parser.parseChar() }
        put("CHR") { parser -> parser.parseChar() }
        put("DECODE") { parser -> parser.parseDecode() }
        put("EXTRACT") { parser -> parser.parseExtract() }
        put("FLOOR") { parser -> parser.parseCeilFloor { a: Args -> dev.brikk.house.sql.ast.Floor(a) } }
        put("JSON_OBJECT") { parser -> parser.parseJsonObject() }
        put("JSON_OBJECTAGG") { parser -> parser.parseJsonObject(agg = true) }
        put("JSON_TABLE") { parser -> parser.parseJsonTable() }
        put("MATCH") { parser -> parser.parseMatchAgainst() }
        put("NORMALIZE") { parser -> parser.parseNormalize() }
        put("POSITION") { parser -> parser.parsePosition() }
        put("SAFE_CAST") { parser -> parser.parseCast(false, safe = true) }
        put("SUBSTRING") { parser -> parser.parseSubstring() }
        put("TRIM") { parser -> parser.parseTrim() }
        put("TRY_CAST") { parser -> parser.parseCast(false, safe = true) }
    }

    // sqlglot: Parser.PROPERTY_PARSERS
    val PROPERTY_PARSERS: Map<String, (Parser, Parser.PropertyKwargs) -> kotlin.Any?> = mapOf(
        "ALLOWED_VALUES" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.AllowedValuesProperty(
                    args("expressions" to p.parseCsv { p.parsePrimary() })
                )
            )
        },
        "ALGORITHM" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.AlgorithmProperty(a) })
        },
        "AUTO" to { p, _ -> p.parseAutoProperty() },
        "AUTO_INCREMENT" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.AutoIncrementProperty(a) })
        },
        "BACKUP" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.BackupProperty(args("this" to p.parseVar(anyToken = true)))
            )
        },
        "BLOCKCOMPRESSION" to { p, _ -> p.parseBlockcompression() },
        "CALLED" to { p, _ -> p.parseCalledOnNullInputProperty() },
        "CHARSET" to { p, kw -> p.parseCharacterSet(default = kw.default) },
        "CHARACTER SET" to { p, kw -> p.parseCharacterSet(default = kw.default) },
        "CHECKSUM" to { p, _ -> p.parseChecksum() },
        "CLUSTER BY" to { p, _ -> p.parseClusterProperty() },
        "CLUSTERED" to { p, _ -> p.parseClusteredBy() },
        "COLLATE" to { p, kw ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.CollateProperty(a) }, kw.toArgs())
        },
        "COMMENT" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.SchemaCommentProperty(a) })
        },
        "CONTAINS" to { p, _ -> p.parseContainsProperty() },
        "COPY" to { p, _ -> p.parseCopyProperty() },
        "DATABLOCKSIZE" to { p, kw ->
            p.parseDatablocksize(default = kw.default, minimum = kw.minimum, maximum = kw.maximum)
        },
        "DATA_DELETION" to { p, _ -> p.parseDataDeletionProperty() },
        "DEFINER" to { p, _ -> p.parseDefiner() },
        "DETERMINISTIC" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.StabilityProperty(args("this" to Literal.string("IMMUTABLE")))
            )
        },
        "DISTRIBUTED" to { p, _ -> p.parseDistributedProperty() },
        "DUPLICATE" to { p, _ ->
            p.parseCompositeKeyProperty { a -> dev.brikk.house.sql.ast.DuplicateKeyProperty(a) }
        },
        "DYNAMIC" to { p, _ -> p.expression(dev.brikk.house.sql.ast.DynamicProperty()) },
        "DISTKEY" to { p, _ -> p.parseDistkey() },
        "DISTSTYLE" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.DistStyleProperty(a) })
        },
        "EMPTY" to { p, _ -> p.expression(dev.brikk.house.sql.ast.EmptyProperty()) },
        "ENGINE" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.EngineProperty(a) })
        },
        "ENVIRONMENT" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.EnviromentProperty(
                    args("expressions" to p.parseWrappedCsv({ p.parseAssignment() }))
                )
            )
        },
        "HANDLER" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.HandlerProperty(a) })
        },
        "EXECUTE" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.ExecuteAsProperty(a) })
        },
        "EXTERNAL" to { p, _ -> p.expression(dev.brikk.house.sql.ast.ExternalProperty()) },
        "FALLBACK" to { p, kw -> p.parseFallback(no = kw.no) },
        "FORMAT" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.FileFormatProperty(a) })
        },
        "FREESPACE" to { p, _ -> p.parseFreespace() },
        "GLOBAL" to { p, _ -> p.expression(dev.brikk.house.sql.ast.GlobalProperty()) },
        "HEAP" to { p, _ -> p.expression(dev.brikk.house.sql.ast.HeapProperty()) },
        "ICEBERG" to { p, _ -> p.expression(dev.brikk.house.sql.ast.IcebergProperty()) },
        "IMMUTABLE" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.StabilityProperty(args("this" to Literal.string("IMMUTABLE")))
            )
        },
        "INHERITS" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.InheritsProperty(
                    args("expressions" to p.parseWrappedCsv({ p.parseTable() }))
                )
            )
        },
        "INPUT" to { p, _ ->
            p.expression(dev.brikk.house.sql.ast.InputModelProperty(args("this" to p.parseSchema())))
        },
        "JOURNAL" to { p, kw -> p.parseJournal(kw) },
        "LANGUAGE" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.LanguageProperty(a) })
        },
        "LAYOUT" to { p, _ -> p.parseDictProperty("LAYOUT") },
        "LIFETIME" to { p, _ -> p.parseDictRange("LIFETIME") },
        "LIKE" to { p, _ -> p.parseCreateLike() },
        "LOCATION" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.LocationProperty(a) })
        },
        "LOCK" to { p, _ -> p.parseLocking() },
        "LOCKING" to { p, _ -> p.parseLocking() },
        "LOG" to { p, kw -> p.parseLog(no = kw.no) },
        "MATERIALIZED" to { p, _ -> p.expression(dev.brikk.house.sql.ast.MaterializedProperty()) },
        "MERGEBLOCKRATIO" to { p, kw -> p.parseMergeblockratio(no = kw.no, default = kw.default) },
        "MODIFIES" to { p, _ -> p.parseModifiesProperty() },
        "MULTISET" to { p, _ ->
            p.expression(dev.brikk.house.sql.ast.SetProperty(args("multi" to true)))
        },
        "NO" to { p, _ -> p.parseNoProperty() },
        "ON" to { p, _ -> p.parseOnProperty() },
        "ORDER BY" to { p, _ -> p.parseOrder(skipOrderToken = true) },
        "OUTPUT" to { p, _ ->
            p.expression(dev.brikk.house.sql.ast.OutputModelProperty(args("this" to p.parseSchema())))
        },
        "PARTITION" to { p, _ -> p.parsePartitionedOf() },
        "PARTITION BY" to { p, _ -> p.parsePartitionedBy() },
        "PARTITIONED BY" to { p, _ -> p.parsePartitionedBy() },
        "PARTITIONED_BY" to { p, _ -> p.parsePartitionedBy() },
        "PRIMARY KEY" to { p, _ -> p.parsePrimaryKey(inProps = true) },
        "RANGE" to { p, _ -> p.parseDictRange("RANGE") },
        "READS" to { p, _ -> p.parseReadsProperty() },
        "REMOTE" to { p, _ -> p.parseRemoteWithConnection() },
        "RETURNS" to { p, _ -> p.parseReturns() },
        "STRICT" to { p, _ -> p.expression(dev.brikk.house.sql.ast.StrictProperty()) },
        "STREAMING" to { p, _ -> p.expression(dev.brikk.house.sql.ast.StreamingTableProperty()) },
        "ROW" to { p, _ -> p.parseRow() },
        "ROW_FORMAT" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.RowFormatProperty(a) })
        },
        "SAMPLE" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.SampleProperty(
                    args("this" to (if (p.matchTextSeq("BY")) p.parseBitwise() else false))
                )
            )
        },
        "SECURE" to { p, _ -> p.expression(dev.brikk.house.sql.ast.SecureProperty()) },
        "SECURITY" to { p, _ -> p.parseSqlSecurity() },
        "SQL SECURITY" to { p, _ -> p.parseSqlSecurity() },
        "SET" to { p, _ ->
            p.expression(dev.brikk.house.sql.ast.SetProperty(args("multi" to false)))
        },
        "SETTINGS" to { p, _ -> p.parseSettingsProperty() },
        "SHARING" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.SharingProperty(a) })
        },
        "SORTKEY" to { p, _ -> p.parseSortkey() },
        "SOURCE" to { p, _ -> p.parseDictProperty("SOURCE") },
        "STABLE" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.StabilityProperty(args("this" to Literal.string("STABLE")))
            )
        },
        "STORED" to { p, _ -> p.parseStored() },
        "SYSTEM_VERSIONING" to { p, _ -> p.parseSystemVersioningProperty() },
        "TBLPROPERTIES" to { p, _ -> p.parseWrappedProperties() },
        "TEMP" to { p, _ -> p.expression(dev.brikk.house.sql.ast.TemporaryProperty()) },
        "TEMPORARY" to { p, _ -> p.expression(dev.brikk.house.sql.ast.TemporaryProperty()) },
        "TO" to { p, _ -> p.parseToTable() },
        "TRANSIENT" to { p, _ -> p.expression(dev.brikk.house.sql.ast.TransientProperty()) },
        "TRANSFORM" to { p, _ ->
            p.expression(
                dev.brikk.house.sql.ast.TransformModelProperty(
                    args("expressions" to p.parseWrappedCsv({ p.parseExpression() }))
                )
            )
        },
        "TTL" to { p, _ -> p.parseTtl() },
        "USING" to { p, _ ->
            p.parsePropertyAssignment({ a -> dev.brikk.house.sql.ast.FileFormatProperty(a) })
        },
        "UNLOGGED" to { p, _ -> p.expression(dev.brikk.house.sql.ast.UnloggedProperty()) },
        "VOLATILE" to { p, _ -> p.parseVolatileProperty() },
        "WITH" to { p, _ -> p.parseWithProperty() },
    )

    // sqlglot: Parser.CONSTRAINT_PARSERS
    val CONSTRAINT_PARSERS: Map<String, (Parser) -> Expression?> = mapOf(
        "AUTOINCREMENT" to { p -> p.parseAutoIncrement() },
        "AUTO_INCREMENT" to { p -> p.parseAutoIncrement() },
        "CASESPECIFIC" to { p ->
            p.expression(dev.brikk.house.sql.ast.CaseSpecificColumnConstraint(args("not_" to false)))
        },
        "CHARACTER SET" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.CharacterSetColumnConstraint(args("this" to p.parseVarOrString()))
            )
        },
        "CHECK" to { p -> p.parseCheckConstraint() },
        "COLLATE" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.CollateColumnConstraint(
                    args("this" to (p.parseIdentifier() ?: p.parseColumn()))
                )
            )
        },
        "COMMENT" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.CommentColumnConstraint(args("this" to p.parseString()))
            )
        },
        "COMPRESS" to { p -> p.parseCompress() },
        "CLUSTERED" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.ClusteredColumnConstraint(
                    args("this" to p.parseWrappedCsv({ p.parseOrdered() }))
                )
            )
        },
        "NONCLUSTERED" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.NonClusteredColumnConstraint(
                    args("this" to p.parseWrappedCsv({ p.parseOrdered() }))
                )
            )
        },
        "DEFAULT" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.DefaultColumnConstraint(args("this" to p.parseBitwise()))
            )
        },
        "ENCODE" to { p ->
            p.expression(dev.brikk.house.sql.ast.EncodeColumnConstraint(args("this" to p.parseVar())))
        },
        "EPHEMERAL" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.EphemeralColumnConstraint(args("this" to p.parseBitwise()))
            )
        },
        "EXCLUDE" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.ExcludeColumnConstraint(args("this" to p.parseIndexParams()))
            )
        },
        "FOREIGN KEY" to { p -> p.parseForeignKey() },
        "FORMAT" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.DateFormatColumnConstraint(args("this" to p.parseVarOrString()))
            )
        },
        "GENERATED" to { p -> p.parseGeneratedAsIdentity() },
        "IDENTITY" to { p -> p.parseAutoIncrement() },
        "INLINE" to { p -> p.parseInline() },
        "LIKE" to { p -> p.parseCreateLike() },
        "NOT" to { p -> p.parseNotConstraint() },
        "NULL" to { p ->
            p.expression(dev.brikk.house.sql.ast.NotNullColumnConstraint(args("allow_null" to true)))
        },
        "ON" to { p ->
            if (p.match(TokenType.UPDATE)) {
                p.expression(
                    dev.brikk.house.sql.ast.OnUpdateColumnConstraint(args("this" to p.parseFunction()))
                )
            } else {
                p.expression(dev.brikk.house.sql.ast.OnProperty(args("this" to p.parseIdVar())))
            }
        },
        "PATH" to { p ->
            p.expression(dev.brikk.house.sql.ast.PathColumnConstraint(args("this" to p.parseString())))
        },
        "PERIOD" to { p -> p.parsePeriodForSystemTime() },
        "PRIMARY KEY" to { p -> p.parsePrimaryKey() },
        "REFERENCES" to { p -> p.parseReferences(matchToken = false) },
        "TITLE" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.TitleColumnConstraint(args("this" to p.parseVarOrString()))
            )
        },
        "TTL" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.MergeTreeTTL(
                    args("expressions" to listOfNotNull(p.parseBitwise()))
                )
            )
        },
        "UNIQUE" to { p -> p.parseUnique() },
        "UPPERCASE" to { p ->
            p.expression(dev.brikk.house.sql.ast.UppercaseColumnConstraint())
        },
        "WITH" to { p ->
            p.expression(
                dev.brikk.house.sql.ast.Properties(args("expressions" to p.parseWrappedProperties()))
            )
        },
        "BUCKET" to { p -> p.parsePartitionedByBucketOrTruncate() },
        "TRUNCATE" to { p -> p.parsePartitionedByBucketOrTruncate() },
    )

    // sqlglot: Parser.SCHEMA_UNNAMED_CONSTRAINTS
    val SCHEMA_UNNAMED_CONSTRAINTS: Set<String> = setOf(
        "CHECK", "EXCLUDE", "FOREIGN KEY", "LIKE", "PERIOD", "PRIMARY KEY", "UNIQUE",
        "BUCKET", "TRUNCATE",
    )

    // sqlglot: Parser.KEY_CONSTRAINT_OPTIONS
    val KEY_CONSTRAINT_OPTIONS: Map<String, List<List<String>>> = mapOf(
        "NOT" to listOf(listOf("ENFORCED")),
        "MATCH" to listOf(listOf("FULL"), listOf("PARTIAL"), listOf("SIMPLE")),
        "INITIALLY" to listOf(listOf("DEFERRED"), listOf("IMMEDIATE")),
        "USING" to listOf(listOf("BTREE"), listOf("HASH")),
        "DEFERRABLE" to emptyList(),
        "NORELY" to emptyList(),
        "RELY" to emptyList(),
    )

    // sqlglot: Parser.CONFLICT_ACTIONS
    val CONFLICT_ACTIONS: Map<String, List<List<String>>> = mapOf(
        "ABORT" to emptyList(),
        "FAIL" to emptyList(),
        "IGNORE" to emptyList(),
        "REPLACE" to emptyList(),
        "ROLLBACK" to emptyList(),
        "UPDATE" to emptyList(),
        "DO" to listOf(listOf("NOTHING"), listOf("UPDATE")),
    )

    // sqlglot: Parser.QUERY_MODIFIER_PARSERS
    val QUERY_MODIFIER_PARSERS: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>> = mapOf(
        TokenType.MATCH_RECOGNIZE to { parser -> "match" to parser.parseMatchRecognize() },
        TokenType.FOR to { parser -> "locks" to parser.parseLocks() },
        TokenType.LOCK to { parser -> "locks" to parser.parseLocks() },
        TokenType.PREWHERE to { parser -> "prewhere" to parser.parsePrewhere() },
        TokenType.WHERE to { parser -> "where" to parser.parseWhere() },
        TokenType.GROUP_BY to { parser -> "group" to parser.parseGroup() },
        TokenType.HAVING to { parser -> "having" to parser.parseHaving() },
        TokenType.QUALIFY to { parser -> "qualify" to parser.parseQualify() },
        TokenType.WINDOW to { parser -> "windows" to parser.parseWindowClause() },
        TokenType.ORDER_BY to { parser -> "order" to parser.parseOrder() },
        TokenType.LIMIT to { parser -> "limit" to parser.parseLimit() },
        TokenType.FETCH to { parser -> "limit" to parser.parseLimit() },
        TokenType.OFFSET to { parser -> "offset" to parser.parseOffset() },
        TokenType.TABLE_SAMPLE to { parser -> "sample" to parser.parseTableSampleModifier() },
        TokenType.USING to { parser -> "sample" to parser.parseTableSampleModifier() },
        TokenType.CLUSTER_BY to { parser -> "cluster" to parser.parseCluster() },
        TokenType.DISTRIBUTE_BY to { parser ->
            "distribute" to parser.parseSort({ a: Args -> dev.brikk.house.sql.ast.Distribute(a) }, TokenType.DISTRIBUTE_BY)
        },
        TokenType.SORT_BY to { parser ->
            "sort" to parser.parseSort({ a: Args -> dev.brikk.house.sql.ast.Sort(a) }, TokenType.SORT_BY)
        },
        TokenType.CONNECT_BY to { parser -> "connect" to parser.parseConnect(skipStartToken = true) },
        TokenType.START_WITH to { parser -> "connect" to parser.parseConnect() },
    )

    // sqlglot: Parser.TYPE_LITERAL_PARSERS
    val TYPE_LITERAL_PARSERS: Map<dev.brikk.house.sql.ast.DType, (Parser, Expression?, Expression) -> Expression?> =
        mapOf(
            dev.brikk.house.sql.ast.DType.JSON to { parser, this_, _ ->
                parser.expression(dev.brikk.house.sql.ast.ParseJSON(args("this" to this_)))
            },
        )

    // sqlglot: Parser.WINDOW_ALIAS_TOKENS
    val WINDOW_ALIAS_TOKENS: Set<TokenType> = ID_VAR_TOKENS - setOf(TokenType.RANGE, TokenType.ROWS)

    // sqlglot: Parser.WINDOW_BEFORE_PAREN_TOKENS
    val WINDOW_BEFORE_PAREN_TOKENS: Set<TokenType> = setOf(TokenType.OVER)

    // sqlglot: Parser.WINDOW_SIDES
    val WINDOW_SIDES: Set<String> = setOf("FOLLOWING", "PRECEDING")

    // sqlglot: Parser.FETCH_TOKENS
    val FETCH_TOKENS: Set<TokenType> = ID_VAR_TOKENS - setOf(
        TokenType.ROW, TokenType.ROWS, TokenType.PERCENT,
    )

    // sqlglot: Parser.DISTINCT_TOKENS
    val DISTINCT_TOKENS: Set<TokenType> = setOf(TokenType.DISTINCT)

    // sqlglot: Parser.SELECT_START_TOKENS
    val SELECT_START_TOKENS: Set<TokenType> = setOf(
        TokenType.L_PAREN, TokenType.WITH, TokenType.SELECT,
    )

    // sqlglot: Parser.AMBIGUOUS_ALIAS_TOKENS
    val AMBIGUOUS_ALIAS_TOKENS: Set<TokenType> = setOf(TokenType.LIMIT, TokenType.OFFSET)

    // sqlglot: Parser.OPERATION_MODIFIERS
    val OPERATION_MODIFIERS: Set<String> = emptySet()

    // sqlglot: Parser.RECURSIVE_CTE_SEARCH_KIND
    val RECURSIVE_CTE_SEARCH_KIND: Set<String> = setOf("BREADTH", "DEPTH", "CYCLE")

    // sqlglot: Parser.CAST_ACTIONS (option -> keyword continuations, see _parse_var_from_options)
    val CAST_ACTIONS: Map<String, List<List<String>>> = mapOf(
        "RENAME" to listOf(listOf("FIELDS")),
        "ADD" to listOf(listOf("FIELDS")),
    )

    // sqlglot: Parser.HISTORICAL_DATA_PREFIX
    val HISTORICAL_DATA_PREFIX: Set<String> = setOf("AT", "BEFORE", "END")

    // sqlglot: Parser.HISTORICAL_DATA_KIND
    val HISTORICAL_DATA_KIND: Set<String> = setOf("OFFSET", "STATEMENT", "STREAM", "TIMESTAMP", "VERSION")
}
