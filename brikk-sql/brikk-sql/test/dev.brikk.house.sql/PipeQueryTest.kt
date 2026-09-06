package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.PipeAggregate
import dev.brikk.house.sql.ast.PipeOrderBy
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeSelect
import dev.brikk.house.sql.ast.PipeWhere
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Serde
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pipe stages are FIRST-CLASS AST nodes (brikk design divergence from sqlglot, which
 * desugars pipe syntax at parse time): the parser produces a PipeQuery + stage nodes,
 * and stage nodes behave like any other Expression (walk/copy/equality).
 */
class PipeQueryTest {

    private val phase0Example =
        "FROM Produce |> WHERE item != :varthing " +
            "|> AGGREGATE SUM(sold) AS total_sold GROUP BY item " +
            "|> ORDER BY item DESC"

    @Test
    fun phase0ExampleParsesIntoPipeQueryWithStages() {
        val ast = parseOne(phase0Example)

        val pipeQuery = assertIs<PipeQuery>(ast, "top-level node should be a PipeQuery")

        // Head: the parser's FROM-first shape (SELECT * FROM Produce)
        val head = assertIs<Select>(pipeQuery.thisArg)
        assertTrue(head.args["from_"] != null, "head keeps the FROM clause")

        val stages = pipeQuery.expressionsArg.map { it as Expression }
        assertEquals(3, stages.size, "three pipe stages")
        assertIs<PipeWhere>(stages[0])
        assertIs<PipeAggregate>(stages[1])
        assertIs<PipeOrderBy>(stages[2])

        // Stage internals wrap the same nodes standard clauses use
        assertIs<Where>(stages[0].thisArg)
        assertIs<Order>(stages[2].thisArg)
    }

    @Test
    fun desugarMergesWhereStagesWithAnd() {
        val desugared = desugarPipes(parseOne("FROM x |> WHERE a > 1 |> WHERE b > 2"))
        val where = assertIs<Where>(assertIs<Select>(desugared).args["where"])
        // Two WHERE stages AND-merge into a single condition (sqlglot: query.where(...))
        assertEquals("And", (where.thisArg as Expression)::class.simpleName)
    }

    @Test
    fun selectDistinctSurvivesParseCopySerdeRenderAndDesugar() {
        for (modifier in listOf("", "DISTINCT ", "DISTINCT ON (a) ")) {
            val source = "FROM t |> SELECT ${modifier}a"
            val ast = assertIs<PipeQuery>(parseOne(source))
            val stage = assertIs<PipeSelect>(ast.expressionsArg.single())
            if (modifier.isNotEmpty()) assertIs<Distinct>(stage.args["distinct"])
            val copy = ast.copy()
            val loaded = Serde.loadExpression(Serde.dump(ast))
            assertEquals(ast, loaded)
            assertEquals(source, loaded.sql())
            assertEquals(ast, parseOne(loaded.sql()))
            assertEquals(
                "WITH __tmp1 AS (SELECT ${modifier}a FROM t) SELECT * FROM __tmp1",
                desugarPipes(ast).sql(),
            )
            assertEquals(copy, ast, "desugaring must not mutate the pipe AST")
        }
    }

    @Test
    fun selectDistinctKeepsExistingStageBoundaries() {
        for (modifier in listOf("", "ALL ")) {
            assertEquals(
                "WITH __tmp1 AS (SELECT DISTINCT * FROM t), __tmp2 AS (SELECT a FROM __tmp1 AS t) SELECT * FROM __tmp2",
                desugarPipes(parseOne("FROM t |> DISTINCT |> SELECT ${modifier}a")).sql(),
            )
        }
        assertEquals(
            "WITH __tmp1 AS (SELECT DISTINCT a, b FROM t), __tmp2 AS (SELECT a FROM __tmp1) SELECT * FROM __tmp2",
            desugarPipes(parseOne("FROM t |> SELECT DISTINCT a, b |> SELECT a")).sql(),
        )
        assertEquals(
            "WITH __tmp1 AS (SELECT DISTINCT a FROM t) SELECT * FROM __tmp1",
            desugarPipes(parseOne("SELECT ALL * FROM t |> SELECT DISTINCT a", "datafusion")).sql("datafusion"),
        )
    }

    @Test
    fun selectAndDistinctKeepPriorRestrictionsOnTheirInput() {
        for (restriction in listOf("LIMIT 3", "LIMIT 3 OFFSET 1", "OFFSET 1", "LIMIT 0", "OFFSET 0")) {
            for (stage in listOf("SELECT category", "SELECT ALL category", "SELECT DISTINCT category", "DISTINCT")) {
                val query = desugarPipes(parseOne("FROM t |> ORDER BY id |> $restriction |> $stage"))
                val restricted = query.findAll<Select>().single { it.args["limit"] != null || it.args["offset"] != null }
                assertNull(restricted.args["distinct"], query.sql())
                assertEquals("*", (restricted.expressionsArg.single() as Expression).sql())
                assertIs<Order>(restricted.args["order"])
                assertEquals(if ("DISTINCT" in stage) 1 else 0, query.findAll<Distinct>().count())
            }
        }
    }

