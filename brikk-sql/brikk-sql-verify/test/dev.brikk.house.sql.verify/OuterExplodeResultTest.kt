package dev.brikk.house.sql.verify

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.annotateTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.TimeUnit
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OuterExplodeResultTest {
    private fun check(sql: String, columns: List<String>, expected: List<String>, annotate: Boolean = true, read: String = "spark") {
        val source = Dialects.forName(read).parseOne(sql)
        val tree = if (annotate) annotateTypes(source, dialect = read) else source
        for (target in listOf("presto", "trino")) {
            val generated = Dialects.forName(target).generate(tree, sourceDialect = read)
            assertFalse(generated.contains("EXPLODE"), generated)
            assertTrue(SqlVerifiers.forEngine("trino")!!.verify(generated).accepted, generated)
            assertEquals(columns, Dialects.forName(target).parseOne(generated).selects.map(Expression::aliasOrName), generated)

            // Opt-in real-engine gate: BRIKK_TRINO_CONTAINER=<Trino 483 container>.
            // Ordinary test runs still check target grammar and output names.
            val container = System.getenv("BRIKK_TRINO_CONTAINER") ?: continue
            val process = ProcessBuilder("docker", "exec", container, "trino", "--execute", generated,
                "--output-format", "JSON").redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Trino query timed out: $generated")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), "$generated\n$output")
                val actual = output.lineSequence().filter { it.startsWith("{") }.map { Json.parseToJsonElement(it).jsonObject }.toList()
                val wanted = expected.map { Json.parseToJsonElement(it).jsonObject }
                assertEquals(wanted.groupingBy { it }.eachCount(), actual.groupingBy { it }.eachCount(), generated)
            } finally {
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun outerArraysKeepEmptyNullAndNonemptyRows() {
        for (arg in listOf("CAST(ARRAY() AS ARRAY<INT>)", "CAST(NULL AS ARRAY<INT>)")) {
            check("SELECT EXPLODE_OUTER($arg) AS x", listOf("x"), listOf("{\"x\":null}"), annotate = false)
        }
        check("SELECT EXPLODE_OUTER(ARRAY(2, 3)) AS x", listOf("x"), listOf("{\"x\":2}", "{\"x\":3}"))
        check("SELECT id, EXPLODE_OUTER(a) AS x FROM (SELECT 7 AS id, CAST(ARRAY() AS ARRAY<INT>) AS a) AS t",
            listOf("id", "x"), listOf("{\"id\":7,\"x\":null}"))
        check("SELECT id, EXPLODE_OUTER(a) AS x FROM (VALUES (1, ARRAY(4, 5)), " +
            "(2, CAST(ARRAY() AS ARRAY<INT>)), (3, CAST(NULL AS ARRAY<INT>))) AS t(id, a)",
            listOf("id", "x"), listOf("{\"id\":1,\"x\":4}", "{\"id\":1,\"x\":5}",
                "{\"id\":2,\"x\":null}", "{\"id\":3,\"x\":null}"))
    }

    @Test
    fun outerPositionsAreZeroBasedAndNullForMissingRows() {
        for (arg in listOf("CAST(ARRAY() AS ARRAY<INT>)", "CAST(NULL AS ARRAY<INT>)")) {
            check("SELECT POSEXPLODE_OUTER($arg) AS (p, v)", listOf("p", "v"), listOf("{\"p\":null,\"v\":null}"))
        }
        check("SELECT POSEXPLODE_OUTER(ARRAY(2, 3)) AS (p, v)", listOf("p", "v"),
            listOf("{\"p\":0,\"v\":2}", "{\"p\":1,\"v\":3}"))
        check("SELECT POSEXPLODE_OUTER(ARRAY(CAST(NULL AS INT), 3))", listOf("pos", "col"),
            listOf("{\"pos\":0,\"col\":null}", "{\"pos\":1,\"col\":3}"))
    }

    @Test
    fun internalAliasesDoNotCaptureSourceColumnsOrChangeDefaultNames() {
        check("SELECT EXPLODE_OUTER(col) FROM (SELECT ARRAY(2, 3) AS col) AS t",
            listOf("col"), listOf("{\"col\":2}", "{\"col\":3}"))
        check("SELECT EXPLODE_OUTER(MAP(2, 3)) AS (k, v) FROM (SELECT 1 AS k) AS t WHERE k = 1",
            listOf("k", "v"), listOf("{\"k\":2,\"v\":3}"))
        check("SELECT POSEXPLODE_OUTER(MAP(2, 3)) ORDER BY pos", listOf("pos", "key", "value"),
            listOf("{\"pos\":0,\"key\":2,\"value\":3}"))
    }

    @Test
    fun outerMapsKeepBothColumnsAndOptionalPosition() {
        for (arg in listOf("CAST(MAP() AS MAP<INT, INT>)", "CAST(NULL AS MAP<INT, INT>)", "MAP(2, 3)")) {
            val nonempty = arg == "MAP(2, 3)"
            check("SELECT EXPLODE_OUTER($arg) AS (k, v)", listOf("k", "v"),
                listOf(if (nonempty) "{\"k\":2,\"v\":3}" else "{\"k\":null,\"v\":null}"))
            check("SELECT POSEXPLODE_OUTER($arg) AS (p, k, v)", listOf("p", "k", "v"),
                listOf(if (nonempty) "{\"p\":0,\"k\":2,\"v\":3}" else "{\"p\":null,\"k\":null,\"v\":null}"))
        }
        check("SELECT t.id, EXPLODE_OUTER(t.m) AS (k, v) FROM " +
            "(SELECT 7 AS id, CAST(NULL AS MAP<INT, INT>) AS m) AS t", listOf("id", "k", "v"),
            listOf("{\"id\":7,\"k\":null,\"v\":null}"))
    }

    @Test
    fun zippedEmptyInputDocumentsSeparateInheritedRowLoss() {
        // The shared upstream zip algorithm cross-joins each UNNEST. An empty
        // non-outer input still annihilates the row; outer normalization cannot fix it.
        val source = "SELECT UNNEST(CAST([] AS INT[])) AS x, UNNEST([1]) AS y"
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(source).use { result ->
                    assertTrue(result.next())
                    assertEquals(null, result.getObject(1))
                    assertEquals(1, result.getInt(2))
                    assertFalse(result.next())
                }
            }
        }
        check(source, listOf("x", "y"), emptyList(), read = "duckdb")
    }
}
