package dev.brikk.house.sql.parser

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.JSONPath
import dev.brikk.house.sql.ast.JSONPathFilter
import dev.brikk.house.sql.ast.JSONPathKey
import dev.brikk.house.sql.ast.JSONPathRecursive
import dev.brikk.house.sql.ast.JSONPathRoot
import dev.brikk.house.sql.ast.JSONPathScript
import dev.brikk.house.sql.ast.JSONPathSelector
import dev.brikk.house.sql.ast.JSONPathSlice
import dev.brikk.house.sql.ast.JSONPathSubscript
import dev.brikk.house.sql.ast.JSONPathUnion
import dev.brikk.house.sql.ast.JSONPathWildcard
import dev.brikk.house.sql.ast.args

/**
 * Port of reference/sqlglot/sqlglot/jsonpath.py for the base "sqlglot" dialect
 * (JSONPathTokenizer not overridden, JSON_PATH_SINGLE_DOT_IS_WILDCARD=false).
 */

// sqlglot: jsonpath.JSONPathTokenizer — SINGLE_TOKENS and KEYWORDS fully replace the
// base tokens.Tokenizer tables; QUOTES/IDENTIFIERS/COMMENTS are inherited, and the
// derived tables (_QUOTES, _FORMAT_STRINGS, _COMMENTS) are spelled out exactly as
// _TokenizerBase.__init_subclass__ computes them (no "/*+" comment entry because
// HINT_START is not in this KEYWORDS table).
internal val JSONPATH_TOKENIZER_CONFIG: TokenizerConfig = TokenizerConfig(
    singleTokens = mapOf(
        '(' to TokenType.L_PAREN,
        ')' to TokenType.R_PAREN,
        '[' to TokenType.L_BRACKET,
        ']' to TokenType.R_BRACKET,
        ':' to TokenType.COLON,
        ',' to TokenType.COMMA,
        '-' to TokenType.DASH,
        '.' to TokenType.DOT,
        '?' to TokenType.PLACEHOLDER,
        '@' to TokenType.PARAMETER,
        '\'' to TokenType.QUOTE,
        '"' to TokenType.QUOTE,
        '$' to TokenType.DOLLAR,
        '*' to TokenType.STAR,
    ),
    keywords = mapOf(".." to TokenType.DOT),
    quotes = mapOf("'" to "'"),
    formatStrings = mapOf(
        "n'" to StringFormat("'", TokenType.NATIONAL_STRING),
        "N'" to StringFormat("'", TokenType.NATIONAL_STRING),
    ),
    identifiers = mapOf('"' to "\""),
    comments = mapOf("--" to null, "/*" to "*/", "{#" to "#}"),
    // sqlglot: jsonpath.JSONPathTokenizer.STRING_ESCAPES / IDENTIFIER_ESCAPES = ["\\"]
    // (BYTE_STRING_ESCAPES is auto-copied from STRING_ESCAPES on subclassing)
    stringEscapes = setOf('\\'),
    byteStringEscapes = setOf('\\'),
    identifierEscapes = setOf('\\'),
    // sqlglot: jsonpath.JSONPathTokenizer.NUMBERS_CAN_HAVE_DECIMALS = False
    numbersCanHaveDecimals = false,
)

// sqlglot: jsonpath.JSONPathTokenizer.VAR_TOKENS
internal val JSONPATH_VAR_TOKENS: Set<TokenType> = setOf(TokenType.VAR)

/**
 * Takes in a JSON path string and parses it into a JSONPath expression.
 *
 * sqlglot: jsonpath.parse (dialect fixed to the base "sqlglot" dialect)
 */
fun parseJsonPath(path: String): JSONPath = JsonPathParser(path).parse()

/**
 * State holder for one jsonpath.parse call (Python uses closures over `i`; Kotlin needs
 * a class because _parse_literal and _parse_bracket are mutually recursive).
 */
private class JsonPathParser(private val path: String) {

    private val tokens: List<Token> = Tokenizer(JSONPATH_TOKENIZER_CONFIG).tokenize(path)
    private val size: Int = tokens.size
    private var i: Int = 0

    // sqlglot: jsonpath.parse._curr
    private fun curr(): TokenType? = if (i < size) tokens[i].tokenType else null

    // sqlglot: jsonpath.parse._prev
    private fun prev(): Token = tokens[i - 1]

    // sqlglot: jsonpath.parse._advance
    private fun advance(): Token {
        i += 1
        return prev()
    }

    // sqlglot: jsonpath.parse._error
    private fun error(msg: String): String = "$msg at index $i: $path"

    // sqlglot: jsonpath.parse._match
    private fun match(tokenType: TokenType, raiseUnmatched: kotlin.Boolean = false): Token? {
        if (curr() == tokenType) return advance()
        if (raiseUnmatched) throw ParseError(error("Expected TokenType.$tokenType"))
        return null
    }

    // sqlglot: jsonpath.parse._match_set
    private fun matchSet(types: Set<TokenType>): Token? =
        if (curr() in types) advance() else null

    /**
     * Python truthiness over the jsonpath literal union (String | Int | false |
     * JSONPathPart): false, 0 and "" are falsy, expressions are always truthy.
     */
    private fun truthy(value: kotlin.Any?): kotlin.Boolean = when (value) {
        null, false -> false
        is Int -> value != 0
        is String -> value.isNotEmpty()
        else -> true
    }

