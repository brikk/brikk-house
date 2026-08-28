package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AlterSet
import dev.brikk.house.sql.ast.Block
import dev.brikk.house.sql.ast.CaseStatement
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.CurrentCatalog
import dev.brikk.house.sql.ast.CurrentVersion
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.EndStatement
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.FunctionSpecification
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.If
import dev.brikk.house.sql.ast.IfBlock
import dev.brikk.house.sql.ast.Iterate
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractQuote
import dev.brikk.house.sql.ast.JSONValue
import dev.brikk.house.sql.ast.Leave
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.LoopBlock
import dev.brikk.house.sql.ast.OnCondition
import dev.brikk.house.sql.ast.Properties
import dev.brikk.house.sql.ast.RepeatBlock
import dev.brikk.house.sql.ast.Return
import dev.brikk.house.sql.ast.StabilityProperty
import dev.brikk.house.sql.ast.Var
import dev.brikk.house.sql.ast.WhileBlock
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.buildLogarithm
import dev.brikk.house.sql.parser.fromArgList
import dev.brikk.house.sql.parser.toJsonPath

/**
 * Port of sqlglot's TrinoParser (reference/sqlglot/sqlglot/parsers/trino.py).
 * Table merges live in [TrinoParserTables]; overridden _parse_* methods below.
 */
