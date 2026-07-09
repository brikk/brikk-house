package dev.brikk.house.sql.parser

/**
 * A single lexical token.
 *
 * sqlglot: tokenizer_core.Token — same fields, same semantics:
 * [start]/[end] are absolute char offsets into the source ([end] inclusive),
 * [line]/[col] are 1-based and refer to the token's end position,
 * [comments] holds comment texts attached to this token.
 */
class Token(
    val tokenType: TokenType,
    val text: String,
    val line: Int = 1,
    val col: Int = 1,
    val start: Int = 0,
    val end: Int = 0,
    val comments: MutableList<String> = mutableListOf(),
) {
    override fun toString(): String =
        "<Token token_type: TokenType.$tokenType, text: $text, line: $line, col: $col, " +
            "start: $start, end: $end, comments: $comments>"

    companion object {
        /** sqlglot: Token.number */
        fun number(number: Int): Token = Token(TokenType.NUMBER, number.toString())

        /** sqlglot: Token.string */
        fun string(string: String): Token = Token(TokenType.STRING, string)

        /** sqlglot: Token.identifier */
        fun identifier(identifier: String): Token = Token(TokenType.IDENTIFIER, identifier)

        /** sqlglot: Token.var */
        fun variable(variable: String): Token = Token(TokenType.VAR, variable)
    }
}

/**
 * A "format string" spec: maps a string prefix (e.g. `n'`, `x'`, `b'`) to its end
 * delimiter and produced token type.
 *
 * sqlglot: the `(end, TokenType)` tuples in Tokenizer._FORMAT_STRINGS.
 */
data class StringFormat(val end: String, val tokenType: TokenType)

/** sqlglot: errors.TokenError */
class TokenError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
