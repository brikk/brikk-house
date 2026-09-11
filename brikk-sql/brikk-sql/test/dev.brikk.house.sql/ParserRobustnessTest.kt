package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserRobustnessTest {
    @Test
    fun deeplyNestedDelimitersFailWithAPositionedParseError() {
        for (sql in listOf(
            "SELECT\n" + "(".repeat(129) + "1" + ")".repeat(129),
            "SELECT\n" + "f(".repeat(129) + "1" + ")".repeat(129),
            "SELECT\n" + "[".repeat(129) + "1" + "]".repeat(129),
        )) {
            val error = assertFailsWith<ParseError> { parseOne(sql) }
            val info = error.errors.single()
            assertEquals("Nesting too deep", info.description)
            assertEquals(2, info.line)
            assertTrue((info.col ?: 0) >= 129)
            assertTrue(info.highlight == "(" || info.highlight == "[")
        }
    }

    @Test
    fun moderateNestingAndLargeFlatInputsStillParse() {
        parseOne("SELECT " + "(".repeat(32) + "1" + ")".repeat(32))
        val projections = (1..2048).joinToString(", ") { "$it AS c$it" }
        val select = parseOne("SELECT $projections") as Select
        assertEquals(2048, select.expressionsArg.size)
    }

    @Test
    fun malformedAndEmptyInputsFailPredictably() {
        val stringError = assertFailsWith<TokenError> { parseOne("SELECT 'unterminated") }
        assertContains(stringError.message.orEmpty(), "Missing '")

        val parenError = assertFailsWith<ParseError> { parseOne("SELECT (1") }
        assertEquals("Expecting )", parenError.errors.single().description)

        val emptyError = assertFailsWith<ParseError> { parseOne("") }
        assertContains(emptyError.message.orEmpty(), "No expression was parsed")
    }
}
