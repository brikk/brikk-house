package dev.brikk.house.sql

import dev.brikk.house.sql.parser.MysqlTokenizerTables
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MySQL-dialect tokenizer parity tests. All expectations below were produced by
 * running the pinned Python sqlglot (v30.12.0-44-g93d16591) mysql tokenizer
 * (`Dialect.get_or_raise("mysql").tokenize(...)`) on the same inputs.
 */
class MysqlTokenizerTest {

    private fun tokenize(sql: String): List<Pair<TokenType, String>> =
        Tokenizer(MysqlTokenizerTables.CONFIG).tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun backTickIdentifiers() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.IDENTIFIER to "back tick",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
            ),
            tokenize("SELECT `back tick` FROM t"),
        )
    }

    @Test
    fun backslashEscapesAndDoubleQuotedStrings() {
        // mysql treats " as a string quote and \ as a string escape
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "a'b",
                TokenType.COMMA to ",",
                TokenType.STRING to "double\"quoted",
            ),
            tokenize("SELECT 'a\\'b', \"double\\\"quoted\""),
        )
    }

    @Test
    fun hexAndBitStrings() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEX_STRING to "1F",
                TokenType.COMMA to ",",
                TokenType.HEX_STRING to "1f",
                TokenType.COMMA to ",",
                TokenType.HEX_STRING to "AF",
                TokenType.COMMA to ",",
                TokenType.BIT_STRING to "0101",
                TokenType.COMMA to ",",
                TokenType.BIT_STRING to "01",
            ),
            tokenize("SELECT x'1F', X'1f', 0xAF, b'0101', 0b01"),
        )
    }

    @Test
    fun hashLineComments() {
        val tokens = Tokenizer(MysqlTokenizerTables.CONFIG)
            .tokenize("# line comment\nSELECT 1 -- t\n/* block */")
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.NUMBER to "1",
            ),
            tokens.map { it.tokenType to it.text },
        )
        assertEquals(listOf(" line comment"), tokens[0].comments)
        assertEquals(listOf(" t", " block "), tokens[1].comments)
    }

    @Test
    fun sessionParametersUserVariablesAndPlaceholders() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.SESSION_PARAMETER to "@@",
                TokenType.SESSION to "session",
                TokenType.DOT to ".",
                TokenType.VAR to "sql_mode",
                TokenType.COMMA to ",",
                TokenType.PARAMETER to "@",
                TokenType.VAR to "user_var",
                TokenType.COMMA to ",",
                TokenType.COLON to ":",
                TokenType.VAR to "named",
            ),
            tokenize("SELECT @@session.sql_mode, @user_var, :named"),
        )
    }

    @Test
    fun charsetIntroducer() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.INTRODUCER to "_utf8mb4",
                TokenType.STRING to "hello",
            ),
            tokenize("SELECT _utf8mb4'hello'"),
        )
    }

    @Test
    fun unescapedSequencesAreTranslated() {
        // mysql translates \n inside strings to a real newline
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "a\nb",
            ),
            tokenize("SELECT 'a\\nb'"),
        )
    }

    @Test
    fun multiWordCommandKeyword() {
        assertEquals(
            listOf(
                TokenType.COMMAND to "LOCK TABLES",
                TokenType.STRING to "t WRITE",
            ),
            tokenize("LOCK TABLES t WRITE"),
        )
    }
}
