package dev.brikk.house.sql.parser

import dev.brikk.house.sql.ast.AggFunc
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Aliases
import dev.brikk.house.sql.ast.All
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Any
import dev.brikk.house.sql.ast.Between
import dev.brikk.house.sql.ast.Boolean
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Case
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.Collate
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.DPipe
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DataTypeParam
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Div
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Escape
import dev.brikk.house.sql.ast.Except
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.Group
import dev.brikk.house.sql.ast.Having
import dev.brikk.house.sql.ast.ILike
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.In
import dev.brikk.house.sql.ast.Intersect
import dev.brikk.house.sql.ast.Is
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Like
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Mod
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.ast.NullSafeEQ
import dev.brikk.house.sql.ast.NullSafeNEQ
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Ordered
import dev.brikk.house.sql.ast.Paren
import dev.brikk.house.sql.ast.Parameter
import dev.brikk.house.sql.ast.PropertyEQ
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.args

/**
 * Faithful port of reference/sqlglot/sqlglot/parser.py `Parser` for the SELECT-family
 * subset (see brikk-sql/testResources/parser-corpus/base.json for the oracle cases).
 *
 * Conventions:
 *  - every ported method carries a `// sqlglot: Parser._parse_x` provenance comment;
 *  - navigation uses the SENTINEL token pattern (never nulls), like Python's
 *    SENTINEL_NONE (Token.__bool__ is False for SENTINEL);
 *  - class-level tables are exposed as open provider properties (defaults in
 *    [BaseParserTables]) so dialect subclasses can override them, mirroring Python's
 *    class-variable overrides;
 *  - node positions (Expression.update_positions -> meta) are NOT tracked yet: oracle
 *    comparisons strip meta on both sides. Comments ARE attached.
 *  - `// TODO(pipe): Phase 4` marks the spots where parser.py handles PIPE_GT.
 */
