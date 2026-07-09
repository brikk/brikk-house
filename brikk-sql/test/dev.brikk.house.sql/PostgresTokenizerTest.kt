package dev.brikk.house.sql

import dev.brikk.house.sql.parser.PostgresTokenizerTables
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Postgres-dialect tokenizer parity tests. All expectations below were produced by
 * running the pinned Python sqlglot (v30.12.0-44-g93d16591) postgres tokenizer
 * (`Dialect.get_or_raise("postgres").tokenize(...)`) on the same inputs.
 */
class PostgresTokenizerTest {

    private fun tokenize(sql: String): List<Pair<TokenType, String>> =
        Tokenizer(PostgresTokenizerTables.CONFIG).tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun dollarQuotedStringsWithTag() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEREDOC_STRING to "hello \$world",
            ),
            tokenize("SELECT \$tag\$hello \$world\$tag\$"),
        )
    }

    @Test
    fun dollarQuotedStringsWithoutTag() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEREDOC_STRING to "plain dollar quoted",
            ),
            tokenize("SELECT \$\$plain dollar quoted\$\$"),
        )
    }

    @Test
    fun doubleColonCasts() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.VAR to "a",
                TokenType.DCOLON to "::",
                TokenType.INT to "INT",
                TokenType.COMMA to ",",
                TokenType.VAR to "b",
                TokenType.DCOLON to "::",
                TokenType.TEXT to "TEXT",
                TokenType.L_BRACKET to "[",
                TokenType.R_BRACKET to "]",
            ),
            tokenize("SELECT a::INT, b::TEXT[]"),
        )
    }

    @Test
    fun numericPositionalParameters() {
        // $1 is not a valid heredoc tag (all digits), so it falls back to PARAMETER
        // via HEREDOC_TAG_IS_IDENTIFIER + HEREDOC_STRING_ALTERNATIVE
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.PARAMETER to "$",
                TokenType.NUMBER to "1",
                TokenType.COMMA to ",",
                TokenType.PARAMETER to "$",
                TokenType.NUMBER to "2",
            ),
            tokenize("SELECT \$1, \$2"),
        )
    }

    @Test
    fun byteBitAndHexStrings() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                // postgres UNESCAPED_SEQUENCES: \n inside E'...' becomes a real newline
                TokenType.BYTE_STRING to "a\nb",
                TokenType.COMMA to ",",
                TokenType.BIT_STRING to "0101",
                TokenType.COMMA to ",",
                TokenType.HEX_STRING to "1F",
            ),
            tokenize("SELECT E'a\\nb', B'0101', X'1F'"),
        )
    }

    @Test
    fun doubledQuoteEscape() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "it's",
            ),
            tokenize("SELECT 'it''s'"),
        )
    }
}