    // sqlglot: jsonpath.parse._parse_literal — returns String | Int | JSONPathWildcard |
    // JSONPathScript | JSONPathFilter | false (Python returns False when nothing matched)
    private fun parseLiteral(): kotlin.Any {
        val token = match(TokenType.STRING) ?: match(TokenType.IDENTIFIER)
        if (token != null) return token.text
        if (match(TokenType.STAR) != null) return JSONPathWildcard()
        if (match(TokenType.PLACEHOLDER) != null || match(TokenType.L_PAREN) != null) {
            val script = prev().text == "("
            val start = i

            while (true) {
                if (match(TokenType.L_BRACKET) != null) {
                    parseBracket() // nested call which we can throw away
                }
                val c = curr()
                if (c == TokenType.R_BRACKET || c == null) break
                advance()
            }

            // Token.end is the inclusive offset of the token's last char, so the
            // exclusive substring end excludes it — exactly like Python's slice.
            val end = if (i < size) tokens[i].end else tokens[size - 1].end
            val text = path.substring(tokens[start].start, end)
            return if (script) {
                JSONPathScript(args("this" to text))
            } else {
                JSONPathFilter(args("this" to text))
            }
        }

        var number = if (match(TokenType.DASH) != null) "-" else ""

        val numberToken = match(TokenType.NUMBER)
        if (numberToken != null) number += numberToken.text

        if (number.isNotEmpty()) return number.toInt()

        return false
    }

    // sqlglot: jsonpath.parse._parse_slice
    private fun parseSlice(): kotlin.Any {
        val start = parseLiteral()
        val end = if (match(TokenType.COLON) != null) parseLiteral() else null
        val step = if (match(TokenType.COLON) != null) parseLiteral() else null

        if (end == null && step == null) return start

        return JSONPathSlice(args("start" to start, "end" to end, "step" to step))
    }

    // sqlglot: jsonpath.parse._parse_bracket
    private fun parseBracket(): Expression {
        var literal = parseSlice()

        val node: Expression
        if (literal is String || literal != false) {
            val indexes = mutableListOf<kotlin.Any?>(literal)
            while (match(TokenType.COMMA) != null) {
                literal = parseSlice()

                if (truthy(literal)) indexes.add(literal)
            }

            node = if (indexes.size == 1) {
                when {
                    literal is String -> JSONPathKey(args("this" to indexes[0]))
                    literal is JSONPathScript || literal is JSONPathFilter ->
                        JSONPathSelector(args("this" to indexes[0]))
                    else -> JSONPathSubscript(args("this" to indexes[0]))
                }
            } else {
                JSONPathUnion(args("expressions" to indexes))
            }
        } else {
            throw ParseError(error("Cannot have empty segment"))
        }

        match(TokenType.R_BRACKET, raiseUnmatched = true)

        return node
    }

    /**
     * Consumes & returns the text for a var. In BigQuery it's valid to have a key with
     * spaces in it, e.g JSON_QUERY(..., '$. a b c ') should produce a single
     * JSONPathKey(' a b c '). This is done by merging "consecutive" vars until a key
     * separator is found (dot, colon etc) or the path string is exhausted.
     *
     * sqlglot: jsonpath.parse._parse_var_text
     */
    private fun parseVarText(): String {
        val prevIndex = i - 2

        while (matchSet(JSONPATH_VAR_TOKENS) != null) {
            // keep consuming consecutive vars
        }

        val start = if (prevIndex < 0) 0 else tokens[prevIndex].end + 1

        return if (i >= tokens.size) {
            // This key is the last token for the path, so it's text is the remaining path
            path.substring(start)
        } else {
            path.substring(start, tokens[i].start)
        }
    }

    // sqlglot: jsonpath.parse (main loop)
    fun parse(): JSONPath {
        // We canonicalize the JSON path AST so that it always starts with a
        // "root" element, so paths like "field" will be generated as "$.field"
        match(TokenType.DOLLAR)
        val expressions = mutableListOf<Expression>(JSONPathRoot())

        while (curr() != null) {
            if (match(TokenType.DOT) != null || match(TokenType.COLON) != null) {
                val recursive = prev().text == ".."

                val value: kotlin.Any? = when {
                    matchSet(JSONPATH_VAR_TOKENS) != null -> parseVarText()
                    match(TokenType.IDENTIFIER) != null -> prev().text
                    match(TokenType.STAR) != null -> JSONPathWildcard()
                    else -> null
                }

                if (recursive) {
                    expressions.add(JSONPathRecursive(args("this" to value)))
                } else if (truthy(value)) {
                    expressions.add(JSONPathKey(args("this" to value)))
                } else {
                    // base dialect: JSON_PATH_SINGLE_DOT_IS_WILDCARD=false
                    throw ParseError(error("Expected key name or * after DOT"))
                }
            } else if (match(TokenType.L_BRACKET) != null) {
                expressions.add(parseBracket())
            } else if (matchSet(JSONPATH_VAR_TOKENS) != null) {
                expressions.add(JSONPathKey(args("this" to parseVarText())))
            } else if (match(TokenType.IDENTIFIER) != null) {
                expressions.add(JSONPathKey(args("this" to prev().text)))
            } else if (match(TokenType.STAR) != null) {
                expressions.add(JSONPathWildcard())
            } else {
                throw ParseError(error("Unexpected TokenType.${tokens[i].tokenType}"))
            }
        }

        return JSONPath(args("expressions" to expressions))
    }
}
