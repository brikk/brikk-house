package dev.brikk.house.sql

import dev.brikk.house.sql.parser.PipeStageDocument
import dev.brikk.house.sql.parser.PipeStageSplitter
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PipeStageSplitterTest {

    private val example = listOf(
        "FROM Produce",
        "|> WHERE item != :varthing",
        "|> AGGREGATE",
        "     COUNT(*) AS num_items,",
        "     SUM(sales) AS total_sales,",
        "     dummy_fun(x) AS dummy",
        "   GROUP BY item",
        "|> ORDER BY item DESC",
    ).joinToString("\n")

    @Test
    fun exampleSplitsIntoFourStages() {
        val result = PipeStageSplitter.split(example, dialect = "bigquery")

        assertEquals(36, result.tokens.size)
        assertEquals(3, result.pipeOperatorCount)
        assertEquals(
            listOf(
                "FROM Produce",
                "WHERE item != :varthing",
                listOf(
                    "AGGREGATE",
                    "     COUNT(*) AS num_items,",
                    "     SUM(sales) AS total_sales,",
                    "     dummy_fun(x) AS dummy",
                    "   GROUP BY item",
                ).joinToString("\n"),
                "ORDER BY item DESC",
            ),
            result.stages.map { it.rawSql },
        )
        assertEquals(
            listOf("FROM", "WHERE", "AGGREGATE", "ORDER BY"),
            result.stages.map { it.operator },
        )
    }

    @Test
    fun exampleDocumentSerializesToExpectedShape() {
        val doc = PipeStageSplitter.split(example, dialect = "bigquery").toDocument()

        assertEquals("bigquery", doc.dialect)
        assertEquals(36, doc.tokenCount)
        assertEquals(3, doc.pipeOperatorCount)
        assertEquals(4, doc.rawStages.size)

        val json = doc.toJson()
        // snake_case wire format
        assertEquals(
            true,
            "\"token_count\":36" in json &&
                "\"pipe_operator_count\":3" in json &&
                "\"raw_stages\"" in json &&
                "\"dialect\":\"bigquery\"" in json,
        )
        // round-trips
        assertEquals(doc, Json.decodeFromString(PipeStageDocument.serializer(), json))
    }

    @Test
    fun pipeInsideStringDoesNotSplit() {
        val result = PipeStageSplitter.split("FROM t |> WHERE x = 'a |> b'")
        assertEquals(2, result.stages.size)
        assertEquals(1, result.pipeOperatorCount)
        assertEquals("WHERE x = 'a |> b'", result.stages[1].rawSql)
    }

    @Test
    fun pipeInsideCommentsDoesNotSplit() {
        val result = PipeStageSplitter.split("FROM t /* |> nope */ |> WHERE x -- |> also nope")
        assertEquals(2, result.stages.size)
        assertEquals(1, result.pipeOperatorCount)
        assertEquals(listOf("FROM t", "WHERE x"), result.stages.map { it.rawSql })
    }

    @Test
    fun pipeInsideQuotedIdentifierDoesNotSplit() {
        val result = PipeStageSplitter.split("FROM t |> SELECT \"weird|>col\"")
        assertEquals(2, result.stages.size)
        assertEquals("SELECT \"weird|>col\"", result.stages[1].rawSql)
    }

    @Test
    fun subpipelineInParensStaysInsideStage() {
        val sql = "FROM (FROM x |> SELECT y) AS sub |> WHERE z > 1"
        val result = PipeStageSplitter.split(sql)
        assertEquals(2, result.stages.size)
        assertEquals(1, result.pipeOperatorCount) // only the depth-0 operator counts
        assertEquals("FROM (FROM x |> SELECT y) AS sub", result.stages[0].rawSql)
        assertEquals("WHERE z > 1", result.stages[1].rawSql)
    }

    @Test
    fun singlePipeAndConcatAreNotStageBoundaries() {
        val result = PipeStageSplitter.split("FROM t |> SELECT a | b AS bits, c || d AS joined")
        assertEquals(2, result.stages.size)
        assertEquals(1, result.pipeOperatorCount)
    }

    @Test
    fun noPipesYieldsSingleStage() {
        val result = PipeStageSplitter.split("SELECT a, b FROM t WHERE x = 1")
        assertEquals(1, result.stages.size)
        assertEquals(0, result.pipeOperatorCount)
        assertEquals("SELECT", result.stages[0].operator)
    }

    @Test
    fun setOperatorStagesAreClassified() {
        val result = PipeStageSplitter.split(
            "FROM t |> UNION ALL (SELECT 1) |> LEFT OUTER INTERSECT ALL BY NAME (SELECT 2)"
        )
        assertEquals(listOf("FROM", "UNION", "INTERSECT"), result.stages.map { it.operator })
    }

    @Test
    fun joinStagesAreClassified() {
        val result = PipeStageSplitter.split(
            "FROM t |> LEFT JOIN u ON t.id = u.id |> CROSS JOIN v |> JOIN w USING (id)"
        )
        assertEquals(listOf("FROM", "JOIN", "JOIN", "JOIN"), result.stages.map { it.operator })
    }

    @Test
    fun recursiveUnionIsClassified() {
        val result = PipeStageSplitter.split("FROM t |> RECURSIVE UNION ALL (SELECT 1)")
        assertEquals(listOf("FROM", "RECURSIVE UNION"), result.stages.map { it.operator })
    }

    @Test
    fun operatorsSqlglotDoesNotSupportStillSplit() {
        // SET / DROP / RENAME / CALL / TEE are GoogleSQL pipe operators that sqlglot cannot
        // parse; Phase 0 stage-splitting handles them fine since it needs no grammar.
        val sql = "FROM t |> SET x = 1 |> DROP y |> RENAME a AS b |> CALL f(z) |> TEE"
        val result = PipeStageSplitter.split(sql)
        assertEquals(
            listOf("FROM", "SET", "DROP", "RENAME", "CALL", "TEE"),
            result.stages.map { it.operator },
        )
    }

    @Test
    fun stageOffsetsSliceOriginalSql() {
        val result = PipeStageSplitter.split(example, dialect = "bigquery")
        for (stage in result.stages) {
            assertEquals(stage.rawSql, example.substring(stage.start, stage.endInclusive + 1))
        }
    }
}
