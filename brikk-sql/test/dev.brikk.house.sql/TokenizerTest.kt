package dev.brikk.house.sql

import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tokenizer parity tests. All expectations below were produced by running the pinned
 * Python sqlglot (v30.12.0-44-g93d16591) base Tokenizer on the same inputs.
 */
class TokenizerTest {

    private fun tokenize(sql: String): List<Pair<TokenType, String>> =
        Tokenizer().tokenize(sql).map { it.tokenType to it.text }

    @Test
    fun keywordsAndStringEscape() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STRING to "it's",
                TokenType.ALIAS to "AS",
                TokenType.VAR to "x",
            ),
            tokenize("SELECT 'it''s' AS x"),
        )
    }

    @Test
    fun numbers() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.NUMBER to "1.5e-3",
                TokenType.COMMA to ",",
                TokenType.NUMBER to "0.5",
                TokenType.COMMA to ",",
                TokenType.NUMBER to "42",
            ),
            tokenize("SELECT 1.5e-3, 0.5, 42"),
        )
    }

    @Test
    fun commentsAttachToTokens() {
        val tokens = Tokenizer().tokenize("SELECT a /* c1 */ FROM t -- trailing")
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.VAR to "a",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
            ),
            tokens.map { it.tokenType to it.text },
        )
        assertEquals(listOf(" c1 "), tokens[1].comments)
        assertEquals(listOf(" trailing"), tokens[3].comments)
    }

    @Test
    fun nestedComments() {
        val tokens = Tokenizer().tokenize("/* outer /* inner */ still */ SELECT 1")
        assertEquals(
            listOf(TokenType.SELECT to "SELECT", TokenType.NUMBER to "1"),
            tokens.map { it.tokenType to it.text },
        )
        assertEquals(listOf(" outer /* inner */ still "), tokens[0].comments)
    }

    @Test
    fun placeholdersAndParameters() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.STAR to "*",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
                TokenType.WHERE to "WHERE",
                TokenType.VAR to "x",
                TokenType.EQ to "=",
                TokenType.PLACEHOLDER to "?",
                TokenType.AND to "AND",
                TokenType.VAR to "y",
                TokenType.EQ to "=",
                TokenType.PARAMETER to "@",
                TokenType.VAR to "param",
                TokenType.AND to "AND",
                TokenType.VAR to "z",
                TokenType.EQ to "=",
                TokenType.COLON to ":",
                TokenType.VAR to "named",
            ),
            tokenize("SELECT * FROM t WHERE x = ? AND y = @param AND z = :named"),
        )
    }

    @Test
    fun quotedIdentifierWithEscapedQuotes() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.IDENTIFIER to "quoted \"col\"",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
            ),
            tokenize("SELECT \"quoted \"\"col\"\"\" FROM t"),
        )
    }

    @Test
    fun nationalStringAndConcat() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.NATIONAL_STRING to "nat",
                TokenType.DPIPE to "||",
                TokenType.STRING to "x",
            ),
            tokenize("SELECT N'nat' || 'x'"),
        )
    }

    @Test
    fun pipeVariantsAreDistinguished() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.VAR to "a",
                TokenType.PIPE to "|",
                TokenType.VAR to "b",
                TokenType.COMMA to ",",
                TokenType.VAR to "c",
                TokenType.DPIPE to "||",
                TokenType.VAR to "d",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
                TokenType.PIPE_GT to "|>",
                TokenType.WHERE to "WHERE",
                TokenType.VAR to "x",
            ),
            tokenize("SELECT a | b, c || d FROM t |> WHERE x"),
        )
    }

    @Test
    fun multiWordKeywords() {
        assertEquals(
            listOf(
                TokenType.SELECT to "SELECT",
                TokenType.VAR to "a",
                TokenType.FROM to "FROM",
                TokenType.VAR to "t",
                TokenType.GROUP_BY to "GROUP BY",
                TokenType.VAR to "a",
                TokenType.ORDER_BY to "ORDER BY",
                TokenType.VAR to "a",
                TokenType.DESC to "DESC",
            ),
            tokenize("SELECT a FROM t GROUP BY a ORDER BY a DESC"),
        )
    }

    @Test
    fun positionsAreTracked() {
        val tokens = Tokenizer().tokenize("FROM Produce\n|> WHERE item")
        // Oracle values from Python sqlglot: line/col are 1-based, col is the token end col.
        val from = tokens[0]
        assertEquals(1, from.line)
        assertEquals(4, from.col)
        assertEquals(0, from.start)
        assertEquals(3, from.end)

        val pipe = tokens[2]
        assertEquals(TokenType.PIPE_GT, pipe.tokenType)
        assertEquals(2, pipe.line)
        assertEquals(2, pipe.col)
        assertEquals(13, pipe.start)
        assertEquals(14, pipe.end)
    }
}
