package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.CTE
import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.JSONExtract
import dev.brikk.house.sql.ast.JSONExtractScalar
import dev.brikk.house.sql.ast.JSONPath
import dev.brikk.house.sql.ast.JSONPathKey
import dev.brikk.house.sql.ast.JSONPathRoot
import dev.brikk.house.sql.ast.JSONPathSubscript
import dev.brikk.house.sql.ast.JSONPathWildcard
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Smoke tests for the parser's public surface (the oracle parity lives in the corpus test). */
class ParserTest {

    @Test
    fun findLocatesTheWhereClause() {
        val select = parseOne("SELECT a FROM t WHERE x = 1")
        val where = select.find<Where>()
        assertNotNull(where)
        assertIs<EQ>(where.thisArg)
    }

    @Test
    fun aliasNameIsExtractable() {
        val select = parseOne("SELECT a AS x, b y FROM t")
        val projections = (select as Select).selects
        val first = assertIs<Alias>(projections[0])
        assertEquals("x", first.alias)
        assertEquals("a", (first.thisArg as dev.brikk.house.sql.ast.Expression).name)
        assertEquals("y", (projections[1] as Alias).aliasOrName)
    }

    @Test
    fun malformedInputRaisesParseErrorWithPosition() {
        val error = assertFailsWith<ParseError> { parseOne("SELECT 1 +") }
        val info = error.errors.first()
        assertEquals(1, info.line)
        assertEquals(10, info.col)
    }

    @Test
    fun unknownLeadingTokenFallsBackToCommand() {
        val command = assertIs<Command>(parseOne("EXPLAIN whatever"))
        assertEquals("EXPLAIN", command.name)
        val payload = assertIs<Literal>(command.expressionArg)
        assertEquals("whatever", payload.name)
    }

    @Test
    fun setOperationShape() {
        val union = assertIs<Union>(parseOne("SELECT a FROM t UNION ALL SELECT b FROM u"))
        assertEquals(false, union.args["distinct"])
        assertIs<Select>(union.left)
        assertIs<Select>(union.right)
    }

    @Test
    fun jsonExtractParsesDotKeyPath() {
        val select = assertIs<Select>(parseOne("SELECT JSON_EXTRACT(x, '$.name')"))
        val extract = assertIs<JSONExtract>(select.selects.single())
        val path = assertIs<JSONPath>(extract.expressionArg)
        val parts = path.expressionsArg
        assertEquals(2, parts.size)
        assertIs<JSONPathRoot>(parts[0])
        val key = assertIs<JSONPathKey>(parts[1])
        assertEquals("name", key.thisArg)
    }

    @Test
    fun jsonExtractScalarParsesSubscriptThenKey() {
        val select = assertIs<Select>(parseOne("SELECT JSON_EXTRACT_SCALAR(x, '$[0].a')"))
        val extract = assertIs<JSONExtractScalar>(select.selects.single())
        assertEquals(false, extract.args["scalar_only"])
        val path = assertIs<JSONPath>(extract.expressionArg)
        val parts = path.expressionsArg
        assertEquals(3, parts.size)
        assertIs<JSONPathRoot>(parts[0])
        val subscript = assertIs<JSONPathSubscript>(parts[1])
        assertEquals(0, subscript.thisArg)
        assertEquals("a", assertIs<JSONPathKey>(parts[2]).thisArg)
    }

    @Test
    fun jsonExtractParsesDotWildcardPath() {
        val select = assertIs<Select>(parseOne("SELECT JSON_EXTRACT(x, '$.*')"))
        val extract = assertIs<JSONExtract>(select.selects.single())
        val path = assertIs<JSONPath>(extract.expressionArg)
        val parts = path.expressionsArg
        assertEquals(2, parts.size)
        assertIs<JSONPathRoot>(parts[0])
        val key = assertIs<JSONPathKey>(parts[1])
        assertIs<JSONPathWildcard>(key.thisArg)
    }

    @Test
    fun cteShape() {
        val select = assertIs<Select>(parseOne("WITH cte AS (SELECT a FROM t) SELECT * FROM cte"))
        val with = assertIs<With>(select.args["with_"])
        val cte = assertIs<CTE>(with.expressionsArg.single())
        assertEquals("cte", cte.alias)
        assertIs<Select>(cte.thisArg)
    }
}