    @Test
    fun ambiguousBoundaryBindingsFailRatherThanDroppingQualifiers() {
        for (source in listOf(
            "FROM t JOIN u ON t.id = u.id |> LIMIT 3 |> SELECT DISTINCT t.category",
            "SELECT id, id FROM t LIMIT 3 |> SELECT DISTINCT id",
            "FROM db.t |> LIMIT 3 |> SELECT DISTINCT db.t.category",
            "SELECT x.*, x.id AS extra FROM t AS x LIMIT 3 |> SELECT DISTINCT x.*",
            "SELECT t.id AS left_id, u.id AS right_id FROM t JOIN u ON t.id = u.id LIMIT 3 |> SELECT DISTINCT t.id",
            "FROM db.t |> LIMIT 3 |> SELECT DISTINCT (SELECT MAX(u.id) FROM db.u AS u WHERE u.id = db.t.id) AS matched_id",
            "SELECT t.id AS left_id, u.id AS right_id FROM t JOIN u ON t.id = u.id LIMIT 3 " +
                "|> SELECT DISTINCT (SELECT MAX(v.id) FROM v WHERE v.id = t.id) AS matched_id",
            "FROM db1.t |> LIMIT 3 |> SELECT DISTINCT " +
                "(SELECT MAX(db2.t.id) FROM db2.t WHERE db2.t.id = db1.t.id) AS matched_id",
            "FROM t |> ORDER BY RAND() |> LIMIT 3 |> SELECT DISTINCT ON (category) *",
            "SELECT t.id FROM t LIMIT 2 |> SELECT (WITH c AS (SELECT t.id AS x) SELECT c.x FROM c) AS v",
        )) {
            assertFailsWith<UnsupportedError>(source) { desugarPipes(parseOne(source)) }
        }
    }

    @Test
    fun bigqueryImplicitTableNamespacesUseAliasRatherThanPhysicalNameRules() {
        val source = "FROM dataset.T |> LIMIT 1 |> SELECT T.id"
        val ast = parseOne(source, "bigquery")
        val output = desugarPipes(ast, Dialects.BIGQUERY).sql("bigquery")
        assertTrue("dataset.T" in output, output)
        assertTrue("AS T" in output, output)
        assertTrue("T.id" in output, output)
    }

    @Test
    fun bigqueryLocalCteTableReferencesDoNotBecomeOuterDependencies() {
        val ast = parseOne(
            "FROM u |> LIMIT 1 |> SELECT (WITH c AS (SELECT T.id FROM dataset.T LIMIT 1) SELECT c.id FROM c) AS id",
            "bigquery",
        )
        val output = desugarPipes(ast, Dialects.BIGQUERY).sql("bigquery")
        assertTrue("dataset.T" in output, output)
        assertTrue("T.id" in output, output)
    }

    @Test
    fun distinctOnKeepsRankingOrderAsWellAsTheRestrictedInputOrder() {
        val query = desugarPipes(parseOne(
            "FROM t |> ORDER BY category, id DESC |> LIMIT 3 |> SELECT DISTINCT ON (category) *",
        ))
        val restricted = query.findAll<Select>().single { it.args["limit"] != null }
        val distinct = query.findAll<Select>().single { it.args["distinct"] != null }
        assertEquals(restricted.args["order"], distinct.args["order"])
        assertNotSame(restricted.args["order"], distinct.args["order"])
    }

    @Test
    fun desugarReplacesOrderByInsteadOfAppending() {
        val desugared = desugarPipes(parseOne("FROM x |> ORDER BY a |> ORDER BY b"))
        val order = assertIs<Order>(assertIs<Select>(desugared).args["order"])
        assertEquals(1, order.expressionsArg.size, "ORDER BY replaces, never appends")
    }

    @Test
    fun desugarKeepsMinimumLimitAndSumsOffsets() {
        val desugared = desugarPipes(
            parseOne("FROM x |> LIMIT 2 OFFSET 2 |> LIMIT 4 OFFSET 3")
        )
        val select = assertIs<Select>(desugared)
        val limit = assertIs<Limit>(select.args["limit"])
        assertEquals("2", (limit.expressionArg as Literal).thisArg, "LIMIT keeps the minimum")
        val offset = assertIs<Offset>(select.args["offset"])
        assertEquals("5", (offset.expressionArg as Literal).thisArg, "OFFSET sums")
    }

    @Test
    fun stageNodesWalkCopyAndCompareLikeExpressions() {
        val ast = parseOne(phase0Example)

        // walk() visits the stage nodes and their inner clause nodes
        val walked = ast.walk().toList()
        assertTrue(walked.any { it is PipeWhere })
        assertTrue(walked.any { it is PipeAggregate })
        assertTrue(walked.any { it is PipeOrderBy })
        assertTrue(walked.any { it is Where })

        // copy() is deep and equal-by-structure
        val copy = ast.copy()
        assertNotSame(ast, copy)
        assertEquals(ast, copy)
        assertEquals(ast.hashCode(), copy.hashCode())

        // structural equality is sensitive to stage contents
        val other = parseOne("FROM Produce |> WHERE item != :varthing")
        assertTrue(ast != other)

        // and desugaring a copy never mutates the original
        desugarPipes(ast)
        assertEquals(ast, copy)
    }
}
