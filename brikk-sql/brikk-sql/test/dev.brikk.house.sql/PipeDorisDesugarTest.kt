package dev.brikk.house.sql

import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.Union
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Pipe-only desugar" under a single dialect: parse doris-flavored pipe SQL, keep every
 * function/expression as doris, desugar only the |> structure, generate doris back.
 */
class PipeDorisDesugarTest {

    private val sql = """
        FROM rumble_import.events
        |> WHERE event_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
        |> EXTEND
             DATE(event_at) AS event_date,
             HOUR(event_at) AS event_hour
        |> AGGREGATE
             COUNT(*) AS event_count,
             COUNT(DISTINCT fid) AS unique_videos
           GROUP BY event_date, event_hour
        |> WHERE event_count > 10
        |> ORDER BY event_date DESC, event_hour DESC
        |> LIMIT 100
    """.trimIndent()

    @Test
    fun dorisPipeQueryStaysFirstClassUntilDesugared() {
        val ast = parseOne(sql, dialect = "doris")
        // Stages are first-class — nothing is desugared at parse time.
        assertTrue(ast is PipeQuery)
        assertEquals(
            listOf("PipeWhere", "PipeExtend", "PipeAggregate", "PipeWhere", "PipeOrderBy", "PipeLimit"),
            (ast as PipeQuery).expressionsArg.map { it!!::class.simpleName },
        )
    }

    @Test
    fun pipeOnlyDesugarKeepsDorisIntact() {
        val ast = parseOne(sql, dialect = "doris")
        val standard = desugarPipes(ast)
        val dorisSql = standard.sql(dialect = "doris")
        println("-- pipe-only desugar (doris in, doris out):")
        println(dorisSql)
        // One-liner equivalent via the shape layer:
        assertEquals(dorisSql, SqlFragment(sql, dialect = "doris").toStandardSql())
        // Doris-isms survived untranslated:
        assertTrue("DATE_SUB(NOW(), INTERVAL '7' DAY)" in dorisSql || "DATE_SUB(NOW(), INTERVAL 7 DAY)" in dorisSql)
        assertTrue("|>" !in dorisSql)
    }

    private fun assertNativeFullJoin(stages: String, distinct: Boolean = false) {
        val fragment = SqlFragment("FROM a |> FULL OUTER JOIN b ON a.id = b.id $stages", "doris")
        assertTrue(fragment.isPipe)
        for (pretty in listOf(false, true)) {
            val result = fragment.toExecutable("doris", pretty = pretty, trackSourceMap = true)
            assertEquals(emptyList(), result.unsupportedMessages)
            assertEquals(result.sql, result.sourceMap?.output)
            val generated = parseOne(result.sql, "doris")
            assertEquals(listOf("FULL"), generated.findAll(Join::class).map { (it as Join).side }.toList(), result.sql)
            assertNull(generated.find(Union::class), result.sql)
            assertEquals(distinct, generated.find(Distinct::class) != null, result.sql)
        }
    }

    @Test
    fun fullOuterJoinAggregatesOnce() {
        assertNativeFullJoin("|> AGGREGATE COUNT(*) AS n")
    }

    @Test
    fun fullOuterJoinGroupsTheWholeRelation() {
        assertNativeFullJoin("|> AGGREGATE COUNT(*) AS n GROUP BY COALESCE(a.category, b.category) AS bucket")
    }

    @Test
    fun fullOuterJoinKeepsGlobalDistinct() {
        assertNativeFullJoin("|> SELECT DISTINCT COALESCE(a.category, b.category) AS category", distinct = true)
    }

    @Test
    fun fullOuterJoinKeepsOrFilterBeforeLimit() {
        assertNativeFullJoin("|> WHERE a.id = 1 OR b.id = 3 |> ORDER BY 1 DESC, 3 |> LIMIT 2")
    }
}
