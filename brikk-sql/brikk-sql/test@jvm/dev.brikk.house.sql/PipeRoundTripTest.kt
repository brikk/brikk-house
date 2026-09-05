package dev.brikk.house.sql

import dev.brikk.house.sql.ast.desugarPipes
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.parseOne
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Pipe generation self-consistency gate (brikk differentiator — sqlglot cannot generate
 * pipe SQL): for every pipe corpus case,
 *
 *   parseOne(sql) -> Generator().sql(pipeAst)   [pipe generation]
 *     -> parseOne(that output) -> desugar -> generate
 *
 * must equal the direct parse -> desugar -> generate path.
 */
class PipeRoundTripTest {

    @Test
    fun pipeGenerationRoundTripsThroughDesugar() {
        val cases = PipeDesugarCorpusTest.loadPipeCases()
        check(cases.isNotEmpty()) { "no pipe cases found" }

        val failures = mutableListOf<String>()

        for (case in cases) {
            val result = runCatching {
                val pipeAst = parseOne(case.sql)
                val pipeSql = Generator().generate(pipeAst)

                val direct = Generator(pretty = case.pretty).generate(desugarPipes(pipeAst))
                val roundTrip = Generator(pretty = case.pretty).generate(desugarPipes(parseOne(pipeSql)))

                Triple(pipeSql, direct, roundTrip)
            }

            val triple = result.getOrNull()
            if (triple == null) {
                failures.add(
                    "SQL: ${case.sql}\n  error: ${result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}"
                )
            } else if (triple.second != triple.third) {
                failures.add(
                    "SQL: ${case.sql}\n" +
                        "  pipe gen:   ${triple.first}\n" +
                        "  direct:     ${triple.second}\n" +
                        "  round trip: ${triple.third}"
                )
            }
        }

        println("PipeRoundTrip: ${cases.size - failures.size}/${cases.size} pass")

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size}/${cases.size} pipe round-trip failures (showing up to 20):\n\n" +
                    failures.take(20).joinToString("\n\n")
            )
        }
    }

    @Test
    fun pipeGenerationExactStrings() {
        fun gen(sql: String) = Generator().generate(parseOne(sql))

        assertEquals(
            "FROM Produce |> WHERE item <> 'bananas' |> SELECT item, sales",
            gen("FROM Produce |> WHERE item != 'bananas' |> SELECT item, sales"),
        )
        assertEquals(
            "FROM x |> AGGREGATE SUM(x1) AS s_x1 GROUP BY x2 AS g_x2 |> ORDER BY g_x2 DESC",
            gen("FROM x |> AGGREGATE SUM(x1) as s_x1 GROUP BY x2 as g_x2 |> ORDER BY g_x2 DESC"),
        )
        assertEquals(
            "FROM x |> SELECT x1 |> UNION ALL (SELECT 1 AS c), (SELECT 2 AS c) |> LIMIT 3 OFFSET 1",
            gen("FROM x |> SELECT x1 |> UNION ALL (SELECT 1 AS c), (SELECT 2 AS c) |> LIMIT 3 OFFSET 1"),
        )
        assertEquals(
            "(SELECT 1 AS col1) |> EXTEND col1 + 1 AS col2 |> DISTINCT",
            gen("(SELECT 1 AS col1) |> EXTEND col1 + 1 AS col2 |> DISTINCT"),
        )
    }
}
