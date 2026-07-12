package dev.brikk.house.sql

import dev.brikk.house.sql.ast.PipeQuery
import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.parser.parseOne
import dev.brikk.house.sql.shape.SqlFragment
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
