package dev.brikk.house.sql.runtime

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.TokenType
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ENG-01 acceptance through the public Engine API, not just the SQL dependency's API. */
class SourcePreservingRelTest {
    @Test
    fun pipePreservesNativeSubqueryAndExecutesExactlyTheReturnedSql() {
        val sql = "FROM (select /* keep */ 2::integer as id) AS raw |> WHERE id = 2 |> SELECT id"
        val rel = Rel<Partial>(sql, "duckdb")
        val result = rel.renderWithDiagnostics()
        assertContains(result.sql, "select /* keep */ 2::integer as id")
        assertTrue(Dialects.DUCKDB.tokenize(result.sql).none { it.tokenType == TokenType.PIPE_GT })
        assertEquals(sql, result.stages.single().sourceSql)
        assertTrue(result.stages.single().diagnostics.isNotEmpty())
        assertEquals(result.sql, rel.render())
        assertEquals(2, execute(result.sql))
    }

    @Test
    fun nestedPipesChangeOnlyTheNestedQueryAndReportItsRanges() {
        val sql = "select /* outer keep */ id::integer as id from (FROM (SELECT 1 AS id) AS raw |> SELECT id) AS nested"
        val result = Rel<Partial>(sql, "postgres").renderWithDiagnostics()
        assertTrue(result.sql.startsWith("select /* outer keep */ id::integer as id from ("), result.sql)
        assertTrue(result.sql.endsWith(") AS nested"), result.sql)
        assertTrue(Dialects.POSTGRES.tokenize(result.sql).none { it.tokenType == TokenType.PIPE_GT })
        val stage = result.stages.single()
        assertEquals(sql, stage.sourceSql)
        for (diagnostic in stage.diagnostics) {
            assertTrue(diagnostic.range.start >= 0 && diagnostic.range.end <= stage.sourceSql.length)
        }
        assertEquals(1, execute(result.sql))
    }

    @Test
    fun compositionPreservesNativeIslandsInsidePipeStages() {
        val source = Rel<Partial>("select /* source hint */ 2::integer as id; -- source tail", "duckdb")
        val rel = Rel<Partial>(
            "FROM src(/* slot */) |> JOIN (select /* dimension */ 2::integer as id) AS dim ON src.id = dim.id " +
                "|> SELECT src.id", "duckdb",
        ).input("src", source)
        val result = rel.renderWithDiagnostics()
        assertContains(result.sql, "select /* source hint */ 2::integer as id -- source tail\n")
        assertContains(result.sql, "select /* dimension */ 2::integer as id")
        assertEquals(listOf("s0", "s1"), result.stages.map { it.name })
        assertTrue(result.stages.first().diagnostics.isEmpty())
        assertTrue(result.stages.last().diagnostics.isNotEmpty())
        assertContains(result.stages.last().sourceSql, "FROM s0/* slot */ AS src")
        assertEquals(2, execute(result.sql))
    }

    @Test
    fun projectionAndLimitBoundariesStillConsumeTheInputRows() {
        for ((sql, expected) in listOf(
            "FROM (select 1 as id) AS raw |> SELECT id + 1 AS id |> WHERE id = 2" to 2,
            "FROM (select 2 as id union all select 1) AS raw |> ORDER BY id |> LIMIT 1 |> SELECT id" to 1,
        )) {
            val rendered = Rel<Partial>(sql, "duckdb").render()
            assertTrue(Dialects.DUCKDB.tokenize(rendered).none { it.tokenType == TokenType.PIPE_GT })
            assertEquals(expected, execute(rendered), rendered)
        }
    }

    @Test
    fun fromFirstNormalizesOnlyTheNeededSyntaxAndKeepsTheNativeBody() {
        val sql = "FROM (select /* keep */ 1::integer as id) AS raw"
        assertEquals(sql, Rel<Partial>(sql, "duckdb").render())
        val result = Rel<Partial>(sql, "postgres").renderWithDiagnostics()
        assertTrue(result.sql.startsWith("SELECT * FROM"), result.sql)
        assertContains(result.sql, "select /* keep */ 1::integer as id")
        assertTrue(result.stages.single().diagnostics.isNotEmpty())
        assertEquals(1, execute(result.sql))
    }

