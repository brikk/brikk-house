package dev.brikk.house.sql

import dev.brikk.house.sql.parser.DuckdbTokenizerTables
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DuckDB-dialect tokenizer parity tests. All expectations below were produced by
 * running the pinned Python sqlglot (v30.12.0-44-g93d16591) duckdb tokenizer
 * (`Dialect.get_or_raise("duckdb").tokenize(...)`) on the same inputs.
 */
class DuckdbTokenizerTest {

    private fun tokenize(sql: String): List<Pair<TokenType, String>> =
        Tokenizer(DuckdbTokenizerTables.CONFIG).tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun dollarQuotedHeredocStrings() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.HEREDOC_STRING to "heredoc body",
            ),
            tokenize("SELECT \$tag\$heredoc body\$tag\$"),
        )
    }

    @Test
    fun dollarParameters() {
        // $1 (all digits) is not a valid heredoc tag, so it falls back to PARAMETER
        // via HEREDOC_TAG_IS_IDENTIFIER + HEREDOC_STRING_ALTERNATIVE; so does $named_param
        // followed by a non-$ end.
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.PARAMETER to "$",
                TokenType.NUMBER to "1",
                TokenType.COMMA to ",",
                TokenType.PARAMETER to "$",
                TokenType.VAR to "named_param",
            ),
            tokenize("SELECT \$1, \$named_param"),
        )
    }

    @Test
    fun lambdaArrow() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.VAR to "list_transform",
                TokenType.L_PAREN to "(",
                TokenType.L_BRACKET to "[",
                TokenType.NUMBER to "1",
                TokenType.COMMA to ",",
                TokenType.NUMBER to "2",
                TokenType.R_BRACKET to "]",
                TokenType.COMMA to ",",
                TokenType.VAR to "x",
                TokenType.ARROW to "->",
                TokenType.VAR to "x",
                TokenType.PLUS to "+",
                TokenType.NUMBER to "1",
                TokenType.R_PAREN to ")",
            ),
            tokenize("SELECT list_transform([1, 2], x -> x + 1)"),
        )
    }

    @Test
    fun underscoreSeparatedNumbers() {
        // duckdb: NUMBERS_CAN_BE_UNDERSCORE_SEPARATED — normalized to plain digits
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.NUMBER to "1000000",
            ),
            tokenize("SELECT 1_000_000"),
        )
    }

    @Test
    fun byteStrings() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                // duckdb UNESCAPED_SEQUENCES: \n inside e'...' becomes a real newline
                TokenType.BYTE_STRING to "a\nb",
            ),
            tokenize("SELECT e'a\\nb'"),
        )
    }

    @Test
    fun darrowAndIntegerDivision() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.VAR to "a",
                TokenType.DARROW to "->>",
                TokenType.STRING to "b",
                TokenType.COMMA to ",",
                TokenType.VAR to "c",
                TokenType.DIV to "//",
                TokenType.VAR to "d",
            ),
            tokenize("SELECT a ->> 'b', c // d"),
        )
    }
}
