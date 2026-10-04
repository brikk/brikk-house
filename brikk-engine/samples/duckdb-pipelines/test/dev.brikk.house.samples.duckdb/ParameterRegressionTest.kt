package dev.brikk.house.samples.duckdb

import dev.brikk.house.sql.runtime.Partial
import dev.brikk.house.sql.runtime.Rel
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParameterRegressionTest {
    @Test
    fun projectionsKeepExactTextAndRangesIncludingParenthesesCastsRepeatedNamesAndUnicode() {
        for (sql in listOf(
            "SELECT :n AS n", "SELECT :n::INTEGER AS n", "SELECT (:n) AS n",
            "SELECT :value AS result", "SELECT :n AS a, :n AS b /* :n */",
            "SELECT ':n 😀' AS note, :n AS value",
        )) {
            val result = SqlFragment(sql, "duckdb").toSourcePreservingExecutable()
            assertEquals(sql, result.sql)
            assertTrue(result.parameterOccurrences.isNotEmpty())
            for (parameter in result.parameterOccurrences) {
                val range = checkNotNull(parameter.nameRange)
                assertEquals(parameter.name, sql.substring(range.start, range.end))
            }
            val name = if (sql.contains(":value")) "value" else "n"
            sampleDatabase().use { connection ->
                val rows = execute(connection, Rel<Partial>(sql, "duckdb").bind(name, 7)).result.rows.single()
                assertEquals(7, rows.last())
                if (sql.contains("AS a")) assertEquals(listOf(7, 7), rows)
            }
        }
    }

    @Test
    fun prefixAliasesStructColonsAndNativeDollarMarkersDoNotBecomeExtraBindings() {
        for ((sql, expected) in listOf(
            "SELECT answer: :n + 1" to "SELECT answer: ? + 1",
            "SELECT {'key': :n} AS data" to "SELECT {'key': ?} AS data",
            "SELECT ${'$'}n AS n" to "SELECT ? AS n",
        )) {
            assertEquals(listOf("n"), SqlFragment(sql, "duckdb").toSourcePreservingExecutable().parameterOccurrences.map { it.name })
            assertEquals(JdbcQuery(expected, listOf(41)), jdbcQuery(sql, mapOf("n" to 41)))
        }
        sampleDatabase().use { connection ->
            assertEquals(listOf(listOf(42)), execute(connection, Rel<Partial>("SELECT answer: :n + 1", "duckdb").bind("n", 41)).result.rows)
        }
    }

    @Test
    fun compilerGeneratedBareProjectionComposesWithCollidingBindingsAndExecutes() {
        val relation = composedParameterRegression()
        assertEquals(setOf(2, 3), relation.bindings().values.toSet())
        assertEquals(2, relation.bindings().size)
        assertTrue("n" !in relation.bindings().keys)
        val rendered = relation.renderWithDiagnostics()
        assertContains(rendered.sql, "/* DDB-001 */")
        assertEquals(2, rendered.stages.size)
        assertTrue(rendered.stages.all { it.diagnostics.isEmpty() && it.unsupportedMessages.isEmpty() })
        sampleDatabase().use { connection ->
            assertEquals(listOf(listOf(5)), execute(connection, relation).result.rows)
        }
    }

    @Test
    fun compilerGeneratedNullableProjectionExecutesWithoutACast() {
        sampleDatabase().use { connection ->
            val relation = nullParameterRegression()
            assertEquals("SELECT :n AS n", relation.render())
            assertEquals(mapOf("n" to null), relation.bindings())
            assertEquals(listOf(listOf(null)), execute(connection, relation).result.rows)
        }
    }
}
