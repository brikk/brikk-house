package dev.brikk.house.sql

import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TrinoTokenizerTables
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Trino-dialect tokenizer parity tests. All expectations below were produced by
 * running the pinned Python sqlglot (v30.12.0-44-g93d16591) trino tokenizer
 * (`Dialect.get_or_raise("trino").tokenize(...)`) on the same inputs.
 */
class TrinoTokenizerTest {

    private fun tokenize(sql: String): List<Pair<TokenType, String>> =
        Tokenizer(TrinoTokenizerTables.CONFIG).tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun unicodeStrings() {
        // trino: U&'...' — escape sequences are kept verbatim in the token text
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.UNICODE_STRING to "d\\0061t\\0061",
            ),
            tokenize("SELECT U&'d\\0061t\\0061'"),
        )
    }

    @Test
    fun hexAndNationalStrings() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEX_STRING to "1F",
                TokenType.COMMA to ",",
                TokenType.NATIONAL_STRING to "nat",
            ),
            tokenize("SELECT X'1F', N'nat'"),
        )
    }

    @Test
    fun doubleQuotedIdentifiers() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.IDENTIFIER to "quoted id",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
            ),
            tokenize("SELECT \"quoted id\" FROM t"),
        )
    }

    @Test
    fun doubledQuoteEscapeAndConcat() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "it's",
                TokenType.COMMA to ",",
                TokenType.VAR to "a",
                TokenType.DPIPE to "||",
                TokenType.VAR to "b",
            ),
            tokenize("SELECT 'it''s', a || b"),
        )
    }

    @Test
    fun timestampWithTimeZone() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.TIMESTAMP to "TIMESTAMP",
                TokenType.STRING to "2020-01-01",
                TokenType.VAR to "AT",
                TokenType.TIME to "TIME",
                TokenType.VAR to "ZONE",
                TokenType.STRING to "UTC",
            ),
            tokenize("SELECT TIMESTAMP '2020-01-01' AT TIME ZONE 'UTC'"),
        )
    }
}
