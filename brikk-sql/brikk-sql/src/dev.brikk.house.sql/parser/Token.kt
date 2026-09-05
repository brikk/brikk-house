package dev.brikk.house.sql.parser

import dev.brikk.house.sql.ast.Expression

/**
 * sqlglot: Expression.update_positions (`other: Token` branch). Stores the token's
 * line/col/start/end into the node's meta. Lives in the parser package (as an
 * extension) so the ast package keeps no dependency on tokens.
 */
fun <E : Expression> E.updatePositions(token: Token): E {
    val m = meta
    m["line"] = token.line
    m["col"] = token.col
    m["start"] = token.start
    m["end"] = token.end
    // brikk-native (no sqlglot counterpart): start-anchored line/col so callers can
    // point at where a token BEGINS without re-deriving it from char offsets. The
    // sqlglot-parity "line"/"col" above are the token's END position.
    m["line_start"] = token.lineStart
    m["col_start"] = token.colStart
    return this
}

/**
 * A single lexical token.
 *
 * sqlglot: tokenizer_core.Token — same fields, same semantics:
 * [start]/[end] are absolute char offsets into the source ([end] inclusive),
 * [line]/[col] are 1-based and refer to the token's end position,
 * [comments] holds comment texts attached to this token.
 *
 * brikk-native additions (no sqlglot counterpart): [lineStart]/[colStart] are the
 * 1-based line/column of the token's START (the sqlglot [line]/[col] are its END).
 * These make the start-anchored position available without counting newlines, and
 * stay correct for tokens that straddle a line break (multi-line string literals).
 */
class Token(
    val tokenType: TokenType,
    val text: String,
    val line: Int = 1,
    val col: Int = 1,
    val start: Int = 0,
    val end: Int = 0,
    val comments: MutableList<String> = mutableListOf(),
    val lineStart: Int = line,
    val colStart: Int = col,
) {
    override fun toString(): String =
        "<Token token_type: TokenType.$tokenType, text: $text, line: $line, col: $col, " +
            "start: $start, end: $end, lineStart: $lineStart, colStart: $colStart, " +
            "comments: $comments>"

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
