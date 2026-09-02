package dev.brikk.house.sql

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pipe-syntax smoke test for the brikk-native `datafusion` dialect (NO sqlglot oracle).
 *
 * The `|>` cases are lifted from DataFusion's real-engine pipe_operator.slt
 * (reference/datafusion — Apache-2.0). For each we assert the three shapes the task
 * asks for:
 *   1. parses under "datafusion" into a first-class [PipeQuery] (stages not desugared
 *      at parse time),
 *   2. regenerates the `|>` surface under "datafusion",
 *   3. desugars ([desugarPipes]) into engine-standard SQL that no longer contains `|>`.
 *
 * `|>` support is BASE machinery (PIPE_GT token -> PipeQuery); this test proves the
 * datafusion dialect inherits it intact.
 */
class DatafusionPipeSmokeTest {

    // datafusion/sqllogictest/test_files/pipe_operator.slt
    private val cases = listOf(
        "SELECT * FROM test |> WHERE a > 1",
        "SELECT * FROM test |> ORDER BY a DESC |> LIMIT 1",
        "SELECT * FROM test |> SELECT a",
        "SELECT * FROM test |> SELECT a, b |> EXTEND a + b AS a_plus_b",
    )

    @Test
    fun pipeQueriesParseToFirstClassPipeQuery() {
        for (sql in cases) {
            val ast = parseOne(sql, dialect = "datafusion")
            assertTrue(ast is PipeQuery, "expected PipeQuery for: $sql (got ${ast::class.simpleName})")
            assertTrue(ast.expressionsArg.isNotEmpty(), "no stages parsed for: $sql")
        }
    }

    @Test
    fun pipeQueriesRegeneratePipeSurface() {
        for (sql in cases) {
            val ast = parseOne(sql, dialect = "datafusion")
            val regenerated = ast.sql(dialect = "datafusion")
            assertTrue("|>" in regenerated, "expected `|>` in regenerated pipe SQL: $regenerated")
        }
    }

    @Test
    fun pipeQueriesDesugarToEngineSql() {
        for (sql in cases) {
            val ast = parseOne(sql, dialect = "datafusion")
            val desugared = desugarPipes(ast).sql(dialect = "datafusion")
            assertTrue("|>" !in desugared, "desugared SQL still contains `|>`: $desugared")
            // desugaring a SELECT-rooted pipe must still be a SELECT query
            assertTrue(
                desugared.uppercase().trimStart().startsWith("SELECT") ||
                    desugared.uppercase().trimStart().startsWith("WITH"),
                "desugared pipe is not a SELECT/WITH query: $desugared",
            )
        }
    }

    @Test
    fun whereStageDesugarsToWhereClause() {
        val ast = parseOne("SELECT * FROM test |> WHERE a > 1", dialect = "datafusion")
        val desugared = desugarPipes(ast).sql(dialect = "datafusion")
        assertEquals("SELECT * FROM test WHERE a > 1", desugared)
    }
}
