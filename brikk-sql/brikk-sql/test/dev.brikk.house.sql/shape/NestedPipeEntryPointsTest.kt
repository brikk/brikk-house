package dev.brikk.house.sql.shape

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.dialects.Dialects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NestedPipeEntryPointsTest {
    private val queries = listOf(
        "SELECT * FROM (FROM t |> SELECT metric) AS s",
        "WITH s AS (FROM t |> SELECT metric) SELECT * FROM s",
        "SELECT * FROM (SELECT * FROM (FROM t |> SELECT metric) AS s) AS q",
        "SELECT * FROM (FROM t |> SELECT metric) AS s UNION ALL SELECT * FROM (FROM t |> SELECT metric) AS q",
    )
    private val shape = Shape(listOf(ColumnShape("metric", "INT", nullable = false)))
    private val catalog = ShapeCatalog(tables = mapOf("t" to shape))

    @Test
    fun executableAndRequestedTranspilationLowerEveryNestedPipe() {
        for (source in queries) {
            val fragment = SqlFragment(source, "duckdb")
            assertFalse(fragment.isPipe)
            val original = fragment.ast.copy()
            for (pretty in listOf(false, true)) {
                val executable = fragment.toExecutable("doris", pretty = pretty)
                val requested = fragment.transpileTo("doris", pretty = pretty, desugarPipes = true, trackSourceMap = true)
                assertEquals(executable.sql, requested.sql)
                for (result in listOf(executable, requested)) {
                    assertFalse(result.sql.contains("|>"), result.sql)
                    assertTrue(Dialects.forName("doris").parseOne(result.sql).findAll<PipeQuery>().none(), result.sql)
                    assertTrue(result.unsupportedMessages.isEmpty(), result.unsupportedMessages.toString())
                    val map = assertNotNull(result.sourceMap)
                    assertSame(result.sql, map.output)
                    assertTrue(map.entries.none { it.node is PipeQuery })
                    assertTrue(map.entries.all { it.start in 0 until it.end && it.end <= result.sql.length })
                    val position = assertNotNull(map.sourcePosition(result.sql.indexOf("metric"), exact = true))
                    assertEquals("metric", source.substring(position.start, position.end + 1))
                }
            }
            assertTrue(fragment.transpileTo("doris", desugarPipes = false).sql.contains("|>"))
            assertEquals(original, fragment.ast, "generation must not mutate the author AST")
        }
    }

    @Test
    fun shapeLineageAndCertificationUseTheLoweredNestedQueries() {
        for (source in queries) {
            val fragment = SqlFragment(source, "duckdb")
            val original = fragment.ast.copy()
            assertEquals(shape, fragment.outputShape(catalog), source)
            assertEquals(mapOf("metric" to setOf("t")), fragment.columnDependencies(inputs = catalog), source)
            val report = fragment.certify("doris", desugarPipes = true, trackSourceMap = true)
            assertTrue(report.ok, report.findings.toString())
            assertFalse(report.result.sql.contains("|>"), report.result.sql)
            assertTrue(Dialects.forName("doris").parseOne(report.result.sql).findAll<PipeQuery>().none())
            assertSame(report.result.sql, assertNotNull(report.result.sourceMap).output)
            assertEquals(original, fragment.ast, "analysis must not mutate the author AST")
        }
    }
}
