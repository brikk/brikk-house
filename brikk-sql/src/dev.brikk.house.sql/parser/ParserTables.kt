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
internal fun binaryRangeParser(factory: NodeFactory): (Parser, Expression?) -> Expression? =
    { parser, this_ ->
        val right = parser.parseBitwise()
        if (right == null && this_ == null) null
        else parser.expression(factory(args("this" to this_, "expression" to right)))
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
    factory(kwargs)
}

object BaseParserTables {

    // sqlglot: Parser.FUNCTIONS — subset of exp.FUNCTION_BY_NAME limited to the ported
    // node catalog, plus the custom COUNT builder. Grows with the node catalog.
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = mapOf(
        // sqlglot: parser.py "COUNT" builder (big_int=True in the base dialect)
        "COUNT" to { fnArgs ->
            Count(
                args(
                    "this" to fnArgs.getOrNull(0),
                    "expressions" to fnArgs.drop(1),
                    "big_int" to true,
                )
            )
        },
        "SUM" to fromArgList(listOf("this"), false) { Sum(it) },
        "MIN" to fromArgList(listOf("this", "expressions"), true) { Min(it) },
        "MAX" to fromArgList(listOf("this", "expressions"), true) { Max(it) },
        "AVG" to fromArgList(listOf("this"), false) { Avg(it) },
        "ROW_NUMBER" to fromArgList(listOf("this"), false) { RowNumber(it) },
        "POWER" to fromArgList(listOf("this", "expression"), false) { Pow(it) },
        "POW" to fromArgList(listOf("this", "expression"), false) { Pow(it) },
        "IF" to fromArgList(listOf("this", "true", "false"), false) { If(it) },
        "IIF" to fromArgList(listOf("this", "true", "false"), false) { If(it) },
        "COLLATE" to fromArgList(listOf("this", "expression"), false) { Collate(it) },
        "CONNECT_BY_ROOT" to fromArgList(listOf("this"), false) { ConnectByRoot(it) },
    )

    // sqlglot: Parser.NO_PAREN_FUNCTIONS — CurrentDate etc. not in the node catalog yet.
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> = emptyMap()

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
    val LAMBDAS: Map<TokenType, (Parser, List<Expression?>) -> Expression?> = emptyMap()

    // sqlglot: Parser.LAMBDA_ARG_TERMINATORS
    val LAMBDA_ARG_TERMINATORS: Set<TokenType> = setOf(TokenType.COMMA, TokenType.R_PAREN)

    // sqlglot: Parser.COLUMN_OPERATORS — only DOT (None sentinel) and DCOLON (cast) are
    // ported; JSON extraction operators land with their nodes.
    val COLUMN_OPERATORS: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?> =
        mapOf(
            TokenType.DOT to null,
            TokenType.DCOLON to { parser, this_, to ->
                parser.buildCast(strict = parser.strictCast, this_ = this_, to = to)
            },
        )

    // sqlglot: Parser.CAST_COLUMN_OPERATORS (DOTCOLON -> JSONCast not ported)
    val CAST_COLUMN_OPERATORS: Set<TokenType> = setOf(TokenType.DCOLON)

    // sqlglot: Parser.STATEMENT_PARSERS — only SEMICOLON from the DDL/DML table is in
    // scope for the SELECT-family subset; the other statements arrive with their nodes.
    val STATEMENT_PARSERS: Map<TokenType, (Parser) -> Expression> = mapOf(
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

    // sqlglot: Parser.STRING_PARSERS (HEREDOC/NATIONAL/RAW/UNICODE strings not ported)
    val STRING_PARSERS: Map<TokenType, (Parser, Token) -> Expression?> = mapOf(
        TokenType.STRING to { parser, token ->
            parser.expression(Literal(args("this" to token.text, "is_string" to true)), token)
        },
    )

    // sqlglot: Parser.NUMERIC_PARSERS (BIT/BYTE/HEX strings not ported)
    val NUMERIC_PARSERS: Map<TokenType, (Parser, Token) -> Expression?> = mapOf(
        TokenType.NUMBER to { parser, token ->
            parser.expression(Literal(args("this" to token.text, "is_string" to false)), token)
        },
    )

    // sqlglot: Parser.PRIMARY_PARSERS (INTRODUCER/SESSION_PARAMETER not ported)
    val PRIMARY_PARSERS: Map<TokenType, (Parser, Token) -> Expression?> =
        STRING_PARSERS + NUMERIC_PARSERS + mapOf(
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

    // sqlglot: Parser.RANGE_PARSERS — subset limited to the ported node catalog
    // (GLOB/RLIKE/OVERLAPS/JSONB operators etc. arrive with their nodes).
    val RANGE_PARSERS: Map<TokenType, (Parser, Expression?) -> Expression?> = mapOf(
        TokenType.BETWEEN to { parser, this_ -> parser.parseBetween(this_) },
        TokenType.ILIKE to binaryRangeParser { ILike(it) },
        TokenType.IN to { parser, this_ -> parser.parseIn(this_) },
        TokenType.IS to { parser, this_ -> parser.parseIs(this_) },
        TokenType.LIKE to binaryRangeParser { Like(it) },
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

    // sqlglot: Parser.FUNCTION_PARSERS — CAST only; the other special-form functions
    // (EXTRACT, TRIM, POSITION, ...) arrive with their nodes.
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> = mapOf(
        "CAST" to { parser -> parser.parseCast(parser.strictCast) },
    )

    // sqlglot: Parser.QUERY_MODIFIER_PARSERS — SELECT-family subset (MATCH_RECOGNIZE,
    // PREWHERE, QUALIFY, WINDOW, FETCH, locks, sampling, cluster/distribute/sort/connect
    // arrive with their nodes).
    val QUERY_MODIFIER_PARSERS: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>> = mapOf(
        TokenType.WHERE to { parser -> "where" to parser.parseWhere() },
        TokenType.GROUP_BY to { parser -> "group" to parser.parseGroup() },
        TokenType.HAVING to { parser -> "having" to parser.parseHaving() },
        TokenType.ORDER_BY to { parser -> "order" to parser.parseOrder() },
        TokenType.LIMIT to { parser -> "limit" to parser.parseLimit() },
        TokenType.OFFSET to { parser -> "offset" to parser.parseOffset() },
    )

    // sqlglot: Parser.TYPE_LITERAL_PARSERS (JSON -> ParseJSON not ported)
    val TYPE_LITERAL_PARSERS: Map<dev.brikk.house.sql.ast.DType, (Parser, Expression?, Expression) -> Expression?> =
        emptyMap()

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
