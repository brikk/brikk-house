package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.outputName
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Execute emitted SQL unchanged; the reference materializes each slice separately. */
class PipePaginationSemanticsTest {
    private data class Slice(val limit: Long? = null, val offset: Long? = null, val comma: Boolean = false) {
        val sql: String get() = listOfNotNull(limit?.let { "LIMIT $it" }, offset?.let { "OFFSET $it" }).joinToString(" ")
        val pipe: String get() = if (comma) "LIMIT $offset, $limit" else sql
    }

    private fun rows(statement: Statement, sql: String): Map<List<String?>, Int> =
        statement.executeQuery(sql).use { result ->
            assertEquals(listOf("id", "category"), (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it) }, sql)
            buildList {
                while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
            }.groupingBy { it }.eachCount()
        }

    private fun assertSlices(cases: List<List<Slice>>) {
        val verifier = assertNotNull(SqlVerifiers.forEngine("doris"))
        val schema = mapOf("t" to mapOf("id" to "INT", "category" to "VARCHAR"))
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE t (id INTEGER, category VARCHAR)")
                for (values in listOf(null, "(1, 'A'), (2, 'A'), (3, 'B')",
                    "(1, 'A'), (2, 'A'), (3, NULL), (3, NULL), (4, 'B'), (5, 'B'), (6, NULL), (7, 'C')")) {
                    statement.execute("DELETE FROM t")
                    if (values != null) statement.execute("INSERT INTO t VALUES $values")
                    for (slices in cases) {
                        var reference = "SELECT id, category FROM t ORDER BY id"
                        for ((index, slice) in slices.withIndex()) {
                            reference = "SELECT id, category FROM ($reference) AS s$index ORDER BY id ${slice.sql}"
                        }
                        val expected = rows(statement, reference)
                        for (source in listOf(
                            "FROM t |> ORDER BY id" + slices.joinToString("") { " |> ${it.pipe}" },
                            "SELECT id, category FROM t ORDER BY id ${slices.first().sql}" +
                                slices.drop(1).joinToString("") { " |> ${it.pipe}" } + " |> ORDER BY id",
                        )) {
                            val fragment = SqlFragment(source, "doris")
                            val original = fragment.ast.copy()
                            for (pretty in listOf(false, true)) {
                                val result = fragment.toExecutable("doris", pretty = pretty, trackSourceMap = true)
                                assertEquals(emptyList(), result.unsupportedMessages, source)
                                assertFalse(result.isRawPassthroughStatement, source)
                                assertSame(result.sql, assertNotNull(result.sourceMap).output)
                                val verified = verifier.verify(result.sql)
                                assertTrue(verified.verified && !verified.advisory && verified.accepted, "$source\n${result.sql}\n$verified")
                                val qualified = qualify(
                                    assertIs<Select>(parseOne(result.sql, "doris")), dialect = Dialects.DORIS, schema = schema,
                                    inferSchema = false, validateQualifyColumns = true, allowPartialQualification = false,
                                )
                                assertEquals(listOf("id", "category"), qualified.expressionsArg.map { assertIs<Expression>(it).outputName }, source)
                                assertEquals(expected, rows(statement, result.sql), "$source\n${result.sql}\nReference: $reference")
                                assertEquals(original, fragment.ast, source)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun successiveSlicesMatchIndependentStagePreservingReferences() {
        assertSlices(listOf(
            listOf(Slice(limit = 2), Slice(offset = 1)),
            listOf(Slice(offset = 1), Slice(limit = 2)),
            listOf(Slice(limit = 2, offset = 1)),
            listOf(Slice(limit = 2, offset = 1, comma = true)),
            listOf(Slice(limit = 2, offset = 2), Slice(limit = 4, offset = 3)),
            listOf(Slice(limit = 4, offset = 2), Slice(offset = 3)),
            listOf(Slice(limit = 5), Slice(limit = 8, offset = 1)),
            listOf(Slice(limit = 5), Slice(limit = 2, offset = 1)),
            listOf(Slice(limit = 5), Slice(limit = 8, offset = 1, comma = true)),
            listOf(Slice(limit = 5), Slice(offset = 8)),
            listOf(Slice(offset = 2), Slice(limit = 5), Slice(offset = 1), Slice(limit = 8, offset = 2)),
            listOf(Slice(limit = 5), Slice(limit = 3), Slice(limit = 8)),
            listOf(Slice(offset = 1), Slice(offset = 2), Slice(offset = 3)),
            listOf(Slice(limit = 0), Slice(offset = 2), Slice(limit = 5)),
            listOf(Slice(offset = 0), Slice(limit = 0)),
        ))
    }

    @Test
    fun signedLongBoundariesExecuteWithoutOverflowOrRowChanges() {
        val max = Long.MAX_VALUE
        assertSlices(listOf(
            listOf(Slice(limit = max), Slice(limit = max), Slice(limit = max)),
            listOf(Slice(offset = max), Slice(offset = 0)),
            listOf(Slice(offset = max - 1), Slice(offset = 1)),
            listOf(Slice(limit = 0, offset = max)),
            listOf(Slice(limit = 0, offset = max, comma = true)),
            listOf(Slice(limit = 0), Slice(offset = max)),
            listOf(Slice(offset = max), Slice(limit = 0)),
            listOf(Slice(limit = max), Slice(offset = max)),
            listOf(Slice(limit = max), Slice(offset = 1)),
            listOf(Slice(limit = max - 1, offset = 1)),
            listOf(Slice(offset = max - 1), Slice(limit = 1)),
            listOf(Slice(limit = 1, offset = max - 1), Slice(offset = 1)),
        ))
    }
}
