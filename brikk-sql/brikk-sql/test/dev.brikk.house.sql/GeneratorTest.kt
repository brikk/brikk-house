package dev.brikk.house.sql

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand assertions for the base-dialect Generator. Expected strings are Python oracle
 * outputs (reference/sqlglot parse_one(...).sql(...)).
 */
class GeneratorTest {

    private fun gen(sql: String, generator: Generator = Generator()): String =
        generator.generate(parseOne(sql))

    @Test
    fun roundTripsSimpleSelect() {
        assertEquals("SELECT a FROM b WHERE c = 1", gen("SELECT a FROM b WHERE c = 1"))
    }

    @Test
    fun identifyQuotesIdentifiers() {
        assertEquals("SELECT \"a\" FROM \"b\"", gen("SELECT a FROM b", Generator(identify = true)))
    }

    @Test
    fun preservesComments() {
        assertEquals("SELECT 1 /* one */", gen("SELECT 1 /* one */"))
    }

    @Test
    fun dropsCommentsWhenDisabled() {
        assertEquals("SELECT 1", gen("SELECT 1 /* one */", Generator(comments = false)))
    }

    @Test
    fun normalizeLowercasesUnquotedIdentifiers() {
        assertEquals("SELECT a FROM b", gen("SELECT A FROM B", Generator(normalize = true)))
    }

    @Test
    fun functionFallbackRendersVarLenArgs() {
        assertEquals("SELECT COALESCE(a, b, c)", gen("SELECT COALESCE(a, b, c)"))
    }

    @Test
    fun prettyPrintsWithIndentation() {
        assertEquals(
            "SELECT\n  a,\n  b\nFROM t\nWHERE\n  x = 1\nORDER BY\n  a",
            gen("SELECT a, b FROM t WHERE x = 1 ORDER BY a", Generator(pretty = true)),
        )
    }
}
