package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.Order
import dev.brikk.house.sql.ast.PipeAggregate
import dev.brikk.house.sql.ast.PipeOrderBy
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.PipeWhere
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Where
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
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
