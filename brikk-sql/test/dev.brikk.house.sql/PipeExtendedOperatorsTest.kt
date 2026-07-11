package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.EQ
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.PipeCall
import dev.brikk.house.sql.ast.PipeDrop
import dev.brikk.house.sql.ast.PipeOffset
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeRename
import dev.brikk.house.sql.ast.PipeSet
import dev.brikk.house.sql.ast.PipeWindow
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Extended GoogleSQL pipe operators: SET, DROP, RENAME, CALL, WINDOW and standalone
 * OFFSET. NO sqlglot counterpart exists for these (sqlglot raises "Unsupported pipe
 * syntax operator"), so every expectation here is HAND-DERIVED from the spec,
 * reference/googlesql/docs/pipe-syntax.md:
 *
 *   SET ~664, DROP ~713, RENAME ~763, CALL ~1323, LIMIT/OFFSET ~1419, WINDOW ~2361.
 */
class PipeExtendedOperatorsTest {

    private fun stages(sql: String): List<Expression> {
        val pipeQuery = assertIs<PipeQuery>(parseOne(sql))
        return pipeQuery.expressionsArg.map { it as Expression }
    }

    private fun gen(sql: String) = Generator().generate(parseOne(sql))

    private fun desugared(sql: String) = Generator().generate(desugarPipes(parseOne(sql)))

    private val catalog = ShapeCatalog(
        tables = mapOf("t" to Shape.of("a" to "INT", "b" to "TEXT", "c" to "INT")),
    )

    // ------------------------------------------------------------- parse shapes

    @Test
    fun setParsesIntoEqAssignments() {
        // googlesql: pipe SET (~664) — `SET column_name = expression [, ...]`
        val stage = assertIs<PipeSet>(stages("FROM t |> SET x = x * x, y = 3").single())
        val assignments = stage.expressionsArg.map { assertIs<EQ>(it) }
        assertEquals(2, assignments.size)
        assertIs<Column>(assignments[0].thisArg)
        assertEquals("x", (assignments[0].thisArg as Column).name)
        assertEquals("y", (assignments[1].thisArg as Column).name)
    }

    @Test
    fun dropParsesColumnList() {
        // googlesql: pipe DROP (~713) — `DROP column_name [, ...]`
        val stage = assertIs<PipeDrop>(stages("FROM t |> DROP a, b").single())
        assertEquals(
            listOf("a", "b"),
            stage.expressionsArg.map { assertIs<Column>(it).name },
        )
    }

    @Test
    fun renameParsesAliasesWithOptionalAs() {
        // googlesql: pipe RENAME (~763) — `RENAME old_column_name [AS] new_column_name`
        val stage = assertIs<PipeRename>(stages("FROM t |> RENAME a AS b, c d").single())
        val renames = stage.expressionsArg.map { assertIs<Alias>(it) }
        assertEquals(2, renames.size)
        assertEquals("a", (renames[0].thisArg as Column).name)
        assertEquals("b", renames[0].alias)
        assertEquals("c", (renames[1].thisArg as Column).name)
        assertEquals("d", renames[1].alias)
    }

    @Test
    fun callParsesAnonymousTvfWithOptionalAlias() {
        // googlesql: pipe CALL (~1323) — `CALL table_function (argument [, ...]) [[AS] alias]`
        val stage = assertIs<PipeCall>(stages("FROM t |> CALL tvf1(arg1) AS u").single())
        val call = assertIs<Anonymous>(stage.thisArg)
        assertEquals("tvf1", call.name)
        assertEquals(1, call.expressionsArg.size)
        assertEquals("u", (stage.args["alias"] as TableAlias).name)

        // alias is optional
        val bare = assertIs<PipeCall>(stages("FROM t |> CALL tvf2(1, 2)").single())
        assertEquals(null, bare.args["alias"])
    }

    @Test
    fun windowParsesAliasedWindowExpressions() {
        // googlesql: pipe WINDOW (~2361) — `WINDOW window_expression [[AS] alias] [, ...]`
        val stage = assertIs<PipeWindow>(
            stages("FROM t |> WINDOW SUM(a) OVER () AS total, RANK() OVER (ORDER BY b) r").single()
        )
        val projections = stage.expressionsArg.map { assertIs<Alias>(it) }
        assertEquals(listOf("total", "r"), projections.map { it.alias })
    }

    @Test
    fun standaloneOffsetParses() {
        // googlesql extension: standalone `|> OFFSET` (LIMIT/OFFSET ~1419 covers the
        // combined form; the standalone stage node existed but was disabled)
        val stage = assertIs<PipeOffset>(stages("FROM t |> OFFSET 5").single())
        assertIs<Offset>(stage.thisArg)
    }

    @Test
    fun unknownOperatorStillRaises() {
        val err = assertFailsWith<ParseError> { parseOne("FROM t |> FROBNICATE a") }
        assertTrue("Unsupported pipe syntax operator" in err.message!!)
    }

    // ------------------------------------------------------------- desugar (hand-derived)

    @Test
    fun setDesugarsToStarReplace() {
        // googlesql SET (~664): "Replaces the value of a column ..., similar to
        // SELECT * REPLACE (expression AS column) in standard syntax."
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM t) SELECT * REPLACE (x * x AS x, 3 AS y) FROM __tmp1",
            desugared("FROM t |> SET x = x * x, y = 3"),
        )
    }

    @Test
    fun dropDesugarsToStarExcept() {
        // googlesql DROP (~713): "Removes listed columns ..., similar to
        // SELECT * EXCEPT (column) in standard syntax."
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM t) SELECT * EXCEPT (a, b) FROM __tmp1",
            desugared("FROM t |> DROP a, b"),
        )
    }

    @Test
    fun renameDesugarsToStarRename() {
        // googlesql RENAME (~763): renames the top-level column, keeping its position.
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM t) SELECT * RENAME (a AS b) FROM __tmp1",
            desugared("FROM t |> RENAME a AS b"),
        )
    }

    @Test
    fun callDesugarsWithInputAsFirstTableArgument() {
        // googlesql CALL (~1323): "The first table argument comes from the input table
        // and must be omitted in the arguments." — the accumulated query is prepended.
        // (unknown function names are normalized to uppercase by the generator, the
        // same treatment `FROM tvf1(...)` slot-style calls get)
        assertEquals(
            "SELECT * FROM TVF1((SELECT * FROM t), arg1)",
            desugared("FROM t |> CALL tvf1(arg1)"),
        )
        assertEquals(
            "SELECT * FROM TVF1((SELECT * FROM t), 1) AS u",
            desugared("FROM t |> CALL tvf1(1) AS u"),
        )
        // Sequential CALLs nest, mirroring the spec's tvf2(arg2, arg3, TABLE tvf1(...)) example
        assertEquals(
            "SELECT * FROM TVF2((SELECT * FROM TVF1((SELECT * FROM t), arg1)), arg2, arg3)",
            desugared("FROM t |> CALL tvf1(arg1) |> CALL tvf2(arg2, arg3)"),
        )
    }

    @Test
    fun windowDesugarsLikeExtend() {
        // googlesql WINDOW (~2361): adds columns, existing rows/columns unchanged —
        // EXTEND-like `SELECT *, w AS a`.
        assertEquals(
            "WITH __tmp1 AS (SELECT *, SUM(a) OVER () AS total FROM t) SELECT * FROM __tmp1",
            desugared("FROM t |> WINDOW SUM(a) OVER () AS total"),
        )
    }

    @Test
    fun standaloneOffsetDesugarsWithOffsetSumSemantics() {
        assertEquals("SELECT * FROM t OFFSET 2", desugared("FROM t |> OFFSET 2"))
        // offsets sum (same merge rule as the LIMIT stage's OFFSET part)
        assertEquals("SELECT * FROM t OFFSET 5", desugared("FROM t |> OFFSET 2 |> OFFSET 3"))
        assertEquals(
            "SELECT * FROM t LIMIT 4 OFFSET 5",
            desugared("FROM t |> LIMIT 4 OFFSET 2 |> OFFSET 3"),
        )
    }

    @Test
    fun mixedChainDesugarsInStageOrder() {
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM t), " +
                "__tmp2 AS (SELECT * REPLACE (a + 1 AS a) FROM __tmp1), " +
                "__tmp3 AS (SELECT * EXCEPT (b) FROM __tmp2) " +
                "SELECT * RENAME (c AS k) FROM __tmp3",
            desugared("FROM t |> SET a = a + 1 |> DROP b |> RENAME c AS k"),
        )
    }

    // ------------------------------------------------------------- pipe generation

    @Test
    fun pipeGenerationExactStrings() {
        assertEquals("FROM t |> SET x = x * x, y = 3", gen("FROM t |> SET x = x * x, y = 3"))
        assertEquals("FROM t |> DROP a, b", gen("FROM t |> DROP a, b"))
        // implicit AS is canonicalized to explicit AS
        assertEquals("FROM t |> RENAME a AS b, c AS d", gen("FROM t |> RENAME a AS b, c d"))
        // function names normalize to uppercase, like any unknown function call
        assertEquals("FROM t |> CALL TVF1(arg1) AS u", gen("FROM t |> CALL tvf1(arg1) AS u"))
        assertEquals(
            "FROM t |> WINDOW SUM(a) OVER () AS total",
            gen("FROM t |> WINDOW SUM(a) OVER () AS total"),
        )
        assertEquals("FROM t |> OFFSET 5", gen("FROM t |> OFFSET 5"))
    }

    @Test
    fun pipeGenerationRoundTripsToIdenticalTree() {
        val cases = listOf(
            "FROM t |> SET x = x * x, y = 3",
            "FROM t |> DROP a, b",
            "FROM t |> RENAME a AS b, c d",
            "FROM t |> CALL tvf1(arg1) AS u",
            "FROM t |> CALL tvf2(1, 2)",
            "FROM t |> WINDOW SUM(a) OVER () AS total",
            "FROM t |> OFFSET 5",
            "FROM t |> SET a = a + 1 |> DROP b |> RENAME c AS k |> OFFSET 2",
        )
        for (sql in cases) {
            val ast = parseOne(sql)
            val rendered = Generator().generate(ast)
            assertEquals(ast, parseOne(rendered), "round-trip tree mismatch for: $sql")
        }
    }

    // ------------------------------------------------------------- any-engine rendering

    @Test
    fun expandedStarModifiersRenderOnAnyEngine() {
        // MySQL has NO native star EXCEPT/REPLACE/RENAME: with a catalog, the modifiers
        // must expand to explicit column lists (plain standard SQL).
        val fragment = SqlFragment("FROM t |> SET a = a + 1 |> DROP b |> RENAME c AS k")
        val expected =
            "WITH __tmp1 AS (SELECT t.a AS a, t.b AS b, t.c AS c FROM t AS t), " +
                "__tmp2 AS (SELECT __tmp1.a + 1 AS a, __tmp1.b AS b, __tmp1.c AS c FROM __tmp1 AS __tmp1), " +
                "__tmp3 AS (SELECT __tmp2.a AS a, __tmp2.c AS c FROM __tmp2 AS __tmp2) " +
                "SELECT __tmp3.a AS a, __tmp3.c AS k FROM __tmp3 AS __tmp3"
        for (target in listOf("mysql", "postgres", "duckdb")) {
            val sql = fragment.toStandardSql(target = target, inputs = catalog, expandStars = true)
            assertEquals(expected, sql, "expanded rendering for $target")
            assertTrue("REPLACE" !in sql && "EXCEPT" !in sql && "RENAME" !in sql, target)
        }
    }

    @Test
    fun withoutCatalogStarModifiersSurvive() {
        // No schema: modifiers stay as-is (BigQuery-style star modifiers).
        assertEquals(
            "WITH __tmp1 AS (SELECT * FROM t) SELECT * EXCEPT (b) FROM __tmp1",
            SqlFragment("FROM t |> DROP b").toStandardSql(),
        )
    }

    // ------------------------------------------------------------- shape flow

    @Test
    fun setKeepsShapeColumnsAndPositions() {
        // googlesql SET (~664): value replaced, column position kept.
        assertEquals(
            Shape.of("a" to "INT", "b" to "TEXT", "c" to "INT"),
            SqlFragment("FROM t |> SET a = a + 1").outputShape(catalog),
        )
    }

    @Test
    fun dropRemovesColumnFromShape() {
        assertEquals(
            Shape.of("a" to "INT", "c" to "INT"),
            SqlFragment("FROM t |> DROP b").outputShape(catalog),
        )
    }

    @Test
    fun renameRenamesColumnInPlaceInShape() {
        assertEquals(
            Shape.of("a" to "INT", "label" to "TEXT", "c" to "INT"),
            SqlFragment("FROM t |> RENAME b AS label").outputShape(catalog),
        )
    }

    @Test
    fun windowAppendsColumnToShape() {
        assertEquals(
            Shape.of("a" to "INT", "b" to "TEXT", "c" to "INT", "total" to "BIGINT"),
            SqlFragment("FROM t |> WINDOW SUM(a) OVER () AS total").outputShape(catalog),
        )
    }

    @Test
    fun mixedChainShapeFlowsThroughAllStages() {
        assertEquals(
            Shape.of("a" to "INT", "k" to "INT"),
            SqlFragment("FROM t |> SET a = a * 2 |> DROP b |> RENAME c AS k").outputShape(catalog),
        )
    }

    @Test
    fun describeReportsNewStageOperators() {
        assertEquals(
            listOf("SET", "DROP", "RENAME", "CALL", "WINDOW", "OFFSET"),
            SqlFragment(
                "FROM t |> SET a = 1 |> DROP b |> RENAME c AS k " +
                    "|> CALL tvf1(1) |> WINDOW SUM(a) OVER () AS s |> OFFSET 2"
            ).describe().stageOperators,
        )
    }

    @Test
    fun calledUnknownTvfBecomesSlotCandidate() {
        // PipeDesugar KDoc: a CALLed function unknown to the dialect lands in table
        // position as Anonymous — the exact shape the slot mechanism keys on.
        assertEquals(
            listOf("tvf1"),
            SqlFragment("FROM t |> CALL tvf1(1)").tableSlots,
        )
    }

    // ------------------------------------------------------------- error cases

    @Test
    fun renameUnknownColumnUnderSchemaRaises() {
        val err = assertFailsWith<ShapeError> {
            SqlFragment("FROM t |> RENAME zz AS y")
                .toStandardSql(target = "mysql", inputs = catalog, expandStars = true)
        }
        assertTrue("zz" in err.message!!, err.message!!)
    }

    @Test
    fun setUnknownColumnUnderSchemaRaises() {
        val err = assertFailsWith<ShapeError> {
            SqlFragment("FROM t |> SET zz = 1")
                .toStandardSql(target = "mysql", inputs = catalog, expandStars = true)
        }
        assertTrue("zz" in err.message!!, err.message!!)
    }

    @Test
    fun callWithoutFunctionCallRaises() {
        // `CALL f` (no argument list) is not a TVF call per the spec grammar (~1327)
        assertFailsWith<ParseError> { parseOne("FROM t |> CALL tvf1") }
        assertFailsWith<ParseError> { parseOne("FROM t |> CALL") }
    }
}