// sqlglot: parsers.trino.TrinoParser
open class TrinoParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = dev.brikk.house.sql.parser.TrinoTokenizerTables.CONFIG,
) : PrestoParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.TRINO

    // sqlglot: Trino.SUPPORTS_USER_DEFINED_TYPES = False
    override val supportsUserDefinedTypes: Boolean get() = false

    // sqlglot: TrinoParser.NO_PAREN_FUNCTIONS
    override val noParenFunctions: Map<TokenType, () -> Expression>
        get() = TrinoParserTables.NO_PAREN_FUNCTIONS

    // sqlglot: TrinoParser.FUNCTIONS
    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = TrinoParserTables.FUNCTIONS

    // sqlglot: TrinoParser.FUNCTION_PARSERS
    override val functionParsers: Map<String, (Parser) -> Expression?>
        get() = TrinoParserTables.FUNCTION_PARSERS

    // sqlglot: TrinoParser.JSON_QUERY_OPTIONS
    protected val jsonQueryOptions: Map<String, List<List<String>>> = buildMap {
        for (key in listOf("WITH", "WITHOUT")) {
            put(
                key,
                listOf(
                    listOf("WRAPPER"),
                    listOf("ARRAY", "WRAPPER"),
                    listOf("CONDITIONAL", "WRAPPER"),
                    listOf("CONDITIONAL", "ARRAY", "WRAPPED"),
                    listOf("UNCONDITIONAL", "WRAPPER"),
                    listOf("UNCONDITIONAL", "ARRAY", "WRAPPER"),
                ),
            )
        }
    }

    // sqlglot: TrinoParser._parse_property — NOT DETERMINISTIC as text-seq (same as BigQuery)
    override fun parseProperty(): kotlin.Any? {
        if (matchTextSeq("NOT", "DETERMINISTIC")) {
            return expression(StabilityProperty(args("this" to Literal.string("VOLATILE"))))
        }
        return super.parseProperty()
    }

    // sqlglot: TrinoParser._parse_cte — WITH FUNCTION <name> is an inline SQL UDF
    override fun parseCte(): Expression? {
        if (
            match(TokenType.FUNCTION, advance = false) &&
            nextToken.exists &&
            nextToken.tokenType in idVarTokens
        ) {
            advance()
            return parseFunctionSpecification()
        }
        return super.parseCte()
    }

    // sqlglot: TrinoParser._parse_function_specification
    open fun parseFunctionSpecification(): Expression {
        val this_ = parseUserDefinedFunction(kind = TokenType.FUNCTION)

        val characteristics = mutableListOf<Expression>()
        val properties = mutableListOf<Expression>()

        while (true) {
            if (match(TokenType.WITH)) {
                properties.addAll(parseWrappedCsv({ parseKeyValueProperty() }))
                continue
            }

            val characteristic = parseProperty() ?: break
            when (characteristic) {
                is Expression -> characteristics.add(characteristic)
                is kotlin.collections.List<*> ->
                    characteristics.addAll(characteristic.filterIsInstance<Expression>())
            }
        }

        return expression(
            FunctionSpecification(
                args(
                    "this" to this_,
                    "characteristics" to if (characteristics.isNotEmpty()) {
                        expression(Properties(args("expressions" to characteristics)))
                    } else {
                        null
                    },
                    "properties" to if (properties.isNotEmpty()) {
                        expression(Properties(args("expressions" to properties)))
                    } else {
                        null
                    },
                    "expression" to parseRoutineStatement(),
                )
            )
        )
    }

    // sqlglot: TrinoParser._parse_routine_statements
    open fun parseRoutineStatements(vararg terminators: String): MutableList<Expression> {
        val statements = mutableListOf<Expression>()
        val terminatorSet = terminators.map { it.uppercase() }.toSet()

        while (!matchTexts(terminatorSet)) {
            if (!currToken.exists) {
                if (chunkIndex >= chunks.size) {
                    raiseError("Unexpected end of routine body")
                    break
                }
                advanceChunk()
            } else if (!match(TokenType.SEMICOLON)) {
                val statement = parseRoutineStatement() ?: break
                statements.add(statement)
            }
        }

        return statements
    }

    // sqlglot: TrinoParser._parse_routine_block
    open fun parseRoutineBlock(): Expression {
        match(TokenType.BEGIN)
        val statements = parseRoutineStatements("END")
        statements.add(EndStatement())
        return expression(Block(args("expressions" to statements, "begin" to true)))
    }

    // sqlglot: TrinoParser._parse_routine_if
    open fun parseRoutineIf(): Expression {
        fun parseBranch(): Expression {
            val condition = parseDisjunction()
            matchTextSeq("THEN")
            val true_ = expression(
                Block(args("expressions" to parseRoutineStatements("ELSEIF", "ELSE", "END")))
            )
            return expression(IfBlock(args("this" to condition, "true" to true_)))
        }

        var this_ = parseBranch()
        var tail = this_
        while (prevToken.text.uppercase() == "ELSEIF") {
            val node = parseBranch()
            tail.set("false", node)
            tail = node
        }

        if (prevToken.text.uppercase() == "ELSE") {
            tail.set(
                "false",
                expression(Block(args("expressions" to parseRoutineStatements("END")))),
            )
        }

        matchTextSeq("IF")
        return this_
    }

    // sqlglot: TrinoParser._parse_routine_case
    open fun parseRoutineCase(): Expression {
        val this_ = parseDisjunction()

        fun parseBranch(): Expression {
            val condition = parseDisjunction()
            matchTextSeq("THEN")
            val true_ = expression(
                Block(args("expressions" to parseRoutineStatements("WHEN", "ELSE", "END")))
            )
            return expression(If(args("this" to condition, "true" to true_)))
        }

        val ifs = mutableListOf<Expression>()
        matchTextSeq("WHEN")
        while (prevToken.text.uppercase() == "WHEN") {
            ifs.add(parseBranch())
        }

        var default: Expression? = null
        if (prevToken.text.uppercase() == "ELSE") {
            default = expression(Block(args("expressions" to parseRoutineStatements("END"))))
        }

        matchTextSeq("CASE")
        return expression(
            CaseStatement(args("this" to this_, "ifs" to ifs, "default" to default))
        )
    }

    // sqlglot: TrinoParser._parse_routine_while
    open fun parseRoutineWhile(label: Expression? = null): Expression {
        val condition = parseDisjunction()
        matchTextSeq("DO")
        val body = expression(Block(args("expressions" to parseRoutineStatements("END"))))
        matchTextSeq("WHILE")
        return expression(WhileBlock(args("this" to condition, "body" to body, "label" to label)))
    }

    // sqlglot: TrinoParser._parse_routine_loop
    open fun parseRoutineLoop(label: Expression? = null): Expression {
        val body = expression(Block(args("expressions" to parseRoutineStatements("END"))))
        matchTextSeq("LOOP")
        return expression(LoopBlock(args("body" to body, "label" to label)))
    }

    // sqlglot: TrinoParser._parse_routine_repeat
    open fun parseRoutineRepeat(label: Expression? = null): Expression {
        val body = expression(Block(args("expressions" to parseRoutineStatements("UNTIL"))))
        val until = parseDisjunction()
        matchTextSeq("END", "REPEAT")
        return expression(RepeatBlock(args("body" to body, "until" to until, "label" to label)))
    }

    // sqlglot: TrinoParser._parse_routine_statement
    open fun parseRoutineStatement(): Expression? {
        // Optional `label :` before WHILE/LOOP/REPEAT (colon lookahead first so SET/IF/etc
        // remain valid label names).
        var label: Expression? = null
        if (nextToken.exists && nextToken.tokenType == TokenType.COLON) {
            label = parseIdVar()
            match(TokenType.COLON)
        }

        if (match(TokenType.BEGIN, advance = false)) {
            return parseRoutineBlock()
        }

        if (matchTextSeq("RETURN")) {
            return expression(Return(args("this" to parseDisjunction())))
        }

        if (matchTextSeq("IF")) {
            return parseRoutineIf()
        }

        if (matchTextSeq("CASE")) {
            return parseRoutineCase()
        }

        if (match(TokenType.DECLARE)) {
            return parseDeclare()
        }

        if (match(TokenType.SET)) {
            return parseSet()
        }

        if (matchTextSeq("ITERATE")) {
            return expression(Iterate(args("this" to parseIdVar())))
        }

        if (matchTextSeq("LEAVE")) {
            return expression(Leave(args("this" to parseIdVar())))
        }

        if (matchTextSeq("WHILE")) {
            return parseRoutineWhile(label = label)
        }

        if (matchTextSeq("LOOP")) {
            return parseRoutineLoop(label = label)
        }

        if (matchTextSeq("REPEAT")) {
            return parseRoutineRepeat(label = label)
        }

        raiseError("Expected routine statement")
        return null
    }

    // sqlglot: TrinoParser._parse_json_query_quote
    open fun parseJsonQueryQuote(): Expression? {
        if (!(matchTextSeq("KEEP", "QUOTES") || matchTextSeq("OMIT", "QUOTES"))) {
            return null
        }

        return expression(
            JSONExtractQuote(
                args(
                    "option" to tokens[index - 2].text.uppercase(),
                    "scalar" to matchTextSeq("ON", "SCALAR", "STRING"),
                )
            )
        )
    }

    // sqlglot: TrinoParser._parse_json_query
    open fun parseJsonQuery(): Expression {
        // sqlglot: `self._match(TokenType.COMMA) and self._parse_bitwise()` — absent
        // comma yields False (serde dumps false), matching the Python arg-presence
        val this_ = parseBitwise()
        val expr: kotlin.Any? = if (match(TokenType.COMMA)) parseBitwise() else false
        return expression(
            JSONExtract(
                args(
                    "this" to this_,
                    "expression" to expr,
                    "option" to parseVarFromOptions(jsonQueryOptions, raiseUnmatched = false),
                    "json_query" to true,
                    "quote" to parseJsonQueryQuote(),
                    "on_condition" to parseOnCondition(),
                )
            )
        )
    }

    // sqlglot: Parser._parse_json_value (base parser method reached via FUNCTION_PARSERS)
    open fun parseJsonValue(): Expression {
        val this_ = parseBitwise()
        match(TokenType.COMMA)
        val path = parseBitwise()

        // sqlglot: `self._match(TokenType.RETURNING) and self._parse_type()`
        val returning: kotlin.Any? = if (match(TokenType.RETURNING)) parseType() else false

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

    // brikk extension (docs/brikk-extensions.md #8, NOT sqlglot parity): sqlglot leaves
    // `ALTER TABLE ... SET PROPERTIES ...` unparsed (Command passthrough with a warning).
    // Trino's grammar (reference/trino .../SqlBase.g4 `#setTableProperties`,
    // `property : identifier EQ propertyValue`) takes a bare CSV of property assignments
    // whose keys must be identifiers. We parse them into AlterSet so the generator can
    // render grammar-legal property keys (string-literal keys are normalized to quoted
    // identifiers, e.g. 'foo bar' -> "foo bar").
    override fun parseAlterTableSet(): Expression {
        if (matchTextSeq("PROPERTIES")) {
            val alterSet = expression(AlterSet())
            alterSet.set("option", expression(Var(args("this" to "PROPERTIES"))))
            alterSet.set("expressions", parseCsv { parseSetPropertyAssignment() })
            return alterSet
        }
        return super.parseAlterTableSet()
    }

    // brikk extension (docs/brikk-extensions.md #8): one `property` from Trino's grammar.
    // parseAssignment handles both `key = value` and `key = DEFAULT` (DEFAULT parses as a
    // column identifier); a string-literal key is normalized to a quoted identifier,
    // preserving the property name while making the rendering grammar-legal.
    protected open fun parseSetPropertyAssignment(): Expression? {
        val assignment = parseAssignment() ?: return null
        val key = (assignment as? EQ)?.thisArg as? Expression
        if (key != null && key.isString) {
            assignment.set(
                "this",
                expression(
                    Column(
                        args(
                            "this" to expression(
                                Identifier(args("this" to key.args["this"], "quoted" to true))
                            )
                        )
                    )
                ),
            )
        }
        return assignment
    }

    // sqlglot: Parser.ON_CONDITION_TOKENS
    protected val onConditionTokens: Set<String> get() = setOf("ERROR", "NULL", "TRUE", "FALSE", "EMPTY")

    // sqlglot: Parser._parse_on_condition (ON_CONDITION_EMPTY_BEFORE_ERROR=True)
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
        return null
    }
}

