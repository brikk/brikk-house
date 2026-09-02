package dev.brikk.house.sql

import dev.brikk.house.sql.dialects.DorisDialect
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

    // brikk extension #19 (docs/brikk-extensions.md, NOT sqlglot parity): the dialect's
    // TOKENIZER_CONFIG layers Doris DDL type keywords over the generated tables. The
    // generated DorisTokenizerTables.CONFIG itself stays oracle-parity (VAR for all of these).
    @Test
    fun dialectConfigAddsDorisStorageTypeKeywords() {
        val sql = "LARGEINT IPV4 IPV6 DECIMALV2 DECIMALV3 BITMAP HLL QUANTILE_STATE"
        val generated = tokenize(sql).map { it.first }
        assertEquals(List(8) { TokenType.VAR }, generated)

        val dialect = Tokenizer(DorisDialect.TOKENIZER_CONFIG).tokenize(sql).map { it.tokenType }
        assertEquals(
            listOf(
                TokenType.INT128, TokenType.IPV4, TokenType.IPV6, TokenType.DECIMAL,
                TokenType.DECIMAL, TokenType.BITMAP, TokenType.HLL, TokenType.QUANTILE_STATE,
            ),
            dialect,
        )
    }
}
