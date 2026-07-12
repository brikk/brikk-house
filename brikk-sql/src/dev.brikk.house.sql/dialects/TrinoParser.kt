package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.AlterSet
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.CurrentCatalog
import dev.brikk.house.sql.ast.CurrentVersion
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractQuote
import dev.brikk.house.sql.ast.JSONValue
import dev.brikk.house.sql.ast.OnCondition
import dev.brikk.house.sql.ast.Var
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