open class Parser(
    errorLevel: ErrorLevel? = null,
    val errorMessageContext: Int = ERROR_MESSAGE_CONTEXT_DEFAULT,
    val maxErrors: Int = 3,
    val maxNodes: Int = -1,
    val tokenizerConfig: TokenizerConfig = TokenizerConfig.BASE,
) {

    companion object {
        // sqlglot: tokenizer_core.SENTINEL_NONE
        val SENTINEL: Token = Token(TokenType.SENTINEL, "")

        // sqlglot: expressions.SQLGLOT_ANONYMOUS
        const val SQLGLOT_ANONYMOUS: String = "sqlglot.anonymous"
    }

    // -----------------------------------------------------------------------
    // Dialect-level flags (sqlglot: Dialect / Parser class vars; base defaults)
    // -----------------------------------------------------------------------

    // sqlglot: Parser.STRICT_CAST
    open val strictCast: kotlin.Boolean get() = true

    // sqlglot: Dialect.NULL_ORDERING
    open val nullOrdering: String get() = "nulls_are_small"

    // sqlglot: Dialect.DPIPE_IS_STRING_CONCAT
    open val dpipeIsStringConcat: kotlin.Boolean get() = true

    // sqlglot: Dialect.STRICT_STRING_CONCAT
    open val strictStringConcat: kotlin.Boolean get() = false

    // sqlglot: Dialect.TYPED_DIVISION
    open val typedDivision: kotlin.Boolean get() = false

    // sqlglot: Dialect.SAFE_DIVISION
    open val safeDivision: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_LIMIT_ALL
    open val supportsLimitAll: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_ORDER_BY_ALL
    open val supportsOrderByAll: kotlin.Boolean get() = false

    // sqlglot: Parser.STRING_ALIASES
    open val stringAliases: kotlin.Boolean get() = false

    // sqlglot: Parser.OPTIONAL_ALIAS_TOKEN_CTE
    open val optionalAliasTokenCte: kotlin.Boolean get() = true

    // sqlglot: Parser.JOINS_HAVE_EQUAL_PRECEDENCE
    open val joinsHaveEqualPrecedence: kotlin.Boolean get() = false

    // sqlglot: Parser.ADD_JOIN_ON_TRUE
    open val addJoinOnTrue: kotlin.Boolean get() = false

    // sqlglot: Parser.MODIFIERS_ATTACHED_TO_SET_OP
    open val modifiersAttachedToSetOp: kotlin.Boolean get() = true

    // sqlglot: Parser.SET_OP_MODIFIERS
    open val setOpModifiers: Set<String> get() = setOf("order", "limit", "offset")

    // sqlglot: Dialect.SET_OP_DISTINCT_BY_DEFAULT (base: all True)
    open fun setOpDistinctByDefault(setOpToken: TokenType): kotlin.Boolean? = true

    // sqlglot: Dialect.PRESERVE_ORIGINAL_NAMES
    open val preserveOriginalNames: kotlin.Boolean get() = false

    // sqlglot: Dialect.SUPPORTS_COLUMN_JOIN_MARKS
    open val supportsColumnJoinMarks: kotlin.Boolean get() = false

    // sqlglot: Parser.COLON_IS_VARIANT_EXTRACT
    open val colonIsVariantExtract: kotlin.Boolean get() = false

    // sqlglot: Parser.VALUES_FOLLOWED_BY_PAREN
    open val valuesFollowedByParen: kotlin.Boolean get() = true

    // sqlglot: Dialect.ALIAS_POST_TABLESAMPLE
    open val aliasPostTablesample: kotlin.Boolean get() = false

    // sqlglot: Dialect.ALIAS_POST_VERSION
    open val aliasPostVersion: kotlin.Boolean get() = true

    // sqlglot: Parser.SUPPORTS_IMPLICIT_UNNEST
    open val supportsImplicitUnnest: kotlin.Boolean get() = false

    // sqlglot: Parser.TYPED_LAMBDA_ARGS
    open val typedLambdaArgs: kotlin.Boolean get() = false

    // sqlglot: Parser.NO_PAREN_IF_COMMANDS
    open val noParenIfCommands: kotlin.Boolean get() = true

    // -----------------------------------------------------------------------
    // Table providers (sqlglot: Parser class-level tables; see BaseParserTables)
    // -----------------------------------------------------------------------

    open val functions: Map<String, (List<Expression?>) -> Expression> get() = BaseParserTables.FUNCTIONS
    open val noParenFunctions: Map<TokenType, () -> Expression> get() = BaseParserTables.NO_PAREN_FUNCTIONS
    open val structTypeTokens: Set<TokenType> get() = BaseParserTables.STRUCT_TYPE_TOKENS
    open val nestedTypeTokens: Set<TokenType> get() = BaseParserTables.NESTED_TYPE_TOKENS
    open val enumTypeTokens: Set<TokenType> get() = BaseParserTables.ENUM_TYPE_TOKENS
    open val aggregateTypeTokens: Set<TokenType> get() = BaseParserTables.AGGREGATE_TYPE_TOKENS
    open val typeTokens: Set<TokenType> get() = BaseParserTables.TYPE_TOKENS
    open val signedToUnsignedTypeToken: Map<TokenType, TokenType> get() = BaseParserTables.SIGNED_TO_UNSIGNED_TYPE_TOKEN
    open val subqueryPredicates: Map<TokenType, NodeFactory> get() = BaseParserTables.SUBQUERY_PREDICATES
    open val subqueryTokens: Set<TokenType> get() = BaseParserTables.SUBQUERY_TOKENS
    open val reservedTokens: Set<TokenType> get() = BaseParserTables.RESERVED_TOKENS
    open val textMatchExcludedTokens: Set<TokenType> get() = BaseParserTables.TEXT_MATCH_EXCLUDED_TOKENS
    open val idVarTokens: Set<TokenType> get() = BaseParserTables.ID_VAR_TOKENS
    open val tableAliasTokens: Set<TokenType> get() = BaseParserTables.TABLE_ALIAS_TOKENS
    open val aliasTokens: Set<TokenType> get() = BaseParserTables.ALIAS_TOKENS
    open val colonPlaceholderTokens: Set<TokenType> get() = BaseParserTables.COLON_PLACEHOLDER_TOKENS
    open val identifierTokens: Set<TokenType> get() = BaseParserTables.IDENTIFIER_TOKENS
    open val brackets: Set<TokenType> get() = BaseParserTables.BRACKETS
    open val columnPostfixTokens: Set<TokenType> get() = BaseParserTables.COLUMN_POSTFIX_TOKENS
    open val tablePostfixTokens: Set<TokenType> get() = BaseParserTables.TABLE_POSTFIX_TOKENS
    open val funcTokens: Set<TokenType> get() = BaseParserTables.FUNC_TOKENS
    open val conjunction: Map<TokenType, NodeFactory> get() = BaseParserTables.CONJUNCTION
    open val assignmentTable: Map<TokenType, NodeFactory> get() = BaseParserTables.ASSIGNMENT
    open val disjunction: Map<TokenType, NodeFactory> get() = BaseParserTables.DISJUNCTION
    open val equality: Map<TokenType, NodeFactory> get() = BaseParserTables.EQUALITY
    open val comparison: Map<TokenType, NodeFactory> get() = BaseParserTables.COMPARISON
    open val bitwise: Map<TokenType, NodeFactory> get() = BaseParserTables.BITWISE
    open val term: Map<TokenType, NodeFactory> get() = BaseParserTables.TERM
    open val factor: Map<TokenType, NodeFactory> get() = BaseParserTables.FACTOR
    open val exponent: Map<TokenType, NodeFactory> get() = BaseParserTables.EXPONENT
    open val times: Set<TokenType> get() = BaseParserTables.TIMES
    open val timestamps: Set<TokenType> get() = BaseParserTables.TIMESTAMPS
    open val setOperations: Set<TokenType> get() = BaseParserTables.SET_OPERATIONS
    open val joinMethods: Set<TokenType> get() = BaseParserTables.JOIN_METHODS
    open val joinSides: Set<TokenType> get() = BaseParserTables.JOIN_SIDES
    open val joinKinds: Set<TokenType> get() = BaseParserTables.JOIN_KINDS
    open val joinHints: Set<String> get() = BaseParserTables.JOIN_HINTS
    open val tableTerminators: Set<TokenType> get() = BaseParserTables.TABLE_TERMINATORS
    open val lambdas: Map<TokenType, (Parser, List<Expression?>) -> Expression?> get() = BaseParserTables.LAMBDAS
    open val lambdaArgTerminators: Set<TokenType> get() = BaseParserTables.LAMBDA_ARG_TERMINATORS
    open val columnOperators: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?> get() = BaseParserTables.COLUMN_OPERATORS
    open val castColumnOperators: Set<TokenType> get() = BaseParserTables.CAST_COLUMN_OPERATORS
    open val statementParsers: Map<TokenType, (Parser) -> Expression> get() = BaseParserTables.STATEMENT_PARSERS
    open val unaryParsers: Map<TokenType, (Parser) -> Expression?> get() = BaseParserTables.UNARY_PARSERS
    open val stringParsers: Map<TokenType, (Parser, Token) -> Expression?> get() = BaseParserTables.STRING_PARSERS
    open val numericParsers: Map<TokenType, (Parser, Token) -> Expression?> get() = BaseParserTables.NUMERIC_PARSERS
    open val primaryParsers: Map<TokenType, (Parser, Token) -> Expression?> get() = BaseParserTables.PRIMARY_PARSERS
    open val placeholderParsers: Map<TokenType, (Parser) -> Expression?> get() = BaseParserTables.PLACEHOLDER_PARSERS
    open val rangeParsers: Map<TokenType, (Parser, Expression?) -> Expression?> get() = BaseParserTables.RANGE_PARSERS
    open val noParenFunctionParsers: Map<String, (Parser) -> Expression?> get() = BaseParserTables.NO_PAREN_FUNCTION_PARSERS
    open val invalidFuncNameTokens: Set<TokenType> get() = BaseParserTables.INVALID_FUNC_NAME_TOKENS
    open val functionsWithAliasedArgs: Set<String> get() = BaseParserTables.FUNCTIONS_WITH_ALIASED_ARGS
    open val functionParsers: Map<String, (Parser) -> Expression?> get() = BaseParserTables.FUNCTION_PARSERS
    open val queryModifierParsers: Map<TokenType, (Parser) -> Pair<String, kotlin.Any?>> get() = BaseParserTables.QUERY_MODIFIER_PARSERS
    open val queryModifierTokens: Set<TokenType> get() = queryModifierParsers.keys
    open val typeLiteralParsers: Map<DType, (Parser, Expression?, Expression) -> Expression?> get() = BaseParserTables.TYPE_LITERAL_PARSERS
    open val windowAliasTokens: Set<TokenType> get() = BaseParserTables.WINDOW_ALIAS_TOKENS
    open val windowBeforeParenTokens: Set<TokenType> get() = BaseParserTables.WINDOW_BEFORE_PAREN_TOKENS
    open val windowSides: Set<String> get() = BaseParserTables.WINDOW_SIDES
    open val fetchTokens: Set<TokenType> get() = BaseParserTables.FETCH_TOKENS
    open val distinctTokens: Set<TokenType> get() = BaseParserTables.DISTINCT_TOKENS
    open val selectStartTokens: Set<TokenType> get() = BaseParserTables.SELECT_START_TOKENS
    open val ambiguousAliasTokens: Set<TokenType> get() = BaseParserTables.AMBIGUOUS_ALIAS_TOKENS
    open val operationModifiers: Set<String> get() = BaseParserTables.OPERATION_MODIFIERS
    open val recursiveCteSearchKind: Set<String> get() = BaseParserTables.RECURSIVE_CTE_SEARCH_KIND
    open val castActions: Map<String, List<List<String>>> get() = BaseParserTables.CAST_ACTIONS
    open val historicalDataPrefix: Set<String> get() = BaseParserTables.HISTORICAL_DATA_PREFIX
    open val historicalDataKind: Set<String> get() = BaseParserTables.HISTORICAL_DATA_KIND

    // -----------------------------------------------------------------------
    // State (sqlglot: Parser.__init__ / Parser.reset)
    // -----------------------------------------------------------------------

    var errorLevel: ErrorLevel = errorLevel ?: ErrorLevel.IMMEDIATE

    var sql: String = ""
        protected set

    val errors: MutableList<ParseError> = mutableListOf()

    protected var tokens: List<Token> = emptyList()
    protected var tokensSize: Int = 0
    protected var index: Int = 0

    var currToken: Token = SENTINEL
        protected set
    var nextToken: Token = SENTINEL
        protected set
    var prevToken: Token = SENTINEL
        protected set
    var prevComments: List<String> = emptyList()
        protected set

    private var chunks: List<MutableList<Token>> = emptyList()
    private var chunkIndex: Int = 0
    private var nodeCount: Int = 0

    /** Python's `bool(token)` — SENTINEL tokens are falsy. */
    protected val Token.exists: kotlin.Boolean get() = tokenType != TokenType.SENTINEL

    // sqlglot: Parser.reset
    fun reset() {
        sql = ""
        errors.clear()
        tokens = emptyList()
        tokensSize = 0
        index = 0
        currToken = SENTINEL
        nextToken = SENTINEL
        prevToken = SENTINEL
        prevComments = emptyList()
        chunks = emptyList()
        chunkIndex = 0
        nodeCount = 0
    }

    // -----------------------------------------------------------------------
    // Navigation / matchers
    // -----------------------------------------------------------------------

    // sqlglot: Parser._advance
    protected fun advance(times: Int = 1) {
        val newIndex = index + times
        index = newIndex
        currToken = if (newIndex < tokensSize) tokens[newIndex] else SENTINEL
        nextToken = if (newIndex + 1 < tokensSize) tokens[newIndex + 1] else SENTINEL

        if (newIndex > 0) {
            val prev = tokens[newIndex - 1]
            prevToken = prev
            prevComments = prev.comments
        } else {
            prevToken = SENTINEL
            prevComments = emptyList()
        }
    }

    // sqlglot: Parser._advance_chunk
    private fun advanceChunk() {
        index = -1
        tokens = chunks[chunkIndex]
        tokensSize = tokens.size
        chunkIndex += 1
        advance()
    }

    // sqlglot: Parser._retreat
    protected fun retreat(index: Int) {
        if (index != this.index) advance(index - this.index)
    }

    // sqlglot: Parser._add_comments
    protected fun addTokenComments(expression: Expression?) {
        if (expression != null && prevComments.isNotEmpty()) {
            expression.addComments(prevComments)
            prevComments = emptyList()
        }
    }

    // sqlglot: Parser._match
    fun match(tokenType: TokenType, advance: kotlin.Boolean = true, expression: Expression? = null): kotlin.Boolean {
        if (currToken.tokenType == tokenType) {
            if (advance) advance()
            addTokenComments(expression)
            return true
        }
        return false
    }

    // sqlglot: Parser._match_set
    fun matchSet(types: Collection<TokenType>, advance: kotlin.Boolean = true): kotlin.Boolean {
        if (currToken.tokenType in types) {
            if (advance) advance()
            return true
        }
        return false
    }

    // sqlglot: Parser._match_pair
    fun matchPair(tokenTypeA: TokenType, tokenTypeB: TokenType, advance: kotlin.Boolean = true): kotlin.Boolean {
        if (currToken.tokenType == tokenTypeA && nextToken.tokenType == tokenTypeB) {
            if (advance) advance(2)
            return true
        }
        return false
    }

    // sqlglot: Parser._match_texts
    fun matchTexts(texts: Collection<String>, advance: kotlin.Boolean = true): kotlin.Boolean {
        if (currToken.tokenType !in textMatchExcludedTokens && currToken.text.uppercase() in texts) {
            if (advance) advance()
            return true
        }
        return false
    }

    // sqlglot: Parser._match_text_seq
    fun matchTextSeq(vararg texts: String, advance: kotlin.Boolean = true): kotlin.Boolean {
        val startIndex = index
        for (text in texts) {
            if (currToken.tokenType !in textMatchExcludedTokens && currToken.text.uppercase() == text) {
                advance()
            } else {
                retreat(startIndex)
                return false
            }
        }
        if (!advance) retreat(startIndex)
        return true
    }

    // sqlglot: Parser._match_l_paren
    protected fun matchLParen(expression: Expression? = null) {
        if (!match(TokenType.L_PAREN, expression = expression)) raiseError("Expecting (")
    }

    // sqlglot: Parser._match_r_paren
    protected fun matchRParen(expression: Expression? = null) {
        if (!match(TokenType.R_PAREN, expression = expression)) raiseError("Expecting )")
    }

    // sqlglot: Parser._is_connected
    protected fun isConnected(): kotlin.Boolean =
        prevToken.exists && currToken.exists && prevToken.end + 1 == currToken.start

    // sqlglot: Parser._find_sql
    protected fun findSql(start: Token, end: Token): String = sql.substring(start.start, end.end + 1)

    // sqlglot: Parser._advance_any
    protected fun advanceAny(ignoreReserved: kotlin.Boolean = false): Token? {
        if (currToken.exists && (ignoreReserved || currToken.tokenType !in reservedTokens)) {
            advance()
            return prevToken
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Errors / validation
    // -----------------------------------------------------------------------

    // sqlglot: Parser.raise_error
    fun raiseError(message: String, token: Token = SENTINEL): Nothing? {
        val errorToken = when {
            token.exists -> token
            currToken.exists -> currToken
            prevToken.exists -> prevToken
            else -> Token.string("")
        }
        val highlighted = highlightSql(
            sql = sql,
            positions = listOf(errorToken.start to errorToken.end),
            contextLength = errorMessageContext,
        )
        val formattedMessage =
            "$message. Line ${errorToken.line}, Col: ${errorToken.col}.\n  ${highlighted.formattedSql}"

        val error = ParseError.new(
            formattedMessage,
            description = message,
            line = errorToken.line,
            col = errorToken.col,
            startContext = highlighted.startContext,
            highlight = highlighted.highlight,
            endContext = highlighted.endContext,
        )

        if (errorLevel == ErrorLevel.IMMEDIATE) throw error

        errors.add(error)
        return null
    }

    /**
     * sqlglot: Parser.validate_expression + Expression.error_messages — validates that
     * required args are present (None / empty list count as missing, false does not).
     */
    fun <E : Expression> validateExpression(expression: E): E {
        if (maxNodes > -1) {
            nodeCount += 1
            if (nodeCount > maxNodes) {
                raiseError("Maximum number of AST nodes ($maxNodes) exceeded")
            }
        }
        if (errorLevel != ErrorLevel.IGNORE) {
            for ((key, required) in expression.argTypes) {
                if (!required) continue
                val v = expression.args[key]
                if (v == null || (v is List<*> && v.isEmpty())) {
                    raiseError("Required keyword: '$key' missing for ${expression::class.simpleName}")
                }
            }
        }
        return expression
    }

    // sqlglot: Parser._try_parse
    protected fun <T> tryParse(parseMethod: () -> T?, retreat: kotlin.Boolean = false): T? {
        val startIndex = index
        val savedErrorLevel = errorLevel
        var result: T? = null

        errorLevel = ErrorLevel.IMMEDIATE
        try {
            result = parseMethod()
        } catch (_: ParseError) {
            result = null
        } finally {
            if (result == null || retreat) retreat(startIndex)
            errorLevel = savedErrorLevel
        }

        return result
    }

    // sqlglot: Parser.expression
    fun <E : Expression> expression(
        instance: E,
        token: Token? = null,
        comments: List<String>? = null,
    ): E {
        // sqlglot: instance.update_positions(token) — positions live in meta, which is
        // stripped for oracle comparisons; position tracking lands in a later phase.
        // NOTE: Python is `add_comments(comments) if comments else _add_comments(...)`,
        // i.e. an empty comments list falls through to the prev-token comments.
        if (!comments.isNullOrEmpty()) instance.addComments(comments) else addTokenComments(instance)
        if (!instance.isPrimitive) validateExpression(instance)
        return instance
    }

    // sqlglot: Parser.check_errors (WARN logging is dropped: no logger in scope yet)
    fun checkErrors() {
        if (errorLevel == ErrorLevel.RAISE && errors.isNotEmpty()) {
            throw ParseError(
                concatMessages(errors, maxErrors),
                errors = mergeErrors(errors),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------

    // sqlglot: Parser.parse
    fun parse(rawTokens: List<Token>, sql: String): List<Expression?> =
        parseInternal({ it.parseStatement() }, rawTokens, sql)

    // sqlglot: Parser._parse
    private fun parseInternal(
        parseMethod: (Parser) -> Expression?,
        rawTokens: List<Token>,
        sql: String?,
    ): List<Expression?> {
        reset()
        this.sql = sql ?: ""

        val total = rawTokens.size
        val newChunks = mutableListOf(mutableListOf<Token>())

        for ((i, token) in rawTokens.withIndex()) {
            if (token.tokenType == TokenType.SEMICOLON) {
                if (token.comments.isNotEmpty()) {
                    newChunks.add(mutableListOf(token))
                }
                if (i < total - 1) {
                    newChunks.add(mutableListOf())
                }
            } else {
                newChunks.last().add(token)
            }
        }

        chunks = newChunks

        return parseBatchStatements(parseMethod = parseMethod, sepFirstStatement = false)
    }

    // sqlglot: Parser._parse_batch_statements
    protected fun parseBatchStatements(
        parseMethod: (Parser) -> Expression?,
        sepFirstStatement: kotlin.Boolean = true,
    ): List<Expression?> {
        val expressions = mutableListOf<Expression?>()

        // Chunkification binds if/while statements with the first statement of the body
        if (sepFirstStatement) {
            match(TokenType.BEGIN)
            expressions.add(parseMethod(this))
        }

        val chunksLength = chunks.size
        while (chunkIndex < chunksLength) {
            advanceChunk()

            if (match(TokenType.ELSE, advance = false)) {
                return expressions
            }

            if (expressions.isNotEmpty() && !nextToken.exists && match(TokenType.END)) {
                // sqlglot: exp.EndStatement() — control-flow statements not ported.
                raiseError("END statements are not supported yet")
                continue
            }

            expressions.add(parseMethod(this))

            if (index < tokensSize) {
                raiseError("Invalid expression / Unexpected token")
            }

            checkErrors()
        }

        return expressions
    }

    // sqlglot: Parser._warn_unsupported (logging dropped: no logger in scope yet)
    protected fun warnUnsupported() {}

    // -----------------------------------------------------------------------
    // CSV / wrapped combinators
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_csv (Kotlin deviation: sep comes first so that parseMethod
    // can be passed as a trailing lambda)
    fun <T : kotlin.Any> parseCsv(
        sep: TokenType = TokenType.COMMA,
        parseMethod: () -> T?,
    ): MutableList<T> {
        var parseResult = parseMethod()
        val items = mutableListOf<T>()
        if (parseResult != null) items.add(parseResult)

        while (match(sep)) {
            if (parseResult is Expression) addTokenComments(parseResult)
            parseResult = parseMethod()
            if (parseResult != null) items.add(parseResult)
        }

        return items
    }

    // sqlglot: Parser._parse_wrapped
    fun <T> parseWrapped(parseMethod: () -> T, optional: kotlin.Boolean = false): T {
        val wrapped = match(TokenType.L_PAREN)
        if (!wrapped && !optional) raiseError("Expecting (")
        val parseResult = parseMethod()
        if (wrapped) matchRParen()
        return parseResult
    }

    // sqlglot: Parser._parse_wrapped_csv
    fun <T : kotlin.Any> parseWrappedCsv(
        parseMethod: () -> T?,
        sep: TokenType = TokenType.COMMA,
        optional: kotlin.Boolean = false,
    ): MutableList<T> = parseWrapped({ parseCsv(sep, parseMethod) }, optional)

    // sqlglot: Parser._parse_wrapped_id_vars
    fun parseWrappedIdVars(optional: kotlin.Boolean = false): MutableList<Expression> =
        parseWrappedCsv({ parseIdVar() }, optional = optional)

    // -----------------------------------------------------------------------
    // Expression ladder (sqlglot: _parse_expression .. _parse_unary)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_expression
    fun parseExpression(): Expression? = parseAlias(parseAssignment())

    // sqlglot: Parser._parse_expressions
    fun parseExpressions(): MutableList<Expression> = parseCsv { parseExpression() }

    // sqlglot: Parser._parse_assignment
    fun parseAssignment(): Expression? {
        var this_ = parseDisjunction()
        if (this_ == null && nextToken.tokenType in assignmentTable) {
            // This allows us to parse <non-identifier token> := <expr>
            advanceAny(ignoreReserved = true)
            this_ = expression(
                Column(args("this" to expression(Identifier(args("this" to prevToken.text)))))
            )
        }

        while (matchSet(assignmentTable.keys)) {
            if (this_ is Column && this_.parts.size == 1) {
                this_ = this_.thisArg as Expression
            }

            val comments = prevComments
            this_ = expression(
                assignmentTable.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseAssignment())
                ),
                comments = comments,
            )
        }

        return this_
    }

    // sqlglot: Parser._parse_disjunction
    fun parseDisjunction(): Expression? {
        var this_ = parseConjunction()
        while (matchSet(disjunction.keys)) {
            val comments = prevComments
            this_ = expression(
                disjunction.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseConjunction())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_conjunction
    fun parseConjunction(): Expression? {
        var this_ = parseEquality()
        while (matchSet(conjunction.keys)) {
            val comments = prevComments
            this_ = expression(
                conjunction.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseEquality())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_equality
    fun parseEquality(): Expression? {
        var this_ = parseComparison()
        while (matchSet(equality.keys)) {
            val comments = prevComments
            this_ = expression(
                equality.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseComparison())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_comparison
    fun parseComparison(): Expression? {
        var this_ = parseRange()
        while (matchSet(comparison.keys)) {
            val comments = prevComments
            this_ = expression(
                comparison.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseRange())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_range
    fun parseRange(this_: Expression? = null): Expression? {
        var current = this_ ?: parseBitwise()

        while (true) {
            val negate = match(TokenType.NOT)
            if (matchSet(rangeParsers.keys)) {
                val parsed = rangeParsers.getValue(prevToken.tokenType)(this, current)
                    ?: return current
                current = parsed
            } else if (match(TokenType.ISNULL) || (negate && match(TokenType.NULL))) {
                current = expression(Is(args("this" to current, "expression" to Null())))
            } else if (match(TokenType.NOTNULL)) {
                // Postgres supports ISNULL and NOTNULL for conditions.
                current = expression(Is(args("this" to current, "expression" to Null())))
                current = expression(Not(args("this" to current)))
            } else {
                if (negate) retreat(index - 1)
                break
            }

            if (negate) {
                current = negateRange(current)
                if (currToken.exists &&
                    (currToken.tokenType == TokenType.NOT || currToken.tokenType in rangeParsers)
                ) {
                    current = expression(Paren(args("this" to current)))
                }
            }
        }

        return current
    }

    // sqlglot: Parser._negate_range
    protected fun negateRange(this_: Expression?): Expression? {
        if (this_ == null) return this_

        val expr = if (this_ is Escape) this_.thisArg as Expression else this_
        if (expr is Like || expr is ILike) {
            expr.set("negate", true)
            return this_
        }

        return expression(Not(args("this" to this_)))
    }

    // sqlglot: Parser._parse_is
    fun parseIs(this_: Expression?): Expression? {
        val startIndex = index - 1
        val negate = match(TokenType.NOT)

        if (matchTextSeq("DISTINCT", "FROM")) {
            val factory: NodeFactory = if (negate) ::NullSafeEQ else ::NullSafeNEQ
            return expression(factory(args("this" to this_, "expression" to parseBitwise())))
        }

        // sqlglot: IS JSON predicate — exp.JSON not ported.
        val expr = parseNull() ?: parseBitwise()
        if (expr == null) {
            retreat(startIndex)
            return null
        }

        var result: Expression = expression(Is(args("this" to this_, "expression" to expr)))
        if (negate) result = expression(Not(args("this" to result)))
        return parseColumnOps(result)
    }

    // sqlglot: Parser._parse_in
    fun parseIn(this_: Expression?, alias: kotlin.Boolean = false): Expression {
        var result: Expression
        val unnest = parseUnnest(withAlias = false)
        if (unnest != null) {
            result = expression(In(args("this" to this_, "unnest" to unnest)))
        } else if (matchSet(setOf(TokenType.L_PAREN, TokenType.L_BRACKET))) {
            val matchedLParen = prevToken.tokenType == TokenType.L_PAREN
            val expressions = parseCsv { parseSelectOrExpression(alias = alias) }

            val query = expressions.singleOrNull()
            if (query != null && isQuery(query)) {
                result = expression(
                    In(args("this" to this_, "query" to subqueryOf(parseQueryModifiers(query))))
                )
            } else {
                result = expression(In(args("this" to this_, "expressions" to expressions)))
            }

            if (matchedLParen) {
                matchRParen(result)
            } else if (!match(TokenType.R_BRACKET, expression = result)) {
                raiseError("Expecting ]")
            }
        } else {
            result = expression(In(args("this" to this_, "field" to parseColumn())))
        }

        return result
    }

    // sqlglot: Parser._parse_between
    fun parseBetween(this_: Expression?): Expression {
        var symmetric: kotlin.Boolean? = null
        if (matchTextSeq("SYMMETRIC")) {
            symmetric = true
        } else if (matchTextSeq("ASYMMETRIC")) {
            symmetric = false
        }

        val low = parseBitwise()
        match(TokenType.AND)
        val high = parseBitwise()

        return expression(
            Between(args("this" to this_, "low" to low, "high" to high, "symmetric" to symmetric))
        )
    }

    // sqlglot: Parser._parse_escape
    fun parseEscape(this_: Expression?): Expression? {
        if (!match(TokenType.ESCAPE)) return this_
        return expression(Escape(args("this" to this_, "expression" to (parseString() ?: parseNull()))))
    }

    // sqlglot: Parser._parse_bitwise
    fun parseBitwise(): Expression? {
        var this_ = parseTerm()

        while (true) {
            if (matchSet(bitwise.keys)) {
                this_ = expression(
                    bitwise.getValue(prevToken.tokenType)(
                        args("this" to this_, "expression" to parseTerm())
                    )
                )
            } else if (dpipeIsStringConcat && match(TokenType.DPIPE)) {
                this_ = expression(
                    DPipe(
                        args(
                            "this" to this_,
                            "expression" to parseTerm(),
                            "safe" to !strictStringConcat,
                        )
                    )
                )
            } else if (match(TokenType.DQMARK)) {
                // sqlglot: exp.Coalesce(this, expressions=[...]) — Coalesce not ported.
                raiseError("The ?? operator is not supported yet")
            } else if (matchPair(TokenType.LT, TokenType.LT) || matchPair(TokenType.GT, TokenType.GT)) {
                // sqlglot: exp.BitwiseLeftShift / exp.BitwiseRightShift — not ported.
                raiseError("Bitwise shift operators are not supported yet")
            } else {
                break
            }
        }

        return this_
    }

    // sqlglot: Parser._parse_term
    fun parseTerm(): Expression? {
        var this_ = parseFactor()

        while (matchSet(term.keys)) {
            val factory = term.getValue(prevToken.tokenType)
            val comments = prevComments
            val expr = parseFactor()

            this_ = expression(factory(args("this" to this_, "expression" to expr)), comments = comments)

            if (this_ is Collate) {
                val collation = this_.expressionArg
                // Preserve collations such as pg_catalog."default" (Postgres) as columns,
                // otherwise fallback to Identifier / Var
                if (collation is Column && collation.parts.size == 1) {
                    val ident = collation.thisArg
                    if (ident is Identifier) {
                        this_.set(
                            "expression",
                            if (ident.quoted) ident else expression(Var(args("this" to ident.name))),
                        )
                    }
                }
            }
        }

        return this_
    }

    // sqlglot: Parser._parse_factor
    fun parseFactor(): Expression? {
        val parseMethod: () -> Expression? =
            if (exponent.isNotEmpty()) ::parseExponent else ::parseUnary
        var this_ = parseAtTimeZone(parseMethod())

        while (matchSet(factor.keys)) {
            val factorToken = prevToken.tokenType
            val factory = factor.getValue(factorToken)
            val comments = prevComments
            val expr = parseMethod()

            if (expr == null && factorToken == TokenType.DIV && prevToken.text.all { it.isLetter() }) {
                retreat(index - 1)
                return this_
            }

            this_ = expression(factory(args("this" to this_, "expression" to expr)), comments = comments)

            if (this_ is Div) {
                this_.set("typed", typedDivision)
                this_.set("safe", safeDivision)
            }
        }

        return this_
    }

    // sqlglot: Parser._parse_exponent
    fun parseExponent(): Expression? {
        var this_ = parseUnary()
        while (matchSet(exponent.keys)) {
            val comments = prevComments
            this_ = expression(
                exponent.getValue(prevToken.tokenType)(
                    args("this" to this_, "expression" to parseUnary())
                ),
                comments = comments,
            )
        }
        return this_
    }

    // sqlglot: Parser._parse_unary
    fun parseUnary(): Expression? {
        if (matchSet(unaryParsers.keys)) {
            return unaryParsers.getValue(prevToken.tokenType)(this)
        }
        return parseType()
    }

    // sqlglot: Parser._parse_at_time_zone — exp.AtTimeZone not ported; the AT TIME ZONE
    // text sequence is left unconsumed (raises when it actually appears).
    fun parseAtTimeZone(this_: Expression?): Expression? {
        if (!matchTextSeq("AT", "TIME", "ZONE")) return this_
        return raiseError("AT TIME ZONE is not supported yet")
    }

    // -----------------------------------------------------------------------
    // Statement layer (sqlglot: _parse_statement + the SELECT machinery)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_statement
    fun parseStatement(): Expression? {
        if (!currToken.exists) return null

        if (matchSet(statementParsers.keys)) {
            val comments = prevComments
            val stmt = statementParsers.getValue(prevToken.tokenType)(this)
            stmt.addComments(comments, prepend = true)
            return stmt
        }

        if (matchSet(tokenizerConfig.commands)) {
            return parseCommand()
        }

        if (matchTextSeq("WHILE")) {
            // sqlglot: Parser._parse_whileblock — control-flow statements not ported.
            return raiseError("WHILE blocks are not supported yet")
        }

        var expr = parseExpression()
        expr = if (expr != null) parseSetOperations(expr) else parseSelect()

        // TODO(pipe): Phase 4 — parser.py feeds a Subquery followed by PIPE_GT into
        // _parse_pipe_syntax_query here.

        return parseQueryModifiers(expr)
    }

    // sqlglot: Parser._parse_command
    fun parseCommand(): Expression {
        warnUnsupported()
        val comments = prevComments
        return expression(
            Command(args("this" to prevToken.text.uppercase(), "expression" to parseString())),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_as_command
    protected fun parseAsCommand(start: Token): Expression {
        while (currToken.exists) advance()
        val text = findSql(start, prevToken)
        val size = start.text.length
        warnUnsupported()
        return Command(args("this" to text.take(size), "expression" to text.drop(size)))
    }

    // sqlglot: Parser._parse_select + Parser._parse_select_query (merged: the pipe-syntax
    // handling that separates them is deferred — TODO(pipe): Phase 4).
    fun parseSelect(
        nested: kotlin.Boolean = false,
        table: kotlin.Boolean = false,
        parseSubqueryAlias: kotlin.Boolean = true,
        parseSetOperation: kotlin.Boolean = true,
    ): Expression? {
        val cte = parseWith()

        if (cte != null) {
            var this_ = parseStatement()

            if (this_ == null) {
                raiseError("Failed to parse any statement following CTE")
                return cte
            }

            while (this_ is Subquery && this_.isWrapper) {
                this_ = this_.thisArg as Expression
            }

            if ("with_" in this_.argTypes) {
                val innerCte = this_.args["with_"] as? With
                if (innerCte != null) {
                    cte.set("expressions", cte.expressionsArg + innerCte.expressionsArg)
                    if (innerCte.args["recursive"] == true) {
                        cte.set("recursive", true)
                    }
                }
                this_.set("with_", cte)
            } else {
                raiseError("${this_.key} does not support CTE")
                this_ = cte
            }

            return this_
        }

        // duckdb supports leading with FROM x
        var from = if (match(TokenType.FROM, advance = false)) parseFrom(joins = true) else null

        var this_: Expression?

        if (match(TokenType.SELECT)) {
            val comments = prevComments

            val hint = parseHint()

            var all: kotlin.Boolean
            var matchedDistinct: kotlin.Boolean
            if (nextToken.exists && nextToken.tokenType != TokenType.DOT) {
                all = match(TokenType.ALL)
                matchedDistinct = matchSet(distinctTokens)
            } else {
                all = false
                matchedDistinct = false
            }

            val kind = if (match(TokenType.ALIAS) && matchTexts(setOf("STRUCT", "VALUE"))) {
                prevToken.text.uppercase()
            } else {
                null
            }

            var distinct: Expression? = if (matchedDistinct) parseDistinctExpression() else null

            val opMods = mutableListOf<Expression>()
            while (currToken.exists && matchTexts(operationModifiers)) {
                opMods.add(Var(args("this" to prevToken.text.uppercase())))
            }

            val limit = parseLimit(top = true)

            // Some dialects (e.g. Redshift, T-SQL) allow SELECT TOP N DISTINCT ...
            if (limit != null && !matchedDistinct && !all) {
                matchedDistinct = matchSet(distinctTokens)
                if (matchedDistinct) {
                    distinct = parseDistinctExpression()
                } else {
                    all = match(TokenType.ALL)
                }
            }

            if (all && distinct != null) {
                raiseError("Cannot specify both ALL and DISTINCT after SELECT")
            }

            val projections = parseProjections()

            this_ = expression(
                Select(
                    args(
                        "kind" to kind,
                        "hint" to hint,
                        "distinct" to distinct,
                        "expressions" to projections,
                        "limit" to limit,
                        "exclude" to null,
                        "operation_modifiers" to opMods.ifEmpty { null },
                    )
                )
            )
            this_.comments = comments.toMutableList()

            val into = parseInto()
            if (into != null) this_.set("into", into)

            if (from == null) from = parseFrom()

            if (from != null) this_.set("from_", from)

            this_ = parseQueryModifiers(this_)
        } else if ((table || nested) && match(TokenType.L_PAREN)) {
            val comments = prevComments
            val wrapped = parseWrappedSelect(table = table)

            wrapped?.addComments(comments, prepend = true)

            // We return early here so that the UNION isn't attached to the subquery by the
            // following call to _parse_set_operations, but instead becomes the parent node
            matchRParen()
            return parseSubquery(wrapped, parseAlias = parseSubqueryAlias)
        } else if (match(TokenType.VALUES, advance = false)) {
            // sqlglot: self._parse_derived_table_values() — exp.Values not ported.
            this_ = raiseError("VALUES expressions are not supported yet")
        } else if (from != null) {
            // sqlglot: exp.select("*").from_(from_.this, copy=False)
            this_ = expression(
                Select(
                    args(
                        "expressions" to mutableListOf<Expression>(Star()),
                        "from_" to expression(From(args("this" to from.thisArg))),
                    )
                )
            )
            this_ = parseQueryModifiers(this_)
        } else if (match(TokenType.SUMMARIZE)) {
            // sqlglot: exp.Summarize — not ported.
            this_ = raiseError("SUMMARIZE is not supported yet")
        } else if (match(TokenType.DESCRIBE)) {
            // sqlglot: Parser._parse_describe — not ported.
            this_ = raiseError("DESCRIBE is not supported yet")
        } else {
            this_ = null
        }

        return if (parseSetOperation) parseSetOperations(this_) else this_
    }

    /** sqlglot: `exp.Distinct(on=self._parse_value(values=False) if ... else None)`. */
    private fun parseDistinctExpression(): Expression {
        val on: Expression? = if (match(TokenType.ON)) {
            // sqlglot: self._parse_value(values=False) — exp.Values not ported.
            raiseError("DISTINCT ON is not supported yet")
        } else {
            null
        }
        return expression(Distinct(args("on" to on)))
    }

    // sqlglot: Parser._parse_wrapped_select
    protected fun parseWrappedSelect(table: kotlin.Boolean = false): Expression? {
        var this_: Expression?
        if (matchSet(setOf(TokenType.PIVOT, TokenType.UNPIVOT))) {
            // sqlglot: Parser._parse_simplified_pivot — exp.Pivot not ported.
            this_ = raiseError("PIVOT/UNPIVOT is not supported yet")
        } else if (match(TokenType.FROM)) {
            val from = parseFrom(joins = true, skipFromToken = true)
            // Support parentheses for duckdb FROM-first syntax
            val select = parseSelect()
            if (select != null) {
                if (select.args["from_"] == null) select.set("from_", from)
                this_ = select
            } else {
                this_ = expression(
                    Select(
                        args(
                            "expressions" to mutableListOf<Expression>(Star()),
                            "from_" to from,
                        )
                    )
                )
                this_ = parseQueryModifiers(parseSetOperations(this_))
            }
        } else {
            this_ = if (table) {
                parseTable()
            } else {
                parseSelect(nested = true, parseSetOperation = false)
            }

            // sqlglot: exp.Values -> exp.Table rewriting — exp.Values not ported.

            this_ = parseQueryModifiers(parseSetOperations(this_))
        }

        return this_
    }

    // sqlglot: Parser._parse_projections (the exclude list is not ported — always null)
    protected fun parseProjections(): MutableList<Expression> = parseExpressions()

    // sqlglot: Parser._parse_with
    fun parseWith(skipWithToken: kotlin.Boolean = false): With? {
        if (!skipWithToken && !match(TokenType.WITH)) return null

        val comments = prevComments
        val recursive = match(TokenType.RECURSIVE)

        var lastComments: List<String>? = null
        val expressions = mutableListOf<Expression>()
        while (true) {
            val cte = parseCte()
            if (cte is CTE) {
                expressions.add(cte)
                if (!lastComments.isNullOrEmpty()) cte.addComments(lastComments)
            }

            if (!match(TokenType.COMMA) && !match(TokenType.WITH)) {
                break
            } else {
                match(TokenType.WITH)
            }

            lastComments = prevComments
        }

        return expression(
            With(
                args(
                    "expressions" to expressions,
                    "recursive" to if (recursive) true else null,
                    "search" to parseRecursiveWithSearch(),
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_recursive_with_search — exp.RecursiveWithSearch not ported.
    // Faithful quirk: SEARCH is consumed even when no search kind follows.
    protected fun parseRecursiveWithSearch(): Expression? {
        matchTextSeq("SEARCH")

        if (!matchTexts(recursiveCteSearchKind)) return null

        return raiseError("WITH ... SEARCH is not supported yet")
    }

    // sqlglot: Parser._parse_cte
    fun parseCte(): Expression? {
        val startIndex = index

        val alias = parseTableAlias(idVarTokens)
        if (alias == null || alias.thisArg == null) {
            raiseError("Expected CTE to have alias")
        }

        val keyExpressions = if (matchTextSeq("USING", "KEY")) parseWrappedIdVars() else null

        if (!match(TokenType.ALIAS) && !optionalAliasTokenCte) {
            retreat(startIndex)
            return null
        }

        val comments = prevComments

        val materialized: kotlin.Boolean? = when {
            matchTextSeq("NOT", "MATERIALIZED") -> false
            matchTextSeq("MATERIALIZED") -> true
            else -> null
        }

        val cte = expression(
            CTE(
                args(
                    "this" to parseWrapped({ parseStatement() }),
                    "alias" to alias,
                    "materialized" to materialized,
                    "key_expressions" to keyExpressions,
                )
            ),
            comments = comments,
        )

        // sqlglot: exp.Values CTE-body rewriting — exp.Values not ported.

        return cte
    }

    // sqlglot: Parser._parse_table_alias
    fun parseTableAlias(aliasTokens: Collection<TokenType>? = null): TableAlias? {
        // In some dialects, LIMIT and OFFSET can act as both identifiers and keywords (clauses)
        // so this section tries to parse the clause version and if it fails, it treats the token
        // as an identifier (alias)
        if (canParseLimitOrOffset()) return null

        val anyToken = match(TokenType.ALIAS)
        val alias = parseIdVar(anyToken = anyToken, tokens = aliasTokens ?: tableAliasTokens)
            ?: parseStringAsIdentifier()

        val startIndex = index
        var columns: MutableList<Expression>? = null
        if (match(TokenType.L_PAREN)) {
            columns = parseCsv { parseFunctionParameter() }
            if (columns.isNotEmpty()) matchRParen() else retreat(startIndex)
        }

        if (alias == null && columns.isNullOrEmpty()) return null

        val tableAlias = expression(TableAlias(args("this" to alias, "columns" to columns)))

        // We bubble up comments from the Identifier to the TableAlias
        if (alias is Identifier) tableAlias.addComments(alias.popComments())

        return tableAlias
    }

    // sqlglot: Parser._parse_function_parameter — exp.ColumnDef not ported; for plain
    // alias-column lists Python's _parse_column_def passes the id_var through unchanged.
    protected fun parseFunctionParameter(): Expression? = parseIdVar()

    // sqlglot: Parser._parse_subquery
    fun parseSubquery(this_: Expression?, parseAlias: kotlin.Boolean = true): Expression? {
        if (this_ == null) return null

        return expression(
            Subquery(
                args(
                    "this" to this_,
                    "pivots" to parsePivots(),
                    "alias" to if (parseAlias) parseTableAlias() else null,
                    "sample" to parseTableSample(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_query_modifiers
    fun parseQueryModifiers(this_: Expression?): Expression? {
        // sqlglot: Parser.MODIFIABLES = (exp.Query, exp.Table, exp.TableFromRows, exp.Values)
        if (this_ != null && (isQuery(this_) || this_ is Table)) {
            for (join in parseJoins()) this_.append("joins", join)
            while (true) {
                val lateral = parseLateral() ?: break
                this_.append("laterals", lateral)
            }

            while (true) {
                if (matchSet(queryModifierParsers.keys, advance = false)) {
                    val modifierToken = currToken
                    val (key, value) = queryModifierParsers.getValue(modifierToken.tokenType)(this)

                    if (value != null) {
                        if (this_.args[key] != null) {
                            raiseError(
                                "Found multiple '${modifierToken.text.uppercase()}' clauses",
                                token = modifierToken,
                            )
                        }

                        this_.set(key, value)
                        if (key == "limit") {
                            val limitNode = value as Expression
                            val offset = limitNode.args["offset"]
                            limitNode.set("offset", null)

                            if (offset != null) {
                                val offsetNode = Offset(args("expression" to offset))
                                this_.set("offset", offsetNode)

                                val limitByExpressions = limitNode.expressionsArg
                                limitNode.set("expressions", null)
                                offsetNode.set("expressions", limitByExpressions)
                            }
                        }
                        continue
                    }
                }
                break
            }
        }

        // sqlglot: SUPPORTS_IMPLICIT_UNNEST — base: False.

        return this_
    }

    // sqlglot: Parser._parse_hint — exp.Hint not ported; the base tokenizer never emits
    // TokenType.HINT, so the gate below cannot fire for the ported dialects.
    protected fun parseHint(): Expression? {
        if (match(TokenType.HINT) && prevComments.isNotEmpty()) {
            return raiseError("Optimizer hints are not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_into — exp.Into not ported.
    protected fun parseInto(): Expression? {
        if (!match(TokenType.INTO)) return null
        return raiseError("SELECT INTO is not supported yet")
    }

    // sqlglot: Parser._parse_from
    fun parseFrom(joins: kotlin.Boolean = false, skipFromToken: kotlin.Boolean = false): From? {
        if (!skipFromToken && !match(TokenType.FROM)) return null

        val comments = prevComments
        return expression(
            From(args("this" to parseTable(joins = joins))),
            comments = comments,
        )
    }

    // -----------------------------------------------------------------------
    // Joins / tables
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_join_parts
    protected fun parseJoinParts(): Triple<Token?, Token?, Token?> = Triple(
        if (matchSet(joinMethods)) prevToken else null,
        if (matchSet(joinSides)) prevToken else null,
        if (matchSet(joinKinds)) prevToken else null,
    )

    // sqlglot: Parser._parse_using_identifiers
    protected fun parseUsingIdentifiers(): MutableList<Expression> {
        // sqlglot: local _parse_column_as_identifier
        fun parseColumnAsIdentifier(): Expression? {
            val this_ = parseColumn()
            return if (this_ is Column) this_.thisArg as Expression? else this_
        }

        return parseWrappedCsv({ parseColumnAsIdentifier() }, optional = true)
    }

    // sqlglot: Parser._parse_join
    fun parseJoin(
        skipJoinToken: kotlin.Boolean = false,
        parseBracket: kotlin.Boolean = false,
        aliasTokens: Collection<TokenType>? = null,
    ): Expression? {
        if (match(TokenType.COMMA)) {
            val table = tryParse({ parseTable(aliasTokens = aliasTokens) })
            val crossJoin = if (table != null) expression(Join(args("this" to table))) else null

            if (crossJoin != null && joinsHaveEqualPrecedence) crossJoin.set("kind", "CROSS")

            return crossJoin
        }

        val startIndex = index
        var (method, side, kind) = parseJoinParts()
        val directed = matchTextSeq("DIRECTED")
        val hint = if (matchTexts(joinHints)) prevToken.text else null
        val join = match(TokenType.JOIN) ||
            (kind != null && kind.tokenType == TokenType.STRAIGHT_JOIN)
        val joinComments = prevComments

        if (!skipJoinToken && !join) {
            retreat(startIndex)
            kind = null
            method = null
            side = null
        }

        val outerApply = matchPair(TokenType.OUTER, TokenType.APPLY, advance = false)
        val crossApply = matchPair(TokenType.CROSS, TokenType.APPLY, advance = false)

        if (!skipJoinToken && !join && !outerApply && !crossApply) return null

        val kwargs = LinkedHashMap<String, kotlin.Any?>()
        kwargs["this"] = parseTable(parseBracket = parseBracket, aliasTokens = aliasTokens)

        if (kind != null && kind.tokenType == TokenType.ARRAY && match(TokenType.COMMA)) {
            kwargs["expressions"] = parseCsv {
                parseTable(parseBracket = parseBracket, aliasTokens = aliasTokens)
            }
        }

        if (method != null) kwargs["method"] = method.text.uppercase()
        if (side != null) kwargs["side"] = side.text.uppercase()
        if (kind != null) kwargs["kind"] = kind.text.uppercase()
        if (hint != null) kwargs["hint"] = hint

        if (match(TokenType.MATCH_CONDITION)) {
            kwargs["match_condition"] = parseWrapped({ parseComparison() })
        }

        if (match(TokenType.ON)) {
            kwargs["on"] = parseDisjunction()
        } else if (match(TokenType.USING)) {
            kwargs["using"] = parseUsingIdentifiers()
        } else if (
            method == null &&
            !(outerApply || crossApply) &&
            // sqlglot: `not isinstance(kwargs["this"], exp.Unnest)` — exp.Unnest not ported
            !(kind != null && (kind.tokenType == TokenType.CROSS || kind.tokenType == TokenType.ARRAY))
        ) {
            val innerIndex = index
            var joins: MutableList<Expression>? = parseJoins(aliasTokens = aliasTokens).toMutableList()

            if (!joins.isNullOrEmpty() && match(TokenType.ON)) {
                kwargs["on"] = parseDisjunction()
            } else if (!joins.isNullOrEmpty() && match(TokenType.USING)) {
                kwargs["using"] = parseUsingIdentifiers()
            } else {
                joins = null
                retreat(innerIndex)
            }

            (kwargs["this"] as? Expression)?.set("joins", if (joins.isNullOrEmpty()) null else joins)
        }

        kwargs["pivots"] = parsePivots()

        val tokenComments = mutableListOf<String>()
        for (token in listOfNotNull(method, side, kind)) tokenComments.addAll(token.comments)
        val comments = joinComments + tokenComments

        if (addJoinOnTrue &&
            kwargs["on"] == null &&
            kwargs["using"] == null &&
            kwargs["method"] == null &&
            kwargs["kind"] in setOf(null, "INNER", "OUTER")
        ) {
            // sqlglot: exp.true()
            kwargs["on"] = Boolean(args("this" to true))
        }

        if (directed) kwargs["directed"] = true

        return expression(Join(kwargs), comments = comments)
    }

    // sqlglot: Parser._parse_joins
    fun parseJoins(aliasTokens: Collection<TokenType>? = null): List<Expression> {
        val joins = mutableListOf<Expression>()
        while (true) {
            val join = parseJoin(aliasTokens = aliasTokens) ?: break
            joins.add(join)
        }
        return joins
    }

    // sqlglot: Parser._parse_table_part
    protected fun parseTablePart(schema: kotlin.Boolean = false): Expression? =
        (if (!schema) parseFunction(optionalParens = false) else null)
            ?: parseIdVar(anyToken = false)
            ?: parseStringAsIdentifier()
            ?: parsePlaceholder()

    // sqlglot: Parser._parse_table_parts_fast
    protected fun parseTablePartsFast(): Expression? {
        val startIndex = index
        var parts: MutableList<Expression>? = null
        var allComments: MutableList<String>? = null

        while (matchSet(identifierTokens)) {
            val token = prevToken
            val comments = prevComments

            val hasDot = match(TokenType.DOT)
            val currTt = currToken.tokenType

            if (!hasDot) {
                if (currTt in tablePostfixTokens) {
                    retreat(startIndex)
                    return null
                }
            } else if (currTt !in identifierTokens) {
                retreat(startIndex)
                return null
            }

            if (parts == null) parts = mutableListOf()

            if (comments.isNotEmpty()) {
                if (allComments == null) allComments = mutableListOf()
                allComments.addAll(comments)
                prevComments = emptyList()
            }

            parts.add(
                expression(
                    Identifier(
                        args("this" to token.text, "quoted" to (token.tokenType == TokenType.IDENTIFIER))
                    ),
                    token,
                )
            )

            if (!hasDot) break
        }

        if (parts == null) return null

        val n = parts.size

        val table: Table = when {
            n == 1 -> Table(args("this" to parts[0]))
            n == 2 -> Table(args("this" to parts[1], "db" to parts[0]))
            else -> {
                var this_: Expression = parts[2]
                for (i in 3 until n) {
                    this_ = Dot(args("this" to this_, "expression" to parts[i]))
                }
                Table(args("this" to this_, "db" to parts[1], "catalog" to parts[0]))
            }
        }

        if (!allComments.isNullOrEmpty()) table.addComments(allComments)
        return table
    }

    // sqlglot: Parser._parse_table_parts
    fun parseTableParts(
        schema: kotlin.Boolean = false,
        isDbReference: kotlin.Boolean = false,
        wildcard: kotlin.Boolean = false,
        fast: kotlin.Boolean = false,
    ): Expression? {
        if (fast) return parseTablePartsFast()

        var catalog: kotlin.Any? = null
        var db: kotlin.Any? = null
        var table: kotlin.Any? = parseTablePart(schema = schema)

        while (match(TokenType.DOT)) {
            if (catalog != null) {
                // This allows nesting the table in arbitrarily many dot expressions if needed
                table = expression(
                    Dot(args("this" to table, "expression" to parseTablePart(schema = schema)))
                )
            } else {
                catalog = db
                db = table
                // "" used for tsql FROM a..b case
                table = parseTablePart(schema = schema) ?: ""
            }
        }

        if (wildcard && isConnected() && (table is Identifier || table == null) && match(TokenType.STAR)) {
            if (table is Identifier) {
                table.args["this"] = (table.args["this"] as String) + "*"
            } else {
                table = Identifier(args("this" to "*"))
            }
        }

        if (isDbReference) {
            catalog = db
            db = table
            table = null
        }

        if (table == null && !isDbReference) raiseError("Expected table name but got $currToken")
        if (db == null && isDbReference) raiseError("Expected database name but got $currToken")

        val tableExp = expression(Table(args("this" to table, "db" to db, "catalog" to catalog)))

        // Bubble up comments from identifier parts to the Table
        val comments = mutableListOf<String>()
        for (part in tableExp.parts) comments.addAll(part.popComments())
        if (comments.isNotEmpty()) tableExp.addComments(comments)

        val changes = parseChanges()
        if (changes != null) tableExp.set("changes", changes)

        val atBefore = parseHistoricalData()
        if (atBefore != null) tableExp.set("when", atBefore)

        val pivots = parsePivots()
        if (pivots != null) tableExp.set("pivots", pivots)

        return tableExp
    }

    // sqlglot: Parser._parse_table
    fun parseTable(
        schema: kotlin.Boolean = false,
        joins: kotlin.Boolean = false,
        aliasTokens: Collection<TokenType>? = null,
        parseBracket: kotlin.Boolean = false,
        isDbReference: kotlin.Boolean = false,
    ): Expression? {
        if (!schema && !isDbReference && !joins) {
            val startIndex = index
            val table = parseTableParts(fast = true)

            if (table != null) {
                val currTt = currToken.tokenType
                val nextTt = nextToken.tokenType

                val fastTerminators = tableTerminators

                // only return the table if we're sure there are no other operators
                // MATCH_CONDITION is a special case because it accepts any alias before it like LIMIT
                if (currTt in fastTerminators && nextTt != TokenType.MATCH_CONDITION) {
                    return table
                }

                val postfixTokens = tablePostfixTokens

                if (currTt !in postfixTokens && nextTt !in postfixTokens) {
                    val alias = parseTableAlias(aliasTokens = aliasTokens ?: tableAliasTokens)
                    if (alias != null) table.set("alias", alias)

                    if (currToken.tokenType in fastTerminators) return table
                }

                retreat(startIndex)
            }
        }

        val stream = parseStream()
        if (stream != null) return stream

        val lateral = parseLateral()
        if (lateral != null) return lateral

        val unnest = parseUnnest()
        if (unnest != null) return unnest

        val values = parseDerivedTableValues()
        if (values != null) return values

        val subquery = parseSelect(table = true)
        if (subquery != null) {
            if (subquery.args["pivots"] == null) subquery.set("pivots", parsePivots())
            if (joins) {
                for (join in parseJoins()) subquery.append("joins", join)
            }
            return subquery
        }

        var bracketTable: Expression? = null
        if (parseBracket) {
            val bracket = this.parseBracket(null)
            if (bracket != null) bracketTable = expression(Table(args("this" to bracket)))
        }

        val rowsFrom: Expression? = if (matchTextSeq("ROWS", "FROM", advance = false)) {
            // sqlglot: exp.Table(rows_from=...) — not ported.
            raiseError("ROWS FROM is not supported yet")
        } else {
            null
        }

        val only = match(TokenType.ONLY)

        val this_: Expression = bracketTable
            ?: rowsFrom
            ?: this.parseBracket(parseTableParts(schema = schema, isDbReference = isDbReference))
            ?: return null

        if (only) this_.set("only", true)

        // Postgres supports a wildcard (table) suffix operator, which is a no-op in this context
        match(TokenType.STAR)

        // sqlglot: SUPPORTS_PARTITION_SELECTION / parse_partition — base: False, not ported.

        if (schema) {
            // sqlglot: Parser._parse_schema — exp.Schema not ported.
            return raiseError("Schema parsing is not supported yet")
        }

        if (aliasPostVersion) this_.set("version", parseVersion())

        if (aliasPostTablesample) this_.set("sample", parseTableSample())

        val alias = parseTableAlias(aliasTokens = aliasTokens ?: tableAliasTokens)
        if (alias != null) this_.set("alias", alias)

        if (match(TokenType.INDEXED_BY)) {
            this_.set("indexed", parseTableParts())
        } else if (matchTextSeq("NOT", "INDEXED")) {
            this_.set("indexed", false)
        }

        if (this_ is Table && matchTextSeq("AT")) {
            // sqlglot: exp.AtIndex — not ported.
            return raiseError("AT index expressions are not supported yet")
        }

        this_.set("hints", parseTableHints())

        if (this_.args["pivots"] == null) this_.set("pivots", parsePivots())

        if (!aliasPostTablesample) this_.set("sample", parseTableSample())

        if (!aliasPostVersion) this_.set("version", parseVersion())

        if (joins) {
            for (join in parseJoins(aliasTokens = aliasTokens)) this_.append("joins", join)
        }

        if (matchPair(TokenType.WITH, TokenType.ORDINALITY)) {
            this_.set("ordinality", true)
            this_.set("alias", parseTableAlias())
        }

        return this_
    }

    // sqlglot: Parser._parse_stream — exp.Stream not ported; the retreat keeps STREAM
    // usable as a plain identifier, like Python.
    protected fun parseStream(): Expression? {
        val startIndex = index
        if (match(TokenType.STREAM)) {
            val this_ = tryParse({ parseTable() })
            if (this_ != null) {
                return raiseError("STREAM tables are not supported yet")
            }
            retreat(startIndex)
        }
        return null
    }

    // sqlglot: Parser._parse_lateral — exp.Lateral not ported.
    protected fun parseLateral(): Expression? {
        if (matchPair(TokenType.CROSS, TokenType.APPLY, advance = false) ||
            matchPair(TokenType.OUTER, TokenType.APPLY, advance = false) ||
            match(TokenType.LATERAL, advance = false)
        ) {
            return raiseError("LATERAL / APPLY is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_unnest — exp.Unnest not ported (Python raises "Expecting ("
    // for a bare UNNEST too; anything else returns null, matching Python's gate).
    fun parseUnnest(withAlias: kotlin.Boolean = true): Expression? {
        if (!match(TokenType.UNNEST, advance = false)) return null
        return raiseError("UNNEST is not supported yet")
    }

    // sqlglot: Parser._parse_derived_table_values — exp.Values not ported.
    protected fun parseDerivedTableValues(): Expression? {
        val isDerived = matchPair(TokenType.L_PAREN, TokenType.VALUES, advance = false)
        if (!isDerived &&
            !matchTextSeq("VALUES", advance = false) &&
            !matchTextSeq("FORMAT", "VALUES", advance = false)
        ) {
            return null
        }
        return raiseError("VALUES expressions are not supported yet")
    }

    // sqlglot: Parser._parse_version — exp.Version not ported.
    protected fun parseVersion(): Expression? {
        if (match(TokenType.TIMESTAMP_SNAPSHOT, advance = false) ||
            match(TokenType.VERSION_SNAPSHOT, advance = false)
        ) {
            return raiseError("FOR TIMESTAMP/VERSION AS OF is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_table_sample — exp.TableSample not ported.
    protected fun parseTableSample(): Expression? {
        if (match(TokenType.TABLE_SAMPLE, advance = false)) {
            return raiseError("TABLESAMPLE is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_table_hints — exp.WithTableHint / exp.IndexTableHint not ported.
    protected fun parseTableHints(): Expression? {
        if (matchPair(TokenType.WITH, TokenType.L_PAREN, advance = false)) {
            return raiseError("Table hints are not supported yet")
        }
        // sqlglot: MySQL index hints (USE/FORCE/IGNORE INDEX ...) — TABLE_INDEX_HINT_TOKENS
        // never appear in the ported dialects' post-table position.
        return null
    }

    // sqlglot: Parser._parse_changes — exp.Changes not ported (Snowflake CHANGES clause).
    protected fun parseChanges(): Expression? {
        if (matchTextSeq("CHANGES", "(", "INFORMATION", "=>", advance = false)) {
            return raiseError("CHANGES(...) is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_historical_data — exp.HistoricalData not ported (Snowflake
    // AT/BEFORE); the retreat keeps AT/BEFORE usable as identifiers, like Python.
    protected fun parseHistoricalData(): Expression? {
        val startIndex = index
        if (matchTexts(historicalDataPrefix)) {
            val kind = match(TokenType.L_PAREN) && matchTexts(historicalDataKind)
            val expr = if (kind && match(TokenType.FARROW)) parseBitwise() else null

            if (expr != null) {
                return raiseError("AT/BEFORE historical clauses are not supported yet")
            }
            retreat(startIndex)
        }
        return null
    }

    // sqlglot: Parser._parse_pivots — exp.Pivot not ported.
    protected fun parsePivots(): Expression? {
        if (currToken.tokenType == TokenType.PIVOT || currToken.tokenType == TokenType.UNPIVOT) {
            return raiseError("PIVOT/UNPIVOT is not supported yet")
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Clause layer (sqlglot: _parse_where .. _parse_offset, set operations)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_where
    fun parseWhere(skipWhereToken: kotlin.Boolean = false): Expression? {
        if (!skipWhereToken && !match(TokenType.WHERE)) return null

        val comments = prevComments
        return expression(
            Where(args("this" to parseDisjunction())),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_group
    fun parseGroup(skipGroupByToken: kotlin.Boolean = false): Expression? {
        if (!skipGroupByToken && !match(TokenType.GROUP_BY)) return null
        val comments = prevComments

        // sqlglot: elements = defaultdict(list) — keys appear in first-touch order
        val elements = LinkedHashMap<String, kotlin.Any?>()

        if (match(TokenType.ALL)) {
            elements["all"] = true
        } else if (match(TokenType.DISTINCT)) {
            elements["all"] = false
        }

        if (matchSet(queryModifierTokens, advance = false)) {
            return expression(Group(elements), comments = comments)
        }

        val expressions = mutableListOf<Expression>()

        while (true) {
            val iterationIndex = index

            elements["expressions"] = expressions
            expressions.addAll(
                parseCsv {
                    if (matchSet(setOf(TokenType.CUBE, TokenType.ROLLUP), advance = false)) {
                        null
                    } else {
                        parseDisjunction()
                    }
                }
            )

            val beforeWithIndex = index
            val withPrefix = match(TokenType.WITH)

            if (parseCubeOrRollup(withPrefix = withPrefix) != null) {
                // unreachable — parseCubeOrRollup raises (exp.Cube/exp.Rollup not ported)
            } else if (parseGroupingSets() != null) {
                // unreachable — parseGroupingSets raises (exp.GroupingSets not ported)
            } else if (matchTextSeq("TOTALS")) {
                elements["totals"] = true
            }

            if (index in beforeWithIndex..(beforeWithIndex + 1)) {
                retreat(beforeWithIndex)
                break
            }

            if (iterationIndex == index) break
        }

        return expression(Group(elements), comments = comments)
    }

    // sqlglot: Parser._parse_cube_or_rollup — exp.Cube/exp.Rollup not ported.
    protected fun parseCubeOrRollup(withPrefix: kotlin.Boolean = false): Expression? {
        if (match(TokenType.CUBE, advance = false) || match(TokenType.ROLLUP, advance = false)) {
            return raiseError("CUBE/ROLLUP is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_grouping_sets — exp.GroupingSets not ported.
    protected fun parseGroupingSets(): Expression? {
        if (match(TokenType.GROUPING_SETS, advance = false)) {
            return raiseError("GROUPING SETS is not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_having
    fun parseHaving(skipHavingToken: kotlin.Boolean = false): Expression? {
        if (!skipHavingToken && !match(TokenType.HAVING)) return null
        val comments = prevComments
        return expression(
            Having(args("this" to parseDisjunction())),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_order
    fun parseOrder(this_: Expression? = null, skipOrderToken: kotlin.Boolean = false): Expression? {
        var siblings: kotlin.Boolean? = null
        if (!skipOrderToken && !match(TokenType.ORDER_BY)) {
            if (!match(TokenType.ORDER_SIBLINGS_BY)) return this_

            siblings = true
        }

        val comments = prevComments
        return expression(
            Order(
                args(
                    "this" to this_,
                    "expressions" to parseCsv { parseOrdered() },
                    "siblings" to siblings,
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_ordered
    fun parseOrdered(parseMethod: (() -> Expression?)? = null): Expression? {
        var this_ = if (parseMethod != null) parseMethod() else parseDisjunction()
        if (this_ == null) return null

        if (this_.name.uppercase() == "ALL" && supportsOrderByAll) {
            this_ = Var(args("this" to "ALL"))
        }

        val asc = match(TokenType.ASC)
        val desc: kotlin.Boolean? = if (match(TokenType.DESC)) true else (if (asc) false else null)

        val isNullsFirst = matchTextSeq("NULLS", "FIRST")
        val isNullsLast = matchTextSeq("NULLS", "LAST")

        var nullsFirst = isNullsFirst
        val explicitlyNullOrdered = isNullsFirst || isNullsLast

        if (!explicitlyNullOrdered &&
            ((desc != true && nullOrdering == "nulls_are_small") ||
                (desc == true && nullOrdering != "nulls_are_small")) &&
            nullOrdering != "nulls_are_last"
        ) {
            nullsFirst = true
        }

        if (matchTextSeq("WITH", "FILL")) {
            // sqlglot: exp.WithFill — not ported.
            return raiseError("WITH FILL is not supported yet")
        }

        return expression(
            Ordered(
                args("this" to this_, "desc" to desc, "nulls_first" to nullsFirst, "with_fill" to null)
            )
        )
    }

    // sqlglot: Parser._parse_limit_options — exp.LimitOptions not ported.
    protected fun parseLimitOptions(): Expression? {
        val percent = matchSet(setOf(TokenType.PERCENT, TokenType.MOD))
        val rows = matchSet(setOf(TokenType.ROW, TokenType.ROWS))
        matchTextSeq("ONLY")
        val withTies = matchTextSeq("WITH", "TIES")

        if (!(percent || rows || withTies)) return null

        return raiseError("LIMIT/FETCH options (PERCENT/ROW(S)/WITH TIES) are not supported yet")
    }

    // sqlglot: Parser._parse_limit
    fun parseLimit(
        this_: Expression? = null,
        top: kotlin.Boolean = false,
        skipLimitToken: kotlin.Boolean = false,
    ): Expression? {
        if (skipLimitToken || match(if (top) TokenType.TOP else TokenType.LIMIT)) {
            val comments = prevComments
            var expr: Expression?
            if (top) {
                val limitParen = match(TokenType.L_PAREN)
                expr = if (limitParen) (parseTerm() ?: parseSelect()) else parseNumber()

                if (limitParen) matchRParen()
            } else {
                if (supportsLimitAll && match(TokenType.ALL)) return this_

                // Parsing LIMIT x% (i.e x PERCENT) as a term leads to an error, since
                // we try to build an exp.Mod expr. For that matter, we backtrack and instead
                // consume the factor plus parse the percentage separately
                val startIndex = index
                expr = tryParse({ parseTerm() })
                if (expr is Mod) {
                    retreat(startIndex)
                    expr = parseFactor()
                } else if (expr == null) {
                    expr = parseFactor()
                }
            }
            val limitOptions = parseLimitOptions()

            var offset: Expression? = null
            if (match(TokenType.COMMA)) {
                offset = expr
                expr = parseTerm()
            }

            val limitExp = expression(
                Limit(
                    args(
                        "this" to this_,
                        "expression" to expr,
                        "offset" to offset,
                        "limit_options" to limitOptions,
                        "expressions" to parseLimitBy(),
                    )
                ),
                comments = comments,
            )

            return limitExp
        }

        if (match(TokenType.FETCH)) {
            // sqlglot: exp.Fetch — not ported.
            return raiseError("FETCH is not supported yet")
        }

        return this_
    }

    // sqlglot: Parser._parse_limit_by
    protected fun parseLimitBy(): List<Expression>? =
        if (matchTextSeq("BY")) parseCsv { parseBitwise() } else null

    // sqlglot: Parser._parse_offset
    fun parseOffset(this_: Expression? = null): Expression? {
        if (!match(TokenType.OFFSET)) return this_

        val count = parseTerm()
        matchSet(setOf(TokenType.ROW, TokenType.ROWS))

        return expression(
            Offset(args("this" to this_, "expression" to count, "expressions" to parseLimitBy()))
        )
    }

    // sqlglot: Parser._can_parse_limit_or_offset
    protected fun canParseLimitOrOffset(): kotlin.Boolean {
        if (!matchSet(ambiguousAliasTokens, advance = false)) return false

        val startIndex = index
        var result = tryParse({ parseLimit() }, retreat = true) != null ||
            tryParse({ parseOffset() }, retreat = true) != null
        retreat(startIndex)

        // MATCH_CONDITION (...) is a special construct that should not be consumed by limit/offset
        if (nextToken.tokenType == TokenType.MATCH_CONDITION) result = false

        return result
    }

    // sqlglot: Parser._can_parse_named_window
    protected fun canParseNamedWindow(): kotlin.Boolean {
        // `WINDOW` is in ID_VAR_TOKENS so it could be mistakenly consumed as an implicit alias.
        // Refuse only when the following tokens look like a named-window clause: `WINDOW <id> AS (`.
        if (!match(TokenType.WINDOW, advance = false)) return false

        val name = tokens.getOrNull(index + 1) ?: return false
        if (name.tokenType !in idVarTokens) return false

        val aliasTok = tokens.getOrNull(index + 2) ?: return false
        if (aliasTok.tokenType != TokenType.ALIAS) return false

        val body = tokens.getOrNull(index + 3) ?: return false
        return body.tokenType == TokenType.L_PAREN
    }

    // sqlglot: Parser.parse_set_operation — TODO(pipe): Phase 4 (consume_pipe parameter)
    fun parseSetOperation(this_: Expression?): Expression? {
        val start = index
        val (_, sideToken, kindToken) = parseJoinParts()

        val side = sideToken?.text
        var kind = kindToken?.text

        if (!matchSet(setOperations)) {
            retreat(start)
            return null
        }

        val tokenType = prevToken.tokenType

        val operationName: String
        val operation: NodeFactory
        when (tokenType) {
            TokenType.UNION -> {
                operationName = "Union"
                operation = { Union(it) }
            }
            TokenType.EXCEPT -> {
                operationName = "Except"
                operation = { Except(it) }
            }
            else -> {
                operationName = "Intersect"
                operation = { Intersect(it) }
            }
        }

        val comments = prevToken.comments

        val distinct: kotlin.Boolean? = when {
            match(TokenType.DISTINCT) -> true
            match(TokenType.ALL) -> false
            else -> setOpDistinctByDefault(tokenType)
                ?: raiseError("Expected DISTINCT or ALL for $operationName")
        }

        var byName = if (matchTextSeq("BY", "NAME") || matchTextSeq("STRICT", "CORRESPONDING")) {
            true
        } else {
            null
        }
        if (matchTextSeq("CORRESPONDING")) {
            byName = true
            if (side == null && kind == null) kind = "INNER"
        }

        var onColumnList: MutableList<Expression>? = null
        if (byName == true && matchTexts(setOf("ON", "BY"))) {
            onColumnList = parseWrappedCsv({ parseColumn() })
        }

        val expr = parseSelect(nested = true, parseSetOperation = false)

        return expression(
            operation(
                args(
                    "this" to this_,
                    "distinct" to distinct,
                    "by_name" to byName,
                    "expression" to expr,
                    "side" to side,
                    "kind" to kind,
                    "on" to onColumnList,
                )
            ),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_set_operations
    fun parseSetOperations(this_: Expression?): Expression? {
        var current = this_
        while (current != null) {
            val setop = parseSetOperation(current) ?: break
            current = setop
        }

        if (current is SetOperation && modifiersAttachedToSetOp) {
            val expr = current.args["expression"] as? Expression

            if (expr != null) {
                for (arg in setOpModifiers) {
                    val modifier = expr.args[arg] as? Expression
                    if (modifier != null) {
                        current.set(arg, modifier.pop())
                    }
                }
            }
        }

        return current
    }

    // -----------------------------------------------------------------------
    // Types (sqlglot: _parse_type / _parse_types)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_type
    fun parseType(
        parseInterval: kotlin.Boolean = true,
        fallbackToIdentifier: kotlin.Boolean = false,
    ): Expression? {
        if (!fallbackToIdentifier) {
            val atom = parseAtom()
            if (atom != null) return atom
        }

        if (parseInterval) {
            val interval = parseInterval()
            if (interval != null) return parseColumnOps(interval)
        }

        val startIndex = index
        val dataType = parseTypes(checkFunc = true, allowIdentifiers = false)

        // sqlglot: BigQuery inline-constructor Cast handling — struct types not ported.

        if (dataType != null) {
            val index2 = index
            val primary = parsePrimary()

            if (primary is Literal) {
                val this_ = parseColumnOps(primary)

                val literalParser = typeLiteralParsers[dataType.thisArg as? DType]
                if (literalParser != null) return literalParser(this, this_, dataType)

                // sqlglot: ZONE_AWARE_TIMESTAMP_CONSTRUCTOR — base: False.

                return expression(Cast(args("this" to this_, "to" to dataType)))
            }

            // The expressions arg gets set by the parser when we have something like DECIMAL(38, 0)
            // in the input SQL. In that case, we'll produce these tokens: DECIMAL ( 38 , 0 )
            if (dataType.expressionsArg.isNotEmpty() && index2 - startIndex > 1) {
                retreat(index2)
                return parseColumnOps(dataType)
            }

            retreat(startIndex)
        }

        if (fallbackToIdentifier) return parseIdVar()

        return parseColumn()
    }

    // sqlglot: Parser._parse_atom
    protected fun parseAtom(): Expression? {
        if (currToken.tokenType in identifierTokens) {
            val column = parseColumn()
            if (column != null) return column
        }

        val token = currToken
        val tokenType = token.tokenType

        val primaryParser = primaryParsers[tokenType] ?: return null

        val nextType = nextToken.tokenType

        if (nextType in columnOperators ||
            nextType in columnPostfixTokens ||
            (tokenType == TokenType.STRING && nextType == TokenType.STRING)
        ) {
            return null
        }

        advance()
        return primaryParser(this, token)
    }

    // sqlglot: Parser._parse_interval — exp.Interval not ported (Python only fires on
    // TokenType.INTERVAL, which the corpus never produces in expression position).
    protected fun parseInterval(): Expression? {
        if (match(TokenType.INTERVAL, advance = false)) {
            return raiseError("INTERVAL expressions are not supported yet")
        }
        return null
    }

    // sqlglot: Parser._parse_types — subset: simple types plus parenthesized size params
    // (VARCHAR(255), DECIMAL(10, 2)). Not ported: allow_identifiers re-tokenization,
    // nested/struct/enum/aggregate types, trailing ARRAY/[n] suffixes, LIST, collation.
    fun parseTypes(
        checkFunc: kotlin.Boolean = false,
        schema: kotlin.Boolean = false,
        allowIdentifiers: kotlin.Boolean = true,
        withCollation: kotlin.Boolean = false,
    ): Expression? {
        val startIndex = index

        if (!matchSet(typeTokens)) return null
        val typeToken = prevToken.tokenType

        if (typeToken == TokenType.PSEUDO_TYPE || typeToken == TokenType.OBJECT_IDENTIFIER) {
            // sqlglot: exp.PseudoType / exp.ObjectIdentifier — not ported.
            return raiseError("Pseudo types are not supported yet")
        }

        val nested = typeToken in nestedTypeTokens

        if (nested || typeToken in enumTypeTokens || typeToken in aggregateTypeTokens) {
            if (match(TokenType.L_PAREN, advance = false) || match(TokenType.LT, advance = false)) {
                return raiseError("Nested/parametrized container types are not supported yet")
            }
            // Bare container-type keywords bail to the column path (see _parse_type retreat).
            retreat(startIndex)
            return null
        }

        var expressions: MutableList<Expression>? = null
        var maybeFunc = false

        if (match(TokenType.L_PAREN)) {
            expressions = parseCsv { parseTypeSize() }

            if (!match(TokenType.R_PAREN)) {
                retreat(startIndex)
                return null
            }

            maybeFunc = true
        }

        if (typeToken in timestamps) {
            if (matchTextSeq("WITH", "TIME", "ZONE") || matchTextSeq("WITH", "LOCAL", "TIME", "ZONE")) {
                // sqlglot: TIMETZ / TIMESTAMPTZ / TIMESTAMPLTZ mapping — DType members not ported.
                return raiseError("WITH TIME ZONE types are not supported yet")
            }
            if (matchTextSeq("WITHOUT", "TIME", "ZONE")) {
                maybeFunc = false
            }
        }

        if (maybeFunc && checkFunc) {
            val index2 = index
            val peek = parseString()

            if (peek == null) {
                retreat(startIndex)
                return null
            }

            retreat(index2)
        }

        if (matchTextSeq("UNSIGNED")) {
            // sqlglot: SIGNED_TO_UNSIGNED_TYPE_TOKEN — unsigned DType members not ported.
            return raiseError("UNSIGNED types are not supported yet")
        }

        val dtype = DType.entries.firstOrNull { it.name == typeToken.name }
        if (dtype == null) {
            // Deviation: the DType enum is still partial (~20 of ~150 members). Python would
            // build the DataType here; bailing to the column path is behavior-equivalent for
            // everything outside a CAST target (where Python would succeed and we error out).
            retreat(startIndex)
            return null
        }

        return DataType(args("this" to dtype, "expressions" to expressions, "nested" to nested))
    }

    // sqlglot: Parser._parse_type_size
    protected fun parseTypeSize(): Expression? {
        var this_ = parseType()
        if (this_ == null) return null

        if (this_ is Column && this_.table.isEmpty()) {
            this_ = Var(args("this" to this_.name.uppercase()))
        }

        return expression(
            DataTypeParam(args("this" to this_, "expression" to parseVar(anyToken = true)))
        )
    }

    // -----------------------------------------------------------------------
    // Columns (sqlglot: _parse_column / _parse_column_ops)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_column
    fun parseColumn(): Expression? {
        var column: Expression? = parseColumnPartsFast()
        if (column == null) {
            var this_ = parseColumnReference()
            if (this_ == null) this_ = parseBracket(this_)
            column = if (this_ != null) parseColumnOps(this_) else this_
        }

        if (column != null) {
            if (supportsColumnJoinMarks) column.set("join_mark", match(TokenType.JOIN_MARKER))
            // sqlglot: COLON_IS_VARIANT_EXTRACT — base: False.
        }

        return column
    }

    // sqlglot: Parser._parse_column_parts_fast
    protected fun parseColumnPartsFast(): Expression? {
        val startIndex = index
        var parts: MutableList<Expression>? = null
        var allComments: MutableList<String>? = null

        while (matchSet(identifierTokens)) {
            val token = prevToken
            val comments = prevComments

            if (parts == null && token.text.uppercase() in noParenFunctionParsers) {
                retreat(startIndex)
                return null
            }

            val hasDot = match(TokenType.DOT)
            val currTt = currToken.tokenType

            if (!hasDot) {
                if (currTt in columnOperators || currTt in columnPostfixTokens) {
                    retreat(startIndex)
                    return null
                }
            } else if (currTt !in identifierTokens) {
                retreat(startIndex)
                return null
            }

            if (parts == null) parts = mutableListOf()

            if (comments.isNotEmpty()) {
                if (allComments == null) allComments = mutableListOf()
                allComments.addAll(comments)
                prevComments = emptyList()
            }

            parts.add(
                expression(
                    Identifier(
                        args("this" to token.text, "quoted" to (token.tokenType == TokenType.IDENTIFIER))
                    ),
                    token,
                )
            )

            if (!hasDot) break
        }

        if (parts == null) return null

        val n = parts.size

        var column: Expression = when {
            n == 1 -> Column(args("this" to parts[0]))
            n == 2 -> Column(args("this" to parts[1], "table" to parts[0]))
            n == 3 -> Column(args("this" to parts[2], "table" to parts[1], "db" to parts[0]))
            else -> Column(
                args(
                    "this" to parts[3], "table" to parts[2], "db" to parts[1], "catalog" to parts[0]
                )
            )
        }
        for (i in 4 until n) {
            column = Dot(args("this" to column, "expression" to parts[i]))
        }

        if (!allComments.isNullOrEmpty()) column.addComments(allComments)

        return column
    }

    // sqlglot: Parser._parse_column_reference
    protected fun parseColumnReference(): Expression? {
        var this_ = parseField()
        if (this_ == null &&
            match(TokenType.VALUES, advance = false) &&
            valuesFollowedByParen &&
            (!nextToken.exists || nextToken.tokenType != TokenType.L_PAREN)
        ) {
            this_ = parseIdVar()
        }

        if (this_ is Identifier) {
            // We bubble up comments from the Identifier to the Column
            this_ = expression(Column(args("this" to this_)), comments = this_.popComments())
        }

        return this_
    }

    // sqlglot: Parser._parse_dcolon
    protected fun parseDcolon(): Expression? = parseTypes()

    // sqlglot: Parser._parse_column_ops
    fun parseColumnOps(this_: Expression?): Expression? {
        var current = this_
        while (currToken.tokenType in brackets) {
            current = parseBracket(current)
        }

        while (currToken.exists) {
            val opToken = currToken.tokenType

            if (opToken !in columnOperators) break
            val op = columnOperators[opToken]
            advance()

            var field: Expression?
            if (opToken in castColumnOperators) {
                field = parseDcolon()
                if (field == null) raiseError("Expected type")
            } else if (op != null && currToken.exists) {
                field = parseColumnReference() ?: parseBitwise()
                if (field is Column && match(TokenType.DOT, advance = false)) {
                    field = parseColumnOps(field)
                }
            } else {
                val dot = isConnected() && prevToken.tokenType == TokenType.DOT
                field = parseField(anyToken = true, anonymousFunc = true)

                // In t.true, t.null we should produce an Identifier node
                if (dot && (field is Null || field is Boolean)) {
                    field = expression(
                        Identifier(args("this" to prevToken.text)),
                        comments = field.comments,
                    )
                }
            }

            // Function calls can be qualified, e.g., x.y.FOO()
            // This converts the final AST to a series of Dots leading to the function call
            if ((field is Func || field is Window) && current != null) {
                current = current.transform { n -> if (n is Column) n.toDot(includeDots = false) else n }
            }

            if (op != null) {
                current = op(this, current, field)
            } else if (current is Column && current.args["catalog"] == null) {
                current = expression(
                    Column(
                        args(
                            "this" to field,
                            "table" to current.thisArg,
                            "db" to current.args["table"],
                            "catalog" to current.args["db"],
                        )
                    ),
                    comments = current.comments,
                )
            } else if (field is Window) {
                // Move the exp.Dot's to the window's function
                val windowFunc = expression(Dot(args("this" to current, "expression" to field.thisArg)))
                field.set("this", windowFunc)
                current = field
            } else {
                current = expression(Dot(args("this" to current, "expression" to field)))
            }

            if (field != null && !field.comments.isNullOrEmpty()) {
                current?.addComments(field.popComments())
            }

            current = parseBracket(current)
        }

        return current
    }

    // sqlglot: Parser._parse_bracket — exp.Bracket/exp.Array/exp.Struct not ported; passes
    // the input through when no bracket token is present, like Python.
    fun parseBracket(this_: Expression?): Expression? {
        if (!matchSet(brackets, advance = false)) return this_
        return raiseError("Bracket expressions are not supported yet")
    }

    // -----------------------------------------------------------------------
    // Primary layer (sqlglot: _parse_primary / _parse_field / _parse_function)
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_paren
    fun parseParen(): Expression? {
        if (!match(TokenType.L_PAREN)) return null

        val comments = prevComments
        val query = parseSelect()

        val expressions: MutableList<Expression> =
            if (query != null) mutableListOf(query) else parseExpressions()

        var this_: Expression? = expressions.firstOrNull()

        if (this_ == null && match(TokenType.R_PAREN, advance = false)) {
            this_ = expression(Tuple())
        } else if (expressions.size > 1 || prevToken.tokenType == TokenType.COMMA) {
            this_ = expression(Tuple(args("expressions" to expressions)))
        } else if (this_ is Select || this_ is SetOperation) {
            // sqlglot: exp.UNWRAPPED_QUERIES
            this_ = parseSubquery(this_, parseAlias = false)
        } else if (this_ is Subquery) {
            // sqlglot: `isinstance(this, (exp.Subquery, exp.Values))` — exp.Values not ported
            this_ = parseSubquery(
                parseQueryModifiers(parseSetOperations(this_)),
                parseAlias = false,
            )
        } else {
            this_ = expression(Paren(args("this" to this_)))
        }

        if (this_ != null) this_.addComments(comments)

        matchRParen(this_)

        if (this_ is Paren && this_.thisArg is AggFunc) {
            return parseWindow(this_)
        }

        return this_
    }

    // sqlglot: Parser._parse_primary
    fun parsePrimary(): Expression? {
        if (matchSet(primaryParsers.keys)) {
            val tokenType = prevToken.tokenType
            val primary = primaryParsers.getValue(tokenType)(this, prevToken)

            if (tokenType == TokenType.STRING && match(TokenType.STRING, advance = false)) {
                // sqlglot: adjacent string literals -> exp.Concat — not ported.
                return raiseError("Adjacent string literals are not supported yet")
            }

            return primary
        }

        if (matchPair(TokenType.DOT, TokenType.NUMBER)) {
            return Literal.number("0.${prevToken.text}")
        }

        return parseParen()
    }

    // sqlglot: Parser._parse_field
    fun parseField(
        anyToken: kotlin.Boolean = false,
        tokens: Collection<TokenType>? = null,
        anonymousFunc: kotlin.Boolean = false,
    ): Expression? {
        val field = if (anonymousFunc) {
            parseFunction(anonymous = anonymousFunc, anyToken = anyToken) ?: parsePrimary()
        } else {
            parsePrimary() ?: parseFunction(anonymous = anonymousFunc, anyToken = anyToken)
        }
        return field ?: parseIdVar(anyToken = anyToken, tokens = tokens)
    }

    // sqlglot: Parser._parse_function
    fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>? = null,
        anonymous: kotlin.Boolean = false,
        optionalParens: kotlin.Boolean = true,
        anyToken: kotlin.Boolean = false,
    ): Expression? {
        // This allows us to also parse {fn <function>} syntax (Snowflake, MySQL support this)
        // See: https://community.snowflake.com/s/article/SQL-Escape-Sequences
        var fnSyntax = false
        if (match(TokenType.L_BRACE, advance = false) && nextToken.text.uppercase() == "FN") {
            advance(2)
            fnSyntax = true
        }

        val func = parseFunctionCall(
            functions = functions,
            anonymous = anonymous,
            optionalParens = optionalParens,
            anyToken = anyToken,
        )

        if (fnSyntax) match(TokenType.R_BRACE)

        return func
    }

    // sqlglot: Parser._parse_function_args
    protected fun parseFunctionArgs(alias: kotlin.Boolean = false): MutableList<Expression> =
        parseCsv { parseLambda(alias = alias) }

    // sqlglot: Parser._parse_function_call
    protected fun parseFunctionCall(
        functions: Map<String, (List<Expression?>) -> Expression>? = null,
        anonymous: kotlin.Boolean = false,
        optionalParens: kotlin.Boolean = true,
        anyToken: kotlin.Boolean = false,
    ): Expression? {
        if (!currToken.exists) return null

        val comments = currToken.comments
        val prev = prevToken
        val tokenType = currToken.tokenType
        val name: String = currToken.text
        val upper = currToken.text.uppercase()

        val afterDot = prev.tokenType == TokenType.DOT
        val noParenParser = noParenFunctionParsers[upper]
        if (optionalParens &&
            noParenParser != null &&
            tokenType !in invalidFuncNameTokens &&
            !afterDot
        ) {
            advance()
            return parseWindow(noParenParser(this))
        }

        if (nextToken.tokenType != TokenType.L_PAREN) {
            if (optionalParens && tokenType in noParenFunctions && !afterDot) {
                advance()
                return expression(noParenFunctions.getValue(tokenType)())
            }

            return null
        }

        if (anyToken) {
            if (tokenType in reservedTokens) return null
        } else if (tokenType !in funcTokens) {
            return null
        }

        advance(2)

        val parser = if (!anonymous) functionParsers[upper] else null
        var result: Expression?
        if (parser != null) {
            result = parser(this)
        } else {
            val subqueryPredicate = subqueryPredicates[tokenType]

            if (subqueryPredicate != null) {
                var expr: Expression? = null
                if (currToken.tokenType in subqueryTokens) {
                    expr = parseSelect()
                    matchRParen()
                } else if (prev.exists &&
                    (prev.tokenType == TokenType.LIKE || prev.tokenType == TokenType.ILIKE)
                ) {
                    // Backtrack one token since we've consumed the L_PAREN here. Instead, we'd like
                    // to parse "LIKE [ANY | ALL] (...)" as a whole into an exp.Tuple or exp.Paren
                    advance(-1)
                    expr = parseBitwise()
                }

                if (expr != null) {
                    return expression(subqueryPredicate(args("this" to expr)), comments = comments)
                }
            }

            val fns = functions ?: this.functions

            val function = fns[upper]
            var knownFunction = function != null && !anonymous

            val aliasArgs = !knownFunction || upper in functionsWithAliasedArgs
            var fnArgs: MutableList<Expression> = parseFunctionArgs(aliasArgs)

            val postFuncComments = if (currToken.exists) currToken.comments else null
            if (knownFunction && !postFuncComments.isNullOrEmpty()) {
                // If the user-inputted comment "/* sqlglot.anonymous */" is following the function
                // call we'll construct it as exp.Anonymous, even if it's "known"
                if (postFuncComments.any { it.trimStart().startsWith(SQLGLOT_ANONYMOUS) }) {
                    knownFunction = false
                }
            }

            if (aliasArgs && knownFunction) {
                fnArgs = kvToPropEq(fnArgs)
            }

            if (knownFunction) {
                val func = function!!(fnArgs)
                result = validateExpression(func)
                if (preserveOriginalNames) func.meta["name"] = name
            } else {
                val fnName: kotlin.Any = if (tokenType == TokenType.IDENTIFIER) {
                    Identifier(args("this" to name, "quoted" to true))
                } else {
                    name
                }
                result = expression(Anonymous(args("this" to fnName, "expressions" to fnArgs)))
            }
        }

        result?.addComments(comments)

        if (parser != null) {
            match(TokenType.R_PAREN, expression = result)
        } else {
            matchRParen(result)
        }
        return parseWindow(result)
    }

    // sqlglot: Parser._kv_to_prop_eq — KEY_VALUE_DEFINITIONS kwarg rewriting is not ported
    // (the base corpus never passes kwargs to known functions).
    protected fun kvToPropEq(expressions: MutableList<Expression>): MutableList<Expression> =
        expressions

    // sqlglot: Parser._parse_lambda
    fun parseLambda(alias: kotlin.Boolean = false): Expression? {
        val nextTokenType = nextToken.tokenType

        // Fast path: simple atom (column, literal, null, bool) followed by , or )
        if (nextTokenType in lambdaArgTerminators) {
            val atom = parseAtom()
            if (atom != null) return atom
        }

        val startIndex = index

        if (match(TokenType.L_PAREN)) {
            val expressions = parseCsv { parseLambdaArg() }

            if (!match(TokenType.R_PAREN)) {
                retreat(startIndex)
            } else if (matchSet(lambdas.keys)) {
                return lambdas.getValue(prevToken.tokenType)(this, expressions)
            } else {
                retreat(startIndex)
            }
        } else if (typedLambdaArgs || nextTokenType in lambdas) {
            val expressions = listOf(parseLambdaArg())

            if (matchSet(lambdas.keys)) {
                return lambdas.getValue(prevToken.tokenType)(this, expressions)
            }

            retreat(startIndex)
        }

        val this_: Expression?

        if (match(TokenType.DISTINCT)) {
            this_ = expression(Distinct(args("expressions" to parseCsv { parseDisjunction() })))
        } else {
            match(TokenType.ALL) // ALL is the default/no-op aggregate modifier (SQL-92)
            this_ = parseSelectOrExpression(alias = alias)
        }

        return parseLimit(
            parseRespectOrIgnoreNulls(
                parseOrder(parseHavingMax(parseRespectOrIgnoreNulls(this_)))
            )
        )
    }

    // sqlglot: Parser._parse_lambda_arg
    protected fun parseLambdaArg(): Expression? = parseIdVar()

    // sqlglot: Parser._parse_respect_or_ignore_nulls — exp.IgnoreNulls/exp.RespectNulls
    // not ported.
    protected fun parseRespectOrIgnoreNulls(this_: Expression?): Expression? {
        if (currToken.tokenType == TokenType.VAR) {
            if (matchTextSeq("IGNORE", "NULLS") || matchTextSeq("RESPECT", "NULLS")) {
                return raiseError("IGNORE/RESPECT NULLS is not supported yet")
            }
        }
        return this_
    }

    // sqlglot: Parser._parse_having_max — exp.HavingMax not ported.
    protected fun parseHavingMax(this_: Expression?): Expression? {
        if (match(TokenType.HAVING)) {
            return raiseError("HAVING MAX/MIN aggregate modifiers are not supported yet")
        }
        return this_
    }

    // sqlglot: Parser._parse_case
    fun parseCase(): Expression? {
        if (match(TokenType.DOT, advance = false)) {
            // Avoid raising on valid expressions like case.*, supported by, e.g., spark & snowflake
            retreat(index - 1)
            return null
        }

        val ifs = mutableListOf<Expression>()
        var default: Expression? = null

        val comments = prevComments
        val caseExpression = parseDisjunction()

        while (match(TokenType.WHEN)) {
            val this_ = parseDisjunction()
            match(TokenType.THEN)
            val then = parseDisjunction()
            ifs.add(expression(If(args("this" to this_, "true" to then))))
        }

        if (match(TokenType.ELSE)) {
            default = parseDisjunction()
        }

        if (!match(TokenType.END)) {
            // sqlglot: Interval-default recovery — exp.Interval not ported.
            raiseError("Expected END after CASE", prevToken)
        }

        return expression(
            Case(args("this" to caseExpression, "ifs" to ifs, "default" to default)),
            comments = comments,
        )
    }

    // sqlglot: Parser._parse_if
    fun parseIf(): Expression? {
        val this_: Expression?
        if (match(TokenType.L_PAREN)) {
            val fnArgs = parseCsv { parseAlias(parseAssignment(), explicit = true) }
            // sqlglot: exp.If.from_arg_list(args)
            this_ = validateExpression(
                If(
                    args(
                        "this" to fnArgs.getOrNull(0),
                        "true" to fnArgs.getOrNull(1),
                        "false" to fnArgs.getOrNull(2),
                    )
                )
            )
            matchRParen()
        } else {
            val startIndex = index - 1

            if (noParenIfCommands && startIndex == 0) {
                return parseAsCommand(prevToken)
            }

            val condition = parseDisjunction()

            if (condition == null) {
                retreat(startIndex)
                return null
            }

            match(TokenType.THEN)
            val trueExpr = parseDisjunction()
            val falseExpr = if (match(TokenType.ELSE)) parseDisjunction() else null
            match(TokenType.END)
            this_ = expression(If(args("this" to condition, "true" to trueExpr, "false" to falseExpr)))
        }

        return this_
    }

    // sqlglot: Parser._parse_cast
    fun parseCast(strict: kotlin.Boolean, safe: kotlin.Boolean? = null): Expression? {
        val this_ = parseAssignment()

        if (!match(TokenType.ALIAS)) {
            if (match(TokenType.COMMA)) {
                // sqlglot: exp.CastToStrType — not ported.
                return raiseError("CAST(x, 'type') is not supported yet")
            }

            raiseError("Expected AS after CAST")
        }

        val to = parseTypes(withCollation = true)

        var default: Expression? = null
        if (match(TokenType.DEFAULT)) {
            default = parseBitwise()
            matchTextSeq("ON", "CONVERSION", "ERROR")
        }

        if (matchSet(setOf(TokenType.FORMAT, TokenType.COMMA))) {
            // sqlglot: exp.StrToDate / exp.StrToTime format casts — not ported.
            return raiseError("CAST ... FORMAT is not supported yet")
        } else if (to == null) {
            raiseError("Expected TYPE after CAST")
        } else if (to.thisArg == DType.CHAR && match(TokenType.CHARACTER_SET)) {
            // sqlglot: DType.CHARACTER_SET — not ported.
            return raiseError("CAST ... CHARACTER SET is not supported yet")
        }

        return buildCast(
            strict = strict,
            this_ = this_,
            to = to,
            format = null,
            safe = safe,
            action = parseVarFromOptions(castActions, raiseUnmatched = false),
            default = default,
        )
    }

    // sqlglot: Parser.build_cast — exp.TryCast not ported (base STRICT_CAST is true, so
    // the non-strict branch never fires).
    fun buildCast(
        strict: kotlin.Boolean,
        this_: Expression?,
        to: Expression?,
        format: Expression? = null,
        safe: kotlin.Boolean? = null,
        action: Expression? = null,
        default: Expression? = null,
    ): Expression? {
        if (!strict) {
            return raiseError("TRY_CAST is not supported yet")
        }

        return expression(
            Cast(
                args(
                    "this" to this_,
                    "to" to to,
                    "format" to format,
                    "safe" to safe,
                    "action" to action,
                    "default" to default,
                )
            )
        )
    }

    // sqlglot: Parser._parse_var_from_options
    protected fun parseVarFromOptions(
        options: Map<String, List<List<String>>>,
        raiseUnmatched: kotlin.Boolean = true,
    ): Expression? {
        val start = currToken
        if (!start.exists) return null

        var option = start.text.uppercase()
        val continuations =
            if (start.tokenType in textMatchExcludedTokens) null else options[option]

        val startIndex = index
        advance()
        var matched = false
        for (keywords in continuations ?: emptyList()) {
            if (matchTextSeq(*keywords.toTypedArray())) {
                option = "$option ${keywords.joinToString(" ")}"
                matched = true
                break
            }
        }
        if (!matched && (continuations == null || continuations.isNotEmpty())) {
            if (raiseUnmatched) raiseError("Unknown option $option")

            retreat(startIndex)
            return null
        }

        // sqlglot: exp.var(option)
        return Var(args("this" to option))
    }

    // sqlglot: Parser._parse_window
    fun parseWindow(this_: Expression?, alias: kotlin.Boolean = false): Expression? {
        var current = this_
        val func = current
        val comments = func?.comments

        if (matchTextSeq("WITHIN", "GROUP")) {
            // sqlglot: exp.WithinGroup — not ported.
            return raiseError("WITHIN GROUP is not supported yet")
        }

        if (matchPair(TokenType.FILTER, TokenType.L_PAREN)) {
            // sqlglot: exp.Filter — not ported.
            return raiseError("FILTER (WHERE ...) is not supported yet")
        }

        // sqlglot: IgnoreNulls/RespectNulls relocation for AggFuncs — nodes not ported.
        current = parseRespectOrIgnoreNulls(current)

        // bigquery select from window x AS (partition by ...)
        val over: String?
        if (alias) {
            over = null
            match(TokenType.ALIAS)
        } else if (!matchSet(windowBeforeParenTokens)) {
            return current
        } else {
            over = prevToken.text.uppercase()
        }

        if (!comments.isNullOrEmpty() && func != null) {
            func.popComments()
        }

        if (!match(TokenType.L_PAREN)) {
            return expression(
                Window(args("this" to current, "alias" to parseIdVar(anyToken = false), "over" to over)),
                comments = comments,
            )
        }

        val windowAlias = parseIdVar(anyToken = false, tokens = windowAliasTokens)

        var first: kotlin.Boolean? = if (match(TokenType.FIRST)) true else null
        if (matchTextSeq("LAST")) first = false

        val (partition, order) = parsePartitionAndOrder()
        val kind = if (matchSet(setOf(TokenType.ROWS, TokenType.RANGE)) || matchTextSeq("GROUPS")) {
            prevToken.text
        } else {
            null
        }

        if (kind != null) {
            // sqlglot: exp.WindowSpec — not ported.
            return raiseError("Window frame specifications are not supported yet")
        }

        matchRParen()

        val window = expression(
            Window(
                args(
                    "this" to current,
                    "partition_by" to partition,
                    "order" to order,
                    "spec" to null,
                    "alias" to windowAlias,
                    "over" to over,
                    "first" to first,
                )
            ),
            comments = comments,
        )

        // This covers Oracle's FIRST/LAST syntax: aggregate KEEP (...) OVER (...)
        if (matchSet(windowBeforeParenTokens, advance = false)) {
            return parseWindow(window, alias = alias)
        }

        return window
    }

    // sqlglot: Parser._parse_partition_and_order
    protected fun parsePartitionAndOrder(): Pair<List<Expression>, Expression?> =
        parsePartitionBy() to parseOrder()

    // sqlglot: Parser._parse_partition_by
    fun parsePartitionBy(): List<Expression> {
        if (match(TokenType.PARTITION_BY)) return parseCsv { parseDisjunction() }
        return emptyList()
    }

    // -----------------------------------------------------------------------
    // Aliases / identifiers / leaf parsers
    // -----------------------------------------------------------------------

    // sqlglot: Parser._parse_alias
    fun parseAlias(this_: Expression?, explicit: kotlin.Boolean = false): Expression? {
        // In some dialects, LIMIT and OFFSET can act as both identifiers and keywords (clauses)
        // so this section tries to parse the clause version and if it fails, it treats the token
        // as an identifier (alias)
        if (canParseLimitOrOffset()) return this_

        // WINDOW is in ID_VAR_TOKENS, so it can be consumed as an implicit alias. Detect the
        // named-window clause shape (`WINDOW <ident> AS (...)`) and avoid swallowing it.
        if (canParseNamedWindow()) return this_

        val anyToken = match(TokenType.ALIAS)
        val comments = prevComments.toMutableList()

        if (explicit && !anyToken) return this_

        if (match(TokenType.L_PAREN)) {
            val aliases = expression(
                Aliases(
                    args("this" to this_, "expressions" to parseCsv { parseIdVar(anyToken) })
                ),
                comments = comments,
            )
            matchRParen(aliases)
            return aliases
        }

        var result = this_
        val alias = parseIdVar(anyToken, tokens = aliasTokens)
            ?: (if (stringAliases) parseStringAsIdentifier() else null)

        if (alias != null) {
            comments.addAll(alias.popComments())
            result = expression(Alias(args("this" to result, "alias" to alias)), comments = comments)
            val column = result.thisArg as? Expression

            // Moves the comment next to the alias in `expr /* comment */ AS alias`
            if (result.comments.isNullOrEmpty() && column != null && !column.comments.isNullOrEmpty()) {
                result.comments = column.popComments()
            }
        }

        return result
    }

    // sqlglot: Parser._parse_id_var
    fun parseIdVar(
        anyToken: kotlin.Boolean = true,
        tokens: Collection<TokenType>? = null,
    ): Expression? {
        var expression = parseIdentifier()
        if (expression == null &&
            ((anyToken && advanceAny() != null) || matchSet(tokens ?: idVarTokens))
        ) {
            val quoted = prevToken.tokenType == TokenType.STRING
            expression = identifierExpression(quoted = quoted)
        }

        return expression
    }

    // sqlglot: Parser._identifier_expression
    protected fun identifierExpression(token: Token = prevToken, quoted: kotlin.Boolean? = null): Expression =
        expression(Identifier(args("this" to token.text, "quoted" to quoted)), token)

    // sqlglot: Parser._parse_string
    fun parseString(): Expression? {
        if (matchSet(stringParsers.keys)) {
            return stringParsers.getValue(prevToken.tokenType)(this, prevToken)
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_string_as_identifier
    fun parseStringAsIdentifier(): Identifier? {
        if (!match(TokenType.STRING)) return null
        // sqlglot: exp.to_identifier(text, quoted=True)
        return Identifier(args("this" to prevToken.text, "quoted" to true))
    }

    // sqlglot: Parser._parse_number
    fun parseNumber(): Expression? {
        if (matchSet(numericParsers.keys)) {
            return numericParsers.getValue(prevToken.tokenType)(this, prevToken)
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_identifier
    fun parseIdentifier(): Expression? {
        if (match(TokenType.IDENTIFIER)) return identifierExpression(quoted = true)
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_var
    fun parseVar(
        anyToken: kotlin.Boolean = false,
        tokens: Collection<TokenType>? = null,
        upper: kotlin.Boolean = false,
    ): Expression? {
        if ((anyToken && advanceAny() != null) ||
            match(TokenType.VAR) ||
            (tokens != null && matchSet(tokens))
        ) {
            return expression(
                Var(args("this" to if (upper) prevToken.text.uppercase() else prevToken.text))
            )
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_var_or_string
    fun parseVarOrString(upper: kotlin.Boolean = false): Expression? =
        parseString() ?: parseVar(anyToken = true, upper = upper)

    // sqlglot: Parser._parse_primary_or_var
    fun parsePrimaryOrVar(): Expression? = parsePrimary() ?: parseVar(anyToken = true)

    // sqlglot: Parser._parse_null
    fun parseNull(): Expression? {
        if (matchSet(setOf(TokenType.NULL, TokenType.UNKNOWN))) {
            return primaryParsers.getValue(TokenType.NULL)(this, prevToken)
        }
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_boolean
    fun parseBoolean(): Expression? {
        if (match(TokenType.TRUE)) return primaryParsers.getValue(TokenType.TRUE)(this, prevToken)
        if (match(TokenType.FALSE)) return primaryParsers.getValue(TokenType.FALSE)(this, prevToken)
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_star
    fun parseStar(): Expression? {
        if (match(TokenType.STAR)) return primaryParsers.getValue(TokenType.STAR)(this, prevToken)
        return parsePlaceholder()
    }

    // sqlglot: Parser._parse_parameter
    fun parseParameter(): Expression {
        val this_ = parseIdentifier() ?: parsePrimaryOrVar()
        return expression(Parameter(args("this" to this_)))
    }

    // sqlglot: Parser._parse_placeholder
    fun parsePlaceholder(): Expression? {
        if (matchSet(placeholderParsers.keys)) {
            val placeholder = placeholderParsers.getValue(prevToken.tokenType)(this)
            if (placeholder != null) return placeholder

            advance(-1)
        }
        return null
    }

    // sqlglot: Parser._parse_star_op
    protected fun parseStarOp(vararg keywords: String): List<Expression>? {
        if (!matchTexts(keywords.toList())) return null
        if (match(TokenType.L_PAREN, advance = false)) {
            return parseWrappedCsv({ parseExpression() })
        }

        val expr = parseAlias(parseDisjunction(), explicit = true)
        return if (expr != null) listOf(expr) else null
    }

    // sqlglot: Parser._parse_star_ops
    fun parseStarOps(): Expression? {
        // sqlglot: BigQuery `* COLUMNS(...)` unpacking — exp.Columns not ported.
        if (matchTextSeq("COLUMNS", "(", advance = false)) {
            return raiseError("* COLUMNS(...) is not supported yet")
        }

        val ilike = if (match(TokenType.ILIKE)) parseString() else null

        return expression(
            Star(
                args(
                    "ilike" to ilike,
                    "except_" to parseStarOp("EXCEPT", "EXCLUDE"),
                    "replace" to parseStarOp("REPLACE"),
                    "rename" to parseStarOp("RENAME"),
                )
            )
        )
    }

    // sqlglot: Parser._parse_select_or_expression
    fun parseSelectOrExpression(alias: kotlin.Boolean = false): Expression? =
        parseSetOperations(
            if (alias) parseAlias(parseAssignment(), explicit = true) else parseAssignment()
        ) ?: parseSelect()

    /** Python `isinstance(x, exp.Query)` for the ported subset (Select / set ops / Subquery). */
    protected fun isQuery(expression: Expression): kotlin.Boolean =
        expression is Select || expression is SetOperation || expression is Subquery

    /** sqlglot: Query.subquery(copy=False) with no alias. */
    protected fun subqueryOf(query: Expression?): Subquery =
        Subquery(args("this" to query, "alias" to null))
}

/**
 * sqlglot: sqlglot.parse_one — tokenize + parse and return the single statement.
 * Only the base ("sqlglot") dialect is wired for now.
 */
fun parseOne(sql: String, dialect: String = ""): Expression {
    val config = TokenizerConfigs.forName(dialect)
    val tokens = Tokenizer(config).tokenize(sql)
    val result = Parser(tokenizerConfig = config).parse(tokens, sql)
    return result.firstOrNull() ?: throw ParseError("No expression was parsed from '$sql'")
}
