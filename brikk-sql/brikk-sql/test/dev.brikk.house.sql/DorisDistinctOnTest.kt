package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.Qualify
import dev.brikk.house.sql.ast.RowNumber
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.Window
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.DorisGenerator
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DorisDistinctOnTest {
    private fun assertNative(source: String, expected: String) {
        val fragment = SqlFragment(source, "doris")
        val before = Serde.dump(fragment.ast)
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty)
            if (pretty) assertTrue('\n' in result.sql) else assertEquals(expected, result.sql)
            val generated = parseOne(result.sql, "doris")
            assertEquals(parseOne(expected, "doris"), generated, result.sql)
            assertEquals(emptyList(), result.unsupportedMessages)
            assertEquals(result.sql, result.sourceMap?.output)
            val rank = generated.findAll<RowNumber>().single()
            assertIs<Qualify>(rank.findAncestor(Qualify::class))
            assertNull(rank.findAncestor(Alias::class), "rank must not be projected or named")
            assertEquals(before, Serde.dump(fragment.ast), "generation must not mutate the source AST")
        }
    }

    @Test
    fun bareStarKeepsOrderLimitAndOffset() {
        assertNative(
            "SELECT DISTINCT ON (id) * FROM t ORDER BY id, stamp DESC LIMIT 3 OFFSET 2",
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY id ORDER BY id, stamp DESC) = 1 " +
                "ORDER BY id, stamp DESC LIMIT 3 OFFSET 2",
        )
        assertNative(
            "SELECT DISTINCT ON (id) * FROM t",
            "SELECT * FROM t QUALIFY ROW_NUMBER() OVER (PARTITION BY id ORDER BY id) = 1",
        )
    }

    @Test
    fun qualifiedAndMixedStarsKeepTheOriginalOutput() {
        for (projection in listOf("x.*", "x.*, x._row_number", "x.*, x.payload + 1 AS extra", "x.*, 7 AS _row_number")) {
            assertNative(
                "SELECT DISTINCT ON (x.id) $projection FROM t AS x ORDER BY x.id, x.stamp DESC",
                "SELECT $projection FROM t AS x " +
                    "QUALIFY ROW_NUMBER() OVER (PARTITION BY x.id ORDER BY x.id, x.stamp DESC) = 1 " +
                    "ORDER BY x.id, x.stamp DESC",
            )
        }
        assertNative(
            "SELECT DISTINCT ON (_row_number) *, payload + 1 AS extra FROM t ORDER BY _row_number",
            "SELECT *, payload + 1 AS extra FROM t " +
                "QUALIFY ROW_NUMBER() OVER (PARTITION BY _row_number ORDER BY _row_number) = 1 ORDER BY _row_number",
        )
        // A qualified source column is not a reference to the same-named projection alias.
        assertNative(
            "SELECT DISTINCT ON (x.id) x.*, x.payload AS id FROM t AS x ORDER BY x.id",
            "SELECT x.*, x.payload AS id FROM t AS x " +
                "QUALIFY ROW_NUMBER() OVER (PARTITION BY x.id ORDER BY x.id) = 1 ORDER BY x.id",
        )
    }

    @Test
    fun pipeHeadsAndStagesDoNotExportARankThroughSubsequentStars() {
        for (source in listOf(
            "SELECT DISTINCT ON (id) * FROM t |> SELECT *",
            "SELECT DISTINCT ON (x.id) x.* FROM t AS x |> SELECT *",
            "FROM t |> SELECT DISTINCT ON (id) * |> SELECT *",
            "FROM t AS x |> SELECT DISTINCT ON (x.id) x.* |> SELECT *",
        )) {
            val fragment = SqlFragment(source, "doris")
            val before = Serde.dump(fragment.ast)
            for (pretty in listOf(false, true)) {
                val result = fragment.toExecutable("doris", pretty = pretty)
                val generated = parseOne(result.sql, "doris")
                val ranked = generated.findAll<Select>().single { it.args["qualify"] != null }
                val projection = if ("x.*" in source) "x.*" else "*"
                assertEquals(parseOne("SELECT $projection", "doris").selects, ranked.selects)
                assertEquals(1, generated.findAll<RowNumber>().count())
                assertNull(generated.find(Distinct::class))
                assertTrue("_row_number" !in result.sql && "_w" !in result.sql, result.sql)
                assertEquals(emptyList(), result.unsupportedMessages)
                assertEquals(result.sql, result.sourceMap?.output)
                assertEquals(before, Serde.dump(fragment.ast))
            }
        }
    }

    @Test
    fun loweringPreservesExpressionsMetadataAndOriginalParentLinks() {
        val original = assertIs<Select>(parseOne(
            "SELECT DISTINCT ON (x.id + 1, x.kind) x.*, x.payload + 1 AS extra FROM t AS x " +
                "ORDER BY x.id + 1, x.stamp DESC NULLS FIRST LIMIT 3 OFFSET 2",
            "doris",
        ))
        val on = assertIs<Tuple>(assertIs<Distinct>(original.args["distinct"]).args["on"])
        val order = assertIs<Order>(original.args["order"])
        on.expressionsArg.filterIsInstance<Expression>().forEach { it.meta["origin"] = "partition" }
        order.meta["origin"] = "order"
        order.comments = mutableListOf("order comment")
        val before = Serde.dump(original)
        val parents = original.walk().map { it to it.parent }.toList()
        for (pretty in listOf(false, true)) {
            var lowered: Select? = null
            val generator = object : DorisGenerator(pretty = pretty) {
                override fun selectSql(expression: Select): String {
                    lowered = expression
                    return super.selectSql(expression)
                }
            }
            generator.generate(original)
            val select = assertNotNull(lowered)
            val window = assertNotNull((select.args["qualify"] as Expression).find<Window>())
            assertEquals(original.selects, select.selects)
            assertEquals(original.args["limit"], select.args["limit"])
            assertEquals(original.args["offset"], select.args["offset"])
            val partition = (window.args["partition_by"] as List<*>).filterIsInstance<Expression>()
            assertEquals(on.expressionsArg.filterIsInstance<Expression>().map(Serde::dump), partition.map(Serde::dump))
            val copiedOrder = assertIs<Order>(window.args["order"])
            assertEquals(Serde.dump(order), Serde.dump(copiedOrder))
            assertEquals(Serde.dump(order), Serde.dump(assertIs<Order>(select.args["order"])))
            assertNotSame(select.args["order"], copiedOrder)
            assertEquals(before, Serde.dump(original))
            for ((node, parent) in parents) assertSame(parent, node.parent)
        }
    }

    @Test
    fun nonstarDistinctOnAndRegularDistinctKeepExistingRendering() {
        val expected = "SELECT a FROM (SELECT a AS a, ROW_NUMBER() OVER (PARTITION BY a ORDER BY a) " +
            "AS _row_number FROM t) AS _t WHERE _row_number = 1"
        for (pretty in listOf(false, true)) {
            val nonstar = parseOne("SELECT DISTINCT ON (a) a FROM t", "doris").sql("doris", pretty = pretty)
            if (!pretty) assertEquals(expected, nonstar)
            assertEquals(parseOne(expected, "doris"), parseOne(nonstar, "doris"))
            val ordinary = "SELECT DISTINCT *, payload + 1 AS extra FROM t ORDER BY id LIMIT 2"
            val result = parseOne(ordinary, "doris").sql("doris", pretty = pretty)
            if (!pretty) assertEquals(ordinary, result)
            assertEquals(parseOne(ordinary, "doris"), parseOne(result, "doris"))
        }
    }

    @Test
    fun scalarStarsDoNotSelectTheNativeQualifyPath() {
        for (source in listOf(
            "SELECT DISTINCT ON (id) COUNT(*) AS n FROM t GROUP BY id",
            "SELECT DISTINCT ON (id) (SELECT * FROM one_column) AS scalar FROM t",
            "SELECT DISTINCT ON (id) (SELECT * FROM one_column) FROM t",
        )) {
            for (pretty in listOf(false, true)) {
                val sql = SqlFragment(source, "doris").toExecutable("doris", pretty = pretty).sql
                assertNull(parseOne(sql, "doris").find(Qualify::class), sql)
                assertTrue("AS _row_number" in sql, "nonstar DISTINCT ON keeps its existing lowering")
            }
        }
    }

    @Test
    fun executableStarShapeMatchesTheRequestedInputShape() {
        val catalog = ShapeCatalog(tables = mapOf("t" to Shape.of("id" to "INT", "category" to "STRING")))
        for (source in listOf(
            "FROM t |> SELECT DISTINCT ON (category) *",
            "FROM t AS x |> SELECT DISTINCT ON (x.category) x.*",
            "FROM t |> SELECT DISTINCT ON (category) * |> SELECT *",
        )) {
            val fragment = SqlFragment(source, "doris")
            assertEquals(listOf("id", "category"), fragment.outputShape(catalog).names())
            val result = fragment.toExecutable("doris", pretty = true)
            assertEquals(listOf("id", "category"), SqlFragment(result.sql, "doris").outputShape(catalog).names())
        }
    }

    @Test
    fun unsafeStarCasesFailExplicitly() {
        for ((source, reason) in listOf(
            "SELECT DISTINCT ON (id) * FROM t QUALIFY ROW_NUMBER() OVER (ORDER BY id) = 1" to "existing QUALIFY",
            "SELECT DISTINCT ON (ROW_NUMBER() OVER (ORDER BY id)) * FROM t" to "window expressions",
            "SELECT DISTINCT ON (id) x.* FROM t AS x ORDER BY ROW_NUMBER() OVER (ORDER BY id)" to "window expressions",
            "SELECT DISTINCT ON (1) * FROM t" to "positional",
            "SELECT DISTINCT ON ((1)) * FROM t" to "positional",
            "SELECT DISTINCT ON (id) * FROM t ORDER BY 1" to "positional",
            "SELECT DISTINCT ON (id) * FROM t ORDER BY (1) DESC" to "positional",
            "SELECT DISTINCT ON (k) *, id + 1 AS k FROM t" to "projection alias 'k'",
            "SELECT DISTINCT ON (id) *, id + 1 AS k FROM t ORDER BY k + 1" to "projection alias 'k'",
            "SELECT DISTINCT ON (id) *, id + 1 AS `K` FROM t ORDER BY k" to "projection alias 'k'",
            "SELECT DISTINCT ON (id) *, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM t ORDER BY rn" to "projection alias 'rn'",
            "SELECT DISTINCT ON ((SELECT id FROM u)) * FROM t" to "scope resolution",
        )) {
            val fragment = SqlFragment(source, "doris")
            val before = Serde.dump(fragment.ast)
            for (pretty in listOf(false, true)) {
                val error = assertFailsWith<UnsupportedError>(source) { fragment.toExecutable("doris", pretty = pretty) }
                assertTrue(error.message.orEmpty().contains("Doris DISTINCT ON"), error.message)
                assertTrue(error.message.orEmpty().contains(reason), error.message)
                assertEquals(before, Serde.dump(fragment.ast))
            }
        }
    }
}
