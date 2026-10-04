package dev.brikk.house.sql.compiler

import dev.brikk.house.sql.compiler.analysis.SqlLiteralRanges
import dev.brikk.house.sql.compiler.analysis.SqlPiece
import dev.brikk.house.sql.compiler.analysis.SqlTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlLiteralRangesTest {
    @Test
    fun unicodeEscapesMapToTheWholeAuthoredEscape() {
        val source = "\"SELECT \\u0078\""
        val template = SqlTemplate.text("SELECT x")
        val range = SqlLiteralRanges.map(source, template, 7, 8)!!
        assertEquals("\\u0078", source.substring(range))
    }

    @Test
    fun trimmedTemplatesMapLaterTextAfterTheInterpolation() {
        val source = "\"\"\"\n    SELECT ${'$'}n AS id, :missing AS x\n\"\"\".trimIndent()"
        val template = SqlTemplate(listOf(SqlPiece.Text("\n    SELECT "), SqlPiece.Bind("n"), SqlPiece.Text(" AS id, :missing AS x\n")))
            .trimmed { it.trimIndent() }
        val offset = template.sql.indexOf(":missing")
        val range = SqlLiteralRanges.map(source, template, offset, offset + 8)!!
        assertEquals(":missing", source.substring(range))
    }

    @Test
    fun foldedAndNonLiteralArgumentsDoNotReceiveInventedRanges() {
        assertEquals(null, SqlLiteralRanges.map("SQL_CONSTANT", SqlTemplate.text("SELECT 1"), 0, 6))
        assertEquals(null, SqlLiteralRanges.map("\"SELECT ${'$'}CONST\"", SqlTemplate.text("SELECT 1"), 0, 6))
        assertEquals(null, SqlLiteralRanges.map("\"SELECT 1\"", SqlTemplate.text("SELECT 2"), 0, 6))
    }
}