    @Test
    fun nativeFragmentsReportNoRegenerationAndTranslationReportsItsSource() {
        val native = Rel<Partial>(" select lower('AbC') as id SETTINGS max_threads = 1;", "clickhouse")
            .renderWithDiagnostics()
        assertTrue(native.stages.single().diagnostics.isEmpty())
        val translated = Rel<Partial>("select LOWER(x) as x from t", "duckdb")
            .renderWithDiagnostics("clickhouse")
        assertContains(translated.sql, "lowerUTF8(x)")
        assertEquals("duckdb", translated.stages.single().sourceDialect)
        assertEquals("clickhouse", translated.stages.single().targetDialect)
        assertTrue(translated.stages.single().diagnostics.isNotEmpty())
    }

    @Test
    fun unsafeClickhouseSettingsMovementIsNotHiddenByEngineFallback() {
        val rel = Rel<Partial>("SELECT x FROM t SETTINGS max_threads = 1 |> SELECT x", "clickhouse")
        assertFailsWith<UnsupportedError> { rel.render() }
        assertFailsWith<UnsupportedError> { rel.renderWithDiagnostics() }
    }

    @Test
    fun renamedPipeBindingsReportRangesAgainstTheEditedStageSource() {
        val source = Rel<Partial>("select :n::integer as id", "postgres").bind("n", 2)
        val rel = Rel<Partial>("FROM src() |> WHERE id = :n |> SELECT id", "postgres")
            .input("src", source).bind("n", 2)
        val result = rel.renderWithDiagnostics()
        val sourceReport = result.stages.first()
        val pipeReport = result.stages.last()
        assertEquals("select :__brikk_bind_0_0::integer as id", sourceReport.sourceSql)
        assertContains(pipeReport.sourceSql, "FROM s0 AS src |> WHERE id = :__brikk_bind_1_0")
        assertContains(result.sql, ":__brikk_bind_1_0")
        assertEquals(setOf("__brikk_bind_0_0", "__brikk_bind_1_0"), rel.bindings().keys)
        for (diagnostic in pipeReport.diagnostics) {
            assertTrue(diagnostic.range.start >= 0 && diagnostic.range.end <= pipeReport.sourceSql.length)
        }
    }

    @Test
    fun aPipeInsideAnExistingCteRetainsTheUnchangedOuterQuery() {
        val sql = "with data as (FROM (select /* keep */ 3 as id) AS raw |> SELECT id) " +
            "select /* outer */ id::integer as id from data"
        val result = Rel<Partial>(sql, "duckdb").renderWithDiagnostics()
        assertTrue(result.sql.startsWith("with data as ("), result.sql)
        assertContains(result.sql, "select /* keep */ 3 as id")
        assertTrue(result.sql.endsWith("select /* outer */ id::integer as id from data"), result.sql)
        assertTrue(result.stages.single().diagnostics.isNotEmpty())
        assertEquals(3, execute(result.sql))
    }

    @Test
    fun explicitTranslationWithRenamedBindsDoesNotDemandSameDialectPreservation() {
        val source = Rel<Partial>("SELECT {n: Int32} AS x", "clickhouse").bind("n", 2)
        val rel = Rel<Partial>(
            "SELECT x FROM src() SETTINGS max_threads = 1 |> SELECT x + {n: Int32} AS y", "clickhouse",
        ).input("src", source).bind("n", 3)
        assertFailsWith<UnsupportedError> { rel.render() }
        val result = rel.renderWithDiagnostics("duckdb")
        assertTrue(Dialects.DUCKDB.tokenize(result.sql).none { it.tokenType == TokenType.PIPE_GT })
        assertContains(result.sql, "__brikk_bind_0_0")
        assertContains(result.sql, "__brikk_bind_1_0")
        assertTrue(result.stages.all { it.targetDialect == "duckdb" && it.diagnostics.isNotEmpty() })
        assertTrue(result.stages.last().diagnostics.any { it.message.contains("cross-dialect", ignoreCase = true) })
    }

    private fun execute(sql: String): Int = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next(), sql)
                val result = rows.getInt(1)
                assertTrue(!rows.next(), sql)
                result
            }
        }
    }
}
