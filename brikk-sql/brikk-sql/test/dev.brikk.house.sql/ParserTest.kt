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
import dev.brikk.house.sql.ast.Add
import dev.brikk.house.sql.ast.Mod
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.UnknownDialectException
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenizerConfigs
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
    fun modUsesMultiplicativePrecedence() {
        val select = assertIs<Select>(parseOne("SELECT 1 + 2 % 3"))
        val add = assertIs<Add>(select.selects.single())
        assertIs<Mod>(add.expressionArg)
    }

    @Test
    fun grantWithoutPrivilegesRaisesParseError() {
        assertFailsWith<ParseError> { parseOne("GRANT ON TABLE tbl TO bob") }
    }

    /** EVAL-07: every entry point rejects unknown dialect names the same way — no base fallback. */
    @Test
    fun unknownDialectIsRejectedConsistentlyByEveryEntryPoint() {
        for (bad in listOf("snowflake", "postgress", "tsql", "SQL Server")) {
            val e1 = assertFailsWith<UnknownDialectException>("parseOne($bad)") { parseOne("SELECT 1", bad) }
            assertEquals(bad, e1.dialectName)
            assertFailsWith<UnknownDialectException>("transpile(read=$bad)") { transpile("SELECT 1", read = bad) }
            assertFailsWith<UnknownDialectException>("transpile(write=$bad)") { transpile("SELECT 1", write = bad) }
            assertFailsWith<UnknownDialectException>("sql($bad)") { parseOne("SELECT 1").sql(bad) }
            assertFailsWith<UnknownDialectException>("TokenizerConfigs($bad)") { TokenizerConfigs.forName(bad) }
            assertEquals(null, Dialects.forNameOrNull(bad))
        }
        // The message lists what IS accepted, and every listed name resolves.
        val message = assertFailsWith<UnknownDialectException> { parseOne("SELECT 1", "nope") }.message!!
        for (name in Dialects.NAMES) {
            assertNotNull(Dialects.forNameOrNull(name), "NAMES entry '$name' must resolve")
            assertEquals(true, name in message, "message should list '$name'")
        }
        // Case/whitespace-insensitive aliases keep working.
        assertEquals("SELECT 1", parseOne("SELECT 1", " PostgreSQL ").sql())
        assertEquals("SELECT 1", transpile("SELECT 1", read = "SparkSQL", write = "Arrow-DataFusion"))
    }

    @Test
    fun groupByStopsBeforeQueryModifiers() {
        assertEquals(
            "SELECT a FROM t GROUP BY ROLLUP (a) LIMIT 2",
            parseOne("SELECT a FROM t GROUP BY ROLLUP (a) LIMIT 2").sql(),
        )
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