/**
 * Merged parser tables for Trino (sqlglot: TrinoParser class-level dict merges over
 * PrestoParser). Kept in an object so the merges happen once.
 */
object TrinoParserTables {

    // sqlglot: TrinoParser.NO_PAREN_FUNCTIONS
    val NO_PAREN_FUNCTIONS: Map<TokenType, () -> Expression> =
        PrestoParserTables.NO_PAREN_FUNCTIONS + mapOf(
            TokenType.CURRENT_CATALOG to { CurrentCatalog() },
        )

    // sqlglot: TrinoParser.FUNCTIONS
    val FUNCTIONS: Map<String, (List<Expression?>) -> Expression> = buildMap {
        putAll(PrestoParserTables.FUNCTIONS)
        // sqlglot: parser.py FUNCTIONS["CONCAT_WS"] with Trino.CONCAT_WS_COALESCE=True
        put("CONCAT_WS") { a ->
            dev.brikk.house.sql.ast.ConcatWs(
                args("expressions" to a, "safe" to false, "coalesce" to true)
            )
        }
        put("VERSION", fromArgList(listOf(), false) { CurrentVersion(it) })
        // sqlglot: parser.build_logarithm with Trino.LOG_BASE_FIRST = True (base order)
        put("LOG", ::buildLogarithm)
    }

    // sqlglot: TrinoParser.FUNCTION_PARSERS
    val FUNCTION_PARSERS: Map<String, (Parser) -> Expression?> =
        PrestoParserTables.FUNCTION_PARSERS + mapOf<String, (Parser) -> Expression?>(
            "TRIM" to { p -> p.parseTrim() },
            "JSON_QUERY" to { p -> (p as TrinoParser).parseJsonQuery() },
            "JSON_VALUE" to { p -> (p as TrinoParser).parseJsonValue() },
            "LISTAGG" to { p -> (p as PrestoParser).parseStringAgg() },
        )
}
