package dev.brikk.house.sql

import dev.brikk.house.sql.ast.ArrayToString
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Null
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.DuckdbGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class BigqueryArrayToStringTest {
    @Test
    fun bq14MatchesPinnedAssertionAndArgumentShape() {
        val source = "SELECT ARRAY_TO_STRING(['cake', 'pie', NULL], '--', 'MISSING') AS text"
        val call = Dialects.BIGQUERY.parseOne(source).find<ArrayToString>()!!
        assertEquals("--", assertIs<Literal>(call.expressionArg).name)
        assertEquals("MISSING", assertIs<Literal>(call.args["null"]).name)
        check(source,
            "SELECT ARRAY_TO_STRING(LIST_TRANSFORM(['cake', 'pie', NULL], x -> COALESCE(x, 'MISSING')), '--') AS text")
    }

    @Test
    fun replacementExpressionsNullsAndEscapingUseSqlBuilders() {
        for ((source, expected) in listOf(
            "ARRAY_TO_STRING(a, '', '')" to
                "ARRAY_TO_STRING(LIST_TRANSFORM(a, x -> COALESCE(x, '')), '')",
            "ARRAY_TO_STRING(a, '--', NULL)" to
                "ARRAY_TO_STRING(LIST_TRANSFORM(a, x -> COALESCE(x, NULL)), '--')",
            "ARRAY_TO_STRING([], '--', 'missing')" to
                "ARRAY_TO_STRING(LIST_TRANSFORM([], x -> COALESCE(x, 'missing')), '--')",
            "ARRAY_TO_STRING(NULL, '--', 'missing')" to
                "ARRAY_TO_STRING(LIST_TRANSFORM(NULL, x -> COALESCE(x, 'missing')), '--')",
            """ARRAY_TO_STRING(['a', NULL], "'", "it's missing")""" to
                "ARRAY_TO_STRING(LIST_TRANSFORM(['a', NULL], x -> COALESCE(x, 'it''s missing')), '''')",
        )) check(source, expected)
        check("ARRAY_TO_STRING(a, d, replacement)",
            "ARRAY_TO_STRING(LIST_TRANSFORM(a, x -> COALESCE(x, replacement)), d)", dynamicDelimiter = true)
        val explicitNull = assertIs<ArrayToString>(Dialects.BIGQUERY.parseOne("ARRAY_TO_STRING(a, d, NULL)"))
        assertIs<Null>(explicitNull.args["null"])
    }

    @Test
    fun lambdaDoesNotCaptureColumnsOrAliasesAndSupportsQuotedIdentifiers() {
        check("SELECT ARRAY_TO_STRING(a, d, X) AS x_1 FROM inputs",
            "SELECT ARRAY_TO_STRING(LIST_TRANSFORM(a, x_2 -> COALESCE(x_2, X)), d) AS x_1 FROM inputs", dynamicDelimiter = true)
        check("SELECT ARRAY_TO_STRING(a, d, x.replacement) FROM inputs AS x",
            "SELECT ARRAY_TO_STRING(LIST_TRANSFORM(a, x_1 -> COALESCE(x_1, x.replacement)), d) FROM inputs AS x", dynamicDelimiter = true)
        check("ARRAY_TO_STRING(`array value`, `delimiter value`, `null text`)",
            "ARRAY_TO_STRING(LIST_TRANSFORM(\"array value\", x -> COALESCE(x, \"null text\")), \"delimiter value\")", dynamicDelimiter = true)
        val tree = Dialects.BIGQUERY.parseOne("ARRAY_TO_STRING(a, d, replacement)")
        assertEquals(
            "ARRAY_TO_STRING(LIST_TRANSFORM(\"a\", \"x\" -> COALESCE(\"x\", \"replacement\")), \"d\")",
            DuckdbGenerator(identify = true, sourceDialect = "bigquery").generate(tree),
        )
    }

    @Test
    fun helperDoesNotMutateOrReparentSourceAst() {
        val tree = Dialects.BIGQUERY.parseOne("SELECT ARRAY_TO_STRING(a, d, COALESCE(X, 'missing')) AS x_1 FROM inputs")
        val call = tree.find<ArrayToString>()!!
        val before = tree.copy()
        val links = tree.walk().map { it to Triple(it.parent, it.argKey, it.index) }.toList()
        val generator = DuckdbGenerator(sourceDialect = "bigquery")
        val first = generator.arrayToStringSql(call)
        assertEquals(first, generator.arrayToStringSql(call))
        assertEquals(before, tree)
        for ((node, link) in links) {
            assertSame(link.first, node.parent)
            assertEquals(link.second, node.argKey)
            assertEquals(link.third, node.index)
        }
    }

    @Test
    fun twoArgumentsAndDuckdbArrayJoinMappingStayNative() {
        for (read in listOf("bigquery", "duckdb")) {
            for (arguments in listOf("['a', NULL, 'b'], '-'", "[], ''", "NULL, '-'", "a, d")) {
                check("ARRAY_TO_STRING($arguments)", "ARRAY_TO_STRING($arguments)", read, dynamicDelimiter = arguments == "a, d")
            }
        }
        check("ARRAY_JOIN(['a', NULL, 'b'], '-')", "ARRAY_TO_STRING(['a', NULL, 'b'], '-')", "duckdb")
    }

    private fun check(source: String, expected: String, read: String = "bigquery", dynamicDelimiter: Boolean = false) {
        val tree = Dialects.forName(read).parseOne(source)
        val before = tree.copy()
        val generator = Dialects.DUCKDB.generator(sourceDialect = read)
        assertEquals(expected, generator.generate(tree), source)
        val diagnostics = if (dynamicDelimiter) listOf(
            "DuckDB ARRAY_TO_STRING requires a constant delimiter; row-dependent delimiters need a separate lowering",
        ) else emptyList()
        assertEquals(diagnostics, generator.unsupportedMessages, source)
        assertEquals(before, tree, source)
    }
}
