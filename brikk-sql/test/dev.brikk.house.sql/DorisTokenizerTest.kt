package dev.brikk.house.sql

import dev.brikk.house.sql.parser.DorisTokenizerTables
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Doris-dialect tokenizer parity tests. All expectations below were produced by
 * running the pinned Python sqlglot (v30.12.0-44-g93d16591) doris tokenizer
 * (`Dialect.get_or_raise("doris").tokenize(...)`) on the same inputs.
 */
class DorisTokenizerTest {

    private fun tokenize(sql: String): List<Pair<TokenType, String>> =
        Tokenizer(DorisTokenizerTables.CONFIG).tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun backTickIdentifiersAndDigitLeadingVars() {
        // doris: IDENTIFIERS_CAN_START_WITH_DIGIT — "2t" lexes as a single VAR
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.IDENTIFIER to "back tick",
                TokenType.FROM to "FROM",
                TokenType.VAR to "2t",
            ),
            tokenize("SELECT `back tick` FROM 2t"),
        )
    }

    @Test
    fun backslashEscapesAndDoubleQuotedStrings() {
        // doris (mysql-style): " is a string quote, \n is an unescaped sequence
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "a\nb",
                TokenType.COMMA to ",",
                TokenType.STRING to "double\"quoted",
            ),
            tokenize("SELECT 'a\\nb', \"double\\\"quoted\""),
        )
    }

    @Test
    fun hexAndBitStrings() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEX_STRING to "1F",
                TokenType.COMMA to ",",
                TokenType.HEX_STRING to "AF",
                TokenType.COMMA to ",",
                TokenType.BIT_STRING to "0101",
                TokenType.COMMA to ",",
                TokenType.BIT_STRING to "01",
            ),
            tokenize("SELECT x'1F', 0xAF, b'0101', 0b01"),
        )
    }

    @Test
    fun sessionParameters() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.SESSION_PARAMETER to "@@",
                TokenType.SESSION to "session",
                TokenType.DOT to ".",
                TokenType.VAR to "sql_mode",
            ),
            tokenize("SELECT @@session.sql_mode"),
        )
    }

    @Test
    fun escapeFollowChars() {
        // doris (mysql-style ESCAPE_FOLLOW_CHARS): \% stays literal, \q drops the backslash
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "a\\%b",
                TokenType.COMMA to ",",
                TokenType.STRING to "aqb",
            ),
            tokenize("SELECT 'a\\%b', 'a\\qb'"),
        )
    }
}
